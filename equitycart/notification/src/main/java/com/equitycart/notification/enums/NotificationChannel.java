package com.equitycart.notification.enums;

/**
 * Delivery channels supported by the Notification Service.
 *
 * <p>Selected at runtime via {@code equitycart.notification.channel} property. The dispatcher
 * resolves the channel name to a Spring bean using the convention {@code <lowercase>Channel} (e.g.,
 * "LOG" → "logChannel" bean).
 */
public enum NotificationChannel {
  EMAIL,
  WEBHOOK,
  LOG
}
