package com.equitycart.notification.service.channel.impl;

import com.equitycart.notification.service.channel.api.NotificationChannelStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Console/log-based notification channel — the default for development.
 *
 * <p>Outputs the notification at INFO level with zero infrastructure dependencies. Useful for local
 * development and testing where email/webhook infrastructure is not available. Activate via {@code
 * equitycart.notification.channel=LOG}.
 */
@Component("logChannel")
public class LogChannelStrategy implements NotificationChannelStrategy {

  private static final Logger log = LogManager.getLogger(LogChannelStrategy.class);

  @Override
  public void send(Long userId, String subject, String body) {
    log.info("[NOTIFICATION] userId={}, subject={}, body={}", userId, subject, body);
  }
}
