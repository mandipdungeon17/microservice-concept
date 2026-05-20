package com.equitycart.commons.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Kafka event published when an order transitions to RETURNED status. Consumed by
 * RewardCancellationConsumer to cancel PENDING stock-back rewards (prevents vesting of rewards for
 * returned orders).
 *
 * <p>If the reward has already been VESTED (past 30-day window), cancellation is not possible —
 * that scenario requires a clawback saga (out of scope for Phase 6).
 *
 * <p><b>Record equivalent:</b> {@code record OrderReturnedEvent(Long orderId, Long userId,
 * LocalDateTime returnedAt) {}}
 */
public class OrderReturnedEvent {
  private Long orderId;
  private Long userId;
  private LocalDateTime returnedAt;

  /** No-arg constructor required by Jackson's default deserialization strategy. */
  public OrderReturnedEvent() {}

  /** All-args constructor for producer-side event creation. */
  public OrderReturnedEvent(Long orderId, Long userId, LocalDateTime returnedAt) {
    this.orderId = orderId;
    this.userId = userId;
    this.returnedAt = returnedAt;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long orderId) {
    this.orderId = orderId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public LocalDateTime getReturnedAt() {
    return returnedAt;
  }

  public void setReturnedAt(LocalDateTime returnedAt) {
    this.returnedAt = returnedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OrderReturnedEvent that)) return false;
    return Objects.equals(orderId, that.orderId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(orderId);
  }

  @Override
  public String toString() {
    return "OrderReturnedEvent{"
        + "orderId="
        + orderId
        + ", userId="
        + userId
        + ", returnedAt="
        + returnedAt
        + '}';
  }
}
