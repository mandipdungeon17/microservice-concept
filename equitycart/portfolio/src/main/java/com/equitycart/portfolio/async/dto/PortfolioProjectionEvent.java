package com.equitycart.portfolio.async.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Event payload for CQRS read model projections. Emitted by the outbox pattern whenever the write
 * model changes, then published to Kafka for consumption by the projection layer.
 *
 * <p>Key design: user-centric events keyed by userId so all user events process in a consistent
 * order within a single Kafka partition.
 *
 * <p>Event types: SHARES_PURCHASED, SHARES_SOLD, REWARD_GRANTED, REWARD_VESTED, REWARD_CANCELLED,
 * REFUND_RESTORED, SELL_TO_SPEND, SELL_TO_SPEND_COMPENSATED
 *
 * @param eventId unique event identifier for deduplication
 * @param eventType the business event type (enum name from PortfolioEventType)
 * @param userId the user whose portfolio is affected (partitioning key)
 * @param tickerSymbol the stock symbol involved in the event
 * @param quantity the number of shares affected
 * @param pricePerShare the unit price at the time of the event (or zero for non-price events)
 * @param totalValue the total transaction value (quantity × pricePerShare or precomputed)
 * @param occurredAt timestamp when the event was created
 * @param metadata additional context (orderId, rewardId, sagaId, etc.) for tracing and debugging
 */
public record PortfolioProjectionEvent(
    String eventId,
    String eventType,
    Long userId,
    String tickerSymbol,
    BigDecimal quantity,
    BigDecimal pricePerShare,
    BigDecimal totalValue,
    LocalDateTime occurredAt,
    Map<String, Object> metadata) {}
