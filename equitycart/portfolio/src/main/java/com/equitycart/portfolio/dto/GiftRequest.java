package com.equitycart.portfolio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * API request payload for stock gifting.
 *
 * @param receiverId target user receiving gifted shares
 * @param tickerSymbol stock symbol to transfer
 * @param quantity gifted share quantity (fractional supported)
 * @param idempotencyKey client-provided dedupe key to prevent duplicate gifts on retries
 */
public record GiftRequest(
    @NotNull Long receiverId,
    @NotBlank String tickerSymbol,
    @NotNull @DecimalMin(value = "0.000001") BigDecimal quantity,
    @NotBlank String idempotencyKey) {}
