package com.equitycart.product.dto;

import java.math.BigDecimal;

public record ProductResponse (
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
        boolean active
) {

}
