package com.equitycart.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Top-level portfolio analytics dashboard view — cost basis summary, per-holding breakdown with
 * portfolio weight, and aggregated reward statistics.
 *
 * @param userId the portfolio owner
 * @param holdingCount number of distinct stock positions
 * @param totalCostBasis total investment across all holdings (sum of qty × avgBuyPrice)
 * @param holdings per-holding analytics with cost basis and weight
 * @param rewardSummary aggregated stock-back reward statistics
 */
public record PortfolioAnalyticsResponse(
    Long userId,
    int holdingCount,
    BigDecimal totalCostBasis,
    List<HoldingAnalyticsResponse> holdings,
    RewardSummaryResponse rewardSummary) {}
