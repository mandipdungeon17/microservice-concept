# Phase 7 Reference Updates — To Integrate Into Project Docs

This document contains all the learnings from Phase 7 Steps 1-3 that should be added to:

- springboot-reference.md
- microservice-patterns.md
- security-reference.md

Copy/paste relevant sections into those files, or inform me and I'll integrate them directly.

---

## Updates for springboot-reference.md

### Section: Spring Cloud Config + bootstrap.yml Breaking Change (Phase 7, Steps 1-2)

**Spring Boot 3.5.8 + Spring Cloud 2025.0.0 Breaking Change: bootstrap.yml Deprecated**

In Spring Boot 3.x / Spring Cloud 2025.0.0+, the separate `bootstrap.yml` file is no longer processed by default. This is a breaking change from Spring Cloud 2024.x and earlier.

**Historical context:**

- Spring Cloud < 2024.0: `bootstrap.yml` was processed in a separate "bootstrap phase" (Phase 1) before `application.yml`
- Spring Cloud 2024.0+: Bootstrap phase was merged into normal Spring Boot startup sequence
- Spring Cloud 2025.0.0: `bootstrap.yml` is deprecated and ignored

**Impact on Config Server integration:**

BEFORE (Spring Cloud 2024.x):

```yaml
# bootstrap.yml
spring:
  application:
    name: api-gateway
  config:
    import: configserver:http://localhost:8888
```

✅ Worked: bootstrap phase processed this, fetched configs from Config Server before beans initialized

AFTER (Spring Cloud 2025.0.0):

```yaml
# bootstrap.yml (ignored!)
spring:
  application:
    name: api-gateway
  config:
    import: configserver:http://localhost:8888
```

❌ Fails: Spring ignores bootstrap.yml, never fetches from Config Server, startup error: "No spring.config.import property has been defined"

**Solution: Move to application.yml**

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: api-gateway
  config:
    import: configserver:http://localhost:8888
  # ... rest of config
```

✅ Works: Spring Boot 3.x processes `spring.config.import` during normal startup, fetches from Config Server

**Key insight:** The timing of config import no longer matters (separate bootstrap phase doesn't exist). Spring Boot processes `spring.config.import` as part of normal `@EnableAutoConfiguration` — early enough to inject external configs into beans.

**Lesson for future upgrades:** Always check Spring Cloud release notes when upgrading Spring Boot versions. The bootstrap phase has been the source of multiple breaking changes across Spring Cloud versions.

---

### Section: @EnableDiscoveryClient — Annotation vs Dependency Activation (Phase 7, Step 3)

**Annotations Activate Beans; They Don't Provide Functionality**

A common mistake: adding `@EnableDiscoveryClient` to your main class and expecting service discovery to work. If service doesn't register with Eureka, check:

1. **Is the dependency present?**

   ```gradle
   implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'
   ```

   Without this, `@EnableDiscoveryClient` has no beans to activate. The annotation alone does nothing.

2. **Is the annotation on the main class?**

   ```java
   @SpringBootApplication
   @EnableDiscoveryClient
   public class GatewayApplication { ... }
   ```

   `@EnableDiscoveryClient` must be on a class that's scanned by Spring (e.g., main class or `@Configuration`).

3. **Is `spring.application.name` configured?**
   ```yaml
   spring:
     application:
       name: api-gateway
   ```
   Eureka uses this to register the service. If missing, Eureka shows name as "UNKNOWN" and service identities collide.

**Why the confusion?** In Spring, there's a pattern where annotations "enable" features. But unlike `@EnableAsync` (which also requires executor beans), `@EnableDiscoveryClient` is particularly fragile because:

- The dependency name (`spring-cloud-starter-netflix-eureka-client`) doesn't explicitly mention "discovery"
- No error is thrown if the dependency is missing — the annotation just silently does nothing
- Other starters (like `spring-cloud-starter-gateway`) don't transitively pull in the Eureka client

**Best practice:** When using `@EnableDiscoveryClient`, explicitly add the provider dependency to your build.gradle and verify startup logs for "Registering application ... with eureka".

---

### Section: Actuator Endpoints — Management Interface Configuration (Phase 7, Step 3)

**Configuring Actuator Endpoints in Spring Boot 3.5.8**

Actuator provides monitoring endpoints (`/actuator/health`, `/actuator/metrics`, `/actuator/info`, etc.) without writing code.

**Enable and expose endpoints:**

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
      base-path: /actuator
  endpoint:
    health:
      show-details: always # or "when-authorized" for sensitive info
```

