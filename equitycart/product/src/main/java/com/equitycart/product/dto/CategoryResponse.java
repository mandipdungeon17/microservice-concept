package com.equitycart.product.dto;

/**
 * Outbound response payload representing a category with resolved parent info.
 *
 * @param id the category identifier
 * @param name the category name
 * @param description category description
 * @param parentId the parent category identifier (null for top-level)
 * @param parentName the resolved parent category name (null for top-level)
 * @param active whether the category is active
 */
public record CategoryResponse(
    Long id, String name, String description, Long parentId, String parentName, boolean active) {}
