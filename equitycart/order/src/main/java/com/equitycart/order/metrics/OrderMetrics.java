package com.equitycart.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class OrderMetrics {
  private final Counter ordersPlaced;
  private final Counter ordersFailed;
  private final Timer orderPlacementTimer;

  public OrderMetrics(MeterRegistry registry) {
    this.ordersPlaced = Counter.builder("equitycart_orders_placed_total").register(registry);

    this.ordersFailed = Counter.builder("equitycart_orders_failed_total").register(registry);

    this.orderPlacementTimer =
        Timer.builder("equitycart_order_placement_duration_seconds")
            .publishPercentileHistogram()
            .register(registry);
  }

  public void recordPlaced() {
    ordersPlaced.increment();
  }

  public void recordFailed() {
    ordersFailed.increment();
  }

  public void recordPlacementDurationNanos(long nanos) {
    orderPlacementTimer.record(nanos, TimeUnit.NANOSECONDS);
  }
}
