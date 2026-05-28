package com.equitycart.portfolio.eventsourcing.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Immutable event document stored in the {@code portfolio_events} MongoDB
 * collection. Represents a
 * single state-change fact in the portfolio's history — never updated or
 * deleted after creation.
 *
 * <p>
 * The event store is append-only: the complete history of a user's portfolio
 * can be
 * reconstructed by replaying events in {@link #sequenceNumber} order. This
 * enables temporal
 * queries, audit trails, and projection rebuilding.
 *
 * <p>
 * Indexed for efficient queries: compound indexes on (userId, timestamp) for
 * timeline access and
 * (userId, tickerSymbol, timestamp) for per-holding history. A unique index on
 * {@link #eventId}
 * prevents duplicate appends on retry.
 */
@Document(collection = "portfolio_events")
@CompoundIndex(def = "{'userId': 1, 'timestamp': 1}")
@CompoundIndex(def = "{'userId': 1, 'tickerSymbol': 1, 'timestamp': 1}")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class PortfolioEvent {
  @Id
  String id;

  @Indexed(unique = true)
  UUID eventId;

  Long userId;

  String eventType;

  String tickerSymbol;

  BigDecimal quantity;

  BigDecimal pricePerShare;

  BigDecimal totalValue;

  Map<String, Object> metadata;

  Instant timestamp;

  Long sequenceNumber;
}
