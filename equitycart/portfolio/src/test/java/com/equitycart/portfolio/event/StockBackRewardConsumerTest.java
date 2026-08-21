package com.equitycart.portfolio.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.dto.BrandTickerMappingDTO;
import com.equitycart.commons.dto.ProductDTO;
import com.equitycart.commons.event.OrderDeliveredEvent;
import com.equitycart.commons.event.OrderItemEvent;
import com.equitycart.commons.event.OrderRefundedEvent;
import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.commons.feign.ProductFeignClient;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.service.api.MarketDataService;
import com.equitycart.portfolio.async.event.PortfolioOutboxWriter;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import com.equitycart.portfolio.saga.orchestrator.ClawbackSagaOrchestrator;
import com.equitycart.portfolio.saga.repository.SellToSpendSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockBackRewardConsumerTest {

  @Mock private PortfolioService portfolioService;
  @Mock private ProductFeignClient productFeignClient;
  @Mock private MarketDataService marketDataService;
  @Mock private StockBackRewardRepository stockBackRewardRepository;
  @Mock private LedgerService ledgerService;
  @Mock private SellToSpendSagaRepository sellToSpendSagaRepository;
  @Mock private PortfolioEventStore portfolioEventStore;
  @Mock private PortfolioOutboxWriter portfolioOutboxWriter;
  @Mock private ClawbackSagaOrchestrator clawbackSagaOrchestrator;

  @InjectMocks private StockBackRewardConsumer consumer;

  @Test
  void handleOrderDeliveredEventShouldAggregateByTickerAndGrantReward() {
    OrderItemEvent item1 =
        new OrderItemEvent(11L, "Product-1", 1, new BigDecimal("100"), new BigDecimal("100"));
    OrderItemEvent item2 =
        new OrderItemEvent(12L, "Product-2", 1, new BigDecimal("50"), new BigDecimal("50"));
    OrderDeliveredEvent event =
        new OrderDeliveredEvent(
            99L, 7L, List.of(item1, item2), new BigDecimal("150"), LocalDateTime.now());

    when(productFeignClient.getProductById(11L))
        .thenReturn(new ProductDTO(11L, "P1", new BigDecimal("100"), 10, 200L, true));
    when(productFeignClient.getProductById(12L))
        .thenReturn(new ProductDTO(12L, "P2", new BigDecimal("50"), 10, 200L, true));
    when(productFeignClient.getTickerMappingsByBrandId(200L))
        .thenReturn(List.of(new BrandTickerMappingDTO(200L, "AAPL", new BigDecimal("10"))));
    when(marketDataService.getPrice("AAPL"))
        .thenReturn(
            new StockPriceResponse(
                "AAPL", new BigDecimal("200"), BigDecimal.ZERO, "0%", 1000L, "2026-01-01", Instant.now()));

    consumer.handleOrderDeliveredEvent(event);

    ArgumentCaptor<BigDecimal> sharesCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    ArgumentCaptor<BigDecimal> dollarCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(portfolioService)
        .grantReward(
            eq(99L),
            eq(7L),
            eq("AAPL"),
            sharesCaptor.capture(),
            dollarCaptor.capture(),
            any(LocalDateTime.class));
    assertEquals(0, new BigDecimal("15").compareTo(dollarCaptor.getValue()));
    assertEquals(0, new BigDecimal("0.075000").compareTo(sharesCaptor.getValue()));
  }

  @Test
  void handleOrderReturnedEventShouldCancelPendingRewards() {
    OrderReturnedEvent event = new OrderReturnedEvent(55L, 5L, LocalDateTime.now());
    StockBackReward pending =
        StockBackReward.builder()
            .orderId(55L)
            .userId(5L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().plusDays(1))
            .build();
    when(stockBackRewardRepository.findByOrderId(55L)).thenReturn(List.of(pending));

    consumer.handleOrderReturnedEvent(event);

    assertEquals(VestingStatus.CANCELLED, pending.getStatus());
    verify(stockBackRewardRepository).save(pending);
    verify(portfolioEventStore)
        .append(
            eq(5L),
            eq(PortfolioEventType.REWARD_CANCELLED),
            eq("AAPL"),
            eq(new BigDecimal("0.1")),
            eq(null),
            eq(null),
            any());
    verify(portfolioOutboxWriter).writeRewardCancelledEvent(pending);
    verify(clawbackSagaOrchestrator, never()).handleOrderReturned(any(), any());
  }

  @Test
  void handleOrderRefundedEventShouldSkipNonStockPayments() {
    OrderRefundedEvent event = new OrderRefundedEvent(77L, 8L, "CARD", LocalDateTime.now());

    consumer.handleOrderRefundedEvent(event);

    verify(sellToSpendSagaRepository, never()).findByOrderIdAndStatus(any(), any());
  }

  @Test
  void handleOrderRefundedEventShouldRestoreStockForCompletedSaga() {
    OrderRefundedEvent event = new OrderRefundedEvent(88L, 9L, "STOCK", LocalDateTime.now());
    SellToSpendSaga saga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(9L)
            .orderId(88L)
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("2"))
            .pricePerShare(new BigDecimal("100"))
            .saleProceeds(new BigDecimal("200"))
            .status(SagaStatus.COMPLETED)
            .build();

    when(sellToSpendSagaRepository.findByOrderIdAndStatus(88L, SagaStatus.COMPLETED))
        .thenReturn(Optional.of(saga));

    consumer.handleOrderRefundedEvent(event);

    verify(portfolioService).addOrUpdateHolding(9L, "MSFT", new BigDecimal("2"), new BigDecimal("100"));
    verify(ledgerService)
        .recordTransaction(
            AccountType.HOLDING_ASSET,
            AccountType.CASH,
            new BigDecimal("200"),
            ReferenceType.SELL_TO_SPEND_REVERSAL,
            88L,
            "REFUND: re-added shares for Order #88");
    verify(sellToSpendSagaRepository).save(saga);
    verify(portfolioOutboxWriter).writeRefundRestoredEvent(saga, 88L);
    assertEquals(true, saga.isRefunded());
  }

  @Test
  void handleOrderDeliveredEventShouldSkipItemWhenProductLookupFails() {
    OrderItemEvent item =
        new OrderItemEvent(99L, "Broken", 1, new BigDecimal("10"), new BigDecimal("10"));
    OrderDeliveredEvent event =
        new OrderDeliveredEvent(100L, 3L, List.of(item), new BigDecimal("10"), LocalDateTime.now());
    when(productFeignClient.getProductById(99L))
        .thenThrow(feign.FeignException.errorStatus("x", feign.Response.builder().status(500).request(feign.Request.create(feign.Request.HttpMethod.GET, "/", java.util.Map.of(), null, null, null)).headers(java.util.Map.of()).build()));

    consumer.handleOrderDeliveredEvent(event);

    verify(portfolioService, never())
        .grantReward(any(), any(), any(), any(), any(), any());
  }

  @Test
  void handleOrderDeliveredEventShouldSkipWhenNoTickerMappings() {
    OrderItemEvent item =
        new OrderItemEvent(11L, "Product-1", 1, new BigDecimal("10"), new BigDecimal("10"));
    OrderDeliveredEvent event =
        new OrderDeliveredEvent(99L, 7L, List.of(item), new BigDecimal("10"), LocalDateTime.now());
    when(productFeignClient.getProductById(11L))
        .thenReturn(new ProductDTO(11L, "P1", new BigDecimal("10"), 10, 200L, true));
    when(productFeignClient.getTickerMappingsByBrandId(200L)).thenReturn(List.of());

    consumer.handleOrderDeliveredEvent(event);

    verify(portfolioService, never()).grantReward(any(), any(), any(), any(), any(), any());
  }

  @Test
  void handleOrderReturnedEventShouldNoopWhenNoRewards() {
    OrderReturnedEvent event = new OrderReturnedEvent(56L, 5L, LocalDateTime.now());
    when(stockBackRewardRepository.findByOrderId(56L)).thenReturn(List.of());

    consumer.handleOrderReturnedEvent(event);

    verify(stockBackRewardRepository, never()).save(any());
  }

  @Test
  void handleOrderReturnedEventShouldInvokeClawbackForVestedReward() {
    OrderReturnedEvent event = new OrderReturnedEvent(57L, 5L, LocalDateTime.now());
    StockBackReward vested =
        StockBackReward.builder()
            .orderId(57L)
            .userId(5L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.VESTED)
            .vestingDate(LocalDateTime.now().minusDays(1))
            .build();
    when(stockBackRewardRepository.findByOrderId(57L)).thenReturn(List.of(vested));

    consumer.handleOrderReturnedEvent(event);

    verify(clawbackSagaOrchestrator).handleOrderReturned(event, vested);
  }

  @Test
  void handleOrderRefundedEventShouldSkipWhenSagaAlreadyRefunded() {
    OrderRefundedEvent event = new OrderRefundedEvent(89L, 9L, "STOCK", LocalDateTime.now());
    SellToSpendSaga saga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(9L)
            .orderId(89L)
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("2"))
            .pricePerShare(new BigDecimal("100"))
            .saleProceeds(new BigDecimal("200"))
            .status(SagaStatus.COMPLETED)
            .isRefunded(true)
            .build();
    when(sellToSpendSagaRepository.findByOrderIdAndStatus(89L, SagaStatus.COMPLETED))
        .thenReturn(Optional.of(saga));

    consumer.handleOrderRefundedEvent(event);

    verify(portfolioService, never()).addOrUpdateHolding(any(), any(), any(), any());
  }
}
