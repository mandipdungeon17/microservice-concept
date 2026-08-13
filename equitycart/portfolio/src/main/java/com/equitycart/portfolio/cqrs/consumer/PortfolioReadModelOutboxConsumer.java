package com.equitycart.portfolio.cqrs.consumer;

import com.equitycart.portfolio.async.dto.PortfolioProjectionEvent;
import com.equitycart.portfolio.cqrs.synchronizer.PortfolioReadModelSynchronizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for CQRS read model projection events. Listens to the portfolio-readmodel-events
 * topic and triggers read model rebuilds for affected users.
 *
 * <p>Each event from the outbox stream is deserialized and passed to the synchronizer, which
 * rebuilds the denormalized MongoDB snapshot for that user. This decouples the read model update
 * from the write-side transaction, enabling eventual consistency.
 *
 * <p>Consumer group: portfolio-readmodel-projection-group
 */
@Component
@RequiredArgsConstructor
public class PortfolioReadModelOutboxConsumer {

  private static final Logger log = LogManager.getLogger(PortfolioReadModelOutboxConsumer.class);

  private final ObjectMapper objectMapper;
  private final PortfolioReadModelSynchronizer portfolioReadModelSynchronizer;

  /**
   * Consumes projection events from the Kafka topic and triggers a read model rebuild for the
   * affected user.
   *
   * <p>The event payload is deserialized into a {@link PortfolioProjectionEvent}, then the user ID
   * is extracted to trigger an upsert of the denormalized portfolio snapshot in MongoDB.
   *
   * @param payload JSON-serialized portfolio projection event
   * @throws Exception if JSON deserialization fails or the rebuild encounters an error
   */
  @KafkaListener(
      topics = "portfolio-readmodel-events",
      groupId = "portfolio-readmodel-projection-group")
  public void consume(String payload) throws Exception {
    PortfolioProjectionEvent event =
        objectMapper.readValue(payload, PortfolioProjectionEvent.class);
    log.debug(
        "Received projection event: eventType={}, userId={}, ticker={}",
        event.eventType(),
        event.userId(),
        event.tickerSymbol());
    portfolioReadModelSynchronizer.rebuildReadModelForUser(event.userId());
    log.debug("Read model rebuild completed for userId={}", event.userId());
  }
}
