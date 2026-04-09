# EquityCart — Project Roadmap

> **Estimated Total Duration: 20–26 weeks** (assuming 15–20 hours/week of focused work)
>
> This estimate accounts for deep learning + design discussion + hands-on implementation + review cycles.
> Faster if you have strong Spring Boot experience. Slower if concepts like Kafka, CQRS, or distributed transactions are new.

---

## Tech Stack (100% Open-Source)

| Layer             | Technology                                            |
| ----------------- | ----------------------------------------------------- |
| Language          | Java 21 (LTS)                                         |
| Framework         | Spring Boot 3.x, Spring WebFlux                       |
| Build             | Gradle (Groovy DSL)                                   |
| SQL Database      | PostgreSQL                                            |
| NoSQL Database    | MongoDB                                               |
| Cache             | Redis                                                 |
| Message Broker    | Apache Kafka                                          |
| Batch Processing  | Spring Batch                                          |
| API Gateway       | Spring Cloud Gateway                                  |
| Service Discovery | Netflix Eureka                                        |
| Config Server     | Spring Cloud Config (Git-backed)                      |
| Circuit Breaker   | Resilience4j                                          |
| Security          | Spring Security + JWT + OAuth2 (Keycloak)             |
| Auth Server       | Keycloak (open-source)                                |
| Monitoring        | Prometheus + Grafana                                  |
| Tracing           | Micrometer Tracing + Zipkin                           |
| Logging           | ELK Stack (Elasticsearch + Logstash + Kibana) or Loki |
| Containerization  | Docker + Docker Compose                               |
| Orchestration     | Kubernetes (Minikube for local)                       |
| CI/CD             | GitHub Actions                                        |
| API Docs          | SpringDoc OpenAPI (Swagger)                           |
| Testing           | JUnit 5, Mockito, Testcontainers, WireMock            |
| Market Data API   | Alpaca (free tier) or Yahoo Finance API               |

---

## Phase 0: Foundation & Project Setup [Week 1] ✅ COMPLETE

### Design Focus

- Finalize domain model vocabulary (Ubiquitous Language)
- Define bounded contexts and module boundaries
- Establish coding standards, package conventions, Git workflow

### Deliverables

- [x] Mono-repo structure with Gradle multi-module skeleton
- [x] Shared `commons` module (DTOs, exceptions, constants)
- [ ] Docker Compose for PostgreSQL + MongoDB + Redis (local dev)
- [ ] CI pipeline (build + test on push)
- [x] Base `application.yml` profiles (dev, test, prod)

### Learning Topics

- Gradle multi-module project setup
- Spring Boot auto-configuration internals
- Docker Compose networking basics
- 12-Factor App principles

```
microservice-concept/
└── equitycart/
    ├── build.gradle              (root build)
    ├── settings.gradle           (include subprojects)
    ├── commons/                  (shared module)
    ├── user-service/
    ├── order-service/
    ├── portfolio-service/
    ├── market-data-service/
    ├── ledger-service/
    ├── notification-service/
    ├── api-gateway/
    ├── discovery-server/
    ├── config-server/
    └── docker/
        └── docker-compose.yml
```

> **Phase 0 Strategy**: Create ALL modules as empty shells now.
> We build them as a **monolith first** (single deployable),
> then decompose into microservices in Phase 7.

---

## Phase 1: User Service & Security Foundation [Weeks 2–3]

### Design Focus

- User entity design (roles, KYC status, wallet linkage)
- Authentication architecture (JWT flow, refresh tokens)
- Authorization model (RBAC: CUSTOMER, ADMIN, SYSTEM)
- API security patterns (rate limiting, input validation, CORS)

### Deliverables

- [ ] User entity + JPA mapping (PostgreSQL)
- [ ] Registration / Login / Logout APIs
- [ ] JWT token generation & validation filter
- [ ] Role-based access control on endpoints
- [ ] Password encryption (BCrypt)
- [ ] Input validation (Bean Validation API)
- [ ] Global exception handler (@ControllerAdvice)
- [ ] Unit + Integration tests with Testcontainers

### Learning Topics

- **JPA/Hibernate**: Entity lifecycle (transient → managed → detached → removed)
- **Spring Security**: Filter chain internals, SecurityContext, Authentication object
- **JWT**: Stateless auth, token structure, refresh token rotation
- **Transaction Management**: Default propagation in Spring, read-only optimization
- **Interview Prep**: "Design an authentication system" — walkthrough

---

## Phase 2: Product Catalog & Brand-Ticker Mapping [Weeks 4–5]

### Design Focus

- Product entity modeling (categories, brands, pricing, stock-back percentage)
- Brand-to-Ticker mapping schema (PostgreSQL)
- Bulk product import pipeline design (CSV → validate → persist)
- Read-heavy catalog: caching strategy

