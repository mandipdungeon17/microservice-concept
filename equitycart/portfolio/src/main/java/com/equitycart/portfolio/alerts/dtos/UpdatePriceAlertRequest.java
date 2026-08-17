package com.equitycart.portfolio.alerts.dtos;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/** Partial update — any null field is left unchanged. Ticker and condition are immutable. */
public record UpdatePriceAlertRequest(
    @DecimalMin(value = "0.01", message = "Threshold1 must be positive")
        @Digits(integer = 13, fraction = 6)
        BigDecimal threshold1,
    @Digits(integer = 13, fraction = 6) BigDecimal threshold2,
    @Min(value = 1) @Max(value = 1440) Integer cooldownMinutes,
    Boolean active) {}
