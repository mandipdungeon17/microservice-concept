# Context Sync Agent — Pre-Implementation Checklist

> **Purpose**: Before starting ANY implementation task, analyze these files systematically to understand project state, avoid context sync issues, and maintain consistency with prior decisions.
>
> **When to use**: At the start of every session OR before starting a new phase/step.
> **Time investment**: 5-10 minutes of analysis per session prevents hours of debugging.

---

## 📋 Files to Analyze (In Priority Order)

### 1. **progress.md** — Project State Tracking [ALWAYS FIRST]

- **What to check**:
  - Current phase (IN PROGRESS vs COMPLETE vs PENDING)
  - Which steps are done vs remaining
  - Last session's date and what was accomplished
  - Known issues or blockers from prior work
- **Why**: Tells you exactly where the project stands and what to do next
- **Time**: 2-3 minutes
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\progress.md`

### 2. **equitycart-roadmap.md** — Macro Requirements

- **What to check**:
  - Full 10-phase roadmap overview
  - This phase's deliverables (compare to progress.md to spot gaps)
  - Dependencies on prior phases (what MUST be done before this phase)
- **Why**: Prevents local optimization that breaks global requirements
- **Time**: 2-3 minutes
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\equitycart-roadmap.md`

### 3. **learning-instructor-agent.md** — Agent Responsibilities & Rules

- **What to check**:
  - Your role and constraints (what you MUST do, what you MUST NOT do)
  - Code ownership rules (agent writes only Javadoc/loggers/docs, student owns implementation)
  - Specification-only approach (never complete code blocks)
  - Documentation requirements (which files need Javadoc, logging consistency)
- **Why**: Ensures you don't violate learned workflow constraints
- **Time**: 1-2 minutes (re-skim sections relevant to current task)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\learning-instructor-agent.md`

### 4. **project-development-prompt.md** — Vision & Standards

- **What to check**:
  - Project vision and learning goals
  - Tech stack decisions and WHY (not just WHAT)
  - Code quality standards (logging, error handling, testing approach)
  - Architecture principles (microservices separation, patterns used)
- **Why**: Aligns suggestions with established project culture
- **Time**: 2-3 minutes (search for relevant sections)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\project-development-prompt.md`

### 5. **c:\Users\H504024\.claude\projects\c--Users-H504024-Documents-Docs-Mandip-Preparation-microservice-concept\memory\MEMORY.md** — Conversation Context

- **What to check**:
  - User feedback rules (what the user explicitly taught you)
  - Project status memories (ongoing initiatives, decisions made)
  - References to external systems (GitHub repos, Docker configs, etc.)
- **Why**: Avoids repeating mistakes the user already corrected
- **Time**: 2-3 minutes (review all entries, focus on feedback entries)
- **Location**: `.claude/projects/c--Users-.../memory/MEMORY.md`

### 6. **learning_log.md** — Phase-Specific Learnings

- **What to check**:
  - Roadblocks and issues FACED in this and prior phases
  - Concepts learned and why they matter
  - Common pitfalls to avoid
- **Why**: Learn from prior mistakes without repeating them
- **Time**: 2-3 minutes (search for current phase section)
- **Location**: `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\learning_log.md`

### 7. **Reference Documentation** (Microservices, Security, SpringBoot, Kafka, Docker)

- **What to check**:
  - microservice-patterns.md → architecture decisions, communication patterns (Outbox, Saga, Event Sourcing, API Gateway)
  - springboot-reference.md → Spring Boot version-specific gotchas, autoconfiguration, dependency management
  - security-reference.md → Security patterns, known vulnerabilities, auth flow
  - kafka-learning.md → Kafka concepts (topics, partitions, consumer groups, DLQ, serialization, outbox poller)
  - docker-learning.md → Docker + Docker Compose concepts, networking, multi-container orchestration
