package com.equitycart.portfolio.saga.repository;

import com.equitycart.portfolio.saga.entity.ClawbackSaga;
import com.equitycart.portfolio.saga.enums.ClawbackStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for clawback saga persistence and recovery queries.
 *
 * <p>Used by orchestrator for:
 *
 * <ul>
 *   <li>idempotency check (active saga exists for same order+reward)
 *   <li>timeout scanning (non-terminal status with stale updatedAt)
 *   <li>audit lookups by order/reward/sagaId
 * </ul>
 */
public interface ClawbackSagaRepository extends JpaRepository<ClawbackSaga, Long> {

  /** Lookup by correlation id. */
  Optional<ClawbackSaga> findBySagaId(UUID sagaId);

  /** Audit lookup for all clawback attempts tied to one order. */
  List<ClawbackSaga> findByOrderId(Long orderId);

  /** Audit lookup for all attempts tied to one reward. */
  List<ClawbackSaga> findByRewardId(Long rewardId);

  /** Active-saga idempotency guard for same order+reward pair. */
  Optional<ClawbackSaga> findByOrderIdAndRewardIdAndStatusNotIn(
      Long orderId, Long rewardId, List<ClawbackStatus> terminalStatuses);

  /** Timeout detector query. */
  List<ClawbackSaga> findByStatusNotInAndUpdatedAtBefore(
      List<ClawbackStatus> terminalStatuses, LocalDateTime cutoff);
}
