package com.equitycart.marketdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Standalone entry point for the Market Data Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Fetches real-time stock prices from the Alpha Vantage external API
 *   <li>Caches prices in Redis (manual cache, 30-second TTL) to respect Alpha Vantage rate limits
 *   <li>Persists price snapshots in MongoDB ({@code price_history} collection, 90-day TTL index)
 *   <li>Exposes a Server-Sent Events (SSE) endpoint for live price streaming via Flux.interval
 *   <li>Provides a composite Health Score endpoint (signals: price change, trend, volume)
 * </ul>
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code market-data-service.yml} from Config Server (port
 *       8888)
 *   <li>MongoDB auto-configuration wires {@code MongoClient} to {@code
 *       mongodb://localhost:27017/equitycart_market_data}
 *   <li>Redis auto-configuration wires {@code StringRedisTemplate} to {@code localhost:6379}
 *   <li>Resilience4j wires circuit breaker, retry, and rate-limiter instances named {@code
 *       alphaVantage}
 *   <li>Reactor Netty HTTP client is initialized for non-blocking Alpha Vantage requests
 *       (WebClient)
 *   <li>Eureka client registers service as {@code MARKET-DATA-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>Redis</b> — Manual price cache via {@code StringRedisTemplate.opsForValue()}. Key:
 *       {@code market:price:{symbol}}. TTL: 30 seconds (enforces Alpha Vantage rate limit window).
 *       Note: this is NOT Spring's {@code @Cacheable} / {@code spring.cache.type: redis} — it is
 *       explicit cache-aside logic in {@code MarketDataServiceImpl}. The distinction matters
 *       because cache-aside gives full control over TTL, key format, and cache-miss behaviour.
 *   <li><b>MongoDB</b> — Append-only price history documents. {@code @Indexed(expireAfter="90d")}
 *       on the {@code fetchedAt} field creates a MongoDB TTL index; MongoDB's internal TTL monitor
 *       (runs every 60 seconds) deletes documents older than 90 days automatically.
 *   <li><b>No SQL / JPA</b> — This service has no relational entities. The {@code commons} module
 *       dependency is intentionally omitted from {@code build.gradle} because {@code
 *       commons/build.gradle} uses {@code api 'spring-boot-starter-data-jpa'}, which would make JPA
 *       transitive to this service, triggering {@code DataSourceAutoConfiguration} and an immediate
 *       startup failure (no datasource URL).
 * </ul>
 *
 * <p><b>External Dependency — Alpha Vantage API:</b>
 *
 * <ul>
 *   <li>Free tier: 25 requests/day (hard cap), 5 requests/minute (soft cap enforced by rate
 *       limiter)
 *   <li>API key: configured via {@code ALPHA_VANTAGE_API_KEY} env var (fallback demo key in config
 *       YAML)
 *   <li>Resilience stack on {@code AlphaVantageClient}: RateLimiter (5 req/60s) → CircuitBreaker
 *       (50% failure threshold, 30s open window) → Retry (3 attempts, 2s wait)
 *   <li>Fallback: {@code getStockQuoteFallback()} returns {@code Mono.error()} — never returns
 *       stale data
 * </ul>
 *
 * <p><b>Security Note (Phase 7 interim state):</b> This service has {@code spring-security-core} on
 * the classpath (via {@code build.gradle}) but NOT {@code spring-boot-starter-security}. Without
 * the full starter, {@code SecurityAutoConfiguration} does NOT fire, meaning there is NO default
 * HTTP security filter chain. All endpoints are open. {@code @PreAuthorize} annotations in
 * controllers are syntactically present but silently inactive because no {@code @Configuration}
 * class declares {@code @EnableMethodSecurity}. This is intentional during Phase 7 extraction.
 * Phase 8 will add per-service JWT validation via Spring Security OAuth2 Resource Server.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka dashboard: http://localhost:8761 → should show {@code MARKET-DATA-SERVICE}
 *       registered
 *   <li>Actuator health: {@code GET http://localhost:8085/actuator/health} → 200 OK
 *   <li>Price endpoint: {@code GET http://localhost:8085/api/market-data/price/AAPL} → JSON price
 *       response
 *   <li>Via gateway: {@code GET http://localhost:8080/api/market-data/price/AAPL} → same response
 *       (lb://market-data-service resolution)
 *   <li>Redis cache: {@code redis-cli KEYS "market:price:*"} → populated after first price fetch
 *   <li>MongoDB: {@code mongosh equitycart_market_data →
 *       db.price_history.find().sort({fetchedAt:-1}).limit(3)}
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → Redis → MongoDB →
 * Market-Data-Service (8085)
 *
 * @see com.equitycart.marketdata.client.AlphaVantageClient
 * @see com.equitycart.marketdata.service.impl.MarketDataServiceImpl
 * @see com.equitycart.marketdata.controller.MarketDataController
 */
@SpringBootApplication
@EnableDiscoveryClient
public class MarketDataServiceApplication {

  private static final Logger log = LogManager.getLogger(MarketDataServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(MarketDataServiceApplication.class, args);
    log.info(
        "Market Data Service started — listening on port 8085, registered with Eureka as MARKET-DATA-SERVICE");
  }
}
