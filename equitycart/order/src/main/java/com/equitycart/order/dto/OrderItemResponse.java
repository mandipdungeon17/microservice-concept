package com.equitycart.order.dto;

import java.math.BigDecimal;

/** Response payload for a single line item within an order. */
public record OrderItemResponse(
    Long productId,
    String productName,
    Integer quantity,
    BigDecimal priceAtPurchase,
    BigDecimal subTotal) {}
