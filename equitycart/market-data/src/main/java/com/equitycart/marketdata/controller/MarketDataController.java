package com.equitycart.marketdata.controller;

import com.equitycart.marketdata.dto.HealthScoreResponse;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.entity.PriceHistory;
import com.equitycart.marketdata.service.api.MarketDataService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * REST controller exposing market-data endpoints: real-time prices, historical snapshots, company
 * health scores, cache management, and a Server-Sent Events stream for live price updates.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/market-data")
public class MarketDataController {

  private static final Logger log = LogManager.getLogger(MarketDataController.class);

  private final MarketDataService marketDataService;

  /** Returns the current price for a single ticker symbol. */
  @GetMapping("/price/{symbol}")
  @ResponseStatus(HttpStatus.OK)
  public StockPriceResponse getPrice(@PathVariable String symbol) {
    log.info("GET /price/{} — single price lookup", symbol);
    return marketDataService.getPrice(symbol);
  }

  /** Returns current prices for multiple ticker symbols supplied as a comma-separated list. */
  @GetMapping("/prices")
  @ResponseStatus(HttpStatus.OK)
  public List<StockPriceResponse> getPrices(@RequestParam("symbols") List<String> symbols) {
    log.info("GET /prices — batch lookup for {} symbols: {}", symbols.size(), symbols);
    return marketDataService.getPrices(symbols);
  }

  /** Returns historical price snapshots from MongoDB for the given symbol over the last N days. */
  @GetMapping("/history/{symbol}")
  @ResponseStatus(HttpStatus.OK)
  public List<PriceHistory> getHistoricalData(
      @PathVariable String symbol, @RequestParam(value = "days", defaultValue = "7") int days) {
    log.info("GET /history/{} — last {} days", symbol, days);
    return marketDataService.getHistory(symbol, days);
  }

  /** Returns a composite health score (0–100) for the given symbol with signal breakdown. */
  @GetMapping("/health/{symbol}")
  @ResponseStatus(HttpStatus.OK)
  public HealthScoreResponse getHealthScore(@PathVariable String symbol) {
    log.info("GET /health/{} — computing health score", symbol);
    return marketDataService.getHealthScore(symbol);
  }

  /** Evicts the Redis price cache entry for the given symbol. Restricted to ADMIN role. */
  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/price/{symbol}/cache")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearPriceCache(@PathVariable String symbol) {
    log.info("DELETE /price/{}/cache — evicting price cache (ADMIN)", symbol);
    marketDataService.evictPriceCache(symbol);
  }

  /**
   * Opens a Server-Sent Events stream that pushes live price updates for the given symbol every 5
   * seconds. Only emits when the price changes ({@code distinctUntilChanged}).
   */
  @GetMapping(value = "/stream/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseStatus(HttpStatus.OK)
  public Flux<ServerSentEvent<StockPriceResponse>> getStreamPrice(@PathVariable String symbol) {
    log.info("GET /stream/{} — opening SSE stream", symbol);
    return marketDataService
        .streamPrice(symbol)
        .map(
            stockPriceResponse ->
                ServerSentEvent.<StockPriceResponse>builder().data(stockPriceResponse).build());
  }
}
