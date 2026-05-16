package com.equitycart.portfolio.dto;

import java.math.BigDecimal;

/**
 * Outbound DTO for a completed "Sell to Spend" transaction. Confirms the order was funded, the
 * shares sold, and the proceeds generated.
 *
 * @param orderId the order that was funded and confirmed
 * @param tickerSymbol the stock that was sold
 * @param sharesSold number of shares liquidated
 * @param saleProceeds total cash generated (quantity × pricePerShare)
 * @param orderStatus the order's new status (CONFIRMED)
 */
public record SellToSpendResponse(
    Long orderId,
    String tickerSymbol,
    BigDecimal sharesSold,
    BigDecimal saleProceeds,
    String orderStatus) {}
