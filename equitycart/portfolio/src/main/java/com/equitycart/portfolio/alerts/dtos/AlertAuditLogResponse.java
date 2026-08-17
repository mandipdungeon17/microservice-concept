package com.equitycart.portfolio.alerts.dtos;

import com.equitycart.portfolio.alerts.enums.AlertEventType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One audit-trail row for an alert. */
public record AlertAuditLogResponse(
    AlertEventType eventType, BigDecimal priceAtEvent, String message, LocalDateTime createdAt) {}
