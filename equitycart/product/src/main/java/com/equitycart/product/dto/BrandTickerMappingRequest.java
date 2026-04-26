package com.equitycart.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BrandTickerMappingRequest (
        @NotNull Long brandId,
        @NotBlank String tickerSymbol,
        @NotBlank String exchange,
        @NotNull @DecimalMin("0.0") BigDecimal stockBackPercentage
) {
}
