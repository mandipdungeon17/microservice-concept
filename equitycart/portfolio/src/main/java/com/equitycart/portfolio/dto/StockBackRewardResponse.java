package com.equitycart.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for a stock-back reward history item.
 *
 * @param orderId source order that triggered the reward
 * @param tickerSymbol ticker of the rewarded stock
 * @param sharesEarned fractional shares earned
 * @param dollarValue fair market value at grant time (for tax reporting)
 * @param status current vesting status (PENDING, VESTED, or CANCELLED)
 * @param vestingDate date after which the reward becomes eligible for vesting
 * @param vestedAt timestamp when the reward was actually vested (null if still pending)
 */
public record StockBackRewardResponse(
    Long orderId,
    String tickerSymbol,
    BigDecimal sharesEarned,
    BigDecimal dollarValue,
    String status,
    LocalDateTime vestingDate,
    LocalDateTime vestedAt) {}
