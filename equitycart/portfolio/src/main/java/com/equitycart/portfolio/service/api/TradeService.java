package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.entity.Holding;
import java.math.BigDecimal;

/**
 * Service contract for manual trade execution. Accepts primitives (not DTOs) so internal callers
 * (future Kafka consumers, scheduled jobs) can invoke without constructing REST-layer objects.
 */
public interface TradeService {

  /**
   * Executes a buy or sell trade for the given user.
   *
   * @param userId the trading user's ID
   * @param tickerSymbol exchange ticker (e.g. "AAPL")
   * @param qty number of shares to trade
   * @param price execution price per share (used for buy cost basis; ignored on sell)
   * @param tradeType "BUY" or "SELL"
   * @return the resulting holding after the trade (quantity zero if fully sold)
   * @throws com.equitycart.commons.exception.InvalidStatusTransitionException if tradeType is
   *     invalid
   * @throws com.equitycart.commons.exception.InsufficientSharesException if selling more than owned
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if selling a non-existent
   *     holding
   */
  Holding executeTrade(
      Long userId, String tickerSymbol, BigDecimal qty, BigDecimal price, String tradeType);
}
