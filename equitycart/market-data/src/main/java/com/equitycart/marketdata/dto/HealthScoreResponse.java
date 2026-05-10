package com.equitycart.marketdata.dto;

import java.time.Instant;
import java.util.Map;

/**
 * API response for the company health score endpoint. The {@code score} (0–100) is a composite
 * derived from current price movement, change-percent magnitude, weekly trend, and trading volume.
 * The {@code signals} map explains each signal's contribution so the caller can see why the score
 * is what it is.
 */
public record HealthScoreResponse(
    String symbol, Integer score, Map<String, String> signals, Instant calculatedAt) {}
