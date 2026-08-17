package com.equitycart.portfolio.alerts.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.alerts.enums.AlertCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user-defined rule that watches a ticker's price and notifies the user when a condition is met.
 * Evaluated on a fixed schedule; cooldown prevents repeated notifications while a condition stays
 * true. {@code lastEvaluatedPrice} enables CROSSING detection without any external history lookup.
 */
@Entity
@Table(
    name = "price_alerts",
    indexes = {
      @Index(name = "idx_price_alert_user_active", columnList = "user_id, active"),
      @Index(name = "idx_price_alert_ticker_active", columnList = "ticker_symbol, active")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAlert extends BaseEntity {

  /** User who created this alert rule. Foreign key to User table. */
  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** Stock ticker symbol (e.g., "AAPL", "GOOGL"). Uppercase, required for consistent lookups. */
  @Column(name = "ticker_symbol", nullable = false)
  private String tickerSymbol;

  /**
   * Alert condition type (ABOVE, BELOW, BETWEEN, CROSSING). Determines how price
   * threshold1/threshold2 are evaluated.
   *
   * <p>ABOVE: currentPrice > threshold1 BELOW: currentPrice < threshold1 BETWEEN: threshold1 <
   * currentPrice < threshold2 CROSSING: (previousPrice <= threshold1 AND currentPrice > threshold1)
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AlertCondition condition;

  /**
   * Primary threshold for condition evaluation. Required for all condition types. Stored with
   * scale=6 to support both cent-precision equities and fractional/crypto prices.
   */
  @Column(nullable = false, precision = 19, scale = 6)
  private BigDecimal threshold1;

  /** Secondary threshold for BETWEEN condition only. Null for ABOVE, BELOW, CROSSING conditions. */
  @Column(precision = 19, scale = 6)
  private BigDecimal threshold2;

  /**
   * Cooldown period in minutes to prevent alert spam. Once alert triggers, next alert for same rule
   * requires cooldown to elapse.
   *
   * <p>Rationale: Without cooldown, if AAPL stays at $151 and price stays > $150, alert would fire
   * continuously every evaluation cycle (5s). User wants ONE alert per "state change" (transition
   * to triggered state), not per price tick.
   *
   * <p>Typical values: 60 (once per hour), 1440 (once per day)
   */
  @Column(name = "cooldown_minutes", nullable = false)
  private Integer cooldownMinutes;

  /**
   * Whether this alert rule is currently active. Users can "pause" alerts without deleting them.
   * Inactive alerts are skipped during evaluation.
   */
  @Column(nullable = false)
  private Boolean active;

  /**
   * Timestamp when this alert last fired a notification; {@code null} if it has never fired.
   *
   * <p>Drives the cooldown window: the alert is eligible again once {@code lastTriggeredAt +
   * cooldownMinutes} has passed (see {@link #isCooldownExpired()}).
   */
  @Column(name = "last_triggered_at")
  private LocalDateTime lastTriggeredAt;

  /** Price seen on the previous evaluation cycle; used for CROSSING detection. */
  @Column(name = "last_evaluated_price", precision = 19, scale = 6)
  private BigDecimal lastEvaluatedPrice;

  /**
   * Optimistic-locking version. Detects concurrent modification when the scheduled evaluator and a
   * user edit touch the same alert at once, so a stale {@code lastTriggeredAt} or {@code
   * lastEvaluatedPrice} is not silently overwritten.
   */
  @Version private Long version;

  /**
   * Whether this alert is currently eligible to fire.
   *
   * <p>Returns {@code true} only when the alert is active AND either it has never triggered, or its
   * cooldown window ({@code lastTriggeredAt + cooldownMinutes}) has elapsed.
   */
  @Transient
  public boolean isCooldownExpired() {
    if (!Boolean.TRUE.equals(active)) {
      return false;
    }
    if (lastTriggeredAt == null) {
      return true;
    }
    return LocalDateTime.now().isAfter(lastTriggeredAt.plusMinutes(cooldownMinutes));
  }
}
