package com.equitycart.notification.service.channel.impl;

import com.equitycart.notification.service.channel.api.NotificationChannelStrategy;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Webhook-based notification channel that POSTs a JSON payload to a configurable external URL.
 *
 * <p>Simulates integration with external systems (Slack, Discord, custom webhooks). Uses Spring
 * WebFlux's {@link WebClient} for non-blocking HTTP calls, with {@code .block()} at the boundary to
 * keep the dispatch synchronous from the caller's perspective.
 *
 * <p>Fire-and-forget semantics: failures are caught and logged at WARN level without retry.
 * Activate via {@code equitycart.notification.channel=WEBHOOK}. Target URL configured via {@code
 * equitycart.notification.webhook-url}.
 */
@Component("webhookChannel")
@RequiredArgsConstructor
public class WebhookChannelStrategy implements NotificationChannelStrategy {

  private static final Logger log = LogManager.getLogger(WebhookChannelStrategy.class);

  private final WebClient.Builder webClientBuilder;

  @Value("${equitycart.notification.webhook-url}")
  String webhookUrl;

  @Override
  public void send(Long userId, String subject, String body) {
    log.info(
        "Sending webhook notification to userId: {}, subject: {}, body: {}", userId, subject, body);
    Map<String, Object> payload = new HashMap<>();

    payload.put("userId", userId);
    payload.put("subject", subject);
    payload.put("body", body);
    payload.put("timestamp", System.currentTimeMillis());

    try {
      webClientBuilder
          .build()
          .post()
          .uri(webhookUrl)
          .bodyValue(payload)
          .retrieve()
          .toBodilessEntity()
          .block();

      log.info("Webhook notification sent");
    } catch (Exception e) {
      log.warn(
          "Failed to send webhook notification for userId: {}, subject: {}", userId, subject, e);
    }
  }
}
