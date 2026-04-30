package com.equitycart.product.dto;

/**
 * Outbound response payload representing a brand.
 *
 * @param id the brand identifier
 * @param name the brand name
 * @param description brand description
 * @param logoUrl URL to the brand logo
 * @param active whether the brand is active
 */
public record BrandResponse(
    Long id, String name, String description, String logoUrl, boolean active) {}
