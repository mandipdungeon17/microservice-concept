package com.equitycart.portfolio.dto;

import java.math.BigDecimal;

/**
 * Response DTO representing a single stock holding in a user's portfolio.
 *
 * @param tickerSymbol exchange ticker symbol
 * @param quantity total shares held (fractional)
 * @param averageBuyPrice weighted-average cost per share
 */
public record HoldingResponse(
    String tickerSymbol, BigDecimal quantity, BigDecimal averageBuyPrice) {}
