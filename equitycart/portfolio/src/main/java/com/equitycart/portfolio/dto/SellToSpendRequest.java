package com.equitycart.portfolio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Inbound DTO for the "Sell to Spend" payment flow. The user specifies which stock to sell, how
 * many shares, the sell price, and which pending order to fund with the proceeds.
 *
 * @param tickerSymbol the stock to sell from the user's portfolio
 * @param quantity number of shares to sell (must be > 0)
 * @param pricePerShare execution price per share — proceeds = quantity × pricePerShare
 * @param orderId the pending order (must be in CREATED status) to fund
 */
public record SellToSpendRequest(
    @NotBlank String tickerSymbol,
    @NotNull @DecimalMin("0.000001") BigDecimal quantity,
    @NotNull @DecimalMin("0.01") BigDecimal pricePerShare,
    @NotNull Long orderId) {}
