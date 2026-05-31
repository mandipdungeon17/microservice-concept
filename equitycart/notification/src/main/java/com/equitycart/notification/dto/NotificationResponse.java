package com.equitycart.notification.dto;

import java.time.LocalDateTime;

/**
 * API response DTO for a single notification log entry returned by {@code GET /api/notifications}.
 *
 * @param id database identifier
 * @param userId the user who received this notification
 * @param notificationType event category (TRADE_EXECUTED, REWARD_VESTED, etc.)
 * @param channel delivery channel used (EMAIL, WEBHOOK, LOG)
 * @param status delivery outcome (SENT, FAILED)
 * @param subject notification title/headline
 * @param body rendered notification message
 * @param createdAt when the notification was dispatched
 */
public record NotificationResponse(
    Long id,
    Long userId,
    String notificationType,
    String channel,
    String status,
    String subject,
    String body,
    LocalDateTime createdAt) {}
