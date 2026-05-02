package com.equitycart.order.cart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AddToCartRequest(
    @NotNull Long productId,
    @NotNull @Min(1) Integer quantity,
    @NotNull @DecimalMin("0.01") BigDecimal price) {}
