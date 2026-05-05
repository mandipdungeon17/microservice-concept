package com.equitycart.order.enums;

import java.util.EnumSet;
import java.util.Map;

/**
 * Order lifecycle states with a built-in state machine. Each constant knows which states it can
 * legally transition to via {@link #canTransition(OrderStatus)}.
 */
public enum OrderStatus {
  CREATED,
  CONFIRMED,
  PROCESSING,
  SHIPPED,
  DELIVERED,
  CANCELLED,
  RETURN_REQUESTED,
  RETURNED,
  REFUNDED;

  private static final Map<OrderStatus, EnumSet<OrderStatus>> TRANSITIONS =
      Map.of(
          CREATED, EnumSet.of(CONFIRMED, CANCELLED),
          CONFIRMED, EnumSet.of(PROCESSING, CANCELLED),
          PROCESSING, EnumSet.of(SHIPPED, CANCELLED),
          SHIPPED, EnumSet.of(DELIVERED),
          DELIVERED, EnumSet.of(RETURN_REQUESTED),
          RETURN_REQUESTED, EnumSet.of(RETURNED, DELIVERED),
          RETURNED, EnumSet.of(REFUNDED),
          REFUNDED, EnumSet.noneOf(OrderStatus.class),
          CANCELLED, EnumSet.noneOf(OrderStatus.class));

  public boolean canTransition(OrderStatus next) {
    return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(next);
  }
}
