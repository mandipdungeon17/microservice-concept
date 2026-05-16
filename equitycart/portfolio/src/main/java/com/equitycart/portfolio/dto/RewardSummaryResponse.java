package com.equitycart.portfolio.dto;

import java.math.BigDecimal;

/**
 * Aggregate statistics for a user's stock-back rewards — counts by status and totals.
 *
 * @param totalRewards total number of rewards (all statuses)
 * @param pendingRewards count of rewards awaiting vesting
 * @param vestedRewards count of rewards already credited to holdings
 * @param totalSharesEarned sum of fractional shares across all rewards
 * @param totalDollarValue sum of grant-time dollar values across all rewards
 */
public record RewardSummaryResponse(
    int totalRewards,
    int pendingRewards,
    int vestedRewards,
    BigDecimal totalSharesEarned,
    BigDecimal totalDollarValue) {}
