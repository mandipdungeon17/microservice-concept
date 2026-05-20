package com.equitycart.commons.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Kafka event published when an order transitions to DELIVERED status. Consumed by
 * StockBackRewardConsumer to calculate and grant fractional share rewards.
 *
 * <p>This is a mutable POJO with no-arg constructor for Jackson deserialization. Jackson lifecycle:
 * {@code new OrderDeliveredEvent()} → setters called per JSON field → object ready.
 *
 * <p><b>Record equivalent:</b> A Java {@code record} would replace this entire class with: {@code
 * record OrderDeliveredEvent(Long orderId, Long userId, List<OrderItemEvent> items, BigDecimal
 * totalAmount, LocalDateTime deliveredAt) {}} Records auto-generate: private final fields,
 * canonical constructor, accessors (orderId() not getOrderId()), toString, equals, hashCode.
 * Jackson 2.12+ deserializes records via the canonical constructor directly (no no-arg constructor
 * needed). The trade-off: records are immutable (no setters), which is ideal for events but
 * requires Jackson's record-aware module.
 */
public class OrderDeliveredEvent {
  private Long orderId;
  private Long userId;
  private List<OrderItemEvent> orderItems;
  private BigDecimal totalAmount;
  private LocalDateTime deliveredAt;

  /** No-arg constructor required by Jackson's default deserialization strategy. */
  public OrderDeliveredEvent() {}

  /** All-args constructor for producer-side event creation. */
  public OrderDeliveredEvent(
      Long orderId,
      Long userId,
      List<OrderItemEvent> orderItems,
      BigDecimal totalAmount,
      LocalDateTime deliveredAt) {
    this.orderId = orderId;
    this.userId = userId;
    this.orderItems = orderItems;
    this.totalAmount = totalAmount;
    this.deliveredAt = deliveredAt;
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

  public List<OrderItemEvent> getOrderItems() {
    return orderItems;
  }

  public void setOrderItems(List<OrderItemEvent> orderItems) {
    this.orderItems = orderItems;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public LocalDateTime getDeliveredAt() {
    return deliveredAt;
  }

  public void setDeliveredAt(LocalDateTime deliveredAt) {
    this.deliveredAt = deliveredAt;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OrderDeliveredEvent that)) return false;
    return Objects.equals(orderId, that.orderId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(orderId);
  }

  @Override
  public String toString() {
    return "OrderDeliveredEvent{"
        + "orderId="
        + orderId
        + ", userId="
        + userId
        + ", orderItems="
        + orderItems
        + ", totalAmount="
        + totalAmount
        + ", deliveredAt="
        + deliveredAt
        + '}';
  }
}
