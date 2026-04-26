package com.equitycart.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductRequest (
        @NotBlank String name,
        String description,
        @NotBlank String sku,
        @NotNull @DecimalMin("0.01") BigDecimal price,
        Integer stockQuantity,
        String imageUrl,
        @NotNull Long categoryId,
        @NotNull Long brandId
) {
}
