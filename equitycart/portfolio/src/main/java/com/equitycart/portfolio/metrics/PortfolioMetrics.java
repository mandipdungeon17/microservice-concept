package com.equitycart.portfolio.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PortfolioMetrics {
  private final MeterRegistry registry;

  public PortfolioMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordTrade(String type) {
    Counter.builder("equitycart_trades_executed_total")
        .tag("type", type)
        .register(registry)
        .increment();
  }

  public void recordRewardGranted() {
    Counter.builder("equitycart_rewards_granted_total").register(registry).increment();
  }
}
