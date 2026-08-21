package com.equitycart.portfolio.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.commons.exception.InsufficientSharesException;
import com.equitycart.portfolio.async.event.PortfolioOutboxWriter;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.metrics.PortfolioMetrics;
import com.equitycart.portfolio.repository.HoldingRepository;
import com.equitycart.portfolio.repository.PortfolioRepository;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.service.api.VestingHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceImplTest {

  @Mock private PortfolioRepository portfolioRepository;
  @Mock private HoldingRepository holdingRepository;
  @Mock private StockBackRewardRepository stockBackRewardRepository;
  @Mock private VestingHelper vestingHelper;
  @Mock private PortfolioEventStore portfolioEventStore;
  @Mock private PortfolioOutboxWriter portfolioOutboxWriter;
  @Mock private PortfolioMetrics portfolioMetrics;

  @InjectMocks private PortfolioServiceImpl portfolioService;

  @Test
  void getOrCreatePortfolioShouldReturnExistingPortfolio() {
    Portfolio existing = Portfolio.builder().userId(7L).build();
    when(portfolioRepository.findByUserId(7L)).thenReturn(Optional.of(existing));

    Portfolio result = portfolioService.getOrCreatePortfolio(7L);

    assertSame(existing, result);
    verify(portfolioRepository, never()).save(any());
  }

  @Test
  void getOrCreatePortfolioShouldCreateWhenMissing() {
    Portfolio created = Portfolio.builder().userId(8L).build();
    when(portfolioRepository.findByUserId(8L)).thenReturn(Optional.empty());
    when(portfolioRepository.save(any(Portfolio.class))).thenReturn(created);

    Portfolio result = portfolioService.getOrCreatePortfolio(8L);

    assertSame(created, result);
    verify(portfolioRepository).save(any(Portfolio.class));
  }

  @Test
  void addOrUpdateHoldingShouldRecalculateWeightedAverageForExistingHolding() {
    Portfolio portfolio = Portfolio.builder().userId(11L).build();
    Holding existing =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("2"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(portfolio)
            .build();

    when(portfolioRepository.findByUserId(11L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(existing));
    when(holdingRepository.save(any(Holding.class))).thenAnswer(inv -> inv.getArgument(0));

    Holding saved =
        portfolioService.addOrUpdateHolding(
            11L, "AAPL", new BigDecimal("1"), new BigDecimal("130"));

    assertEquals(new BigDecimal("3"), saved.getQuantity());
    assertEquals(0, new BigDecimal("110").compareTo(saved.getAverageBuyPrice()));
  }

  @Test
  void addOrUpdateHoldingShouldCreateNewHoldingWhenTickerAbsent() {
    Portfolio portfolio = Portfolio.builder().userId(12L).build();
    when(portfolioRepository.findByUserId(12L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "MSFT"))
        .thenReturn(Optional.empty());
    when(holdingRepository.save(any(Holding.class))).thenAnswer(inv -> inv.getArgument(0));

    Holding saved =
        portfolioService.addOrUpdateHolding(12L, "MSFT", new BigDecimal("2"), new BigDecimal("50"));

    assertEquals("MSFT", saved.getTickerSymbol());
    assertEquals(0, new BigDecimal("2").compareTo(saved.getQuantity()));
  }

  @Test
  void addOrUpdateHoldingShouldRetryOnOptimisticLockConflict() {
    Portfolio portfolio = Portfolio.builder().userId(13L).build();
    when(portfolioRepository.findByUserId(13L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(Holding.builder().tickerSymbol("AAPL").quantity(new BigDecimal("1")).averageBuyPrice(new BigDecimal("10")).portfolio(portfolio).build()));
    when(holdingRepository.save(any(Holding.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"))
        .thenAnswer(inv -> inv.getArgument(0));

    Holding saved =
        portfolioService.addOrUpdateHolding(13L, "AAPL", new BigDecimal("1"), new BigDecimal("10"));

    assertEquals(0, new BigDecimal("3").compareTo(saved.getQuantity()));
    verify(holdingRepository, times(2)).save(any(Holding.class));
  }

  @Test
  void addOrUpdateHoldingShouldThrowAfterExhaustingRetryAttempts() {
    Portfolio portfolio = Portfolio.builder().userId(14L).build();
    when(portfolioRepository.findByUserId(14L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(
            Optional.of(
                Holding.builder()
                    .tickerSymbol("AAPL")
                    .quantity(new BigDecimal("1"))
                    .averageBuyPrice(new BigDecimal("10"))
                    .portfolio(portfolio)
                    .build()));
    when(holdingRepository.save(any(Holding.class)))
        .thenThrow(new OptimisticLockingFailureException("conflict"));

    assertThrows(
        RuntimeException.class,
        () -> portfolioService.addOrUpdateHolding(14L, "AAPL", new BigDecimal("1"), new BigDecimal("10")));
    verify(holdingRepository, times(3)).save(any(Holding.class));
  }

  @Test
  void grantRewardShouldReturnExistingRewardWithoutWritingEvents() {
    StockBackReward existing =
        StockBackReward.builder()
            .orderId(31L)
            .userId(9L)
            .tickerSymbol("MSFT")
            .sharesEarned(new BigDecimal("0.2"))
            .dollarValue(new BigDecimal("20"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().plusDays(30))
            .build();

    when(stockBackRewardRepository.findByOrderIdAndTickerSymbol(31L, "MSFT"))
        .thenReturn(Optional.of(existing));

    StockBackReward result =
        portfolioService.grantReward(
            31L,
            9L,
            "MSFT",
            new BigDecimal("0.2"),
            new BigDecimal("20"),
            LocalDateTime.now().plusDays(30));

    assertSame(existing, result);
    verify(stockBackRewardRepository, never()).save(any(StockBackReward.class));
    verify(portfolioEventStore, never())
        .append(any(), any(PortfolioEventType.class), any(), any(), any(), any(), any());
    verify(portfolioOutboxWriter, never()).writeRewardGrantedEvent(any());
  }

  @Test
  void grantRewardShouldPersistAndPublishWhenNewReward() {
    when(stockBackRewardRepository.findByOrderIdAndTickerSymbol(41L, "AAPL"))
        .thenReturn(Optional.empty());
    when(stockBackRewardRepository.save(any(StockBackReward.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    StockBackReward reward =
        portfolioService.grantReward(
            41L, 2L, "AAPL", new BigDecimal("0.1"), new BigDecimal("10"), LocalDateTime.now().plusDays(1));

    assertEquals(VestingStatus.PENDING, reward.getStatus());
    verify(portfolioEventStore)
        .append(any(), any(PortfolioEventType.class), any(), any(), any(), any(), any());
    verify(portfolioOutboxWriter).writeRewardGrantedEvent(any(StockBackReward.class));
    verify(portfolioMetrics).recordRewardGranted();
  }

  @Test
  void reduceHoldingShouldThrowWhenHoldingNotFound() {
    Portfolio portfolio = Portfolio.builder().userId(3L).build();
    when(portfolioRepository.findByUserId(3L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "TSLA"))
        .thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> portfolioService.reduceHolding(3L, "TSLA", new BigDecimal("1")));
  }

  @Test
  void reduceHoldingShouldDeleteHoldingWhenQuantityBecomesZero() {
    Portfolio portfolio = Portfolio.builder().userId(4L).build();
    Holding holding =
        Holding.builder()
            .tickerSymbol("NVDA")
            .quantity(new BigDecimal("2"))
            .averageBuyPrice(new BigDecimal("300"))
            .portfolio(portfolio)
            .build();

    when(portfolioRepository.findByUserId(4L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "NVDA"))
        .thenReturn(Optional.of(holding));

    Holding result = portfolioService.reduceHolding(4L, "NVDA", new BigDecimal("2"));

    assertEquals(0, BigDecimal.ZERO.compareTo(result.getQuantity()));
    verify(holdingRepository).delete(holding);
    verify(holdingRepository, never()).save(any(Holding.class));
  }

  @Test
  void reduceHoldingShouldThrowWhenQuantityInsufficient() {
    Portfolio portfolio = Portfolio.builder().userId(5L).build();
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(portfolio)
            .build();
    when(portfolioRepository.findByUserId(5L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(holding));

    assertThrows(
        InsufficientSharesException.class,
        () -> portfolioService.reduceHolding(5L, "AAPL", new BigDecimal("2")));
  }

  @Test
  void reduceHoldingShouldSaveWhenQuantityRemainsPositive() {
    Portfolio portfolio = Portfolio.builder().userId(6L).build();
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("3"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(portfolio)
            .build();
    when(portfolioRepository.findByUserId(6L)).thenReturn(Optional.of(portfolio));
    when(holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL"))
        .thenReturn(Optional.of(holding));
    when(holdingRepository.save(any(Holding.class))).thenAnswer(inv -> inv.getArgument(0));

    Holding result = portfolioService.reduceHolding(6L, "AAPL", new BigDecimal("1"));

    assertEquals(0, new BigDecimal("2").compareTo(result.getQuantity()));
    verify(holdingRepository).save(holding);
  }

  @Test
  void vestPendingRewardsShouldContinueWhenRepositoryThrows() {
    when(stockBackRewardRepository.findByStatusAndVestingDateBefore(
            any(VestingStatus.class), any(LocalDateTime.class)))
        .thenThrow(new RuntimeException("db unavailable"));

    assertDoesNotThrow(() -> portfolioService.vestPendingRewards());
  }

  @Test
  void vestPendingRewardsShouldDelegateEachPendingReward() {
    StockBackReward r1 =
        StockBackReward.builder()
            .orderId(1L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().minusDays(1))
            .build();
    StockBackReward r2 =
        StockBackReward.builder()
            .orderId(2L)
            .userId(1L)
            .tickerSymbol("MSFT")
            .sharesEarned(new BigDecimal("0.2"))
            .dollarValue(new BigDecimal("20"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().minusDays(1))
            .build();

    when(stockBackRewardRepository.findByStatusAndVestingDateBefore(
            any(VestingStatus.class), any(LocalDateTime.class)))
        .thenReturn(List.of(r1, r2));

    portfolioService.vestPendingRewards();

    verify(vestingHelper).vestSingleReward(r1);
    verify(vestingHelper).vestSingleReward(r2);
  }

  @Test
  void getRewardsShouldDelegateToRepository() {
    when(stockBackRewardRepository.findByUserId(44L)).thenReturn(List.of());
    assertEquals(0, portfolioService.getRewards(44L).size());
  }
}
