package com.equitycart.user;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Standalone entry point for the User Service microservice.
 *
 * <p>Owns the user identity domain: registration, authentication (JWT), token refresh, logout, and
 * role-based access control. This is the only service that issues and validates JWT access tokens.
 *
 * <p>Internal startup flow (debug mode):
 *
 * <ol>
 *   <li>Spring reads local {@code application.yml}: resolves {@code
 *       spring.application.name=user-service} and {@code spring.config.import=configserver:...}
 *   <li>Config Client fetches {@code /user-service/default} from Config Server (port 8888)
 *   <li>Config Server merges {@code application.yml} (base) ← {@code user-service.yml} (overrides)
 *       — service-specific values (port 8081, equitycart_user datasource, jwt.secret) are returned
 *   <li>Spring applies merged config, connects to PostgreSQL {@code equitycart_user} database
 *   <li>Hibernate validates/updates schema ({@code ddl-auto: update} on first run creates tables)
 *   <li>{@link org.springframework.boot.CommandLineRunner} ({@code DataSeeder}) seeds default roles
 *       if absent
 *   <li>{@code @EnableDiscoveryClient} registers this instance with Eureka (port 8761) using {@code
 *       spring.application.name} as the service ID
 * </ol>
 *
 * <p>API surface (routed via gateway at {@code lb://user-service}):
 *
 * <ul>
 *   <li>{@code POST /api/auth/register} — create account + wallet
 *   <li>{@code POST /api/auth/login} — issue JWT access + refresh tokens
 *   <li>{@code POST /api/auth/refresh} — rotate refresh token, issue new access token
 *   <li>{@code POST /api/user/logout} — revoke all active refresh tokens
 * </ul>
 *
 * <p>Verify after startup:
 *
 * <ul>
 *   <li>{@code http://localhost:8761} — Eureka dashboard shows {@code USER-SERVICE} registered
 *   <li>{@code http://localhost:8081/actuator/health} — returns {@code {"status":"UP"}}
 *   <li>{@code http://localhost:8888/user-service/default} — confirms merged config returned
 * </ul>
 *
 * <p>Startup dependency order: PostgreSQL → Config Server (8888) → Eureka (8761) →
 * UserServiceApplication
 *
 * @see EnableDiscoveryClient
 */
@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {

  private static final Logger log = LogManager.getLogger(UserServiceApplication.class);

  /**
   * Entry point for User Service.
   *
   * @param args command-line arguments (unused)
   */
  public static void main(String[] args) {
    SpringApplication.run(UserServiceApplication.class, args);
    log.info(
        "User Service started — listening on port 8081, registered with Eureka as USER-SERVICE");
  }
}
