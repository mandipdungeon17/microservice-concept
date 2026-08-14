package com.equitycart.portfolio.saga.event;

import com.equitycart.commons.event.SagaLifecycleEvent;
import com.equitycart.order.entity.OutboxEvent;
import com.equitycart.order.enums.OutboxStatus;
import com.equitycart.order.repository.OutboxEventRepository;
import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Writes gifting saga lifecycle events to outbox for Kafka relay (poller/CDC).
 *
 * <p>These events provide observability/audit of saga transitions and are not the source of truth
 * for saga correctness.
 */
@Component
@RequiredArgsConstructor
public class GiftSagaOutboxWriter {

  private static final Logger log = LogManager.getLogger(GiftSagaOutboxWriter.class);

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  /**
   * Persists one gifting lifecycle event to outbox.
   *
   * @param saga current saga snapshot
   * @param eventType event semantic (e.g., GIFT_SAGA_STARTED, GIFT_SAGA_COMPENSATED)
   * @param stepName optional step name for step-level events
   */
  public void writeGiftLifeCycleEvent(GiftSaga saga, String eventType, String stepName) {
    SagaLifecycleEvent event =
        new SagaLifecycleEvent(
            saga.getSagaId(),
            saga.getId(),
            saga.getGiverUserId(),
            eventType,
            saga.getStatus().name(),
            stepName,
            saga.getFailureReason(),
            saga.getUpdatedAt());

    String json = convertObjToJsonString(event);

    OutboxEvent outboxEvent = getOutboxEvent(saga, json, event.getClass().getName(), eventType);

    outboxEventRepository.save(outboxEvent);

    log.info(
        "Gift outbox event written: eventType={}, sagaId={}, giverUserId={}, receiverUserId={}",
        eventType,
        saga.getSagaId(),
        saga.getGiverUserId(),
        saga.getReceiverUserId());
  }

  /** Converts DTO to JSON payload or fails fast with explicit exception. */
  private String convertObjToJsonString(Object event) {
    String json;
    try {
      json = objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.error(
          "Failed to serialize event to JSON: class={}, error={}",
          event.getClass().getName(),
          e.getMessage());
      throw new RuntimeException(e);
    }
    return json;
  }

  /** Builds outbox row targeting the gift-saga topic. */
  private static OutboxEvent getOutboxEvent(
      GiftSaga saga, String json, String className, String eventType) {
    return OutboxEvent.builder()
        .aggregateType("GiftSaga")
        .aggregateId(saga.getId())
        .eventType(eventType)
        .topic("gift-saga")
        .payload(json)
        .payloadType(className)
        .status(OutboxStatus.PENDING)
        .build();
  }
}
