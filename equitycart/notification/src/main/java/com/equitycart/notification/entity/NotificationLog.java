package com.equitycart.notification.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.notification.enums.NotificationChannel;
import com.equitycart.notification.enums.NotificationStatus;
import com.equitycart.notification.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

/**
 * Audit-trail entity persisting every notification dispatch attempt to PostgreSQL.
 *
 * <p>Stores the rendered subject/body, delivery channel, and outcome (SENT/FAILED). On failure, the
 * {@code errorMessage} field captures the exception message for debugging. The {@code metadata}
 * field holds JSON-stringified context from the original event (tradeType, sagaId, etc.) to aid
 * troubleshooting without requiring Kafka replay.
 *
 * <p>Serves dual purposes: (1) user-facing notification history via the REST API, and (2) internal
 * audit log for monitoring delivery success rate.
 */
@Entity
@Table(name = "notification_logs")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class NotificationLog extends BaseEntity {
  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationType notificationType;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationChannel notificationChannel;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationStatus notificationStatus;

  private String subject;

  @Column(columnDefinition = "text")
  private String body;

  @Column(columnDefinition = "text")
  private String metadata;

  private String errorMessage;
}