### Deliverables

- [ ] Product entity + Category entity (PostgreSQL, JPA)
- [ ] Brand entity + BrandTickerMapping entity
- [ ] CRUD APIs for Products (admin)
- [ ] Search/Filter APIs for Products (customer) with Spring Data JPA Specifications
- [ ] Bulk CSV import using Spring Batch (chunked reader → processor → writer)
- [ ] Redis caching for product listings (@Cacheable, TTL-based eviction)
- [ ] Brand-Ticker admin management APIs

### Learning Topics

- **Spring Data JPA**: Specifications, Projections, Pagination, custom queries
- **N+1 Problem**: Detect with Hibernate statistics, fix with JOIN FETCH / EntityGraph
- **Spring Batch**: Job → Step → (ItemReader → ItemProcessor → ItemWriter), chunk-oriented processing
- **Caching**: @Cacheable / @CacheEvict lifecycle, Redis serialization, TTL strategies
- **File Parsing**: OpenCSV / Apache Commons CSV, validation pipeline, error reporting
- **Interview Prep**: "How would you design a product catalog for 10M products?"

---

## Phase 3: Order Service & Cart [Weeks 6–7]

### Design Focus

- Order lifecycle state machine (CREATED → CONFIRMED → PROCESSING → SHIPPED → DELIVERED → RETURNED)
- Cart design (Session-based? Redis-backed? DB-persisted?)
- Inventory management and stock reservation
- Idempotency design for order creation

### Deliverables

- [ ] Cart API (add/remove/update items) — Redis-backed
- [ ] Order entity with order-items (PostgreSQL)
- [ ] Order placement with inventory reservation
- [ ] Idempotency key implementation (request deduplication)
- [ ] Order status tracking APIs
- [ ] Return/Refund initiation flow
- [ ] Pessimistic locking on inventory during checkout

### Learning Topics

- **Locking**: Optimistic (@Version) vs Pessimistic (LockModeType.PESSIMISTIC_WRITE)
- **Transaction Isolation**: READ_COMMITTED vs REPEATABLE_READ — when each matters
- **Idempotency**: Token-based deduplication, database unique constraints
- **State Machine**: Enum-based vs Spring State Machine for order lifecycle
- **Entity Modeling**: @OneToMany, @ManyToOne, cascade types, orphan removal
- **Interview Prep**: "Design an order management system with inventory reservation"

---

## Phase 4: Market Data Service (Reactive) [Weeks 8–9]

### Design Focus

- Reactive architecture for real-time price fetching
- External API integration patterns (circuit breaker, fallback, retry)
- Price caching strategy (short TTL for real-time, longer for historical)
- Company health score algorithm design

### Deliverables

- [ ] Spring WebFlux service wrapping Alpaca/Yahoo Finance API
- [ ] Real-time price endpoint (GET /prices/{ticker}) — non-blocking
- [ ] Batch price fetch for multiple tickers (Flux)
- [ ] Resilience4j circuit breaker + retry + rate limiter on external calls
- [ ] Redis cache for latest prices (5-second TTL)
- [ ] Company Health Score endpoint (market cap, P/E, 52-week performance)
- [ ] Historical price data storage (MongoDB — time-series)
- [ ] WebSocket/SSE endpoint for live price streaming to frontend

### Learning Topics

- **Spring WebFlux**: Mono/Flux, WebClient, reactive streams, backpressure
- **Resilience Patterns**: Circuit breaker states (CLOSED → OPEN → HALF_OPEN), bulkhead
- **NoSQL**: MongoDB document design for time-series data, TTL indexes
- **CAP Theorem**: Why MongoDB (AP) for market data vs PostgreSQL (CP) for ledger
- **Caching**: Multi-layer cache (L1: local Caffeine, L2: Redis)
- **Interview Prep**: "Design a real-time stock price service"

---

## Phase 5: Portfolio Service & Stock-Back Engine [Weeks 10–12]

### Design Focus

- Unified Wealth Account: Shopping Credits + Investment Portfolio
- Double-Entry Ledger design (every transaction = debit + credit = zero sum)
- Equity Calculation Engine: Discount → Fractional shares
- Vesting schedule and state machine (PENDING → VESTED → LIQUIDATED)
- Trading engine: BUY/SELL order processing

### Deliverables

- [ ] Portfolio entity + Holdings entity (PostgreSQL)
- [ ] Ledger entity with double-entry bookkeeping
- [ ] Stock-Back reward calculation on order completion
- [ ] Vesting status tracker (PENDING during return window)
- [ ] Spring Batch daily vesting job (move eligible → VESTED)
- [ ] BUY/SELL trade APIs (manual trading)
- [ ] "Sell to Spend" — liquidate stock to fund purchase
- [ ] Atomic transaction: if stock sale fails → order payment rejected
- [ ] Portfolio analytics: total value, growth %, average buy price
- [ ] Idempotency on reward granting (one reward per order ID)

