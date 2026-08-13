package com.equitycart.portfolio.cqrs.dtos;

import java.math.BigDecimal;

/**
 * CQRS read model response for a single holding within a portfolio. Denormalized snapshot of stock
 * position data optimized for fast API responses.
 *
 * @param tickerSymbol the stock symbol (e.g., AAPL)
 * @param quantity the number of shares held
 * @param averageBuyPrice the weighted average purchase price per share
 * @param costBasis the total investment cost (quantity × averageBuyPrice)
 */
public record HoldingReadResponse(
    String tickerSymbol, BigDecimal quantity, BigDecimal averageBuyPrice, BigDecimal costBasis) {}
