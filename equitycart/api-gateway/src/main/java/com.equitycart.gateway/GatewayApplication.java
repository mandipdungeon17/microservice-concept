package com.equitycart.gateway;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Cloud Gateway — single entry point for all EquityCart microservices.
 *
 * <p>Acts as a reverse proxy: routes inbound HTTP requests to downstream services discovered via
 * Eureka. All client traffic enters here and is forwarded based on path-matching predicates defined
 * in equitycart-config/api-gateway.yml.
 *
 * <p>Internal flow (debug mode): 1. Request arrives at gateway (port 8080) 2. Gateway matches route
 * by path predicate (e.g., /api/auth/** → user-service) 3. {@code lb://service-name} URI triggers
 * Eureka lookup via Spring Cloud LoadBalancer 4. LoadBalancer selects an instance (round-robin
 * across registered instances) 5. Gateway forwards request to selected instance (e.g.,
 * http://localhost:8081/api/auth/login) 6. Downstream response returned to client (transparent
 * proxy)
 *
 * <p>Route configuration (in equitycart-config/api-gateway.yml):
 *
 * <pre>
 * spring.cloud.gateway.routes:
 *   - id: user-service       → lb://user-service      Path=/api/auth/**,/api/users/**
 *   - id: order-service      → lb://order-service     Path=/api/order/**
 *   - id: portfolio-service  → lb://portfolio-service Path=/api/portfolio/**
 *   - id: market-data-service→ lb://market-data-service Path=/api/market-data/**
 *   - id: ledger-service     → lb://ledger-service    Path=/api/ledger/**
 *   - id: notification-service→ lb://notification-service Path=/api/notifications/**
 * </pre>
 *
 * <p>Service Discovery: - {@code @EnableDiscoveryClient} registers gateway with Eureka and enables
 * {@code lb://} resolution - Eureka lookup happens at request time (not startup) — stale instances
 * are skipped by load balancer - Requires {@code spring-cloud-starter-netflix-eureka-client} on
 * classpath
 *
 * <p>Centralized Config: - Fetches all configuration from Config Server at startup (via
 * spring.config.import in application.yml) - Routes, ports, and actuator settings defined in
 * equitycart-config/api-gateway.yml - Config Server must be running before gateway starts
 *
 * <p>Actuator endpoints: /actuator/health, /actuator/metrics, /actuator/info (port 8080)
 *
 * @see EnableDiscoveryClient
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

  private static final Logger log = LogManager.getLogger(GatewayApplication.class);

  /**
   * Entry point for API Gateway.
   *
   * @param args command-line arguments (unused)
   */
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
    log.info("API Gateway started — listening on port 8080, routing via Eureka discovery");
  }
}
