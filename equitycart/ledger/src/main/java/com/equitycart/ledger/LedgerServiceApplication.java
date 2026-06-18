package com.equitycart.ledger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Standalone entry point for the Ledger Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Double-entry bookkeeping for all financial events on the platform
 *   <li>Each financial action (trade, vesting, sell-to-spend) produces exactly one transaction: a
 *       paired DEBIT + CREDIT entry sharing the same {@code transactionId} (UUID)
 *   <li>Entries are append-only — never updated or deleted (immutable audit trail)
 *   <li>Query support: by transaction ID, by reference (orderId, tradeId), by account type
 * </ul>
 *
 * <p><b>This is the simplest standalone extraction in Phase 7.</b> Ledger-service has no
 * cross-module service dependencies — {@code LedgerServiceImpl} only injects {@code
 * LedgerEntryRepository}, which lives in the same package tree ({@code
 * com.equitycart.ledger.repository}). The default {@code @SpringBootApplication} scan covers
 * everything. The only annotation required beyond the minimum is {@code @EntityScan}.
 *
 * <p><b>Why {@code @EntityScan} is required here:</b> {@code LedgerEntry} extends {@code
 * BaseEntity} (a {@code @MappedSuperclass} at {@code com.equitycart.commons.entity}). Without
 * explicitly including {@code com.equitycart.commons} in the entity scan, Hibernate may not
 * register {@code BaseEntity} as a managed superclass, causing the inherited {@code id}, {@code
 * createdAt}, and {@code updatedAt} columns to be invisible during schema generation.
 *
 * <p><b>Why {@code @EnableJpaRepositories} is NOT required here</b> (contrast with order-service):
 * {@code LedgerEntryRepository} is at {@code com.equitycart.ledger.repository} — fully within
 * {@code com.equitycart.ledger.*}, which is the default JPA repository scan scope.
 * {@code @EnableJpaRepositories} is only needed when a repository lives in a package tree
 * <em>outside</em> the main class's package. Here, it is inside, so the default covers it.
 *
 * <p><b>Why {@code @ComponentScan} IS required here (Phase 8 addition):</b> Ledger-service injects
 * no beans from another module's <em>service layer</em> — however, cross-cutting infrastructure
 * beans in {@code com.equitycart.commons} (SecurityAutoConfig, JwtAuthenticationFilter,
 * GlobalExceptionHandler, MdcCorrelationFilter, FeignCorrelationInterceptor,
 * FeignAuthorizationInterceptor, KafkaConsumerConfig) must be registered as Spring beans. Without
 * {@code @ComponentScan} including {@code com.equitycart.commons}, these classes exist on the
 * classpath (via Gradle {@code implementation project(':commons')}) but are never instantiated —
 * {@code @SpringBootApplication} only scans its own package tree ({@code com.equitycart.ledger.*}).
 * {@code @EntityScan} only discovers JPA {@code @Entity}/{@code @MappedSuperclass} classes, NOT
 * {@code @Component}/{@code @Configuration} beans. The {@code excludeFilters} prevents scanning
 * other modules' {@code @SpringBootApplication} classes that may appear on the classpath during
 * multi-module builds.
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code ledger-service.yml} from Config Server (8888)
 *   <li>HikariCP connects to {@code equitycart_ledger} PostgreSQL database
 *   <li>Hibernate auto-creates the {@code ledger_entries} table (with BaseEntity inherited columns)
 *   <li>Eureka client registers service as {@code LEDGER-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code equitycart_ledger}) — {@code ledger_entries} table only
 * </ul>
 *
 * <p><b>Security (Phase 8 Step 2):</b> Commons {@code SecurityAutoConfig} is active ({@code
 * equitycart.security.enabled=true} in ledger-service.yml). {@code JwtAuthenticationFilter}
 * validates every request except {@code /api/auth/**} and {@code /actuator/**}. All other endpoints
 * require a valid JWT with HMAC-SHA256 signature. Phase 8 Steps 5-7 will migrate to OAuth2 Resource
 * Server with Keycloak RS256 tokens.
 *
 * <p><b>No REST controllers in Phase 7:</b> Ledger-service currently exposes no endpoints. Other
 * services (portfolio-service) still call {@code LedgerService} directly via the library JAR. The
 * gateway route ({@code /api/ledger/**}) is pre-wired for Phase 10, when direct dependencies are
 * replaced with Feign HTTP clients and REST endpoints are added here.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka: {@code http://localhost:8761} → {@code LEDGER-SERVICE} registered on port 8086
 *   <li>Actuator: {@code GET http://localhost:8086/actuator/health} → 200 OK
 *   <li>Gateway routing: {@code GET http://localhost:8080/api/ledger/anything} → 404 from
 *       ledger-service (not 503 from gateway) confirms {@code lb://ledger-service} resolves
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → PostgreSQL →
 * Ledger-Service (8086)
 *
 * @see com.equitycart.ledger.service.impl.LedgerServiceImpl
 * @see com.equitycart.ledger.entity.LedgerEntry
 */
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(
    basePackages = {"com.equitycart.ledger", "com.equitycart.commons"},
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class))
@EntityScan(basePackages = {"com.equitycart.ledger", "com.equitycart.commons"})
public class LedgerServiceApplication {

  private static final Logger log = LogManager.getLogger(LedgerServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(LedgerServiceApplication.class, args);
    log.info(
        "Ledger Service started — listening on port 8086, registered with Eureka as LEDGER-SERVICE");
  }
}
