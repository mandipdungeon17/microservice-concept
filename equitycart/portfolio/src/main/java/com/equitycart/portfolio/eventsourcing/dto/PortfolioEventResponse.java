package com.equitycart.portfolio.eventsourcing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API response DTO representing a single portfolio event from the MongoDB event
 * store. Maps 1:1
 * from the
 * {@link com.equitycart.portfolio.eventsourcing.document.PortfolioEvent}
 * document, minus
 * the internal MongoDB {@code _id} field.
 */
public record PortfolioEventResponse(
        UUID eventId,
        String eventType,
        String tickerSymbol,
        BigDecimal quantity,
        BigDecimal pricePerShare,
        BigDecimal totalValue,
        Map<String, Object> metadata,
        Instant timestamp,
        Long sequenceNumber) {
}
