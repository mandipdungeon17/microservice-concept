package com.equitycart.portfolio.service.impl;

import com.equitycart.commons.exception.InsufficientSharesException;
import com.equitycart.commons.exception.ResourceNotFoundException;
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
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.VestingHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core portfolio service managing user portfolios, stock holdings, and stock-back reward grants.
 *
 * <p>Transaction strategy:
 *
 * <ul>
 *   <li>Class-level {@code @Transactional} provides a default read-write transaction for all public
 *       methods (REQUIRED propagation).
 *   <li>{@link #vestPendingRewards()} overrides with {@code readOnly = true} since it only queries
 *       — actual writes happen inside {@link VestingHelper}'s REQUIRES_NEW transactions.
 *   <li>{@link #addOrUpdateHolding} uses a manual retry loop to handle optimistic lock conflicts on
 *       concurrent holding updates.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PortfolioServiceImpl implements PortfolioService {

  private static final Logger logger = LogManager.getLogger(PortfolioServiceImpl.class);

  private final PortfolioRepository portfolioRepository;
  private final HoldingRepository holdingRepository;
  private final StockBackRewardRepository stockBackRewardRepository;
  private final VestingHelper vestingHelper;
  private final PortfolioEventStore portfolioEventStore;
  private final PortfolioMetrics portfolioMetrics;

  private static final int retryOptimisticLocking = 3;

  /**
   * {@inheritDoc}
   *
   * <p>Attempts to find an existing portfolio for the user. If none exists, creates and persists a
   * new empty portfolio. This lazy-creation approach avoids pre-allocating portfolios at user
   * registration time.
   */
  @Override
  public Portfolio getOrCreatePortfolio(Long userId) {
    Optional<Portfolio> optionalPortfolio = portfolioRepository.findByUserId(userId);
    if (optionalPortfolio.isPresent()) {
      logger.debug("Found existing portfolio for userId={}", userId);
      return optionalPortfolio.get();
    } else {
      Portfolio portfolio = Portfolio.builder().userId(userId).build();
      logger.info("Creating new portfolio for userId={}", userId);
      return portfolioRepository.save(portfolio);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>If the user already holds the given ticker, recalculates the weighted-average buy price
   * using: {@code newAvg = ((oldQty × oldAvg) + (newQty × price)) / (oldQty + newQty)}. Otherwise,
   * creates a fresh holding.
   *
   * <p>Retries up to {@value #retryOptimisticLocking} times on {@link
   * OptimisticLockingFailureException} to handle concurrent updates to the same holding row.
   */
  @Override
  public Holding addOrUpdateHolding(Long userId, String ticker, BigDecimal qty, BigDecimal price) {
    for (int i = 0; i < retryOptimisticLocking; i++) {
      try {
        Portfolio portfolio = getOrCreatePortfolio(userId);

        Optional<Holding> optionalHolding =
            holdingRepository.findByPortfolioAndTickerSymbol(portfolio, ticker);
        Holding holding;
        if (optionalHolding.isPresent()) {
          holding = optionalHolding.get();
          BigDecimal oldQty = holding.getQuantity();
          BigDecimal oldAvg = holding.getAverageBuyPrice();
          BigDecimal newQty = oldQty.add(qty);
          BigDecimal newAvg =
              (oldQty.multiply(oldAvg).add(qty.multiply(price)))
                  .divide(newQty, RoundingMode.HALF_UP);

          holding.setQuantity(newQty);
          holding.setAverageBuyPrice(newAvg);
          logger.info(
              "Updating holding for userId={}, ticker={}: qty {} → {}, avgPrice {} → {}",
              userId,
              ticker,
              oldQty,
              newQty,
              oldAvg,
              newAvg);
        } else {
          holding =
              Holding.builder()
                  .tickerSymbol(ticker)
                  .quantity(qty)
                  .averageBuyPrice(price)
                  .portfolio(portfolio)
                  .build();
          logger.info(
              "Creating new holding for userId={}, ticker={}, qty={}, price={}",
              userId,
              ticker,
              qty,
              price);
        }
        return holdingRepository.save(holding);
      } catch (OptimisticLockingFailureException e) {
        logger.warn(
            "Optimistic lock conflict for userId={}, ticker={}, attempt {}/{}",
            userId,
            ticker,
            i + 1,
            retryOptimisticLocking);
      }
    }
    logger.error(
        "Exhausted {} retry attempts for userId={}, ticker={}",
        retryOptimisticLocking,
        userId,
        ticker);
    throw new RuntimeException(
        "Failed to update holding after "
            + retryOptimisticLocking
            + " attempts due to concurrent updates.");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Idempotent: if a reward already exists for the given {@code orderId}, returns the existing
   * record without modification. This guards against duplicate Kafka messages triggering
   * double-grants.
   */
  @Override
  public StockBackReward grantReward(
      Long orderId,
      Long userId,
      String ticker,
      BigDecimal shares,
      BigDecimal dollarVal,
      LocalDateTime vestingDate) {
    Optional<StockBackReward> rewardOptional =
        stockBackRewardRepository.findByOrderIdAndTickerSymbol(orderId, ticker);
    if (rewardOptional.isPresent()) {
      logger.warn(
          "Duplicate reward grant attempt for orderId={}, returning existing record", orderId);
      return rewardOptional.get();
    } else {
      StockBackReward reward =
          StockBackReward.builder()
              .orderId(orderId)
              .userId(userId)
              .tickerSymbol(ticker)
              .sharesEarned(shares)
              .dollarValue(dollarVal)
              .status(VestingStatus.PENDING)
              .vestingDate(vestingDate)
              .build();
      logger.info(
          "Granting stock-back reward for orderId={}, userId={}, ticker={}, shares={}",
          orderId,
          userId,
          ticker,
          shares);

      StockBackReward savedStockBackReward = stockBackRewardRepository.save(reward);

      portfolioEventStore.append(
          userId,
          PortfolioEventType.REWARD_GRANTED,
          ticker,
          shares,
          BigDecimal.ZERO,
          dollarVal,
          Map.of("orderId", orderId, "vestingDate", vestingDate.toString()));

      portfolioMetrics.recordRewardGranted();
      return savedStockBackReward;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Scheduled job that runs every 60 seconds. Queries all PENDING rewards whose vesting date has
   * passed and delegates each to {@link VestingHelper#vestSingleReward} which runs in its own
   * REQUIRES_NEW transaction — isolating per-reward failures.
   *
   * <p>This method's own transaction is read-only since it only queries; all writes occur in the
   * helper's independent transactions.
   */
  @Override
  @Scheduled(fixedDelay = 60000)
  @Transactional(readOnly = true)
  public void vestPendingRewards() {
    try {
      var pendingRewards =
          stockBackRewardRepository.findByStatusAndVestingDateBefore(
              VestingStatus.PENDING, LocalDateTime.now());
      logger.info(
          "Vesting job found {} pending rewards eligible for vesting", pendingRewards.size());
      pendingRewards.forEach(vestingHelper::vestSingleReward);
    } catch (Exception e) {
      logger.error("Vesting job encountered an error during batch processing", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<StockBackReward> getRewards(Long userId) {
    logger.debug("Retrieving all rewards for userId={}", userId);
    return stockBackRewardRepository.findByUserId(userId);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Validates that the holding exists and has sufficient quantity before reducing. If the
   * resulting quantity is zero, the holding row is deleted to avoid phantom positions in portfolio
   * views. Uses the same optimistic locking retry strategy as {@link #addOrUpdateHolding}.
   */
  @Override
  public Holding reduceHolding(Long userId, String ticker, BigDecimal qty) {
    for (int i = 0; i < retryOptimisticLocking; i++) {
      try {
        Portfolio portfolio = this.getOrCreatePortfolio(userId);
        Optional<Holding> optionalHolding =
            holdingRepository.findByPortfolioAndTickerSymbol(portfolio, ticker);
        if (optionalHolding.isEmpty()) {
          logger.error(
              "Attempt to reduce non-existent holding for userId={}, ticker={}", userId, ticker);
          throw new ResourceNotFoundException(
              "No existing holding found for userId=" + userId + ", ticker=" + ticker);
        } else {
          Holding holding = optionalHolding.get();
          if (holding.getQuantity().compareTo(qty) < 0) {
            logger.error(
                "Attempt to reduce holding by more than owned for userId={}, ticker={}, owned={}, attempted reduction={}",
                userId,
                ticker,
                holding.getQuantity(),
                qty);
            throw new InsufficientSharesException(
                "Cannot reduce holding by more than owned for userId="
                    + userId
                    + ", ticker="
                    + ticker);
          }
          BigDecimal newQty = holding.getQuantity().subtract(qty);
          logger.info(
              "Reducing holding for userId={}, ticker={}: qty {} → {}",
              userId,
              ticker,
              holding.getQuantity(),
              newQty);

          holding.setQuantity(newQty);

          if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            holdingRepository.delete(holding);
            return holding;
          } else {
            return holdingRepository.save(holding);
          }
        }
      } catch (OptimisticLockingFailureException e) {
        logger.warn(
            "Optimistic lock conflict while reducing holding for userId={}, ticker={}, attempt {}/{}",
            userId,
            ticker,
            i + 1,
            retryOptimisticLocking);
      }
    }
    logger.error(
        "Exhausted {} retry attempts while reducing holding for userId={}, ticker={}",
        retryOptimisticLocking,
        userId,
        ticker);
    throw new RuntimeException(
        "Failed to reduce holding after "
            + retryOptimisticLocking
            + " attempts due to concurrent updates.");
  }
}
