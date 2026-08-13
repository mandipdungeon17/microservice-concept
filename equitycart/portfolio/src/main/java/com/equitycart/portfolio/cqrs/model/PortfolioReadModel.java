package com.equitycart.portfolio.cqrs.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * CQRS Read Model: Denormalized portfolio snapshot stored in MongoDB.
 *
 * <p>This document is updated asynchronously by PortfolioReadModelSynchronizer when events are
 * appended to portfolio_events collection.
 *
 * <p>Used for fast reads only; all writes go through PostgreSQL write model.
 */
@Document(collection = "portfolio_read_models")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioReadModel {
  @Id private String id; // MongoDB ObjectId

  @Indexed(unique = true)
  private Long userId;

  private Long portfolioId;

  private BigDecimal totalCostBasis;

  private Integer holdingCount;

  @Builder.Default private List<ReadModelHolding> holdings = new ArrayList<>();

  @Builder.Default private ReadModelRewards rewards = new ReadModelRewards();

  private LocalDateTime lastUpdatedAt;

  private Long version; // Timestamp for optimistic concurrency
}
