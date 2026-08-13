package com.equitycart.portfolio.async.enums;

/**
 * Lifecycle status of an outbox event row. Infrastructure-only — does not represent domain/business
 * state.
 *
 * <ul>
 *   <li>{@code PENDING} — written to DB, awaiting publication to Kafka by the poller.
 *   <li>{@code SENT} — successfully published to Kafka and acknowledged.
 * </ul>
 */
public enum OutboxStatus {
  PENDING,
  SENT
}
