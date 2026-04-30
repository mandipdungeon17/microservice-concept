package com.equitycart.product.dto;

import java.math.BigDecimal;

/**
 * Outbound response payload representing a product with resolved brand and category names.
 *
 * @param id the product identifier
 * @param name the product display name
 * @param description product description
 * @param sku unique stock-keeping unit identifier
 * @param price product price
 * @param stockQuantity available inventory count
 * @param imageUrl URL to the product image
 * @param categoryId the category identifier
 * @param categoryName the resolved category name
 * @param brandId the brand identifier
 * @param brandName the resolved brand name
 * @param active whether the product is active (not soft-deleted)
 */
public record ProductResponse(
    Long id,
    String name,
    String description,
    String sku,
    BigDecimal price,
    Integer stockQuantity,
    String imageUrl,
    Long categoryId,
    String categoryName,
    Long brandId,
    String brandName,
    boolean active) {}
