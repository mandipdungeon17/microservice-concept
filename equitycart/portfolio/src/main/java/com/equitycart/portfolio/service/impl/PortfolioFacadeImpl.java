package com.equitycart.portfolio.service.impl;

import com.equitycart.portfolio.alerts.dtos.AlertAuditLogResponse;
import com.equitycart.portfolio.alerts.dtos.CreatePriceAlertRequest;
import com.equitycart.portfolio.alerts.dtos.PriceAlertResponse;
import com.equitycart.portfolio.alerts.dtos.UpdatePriceAlertRequest;
import com.equitycart.portfolio.alerts.service.PriceAlertService;
import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.dto.GiftResponse;
import com.equitycart.portfolio.dto.HoldingAnalyticsResponse;
import com.equitycart.portfolio.dto.HoldingRequest;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioAnalyticsResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.RewardSummaryResponse;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;
import com.equitycart.portfolio.dto.StockBackRewardResponse;
import com.equitycart.portfolio.dto.TradeRequest;
import com.equitycart.portfolio.dto.TradeResponse;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.saga.service.GiftSagaServiceImpl;
import com.equitycart.portfolio.service.api.PortfolioFacade;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.SellToSpendService;
import com.equitycart.portfolio.service.api.TradeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Maps between controller DTOs and {@link PortfolioService} entities. Delegates all business logic
 * to the service layer — this class only transforms inputs and outputs.
 */
@Service
@RequiredArgsConstructor
public class PortfolioFacadeImpl implements PortfolioFacade {

  private static final Logger logger = LogManager.getLogger(PortfolioFacadeImpl.class);

  private final PortfolioService portfolioService;
  private final TradeService tradeService;
  private final SellToSpendService sellToSpendService;
  private final GiftSagaServiceImpl giftSagaService;
  private final PriceAlertService priceAlertService;

  /** {@inheritDoc} */
  @Override
  public PortfolioResponse getPortfolio(Long userId) {
    Portfolio portfolio = portfolioService.getOrCreatePortfolio(userId);
    logger.debug(
        "Returning portfolio for userId={} with {} holdings",
        userId,
        portfolio.getHoldings().size());
    return new PortfolioResponse(
        userId, portfolio.getHoldings().stream().map(this::toHoldingResponse).toList());
  }

  /** {@inheritDoc} */
  @Override
  public HoldingResponse addHolding(Long userId, HoldingRequest request) {
    logger.info(
        "Adding holding for userId={}, ticker={}, qty={}, price={}",
        userId,
        request.tickerSymbol(),
        request.quantity(),
        request.price());
    return toHoldingResponse(
        portfolioService.addOrUpdateHolding(
            userId, request.tickerSymbol(), request.quantity(), request.price()));
  }

