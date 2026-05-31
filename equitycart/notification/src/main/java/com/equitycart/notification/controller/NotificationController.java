package com.equitycart.notification.controller;

import com.equitycart.notification.dto.NotificationResponse;
import com.equitycart.notification.entity.NotificationLog;
import com.equitycart.notification.enums.NotificationType;
import com.equitycart.notification.repository.NotificationLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the notification history for the authenticated user.
 *
 * <p>Provides a read-only view over the {@code notification_logs} table, allowing clients to
 * retrieve their notification audit trail. Supports optional filtering by {@link NotificationType}.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/notifications} — all notifications (most recent first)
 *   <li>{@code GET /api/notifications?type=TRADE_EXECUTED} — filtered by type
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationLogRepository notificationLogRepository;

  @GetMapping
  public ResponseEntity<List<NotificationResponse>> getNotifications(
      Authentication authentication, @RequestParam(required = false) String type) {
    Long userId = (Long) authentication.getPrincipal();
    List<NotificationLog> notificationLogs;
    if (type != null) {
      notificationLogs =
          notificationLogRepository.findByUserIdAndNotificationTypeOrderByCreatedAtDesc(
              userId, NotificationType.valueOf(type));
    } else {
      notificationLogs = notificationLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    List<NotificationResponse> responses =
        notificationLogs.stream()
            .map(
                notificationLog ->
                    new NotificationResponse(
                        notificationLog.getId(),
                        notificationLog.getUserId(),
                        notificationLog.getNotificationType().name(),
                        notificationLog.getNotificationChannel().name(),
                        notificationLog.getNotificationStatus().name(),
                        notificationLog.getSubject(),
                        notificationLog.getBody(),
                        notificationLog.getCreatedAt()))
            .toList();

    return new ResponseEntity<>(responses, HttpStatus.OK);
  }
}
