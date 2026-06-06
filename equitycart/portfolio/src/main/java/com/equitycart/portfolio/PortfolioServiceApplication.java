package com.equitycart.portfolio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone entry point for the Portfolio Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Portfolio and holding management (BUY/SELL trades, weighted-average cost basis)
 *   <li>Stock-back reward lifecycle: PENDING → VESTED via scheduled vesting job
 *   <li>Double-entry ledger integration: each trade writes paired debit/credit entries
 *   <li>Sell-to-Spend Saga: orchestrates portfolio sell + ledger + order confirmation
 *   <li>Event Sourcing: append-only portfolio change log in MongoDB ({@code portfolio_events})
 *   <li>Notification publishing: fire-and-forget events to {@code portfolio-notification} Kafka
 *       topic
 *   <li>Kafka consumers: stock-back reward granting (order-delivered) + cancellation
 *       (order-returned)
 * </ul>
 *
 * <p><b>Why explicit {@code @ComponentScan} is required here (not just in order-service):</b>
 * Portfolio-service depends on actual <em>service beans</em> from other modules — {@code
 * LedgerServiceImpl}, {@code ProductServiceImpl}, {@code MarketDataServiceImpl}, etc. These are
 * {@code @Service} / {@code @Configuration} classes that live in foreign packages.
 * {@code @SpringBootApplication} only scans {@code com.equitycart.portfolio.*} by default; without
 * an explicit {@code @ComponentScan} covering all six packages, these beans are never registered
 * and injection fails with {@code UnsatisfiedDependencyException}.
 *
 * <p>Order-service only needed <em>repository proxies</em> from the product module — it used
 * {@code @EnableJpaRepositories} alone (which only registers proxy beans, not
 * {@code @Configuration} classes). Portfolio-service needs the full service layer, so it must use
 * {@code @ComponentScan}, which loads ALL {@code @Configuration} beans including {@code
 * ProductBatchConfig} (from {@code product-service}) and {@code WebClientConfig} (from {@code
 * market-data-service}).
 *
 * <p><b>Why {@code excludeFilters = @Filter(SpringBootApplication.class)} is mandatory:</b>
 * {@code @ComponentScan} covering {@code com.equitycart.order} would otherwise find {@code
 * OrderServiceApplication} (annotated {@code @SpringBootApplication}, which is a
 * {@code @Configuration}) and load its embedded {@code @EnableJpaRepositories} declaration. That
 * would cause Spring to register repository beans a second time, throwing {@code
 * BeanDefinitionOverrideException} on startup. The filter prevents any class annotated with
 * {@code @SpringBootApplication} from being treated as a configuration bean.
 *
 * <p><b>Why Spring Batch config properties are required (but were NOT needed in order-service):</b>
 * {@code product-service} has {@code spring-batch} on its classpath. When this service's
 * {@code @ComponentScan} covers {@code com.equitycart.product.*}, Spring loads {@code
 * ProductBatchConfig} — a {@code @Configuration} class that registers Batch beans. {@code
 * BatchAutoConfiguration} then fires and expects {@code spring.batch.jdbc.initialize-schema} to be
 * set. Order-service avoided this because it used {@code @EnableJpaRepositories} (not
 * {@code @ComponentScan}), which only picks up repository interfaces — {@code ProductBatchConfig}
 * is never loaded.
 *
 * <p><b>Why {@code alphavantage.*} properties are required:</b> {@code @ComponentScan} covering
 * {@code com.equitycart.marketdata} loads {@code WebClientConfig} and {@code
 * MarketDataServiceImpl}, both of which inject {@code @Value("${alphavantage.base-url}")} and
 * {@code @Value("${alphavantage.api-key}")}. Without those properties in the environment, Spring
 * fails at startup with a {@code PropertyValueException}.
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code portfolio-service.yml} from Config Server (8888)
 *   <li>HikariCP connects to {@code equitycart_portfolio} PostgreSQL database
 *   <li>Hibernate auto-creates tables for ALL scanned entities (portfolio, holding,
 *       stock_back_reward, ledger_entry, order, order_item, outbox_events, product, brand,
 *       category, sell_to_spend_saga, notification_log)
 *   <li>MongoDB connects for {@code portfolio_events} event store
 *   <li>Kafka consumer ({@code StockBackRewardConsumer}) subscribes to {@code order-delivered} +
 *       {@code order-returned} topics
 *   <li>VestingHelper {@code @Scheduled} task activated by {@code @EnableScheduling}
 *   <li>Eureka client registers service as {@code PORTFOLIO-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code equitycart_portfolio}) — portfolio, holding, stock_back_reward,
 *       ledger_entry, notification_log, saga state, outbox events
 *   <li><b>MongoDB</b> ({@code portfolio_events}) — append-only event sourcing log (TTL none)
 *   <li><b>Kafka</b> — consumer: {@code order-delivered}, {@code order-returned}; producer: {@code
 *       portfolio-notification} topic
 * </ul>
 *
 * <p><b>Security Note (Phase 7 interim state):</b> No HTTP security filter chain is active. {@code
 * PortfolioController} extracts {@code userId} from {@code SecurityContextHolder} — which is {@code
 * null} because no JWT filter processes the incoming token. All endpoints effectively require
 * direct port access until Phase 8 adds OAuth2 Resource Server per service.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka: {@code http://localhost:8761} → {@code PORTFOLIO-SERVICE} registered on port 8084
 *   <li>Actuator: {@code GET http://localhost:8084/actuator/health} → 200 OK
 *   <li>Gateway routing: {@code GET http://localhost:8080/api/portfolio} → reaches
 *       portfolio-service
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → PostgreSQL → MongoDB →
 * Kafka → Portfolio-Service (8084)
 *
 * @see com.equitycart.portfolio.service.impl.TradeServiceImpl
 * @see com.equitycart.portfolio.service.impl.VestingHelperImpl
 * @see com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator
 * @see com.equitycart.portfolio.eventsourcing.service.impl.PortfolioEventStoreImpl
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {
      "com.equitycart.portfolio",
      "com.equitycart.ledger", // LedgerServiceImpl (@Service)
      "com.equitycart.order", // OrderServiceImpl, CartServiceImpl (@Service)
      "com.equitycart.product", // ProductServiceImpl, etc. (@Service)
      "com.equitycart.commons", // GlobalExceptionHandler, KafkaConsumerConfig (@Configuration)
      "com.equitycart.marketdata" // MarketDataServiceImpl (@Service) — used by //
      // StockBackRewardConsumer
    },
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class))
@EnableJpaRepositories(
    basePackages = {
      "com.equitycart.portfolio",
      "com.equitycart.ledger",
      "com.equitycart.order",
      "com.equitycart.product"
    })
@EntityScan(
    basePackages = {
      "com.equitycart.portfolio",
      "com.equitycart.ledger",
      "com.equitycart.order",
      "com.equitycart.product",
      "com.equitycart.commons"
    })
@EnableMongoRepositories(
    basePackages = {
      "com.equitycart.portfolio.eventsourcing",
      "com.equitycart.marketdata" // PriceHistoryRepository
    })
@EnableDiscoveryClient
@EnableScheduling
public class PortfolioServiceApplication {

  private static final Logger log = LogManager.getLogger(PortfolioServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(PortfolioServiceApplication.class, args);
    log.info(
        "Portfolio Service started — listening on port 8084, registered with Eureka as PORTFOLIO-SERVICE");
  }
}
