package com.equitycart.portfolio.service.impl;

import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.feign.OrderFeignClient;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.SellToSpendService;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional "Sell to Spend" payment flow — coordinates portfolio, ledger, and order services.
 *
 * <p><b>What is Sell to Spend?</b> A payment method where the user sells stock from their portfolio
 * to fund a pending order. Similar to Robinhood's fractional-share spending or Revolut's stock
 * auto-sell at checkout — the user's stock holdings act as a payment source.
 *
 * <p><b>Flow:</b>
 *
 * <pre>
 * 1. User places order → status CREATED (placed, awaiting payment)
 * 2. User calls sell-to-spend → specifies which stock, how many shares, at what price
 * 3. System validates: order belongs to user, order is CREATED, sale proceeds ≥ order total
 * 4. Execution: sell shares + record ledger + confirm order (via OrderFeignClient HTTP call)
 * 5. If portfolio or ledger step fails → local transaction rolls back (no partial local state)
 * </pre>
 *
 * <p><b>Why must the order be in CREATED state?</b> CREATED means "placed but not yet paid" — the
 * only state where accepting payment makes sense. CONFIRMED means already paid; SHIPPED/DELIVERED
 * are post-payment states.
 *
 * <p><b>Why require full payment (proceeds ≥ order total)?</b> Partial payment would require
 * tracking amount-remaining on the order, multiple payment rounds, and multi-source reconciliation
 * — a payments-platform concern beyond this service's scope.
 *
 * <p><b>Distributed transaction limitation (Phase 10):</b> {@code @Transactional} covers local
 * operations (portfolio holding + ledger entry) but does NOT span the {@link
 * com.equitycart.portfolio.feign.OrderFeignClient#updateOrderStatus} HTTP call — that call commits
 * independently in order-service's own transaction. If order confirmation fails after the local
 * transaction has committed, the local state is already durable. The {@link
 * com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator} handles this correctly
 * via explicit compensating transactions and is preferred for production use.
 *
 * <p><b>Active when:</b> {@code equitycart.sell-to-spend.strategy=transactional} or property absent
 * ({@code matchIfMissing=true}). The saga strategy activates when the property is {@code saga}.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "equitycart.sell-to-spend.strategy",
    havingValue = "transactional",
    matchIfMissing = true)
public class SellToSpendServiceImpl implements SellToSpendService {

  private static final Logger logger = LogManager.getLogger(SellToSpendServiceImpl.class);

  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;
  private final OrderFeignClient orderFeignClient;
  private final PortfolioEventStore portfolioEventStore;

  /** {@inheritDoc} */
  @Override
  @Transactional
  public SellToSpendResponse sellToSpend(Long userId, SellToSpendRequest request) {
    OrderResponse orderResponse = orderFeignClient.getOrderById(request.orderId());
    if (!orderResponse.userId().equals(userId)) {
      logger.warn(
          "Sell-to-spend rejected: userId={} does not own orderId={}", userId, request.orderId());
      throw new ResourceNotFoundException("Order not found for user: " + userId);
    }
    if (!orderResponse.status().equals(OrderStatus.CREATED.name())) {
      logger.warn(
          "Sell-to-spend rejected: orderId={} is in state {} (expected CREATED)",
          request.orderId(),
          orderResponse.status());
      throw new InvalidStatusTransitionException(
          "Order is not in a valid state for sell-to-spend: " + orderResponse.status());
    }

    BigDecimal saleProceeds = request.quantity().multiply(request.pricePerShare());

    if (saleProceeds.compareTo(orderResponse.totalAmount()) < 0) {
      logger.warn(
          "Sell-to-spend rejected: proceeds {} < order total {} for orderId={}",
          saleProceeds,
          orderResponse.totalAmount(),
          request.orderId());
      throw new IllegalArgumentException(
          "Sale proceeds ("
              + saleProceeds
              + ") do not cover order total ("
              + orderResponse.totalAmount()
              + ")");
    }

    portfolioService.reduceHolding(userId, request.tickerSymbol(), request.quantity());
    logger.info(
        "Sell-to-spend: sold {} shares of {} for userId={}, proceeds={}",
        request.quantity(),
        request.tickerSymbol(),
        userId,
        saleProceeds);

    portfolioEventStore.append(
        userId,
        PortfolioEventType.SELL_TO_SPEND,
        request.tickerSymbol(),
        request.quantity(),
        request.pricePerShare(),
        saleProceeds,
        Map.of("orderId", request.orderId()));

    ledgerService.recordTransaction(
        AccountType.CASH,
        AccountType.HOLDING_ASSET,
        saleProceeds,
        ReferenceType.SELL_TO_SPEND,
        request.orderId(),
        "SELL_TO_SPEND "
            + request.quantity()
            + " "
            + request.tickerSymbol()
            + " for Order #"
            + request.orderId());

    orderFeignClient.updateOrderStatus(
        request.orderId(), new UpdateOrderStatusRequest(OrderStatus.CONFIRMED.name()));
    logger.info(
        "Sell-to-spend complete: orderId={} confirmed, proceeds={}, ticker={}, sharesSold={}",
        request.orderId(),
        saleProceeds,
        request.tickerSymbol(),
        request.quantity());

    return new SellToSpendResponse(
        request.orderId(),
        request.tickerSymbol(),
        request.quantity(),
        saleProceeds,
        OrderStatus.CONFIRMED.name());
  }
}