### Learning Topics

- **Transaction Management (Advanced)**: REQUIRES_NEW propagation for ledger entries, isolation levels for concurrent trades
- **Optimistic Locking**: @Version on Holdings to handle concurrent trades on same stock
- **Double-Entry Accounting**: Debit/Credit model, reconciliation, audit trail
- **Spring Batch (Advanced)**: SkipPolicy, RetryPolicy, job parameters, restartability
- **Entity Relationships**: Portfolio → Holdings (1:N), Order → Reward (1:1), Ledger entries
- **Interview Prep**: "Design a stock trading system" / "Explain distributed transactions"

---

## Phase 6: Event-Driven Architecture [Weeks 13–15]

### Design Focus

- Event-driven pipeline: Order placed → Stock reward calculated → Portfolio updated → Notification sent
- Kafka topic design, partitioning strategy, consumer groups
- Outbox pattern for reliable event publishing
- Saga pattern for distributed transactions (order + payment + portfolio)
- Event sourcing for portfolio audit trail

### Deliverables

- [ ] Kafka cluster setup (Docker Compose)
- [ ] Order-Placed event → triggers stock-back reward calculation
- [ ] Reward-Granted event → updates portfolio
- [ ] Outbox table + Debezium CDC or polling publisher
- [ ] Saga orchestrator for "Sell to Spend" flow
- [ ] Dead Letter Queue (DLQ) for failed event processing
- [ ] Event sourcing for portfolio changes (MongoDB)
- [ ] Notification service (email/webhook on trade execution, vesting completion)
- [ ] Retry logic with exponential backoff

### Learning Topics

- **Apache Kafka**: Topics, partitions, consumer groups, offset management, exactly-once semantics
- **Outbox Pattern**: Why direct Kafka publish from service is dangerous
- **Saga Pattern**: Orchestration vs Choreography, compensating transactions
- **Event Sourcing**: Event store, projections, rebuilding state from events
- **Eventual Consistency**: Handling. "Portfolio shows old balance for 2 seconds"
- **Async (@Async)**: Spring async internals, thread pool configuration
- **Interview Prep**: "How do you handle distributed transactions across microservices?"

---

## Phase 7: Microservices Decomposition [Weeks 16–18]

### Design Focus

- Decompose the monolith into independently deployable services
- API Gateway pattern (routing, rate limiting, auth propagation)
- Service discovery and client-side load balancing
- Inter-service communication (sync: OpenFeign/WebClient, async: Kafka)
- Config externalization

### Deliverables

- [ ] Spring Cloud Eureka discovery server
- [ ] Spring Cloud Config server (Git-backed)
- [ ] Spring Cloud Gateway (routes, filters, rate limiting)
- [ ] Extract User-Service as standalone microservice
- [ ] Extract Order-Service as standalone microservice
- [ ] Extract Portfolio-Service as standalone microservice
- [ ] Extract Market-Data-Service as standalone microservice
- [ ] Extract Ledger-Service as standalone microservice
- [ ] OpenFeign clients for sync inter-service calls
- [ ] Correlation ID propagation across services
- [ ] Independent database per service (database-per-service pattern)
- [ ] Docker Compose for full stack (all services + infra)

### Learning Topics

- **Microservices Patterns**: Strangler Fig, Database-per-Service, API Gateway, BFF
- **Spring Cloud**: Eureka internals, Gateway filters, Config refresh
- **Service Communication**: Sync vs Async trade-offs, circuit breaker on Feign calls
- **Data Ownership**: Each service owns its database, no shared DB
- **Interview Prep**: "How would you decompose a monolith?" / "Explain service discovery"

---

## Phase 8: Security Hardening [Weeks 19–20]

### Design Focus

- OAuth2 + OpenID Connect with Keycloak
- Service-to-service authentication (mTLS or JWT propagation)
- API security (rate limiting, input sanitization, SQL injection prevention)
- Data encryption (at rest + in transit)
- Secrets management

### Deliverables

- [ ] Keycloak setup with realm, clients, roles
- [ ] OAuth2 Resource Server on each microservice
- [ ] Token relay through API Gateway
- [ ] Service-to-service JWT propagation via Feign interceptors
- [ ] Rate limiting at Gateway (Redis-backed)
- [ ] OWASP security headers
- [ ] Input sanitization and parameterized queries audit
- [ ] TLS configuration for all inter-service calls
- [ ] Secrets in environment variables (no hardcoded credentials)
- [ ] Security integration tests

### Learning Topics