- **Why**: Prevents re-inventing the wheel or breaking established patterns
- **Time**: 1-2 minutes per file (search for relevant sections)
- **Locations**:
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\microservice-patterns.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\springboot-reference.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\security-reference.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\kafka-learning.md`
  - `c:\Users\H504024\Documents\Docs\Mandip\Preparation\microservice-concept\docker-learning.md`

### 8. **Project Structure Understanding** — Mental Model

- **What to check**:
  - Current directory structure: modules, main app, config repo, test artifacts
  - Which services exist, what ports they run on
  - Database schemas (PostgreSQL vs MongoDB vs Redis usage)
  - Infrastructure dependencies (Kafka, Eureka, Config Server, Docker containers)
  - Inter-service communication paths (Feign clients, Kafka topics)
- **Why**: Prevents proposing solutions that conflict with existing structure
- **Time**: 2-3 minutes (visual scan)
- **Key locations**:
  ```
  microservice-concept/
  ├── equitycart/                        # Main application modules
  │   ├── app/                           # Monolithic aggregator (DEPRECATED — legacy mode)
  │   ├── commons/                       # Shared DTOs, entities, exceptions, Feign clients, Kafka config
  │   │   ├── dto/                       # ErrorResponse, PagedResponse, ProductDTO, BrandTickerMappingDTO
  │   │   ├── entity/                    # BaseEntity (auditing)
  │   │   ├── event/                     # Kafka event DTOs (OrderDelivered, OrderReturned, OrderRefunded, SagaLifecycle, Notification)
  │   │   ├── exception/                 # Domain exceptions (ResourceNotFound, InsufficientStock, InsufficientShares, etc.)
  │   │   ├── feign/                     # ProductFeignClient, FeignErrorDecoder, FeignCorrelationInterceptor
  │   │   ├── filter/                    # MdcCorrelationFilter (servlet filter for correlation ID)
  │   │   ├── config/                    # KafkaConsumerConfig (shared trusted-packages)
  │   │   └── handler/                   # GlobalExceptionHandler (@ControllerAdvice)
  │   ├── user/                          # User service (port 8081)
  │   ├── product/                       # Product service (port 8089)
  │   ├── order/                         # Order service (port 8088)
  │   ├── portfolio/                     # Portfolio service (port 8084) — most complex module
  │   │   ├── controller/                # PortfolioController (6 endpoints)
  │   │   ├── dto/                       # Trade, Holding, SellToSpend, Analytics DTOs
  │   │   ├── entity/                    # Portfolio, Holding, StockBackReward
  │   │   ├── event/                     # NotificationPublisher (fire-and-forget Kafka)
  │   │   ├── eventsourcing/             # PortfolioEvent (MongoDB), EventStore, Projection, Controller
  │   │   ├── feign/                     # OrderFeignClient (portfolio-specific, avoids circular dep)
  │   │   ├── saga/                      # SellToSpendSagaOrchestrator, SagaEntity, SagaStatus, TimeoutDetector
  │   │   ├── repository/               # JPA repos for Portfolio, Holding, StockBackReward
  │   │   └── service/                   # PortfolioService, TradeService, SellToSpendService, VestingHelper, Facade
  │   ├── market-data/                   # Market data service (port 8085)
  │   ├── ledger/                        # Ledger service (port 8086)
  │   ├── notification/                  # Notification service (port 8087)
  │   │   ├── consumer/                  # NotificationConsumer (@KafkaListener)
  │   │   ├── entity/                    # NotificationLog (audit trail)
  │   │   ├── enums/                     # NotificationType, NotificationChannel, NotificationStatus
  │   │   ├── service/channel/           # Strategy Pattern: Email, Webhook, Log channel implementations
  │   │   └── service/impl/             # NotificationDispatcherImpl (routes to channel + persists)
  │   ├── discovery-server/              # Eureka (port 8761)
  │   ├── config-server/                 # Config Server (port 8888)
  │   ├── api-gateway/                   # Spring Cloud Gateway (port 8080)
  │   │   └── filter/                    # CorrelationIdGatewayFilter (GlobalFilter)
  │   └── docker/                        # Docker infrastructure
  │       ├── Dockerfile                 # Universal Dockerfile for all services
  │       ├── docker-pets.yml            # Infrastructure containers (Postgres, Kafka, Redis, Mongo, MailHog, Debezium)
  │       ├── docker-compose-services.yml # All 10 application services
  │       ├── build-images.sh            # Builds Docker images for all services
  │       ├── start-pets.sh              # Starts infrastructure with readiness polling
  │       ├── start-services.sh          # Starts app services (depends on infrastructure)
  │       └── init-db.sh                 # Creates 7 PostgreSQL databases
  ├── equitycart-config/                 # Git-backed config repo (Spring Cloud Config source)
  │   ├── application.yml                # Shared config (all services inherit)
  │   ├── api-gateway.yml                # Gateway routes (7 lb:// routes)
  │   ├── user-service.yml               # User service config
  │   ├── order-service.yml              # Order service config
  │   ├── portfolio-service.yml          # Portfolio service config
  │   ├── product-service.yml            # Product service config
  │   ├── market-data-service.yml        # Market data config
  │   ├── ledger-service.yml             # Ledger config
  │   └── notification-service.yml       # Notification config
  ├── progress.md                        # THIS SESSION'S START POINT
  ├── learning_log.md                    # Phase learnings (roadblocks, concepts, Q&A)
  ├── equitycart-roadmap.md              # Full 10-phase roadmap
  ├── microservice-patterns.md           # Outbox, Saga, Event Sourcing, API Gateway patterns
  ├── kafka-learning.md                  # Kafka deep-dive (topics, partitions, DLQ, serialization)
  ├── docker-learning.md                 # Docker concepts (images, containers, networking, compose)
  ├── springboot-reference.md            # Spring Boot concepts (autoconfiguration, profiles, etc.)
  ├── security-reference.md              # Security patterns (JWT, Spring Security)
  ├── java-reference.md                  # Java language concepts
  ├── test-commands.md                   # cURL commands for testing all phases
  └── context-sync-agent.md              # THIS FILE
  ```

---

## 🔍 Quick Context Sync Flowchart

```
START IMPLEMENTATION SESSION
    ↓
