package com.equitycart.order;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone entry point for the Order Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Shopping cart management via Redis Hash storage (30-minute TTL per cart)
 *   <li>Order placement with idempotency (client-generated {@code idempotencyKey} prevents
 *       duplicates)
 *   <li>Pessimistic locking on product stock during order placement (prevents overselling)
 *   <li>Order lifecycle state machine: CREATED → CONFIRMED → PROCESSING → SHIPPED → DELIVERED →
 *       RETURN_REQUESTED → RETURNED → REFUNDED
 *   <li>Transactional Outbox Pattern: order state changes (DELIVERED, RETURNED, REFUNDED) are
 *       written atomically to the {@code outbox_events} table and relayed to Kafka by {@code
 *       OutboxPoller}
 * </ul>
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code order-service.yml} from Config Server (port 8888)
 *   <li>HikariCP connects to {@code equitycart_order} PostgreSQL database
 *   <li>Hibernate auto-creates tables for ALL entities on classpath: order, order_item,
 *       outbox_events (from this module) PLUS product, brand, category, brand_ticker_mapping (from
 *       product-service dependency) — this is a Phase 7 Strangler Fig transitional artifact
 *   <li>Redis auto-configuration wires {@code StringRedisTemplate} for cart storage
 *   <li>{@code OutboxPoller} {@code @Scheduled} task is activated by {@code @EnableScheduling}
 *   <li>Eureka client registers service as {@code ORDER-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Why {@code @EnableJpaRepositories} and {@code @EntityScan} are explicit here:</b> By
 * default, {@code @SpringBootApplication} scans only the annotated class's package ({@code
 * com.equitycart.order.*}). {@code OrderServiceImpl} directly injects {@code ProductRepository}
 * (from the {@code product-service} module dependency) to execute pessimistic-lock stock queries.
 * Without expanding the scan:
 *
 * <ul>
 *   <li>{@code ProductRepository} bean is never created → {@code UnsatisfiedDependencyException}
 *   <li>{@code Product}, {@code Brand}, {@code Category} entities are not registered with Hibernate
 *       → schema validation/creation fails
 * </ul>
 *
 * The fix: explicit {@code @EnableJpaRepositories} covering both {@code order} and {@code product}
 * packages, and {@code @EntityScan} covering {@code order}, {@code product}, and {@code commons}
 * (where {@code BaseEntity} {@code @MappedSuperclass} lives). In Phase 10, the {@code
 * ProductRepository} injection will be replaced with a Feign HTTP client, and these explicit
 * annotations can be removed.
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code equitycart_order}) — order entities, outbox events
 *   <li><b>Redis</b> — cart storage (key: {@code Cart:{userId}}, TTL: 30 min)
 * </ul>
 *
 * <p><b>Outbox Poller note:</b> {@code OutboxPoller} uses {@code @Profile("!cdc")} — it is active
 * when the {@code cdc} profile is NOT set. Do NOT activate the {@code cdc} profile for this service
 * until Debezium is configured to watch the {@code equitycart_order} database's WAL. Without CDC
 * configured, setting {@code cdc} profile disables the poller and outbox events will never reach
 * Kafka.
 *
 * <p><b>Security Note (Phase 7 interim state):</b> No HTTP security filter chain is active. {@code
 * CartController} and {@code OrderController} extract {@code userId} from {@code
 * SecurityContextHolder} — which is {@code null} because no JWT filter processes the incoming
 * token. All endpoints effectively require direct port access (bypass gateway auth check) until
 * Phase 8 adds OAuth2 Resource Server per service.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka: {@code http://localhost:8761} → {@code ORDER-SERVICE} registered on port 8088
 *   <li>Actuator: {@code GET http://localhost:8088/actuator/health} → 200 OK
 *   <li>Gateway routing: {@code POST http://localhost:8080/api/cart/items} → reaches order-service
 *       (500 on auth NullPointer is expected — confirms routing works)
 *   <li>Gateway route: {@code POST http://localhost:8080/api/order} → reaches order-service
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → PostgreSQL → Redis →
 * Kafka → Order-Service (8088)
 *
 * @see com.equitycart.order.service.impl.OrderServiceImpl
 * @see com.equitycart.order.cart.service.impl.CartServiceImpl
 * @see com.equitycart.order.event.OutboxPoller
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = {"com.equitycart.order", "com.equitycart.product"})
@EntityScan(
    basePackages = {"com.equitycart.order", "com.equitycart.product", "com.equitycart.commons"})
@EnableDiscoveryClient
@EnableScheduling
public class OrderServiceApplication {

  private static final Logger log = LogManager.getLogger(OrderServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
    log.info(
        "Order Service started — listening on port 8088, registered with Eureka as ORDER-SERVICE");
  }
}