**Important:** Configuration is necessary but not sufficient if Spring Security is active.

If you see HTTP 403 (Forbidden) instead of 200 on `/actuator/health`, your security rules are blocking it.

**With Spring Security, explicitly allow actuator:**

```java
// SecurityConfig.java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authz -> authz
        .requestMatchers("/actuator/**").permitAll()      // Allow public access
        .requestMatchers("/api/auth/**").permitAll()      // Allow login/register
        .anyRequest().authenticated()                     // Everything else needs auth
    );
    return http.build();
}
```

**Common pitfall:** Exposing actuator in `management.endpoints.web.exposure.include` doesn't override Spring Security. They're independent: management config says "which endpoints exist", security config says "who can access them". Both must allow access.

**Actuator endpoints by sensitivity:**

- `health` — safe, shows status (UP/DOWN)
- `info` — safe, shows version/build info
- `metrics` — sensitive, exposes system metrics (memory, GC, requests)
- `env` — sensitive, may expose secrets
- `configprops` — sensitive, shows all properties (including passwords if not masked)

In production, use `exposure.include: health,metrics,info` (safe ones) + role-based access for sensitive endpoints (`@PreAuthorize("hasRole('ADMIN')")`).

---

## Updates for microservice-patterns.md

### Section: Service Discovery — Eureka Registry Pattern (Phase 7, Step 1)

**Pattern: Eureka Service Discovery**

**Problem:** In a distributed system with N services, clients need to find and call each other. Hardcoding IPs/hostnames breaks when:

- Services scale (new instances get new IPs)
- Services restart (IP changes)
- Services die (IP goes stale)

**Solution:** Eureka maintains a dynamic registry. Services self-register; clients query the registry.

**Registration flow:**

```
Service startup
  ↓ POST /eureka/apps/{appName} with InstanceInfo
  ├─ hostname, port, status
  └─ Eureka: store in memory, assign 90s TTL lease

Service running
  ↓ Heartbeat every 30s
  ├─ GET /eureka/apps (fetch other services)
  └─ Eureka: refresh lease TTL (extends expiry)

Service failure
  ↓ Misses 3 heartbeats (90s no signal)
  └─ Eureka: evict from registry (unless self-preservation mode)
```

**Client-side:**

- Local cache of registry (fetched every 30s)
- Round-robin load balancing across instances
- Ribbon/Spring Cloud LoadBalancer handles failover

**Configuration:**

```yaml
# Server (single, no clustering in Phase 7)
eureka:
  client:
    register-with-eureka: false    # This IS the server, don't register
    fetch-registry: false          # Server doesn't fetch its own registry
  server:
    enable-self-preservation: false  # Dev: strict mode (immediate eviction)
    eviction-interval-timer-in-ms: 10000

# Client services
eureka:
  client:
    serviceUrl:
      defaultZone: http://localhost:8761/eureka/
```

**Self-preservation mode:** If Eureka detects a large number of missed heartbeats, it assumes a network partition (not service failures) and stops evicting. In prod, leave enabled; in dev learning, disable to see failures immediately.

**Lessons from Phase 7 implementation:**

- Services MUST have `spring-cloud-starter-netflix-eureka-client` dependency
- Services MUST have `spring.application.name` configured (used as registration name)
- Dashboard at http://localhost:8761 shows all registered instances + health
- Without heartbeats, services are evicted after 90s (configurable via `lease.expiration-duration-in-seconds`)

