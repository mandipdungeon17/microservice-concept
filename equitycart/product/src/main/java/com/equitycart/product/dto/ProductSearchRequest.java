package com.equitycart.product.dto;

import java.math.BigDecimal;

public record ProductSearchRequest (
        String name,
        Long brandId,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Boolean active
    ){
}
