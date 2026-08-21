package com.equitycart.portfolio.event;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.equitycart.commons.event.NotificationEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

  @Mock private KafkaTemplate<String, Object> kafkaTemplate;
  @InjectMocks private NotificationPublisher publisher;

  @Test
  void publishShouldSendToPortfolioNotificationTopic() {
    NotificationEvent event =
        new NotificationEvent(
            8L,
            "TRADE_EXECUTED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("100"),
            new BigDecimal("100"),
            Map.of(),
            LocalDateTime.now());

    publisher.publish(event);

    verify(kafkaTemplate).send("portfolio-notification", "8", event);
  }

  @Test
  void publishShouldSwallowKafkaException() {
    NotificationEvent event =
        new NotificationEvent(
            8L,
            "TRADE_EXECUTED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("100"),
            new BigDecimal("100"),
            Map.of(),
            LocalDateTime.now());
    doThrow(new RuntimeException("kafka down"))
        .when(kafkaTemplate)
        .send("portfolio-notification", "8", event);

    assertDoesNotThrow(() -> publisher.publish(event));
  }
}
