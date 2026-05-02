package com.equitycart.order.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartResponse(
    String userId, List<CartItemResponse> items, BigDecimal total, Instant expiresAt) {}
