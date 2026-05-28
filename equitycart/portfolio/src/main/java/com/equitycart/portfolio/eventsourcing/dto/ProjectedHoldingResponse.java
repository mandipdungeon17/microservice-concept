package com.equitycart.portfolio.eventsourcing.dto;

import java.math.BigDecimal;

/**
 * Represents a single holding position reconstructed entirely from event replay
 * — the projection
 * output. Used by the validation endpoint to compare event-derived state
 * against PostgreSQL.
 */
public record ProjectedHoldingResponse(
        String tickerSymbol, BigDecimal quantity, BigDecimal averageBuyPrice) {
}
