package com.equitycart.product.dto;

import java.math.BigDecimal;

public record BrandTickerMappingResponse (
        Long id,
        Long brandId,
        String brandName,
        String tickerSymbol,
        String exchange,
        BigDecimal stockBackPercentage,
        boolean active
) {
}
