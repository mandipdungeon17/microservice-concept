package com.equitycart.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal DTO representing a parsed stock quote from the Alpha Vantage GLOBAL_QUOTE endpoint.
 * Fields are extracted manually from the JSON response via {@link
 * com.fasterxml.jackson.databind.JsonNode} because the API's numbered keys ({@code "05. price"})
 * cannot map directly to Java field names.
 */
public record StockQuote(
    String symbol,
    BigDecimal price,
    BigDecimal change,
    String changePercent,
    Long volume,
    String latestTradingDay,
    Instant timestamp) {}
