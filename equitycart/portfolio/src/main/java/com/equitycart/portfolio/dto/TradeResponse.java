package com.equitycart.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Outbound DTO representing the result of an executed trade. For a BUY, reflects the updated
 * holding state (new quantity and weighted-average price). For a SELL, reflects the holding after
 * reduction (quantity zero if fully sold).
 *
 * @param tickerSymbol the traded ticker
 * @param quantity remaining shares after the trade
 * @param executedPrice the average buy price of the holding (cost basis, not the trade price)
 * @param tradeType "BUY" or "SELL"
 * @param executedAt timestamp of the holding update
 */
public record TradeResponse(
    String tickerSymbol,
    BigDecimal quantity,
    BigDecimal executedPrice,
    String tradeType,
    LocalDateTime executedAt) {}
