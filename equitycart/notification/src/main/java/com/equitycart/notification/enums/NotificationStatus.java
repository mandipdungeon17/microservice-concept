package com.equitycart.notification.enums;

/**
 * Outcome of a notification dispatch attempt, persisted in {@code notification_logs}.
 *
 * <ul>
 *   <li>{@code SENT} — channel strategy completed without exception
 *   <li>{@code FAILED} — an exception occurred during dispatch (see {@code errorMessage} field)
 * </ul>
 */
public enum NotificationStatus {
  SENT,
  FAILED
}