---

### Section: Config Server — Centralized Configuration Pattern (Phase 7, Step 2)

**Pattern: Spring Cloud Config Server (Git-Backed)**

**Problem:** Configuration changes per environment (dev/staging/prod) and per service. Hardcoding config in JARs means recompiling/redeploying for every config change.

**Solution:** Config Server reads from Git repository (single source of truth) and serves merged configs to clients.

**Config Server setup:**

```yaml
# config-server/application.yml
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/yourorg/your-config-repo.git
          # OR local for dev:
          # uri: file:///path/to/your-config-repo
server:
  port: 8888
```

**Config repository structure:**

```
your-config-repo/
├── application.yml              # Base config (all services, all envs)
├── application-dev.yml          # Dev env overrides
├── application-prod.yml         # Prod env overrides
├── api-gateway.yml              # Service-specific (all envs)
├── api-gateway-dev.yml          # Service + env combo
├── user-service.yml
├── portfolio-service.yml
└── ... (one YAML per service)
```

**Client requests config:**

```
Client startup reads: spring.config.import: configserver:http://localhost:8888
  ↓
Client needs: spring.application.name (e.g., "api-gateway")
  ↓
Client makes: HTTP GET /api-gateway/default
  ↓
Config Server:
  ├─ Merge: application.yml (base) ← api-gateway.yml (overrides)
  └─ Return merged PropertySource as JSON
  ↓
Client merges: remote config ← local application.yml (local overrides remote)
```

**Merge priority (highest to lowest):**

1. Local `application.yml` property
2. Remote `{service}.yml` property
3. Remote `application.yml` property
4. Spring Boot defaults

**Environment-specific configs:**

```bash
# Dev environment
GET /api-gateway/dev → returns merged application-dev.yml ← api-gateway-dev.yml

# Prod environment
GET /api-gateway/prod → returns merged application-prod.yml ← api-gateway-prod.yml
```

**Lessons from Phase 7 implementation:**

