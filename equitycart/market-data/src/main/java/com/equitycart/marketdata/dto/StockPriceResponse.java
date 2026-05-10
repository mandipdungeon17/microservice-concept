package com.equitycart.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * API-facing response record for a stock price lookup. Wraps the core quote data with a {@code
 * cachedAt} timestamp so clients can judge data freshness.
 */
public record StockPriceResponse(
    String symbol,
    BigDecimal price,
    BigDecimal change,
    String changePercent,
    Long volume,
    String latestTradingDay,
    Instant cachedAt) {}
