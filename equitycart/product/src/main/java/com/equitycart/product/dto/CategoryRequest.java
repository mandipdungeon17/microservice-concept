package com.equitycart.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound request payload for creating a category.
 *
 * @param name the category name (required)
 * @param description optional category description
 * @param parentId optional parent category identifier for subcategories
 */
public record CategoryRequest(@NotBlank String name, String description, Long parentId) {}
