package com.equitycart.portfolio.cqrs.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CQRS read model response for fast portfolio queries. Contains a denormalized, pre-computed
 * snapshot of the user's holdings and portfolio metadata from MongoDB.
 *
 * <p>Used by {@link PortfolioReadController} to return cached portfolio data with <10ms latency.
 *
 * @param userId the unique user identifier
 * @param portfolioId the unique portfolio record ID
 * @param holdingCount the total number of different stocks held
 * @param totalCostBasis the sum of all cost bases (purchase investments)
 * @param holdings list of individual holding snapshots
 * @param lastUpdatedAt timestamp when this snapshot was last refreshed from write model
 */
public record PortfolioReadResponse(
    Long userId,
    Long portfolioId,
    Integer holdingCount,
    BigDecimal totalCostBasis,
    List<HoldingReadResponse> holdings,
    LocalDateTime lastUpdatedAt) {}
