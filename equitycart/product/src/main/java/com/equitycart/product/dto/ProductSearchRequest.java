package com.equitycart.product.dto;

import java.math.BigDecimal;

/**
 * Query parameters for searching products with optional filters. All fields are nullable; null
 * values are ignored during specification building.
 *
 * @param name partial product name filter (case-insensitive LIKE)
 * @param brandId exact brand identifier filter
 * @param categoryId exact category identifier filter
 * @param minPrice minimum price (inclusive) filter
 * @param maxPrice maximum price (inclusive) filter
 * @param active active-status filter
 */
public record ProductSearchRequest(
    String name,
    Long brandId,
    Long categoryId,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    Boolean active) {}
