package com.equitycart.portfolio.service.impl;

import com.equitycart.portfolio.dto.HoldingRequest;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.StockBackRewardResponse;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.service.api.PortfolioFacade;
import com.equitycart.portfolio.service.api.PortfolioService;
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

  private HoldingResponse toHoldingResponse(Holding holding) {
    return new HoldingResponse(
        holding.getTickerSymbol(), holding.getQuantity(), holding.getAverageBuyPrice());
  }
}
