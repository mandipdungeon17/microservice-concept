package com.equitycart.portfolio.dto;

import java.util.List;

/**
 * Response DTO representing a user's full portfolio — the user ID and all holdings.
 *
 * @param userId the owning user's ID
 * @param holdings all stock holdings in this portfolio
 */
public record PortfolioResponse(Long userId, List<HoldingResponse> holdings) {}
