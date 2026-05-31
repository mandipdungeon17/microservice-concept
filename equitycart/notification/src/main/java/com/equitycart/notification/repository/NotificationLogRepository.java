package com.equitycart.notification.repository;

import com.equitycart.notification.entity.NotificationLog;
import com.equitycart.notification.enums.NotificationType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link NotificationLog} audit-trail entity.
 *
 * <p>Provides query methods for the notification history REST API, ordered by most recent first.
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

  List<NotificationLog> findByUserIdOrderByCreatedAtDesc(Long userId);

  List<NotificationLog> findByUserIdAndNotificationTypeOrderByCreatedAtDesc(
      Long userId, NotificationType type);
}
