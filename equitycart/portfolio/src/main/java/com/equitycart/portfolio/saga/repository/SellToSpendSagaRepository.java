package com.equitycart.portfolio.saga.repository;

import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SellToSpendSaga} entities. Provides queries used by the saga
 * orchestrator for idempotency checks, correlation-ID lookups, and timeout detection.
 */
public interface SellToSpendSagaRepository extends JpaRepository<SellToSpendSaga, Long> {

  /** Finds a saga by its UUID correlation ID (used for external saga lookups). */
  Optional<SellToSpendSaga> findBySagaId(UUID sagaId);

  /**
   * Finds an active (non-terminal) saga for the given order. Used for idempotency — prevents
   * starting a duplicate saga for an order that already has one in progress.
   */
  Optional<SellToSpendSaga> findByOrderIdAndStatusNotIn(Long orderId, List<SagaStatus> statuses);

  /**
   * Finds sagas stuck in non-terminal states past the timeout threshold. The scheduled timeout
   * detector uses this to identify sagas that need compensation or cleanup.
   */
  List<SellToSpendSaga> findByStatusNotInAndUpdatedAtBefore(
      List<SagaStatus> terminalStatuses, LocalDateTime cutoff);
}
