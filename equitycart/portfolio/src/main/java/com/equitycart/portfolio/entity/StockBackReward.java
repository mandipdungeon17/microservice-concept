package com.equitycart.portfolio.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.enums.VestingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tracks a fractional-share reward granted to a user after an order is filled — EquityCart's
 * "stock-back" loyalty program (conceptually similar to cash-back but settled in equity).
 *
 * <p>Lifecycle: {@code PENDING → VESTED} (or {@code PENDING → CANCELLED}). A scheduled job checks
 * {@code vestingDate}; once reached, shares are credited to the user's {@link Holding} and status
 * transitions to {@code VESTED}.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>One reward per order (unique constraint on {@code orderId}) — idempotency guard against
 *       duplicate Kafka messages.
 *   <li>{@code dollarValue} captures the grant-time valuation for accounting/reporting; actual
 *       share quantity is in {@code sharesEarned}.
 * </ul>
 */
@Entity
@Table(name = "stock_back_rewards")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class StockBackReward extends BaseEntity {

  /** Source order that triggered this reward; unique to ensure at-most-once granting. */
  @Column(unique = true, nullable = false)
  private Long orderId;

  /** Beneficiary user who will receive the vested shares. */
  @Column(nullable = false)
  private Long userId;

  /** Ticker of the stock being rewarded (may differ from the purchased stock). */
  @Column(nullable = false)
  private String tickerSymbol;

  /** Fractional share quantity earned; scale=6 for sub-penny precision. */
  @Column(precision = 19, scale = 6, nullable = false)
  private BigDecimal sharesEarned;

  /** Dollar value of the reward at grant time — used for tax/reporting purposes. */
  @Column(precision = 19, scale = 4, nullable = false)
  private BigDecimal dollarValue;

  /** Current lifecycle state of this reward. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private VestingStatus status;

  /** Date after which the reward becomes eligible for vesting into real shares. */
  @Column(nullable = false)
  private LocalDateTime vestingDate;

  /** Timestamp when the reward was actually vested; null while PENDING. */
  private LocalDateTime vestedAt;
}
