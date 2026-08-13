package com.equitycart.portfolio.cqrs.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CQRS read model: Denormalized reward summary snapshot embedded within a PortfolioReadModel
 * document.
 *
 * <p>Provides a quick summary of stock-back reward status and value without requiring aggregation
 * queries on the write model. Updated asynchronously via projection events.
 *
 * <p>All counters and totals are precomputed and denormalized for fast reads. Vesting status
 * breakdown enables analytics without secondary queries.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReadModelRewards {
  /** Total number of rewards ever granted to this user */
  @Builder.Default private Integer totalCount = 0;

  /** Number of rewards still in PENDING status (vesting window) */
  @Builder.Default private Integer pendingCount = 0;

  /** Number of rewards that have reached VESTED status */
  @Builder.Default private Integer vestedCount = 0;

  /** Total shares earned across all stock-back rewards */
  @Builder.Default private BigDecimal totalSharesEarned = BigDecimal.ZERO;

  /** Total dollar value of all rewards at grant time */
  @Builder.Default private BigDecimal totalDollarValue = BigDecimal.ZERO;
}
