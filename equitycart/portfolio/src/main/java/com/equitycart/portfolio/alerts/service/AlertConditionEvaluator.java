package com.equitycart.portfolio.alerts.service;

import com.equitycart.portfolio.alerts.entity.PriceAlert;
import java.math.BigDecimal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether an alert's condition is met given current/previous prices. Pure logic service;
 * no database access or side effects.
 *
 * <p>Key responsibility: Implement condition matching logic for ABOVE, BELOW, BETWEEN, CROSSING.
 *
 * <p>Design pattern: Stateless utility class (no @Service annotation needed, but added for
 * consistency). Could also be extracted as static methods, but class form allows for future
 * extension (e.g., custom condition types, time-weighted conditions).
 */
@Service
public class AlertConditionEvaluator {

  private static final Logger logger = LogManager.getLogger(AlertConditionEvaluator.class);

  /**
   * Main entry point: Evaluate if alert condition is met given current price.
   *
   * <p>Algorithm: 1. Null safety: reject if condition type unknown or thresholds missing 2. Switch
   * on condition type (ABOVE, BELOW, BETWEEN, CROSSING) 3. Perform comparison 4. Log result (for
   * debugging failed evaluations) 5. Return boolean
   *
   * @param alert the alert rule to evaluate
   * @param currentPrice current market price for alert's ticker
   * @param previousPrice previous market price (needed for CROSSING; can be null for
   *     ABOVE/BELOW/BETWEEN)
   * @return true if condition is met, false otherwise
   * @throws IllegalArgumentException if condition/thresholds invalid
   */
  public boolean isConditionMet(
      PriceAlert alert, BigDecimal currentPrice, BigDecimal previousPrice) {

    if (alert == null || alert.getCondition() == null) {
      logger.error("Alert or condition is null; cannot evaluate");
      throw new IllegalArgumentException("Alert and condition cannot be null");
    }

    logger.debug(
        "Evaluating alert: alertId={}, condition={}, threshold1={}, currentPrice={}",
        alert.getId(),
        alert.getCondition(),
        alert.getThreshold1(),
        currentPrice);

    if (currentPrice == null) {
      logger.warn("Current price is null for alert {}; skipping evaluation", alert.getId());
      return false;
    }

    boolean result =
        switch (alert.getCondition()) {
          case ABOVE -> evaluateAbove(alert.getThreshold1(), currentPrice);
          case BELOW -> evaluateBelow(alert.getThreshold1(), currentPrice);
          case BETWEEN ->
              evaluateBetween(alert.getThreshold1(), alert.getThreshold2(), currentPrice);
          case CROSSING -> evaluateCrossing(alert.getThreshold1(), previousPrice, currentPrice);
        };

    logger.debug(
        "Alert evaluation result: alertId={}, condition={}, result={}",
        alert.getId(),
        alert.getCondition(),
        result);

    return result;
  }

  /**
   * ABOVE condition: currentPrice > threshold.
   *
   * <p>Logic: Simple comparison. Trigger: Every evaluation cycle while price stays above threshold
   * (can trigger multiple times if no cooldown).
   *
   * <p>Example: alert.threshold1 = 150.00 currentPrice = 151.23 → 151.23 > 150.00 → TRUE
   *
   * @param threshold threshold price
   * @param currentPrice current market price
   * @return true if currentPrice > threshold
   */
  private boolean evaluateAbove(BigDecimal threshold, BigDecimal currentPrice) {
    boolean result = currentPrice.compareTo(threshold) > 0;
    logger.trace("ABOVE evaluation: {} > {} = {}", currentPrice, threshold, result);
    return result;
  }

  /**
   * BELOW condition: currentPrice < threshold.
   *
   * <p>Logic: Simple comparison. Trigger: Every evaluation cycle while price stays below threshold.
   *
   * <p>Example: alert.threshold1 = 100.00 currentPrice = 99.50 → 99.50 < 100.00 → TRUE
   *
   * @param threshold threshold price
   * @param currentPrice current market price
   * @return true if currentPrice < threshold
   */
  private boolean evaluateBelow(BigDecimal threshold, BigDecimal currentPrice) {
    boolean result = currentPrice.compareTo(threshold) < 0;
    logger.trace("BELOW evaluation: {} < {} = {}", currentPrice, threshold, result);
    return result;
  }

