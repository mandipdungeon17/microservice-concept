package com.equitycart.notification.enums;

/**
 * Categories of portfolio-related notifications the system can dispatch.
 *
 * <p>Matches the {@code notificationType} String field in {@code NotificationEvent} — the consumer
 * uses {@code valueOf()} to convert. New event types require adding a value here AND a
 * corresponding case in {@code NotificationDispatcherImpl}.
 */
public enum NotificationType {
  TRADE_EXECUTED,
  REWARD_VESTED,
  SELL_TO_SPEND_COMPLETED,
  SELL_TO_SPEND_FAILED,
  PRICE_ALERT_TRIGGERED
}
