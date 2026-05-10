package com.equitycart.marketdata.client;

import com.equitycart.marketdata.dto.StockQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Non-blocking client for the Alpha Vantage GLOBAL_QUOTE endpoint. Returns a {@link Mono} of {@link
 * StockQuote} parsed from the JSON response via {@link JsonNode} tree navigation. The reactive
 * chain is assembled lazily — no HTTP call is made until a subscriber (or {@code .block()})
 * triggers execution.
 */
@Component
@RequiredArgsConstructor
public class AlphaVantageClient {

  private static final Logger log = LogManager.getLogger(AlphaVantageClient.class);

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  @Value("${alphaVantage.api-key}")
  private String alphaVantageApiKey;

  /**
   * Fetches a real-time stock quote for the given ticker symbol from Alpha Vantage. The returned
   * {@link Mono} completes with a parsed {@link StockQuote} on success, or signals an error if the
   * response is missing, empty, or unparseable.
   */
  @Retry(name = "alphaVantage")
  @CircuitBreaker(name = "alphaVantage", fallbackMethod = "getStockQuoteFallback")
  @RateLimiter(name = "alphaVantage")
  public Mono<StockQuote> getStockQuote(String symbol) {
    log.info("Fetching stock quote for symbol: {}", symbol);
    return webClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/query")
                    .queryParam("function", "GLOBAL_QUOTE")
                    .queryParam("symbol", symbol)
                    .queryParam("apikey", alphaVantageApiKey)
                    .build())
        .retrieve()
        .bodyToMono(String.class)
        .flatMap(
            responseBody -> {
              JsonNode rootNode;
              StockQuote stockQuote;
              try {
                rootNode = objectMapper.readTree(responseBody);
                JsonNode quoteNode = rootNode.path("Global Quote");
                if (quoteNode.isMissingNode() || quoteNode.isEmpty()) {
                  log.warn("Empty or missing Global Quote for symbol: {}", symbol);
                  return Mono.error(
                      new RuntimeException("Invalid response from Alpha Vantage: " + responseBody));
                }
                stockQuote =
                    new StockQuote(
                        quoteNode.path("01. symbol").asText(),
                        new BigDecimal(quoteNode.path("05. price").asText()),
                        new BigDecimal(quoteNode.path("09. change").asText()),
                        quoteNode.path("10. change percent").asText(),
                        quoteNode.path("06. volume").asLong(),
                        quoteNode.path("07. latest trading day").asText(),
                        Instant.now());
              } catch (Exception e) {
                log.error("Failed to parse Alpha Vantage response for symbol: {}", symbol, e);
                return Mono.error(
                    new RuntimeException("Failed to parse response from Alpha Vantage", e));
              }
              log.info("Successfully parsed quote for {}: price={}", symbol, stockQuote.price());
              return Mono.just(stockQuote);
            });
  }

  /**
   * Fallback invoked by the circuit breaker when {@link #getStockQuote(String)} fails or the
   * breaker is open. Signals an error with a descriptive message rather than returning fake data.
   */
  private Mono<StockQuote> getStockQuoteFallback(String symbol, Throwable t) {
    log.error(
        "Alpha Vantage request failed for symbol: {}. Returning fallback response.", symbol, t);
    return Mono.error(
        new RuntimeException(
            "Unable to fetch stock quote for symbol: " + symbol + " due to: " + t.getMessage(), t));
  }
}
