package com.equitycart.portfolio.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.alerts.dtos.PriceAlertResponse;
import com.equitycart.portfolio.alerts.dtos.CreatePriceAlertRequest;
import com.equitycart.portfolio.alerts.dtos.AlertAuditLogResponse;
import com.equitycart.portfolio.alerts.dtos.UpdatePriceAlertRequest;
import com.equitycart.portfolio.alerts.enums.AlertCondition;
import com.equitycart.portfolio.alerts.enums.AlertEventType;
import com.equitycart.portfolio.alerts.service.PriceAlertService;
import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.dto.GiftResponse;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioAnalyticsResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.RewardSummaryResponse;
import com.equitycart.portfolio.dto.TradeRequest;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import com.equitycart.portfolio.saga.service.GiftSagaServiceImpl;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.SellToSpendService;
import com.equitycart.portfolio.service.api.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioFacadeImplTest {

  @Mock private PortfolioService portfolioService;
  @Mock private TradeService tradeService;
  @Mock private SellToSpendService sellToSpendService;
  @Mock private GiftSagaServiceImpl giftSagaService;
  @Mock private PriceAlertService priceAlertService;

  @InjectMocks private PortfolioFacadeImpl portfolioFacade;

  @Test
  void getPortfolioShouldMapAllHoldings() {
    Holding h1 =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("2"))
            .averageBuyPrice(new BigDecimal("100"))
            .build();
    Holding h2 =
        Holding.builder()
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("3"))
            .averageBuyPrice(new BigDecimal("200"))
            .build();
    Portfolio portfolio = Portfolio.builder().userId(4L).holdings(List.of(h1, h2)).build();
    when(portfolioService.getOrCreatePortfolio(4L)).thenReturn(portfolio);

    PortfolioResponse response = portfolioFacade.getPortfolio(4L);

    assertEquals(4L, response.userId());
    assertEquals(2, response.holdings().size());
    HoldingResponse first = response.holdings().getFirst();
    assertEquals("AAPL", first.tickerSymbol());
    assertEquals(0, new BigDecimal("2").compareTo(first.quantity()));
  }

  @Test
  void executeTradeShouldReturnNullWhenTradeServiceReturnsNull() {
    TradeRequest request = new TradeRequest("AAPL", new BigDecimal("1"), new BigDecimal("100"), "BUY");
    when(tradeService.executeTrade(1L, "AAPL", new BigDecimal("1"), new BigDecimal("100"), "BUY"))
        .thenReturn(null);

    assertNull(portfolioFacade.executeTrade(1L, request));
  }

  @Test
  void getAnalyticsShouldCalculateWeightsAndRewardSummary() {
    Holding aapl =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("2"))
            .averageBuyPrice(new BigDecimal("100"))
            .build();
    Holding msft =
        Holding.builder()
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("1"))
            .averageBuyPrice(new BigDecimal("200"))
            .build();

    Portfolio portfolio = Portfolio.builder().userId(8L).holdings(List.of(aapl, msft)).build();

    StockBackReward pending =
        StockBackReward.builder()
            .orderId(10L)
            .userId(8L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.PENDING)
            .vestingDate(LocalDateTime.now().plusDays(30))
            .build();
    StockBackReward vested =
        StockBackReward.builder()
            .orderId(11L)
            .userId(8L)
            .tickerSymbol("MSFT")
            .sharesEarned(new BigDecimal("0.2"))
            .dollarValue(new BigDecimal("20"))
            .status(VestingStatus.VESTED)
            .vestingDate(LocalDateTime.now().minusDays(1))
            .build();

    when(portfolioService.getOrCreatePortfolio(8L)).thenReturn(portfolio);
    when(portfolioService.getRewards(8L)).thenReturn(List.of(pending, vested));

    PortfolioAnalyticsResponse response = portfolioFacade.getAnalytics(8L);

    assertEquals(8L, response.userId());
    assertEquals(2, response.holdingCount());
    assertEquals(0, new BigDecimal("400").compareTo(response.totalCostBasis()));
    RewardSummaryResponse summary = response.rewardSummary();
    assertEquals(2, summary.totalRewards());
    assertEquals(1, summary.pendingRewards());
    assertEquals(1, summary.vestedRewards());
  }

  @Test
  void giftStockShouldDelegateToGiftSagaService() {
    GiftRequest request = new GiftRequest(2L, "AAPL", new BigDecimal("1"), "idem-key");
    GiftResponse expected =
        new GiftResponse(UUID.randomUUID(), GiftSagaStatus.COMPLETED, 1L, 2L, "AAPL");
    when(giftSagaService.gift(1L, request)).thenReturn(expected);

    GiftResponse response = portfolioFacade.giftStock(1L, request);

    assertEquals(expected, response);
  }

  @Test
  void createPriceAlertShouldDelegateToAlertService() {
    CreatePriceAlertRequest request =
        new CreatePriceAlertRequest(
            "AAPL", AlertCondition.ABOVE, new BigDecimal("150"), null, 30);
    PriceAlertResponse expected =
        new PriceAlertResponse(
            1L,
            "AAPL",
            AlertCondition.ABOVE,
            new BigDecimal("150"),
            null,
            30,
            true,
            null);
    when(priceAlertService.createAlert(1L, request)).thenReturn(expected);

    PriceAlertResponse response = portfolioFacade.createPriceAlert(1L, request);

    assertEquals(expected, response);
    verify(priceAlertService).createAlert(1L, request);
  }

  @Test
  void addHoldingShouldDelegateAndMapResponse() {
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("3"))
            .averageBuyPrice(new BigDecimal("120"))
            .build();
    when(portfolioService.addOrUpdateHolding(1L, "AAPL", new BigDecimal("3"), new BigDecimal("120")))
        .thenReturn(holding);

    HoldingResponse response =
        portfolioFacade.addHolding(
            1L, new com.equitycart.portfolio.dto.HoldingRequest("AAPL", new BigDecimal("3"), new BigDecimal("120")));

    assertEquals("AAPL", response.tickerSymbol());
    assertEquals(0, new BigDecimal("3").compareTo(response.quantity()));
  }

  @Test
  void getRewardsShouldMapAllRewardFields() {
    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(1L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .status(VestingStatus.VESTED)
            .vestingDate(LocalDateTime.now().minusDays(1))
            .vestedAt(LocalDateTime.now())
            .build();
    when(portfolioService.getRewards(1L)).thenReturn(List.of(reward));

    List<com.equitycart.portfolio.dto.StockBackRewardResponse> responses = portfolioFacade.getRewards(1L);

    assertEquals(1, responses.size());
    assertEquals("VESTED", responses.getFirst().status());
  }

  @Test
  void executeTradeShouldReturnMappedTradeResponseOnSuccess() {
    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("4"))
            .averageBuyPrice(new BigDecimal("110"))
            .build();
    when(tradeService.executeTrade(1L, "AAPL", new BigDecimal("1"), new BigDecimal("100"), "BUY"))
        .thenReturn(holding);

    com.equitycart.portfolio.dto.TradeResponse response =
        portfolioFacade.executeTrade(1L, new TradeRequest("AAPL", new BigDecimal("1"), new BigDecimal("100"), "BUY"));

    assertEquals("AAPL", response.tickerSymbol());
    assertEquals("BUY", response.tradeType());
  }

  @Test
  void sellToSpendShouldDelegateToService() {
    com.equitycart.portfolio.dto.SellToSpendRequest request =
        new com.equitycart.portfolio.dto.SellToSpendRequest("AAPL", new BigDecimal("1"), new BigDecimal("100"), 99L);
    com.equitycart.portfolio.dto.SellToSpendResponse expected =
        new com.equitycart.portfolio.dto.SellToSpendResponse(
            99L, "AAPL", new BigDecimal("1"), new BigDecimal("100"), "CONFIRMED");
    when(sellToSpendService.sellToSpend(1L, request)).thenReturn(expected);

    assertSame(expected, portfolioFacade.sellToSpend(1L, request));
  }

  @Test
  void priceAlertCrudMethodsShouldDelegate() {
    PriceAlertResponse alert =
        new PriceAlertResponse(2L, "AAPL", AlertCondition.ABOVE, new BigDecimal("100"), null, 30, true, null);
    UpdatePriceAlertRequest update = new UpdatePriceAlertRequest(new BigDecimal("90"), null, 15, true);
    AlertAuditLogResponse history =
        new AlertAuditLogResponse(AlertEventType.CREATED, new BigDecimal("100"), "created", LocalDateTime.now());
    when(priceAlertService.getUserAlerts(1L)).thenReturn(List.of(alert));
    when(priceAlertService.updateAlert(1L, 2L, update)).thenReturn(alert);
    when(priceAlertService.getAlertHistory(1L, 2L)).thenReturn(List.of(history));

    assertEquals(1, portfolioFacade.getPriceAlerts(1L).size());
    assertSame(alert, portfolioFacade.updatePriceAlert(1L, 2L, update));
    portfolioFacade.deactivatePriceAlert(1L, 2L);
    assertEquals(1, portfolioFacade.getPriceAlertHistory(1L, 2L).size());

    verify(priceAlertService).deactivateAlert(1L, 2L);
  }
}
