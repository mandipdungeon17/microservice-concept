package com.equitycart.portfolio.alerts.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.portfolio.alerts.enums.AlertEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Append-only audit trail of what happened to a price alert (created, fired, skipped, etc.). */
@Entity
@Table(
    name = "alert_audit_logs",
    indexes = {@Index(name = "idx_alert_audit_alert_id", columnList = "price_alert_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertAuditLog extends BaseEntity {

  /** Reference to the PriceAlert rule that triggered this event. */
  @Column(name = "price_alert_id", nullable = false)
  private Long priceAlertId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "ticker_symbol", nullable = false)
  private String tickerSymbol;

  /** Type of event that occurred (created, triggered, skipped, deactivated, etc). */
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false)
  private AlertEventType eventType;

  /**
   * Market price at the moment of the event; null for non-evaluation events (CREATED, DEACTIVATED).
   */
  @Column(name = "price_at_event", precision = 19, scale = 6)
  private BigDecimal priceAtEvent;

  /** Short human-readable context (e.g. "AAPL 152.30 crossed above 150.00"). */
  @Column(length = 500)
  private String message;
}
