package com.equitycart.portfolio.repository;

import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link StockBackReward} entities. */
public interface StockBackRewardRepository extends JpaRepository<StockBackReward, Long> {

  /**
   * Finds all rewards in the given status whose vesting date has passed. Used by the scheduled
   * vesting job to discover rewards eligible for vesting.
   *
   * @param status the target status (typically PENDING)
   * @param now the current timestamp — rewards with {@code vestingDate < now} are returned
   * @return list of rewards ready to be vested
   */
  List<StockBackReward> findByStatusAndVestingDateBefore(VestingStatus status, LocalDateTime now);

  /**
   * Finds all rewards for a given order across all tickers. Used by the cancellation consumer to
   * cancel PENDING rewards when an order is returned.
   *
   * @param orderId the source order's ID
   * @return all rewards for this order (may span multiple tickers)
   */
  List<StockBackReward> findByOrderId(Long orderId);

  /**
   * Finds all rewards belonging to a user regardless of status. Used by the facade to build the
   * user's reward history view.
   *
   * @param userId the beneficiary user's ID
   * @return all rewards for the user
   */
  List<StockBackReward> findByUserId(Long userId);

  /**
   * Finds a specific reward by order ID and ticker symbol. Used for idempotency checks when
   * granting rewards — prevents duplicate grants on Kafka message redelivery.
   *
   * @param orderId the source order's ID
   * @param tickerSymbol the ticker being rewarded
   * @return the existing reward, or empty if none exists for this (order, ticker) pair
   */
  Optional<StockBackReward> findByOrderIdAndTickerSymbol(Long orderId, String tickerSymbol);
}
