package com.equitycart.portfolio.saga.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import com.equitycart.portfolio.saga.event.GiftSagaOutboxWriter;
import com.equitycart.portfolio.saga.repository.GiftSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GiftSagaOrchestratorTest {

  @Mock private GiftSagaRepository giftSagaRepository;
  @Mock private GiftSagaOutboxWriter giftSagaOutboxWriter;
  @Mock private PortfolioService portfolioService;
  @Mock private LedgerService ledgerService;

  @InjectMocks private GiftSagaOrchestrator orchestrator;

  @Test
  void startGiftShouldReturnExistingSagaForDuplicateIdempotencyKey() {
    GiftRequest request = new GiftRequest(2L, "AAPL", new BigDecimal("1"), "idem-1");
    GiftSaga existing = GiftSaga.builder().sagaId(UUID.randomUUID()).status(GiftSagaStatus.COMPLETED).build();
    when(giftSagaRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

    GiftSaga result = orchestrator.startGift(1L, request);

    assertSame(existing, result);
  }

  @Test
  void startGiftShouldCompleteHappyPath() {
    GiftRequest request = new GiftRequest(2L, "AAPL", new BigDecimal("1"), "idem-2");
    Portfolio giverPortfolio =
        Portfolio.builder()
            .userId(1L)
            .holdings(
                List.of(
                    Holding.builder()
                        .tickerSymbol("AAPL")
                        .quantity(new BigDecimal("5"))
                        .averageBuyPrice(new BigDecimal("100"))
                        .build()))
            .build();

    when(giftSagaRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.empty());
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(giverPortfolio);
    when(giftSagaRepository.save(any(GiftSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(1L, "AAPL", new BigDecimal("1")))
        .thenReturn(Holding.builder().build());
    when(portfolioService.addOrUpdateHolding(2L, "AAPL", new BigDecimal("1"), new BigDecimal("100")))
        .thenReturn(Holding.builder().build());

    GiftSaga saga = orchestrator.startGift(1L, request);

    assertEquals(GiftSagaStatus.COMPLETED, saga.getStatus());
    verify(ledgerService).recordTransaction(any(), any(), eq(new BigDecimal("100")), any(), any(), any());
    verify(giftSagaOutboxWriter).writeGiftLifeCycleEvent(any(GiftSaga.class), eq("GIFT_SAGA_COMPLETED"), eq(null));
  }

  @Test
  void startGiftShouldCompensateWhenReceiverCreditFails() {
    GiftRequest request = new GiftRequest(2L, "MSFT", new BigDecimal("2"), "idem-3");
    Portfolio giverPortfolio =
        Portfolio.builder()
            .userId(1L)
            .holdings(
                List.of(
                    Holding.builder()
                        .tickerSymbol("MSFT")
                        .quantity(new BigDecimal("5"))
                        .averageBuyPrice(new BigDecimal("50"))
                        .build()))
            .build();

    when(giftSagaRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty());
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(giverPortfolio);
    when(giftSagaRepository.save(any(GiftSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(1L, "MSFT", new BigDecimal("2")))
        .thenReturn(Holding.builder().build());
    when(portfolioService.addOrUpdateHolding(2L, "MSFT", new BigDecimal("2"), new BigDecimal("50")))
        .thenThrow(new RuntimeException("receiver update failed"));

    assertThrows(RuntimeException.class, () -> orchestrator.startGift(1L, request));

    verify(portfolioService).addOrUpdateHolding(1L, "MSFT", new BigDecimal("2"), new BigDecimal("50"));
    verify(giftSagaOutboxWriter).writeGiftLifeCycleEvent(any(GiftSaga.class), eq("GIFT_SAGA_COMPENSATED"), eq(null));
  }

  @Test
  void startGiftShouldFailForSelfGift() {
    GiftRequest request = new GiftRequest(1L, "AAPL", new BigDecimal("1"), "idem-4");
    when(giftSagaRepository.findByIdempotencyKey("idem-4")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> orchestrator.startGift(1L, request));
  }

  @Test
  void startGiftShouldCompensateWhenLedgerFails() {
    GiftRequest request = new GiftRequest(2L, "MSFT", new BigDecimal("1"), "idem-5");
    Portfolio giverPortfolio =
        Portfolio.builder()
            .userId(1L)
            .holdings(
                List.of(
                    Holding.builder()
                        .tickerSymbol("MSFT")
                        .quantity(new BigDecimal("5"))
                        .averageBuyPrice(new BigDecimal("50"))
                        .build()))
            .build();

    when(giftSagaRepository.findByIdempotencyKey("idem-5")).thenReturn(Optional.empty());
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(giverPortfolio);
    when(giftSagaRepository.save(any(GiftSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(portfolioService.reduceHolding(1L, "MSFT", new BigDecimal("1")))
        .thenReturn(Holding.builder().build());
    when(portfolioService.addOrUpdateHolding(2L, "MSFT", new BigDecimal("1"), new BigDecimal("50")))
        .thenReturn(Holding.builder().build());
    org.mockito.Mockito.doThrow(new RuntimeException("ledger down"))
        .when(ledgerService)
        .recordTransaction(any(), any(), any(), any(), any(), any());

    assertThrows(RuntimeException.class, () -> orchestrator.startGift(1L, request));
    verify(giftSagaOutboxWriter).writeGiftLifeCycleEvent(any(GiftSaga.class), eq("GIFT_SAGA_COMPENSATED"), eq(null));
  }

  @Test
  void detectTimedOutSagasShouldProcessTimedOutStates() {
    GiftSaga timedOut =
        GiftSaga.builder()
            .sagaId(UUID.randomUUID())
            .giverUserId(1L)
            .receiverUserId(2L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .transferPricePerShare(new BigDecimal("10"))
            .transferDollarValue(new BigDecimal("10"))
            .status(GiftSagaStatus.GIVER_DEBITED)
            .build();
    timedOut.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    GiftSaga timedOutBeforeStep =
        GiftSaga.builder()
            .sagaId(UUID.randomUUID())
            .giverUserId(1L)
            .receiverUserId(2L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .transferPricePerShare(new BigDecimal("10"))
            .transferDollarValue(new BigDecimal("10"))
            .status(GiftSagaStatus.INITIATED)
            .build();
    timedOutBeforeStep.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    when(giftSagaRepository.findByStatusNotInAndUpdatedAtBefore(any(List.class), any()))
        .thenReturn(List.of(timedOut, timedOutBeforeStep));
    when(giftSagaRepository.save(any(GiftSaga.class))).thenAnswer(inv -> inv.getArgument(0));

    orchestrator.detectTimedOutSagas();

    verify(giftSagaOutboxWriter).writeGiftLifeCycleEvent(any(GiftSaga.class), eq("GIFT_SAGA_FAILED"), any());
  }

  @Test
  void startGiftShouldFailForNonPositiveQuantity() {
    GiftRequest request = new GiftRequest(2L, "AAPL", BigDecimal.ZERO, "idem-6");
    when(giftSagaRepository.findByIdempotencyKey("idem-6")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> orchestrator.startGift(1L, request));
  }

  @Test
  void startGiftShouldFailWhenAverageBuyPriceInvalid() {
    GiftRequest request = new GiftRequest(2L, "AAPL", new BigDecimal("1"), "idem-7");
    Portfolio giverPortfolio =
        Portfolio.builder()
            .userId(1L)
            .holdings(
                List.of(
                    Holding.builder()
                        .tickerSymbol("AAPL")
                        .quantity(new BigDecimal("5"))
                        .averageBuyPrice(BigDecimal.ZERO)
                        .build()))
            .build();
    when(giftSagaRepository.findByIdempotencyKey("idem-7")).thenReturn(Optional.empty());
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(giverPortfolio);

    assertThrows(IllegalStateException.class, () -> orchestrator.startGift(1L, request));
  }

  @Test
  void detectTimedOutSagasShouldCompensateThreeCompletedSteps() {
    GiftSaga timedOut =
        GiftSaga.builder()
            .sagaId(UUID.randomUUID())
            .giverUserId(1L)
            .receiverUserId(2L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .transferPricePerShare(new BigDecimal("10"))
            .transferDollarValue(new BigDecimal("10"))
            .status(GiftSagaStatus.LEDGER_RECORDED)
            .build();
    timedOut.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    when(giftSagaRepository.findByStatusNotInAndUpdatedAtBefore(any(List.class), any()))
        .thenReturn(List.of(timedOut));
    when(giftSagaRepository.save(any(GiftSaga.class))).thenAnswer(inv -> inv.getArgument(0));

    orchestrator.detectTimedOutSagas();

    verify(ledgerService).recordTransaction(any(), any(), eq(new BigDecimal("10")), any(), any(), any());
    verify(giftSagaOutboxWriter).writeGiftLifeCycleEvent(any(GiftSaga.class), eq("GIFT_SAGA_COMPENSATED"), eq(null));
  }
}
