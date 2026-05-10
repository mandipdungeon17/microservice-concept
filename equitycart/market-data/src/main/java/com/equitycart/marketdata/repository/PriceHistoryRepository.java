package com.equitycart.marketdata.repository;

import com.equitycart.marketdata.entity.PriceHistory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data MongoDB repository for {@link PriceHistory} documents. */
public interface PriceHistoryRepository extends MongoRepository<PriceHistory, String> {

  /** Returns recent price snapshots for a symbol, sorted newest-first, with pagination support. */
  List<PriceHistory> findBySymbolOrderByFetchedAtDesc(String symbol, Pageable pageable);

  /** Returns price snapshots for a symbol within a time range (inclusive). */
  List<PriceHistory> findBySymbolAndFetchedAtBetween(
      String symbol, Instant startTime, Instant endTime);
}
