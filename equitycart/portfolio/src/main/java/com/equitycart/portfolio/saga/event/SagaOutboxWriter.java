package com.equitycart.portfolio.saga.event;

import com.equitycart.commons.event.SagaLifecycleEvent;
import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Writes saga lifecycle events into the shared outbox table for Kafka delivery. Reuses the same
 * outbox infrastructure as {@link com.equitycart.order.event.OrderOutboxWriter} — events are
 * relayed to the {@code sell-to-spend-saga} topic by the OutboxPoller (or Debezium CDC).
 *
 * <p>These events are purely for <b>observability</b> — a monitoring consumer can subscribe to
 * track saga progress, completions, and failures. The saga's correctness does not depend on these
 * events being delivered.
 *
 * @see com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator
 */
@Component
@RequiredArgsConstructor
public class SagaOutboxWriter {

  private static final Logger log = LogManager.getLogger(SagaOutboxWriter.class);

  private final PortfolioOutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public void writeSagaLifecycleEvent(SellToSpendSaga saga, String eventType, String stepName) {
    SagaLifecycleEvent event =
        new SagaLifecycleEvent(
            saga.getSagaId(),
            saga.getOrderId(),
            saga.getUserId(),
            eventType,
            saga.getStatus().name(),
            stepName,
            saga.getFailureReason(),
            LocalDateTime.now());

    String json = convertObjToJsonString(event);

    PortfolioOutboxEvent outboxEvent =
        getOutboxEvent(saga, json, event.getClass().getName(), eventType);

    outboxEventRepository.save(outboxEvent);
    log.info(
        "Saga outbox event written: eventType={}, sagaId={}, orderId={}",
        eventType,
        saga.getSagaId(),
        saga.getOrderId());
  }

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

  private static PortfolioOutboxEvent getOutboxEvent(
      SellToSpendSaga saga, String json, String className, String eventType) {
    return PortfolioOutboxEvent.builder()
        .aggregateType("SellToSpendSaga")
        .aggregateId(saga.getId())
        .eventType(eventType)
        .topic("sell-to-spend-saga")
        .payload(json)
        .payloadType(className)
        .status(PortfolioOutboxStatus.PENDING)
        .build();
  }
}
