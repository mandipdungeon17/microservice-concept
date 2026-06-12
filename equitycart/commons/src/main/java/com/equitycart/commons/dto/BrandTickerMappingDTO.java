package com.equitycart.commons.dto;

import java.math.BigDecimal;

/**
 * DTO projection used by portfolio-service when calling {@code
 * ProductFeignClient.getTickerMappingsByBrandId()}.
 *
 * <p>A brand may be associated with multiple stock tickers (e.g., a parent company listed on both
 * NYSE and LSE). Each mapping specifies the percentage of the order value awarded as stock-back.
 *
 * <p>This is a 3-field subset of the full {@code BrandTickerMappingResponse} in product-service.
 * Jackson drops the remaining fields silently ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}).
 *
 * @param brandId the brand identifier (links back to the product's brand)
 * @param tickerSymbol stock ticker symbol (e.g., "AAPL", "AMZN")
 * @param stockBackPercentage percentage of order total awarded as fractional shares of this ticker
 */
public record BrandTickerMappingDTO(
    Long brandId, String tickerSymbol, BigDecimal stockBackPercentage) {}
