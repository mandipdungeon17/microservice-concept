package com.equitycart.marketdata.service.impl;

import com.equitycart.marketdata.client.AlphaVantageClient;
import com.equitycart.marketdata.dto.HealthScoreResponse;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.dto.StockQuote;
import com.equitycart.marketdata.entity.PriceHistory;
import com.equitycart.marketdata.repository.PriceHistoryRepository;
import com.equitycart.marketdata.service.api.MarketDataService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implements {@link MarketDataService} with a two-layer data flow: Redis (short-TTL price cache)
 * backed by the Alpha Vantage external API via {@link AlphaVantageClient}. Uses {@code .block()} at
 * the service boundary to bridge reactive Mono results into the synchronous MVC controller layer.
 */
@Service
@RequiredArgsConstructor
public class MarketDataServiceImpl implements MarketDataService {

  private static final Logger log = LogManager.getLogger(MarketDataServiceImpl.class);

  private final AlphaVantageClient alphaVantageClient;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final PriceHistoryRepository priceHistoryRepository;

  private static final String KEY_PREFIX = "price:";
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  /** {@inheritDoc} */
  @Override
  public StockPriceResponse getPrice(String symbol) {
    String key = KEY_PREFIX + symbol.toUpperCase();
    StockPriceResponse stockPriceResponse;

    String cachedData = redisTemplate.opsForValue().get(key);

    if (cachedData == null) {
      log.info("Cache MISS for symbol: {} — calling Alpha Vantage API", symbol);
      Mono<StockQuote> stockQuoteMono = alphaVantageClient.getStockQuote(symbol);
      stockPriceResponse = stockQuoteMono.map(this::toResponse).block();

      if (stockPriceResponse != null) {
        try {
          String jsonData = objectMapper.writeValueAsString(stockPriceResponse);
          redisTemplate.opsForValue().set(key, jsonData, CACHE_TTL);
          log.info("Cached price for symbol: {} (TTL: {})", symbol, CACHE_TTL);

          /*
           * Asynchronously save price history to the database without blocking the main thread.
           * fire-and-forget operation — don't block the response waiting for MongoDB to acknowledge to write.
           * The price response goes back to the client immediately; the history record is saved asynchronously.
           */
          CompletableFuture.runAsync(
                  () -> {
                    PriceHistory priceHistory =
                        PriceHistory.builder()
                            .symbol(stockPriceResponse.symbol())
                            .price(stockPriceResponse.price())
                            .change(stockPriceResponse.change())
                            .changePercent(stockPriceResponse.changePercent())
                            .volume(stockPriceResponse.volume())
                            .tradingDay(stockPriceResponse.latestTradingDay())
                            .fetchedAt(stockPriceResponse.cachedAt())
                            .build();

                    priceHistoryRepository.save(priceHistory);
                  })
              .whenComplete(
                  (result, ex) -> {
                    if (ex != null) {
                      log.error("Failed to save price history for symbol: {}", symbol, ex);
                    } else {
                      log.info("Saved price history for symbol: {}", symbol);
                    }
                  });
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException(
              "Failed to serialize cached data for symbol: " + symbol, e);
        }
      }
    } else {
      log.debug("Cache HIT for symbol: {}", symbol);
      try {
        stockPriceResponse = objectMapper.readValue(cachedData, StockPriceResponse.class);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "Failed to deserialize cached data for symbol: " + symbol, e);
      }
    }

