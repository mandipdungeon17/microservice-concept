package com.equitycart.portfolio.saga.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.saga.enums.ClawbackStatus;
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
 * Persistent recovery record for clawback saga execution.
 *
 * <p>Stores the full context required to safely resume or compensate a partially executed clawback
 * after failures/timeouts. Each row represents one clawback attempt for one order+reward pair.
 */
@Entity
@Table(name = "clawback_sagas")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class ClawbackSaga extends BaseEntity {
  /** Cross-service correlation id exposed in logs/events. */
  @Column(nullable = false, unique = true)
  private UUID sagaId;

  /** User whose vested reward is being reversed. */
  @Column(nullable = false)
  private Long userId;

  /** Returned order id that triggered the reversal flow. */
  @Column(nullable = false)
  private Long orderId;

  /** Reward id being clawed back. */
  @Column(nullable = false)
  private Long rewardId;

  /** Quantity of shares to reverse from holding state. */
  @Column(nullable = false, precision = 19, scale = 6)
  private BigDecimal rewardQuantity;

  /** Current saga state for resume/compensation decisions. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ClawbackStatus status;

  /** Timestamp when saga was first created. */
  private LocalDateTime clawbackStartedAt;

  /** Timestamp when compensation began, if applicable. */
  private LocalDateTime compensationStartedAt;

  /** Last failure reason for failed/compensated flows. */
  private String failureReason;

  /** Optimistic lock guard against concurrent timeout/worker updates. */
  @Version private Long version;
}
