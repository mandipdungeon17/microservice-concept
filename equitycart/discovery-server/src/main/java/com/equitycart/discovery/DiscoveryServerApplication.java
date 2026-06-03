package com.equitycart.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka Discovery Server for EquityCart microservices.
 *
 * <p>Provides a service registry where all microservices register on startup and heartbeat every
 * 30s. Services use Eureka to discover and load-balance calls to other services.
 *
 * <p>Internal flow (debug mode): 1. Service starts → calls POST /eureka/apps/{appName} with
 * host:port 2. Eureka stores registration in in-memory ConcurrentHashMap (lease with 90s TTL) 3.
 * Service heartbeats every 30s → Eureka refreshes lease expiry 4. Other services fetch registry
 * every 30s via GET /eureka/apps → cache locally 5. If no heartbeat for 90s → Eureka evicts
 * instance (unless self-preservation mode active)
 *
 * <p>Configuration: - register-with-eureka: false (this IS the server, not a client) -
 * fetch-registry: false (server doesn't fetch its own registry) - enable-self-preservation: false
 * (dev mode: evict immediately on missed heartbeats)
 *
 * <p>Dashboard: <a href="http://localhost:8761">...</a>
 *
 * @see EnableEurekaServer
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

  /**
   * Entry point for Eureka Discovery Server.
   *
   * @param args command-line arguments (unused)
   */
  public static void main(String[] args) {
    SpringApplication.run(DiscoveryServerApplication.class, args);
  }
}