  /** {@inheritDoc} */
  @Override
  public List<StockBackRewardResponse> getRewards(Long userId) {
    var rewards = portfolioService.getRewards(userId);
    logger.debug("Returning {} rewards for userId={}", rewards.size(), userId);
    return rewards.stream()
        .map(
            reward ->
                new StockBackRewardResponse(
                    reward.getOrderId(),
                    reward.getTickerSymbol(),
                    reward.getSharesEarned(),
                    reward.getDollarValue(),
                    reward.getStatus().name(),
                    reward.getVestingDate(),
                    reward.getVestedAt()))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public TradeResponse executeTrade(Long userId, TradeRequest request) {
    Holding holding =
        tradeService.executeTrade(
            userId,
            request.tickerSymbol(),
            request.quantity(),
            request.price(),
            request.tradeType());
    if (holding == null) {
      logger.warn(
          "Trade execution failed for userId={}, ticker={}, qty={}, price={}, type={}",
          userId,
          request.tickerSymbol(),
          request.quantity(),
          request.price(),
          request.tradeType());
      return null;
    }
    return new TradeResponse(
        holding.getTickerSymbol(),
        holding.getQuantity(),
        holding.getAverageBuyPrice(),
        request.tradeType(),
        holding.getUpdatedAt());
  }

  /** {@inheritDoc} */
  @Override
  public SellToSpendResponse sellToSpend(Long userId, SellToSpendRequest request) {
    return sellToSpendService.sellToSpend(userId, request);
  }

  /** {@inheritDoc} */
  @Override
  public GiftResponse giftStock(Long giverUserId, GiftRequest request) {
    logger.info(
        "Portfolio facade gift request: giverUserId={}, receiverUserId={}, ticker={}, qty={}",
        giverUserId,
        request.receiverId(),
        request.tickerSymbol(),
        request.quantity());
    return giftSagaService.gift(giverUserId, request);
  }

  /** {@inheritDoc} */
  @Override
  public PortfolioAnalyticsResponse getAnalytics(Long userId) {
    Portfolio portfolio = portfolioService.getOrCreatePortfolio(userId);
    List<StockBackReward> rewards = portfolioService.getRewards(userId);
    logger.debug(
        "Computing analytics for userId={}: {} holdings, {} rewards",
        userId,
        portfolio.getHoldings().size(),
        rewards.size());

    BigDecimal totalCostBasis =
        portfolio.getHoldings().stream()
            .map(holding -> holding.getQuantity().multiply(holding.getAverageBuyPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    int pendingRewardsCount =
        (int)
            rewards.stream()
                .filter(reward -> reward.getStatus().equals(VestingStatus.PENDING))
                .count();

    int vestedRewardsCount =
        (int)
            rewards.stream()
                .filter(reward -> reward.getStatus().equals(VestingStatus.VESTED))
                .count();

    BigDecimal totalSharesEarned =
        rewards.stream()
            .map(StockBackReward::getSharesEarned)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalDollarValue =
        rewards.stream()
            .map(StockBackReward::getDollarValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<HoldingAnalyticsResponse> analyticsResponses =
        portfolio.getHoldings().stream()
            .map(
                holding -> {
                  BigDecimal costBasis =
                      holding.getQuantity().multiply(holding.getAverageBuyPrice());
                  BigDecimal portfolioWeight =
                      totalCostBasis.compareTo(BigDecimal.ZERO) == 0
                          ? BigDecimal.ZERO
                          : costBasis
                              .multiply(BigDecimal.valueOf(100))
                              .divide(totalCostBasis, 2, RoundingMode.HALF_UP);
                  return new HoldingAnalyticsResponse(
                      holding.getTickerSymbol(),
                      holding.getQuantity(),
                      holding.getAverageBuyPrice(),
                      costBasis,
                      portfolioWeight);
                })
            .toList();

    RewardSummaryResponse rewardSummaryResponse =
        new RewardSummaryResponse(
            rewards.size(),
            pendingRewardsCount,
            vestedRewardsCount,
            totalSharesEarned,
            totalDollarValue);

    PortfolioAnalyticsResponse analyticsResponse =
        new PortfolioAnalyticsResponse(
            userId,
            portfolio.getHoldings().size(),
            totalCostBasis,
            analyticsResponses,
            rewardSummaryResponse);

    logger.info(
        "Analytics for userId={}: totalCostBasis={}, holdingCount={}, rewards={}(pending={}, vested={})",
        userId,
        totalCostBasis,
        portfolio.getHoldings().size(),
        rewards.size(),
        pendingRewardsCount,
        vestedRewardsCount);

    return analyticsResponse;
  }

  @Override
  public PriceAlertResponse createPriceAlert(Long userId, CreatePriceAlertRequest request) {
    return priceAlertService.createAlert(userId, request);
  }

  @Override
  public List<PriceAlertResponse> getPriceAlerts(Long userId) {
    return priceAlertService.getUserAlerts(userId);
  }

  @Override
  public PriceAlertResponse updatePriceAlert(
      Long userId, Long alertId, UpdatePriceAlertRequest request) {
    return priceAlertService.updateAlert(userId, alertId, request);
  }

  @Override
  public void deactivatePriceAlert(Long userId, Long alertId) {
    priceAlertService.deactivateAlert(userId, alertId);
  }

  @Override
  public List<AlertAuditLogResponse> getPriceAlertHistory(Long userId, Long alertId) {
    return priceAlertService.getAlertHistory(userId, alertId);
  }

  private HoldingResponse toHoldingResponse(Holding holding) {
    return new HoldingResponse(
        holding.getTickerSymbol(), holding.getQuantity(), holding.getAverageBuyPrice());
  }
}
