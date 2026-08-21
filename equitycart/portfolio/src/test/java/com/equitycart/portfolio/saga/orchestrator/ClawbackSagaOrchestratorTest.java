package com.equitycart.portfolio.saga.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.repository.HoldingRepository;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.saga.entity.ClawbackSaga;
import com.equitycart.portfolio.saga.enums.ClawbackStatus;
import com.equitycart.portfolio.saga.event.ClawbackOutboxWriter;
import com.equitycart.portfolio.saga.repository.ClawbackSagaRepository;
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
class ClawbackSagaOrchestratorTest {

  @Mock private PortfolioService portfolioService;
  @Mock private LedgerService ledgerService;
  @Mock private StockBackRewardRepository stockBackRewardRepository;
  @Mock private ClawbackSagaRepository clawbackSagaRepository;
  @Mock private ClawbackOutboxWriter clawbackOutboxWriter;
  @Mock private PortfolioEventStore portfolioEventStore;
  @Mock private HoldingRepository holdingRepository;

  @InjectMocks private ClawbackSagaOrchestrator orchestrator;

  @Test
  void handleOrderReturnedShouldReturnWhenActiveSagaAlreadyExists() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward = StockBackReward.builder().orderId(10L).build();
    reward.setId(5L);
    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(5L), any()))
        .thenReturn(
            Optional.of(
                ClawbackSaga.builder().sagaId(UUID.randomUUID()).status(ClawbackStatus.REDUCING_HOLDING).build()));

    orchestrator.handleOrderReturned(event, reward);

    verify(clawbackSagaRepository, never()).save(any(ClawbackSaga.class));
  }

  @Test
  void handleOrderReturnedShouldCompleteClawbackForVestedReward() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.4"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.VESTED)
            .vestingDate(LocalDateTime.now().minusDays(10))
            .build();
    reward.setId(5L);

    Portfolio portfolio = Portfolio.builder().userId(1L).build();
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1.0"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(portfolio)
            .build();

    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(5L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(5L)).thenReturn(Optional.of(reward));
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(portfolio);
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(holding));
    when(portfolioService.reduceHolding(1L, "AAPL", new BigDecimal("0.4")))
        .thenReturn(Holding.builder().build());

    orchestrator.handleOrderReturned(event, reward);

    assertEquals(VestingStatus.CLAWED_BACK, reward.getStatus());
    verify(ledgerService).recordTransaction(any(), any(), eq(new BigDecimal("40")), any(), eq(5L), any());
    verify(stockBackRewardRepository).save(reward);
    verify(portfolioEventStore)
        .append(
            eq(1L),
            eq(PortfolioEventType.REWARD_CLAWED_BACK),
            eq("AAPL"),
            eq(new BigDecimal("0.4")),
            eq(BigDecimal.ZERO),
            eq(new BigDecimal("40")),
            any());
  }

  @Test
  void handleOrderReturnedShouldFailWhenRewardNotVested() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.4"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().plusDays(10))
            .build();
    reward.setId(5L);

    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(5L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(5L)).thenReturn(Optional.of(reward));

    assertThrows(RuntimeException.class, () -> orchestrator.handleOrderReturned(event, reward));
  }

  @Test
  void handleOrderReturnedShouldFailWhenHoldingMissing() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.4"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.VESTED)
            .vestingDate(LocalDateTime.now().minusDays(10))
            .build();
    reward.setId(6L);
    Portfolio portfolio = Portfolio.builder().userId(1L).build();

    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(6L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(6L)).thenReturn(Optional.of(reward));
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(portfolio);
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> orchestrator.handleOrderReturned(event, reward));
  }

  @Test
  void detectTimeoutsShouldCompensateAndFailAppropriately() {
    ClawbackSaga compensate =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(1L)
            .orderId(10L)
            .rewardId(5L)
            .rewardQuantity(new BigDecimal("1"))
            .status(ClawbackStatus.LEDGER_RECORDED)
            .build();
    compensate.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    ClawbackSaga failDirectly =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(1L)
            .orderId(11L)
            .rewardId(6L)
            .rewardQuantity(new BigDecimal("1"))
            .status(ClawbackStatus.INITIATED)
            .build();
    failDirectly.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.VESTED)
            .build();
    reward.setId(5L);

    when(clawbackSagaRepository.findByStatusNotInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(compensate, failDirectly));
    when(stockBackRewardRepository.findById(5L)).thenReturn(Optional.of(reward));
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));

    orchestrator.detectTimeouts();

    verify(clawbackOutboxWriter, org.mockito.Mockito.atLeastOnce())
        .writeClawbackLifeCycleEvent(any(ClawbackSaga.class), eq("CLAWBACK_FAILED"), any());
  }

  @Test
  void handleOrderReturnedShouldFailWhenRewardUserOrOrderMismatch() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(99L)
            .userId(2L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.4"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.VESTED)
            .build();
    reward.setId(7L);
    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(7L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(7L)).thenReturn(Optional.of(reward));

    assertThrows(RuntimeException.class, () -> orchestrator.handleOrderReturned(event, reward));
  }

  @Test
  void handleOrderReturnedShouldFailWhenHoldingInsufficient() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("5"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.VESTED)
            .build();
    reward.setId(8L);
    Portfolio portfolio = Portfolio.builder().userId(1L).build();
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(portfolio)
            .build();
    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(8L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(8L)).thenReturn(Optional.of(reward));
    when(portfolioService.getOrCreatePortfolio(1L)).thenReturn(portfolio);
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(holding));

    assertThrows(RuntimeException.class, () -> orchestrator.handleOrderReturned(event, reward));
  }

  @Test
  void handleOrderReturnedShouldFailWhenRewardOrderMismatch() {
    OrderReturnedEvent event = new OrderReturnedEvent(10L, 1L, LocalDateTime.now());
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(99L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.4"))
            .dollarValue(new BigDecimal("40"))
            .status(VestingStatus.VESTED)
            .build();
    reward.setId(9L);
    when(clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(eq(10L), eq(9L), any()))
        .thenReturn(Optional.empty());
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));
    when(stockBackRewardRepository.findById(9L)).thenReturn(Optional.of(reward));

    assertThrows(RuntimeException.class, () -> orchestrator.handleOrderReturned(event, reward));
  }

  @Test
  void detectTimeoutsShouldMapHoldingReducedToSingleCompletedStep() {
    ClawbackSaga timedOut =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(1L)
            .orderId(12L)
            .rewardId(10L)
            .rewardQuantity(new BigDecimal("1"))
            .status(ClawbackStatus.HOLDING_REDUCED)
            .build();
    timedOut.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(12L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.VESTED)
            .build();
    reward.setId(10L);

    when(clawbackSagaRepository.findByStatusNotInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(timedOut));
    when(stockBackRewardRepository.findById(10L)).thenReturn(Optional.of(reward));
    when(clawbackSagaRepository.save(any(ClawbackSaga.class))).thenAnswer(inv -> inv.getArgument(0));

    orchestrator.detectTimeouts();

    verify(portfolioService).addOrUpdateHolding(1L, "AAPL", new BigDecimal("1"), BigDecimal.ZERO);
  }
}
