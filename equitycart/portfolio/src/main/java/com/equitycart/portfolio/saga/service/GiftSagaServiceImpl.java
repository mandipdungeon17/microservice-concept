package com.equitycart.portfolio.saga.service;

import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.dto.GiftResponse;
import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.equitycart.portfolio.saga.orchestrator.GiftSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Application service adapter for stock gifting saga orchestration.
 *
 * <p>Converts orchestrator domain result into API response DTO used by facade/controller.
 */
@Service
@RequiredArgsConstructor
public class GiftSagaServiceImpl {

  private static final Logger log = LogManager.getLogger(GiftSagaServiceImpl.class);

  private final GiftSagaOrchestrator giftSagaOrchestrator;

  /**
   * Initiates or reuses stock gifting saga based on idempotency key.
   *
   * @param giverUserId authenticated user gifting shares
   * @param request gifting payload
   * @return gift response with saga correlation/status details
   */
  public GiftResponse gift(Long giverUserId, GiftRequest request) {
    log.info(
        "Gift request accepted at service layer: giverUserId={}, receiverUserId={}, ticker={}, qty={}, key={}",
        giverUserId,
        request.receiverId(),
        request.tickerSymbol(),
        request.quantity(),
        request.idempotencyKey());

    GiftSaga saga = giftSagaOrchestrator.startGift(giverUserId, request);

    log.info(
        "Gift request resolved: sagaId={}, status={}, giverUserId={}, receiverUserId={}",
        saga.getSagaId(),
        saga.getStatus(),
        saga.getGiverUserId(),
        saga.getReceiverUserId());

    return new GiftResponse(
        saga.getSagaId(),
        saga.getStatus(),
        saga.getGiverUserId(),
        saga.getReceiverUserId(),
        saga.getTickerSymbol());
  }
}
