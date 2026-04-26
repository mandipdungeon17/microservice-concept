package com.equitycart.product.dto;

public record BrandResponse (
        Long id,
        String name,
        String description,
        String logoUrl,
        boolean active
) {
}
