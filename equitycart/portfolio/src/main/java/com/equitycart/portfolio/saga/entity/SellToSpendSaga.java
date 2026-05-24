package com.equitycart.portfolio.saga.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Persists the state of a Sell-to-Spend Saga — the orchestrator's recovery log. Each row tracks one
 * saga instance through its lifecycle: which steps completed, the current status, and all input
 * parameters needed to resume or compensate.
 *
 * <p>Stores all request inputs (ticker, quantity, price, proceeds) so the orchestrator can
 * retry/compensate without re-fetching the original request. The {@code sagaId} UUID serves as the
 * correlation ID visible in Kafka lifecycle events and logs.
 *
 * <p>{@code @Version} enables optimistic locking — prevents the timeout detector and an in-flight
 * saga from concurrently updating the same row.
 *
 * @see SagaStatus
 * @see com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator
 */
@Entity
@Table(name = "sell_to_spend_sagas")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class SellToSpendSaga extends BaseEntity {
  @Column(nullable = false, unique = true)
  UUID sagaId;

  @Column(nullable = false)
  Long userId;

  @Column(nullable = false)
  Long orderId;

  @Column(nullable = false)
  String tickerSymbol;

  @Column(nullable = false, precision = 19, scale = 6)
  BigDecimal quantity;

  @Column(nullable = false, precision = 19, scale = 4)
  BigDecimal pricePerShare;

  @Column(precision = 19, scale = 4)
  BigDecimal saleProceeds;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  SagaStatus status;

  @Builder.Default boolean isRefunded = false;

  @Version Long version;

  String failureReason;

  LocalDateTime compensationStartedAt;
}
