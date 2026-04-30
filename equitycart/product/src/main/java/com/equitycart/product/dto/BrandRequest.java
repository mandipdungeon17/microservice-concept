package com.equitycart.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound request payload for creating a brand.
 *
 * @param name the brand name (required)
 * @param description optional brand description
 * @param logoUrl optional URL to the brand logo
 */
public record BrandRequest(@NotBlank String name, String description, String logoUrl) {}
