package com.equitycart.notification.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.notification.metrics.NotificationMetrics;
import com.equitycart.notification.repository.NotificationLogRepository;
import com.equitycart.notification.service.channel.api.NotificationChannelStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherImplTest {

  @Mock private NotificationChannelStrategy logChannel;
  @Mock private NotificationLogRepository notificationLogRepository;
  @Mock private ObjectMapper objectMapper;
  @Mock private NotificationMetrics notificationMetrics;

  private NotificationDispatcherImpl dispatcher;

  @BeforeEach
  void init() {
    dispatcher =
        new NotificationDispatcherImpl(
            Map.of("logChannel", logChannel), notificationLogRepository, objectMapper, notificationMetrics);
    ReflectionTestUtils.setField(dispatcher, "activeChannel", "LOG");
  }

  @Test
  void dispatchShouldIgnoreUnknownNotificationType() {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "UNKNOWN_TYPE",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("10"),
            Map.of(),
            LocalDateTime.now());

    dispatcher.dispatch(event);

    verify(logChannel, never()).send(any(), any(), any());
    verify(notificationLogRepository, never()).save(any());
  }

  @Test
  void dispatchShouldSendAndPersistSuccessLog() throws Exception {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "TRADE_EXECUTED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("10"),
            Map.of("k", "v"),
            LocalDateTime.now());
    when(objectMapper.writeValueAsString(event.metadata())).thenReturn("{\"k\":\"v\"}");

    dispatcher.dispatch(event);

    verify(logChannel).send(1L, "Trade Executed: Executed 1 shares of AAPL", "Your trade for 1 shares of AAPL at $10 has been executed.");
    verify(notificationLogRepository).save(any());
    verify(notificationMetrics).record("LOG", "SUCCESS");
  }

  @Test
  void dispatchShouldPersistFailedLogWhenChannelThrows() throws Exception {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "REWARD_VESTED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("10"),
            Map.of(),
            LocalDateTime.now());
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(logChannel).send(any(), any(), any());

    dispatcher.dispatch(event);

    verify(notificationLogRepository).save(any());
    verify(notificationMetrics).record("LOG", "FAILED");
  }

  @Test
  void dispatchShouldSendSellToSpendCompletedNotification() throws Exception {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "SELL_TO_SPEND_COMPLETED",
            "AAPL",
            new BigDecimal("2"),
            new BigDecimal("10"),
            new BigDecimal("20"),
            Map.of(),
            LocalDateTime.now());
    when(objectMapper.writeValueAsString(event.metadata())).thenReturn("{}");

    dispatcher.dispatch(event);

    verify(logChannel)
        .send(
            1L,
            "Sell-to-Spend Completed: Sold 2 shares of AAPL",
            "Your sell-to-spend order for 2 shares of AAPL at $10 has been completed.");
    verify(notificationMetrics).record("LOG", "SUCCESS");
  }

  @Test
  void dispatchShouldPersistFailedLogWhenMetadataSerializationFails() throws Exception {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "PRICE_ALERT_TRIGGERED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("10"),
            Map.of("k", "v"),
            LocalDateTime.now());
    when(objectMapper.writeValueAsString(event.metadata())).thenThrow(new RuntimeException("json"));

    dispatcher.dispatch(event);

    verify(notificationLogRepository).save(any());
    verify(notificationMetrics).record("LOG", "FAILED");
  }

  @Test
  void dispatchShouldPersistFailedWhenConfiguredStrategyMissing() {
    ReflectionTestUtils.setField(dispatcher, "activeChannel", "EMAIL");
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "TRADE_EXECUTED",
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("10"),
            new BigDecimal("10"),
            Map.of(),
            LocalDateTime.now());

    dispatcher.dispatch(event);

    verify(notificationLogRepository).save(any());
    verify(notificationMetrics).record("EMAIL", "FAILED");
  }

  @Test
  void dispatchShouldSendSellToSpendFailedNotification() throws Exception {
    NotificationEvent event =
        new NotificationEvent(
            1L,
            "SELL_TO_SPEND_FAILED",
            "AAPL",
            new BigDecimal("2"),
            new BigDecimal("10"),
            new BigDecimal("20"),
            Map.of(),
            LocalDateTime.now());
    when(objectMapper.writeValueAsString(event.metadata())).thenReturn("{}");

    dispatcher.dispatch(event);

    verify(logChannel)
        .send(
            1L,
            "Sell-to-Spend Failed: Failed to sell 2 shares of AAPL",
            "Unfortunately, your sell-to-spend order for 2 shares of AAPL at $10 has failed. Please try again.");
    verify(notificationMetrics).record("LOG", "SUCCESS");
  }
}