- **OAuth2/OIDC**: Authorization Code flow, token introspection, scopes vs roles
- **Keycloak**: Realm, client, roles, token mappers
- **Spring Security (Advanced)**: Resource server, method security, @PreAuthorize SpEL
- **API Security**: OWASP Top 10, CORS, CSRF, Content-Security-Policy
- **Interview Prep**: "Design an authentication/authorization system for microservices"

---

## Phase 9: Observability & Production Readiness [Weeks 21–22]

### Design Focus

- Three pillars: Logs, Metrics, Traces
- Centralized logging architecture
- Health checks and readiness probes
- Alerting strategy

### Deliverables

- [ ] Structured logging (JSON format) with correlation IDs
- [ ] Centralized logging with ELK or Loki + Grafana
- [ ] Micrometer metrics → Prometheus → Grafana dashboards
- [ ] Distributed tracing with Zipkin (trace across all services)
- [ ] Health/Readiness endpoints (Spring Actuator)
- [ ] Custom business metrics (trades/sec, rewards granted/day, cache hit ratio)
- [ ] Alerting rules in Grafana (error rate > 5%, latency P99 > 2s)

### Learning Topics

- **Observability**: Logs vs Metrics vs Traces — when to use each
- **Distributed Tracing**: Trace context propagation, span creation
- **Prometheus**: Pull-based model, PromQL basics
- **Interview Prep**: "How do you monitor microservices in production?"

---

## Phase 10: Advanced Features & Scale [Weeks 23–26]

### Design Focus

- CQRS for portfolio (write to SQL, read from denormalized MongoDB view)
- Stock gifting with saga pattern
- Flash sale with distributed locking (Redis + Redisson)
- Tax report generation (Spring Batch)
- Performance tuning and load testing

### Deliverables

- [ ] CQRS: Kafka event → MongoDB read model for portfolio queries
- [ ] Stock Gifting: atomic transfer between two portfolios
- [ ] Flash Sale Stock Drops: distributed lock, inventory control, cache burst invalidation
- [ ] Price Alert Watchlist: async evaluation, WebSocket push
- [ ] Dividend DRIP: scheduled reinvestment batch job
- [ ] Tax Report CSV/PDF generation (Spring Batch)
- [ ] Portfolio Leaderboard (MongoDB aggregation pipeline)
- [ ] Return Clawback saga (compensating transaction)
- [ ] Load testing with Gatling or k6
- [ ] Performance tuning (connection pools, thread pools, query optimization)

### Learning Topics

- **CQRS**: Command model vs Query model, projection rebuilds, eventual consistency
- **Distributed Locking**: Redis SETNX, Redisson, fencing tokens
- **Performance**: JVM tuning, Hikari pool sizing, Kafka consumer tuning
- **Interview Prep**: Full system design mock — "Design EquityCart end-to-end"

---

## Time Estimate Summary

| Phase                                   | Duration        | Focus                                         |
| --------------------------------------- | --------------- | --------------------------------------------- |
| Phase 0: Foundation & Setup             | Week 1          | Project skeleton, Docker, CI                  |
| Phase 1: User Service & Security        | Weeks 2–3       | Auth, JWT, JPA basics                         |
| Phase 2: Product Catalog & Batch Import | Weeks 4–5       | Spring Data JPA, Caching, Spring Batch        |
| Phase 3: Order Service & Cart           | Weeks 6–7       | Locking, Transactions, Idempotency            |
| Phase 4: Market Data (Reactive)         | Weeks 8–9       | WebFlux, Resilience4j, MongoDB                |
| Phase 5: Portfolio & Stock-Back Engine  | Weeks 10–12     | Ledger, Trading, Vesting, Core business logic |
| Phase 6: Event-Driven Architecture      | Weeks 13–15     | Kafka, Saga, Outbox, Event Sourcing           |
| Phase 7: Microservices Decomposition    | Weeks 16–18     | Eureka, Gateway, Config, Service split        |
| Phase 8: Security Hardening             | Weeks 19–20     | Keycloak, OAuth2, mTLS, OWASP                 |
| Phase 9: Observability                  | Weeks 21–22     | ELK, Prometheus, Grafana, Zipkin              |
| Phase 10: Advanced Features & Scale     | Weeks 23–26     | CQRS, Flash Sales, Load Testing               |
| **TOTAL**                               | **20–26 weeks** |                                               |

---

## Guiding Principles Throughout

1. **Design before code** — Every phase starts with architecture/design discussion
2. **Test everything** — Unit, Integration, Contract tests per phase
3. **Security at every layer** — Never bolt on security later
4. **Document decisions** — ADRs (Architecture Decision Records) for key choices
5. **Interview-ready** — Each phase ends with related interview topic review
6. **Incremental complexity** — Monolith → Events → Microservices (not all at once)
