package com.equitycart.portfolio.dto;

import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import java.util.UUID;

/**
 * API response payload for stock gifting saga initiation/result.
 *
 * @param sagaId correlation ID for tracking saga progress
 * @param status current/terminal saga status
 * @param giverUserId source user from whom shares were debited
 * @param receiverUserId destination user to whom shares were credited
 * @param tickerSymbol transferred stock symbol
 */
public record GiftResponse(
    UUID sagaId,
    GiftSagaStatus status,
    Long giverUserId,
    Long receiverUserId,
    String tickerSymbol) {}
