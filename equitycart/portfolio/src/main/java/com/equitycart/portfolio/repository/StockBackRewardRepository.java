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
   * Finds a reward by its source order ID. Used for idempotency checks when granting rewards.
   *
   * @param orderId the source order's ID
   * @return the reward, or empty if no reward was granted for this order
   */
  Optional<StockBackReward> findByOrderId(Long orderId);

  /**
   * Finds all rewards belonging to a user regardless of status. Used by the facade to build the
   * user's reward history view.
   *
   * @param userId the beneficiary user's ID
   * @return all rewards for the user
   */
  List<StockBackReward> findByUserId(Long userId);
}
