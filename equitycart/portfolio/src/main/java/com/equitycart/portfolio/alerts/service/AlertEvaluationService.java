package com.equitycart.portfolio.alerts.service;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.service.api.MarketDataService;
import com.equitycart.portfolio.alerts.entity.AlertAuditLog;
import com.equitycart.portfolio.alerts.entity.PriceAlert;
import com.equitycart.portfolio.alerts.enums.AlertEventType;
import com.equitycart.portfolio.alerts.repository.AlertAuditLogRepository;
import com.equitycart.portfolio.alerts.repository.PriceAlertRepository;
import com.equitycart.portfolio.event.NotificationPublisher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled orchestrator that evaluates every active price alert against the latest market price and
 * fires notifications when a rule is satisfied.
 *
 * <p><b>Per-cycle flow</b> (see {@link #evaluateActiveAlerts()}):
 *
 * <ol>
 *   <li>Load all active alerts ({@code PriceAlertRepository.findByActiveTrue()}).
 *   <li>For each alert, fetch its ticker's current price from {@link MarketDataService} (Redis-cached
 *       upstream, so repeated tickers are cheap).
 *   <li>Delegate the condition check to {@link AlertConditionEvaluator}. The alert's previous price
 *       ({@code lastEvaluatedPrice}) supplies the "before" value needed for CROSSING detection.
 *   <li>Persist the freshly observed price so the next cycle can detect a crossing.
 *   <li>If the condition is met <em>and</em> the cooldown has elapsed, publish a {@link
 *       com.equitycart.commons.event.NotificationEvent} via {@link NotificationPublisher} (the
 *       notification service performs actual delivery), stamp {@code lastTriggeredAt}, and write a
 *       {@code TRIGGERED} audit row.
 * </ol>
 *
 * <p><b>Error handling:</b> a failure evaluating one alert is caught, logged at ERROR, and recorded
 * as an {@code EVALUATION_ERROR} audit row — it never aborts the rest of the cycle. Notification
 * publishing is best-effort (see {@link NotificationPublisher}); a publish failure is logged and does
 * not roll back the trigger.
 *
 * <p><b>Design notes:</b> the evaluator holds no mutable state (thread-safe); the database is the
 * single source of truth with {@code @Version} optimistic locking guarding concurrent user edits. A
 * full scan every cycle is acceptable at the current scale; sharding by ticker is the future
 * optimization if the active-alert count grows large.
 */
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {

  private static final Logger log = LogManager.getLogger(AlertEvaluationService.class);

  private final PriceAlertRepository alertRepository;
  private final AlertAuditLogRepository auditLogRepository;
  private final AlertConditionEvaluator evaluator;
  private final MarketDataService marketDataService;
  private final NotificationPublisher notificationPublisher;

  /**
   * Scheduled entry point — runs on a fixed delay (default 5s; see {@code
   * equitycart.alerts.evaluation.*} config). Each invocation evaluates every active alert once. A
   * fixed <em>delay</em> (not rate) means the next run only starts after the previous one finishes,
   * so cycles never overlap and no distributed lock is required at the current scale.
   */
  @Scheduled(
      fixedDelayString = "${equitycart.alerts.evaluation.fixed-delay-ms:5000}",
      initialDelayString = "${equitycart.alerts.evaluation.initial-delay-ms:10000}")
  public void evaluateActiveAlerts() {
    long startTime = System.currentTimeMillis();
    log.info("Starting alert evaluation cycle");

    List<PriceAlert> alerts = alertRepository.findByActiveTrue();
    if (alerts.isEmpty()) {
      log.debug("No active alerts to evaluate; skipping cycle");
      return;
    }
    log.debug("Evaluating {} active alerts", alerts.size());

    for (PriceAlert alert : alerts) {
      try {
        evaluateOne(alert);
      } catch (Exception e) {
        log.error("Error evaluating alertId={}: {}", alert.getId(), e.getMessage(), e);
        recordAudit(alert, AlertEventType.EVALUATION_ERROR, null, e.getMessage());
      }
    }
    log.info(
        "Alert evaluation cycle completed: evaluated={}, duration={}ms",
        alerts.size(),
        System.currentTimeMillis() - startTime);
  }

  /**
   * Evaluates one alert against the latest market price and reacts to the outcome.
   *
   * <p>The current price is always written back to {@code lastEvaluatedPrice} (even when the
   * condition is not met) so the next cycle has a "before" value for CROSSING detection. When the
   * condition holds and the cooldown has elapsed the alert is triggered; when it holds but the
   * cooldown is still active a {@code COOLDOWN_SKIPPED} audit row is recorded instead (no
   * notification) to prevent spam.
   *
   * @param alert the active alert to evaluate
   */
  private void evaluateOne(PriceAlert alert) {
    StockPriceResponse priceResponse = marketDataService.getPrice(alert.getTickerSymbol());
    if (priceResponse == null || priceResponse.price() == null) {
      log.warn(
          "No price for ticker={}, skipping alertId={}", alert.getTickerSymbol(), alert.getId());
      return;
    }

    BigDecimal currentPrice = priceResponse.price();
    BigDecimal previousPrice = alert.getLastEvaluatedPrice();
    log.debug(
        "Evaluating alertId={} ticker={}: currentPrice={}, previousPrice={}",
        alert.getId(),
        alert.getTickerSymbol(),
        currentPrice,
        previousPrice);

    boolean conditionMet = evaluator.isConditionMet(alert, currentPrice, previousPrice);
    // Always remember the latest price so the NEXT cycle can detect a CROSSING transition.
    alert.setLastEvaluatedPrice(currentPrice);

    if (conditionMet && alert.isCooldownExpired()) {
      log.debug(
          "Condition met and cooldown expired for alertId={}; triggering alert", alert.getId());
      triggerAlert(alert, currentPrice);
    } else if (conditionMet) {
      log.debug(
          "alertId={} met but cooling down until {}",
          alert.getId(),
          alert.getLastTriggeredAt().plusMinutes(alert.getCooldownMinutes()));
      recordAudit(
          alert, AlertEventType.COOLDOWN_SKIPPED, currentPrice, "Condition met but within cooldown");
      alertRepository.save(alert); // Persist the updated lastEvaluatedPrice.
    } else {
      log.debug("Condition not met for alertId={}; skipping", alert.getId());
      alertRepository.save(alert); // Persist the updated lastEvaluatedPrice only.
    }
  }

  /**
   * Fires a triggered alert: publishes a notification event, stamps {@code lastTriggeredAt} (which
   * starts the cooldown window), and records a {@code TRIGGERED} audit row.
   *
   * <p>Delivery is delegated to the notification service via Kafka — this method only publishes the
   * intent through {@link NotificationPublisher}. The order is publish → persist trigger time →
   * audit: publishing is best-effort, so we still record the trigger locally even if the broker is
   * temporarily unavailable.
   *
   * @param alert the alert whose condition was satisfied
   * @param currentPrice the market price at the moment of triggering
   */
  private void triggerAlert(PriceAlert alert, BigDecimal currentPrice) {
    String message =
        String.format(
            "%s %s crossed %s (current %s)",
            alert.getTickerSymbol(), alert.getCondition(), alert.getThreshold1(), currentPrice);

    // Build the shared notification contract; the notification service picks the delivery channel.
    NotificationEvent notification =
        new NotificationEvent(
            alert.getUserId(),
            "PRICE_ALERT_TRIGGERED",
            alert.getTickerSymbol(),
            null, // quantity — not applicable to a price alert
            currentPrice, // pricePerShare carries the trigger price
            null, // totalValue — not applicable
            Map.of(
                "alertId", alert.getId(),
                "condition", alert.getCondition().name(),
                "threshold1", alert.getThreshold1()),
            LocalDateTime.now());
    log.debug("Publishing PRICE_ALERT_TRIGGERED notification: alertId={}", alert.getId());

    // Publish the notification intent (fire-and-forget to Kafka).
    notificationPublisher.publish(notification);

    // Stamp the trigger time — this opens the cooldown window for subsequent cycles.
    alert.setLastTriggeredAt(LocalDateTime.now());
    alertRepository.save(alert);

    // Record the trigger in the audit trail.
    recordAudit(alert, AlertEventType.TRIGGERED, currentPrice, message);
    log.info(
        "Alert triggered: alertId={}, userId={}, ticker={}, price={}",
        alert.getId(),
        alert.getUserId(),
        alert.getTickerSymbol(),
        currentPrice);
  }

  /**
   * Records an audit-trail row for an alert lifecycle event (triggered, cooldown-skipped, error).
   *
   * @param alert the alert the event relates to
   * @param type the event category
   * @param price market price at the moment of the event, or {@code null} for non-price events
   * @param message short human-readable context (or the error message for {@code EVALUATION_ERROR})
   */
  private void recordAudit(
      PriceAlert alert, AlertEventType type, BigDecimal price, String message) {
    auditLogRepository.save(
        AlertAuditLog.builder()
            .priceAlertId(alert.getId())
            .userId(alert.getUserId())
            .tickerSymbol(alert.getTickerSymbol())
            .eventType(type)
            .priceAtEvent(price)
            .message(message)
            .build());
  }
}
