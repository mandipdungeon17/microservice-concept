package com.equitycart.notification.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {
  private final MeterRegistry registry;

  public NotificationMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void record(String channel, String status) {
    Counter.builder("equitycart_notifications_total")
        .tag("channel", channel)
        .tag("status", status)
        .register(registry)
        .increment();
  }
}
