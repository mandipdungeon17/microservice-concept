package com.equitycart.notification.consumer;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.notification.service.api.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer (Observer) that listens to the {@code portfolio-notification} topic and routes
 * incoming events to the {@link NotificationDispatcher}.
 *
 * <p>This is the subscription side of the distributed Observer pattern — portfolio services publish
 * events without knowing who listens; this consumer reacts independently. Uses a dedicated consumer
 * group ({@code equitycart-notification-group}) so notifications are processed exactly once per
 * event.
 *
 * <p>The {@code spring.json.value.default.type} listener property ensures correct deserialization
 * even when messages lack a {@code __TypeId__} header (e.g., if published via Debezium CDC or other
 * non-Spring producers).
 */
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

  private static final Logger log = LogManager.getLogger(NotificationConsumer.class);

  private final NotificationDispatcher notificationDispatcher;

  @KafkaListener(
      topics = "portfolio-notification",
      groupId = "equitycart-notification-group",
      properties = "spring.json.value.default.type=com.equitycart.commons.event.NotificationEvent")
  public void handleNotificationEvent(NotificationEvent event) {
    log.info(
        "Received notification event: type={}, userId={}, ticker={}",
        event.notificationType(),
        event.userId(),
        event.tickerSymbol());
    notificationDispatcher.dispatch(event);
  }
}
