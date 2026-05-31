package com.equitycart.portfolio.eventsourcing.repository;

import com.equitycart.portfolio.eventsourcing.document.PortfolioEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data MongoDB repository for {@link PortfolioEvent} documents. Provides query methods for
 * event timeline retrieval, per-holding history, time-range filtering, and sequence number
 * resolution for the event store's append operation.
 */
public interface PortfolioEventRepository extends MongoRepository<PortfolioEvent, String> {

  List<PortfolioEvent> findByUserIdOrderBySequenceNumberAsc(Long userId);

  List<PortfolioEvent> findByUserIdAndTickerSymbolOrderBySequenceNumberAsc(
      Long userId, String tickerSymbol);

  List<PortfolioEvent> findByUserIdAndTimestampBetweenOrderBySequenceNumberAsc(
      Long userId, Instant from, Instant to);

  Optional<PortfolioEvent> findTopByUserIdOrderBySequenceNumberDesc(Long userId);

  Optional<PortfolioEvent> findByEventId(UUID eventId);
}
