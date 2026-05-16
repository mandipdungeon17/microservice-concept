package com.equitycart.portfolio.dto;

import java.math.BigDecimal;

/**
 * Per-holding analytics with computed cost basis and portfolio weight.
 *
 * @param tickerSymbol exchange ticker symbol
 * @param quantity total shares held
 * @param averageBuyPrice weighted-average purchase price per share
 * @param costBasis total investment in this holding (quantity × averageBuyPrice)
 * @param portfolioWeight percentage of total portfolio cost basis allocated to this holding (0–100)
 */
public record HoldingAnalyticsResponse(
    String tickerSymbol,
    BigDecimal quantity,
    BigDecimal averageBuyPrice,
    BigDecimal costBasis,
    BigDecimal portfolioWeight) {}
