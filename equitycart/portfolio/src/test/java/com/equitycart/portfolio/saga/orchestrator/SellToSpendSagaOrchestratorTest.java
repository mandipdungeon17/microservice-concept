package com.equitycart.portfolio.saga.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.async.event.PortfolioOutboxWriter;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.event.NotificationPublisher;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.feign.OrderFeignClient;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import com.equitycart.portfolio.saga.event.SagaOutboxWriter;
import com.equitycart.portfolio.saga.repository.SellToSpendSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellToSpendSagaOrchestratorTest {

  @Mock private PortfolioService portfolioService;
  @Mock private LedgerService ledgerService;
  @Mock private OrderFeignClient orderFeignClient;
  @Mock private SellToSpendSagaRepository sellToSpendSagaRepository;
  @Mock private SagaOutboxWriter sagaOutboxWriter;
  @Mock private PortfolioEventStore portfolioEventStore;
  @Mock private NotificationPublisher notificationPublisher;
  @Mock private PortfolioOutboxWriter portfolioOutboxWriter;

  @InjectMocks private SellToSpendSagaOrchestrator orchestrator;

  @Test
  void executeSagaShouldCompleteAndPublishEvents() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 90L);
    when(sellToSpendSagaRepository.findByOrderIdAndStatusNotIn(eq(90L), any(List.class)))
        .thenReturn(Optional.empty());
    when(sellToSpendSagaRepository.save(any(SellToSpendSaga.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(1L, "AAPL", new BigDecimal("2")))
        .thenReturn(com.equitycart.portfolio.entity.Holding.builder().build());

    SellToSpendSaga result = orchestrator.executeSaga(1L, request);

    assertEquals(SagaStatus.COMPLETED, result.getStatus());
    verify(portfolioOutboxWriter).writeSellToSpendEvent(any(SellToSpendSaga.class));
    verify(orderFeignClient).updateOrderStatus(eq(90L), any());
    verify(notificationPublisher, org.mockito.Mockito.atLeastOnce()).publish(any());
  }

  @Test
  void executeSagaShouldCompensateWhenLedgerStepFails() {
    SellToSpendRequest request =
        new SellToSpendRequest("TSLA", new BigDecimal("1"), new BigDecimal("200"), 99L);
    when(sellToSpendSagaRepository.findByOrderIdAndStatusNotIn(eq(99L), any(List.class)))
        .thenReturn(Optional.empty());
    when(sellToSpendSagaRepository.save(any(SellToSpendSaga.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(2L, "TSLA", new BigDecimal("1")))
        .thenReturn(com.equitycart.portfolio.entity.Holding.builder().build());
    org.mockito.Mockito.doThrow(new RuntimeException("ledger down"))
        .when(ledgerService)
        .recordTransaction(any(), any(), any(), any(), any(), any());

    assertThrows(RuntimeException.class, () -> orchestrator.executeSaga(2L, request));

    verify(portfolioService).addOrUpdateHolding(2L, "TSLA", new BigDecimal("1"), new BigDecimal("200"));
    verify(portfolioOutboxWriter).writeSellToSpendCompensatedEvent(any(SellToSpendSaga.class));
    verify(notificationPublisher).publish(any());
  }

  @Test
  void executeSagaShouldReturnExistingActiveSaga() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 190L);
    SellToSpendSaga existing = SellToSpendSaga.builder().status(SagaStatus.RECORDING_LEDGER).build();
    when(sellToSpendSagaRepository.findByOrderIdAndStatusNotIn(eq(190L), any(List.class)))
        .thenReturn(Optional.of(existing));

    SellToSpendSaga result = orchestrator.executeSaga(1L, request);

    assertSame(existing, result);
  }

  @Test
  void executeSagaShouldFailWhenStep1Throws() {
    SellToSpendRequest request =
        new SellToSpendRequest("AAPL", new BigDecimal("2"), new BigDecimal("100"), 191L);
    when(sellToSpendSagaRepository.findByOrderIdAndStatusNotIn(eq(191L), any(List.class)))
        .thenReturn(Optional.empty());
    when(sellToSpendSagaRepository.save(any(SellToSpendSaga.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(1L, "AAPL", new BigDecimal("2")))
        .thenThrow(new RuntimeException("reduce failed"));

    assertThrows(RuntimeException.class, () -> orchestrator.executeSaga(1L, request));
    verify(sagaOutboxWriter, org.mockito.Mockito.atLeastOnce())
        .writeSagaLifecycleEvent(any(SellToSpendSaga.class), eq("SAGA_FAILED"), any());
  }

  @Test
  void detectTimedOutSagasShouldCompensateOrFailByStatus() {
    SellToSpendSaga needsCompensation =
        SellToSpendSaga.builder()
            .status(SagaStatus.LEDGER_RECORDED)
            .userId(1L)
            .orderId(200L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .pricePerShare(new BigDecimal("100"))
            .saleProceeds(new BigDecimal("100"))
            .sagaId(java.util.UUID.randomUUID())
            .build();
    needsCompensation.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    SellToSpendSaga failDirectly =
        SellToSpendSaga.builder()
            .status(SagaStatus.STARTED)
            .userId(2L)
            .orderId(201L)
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("1"))
            .pricePerShare(new BigDecimal("100"))
            .saleProceeds(new BigDecimal("100"))
            .sagaId(java.util.UUID.randomUUID())
            .build();
    failDirectly.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    when(sellToSpendSagaRepository.findByStatusNotInAndUpdatedAtBefore(any(List.class), any()))
        .thenReturn(List.of(needsCompensation, failDirectly));
    when(sellToSpendSagaRepository.save(any(SellToSpendSaga.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    orchestrator.detectTimedOutSagas();

    verify(sagaOutboxWriter, org.mockito.Mockito.atLeastOnce())
        .writeSagaLifecycleEvent(any(SellToSpendSaga.class), eq("SAGA_FAILED"), any());
    verify(notificationPublisher, org.mockito.Mockito.atLeastOnce()).publish(any());
  }
}
