package com.equitycart.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Response payload representing a complete order with its line items. */
public record OrderResponse(
    Long orderId,
    Long userId,
    String status,
    BigDecimal totalAmount,
    String idempotencyKey,
    String shippingAddress,
    String paymentMethod,
    List<OrderItemResponse> items,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
