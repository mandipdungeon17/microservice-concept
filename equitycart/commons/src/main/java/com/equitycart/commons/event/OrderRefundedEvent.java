package com.equitycart.commons.event;

import java.time.LocalDateTime;

/**
 * Event published to the {@code order-refunded} Kafka topic when an order transitions to REFUNDED
 * status. Carries the payment method so downstream consumers can determine the appropriate refund
 * action (e.g., STOCK payment triggers share restoration via the sell-to-spend saga reversal).
 *
 * @param orderId the refunded order's ID (used as Kafka message key for partition ordering)
 * @param userId the user who placed the order
 * @param paymentMethod original payment method ("STOCK", "CARD", etc.) — determines refund path
 * @param returnedAt timestamp when the refund was initiated
 */
public record OrderRefundedEvent(
    Long orderId, Long userId, String paymentMethod, LocalDateTime returnedAt) {}