  /**
   * BETWEEN condition: threshold1 < currentPrice < threshold2.
   *
   * <p>Logic: Range check; both boundaries required. Assumption: threshold1 < threshold2 (validated
   * in service layer before storing alert).
   *
   * <p>Example: alert.threshold1 = 150.00 alert.threshold2 = 160.00 currentPrice = 155.00 → (150.00
   * < 155.00 < 160.00) → TRUE
   *
   * @param threshold1 lower bound
   * @param threshold2 upper bound
   * @param currentPrice current market price
   * @return true if threshold1 < currentPrice < threshold2
   */
  private boolean evaluateBetween(
      BigDecimal threshold1, BigDecimal threshold2, BigDecimal currentPrice) {
    boolean result =
        currentPrice.compareTo(threshold1) > 0 && currentPrice.compareTo(threshold2) < 0;
    logger.trace(
        "BETWEEN evaluation: {} < {} < {} = {}", threshold1, currentPrice, threshold2, result);
    return result;
  }

  /**
   * CROSSING condition: price crossed threshold from below. Requires two data points
   * (previousPrice, currentPrice).
   *
   * <p>Logic: 1. previousPrice <= threshold (was at/below before) 2. currentPrice > threshold (is
   * now above) → Transition detected (price crossed upward)
   *
   * <p>Example: alert.threshold1 = 150.00 previousPrice = 149.80 currentPrice = 151.23 → (149.80 <=
   * 150.00 AND 151.23 > 150.00) → TRUE (crossed)
   *
   * <p>Next evaluation cycle (price stays high): previousPrice = 151.23 currentPrice = 151.50 →
   * (151.23 <= 150.00) FALSE → FALSE (no longer crossing, already crossed)
   *
   * <p>Reason for separate CROSSING: - Without it, ABOVE triggers every cycle (spam) - With
   * CROSSING, triggers only on state change - Users expect "alert me when price reaches X", not
   * "alert me every second while it's above X"
   *
   * <p>Edge case: previousPrice can be null on first evaluation (no prior data). Handling: Treat as
   * crossing-eligible (trigger if currentPrice > threshold).
   *
   * @param threshold crossing threshold
   * @param previousPrice last known price (can be null)
   * @param currentPrice current price
   * @return true if price crossed from <= to >
   */
  private boolean evaluateCrossing(
      BigDecimal threshold, BigDecimal previousPrice, BigDecimal currentPrice) {
    // If no previous price, assume crossing is eligible (first data point)
    if (previousPrice == null) {
      boolean result = currentPrice.compareTo(threshold) > 0;
      logger.trace(
          "CROSSING evaluation (no previous price): {} > {} = {}", currentPrice, threshold, result);
      return result;
    }

    boolean wasBelowOrAt = previousPrice.compareTo(threshold) <= 0;
    boolean isAbove = currentPrice.compareTo(threshold) > 0;

    boolean result = wasBelowOrAt && isAbove;
    logger.trace(
        "CROSSING evaluation: prev={} <= {} = {}, curr={} > {} = {}, result={}",
        previousPrice,
        threshold,
        wasBelowOrAt,
        currentPrice,
        threshold,
        isAbove,
        result);
    return result;
  }

  /**
   * Convenience method: Validate alert's thresholds are sensible. Used by alert creation/update
   * service before storing alert.
   *
   * <p>Validations: - threshold1 must not be null (required for all types) - threshold1 must be
   * positive (no negative prices) - threshold2 required for BETWEEN - threshold2 > threshold1 for
   * BETWEEN
   *
   * @param alert alert to validate
   * @throws IllegalArgumentException if validation fails
   */
  public void validateThresholds(PriceAlert alert) {
    if (alert.getThreshold1() == null || alert.getThreshold1().signum() <= 0) {
      throw new IllegalArgumentException("threshold1 must be a positive value");
    }
    if (alert.getCondition() == com.equitycart.portfolio.alerts.enums.AlertCondition.BETWEEN) {
      if (alert.getThreshold2() == null
          || alert.getThreshold2().compareTo(alert.getThreshold1()) <= 0) {
        throw new IllegalArgumentException("BETWEEN requires threshold2 > threshold1");
      }
    }
  }
}
