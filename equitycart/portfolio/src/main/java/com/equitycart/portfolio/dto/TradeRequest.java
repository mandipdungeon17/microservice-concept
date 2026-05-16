package com.equitycart.portfolio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Inbound DTO for executing a manual trade (buy or sell). Validated at the controller layer via
 * {@code @Valid}; the {@code tradeType} string is parsed to {@link
 * com.equitycart.portfolio.enums.TradeType} in the service layer.
 *
 * @param tickerSymbol exchange ticker symbol (e.g. "AAPL")
 * @param quantity number of shares to trade (must be > 0)
 * @param price execution price per share
 * @param tradeType "BUY" or "SELL" — validated as a string to keep DTOs decoupled from enums
 */
public record TradeRequest(
    @NotBlank String tickerSymbol,
    @NotNull @DecimalMin("0.000001") BigDecimal quantity,
    @NotNull @DecimalMin("0.01") BigDecimal price,
    @NotNull String tradeType) {}
