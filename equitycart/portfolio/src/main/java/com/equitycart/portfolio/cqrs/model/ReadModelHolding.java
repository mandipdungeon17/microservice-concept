package com.equitycart.portfolio.cqrs.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CQRS read model: Denormalized holding snapshot stored within a MongoDB PortfolioReadModel
 * document.
 *
 * <p>Represents a single stock position with precomputed cost basis, optimized for fast API
 * responses without requiring stream aggregation.
 *
 * <p>All values are read-only from the perspective of this document; updates flow through the write
 * model (PostgreSQL) and are denormalized asynchronously by the projection layer.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReadModelHolding {
  /** The stock ticker symbol (e.g., AAPL) */
  private String tickerSymbol;

  /** The total number of shares owned */
  private BigDecimal quantity;

  /** The weighted average purchase price per share */
  private BigDecimal averageBuyPrice;

  /** Precomputed cost basis: quantity × averageBuyPrice */
  private BigDecimal costBasis;
}
