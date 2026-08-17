package com.equitycart.notification.service.impl;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.notification.entity.NotificationLog;
import com.equitycart.notification.enums.NotificationChannel;
import com.equitycart.notification.enums.NotificationStatus;
import com.equitycart.notification.enums.NotificationType;
import com.equitycart.notification.metrics.NotificationMetrics;
import com.equitycart.notification.repository.NotificationLogRepository;
import com.equitycart.notification.service.api.NotificationDispatcher;
import com.equitycart.notification.service.channel.api.NotificationChannelStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Core dispatcher that routes notification events to the active channel strategy and persists audit
 * logs.
 *
 * <p>Implements the <b>Strategy Pattern</b>: all {@link NotificationChannelStrategy} beans are
 * auto-injected into a {@code Map<String, NotificationChannelStrategy>} keyed by bean name. The
 * active channel is resolved at runtime from {@code equitycart.notification.channel} config using
 * the naming convention {@code <lowercase>Channel}.
 *
 * <p>Flow: (1) resolve strategy bean → (2) build subject/body from event type → (3) invoke
 * channel.send() → (4) persist {@link com.equitycart.notification.entity.NotificationLog} with SENT
 * or FAILED status.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatcherImpl implements NotificationDispatcher {

  private static final Logger log = LogManager.getLogger(NotificationDispatcherImpl.class);

  // Spring auto-injects all implementations keyed by their bean name (logChannel, emailChannel,
  // webhookChannel)
  private final Map<String, NotificationChannelStrategy> notificationChannelStrategies;
  private final NotificationLogRepository notificationLogRepository;
  private final ObjectMapper objectMapper;
  private final NotificationMetrics notificationMetrics;

  @Value("${equitycart.notification.channel}")
  String activeChannel;

  @Override
  public void dispatch(NotificationEvent event) {
    try {
      String beanName = activeChannel.toLowerCase() + "Channel";
      NotificationChannelStrategy strategy = notificationChannelStrategies.get(beanName);

      String subject;
      String body;

      switch (event.notificationType()) {
        case "TRADE_EXECUTED":
          subject =
              "Trade Executed: Executed " + event.quantity() + " shares of " + event.tickerSymbol();
          body =
              String.format(
                  "Your trade for %s shares of %s at $%s has been executed.",
                  event.quantity(), event.tickerSymbol(), event.pricePerShare());
          break;
        case "REWARD_VESTED":
          subject =
              "Reward Vested: "
                  + event.quantity()
                  + " shares of "
                  + event.tickerSymbol()
                  + " vested";
          body =
              String.format(
                  "Congratulations! %s shares of %s have vested in your account.",
                  event.quantity(), event.tickerSymbol());
          break;
        case "SELL_TO_SPEND_COMPLETED":
          subject =
              "Sell-to-Spend Completed: Sold "
                  + event.quantity()
                  + " shares of "
                  + event.tickerSymbol();
          body =
              String.format(
                  "Your sell-to-spend order for %s shares of %s at $%s has been completed.",
                  event.quantity(), event.tickerSymbol(), event.pricePerShare());
          break;
        case "SELL_TO_SPEND_FAILED":
          subject =
              "Sell-to-Spend Failed: Failed to sell "
                  + event.quantity()
                  + " shares of "
                  + event.tickerSymbol();
          body =
              String.format(
                  "Unfortunately, your sell-to-spend order for %s shares of %s at $%s has failed. Please try again.",
                  event.quantity(), event.tickerSymbol(), event.pricePerShare());
          break;
        case "PRICE_ALERT_TRIGGERED":
          subject = "Price Alert: " + event.tickerSymbol();
          body =
              String.format(
                  "Your price alert for %s triggered. Current price: $%s.",
                  event.tickerSymbol(), event.pricePerShare());
          break;
        default:
          log.warn(
              "Received unknown notification type: {}. No notification will be sent.",
              event.notificationType());
          return;
      }
      strategy.send(event.userId(), subject, body);

      String metaData = objectMapper.writeValueAsString(event.metadata());

      NotificationLog notificationLog =
          NotificationLog.builder()
              .userId(event.userId())
              .notificationType(NotificationType.valueOf(event.notificationType()))
              .notificationChannel(NotificationChannel.valueOf(activeChannel))
              .notificationStatus(NotificationStatus.SENT)
              .subject(subject)
              .body(body)
              .metadata(metaData)
              .build();

      notificationLogRepository.save(notificationLog);
      notificationMetrics.record(activeChannel, "SUCCESS");
    } catch (Exception e) {
      log.error(
          "Failed to dispatch notification for event: {}. Error: {}", event, e.getMessage(), e);
      NotificationLog notificationLog =
          NotificationLog.builder()
              .userId(event.userId())
              .notificationType(NotificationType.valueOf(event.notificationType()))
              .notificationChannel(NotificationChannel.valueOf(activeChannel))
              .notificationStatus(NotificationStatus.FAILED)
              .subject("Failed to send notification for " + event.notificationType())
              .body("An error occurred while sending your notification. Please try again later.")
              .metadata("{}")
              .errorMessage(e.getMessage())
              .build();
      notificationLogRepository.save(notificationLog);
      notificationMetrics.record(activeChannel, "FAILED");
    }
  }
}
