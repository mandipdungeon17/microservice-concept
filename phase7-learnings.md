---
## Phase 7: Microservices Decomposition — Discovery, Config, Gateway ✅ (Steps 1-3)

### Date: 2026-06-02
---

### Roadblocks & Issues Faced

**1. bootstrap.yml Not Processed in Spring Boot 3.5.8 + Spring Cloud 2025.0.0**

- Problem: Created `bootstrap.yml` with `spring.config.import: configserver:http://localhost:8888` in all service directories. Spring Boot threw error: "No spring.config.import property has been defined."
- Root cause: Spring Boot 3.5.8 / Spring Cloud 2025.0.0 no longer processes `bootstrap.yml` by default (breaking change from Spring Cloud 2024.x).
- Fix: Moved `spring.config.import` and `spring.application.name` from `bootstrap.yml` to local `application.yml`. Deleted all bootstrap.yml files.
- Lesson: Spring Cloud versioning has breaking changes with Spring Boot 3.x. Always check official migration guides.

**2. @EnableDiscoveryClient Without Eureka Dependency — No Registration Logs**

- Problem: Added `@EnableDiscoveryClient` to GatewayApplication, but no registration occurred. Eureka dashboard showed zero instances.
- Root cause: `@EnableDiscoveryClient` is an annotation only — it activates beans provided by dependencies. Without `spring-cloud-starter-netflix-eureka-client`, the annotation has nothing to enable.
- Fix: Added `implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'` to api-gateway/build.gradle. **This dependency is required for ALL services.**
- Lesson: Annotations don't add functionality — they activate provided beans. Missing dependency = annotation is a no-op.

**3. YAML Structure Error: gateway routes under `server.cloud.gateway` instead of `spring.cloud.gateway`**

- Problem: In equitycart-config/api-gateway.yml, wrote `server.cloud.gateway` instead of `spring.cloud.gateway`.
- Root cause: YAML indentation ambiguity.
- Fix: Restructured YAML so `cloud.gateway` lives under `spring:`, not `server:`.
- Lesson: YAML indentation is error-prone. Use validators or tests to verify structure.

**4. Actuator Endpoints Blocked by Spring Security (403 Forbidden)**

- Problem: api-gateway (8080) returned HTTP 200 for `/actuator/health`, but equitycart app (8082) returned HTTP 403.
- Root cause: equitycart has Spring Security active, which by default blocks all endpoints except explicitly permitted.
- Fix: Added to SecurityConfig: `.requestMatchers("/actuator/**").permitAll()`.
- Lesson: **Spring Security is the ultimate gatekeeper.** Configuration alone is insufficient if SecurityFilterChain blocks it.

**5. Port Conflict: Both api-gateway and equitycart on port 8080**

- Problem: Running both failed with "Address already in use."
- Fix: Changed equitycart to 8082.
- Lesson: Multi-service development requires explicit port management.

---

### Core Concepts Learned

**1. Service Discovery — Eureka Registry Model**

Eureka maintains an in-memory registry of all services. Services register with hostname, port, health status. Heartbeat every 30s refreshes 90s TTL lease. Other services fetch registry every 30s. If service misses 3 heartbeats, Eureka evicts it (unless self-preservation mode enabled). Self-preservation prevents eviction during network partitions.

**2. Config Server — Git-Backed Centralized Configuration**

Client reads `spring.config.import: configserver:...` + uses `spring.application.name` to fetch service-specific config. Config Server merges `application.yml` (base) ← `service.yml` (overrides) from Git repo. Enables ops to change configs without redeploying code.

**3. Spring Cloud Gateway — Reverse Proxy with Service Discovery**

Single entry point for all downstream services. Routes match by path/header → forward to `lb://service-name` (Eureka-resolved URIs + load balancing). Enables failover and scaling without hardcoding IPs.

**4. bootstrap.yml vs application.yml — Spring Boot 3.x Breaking Change**

Spring Boot 3.x merged bootstrap phase into normal startup. `spring.config.import` must now be in `application.yml`, not separate `bootstrap.yml`. Old Spring Cloud versions required bootstrap.yml; Spring Cloud 2025.0.0 ignores it.

**5. @EnableDiscoveryClient Activation Model**

Annotations only activate beans provided by dependencies. `@EnableDiscoveryClient` requires `spring-cloud-starter-netflix-eureka-client` on classpath to function.

**6. Actuator + Spring Security Authorization Chain**

Actuator endpoints are exposed by `management.endpoints.web.exposure.include`, but still subject to Spring Security. Must explicitly allow `/actuator/**` in authorization rules if Spring Security is active.

---

### Key Decisions Made

1. Separate equitycart-config Git repo for configuration (ops can deploy changes without code rebuild)
2. Port allocations: 8080 (gateway), 8761 (Eureka), 8888 (Config Server), 8081-8087 (future services)
3. All services MUST have `spring-cloud-starter-netflix-eureka-client` dependency
4. `spring.application.name` stays in local `application.yml` (Config Server uses it for service-specific config lookup)

---

### Interview Questions & Answers

**Q108: "Why does Eureka need both heartbeat AND TTL lease?" (2026-06-02)**

A: Heartbeat is the signal ("I'm alive"), lease TTL is the timeout ("if silent 90s, evict me"). Heartbeat refreshes TTL so healthy services stay registered. If heartbeat stops, TTL expires and service is evicted. This is TCP keep-alive logic: periodic signal + timeout = death detection.

**Q109: "What if Config Server is down at startup?" (2026-06-02)**

A: `spring.config.import: configserver:...` (required) — startup FAILS if unreachable. `spring.config.import: optional:configserver:...` (optional) — proceeds with local application.yml. In EquityCart, we use required (Config Server is hard dependency for all microservices).

**Q110: "Can Config Server serve different configs per environment?" (2026-06-02)**

A: Yes! `/api-gateway/dev` vs `/api-gateway/prod` via profile parameter. Git repo contains `application.yml` + `application-dev.yml` + `application-prod.yml`. Config Server merges base + profile-specific overrides. This enables environment-specific deployments without code changes.

**Q111: "Why gateway + Eureka instead of hardcoded downstream URLs?" (2026-06-02)**

A: (1) Elasticity — services scale/restart without config updates. (2) Failover — dead instances auto-evicted. (3) Decoupling — services self-register. (4) Ops simplicity — scale without touching config.

**Q112: "Is Config Server a single point of failure?" (2026-06-02)**

A: Yes, but mitigated: (1) Reads from Git (immutable source), not a database. (2) Clients cache configs locally (transient outages don't affect running services). (3) Config Server can be clustered (multi-instance behind LB). New deployments fail until Config Server recovers; running services unaffected.
