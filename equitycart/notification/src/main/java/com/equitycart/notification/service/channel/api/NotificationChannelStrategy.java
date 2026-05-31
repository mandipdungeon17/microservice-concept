package com.equitycart.notification.service.channel.api;

/**
 * Strategy interface (GoF Strategy Pattern) for notification delivery channels.
 *
 * <p>Each implementation represents a distinct delivery mechanism (email, webhook, console log).
 * Spring auto-collects all implementations into a {@code Map<String, NotificationChannelStrategy>}
 * keyed by bean name — the dispatcher selects one at runtime based on the {@code
 * equitycart.notification.channel} property.
 *
 * <p>Bean naming convention: {@code @Component("<channel>Channel")} where {@code <channel>} is the
 * lowercase channel name (e.g., "logChannel", "emailChannel", "webhookChannel").
 */
public interface NotificationChannelStrategy {

  void send(Long userId, String subject, String body);
}
