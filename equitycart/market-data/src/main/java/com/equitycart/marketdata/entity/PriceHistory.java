package com.equitycart.marketdata.entity;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing a historical price snapshot for a stock symbol. Each document is a
 * point-in-time record saved on cache miss. A TTL index on {@code fetchedAt} auto-deletes documents
 * after 90 days.
 */
@Document(collection = "price_history")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PriceHistory {
  @Id private String id;

  private String symbol;

  private BigDecimal price;

  private BigDecimal change;

  private String changePercent;

  private Long volume;

  private String tradingDay;

  @Indexed(expireAfter = "90d")
  private Instant fetchedAt;
}
