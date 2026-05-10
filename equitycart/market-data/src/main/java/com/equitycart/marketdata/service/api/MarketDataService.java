package com.equitycart.marketdata.service.api;

import com.equitycart.marketdata.dto.HealthScoreResponse;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.entity.PriceHistory;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Service interface for retrieving real-time stock prices. Implementations are expected to cache
 * prices in Redis (short TTL) and delegate to an external market data API on cache miss.
 */
public interface MarketDataService {

  /** Returns the current price for a single ticker symbol, serving from cache when available. */
  StockPriceResponse getPrice(String symbol);

  /** Returns prices for multiple ticker symbols. Each symbol is looked up individually. */
  List<StockPriceResponse> getPrices(List<String> symbols);

  /** Removes the cached price entry for the given symbol, forcing a fresh API call on next read. */
  void evictPriceCache(String symbol);

  /** Returns historical price snapshots for the given symbol over the specified number of days. */
  List<PriceHistory> getHistory(String symbol, int days);

  /**
   * Computes a composite health score (0–100) for the given symbol based on price, trend, and
   * volume signals.
   */
  HealthScoreResponse getHealthScore(String symbol);

  /**
   * Returns a reactive stream that emits live price updates for the given symbol at a fixed
   * interval.
   */
  Flux<StockPriceResponse> streamPrice(String symbol);
}