    return stockPriceResponse;
  }

  /** {@inheritDoc} */
  @Override
  public List<StockPriceResponse> getPrices(List<String> symbols) {
    log.info("Batch price lookup for {} symbols: {}", symbols.size(), symbols);
    return symbols.stream().map(this::getPrice).toList();
  }

  /** {@inheritDoc} */
  @Override
  public void evictPriceCache(String symbol) {
    String key = KEY_PREFIX + symbol.toUpperCase();
    redisTemplate.delete(key);
    log.info("Evicted price cache for symbol: {}", symbol);
  }

  /** {@inheritDoc} */
  @Override
  public List<PriceHistory> getHistory(String symbol, int days) {
    log.info("Fetching price history for symbol: {} over last {} days", symbol, days);
    return priceHistoryRepository.findBySymbolAndFetchedAtBetween(
        symbol, Instant.now().minus(days, ChronoUnit.DAYS), Instant.now());
  }

  /** {@inheritDoc} */
  @Override
  public HealthScoreResponse getHealthScore(String symbol) {
    log.info("Computing health score for symbol: {}", symbol);
    StockPriceResponse priceResponse = this.getPrice(symbol);
    if (priceResponse == null) {
      log.warn("No price data available for symbol: {} — returning null health score", symbol);
      return null;
    }

    double score = 50.0;
    Map<String, String> signals = new LinkedHashMap<>();

    // Signal 1 — Current price change
    int changeSign = priceResponse.change().compareTo(BigDecimal.ZERO);
    if (changeSign > 0) {
      score += 15;
      signals.put("priceChange", "POSITIVE (+15)");
    } else if (changeSign < 0) {
      score -= 15;
      signals.put("priceChange", "NEGATIVE (-15)");
    } else {
      signals.put("priceChange", "NEUTRAL (+0)");
    }

    // Signal 2 — Change percent magnitude
    double absPercent =
        Math.abs(Double.parseDouble(priceResponse.changePercent().replace("%", "")));
    if (absPercent > 2.0) {
      int bonus = changeSign > 0 ? 10 : -10;
      score += bonus;
      signals.put("changePercent", "SIGNIFICANT (" + (bonus >= 0 ? "+" : "") + bonus + ")");
    } else {
      signals.put("changePercent", "NORMAL (+0)");
    }

    // Signal 3 — Weekly trend from MongoDB
    List<PriceHistory> history = this.getHistory(symbol, 7);
    if (history != null && history.size() > 1) {
      BigDecimal earliest = history.getFirst().getPrice();
      BigDecimal latest = history.getLast().getPrice();
      int trend = latest.compareTo(earliest);
      if (trend > 0) {
        score += 15;
        signals.put("weeklyTrend", "UPTREND (+15)");
      } else if (trend < 0) {
        score -= 15;
        signals.put("weeklyTrend", "DOWNTREND (-15)");
      } else {
        signals.put("weeklyTrend", "FLAT (+0)");
      }
    } else {
      signals.put("weeklyTrend", "INSUFFICIENT_DATA (+0)");
    }

    // Signal 4 — Volume
    if (priceResponse.volume() != null && priceResponse.volume() > 1_000_000) {
      score += 10;
      signals.put("volume", "HIGH (+10)");
    } else {
      signals.put("volume", "LOW (+0)");
    }

    // Clamp to [0, 100]
    int finalScore = (int) Math.max(0, Math.min(100, score));

    log.info("Health score for {}: {} — signals: {}", symbol, finalScore, signals);
    return new HealthScoreResponse(symbol, finalScore, signals, Instant.now());
  }

  /** {@inheritDoc} */
  @Override
  public Flux<StockPriceResponse> streamPrice(String symbol) {
    log.info("Opening SSE price stream for symbol: {}", symbol);
    return Flux.interval(Duration.ofSeconds(5))
        .concatMap(tick -> alphaVantageClient.getStockQuote(symbol))
        .map(this::toResponse)
        .onErrorResume(e -> Mono.empty())
        .distinctUntilChanged(StockPriceResponse::price);
  }

  /** Converts an internal {@link StockQuote} to the API-facing {@link StockPriceResponse}. */
  private StockPriceResponse toResponse(StockQuote stockQuote) {
    return new StockPriceResponse(
        stockQuote.symbol(),
        stockQuote.price(),
        stockQuote.change(),
        stockQuote.changePercent(),
        stockQuote.volume(),
        stockQuote.latestTradingDay(),
        stockQuote.timestamp());
  }
}