[1] Read progress.md — What is the current phase/step?
    ↓
[2] Read equitycart-roadmap.md — What SHOULD I deliver?
    ↓
[3] Read learning-instructor-agent.md — What are MY constraints?
    ↓
[4] Read project-development-prompt.md — What's the vision?
    ↓
[5] Skim MEMORY.md — What feedback should I apply?
    ↓
[6] Skim learning_log.md (current phase) — What pitfalls exist?
    ↓
[7] Skim relevant reference docs — What patterns are established?
    ↓
[8] Mental map of project structure — Can my solution work here?
    ↓
[9] Check inter-service communication map — Does my change affect other services?
    ↓
IF ALL CLEAR → Proceed with task
    OR
IF CONFUSED → Ask user for clarification
```

---

## 🗺️ Inter-Service Communication Map (Post Phase 7)

Understanding who calls whom and HOW is critical before making any change.

### Synchronous (OpenFeign via Eureka lb://)

| Caller | Callee | Feign Client | Purpose |
|--------|--------|--------------|---------|
| order-service | product-service | `ProductFeignClient` (commons) | Validate product, deduct stock |
| portfolio-service | product-service | `ProductFeignClient` (commons) | Get price for trade execution |
| portfolio-service | order-service | `OrderFeignClient` (portfolio) | Get order, confirm order (Sell-to-Spend) |

### Asynchronous (Kafka Topics)

| Topic | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `order-outbox-events` | Debezium CDC (order DB) | portfolio (StockBackRewardConsumer) | Order delivered → grant reward; Order returned → cancel reward |
| `portfolio-notification` | portfolio (NotificationPublisher) | notification (NotificationConsumer) | Trade/vest/saga events → dispatch notification |
| `saga-lifecycle-events` | portfolio (SagaOutboxWriter) | portfolio (SagaOrchestrator) | Saga step coordination |

### Still Coupled (Direct Gradle Import — Target: Feign in Phase 9+)

| Service A | Imports | Reason (Transitional) |
|-----------|---------|----------------------|
| portfolio | ledger-service | Calls LedgerService bean directly (not yet Feign) |
| portfolio | market-data-service | Calls MarketDataService bean directly (not yet Feign) |
| portfolio | order entities | EntityScan for OutboxEvent (outbox not yet moved to commons) |

### Gateway Routes (equitycart-config/api-gateway.yml)

| Route ID | Path Predicate | Target |
|----------|---------------|--------|
| user-service | `/api/auth/**`, `/api/users/**` | `lb://USER-SERVICE` |
| order-service | `/api/order/**`, `/api/cart/**` | `lb://ORDER-SERVICE` |
| portfolio-service | `/api/portfolio/**`, `/api/notifications/**` | `lb://PORTFOLIO-SERVICE` |
| product-service | `/api/products/**`, `/api/brands/**`, `/api/categories/**`, `/api/brand-ticker-mappings/**` | `lb://PRODUCT-SERVICE` |
| market-data-service | `/api/market-data/**` | `lb://MARKET-DATA-SERVICE` |
| ledger-service | `/api/ledger/**` | `lb://LEDGER-SERVICE` |
| notification-service | `/api/notifications/**` | `lb://NOTIFICATION-SERVICE` |

---

## 🏗️ Architecture Patterns in Use (Cumulative)

| Pattern | Where Used | Reference Doc Section |
|---------|-----------|----------------------|
| **Transactional Outbox** | order-service (OutboxEvent → Debezium → Kafka) | microservice-patterns.md §1 |
| **Saga (Orchestrator)** | portfolio-service (SellToSpendSagaOrchestrator) | microservice-patterns.md §2 |
| **Event Sourcing** | portfolio-service (PortfolioEventStore → MongoDB) | microservice-patterns.md §3 |
| **API Gateway** | api-gateway (Spring Cloud Gateway, lb://, GlobalFilter) | microservice-patterns.md §4 |
| **Observer via Kafka** | portfolio → notification (fire-and-forget) | kafka-learning.md |
| **Strategy Pattern** | notification-service (Email/Webhook/Log channels) | notification module |
| **Facade Pattern** | portfolio-service (PortfolioFacade) | portfolio module |
| **State Machine** | order-service (OrderStatus EnumSet transitions) | order module |
| **Circuit Breaker** | market-data-service (Resilience4j on AlphaVantageClient) | springboot-reference.md |
| **Cache-Aside** | product-service (@Cacheable), market-data (manual Redis) | springboot-reference.md |
| **Strangler Fig** | Overall migration strategy (monolith → microservices) | learning_log.md Phase 7 |
| **Correlation ID** | api-gateway filter → MDC filter → Feign interceptor | commons module |

---

## ⚠️ Known Architectural Debt (Phase 7 → Phase 8+ Resolution)

| Issue | Impact | Resolution Phase |
|-------|--------|-----------------|
| No per-service JWT validation | Services can't authenticate requests independently | Phase 8 |
| `SecurityContextHolder` returns null in standalone services | Cross-service userId extraction fails | Phase 8 |
| portfolio imports ledger/market-data Gradle modules | Cannot deploy independently | Phase 9+ (extract to Feign) |
| OutboxEvent entity lives in order module | portfolio needs EntityScan of order package | Move to commons |
| No distributed tracing (only correlation ID) | Can't visualize call chains | Phase 9 (Micrometer/Zipkin) |
| No rate limiting per service | DDoS risk at service level | Phase 8 |

---

## 🚨 Context Sync Red Flags

**Stop and ask the user if you notice**:

1. **progress.md doesn't mention this phase** → Task may be out of scope or future work
2. **equitycart-roadmap.md lists dependencies not yet COMPLETE** → Prerequisites missing
3. **MEMORY.md has a feedback rule contradicting your plan** → User taught you something different
4. **learning_log.md documents a known pitfall you're about to walk into** → Learn from history
5. **Reference docs show a different pattern than what you're proposing** → Breaking established patterns
6. **Current file structure doesn't match what you're implementing** → Architecture assumption wrong
7. **Two sessions ago you built X, but progress.md says it's PENDING** → Data integrity issue (ask user)

---

## 📝 Files to Create/Update When Work Is Done

After completing a phase/step, update **in this order**:

1. **progress.md** — Mark steps COMPLETE, record date, summarize what was done and issues faced
2. **learning_log.md** — Add roadblocks faced, concepts learned, lessons for next time
3. **Reference docs** (springboot-reference.md, microservice-patterns.md, etc.) — Add relevant learnings so others benefit
4. **Javadoc** in all new/modified Java files (per learning-instructor-agent.md rules)
5. **Logging statements** (use Log4j, not @Slf4j per project rules)
6. **MEMORY.md** (via memory files in .claude/projects/.../) — Record any user feedback or project decisions

---

## 💡 Pro Tips

- **Bookmark progress.md** — It's your north star. Check it 1st and last in every session.
- **Trust learning_log.md** — If it documents a roadblock, you're likely to hit it. Plan for it.
- **Use Grep** on .md files to search for keywords (e.g., "bootstrap", "Eureka", "YAML") rather than scrolling.
- **If context drifts**, re-read these 8 files in order. The answer will be in one of them.
- **When proposing fixes**, cite which document supports your approach (e.g., "per microservice-patterns.md, services communicate via OpenFeign").

---

## 📌 Last Updated

- **Date**: 2026-06-12
- **By**: Context Sync Agent
- **Triggered by**: Phase 7 completion — full inter-service map, architecture patterns catalog, known debt inventory added before Phase 8 begins
- **Phase completed**: Phase 7 (Microservices Decomposition) — COMPLETE
- **Next phase**: Phase 8 (Security Hardening — per-service JWT, OAuth2/Keycloak, rate limiting)
