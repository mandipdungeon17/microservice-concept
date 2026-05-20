package com.equitycart.order.event;

import com.equitycart.order.entity.OutboxEvent;
import com.equitycart.order.enums.OutboxStatus;
import com.equitycart.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background poller that reads PENDING outbox events and publishes them to Kafka. Completes the
 * Outbox Pattern: the writer stores events atomically with business data, this poller relays them
 * to Kafka asynchronously.
 *
 * <p>Polls every 5 seconds. For each PENDING row: re-hydrates the JSON payload into its original
 * DTO class (via {@code payloadType} FQCN), sends through {@link KafkaTemplate} (which adds the
 * correct {@code __TypeId__} header), blocks until Kafka ACKs, then marks the row as SENT.
 *
 * <p>On failure: logs the error and leaves the row as PENDING — it will be retried on the next poll
 * cycle. This provides at-least-once delivery; consumers must be idempotent.
 */
@Component
@RequiredArgsConstructor
public class OutboxPoller {

  private static final Logger log = LogManager.getLogger(OutboxPoller.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void pollAndPublish() {
    List<OutboxEvent> outboxEvents = outboxEventRepository.findByStatus(OutboxStatus.PENDING);

    if (outboxEvents.isEmpty()) return;

    outboxEvents.forEach(
        outboxEvent -> {
          try {
            Class<?> clazz = Class.forName(outboxEvent.getPayloadType());
            Object event = objectMapper.readValue(outboxEvent.getPayload(), clazz);
            kafkaTemplate
                .send(outboxEvent.getTopic(), outboxEvent.getAggregateId().toString(), event)
                .get();

            outboxEvent.setStatus(OutboxStatus.SENT);
            outboxEvent.setPublishedAt(LocalDateTime.now());
            outboxEventRepository.save(outboxEvent);

            log.info(
                "Published outbox event id={}, topic={}, aggregateId={}",
                outboxEvent.getId(),
                outboxEvent.getTopic(),
                outboxEvent.getAggregateId());
          } catch (Exception e) {
            log.error(
                "Failed to publish outbox event id={}, topic={}: {}",
                outboxEvent.getId(),
                outboxEvent.getTopic(),
                e.getMessage());
          }
        });
  }
}
