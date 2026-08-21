package com.equitycart.portfolio.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.async.event.PortfolioOutboxWriter;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.event.NotificationPublisher;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.metrics.PortfolioMetrics;
import com.equitycart.portfolio.service.api.PortfolioService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradeServiceImplTest {

  @Mock private PortfolioService portfolioService;
  @Mock private LedgerService ledgerService;
  @Mock private PortfolioEventStore portfolioEventStore;
  @Mock private NotificationPublisher notificationPublisher;
  @Mock private PortfolioMetrics portfolioMetrics;
  @Mock private PortfolioOutboxWriter portfolioOutboxWriter;

  @InjectMocks private TradeServiceImpl tradeService;

  @Test
  void executeTradeShouldThrowForInvalidTradeType() {
    assertThrows(
        InvalidStatusTransitionException.class,
        () ->
            tradeService.executeTrade(
                1L, "AAPL", new BigDecimal("1"), new BigDecimal("100"), "UNKNOWN"));
  }

  @Test
  void executeTradeShouldRunBuyFlow() {
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("3"))
            .averageBuyPrice(new BigDecimal("105"))
            .portfolio(Portfolio.builder().userId(1L).build())
            .build();
    holding.setId(88L);
    when(portfolioService.addOrUpdateHolding(
            1L, "AAPL", new BigDecimal("2"), new BigDecimal("100")))
        .thenReturn(holding);

    Holding result =
        tradeService.executeTrade(1L, "AAPL", new BigDecimal("2"), new BigDecimal("100"), "BUY");

    assertSame(holding, result);
    verify(ledgerService)
        .recordTransaction(
            AccountType.HOLDING_ASSET,
            AccountType.CASH,
            new BigDecimal("200"),
            ReferenceType.TRADE,
            88L,
            "BUY 2 AAPL @ 100");
    verify(portfolioEventStore)
        .append(
            eq(1L),
            eq(PortfolioEventType.SHARES_PURCHASED),
            eq("AAPL"),
            eq(new BigDecimal("2")),
            eq(new BigDecimal("100")),
            eq(new BigDecimal("200")),
            any());
    verify(portfolioOutboxWriter).writeSharesPurchasedEvent(holding);
    verify(portfolioMetrics).recordTrade("BUY");
    verify(notificationPublisher).publish(any());
  }

  @Test
  void executeTradeShouldRunSellFlow() {
    Holding holding =
        Holding.builder()
            .tickerSymbol("TSLA")
            .quantity(new BigDecimal("4"))
            .averageBuyPrice(new BigDecimal("180"))
            .portfolio(Portfolio.builder().userId(2L).build())
            .build();
    holding.setId(99L);
    when(portfolioService.reduceHolding(2L, "TSLA", new BigDecimal("1.5"))).thenReturn(holding);

    Holding result =
        tradeService.executeTrade(2L, "TSLA", new BigDecimal("1.5"), new BigDecimal("200"), "SELL");

    assertEquals("TSLA", result.getTickerSymbol());
    verify(ledgerService)
        .recordTransaction(
            AccountType.CASH,
            AccountType.HOLDING_ASSET,
            new BigDecimal("300.0"),
            ReferenceType.TRADE,
            99L,
            "SELL 1.5 TSLA @ 200");
    verify(portfolioEventStore)
        .append(
            eq(2L),
            eq(PortfolioEventType.SHARES_SOLD),
            eq("TSLA"),
            eq(new BigDecimal("1.5")),
            eq(new BigDecimal("200")),
            eq(new BigDecimal("300.0")),
            any());
    verify(portfolioOutboxWriter).writeSharesSoldEvent(holding, new BigDecimal("1.5"), new BigDecimal("200"));
    verify(portfolioMetrics).recordTrade("SELL");
    verify(notificationPublisher).publish(any());
  }
}

