package com.equitycart.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server for EquityCart microservices.
 *
 * <p>Provides centralized, externalized configuration management. Services fetch their config from
 * this server at startup instead of embedding config in each JAR. Supports Git-backed config
 * repositories and dynamic property refresh without redeployment.
 *
 * <p>Internal flow (debug mode): 1. Config Server starts → reads application.yml with git.uri
 * pointing to equitycart-config repo 2. Client service starts → reads bootstrap.yml with
 * spring.config.import=configserver:<a href="http://localhost:8888">...</a> 3. Service makes GET
 * /application/default (or /{service-name}/default) 4. Config Server clones/pulls from Git repo,
 * parses application.yml + {service-name}.yml 5. Merges configs: application.yml (base) ←
 * {service-name}.yml (overrides) 6. Returns merged PropertySource to client 7. Client applies
 * config to environment (overrides hardcoded defaults)
 *
 * <p>Configuration: - spring.cloud.config.server.git.uri: Points to equitycart-config Git repo
 * (local file:// or remote https://) - Clients must have bootstrap.yml with
 * spring.config.import=configserver:... - Changes to configs: push to Git → services pull on
 * restart or /actuator/refresh POST
 *
 * <p>API Endpoints: - GET /{service}/{profile} → Returns merged config for service in profile - GET
 * /{service}/{profile}/{label} → Returns config for specific Git branch/tag - Example: <a
 * href="http://localhost:8888/user-service/default">...</a> → user-service.yml + application.yml
 * merged
 *
 * <p>Git Backend: - Local file:// URI (dev): file:///path/to/equitycart-config - Remote HTTPS
 * (prod): <a href="https://github.com/user/equitycart-config">...</a> - Config Server clones repo
 * on first request, pulls on subsequent requests
 *
 * @see EnableConfigServer
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

  /**
   * Entry point for Config Server.
   *
   * @param args command-line arguments (unused)
   */
  public static void main(String[] args) {
    SpringApplication.run(ConfigServerApplication.class, args);
  }
}
