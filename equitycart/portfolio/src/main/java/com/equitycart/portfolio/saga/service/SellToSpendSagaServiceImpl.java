package com.equitycart.portfolio.saga.service;

import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.service.api.OrderService;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator;
import com.equitycart.portfolio.service.api.SellToSpendService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Saga-based implementation of {@link SellToSpendService}. Activated when {@code
 * equitycart.sell-to-spend.strategy=saga} — delegates to the {@link SellToSpendSagaOrchestrator}
 * which drives each step independently with compensating transactions on failure.
 *
 * <p>Validates all preconditions (order ownership, status, proceeds coverage) <b>before</b>
 * creating the saga — fast-fail without wasting a saga row. The orchestrator assumes preconditions
 * are already met and focuses purely on step coordination.
 *
 * <p>Coexists with the transactional implementation via {@link
 * org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} — only one bean is active
 * at runtime.
 *
 * @see com.equitycart.portfolio.service.impl.SellToSpendServiceImpl
 * @see SellToSpendSagaOrchestrator
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "equitycart.sell-to-spend.strategy", havingValue = "saga")
public class SellToSpendSagaServiceImpl implements SellToSpendService {

  private static final Logger logger = LogManager.getLogger(SellToSpendSagaServiceImpl.class);

  private final OrderService orderService;
  private final SellToSpendSagaOrchestrator sellToSpendSagaOrchestrator;

  @Override
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

    SellToSpendSaga sellToSpendSaga = sellToSpendSagaOrchestrator.executeSaga(userId, request);

    return new SellToSpendResponse(
        sellToSpendSaga.getOrderId(),
        sellToSpendSaga.getTickerSymbol(),
        sellToSpendSaga.getQuantity(),
        sellToSpendSaga.getSaleProceeds(),
        OrderStatus.CONFIRMED.name());
  }
}