- Config Server reads from Git (immutable source), not a database → better for audit/rollback
- Clients cache configs locally (transient Config Server outages don't break running services)
- Use `optional:configserver:...` for graceful degradation if Config Server unavailable at startup
- Use `configserver:...` (required) for services that cannot start without central config

---

### Section: Spring Cloud Gateway — API Gateway + Service Routing (Phase 7, Step 3)

**Pattern: API Gateway as Service Mesh Entry Point**

**Problem:** Clients face N service endpoints. Direct service calls mean:

- Client knows all service locations (tight coupling)
- Client routes requests (business logic leaks)
- Cross-cutting concerns (auth, rate-limiting, logging) duplicated per service

**Solution:** API Gateway is single entry point. Routes requests to downstream services, handles cross-cutting concerns centrally.

**Routing configuration:**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service # Eureka-aware URI (load-balanced)
          predicates:
            - Path=/api/auth/**,/api/users/**

        - id: portfolio-service
          uri: lb://portfolio-service
          predicates:
            - Path=/api/portfolio/**

        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
```

**How it works:**

```
Client request: POST /api/auth/login
  ↓
Gateway matches: Path=/api/auth/** → route to lb://user-service
  ↓
Eureka resolution: lb:// discovers user-service instances
  ↓
Load balancer: selects one instance (round-robin)
  ↓
Gateway forwards: HTTP POST to http://user-service-host:8081/api/auth/login
  ↓
Response: returned to client (transparent proxy)
```

**Key features:**

- `lb://` URIs — Load-balanced Eureka discovery (service name, not IP)
- Path predicates — Route by URL path, hostname, header, etc.
- Filters — Intercept requests (add headers, transform, rate-limit)
- Circuit breaker — Handle downstream failures gracefully

**Gateway as central security point:**

- Authenticate all requests once
- Rate-limit per client
- Log all traffic
- Transform requests (add correlation IDs, etc.)

**Lessons from Phase 7 implementation:**

- Gateway registers itself with Eureka (becomes discoverable service)
- Gateway uses `@EnableDiscoveryClient` to enable Eureka client
- Gateway itself is port 8080; downstream services are 8081+ (separated by purpose)
- Actuator endpoints on gateway accessible at `http://localhost:8080/actuator/health` (not `/api-gateway/actuator/...`)

---

## Updates for security-reference.md

### Section: Spring Security + Actuator Endpoints (Phase 7, Step 3)

**Actuator Endpoints are Blocked by Spring Security by Default**

If you configure actuator to expose health/metrics but see 403 Forbidden, your Spring Security configuration is blocking it.

**Example problem:**

```java
// Naïve SecurityConfig — blocks /actuator/**
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authz -> authz
        .anyRequest().authenticated()      // ❌ ALL requests need auth, including /actuator
    );
    return http.build();
}
```

Result: `/actuator/health` returns 403, even if `management.endpoints.web.exposure.include` lists "health".

**Solution: Explicitly permit /actuator/**:\*\*

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authz -> authz
        .requestMatchers("/actuator/health").permitAll()        // Allow unauthenticated health checks
        .requestMatchers("/actuator/metrics").hasRole("ADMIN")  // Role-based access
        .requestMatchers("/api/auth/**").permitAll()            // Public auth endpoints
        .anyRequest().authenticated()                           // Everything else needs auth
    );
    return http.build();
}
```

**Or, use Spring Security's built-in actuator support:**

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(authz -> authz
            .requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated()
            .requestMatchers("/api/auth/**").permitAll()
            .anyRequest().authenticated()
        )
        .httpBasic(Customizer.withDefaults());  // Use HTTP Basic for actuator
    return http.build();
}
```

This uses `EndpointRequest` (Spring Security integration) to match all actuator endpoints by name, not hardcoded paths.

**Principle: Management Interface ≠ Application Interface**

Actuator is the "management" interface (ops/monitoring). Application API is the "business" interface (clients). They should have different security rules:

- `/actuator/**` → Usually admin/monitoring only (or public for health checks)
- `/api/**` → Public auth endpoints + authenticated user endpoints

In EquityCart Phase 7: api-gateway allows `/actuator/**` public (simple health checks), while user-service blocks it by default (requires ADMIN role) to prevent information leakage.

**Lessons from Phase 7:**

- Spring Security AuthenticationFilter chain runs BEFORE Spring Cloud Gateway filter chain
- Gateway's Security config affects ALL downstream services (centralized auth point)
- Actuator endpoints on each service have their OWN security config (per-service management rules)
- Test actuator access with curl: `curl -v http://localhost:8080/actuator/health` to verify 200 vs 403

---

## Summary: Which Files to Update

1. **springboot-reference.md**
   - Add: "Spring Boot 3.5.8 + Spring Cloud 2025.0.0 Breaking Change: bootstrap.yml Deprecated"
   - Add: "@EnableDiscoveryClient — Annotation vs Dependency Activation"
   - Add: "Actuator Endpoints — Management Interface Configuration"

2. **microservice-patterns.md**
   - Add: "Pattern: Eureka Service Discovery"
   - Add: "Pattern: Spring Cloud Config Server (Git-Backed)"
   - Add: "Pattern: Spring Cloud Gateway — API Gateway + Service Routing"

3. **security-reference.md**
   - Add: "Spring Security + Actuator Endpoints"

4. **learning_log.md**
   - Append: [Content from phase7-learnings.md in root directory]

5. **progress.md** ✅ DONE

6. **MEMORY.md** (Optional)
   - Record: User teaches code ownership rules, specification-only approach, documentation requirements
   - Record: Phase 7 learnings about Spring Cloud versioning, Eureka registration, Config Server patterns
