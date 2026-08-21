package com.equitycart.notification.consumer;

import static org.mockito.Mockito.verify;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.notification.service.api.NotificationDispatcher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

  @Mock private NotificationDispatcher notificationDispatcher;
  @InjectMocks private NotificationConsumer consumer;

  @Test
  void handleNotificationEventShouldDelegateToDispatcher() {
    NotificationEvent event =
        new NotificationEvent(
            10L,
            "TRADE_EXECUTED",
            "AAPL",
            new BigDecimal("2"),
            new BigDecimal("100"),
            new BigDecimal("200"),
            Map.of("source", "test"),
            LocalDateTime.now());

    consumer.handleNotificationEvent(event);

    verify(notificationDispatcher).dispatch(event);
  }
}

