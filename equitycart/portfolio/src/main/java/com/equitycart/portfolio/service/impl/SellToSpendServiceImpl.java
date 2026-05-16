package com.equitycart.portfolio.service.impl;

import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.service.api.OrderService;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.SellToSpendService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the "Sell to Spend" payment flow — a cross-domain operation that coordinates
 * portfolio, ledger, and order services within a single atomic transaction.
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
 * 4. Atomic execution: sell shares + record ledger + confirm order
 * 5. If any step fails → entire transaction rolls back (no partial state)
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
 * <p><b>Monolith advantage:</b> one {@code @Transactional} wraps all three service calls. In a
 * microservices architecture with separate databases, this would require a Saga pattern with
 * compensating transactions (e.g., re-add shares if order confirmation fails).
 */
@Service
@RequiredArgsConstructor
public class SellToSpendServiceImpl implements SellToSpendService {

  private static final Logger logger = LogManager.getLogger(SellToSpendServiceImpl.class);

  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;
  private final OrderService orderService;

  /** {@inheritDoc} */
  @Override
  @Transactional
  public SellToSpendResponse sellToSpend(Long userId, SellToSpendRequest request) {
    OrderResponse orderResponse = orderService.getOrderById(request.orderId());
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

    orderService.updateOrderStatus(
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
