package com.equitycart.portfolio.alerts.enums;

/**
 * Price alert condition types. Determines how alert's threshold1/threshold2 are interpreted during
 * evaluation.
 *
 * <p>ABOVE/BELOW/BETWEEN are "level conditions" (price at moment of evaluation) CROSSING is a
 * "transition condition" (price crossed threshold since last check)
 */
public enum AlertCondition {

  /**
   * Alert triggers when current price > threshold1.
   *
   * <p>Example: "Notify me when AAPL > $150" Example evaluation: alert.threshold1 = $150
   * currentPrice = $151.23 → $151.23 > $150 → TRUE → alert eligibility checked
   */
  ABOVE,

  /**
   * Alert triggers when current price < threshold1.
   *
   * <p>Example: "Notify me when SPY < $400" Example evaluation: alert.threshold1 = $400
   * currentPrice = $399.50 → $399.50 < $400 → TRUE → alert eligibility checked
   */
  BELOW,

  /**
   * Alert triggers when threshold1 < current price < threshold2. Both thresholds required;
   * threshold1 < threshold2 validated in service.
   *
   * <p>Example: "Notify me when TSLA is between $200–$220" Example evaluation: alert.threshold1 =
   * $200 alert.threshold2 = $220 currentPrice = $215 → ($200 < $215 < $220) → TRUE → alert
   * eligibility checked
   */
  BETWEEN,

  /**
   * Alert triggers when price CROSSES threshold1 from below. Requires comparing previousPrice
   * against threshold1.
   *
   * <p>Rationale for separate CROSSING: - ABOVE fires every evaluation cycle while price stays >
   * $150 (spammy) - CROSSING fires only once when price changes from ≤$150 to >$150 (cleaner)
   *
   * <p>Example: "Notify me when AAPL crosses above $150" Example evaluation (assuming previous
   * price was $149.80): alert.threshold1 = $150 previousPrice = $149.80 currentPrice = $151.23 →
   * ($149.80 <= $150 AND $151.23 > $150) → TRUE → alert eligibility checked
   *
   * <p>If same price point evaluated again: previousPrice = $151.23 currentPrice = $151.50 →
   * ($151.23 <= $150) FALSE → does NOT trigger again
   */
  CROSSING;
}
