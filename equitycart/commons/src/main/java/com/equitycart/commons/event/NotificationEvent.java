package com.equitycart.commons.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Kafka event DTO published by portfolio services and consumed by the Notification Service.
 *
 * <p>Shared between producer (portfolio module) and consumer (notification module) via the commons
 * module. Serialized as JSON by Spring Kafka's {@code JsonSerializer} and deserialized by {@code
 * JsonDeserializer} with a trusted-packages allowlist.
 *
 * @param userId recipient of the notification
 * @param notificationType event category as String (maps to {@code NotificationType} enum in
 *     notification module)
 * @param tickerSymbol stock ticker involved in the event (e.g., "AAPL")
 * @param quantity number of shares involved
 * @param pricePerShare price per share at the time of the event
 * @param totalValue total monetary value (quantity × pricePerShare)
 * @param metadata flexible context map (tradeType, sagaId, rewardId, orderId, etc.)
 * @param timestamp when the event was created by the publisher
 */
public record NotificationEvent(
    Long userId,
    String notificationType,
    String tickerSymbol,
    BigDecimal quantity,
    BigDecimal pricePerShare,
    BigDecimal totalValue,
    Map<String, Object> metadata,
    LocalDateTime timestamp) {}
