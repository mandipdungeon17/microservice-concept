package com.equitycart.portfolio.eventsourcing.service.impl;

import com.equitycart.portfolio.eventsourcing.document.PortfolioEvent;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.repository.PortfolioEventRepository;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * MongoDB-backed implementation of {@link PortfolioEventStore}. Appends immutable portfolio events
 * to the {@code portfolio_events} collection with monotonically increasing sequence numbers per
 * user.
 *
 * <p>Best-effort semantics: all exceptions are caught and logged at WARN level. A MongoDB failure
 * never propagates to the caller, ensuring core portfolio operations (PostgreSQL state) are not
 * affected by event store outages.
 *
 * <p>Sequence numbers are derived by querying the latest event for the user and incrementing. Under
 * high concurrency, the unique index on {@code eventId} prevents duplicates while sequence gaps are
 * acceptable (detectable but not harmful for projection replay).
 */
@Service
@RequiredArgsConstructor
public class PortfolioEventStoreImpl implements PortfolioEventStore {

  private static final Logger log = LogManager.getLogger(PortfolioEventStoreImpl.class);

  private final PortfolioEventRepository portfolioEventRepository;

  @Override
  public void append(
      Long userId,
      PortfolioEventType eventType,
      String tickerSymbol,
      BigDecimal quantity,
      BigDecimal pricePerShare,
      BigDecimal totalValue,
      Map<String, Object> metadata) {
    try {
      UUID eventId = UUID.randomUUID();
      Optional<PortfolioEvent> topByUserIdOrderBySequenceNumberDesc =
          portfolioEventRepository.findTopByUserIdOrderBySequenceNumberDesc(userId);

      if (topByUserIdOrderBySequenceNumberDesc.isEmpty()) {
        log.info("No existing events found for userId: {}. Starting sequence number at 1.", userId);
      }
      Long sequenceNumber =
          topByUserIdOrderBySequenceNumberDesc
              .map(event -> event.getSequenceNumber() + 1)
              .orElse(1L);

      PortfolioEvent portfolioEvent =
          PortfolioEvent.builder()
              .eventId(eventId)
              .userId(userId)
              .eventType(eventType.name())
              .tickerSymbol(tickerSymbol)
              .quantity(quantity)
              .pricePerShare(pricePerShare)
              .totalValue(totalValue)
              .metadata(metadata)
              .timestamp(Instant.now())
              .sequenceNumber(sequenceNumber)
              .build();

      portfolioEventRepository.save(portfolioEvent);
    } catch (Exception e) {
      log.warn(
          "Failed to append portfolio event for userId: {}, eventType: {}, tickerSymbol: {}. Error: {}",
          userId,
          eventType,
          tickerSymbol,
          e.getMessage(),
          e);
    }
  }
}
