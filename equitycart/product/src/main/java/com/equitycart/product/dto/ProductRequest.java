package com.equitycart.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Inbound request payload for creating or updating a product. Carries validated product details
 * including brand and category references.
 *
 * @param name the product display name
 * @param description optional product description
 * @param sku unique stock-keeping unit identifier
 * @param price product price (must be at least 0.01)
 * @param stockQuantity available inventory count
 * @param imageUrl optional URL to the product image
 * @param categoryId the category this product belongs to
 * @param brandId the brand this product belongs to
 */
public record ProductRequest(
    @NotBlank String name,
    String description,
    @NotBlank String sku,
    @NotNull @DecimalMin("0.01") BigDecimal price,
    Integer stockQuantity,
    String imageUrl,
    @NotNull Long categoryId,
    @NotNull Long brandId) {}
