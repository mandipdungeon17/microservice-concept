package com.equitycart.portfolio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO for adding or updating a holding in the user's portfolio.
 *
 * @param tickerSymbol exchange ticker symbol (e.g. "AAPL")
 * @param quantity number of shares to add (fractional allowed, minimum 0.000001)
 * @param price purchase price per share (minimum 0.01; ZERO is set by the system for rewards)
 */
public record HoldingRequest(
    @NotBlank String tickerSymbol,
    @NotNull @DecimalMin("0.000001") BigDecimal quantity,
    @NotNull @DecimalMin("0.01") BigDecimal price) {}
