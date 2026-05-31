package com.equitycart.portfolio.eventsourcing.service.api;

import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Append-only event store interface for portfolio state changes. Implementations persist events to
 * MongoDB as immutable documents, providing a complete audit trail and enabling state
 * reconstruction via projection replay.
 *
 * <p>Designed as best-effort: failures to append do NOT propagate to the caller — the primary
 * PostgreSQL state update proceeds regardless. This prevents MongoDB outages from breaking core
 * portfolio operations.
 */
public interface PortfolioEventStore {

  /**
   * Appends a new event to the store. Assigns a monotonically increasing sequence number per user
   * and persists the event with the current timestamp.
   *
   * @param userId portfolio owner
   * @param eventType classification of the state change
   * @param tickerSymbol affected stock (null for non-holding informational events)
   * @param quantity shares involved in the operation
   * @param pricePerShare execution price (null or ZERO for reward events)
   * @param totalValue monetary value of the operation (quantity × price, or reward dollar value)
   * @param metadata flexible context map (orderId, sagaId, tradeType, etc.)
   */
  void append(
      Long userId,
      PortfolioEventType eventType,
      String tickerSymbol,
      BigDecimal quantity,
      BigDecimal pricePerShare,
      BigDecimal totalValue,
      Map<String, Object> metadata);
}
