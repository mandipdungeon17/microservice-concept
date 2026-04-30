package com.equitycart.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Inbound request payload for creating a brand-to-ticker-symbol mapping.
 *
 * @param brandId the brand to map
 * @param tickerSymbol the stock ticker symbol (e.g., "AAPL")
 * @param exchange the stock exchange (e.g., "NASDAQ")
 * @param stockBackPercentage the cashback percentage for purchases
 */
public record BrandTickerMappingRequest(
    @NotNull Long brandId,
    @NotBlank String tickerSymbol,
    @NotBlank String exchange,
    @NotNull @DecimalMin("0.0") BigDecimal stockBackPercentage) {}
