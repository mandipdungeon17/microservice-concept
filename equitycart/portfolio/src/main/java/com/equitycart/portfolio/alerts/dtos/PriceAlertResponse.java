package com.equitycart.portfolio.alerts.dtos;

import com.equitycart.portfolio.alerts.enums.AlertCondition;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Alert view returned to the client (create/list/update responses).
 *
 * @param id server-assigned alert id
 * @param tickerSymbol watched ticker (uppercase)
 * @param condition alert condition type
 * @param threshold1 primary threshold
 * @param threshold2 upper bound (BETWEEN only; null otherwise)
 * @param cooldownMinutes minimum minutes between notifications
 * @param active whether the alert is currently evaluated
 * @param lastTriggeredAt when the alert last fired (null if never)
 */
public record PriceAlertResponse(
    Long id,
    String tickerSymbol,
    AlertCondition condition,
    BigDecimal threshold1,
    BigDecimal threshold2,
    Integer cooldownMinutes,
    Boolean active,
    LocalDateTime lastTriggeredAt) {}
