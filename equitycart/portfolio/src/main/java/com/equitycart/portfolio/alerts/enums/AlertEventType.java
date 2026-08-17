package com.equitycart.portfolio.alerts.enums;

/**
 * Categories of events recorded in the alert audit trail ({@code alert_audit_logs}).
 *
 * <p>Currently emitted by the code: {@link #CREATED}, {@link #TRIGGERED}, {@link
 * #COOLDOWN_SKIPPED}, {@link #DEACTIVATED}, {@link #EVALUATION_ERROR}. {@link #CONDITION_NOT_MET},
 * {@link #REACTIVATED}, and {@link #NOTIFICATION_FAILED} are reserved for future use (e.g. verbose
 * tracing, reactivation auditing, delivery-failure tracking) and are not written today.
 */
public enum AlertEventType {
  /** Alert rule was created by the user. Written once per alert at creation. */
  CREATED,

  /** Condition was met AND the cooldown had elapsed — a notification was published. */
  TRIGGERED,

  /**
   * Condition was met but the cooldown had not yet elapsed — notification suppressed (anti-spam).
   */
  COOLDOWN_SKIPPED,

  /** Reserved: condition was evaluated and not met (verbose tracing; not written by default). */
  CONDITION_NOT_MET,

  /** Alert rule was deactivated (active=false); it is skipped by the evaluator thereafter. */
  DEACTIVATED,

  /** Reserved: alert rule was reactivated (active=true after being false). */
  REACTIVATED,

  /**
   * An error occurred while evaluating the alert (e.g. price feed unavailable); retried next cycle.
   */
  EVALUATION_ERROR,

  /** Reserved: notification delivery failed downstream after a successful trigger. */
  NOTIFICATION_FAILED
}
