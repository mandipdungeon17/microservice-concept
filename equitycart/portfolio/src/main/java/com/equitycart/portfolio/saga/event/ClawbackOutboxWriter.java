package com.equitycart.portfolio.saga.event;

import com.equitycart.commons.event.SagaLifecycleEvent;
import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.saga.entity.ClawbackSaga;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Writes clawback saga lifecycle events to outbox table for Kafka relay (poller/CDC).
 *
 * <p>Purpose is observability/audit of saga state transitions; core correctness remains in saga
 * persistence + domain mutations.
 */
@Component
@RequiredArgsConstructor
public class ClawbackOutboxWriter {

  private static final Logger log = LogManager.getLogger(ClawbackOutboxWriter.class);

  private final PortfolioOutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  /**
   * Persists one lifecycle event row into outbox.
   *
   * @param saga current saga snapshot
   * @param eventType semantic event label (e.g. CLAWBACK_STARTED/CLAWBACK_COMPENSATED)
   * @param stepName optional step name when publishing step-level events
   */
  public void writeClawbackLifeCycleEvent(ClawbackSaga saga, String eventType, String stepName) {
    SagaLifecycleEvent event =
        new SagaLifecycleEvent(
            saga.getSagaId(),
            saga.getOrderId(),
            saga.getUserId(),
            eventType,
            saga.getStatus().name(),
            stepName,
            saga.getFailureReason(),
            saga.getUpdatedAt());

    String json = convertObjToJsonString(event);

    PortfolioOutboxEvent outboxEvent =
        getOutboxEvent(saga, json, event.getClass().getName(), eventType);

    outboxEventRepository.save(outboxEvent);

    log.info(
        "Clawback outbox event written: eventType={}, sagaId={}, orderId={}, rewardId={}",
        eventType,
        saga.getSagaId(),
        saga.getOrderId(),
        saga.getRewardId());
  }

  /** Serializes event DTO to JSON payload. Throws if serialization fails. */
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

  /** Builds outbox row targeting the clawback lifecycle topic. */
  private static PortfolioOutboxEvent getOutboxEvent(
      ClawbackSaga saga, String json, String className, String eventType) {
    return PortfolioOutboxEvent.builder()
        .aggregateType("ClawbackSaga")
        .aggregateId(saga.getId())
        .eventType(eventType)
        .topic("clawback-saga")
        .payload(json)
        .payloadType(className)
        .status(PortfolioOutboxStatus.PENDING)
        .build();
  }
}
