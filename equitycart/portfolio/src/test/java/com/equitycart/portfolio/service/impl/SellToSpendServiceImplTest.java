package com.equitycart.portfolio.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellToSpendServiceImplTest {

  @Mock private PortfolioService portfolioService;
  @Mock private LedgerService ledgerService;
  @Mock private OrderFeignClient orderFeignClient;
  @Mock private PortfolioEventStore portfolioEventStore;

  @InjectMocks private SellToSpendServiceImpl sellToSpendService;

  @Test
  void sellToSpendShouldThrowWhenOrderBelongsToAnotherUser() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 80L);
    when(orderFeignClient.getOrderById(80L))
        .thenReturn(
            new OrderResponse(
                80L,
                99L,
                OrderStatus.CREATED.name(),
                new BigDecimal("100"),
                "idem",
                "addr",
                "STOCK",
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now()));

    assertThrows(ResourceNotFoundException.class, () -> sellToSpendService.sellToSpend(1L, request));
  }

  @Test
  void sellToSpendShouldThrowWhenOrderStatusIsNotCreated() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 80L);
    when(orderFeignClient.getOrderById(80L))
        .thenReturn(
            new OrderResponse(
                80L,
                1L,
                OrderStatus.CONFIRMED.name(),
                new BigDecimal("100"),
                "idem",
                "addr",
                "STOCK",
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now()));

    assertThrows(
        InvalidStatusTransitionException.class, () -> sellToSpendService.sellToSpend(1L, request));
  }

  @Test
  void sellToSpendShouldThrowWhenSaleProceedsAreInsufficient() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("1"), new BigDecimal("50"), 80L);
    when(orderFeignClient.getOrderById(80L))
        .thenReturn(
            new OrderResponse(
                80L,
                1L,
                OrderStatus.CREATED.name(),
                new BigDecimal("100"),
                "idem",
                "addr",
                "STOCK",
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now()));

    assertThrows(IllegalArgumentException.class, () -> sellToSpendService.sellToSpend(1L, request));
  }

  @Test
  void sellToSpendShouldExecuteBusinessFlowAndConfirmOrder() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 80L);
    when(orderFeignClient.getOrderById(80L))
        .thenReturn(
            new OrderResponse(
                80L,
                1L,
                OrderStatus.CREATED.name(),
                new BigDecimal("150"),
                "idem",
                "addr",
                "STOCK",
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now()));

    SellToSpendResponse response = sellToSpendService.sellToSpend(1L, request);

    assertEquals(80L, response.orderId());
    assertEquals("CONFIRMED", response.orderStatus());
    assertEquals(0, new BigDecimal("200").compareTo(response.saleProceeds()));

    verify(portfolioService).reduceHolding(1L, "AAPL", new BigDecimal("2"));
    verify(portfolioEventStore)
        .append(
            eq(1L),
            eq(PortfolioEventType.SELL_TO_SPEND),
            eq("AAPL"),
            eq(new BigDecimal("2")),
            eq(new BigDecimal("100")),
            eq(new BigDecimal("200")),
            any());
    verify(ledgerService)
        .recordTransaction(
            AccountType.CASH,
            AccountType.HOLDING_ASSET,
            new BigDecimal("200"),
            ReferenceType.SELL_TO_SPEND,
            80L,
            "SELL_TO_SPEND 2 AAPL for Order #80");
    verify(orderFeignClient)
        .updateOrderStatus(80L, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED.name()));
  }
}

