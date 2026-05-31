package com.equitycart.portfolio.event;

import com.equitycart.commons.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget publisher for notification events to the {@code portfolio-notification} Kafka
 * topic.
 *
 * <p>Lives in the portfolio module (not the notification module) because it is the <em>subject</em>
 * in the Observer pattern — portfolio services create events, the notification service reacts.
 * Placing the publisher here avoids a circular module dependency (notification depends on portfolio
 * for nothing; portfolio doesn't depend on notification).
 *
 * <p>Uses {@link KafkaTemplate#send(String, Object, Object)} with {@code userId} as the message
 * key, ensuring all notifications for a given user land on the same partition (ordered processing).
 * Failures are caught and logged at WARN level — a missed notification is low-severity and must
 * never disrupt the business transaction that triggered it.
 */
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

  private static final Logger log = LogManager.getLogger(NotificationPublisher.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public void publish(NotificationEvent event) {
    try {
      kafkaTemplate.send("portfolio-notification", event.userId().toString(), event);
      log.debug("Published notification event for userId {}: {}", event.userId(), event);
    } catch (Exception e) {
      log.warn(
          "Failed to publish notification event for userId {}: {}", event.userId(), e.getMessage());
    }
  }
}
