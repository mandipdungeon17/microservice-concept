package com.equitycart.portfolio.alerts.dtos;

import com.equitycart.portfolio.alerts.enums.AlertCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Payload to create a price alert. Channel selection is NOT here — notification-service decides.
 */
public record CreatePriceAlertRequest(
    @NotBlank(message = "Ticker symbol required") String tickerSymbol,
    @NotNull(message = "Condition required") AlertCondition condition,
    @NotNull(message = "Threshold1 required")
        @DecimalMin(value = "0.01", message = "Threshold1 must be positive")
        @Digits(integer = 13, fraction = 6, message = "Threshold1 must have max 6 decimal places")
        BigDecimal threshold1,
    @Digits(integer = 13, fraction = 6, message = "Threshold2 must have max 6 decimal places")
        BigDecimal threshold2,
    @Min(value = 1, message = "Cooldown must be >= 1 minute")
        @Max(value = 1440, message = "Cooldown must be <= 1440 minutes (1 day)")
        Integer cooldownMinutes) {}
