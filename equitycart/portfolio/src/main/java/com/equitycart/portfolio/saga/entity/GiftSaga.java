package com.equitycart.portfolio.saga.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Durable recovery record for stock gifting saga execution.
 *
 * <p>One row represents one gifting attempt. The orchestrator uses this record to resume/compensate
 * partial executions and to enforce idempotency via {@code idempotencyKey}.
 */
@Entity
@Table(name = "gift_sagas")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftSaga extends BaseEntity {

  /** Cross-service correlation id surfaced in logs/outbox events. */
  @Column(nullable = false, unique = true)
  private UUID sagaId;

  /** Source user from whom shares are debited. */
  @Column(nullable = false)
  private Long giverUserId;

  /** Target user to whom shares are credited. */
  @Column(nullable = false)
  private Long receiverUserId;

  /** Stock symbol transferred from giver to receiver. */
  @Column(nullable = false)
  private String tickerSymbol;

  /** Quantity of shares transferred in this gift request. */
  @Column(nullable = false, precision = 19, scale = 6)
  private BigDecimal quantity;

  /** Cost-basis price per share used for receiver holding credit and compensation restore. */
  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal transferPricePerShare;

  /** Total monetary value of the gifted shares used for ledger audit entries. */
  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal transferDollarValue;

  /** Client-level dedupe key to prevent duplicate transfers on retries. */
  @Column(nullable = false, unique = true)
  private String idempotencyKey;

  /** Current state of the gifting saga finite state machine. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GiftSagaStatus status;

  /** Start timestamp of gifting flow. */
  private LocalDateTime giftStartedAt;

  /** Start timestamp of compensation flow (if triggered). */
  private LocalDateTime compensationStartedAt;

  /** Last failure reason for observability and manual reconciliation. */
  private String failureReason;

  /** Optimistic lock guard for scheduler + live worker update races. */
  @Version private Long version;
}
