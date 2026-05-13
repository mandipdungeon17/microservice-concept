package com.equitycart.portfolio.service.impl;

import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.VestingHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the vesting of individual {@link StockBackReward} entries in isolated transactions.
 *
 * <p>Separated from {@link PortfolioServiceImpl} to solve the Spring proxy self-invocation problem:
 * {@code vestPendingRewards()} needs each reward processed in its own transaction (REQUIRES_NEW),
 * but calling a {@code this.method()} bypasses the proxy. By extracting into a separate bean, the
 * call goes through the proxy and REQUIRES_NEW is honored.
 */
@Service
public class VestingHelperImpl implements VestingHelper {

  private static final Logger logger = LogManager.getLogger(VestingHelperImpl.class);

  @Lazy @Autowired private PortfolioService portfolioService;
  @Autowired private StockBackRewardRepository stockBackRewardRepository;

  /**
   * {@inheritDoc}
   *
   * <p>Runs in a REQUIRES_NEW transaction so that a failure vesting one reward does not roll back
   * other rewards processed in the same scheduler cycle. On success, the reward transitions to
   * VESTED and the earned shares are credited to the user's holding. On failure, the reward remains
   * PENDING and will be retried in the next scheduler cycle.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void vestSingleReward(StockBackReward reward) {
    try {
      portfolioService.addOrUpdateHolding(
          reward.getUserId(), reward.getTickerSymbol(), reward.getSharesEarned(), BigDecimal.ZERO);
      reward.setStatus(VestingStatus.VESTED);
      reward.setVestedAt(LocalDateTime.now());
      stockBackRewardRepository.save(reward);
      logger.info(
          "Vested reward id={} for userId={}, ticker={}, shares={}",
          reward.getId(),
          reward.getUserId(),
          reward.getTickerSymbol(),
          reward.getSharesEarned());
    } catch (Exception e) {
      logger.error(
          "Failed to vest reward id={} for userId={}, ticker={} — will retry next cycle",
          reward.getId(),
          reward.getUserId(),
          reward.getTickerSymbol(),
          e);
    }
  }
}
