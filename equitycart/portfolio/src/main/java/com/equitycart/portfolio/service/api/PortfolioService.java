package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service contract for portfolio management — creating portfolios, managing holdings, granting
 * stock-back rewards, and processing vesting.
 */
public interface PortfolioService {

  /**
   * Retrieves the portfolio for the given user, creating one if it doesn't exist.
   *
   * @param userId the owning user's ID
   * @return the existing or newly created portfolio
   */
  Portfolio getOrCreatePortfolio(Long userId);

  /**
   * Adds shares to an existing holding or creates a new one. If the holding exists, the
   * weighted-average buy price is recalculated.
   *
   * @param userId the owning user's ID
   * @param ticker exchange ticker symbol (e.g. "AAPL")
   * @param qty number of shares to add
   * @param price purchase price per share (ZERO for free shares like stock-back rewards)
   * @return the saved holding with updated quantity and average price
   * @throws RuntimeException if optimistic lock retries are exhausted
   */
  Holding addOrUpdateHolding(Long userId, String ticker, BigDecimal qty, BigDecimal price);

  /**
   * Grants a stock-back reward for a completed order. Idempotent — duplicate calls with the same
   * {@code orderId} return the existing reward without modification.
   *
   * @param orderId source order that triggered this reward (unique key)
   * @param userId beneficiary user
   * @param ticker ticker symbol of the rewarded stock
   * @param shares fractional share quantity earned
   * @param dollarVal dollar value at grant time (for reporting)
   * @param vestingDate date after which the reward becomes eligible for vesting
   * @return the created or already-existing reward
   */
  StockBackReward grantReward(
      Long orderId,
      Long userId,
      String ticker,
      BigDecimal shares,
      BigDecimal dollarVal,
      LocalDateTime vestingDate);

  /**
   * Scheduled job that finds all PENDING rewards past their vesting date and credits the earned
   * shares to each user's holding.
   */
  void vestPendingRewards();

  /**
   * Retrieves all stock-back rewards (any status) for the given user.
   *
   * @param userId the beneficiary user's ID
   * @return list of rewards ordered by the repository's default (insertion order)
   */
  List<StockBackReward> getRewards(Long userId);
}
