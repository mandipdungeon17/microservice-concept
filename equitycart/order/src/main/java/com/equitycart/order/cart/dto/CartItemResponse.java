package com.equitycart.order.cart.dto;

import java.math.BigDecimal;

public record CartItemResponse(
    Long productId, Integer quantity, BigDecimal price, BigDecimal subtotal // price × quantity
    ) {}
