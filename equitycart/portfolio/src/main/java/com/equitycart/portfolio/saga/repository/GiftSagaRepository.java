package com.equitycart.portfolio.saga.repository;

import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for stock gifting saga persistence, idempotency lookup, and timeout recovery scans.
 */
public interface GiftSagaRepository extends JpaRepository<GiftSaga, Long> {

  /** Lookup by correlation id for troubleshooting and audit. */
  Optional<GiftSaga> findBySagaId(UUID sagaId);

  /** Idempotency lookup by client-supplied key. */
  Optional<GiftSaga> findByIdempotencyKey(String idempotencyKey);

  /** Active-saga idempotency guard with explicit terminal-status filter. */
  Optional<GiftSaga> findByIdempotencyKeyAndStatusNotIn(
      String idempotencyKey, List<GiftSagaStatus> terminal);

  /** Timeout detector query for stale non-terminal sagas. */
  List<GiftSaga> findByStatusNotInAndUpdatedAtBefore(
      List<GiftSagaStatus> terminal, LocalDateTime cutoff);
}
