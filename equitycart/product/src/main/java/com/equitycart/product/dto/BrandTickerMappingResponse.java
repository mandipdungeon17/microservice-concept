package com.equitycart.product.dto;

import java.math.BigDecimal;

/**
 * Outbound response payload representing a brand-ticker mapping with resolved brand name.
 *
 * @param id the mapping identifier
 * @param brandId the brand identifier
 * @param brandName the resolved brand name
 * @param tickerSymbol the stock ticker symbol
 * @param exchange the stock exchange
 * @param stockBackPercentage the cashback percentage
 * @param active whether this mapping is active
 */
public record BrandTickerMappingResponse(
    Long id,
    Long brandId,
    String brandName,
    String tickerSymbol,
    String exchange,
    BigDecimal stockBackPercentage,
    boolean active) {}
