# EquityCart

A hybrid E-Commerce + Stock Market platform where users earn fractional stocks as shopping rewards, trade independently, and liquidate stocks to fund purchases.

## Core Concept

**Micro-Investing meets E-Commerce**: Users shop for products and earn "Stock-Back" rewards (fractional shares instead of cash discounts). They can also trade stocks manually and sell holdings to fund future purchases — creating a circular economy.

## Architecture

**Monolith-first** (Phases 0-6) evolving to **Microservices** (Phase 7+).

### Modules

| Module                | Package                     | Purpose                                           | Status      |
| --------------------- | --------------------------- | ------------------------------------------------- | ----------- |
| `commons`             | `com.equitycart.commons`    | Shared DTOs, exceptions, events, config       | Implemented |
| `user-service`        | `com.equitycart.user`       | Authentication, authorization, user profiles, KYC | Implemented |
| `product-service`     | `com.equitycart.product`    | Product catalog, brands, categories, batch import | Implemented |
| `order-service`       | `com.equitycart.order`      | Cart, orders, inventory, idempotency              | Implemented |
| `portfolio-service`   | `com.equitycart.portfolio`  | Holdings, trading, stock-back rewards, vesting    | Implemented |
| `market-data-service` | `com.equitycart.marketdata` | Real-time prices, health scores, SSE streaming  | Implemented |
| `ledger-service`      | `com.equitycart.ledger`     | Double-entry bookkeeping, wallet, audit trail     | Implemented |
| `app`                 | `com.equitycart`            | Monolith aggregator (runs all modules as one JAR) | Implemented |

### Folder Structure

```
equitycart/
├── build.gradle              (root — plugins, subprojects config, BOM)
├── settings.gradle           (module declarations)
├── gradle.properties         (centralized versions)
├── gradlew / gradlew.bat     (Gradle wrapper)
├── app/                      (monolith entry point)
│   ├── build.gradle
│   └── src/main/java/com/equitycart/EquityCartApplication.java
├── commons/                  (shared library)
│   └── src/main/java/com/equitycart/commons/
│       ├── dto/              (ErrorResponse, ValidationErrorResponse, PagedResponse)
│       ├── entity/           (BaseEntity — auditing fields)
│       ├── exception/        (ResourceNotFoundException, DuplicateResourceException, etc.)
│       └── handler/          (GlobalExceptionHandler)
├── user/                     (user-service module)
│   └── src/main/java/com/equitycart/user/
│       ├── controller/       (AuthController, UserController)
│       ├── dto/              (request/response DTOs)
│       ├── entity/           (User, Role, UserProfile, KycDetail, RefreshToken, WalletAccount)
│       ├── enums/            (RoleName, KycStatus)
│       ├── repository/       (7 JPA repositories)
│       ├── security/         (JwtAuthFilter, SecurityConfig)
│       └── service/          (AuthService, JwtService, UserService + impls)
├── product/                  (product-service module)
│   └── src/main/java/com/equitycart/product/
│       ├── batch/            (ProductBatchConfig — Spring Batch job)
│       ├── cache/            (RedisCacheConfig — cache manager + serialization)
│       ├── controller/       (ProductController, BrandController, CategoryController, etc.)
│       ├── dto/              (request/response DTOs, ProductCsvRow, ProductSearchRequest)
│       ├── entity/           (Product, Brand, Category, BrandTickerMapping)
│       ├── repository/       (4 JPA repositories + JpaSpecificationExecutor)
│       ├── service/          (ProductService, BrandService, CategoryService + impls)
│       └── specification/    (ProductSpecification — dynamic query builder)
├── order/                    (order-service module)
│   └── src/main/java/com/equitycart/order/
│       ├── cart/
│       │   ├── controller/   (CartController — REST endpoints)
│       │   ├── dto/          (AddToCartRequest, CartItemResponse, CartResponse)
│       │   ├── repository/   (CartRedisRepository — Redis Hash operations)
│       │   └── service/      (CartService + CartServiceImpl)
│       ├── controller/       (OrderController — order lifecycle endpoints)
│       ├── dto/              (PlaceOrderRequest, OrderResponse, UpdateOrderStatusRequest)
│       ├── entity/           (Order, OrderItem)
│       ├── enums/            (OrderStatus — state machine with EnumSet transitions)
│       ├── repository/       (OrderRepository, OrderItemRepository)
│       └── service/          (OrderService + OrderServiceImpl)
├── portfolio/                (portfolio-service module)
│   └── src/main/java/com/equitycart/portfolio/
│       ├── controller/       (PortfolioController — 6 endpoints)
│       ├── dto/              (HoldingRequest/Response, TradeRequest/Response, SellToSpendRequest/Response, PortfolioAnalyticsResponse)
│       ├── entity/           (Portfolio, Holding, StockBackReward)
│       ├── enums/            (VestingStatus, TradeType)
│       ├── repository/       (PortfolioRepository, HoldingRepository, StockBackRewardRepository)
│       └── service/          (PortfolioService, PortfolioFacade, TradeService, SellToSpendService, VestingHelper + impls)
├── market-data/              (market-data-service module)
│   └── src/main/java/com/equitycart/marketdata/
│       ├── client/           (AlphaVantageClient — reactive WebClient + Resilience4j)
│       ├── config/           (WebClientConfig — Reactor Netty timeouts)
│       ├── controller/       (MarketDataController — 6 endpoints + SSE)
│       ├── dto/              (StockQuote, StockPriceResponse, HealthScoreResponse)
│       ├── entity/           (PriceHistory — MongoDB document)
│       ├── repository/       (PriceHistoryRepository — MongoRepository)
│       └── service/          (MarketDataService + MarketDataServiceImpl)
└── ledger/                   (ledger-service module)
    └── src/main/java/com/equitycart/ledger/
        ├── entity/           (LedgerEntry)
        ├── enums/            (AccountType, EntryType, ReferenceType)
        ├── repository/       (LedgerEntryRepository)
        └── service/          (LedgerService + LedgerServiceImpl)
```

## Tech Stack

| Layer             | Technology                                    |
| ----------------- | --------------------------------------------- |
| Language          | Java 21 (LTS)                                 |
| Framework         | Spring Boot 3.5.8                             |
| Batch Processing  | Spring Batch (chunk-oriented CSV import)      |
| Build             | Gradle 8.14.2 (Groovy DSL)                    |
| Code Formatting   | Spotless (Google Java Format)                 |
| Resilience        | Resilience4j (Circuit Breaker, Retry, Rate Limiter) |
| Reactive Client   | WebClient + Reactor Netty (non-blocking HTTP) |
| SQL Database      | PostgreSQL                                    |
| NoSQL Database    | MongoDB (price history, TTL indexes)      |
| Cache             | Redis (@Cacheable + RedisTemplate + manual opsForValue) |
| Message Broker    | Apache Kafka (KRaft mode, event-driven rewards) |
| Security          | Spring Security + JWT (later Keycloak/OAuth2) |
| API Gateway       | Spring Cloud Gateway (planned)                |
| Service Discovery | Netflix Eureka (planned)                      |
| Monitoring        | Prometheus + Grafana (planned)                |
| Containerization  | Docker + Kubernetes (planned)                 |
| API Docs          | SpringDoc OpenAPI (planned)                   |
| Testing           | JUnit 5, Mockito, Testcontainers (planned)    |

## Implemented Features

### Phase 1 — User Service & Security

- **User Registration & Login** with JWT (access + refresh tokens)
- **Role-Based Access Control** (ADMIN, SELLER, CUSTOMER) via `@PreAuthorize`
- **Spring Security Filter Chain** with stateless session + JWT validation
- **Refresh Token** rotation and revocation (logout)
- **KYC Entity** and user profile management
- **Data Seeder** for initial admin account on startup
- **Bean Validation** on all request DTOs
- **Global Exception Handling** with structured error responses (404, 409, 401, 403, 400, 500)

### Phase 2 — Product Catalog & Batch Import

- **Product CRUD** — create, read, update, delete with RBAC (ADMIN/SELLER only for writes)
- **Brand Management** — CRUD for brands
- **Category Management** — hierarchical categories with self-referential parent-child
- **Brand-Ticker Mapping** — links brands to stock market tickers
- **Product Search & Filter** — dynamic queries via JPA Specifications (name, brand, category, price range, active status)
- **Pagination** — `PagedResponse<T>` generic wrapper with page metadata
- **Bulk Product Import** — CSV upload via Spring Batch (chunk size 50, FlatFileItemReader + RepositoryItemWriter)
- **Redis Caching** — @Cacheable on reads, @CacheEvict on writes, 10-min TTL, JSON serialization
- **Javadoc** — documentation on all classes and public methods
- **Logging** — Log4j2 loggers across all modules

### Phase 3 — Order Service & Cart

- **Shopping Cart (Redis-backed)** — add/remove/get/clear items using Redis Hash (per-user key, product fields, 30-min TTL)
- **Cart SecurityContext** — userId extracted from JWT token (no path variable exposure)
- **Order Placement** — pessimistic locking on product stock, cart-to-order conversion, stock decrement
- **Idempotency** — client-generated idempotency key prevents duplicate orders on retry
- **Order Status State Machine** — 9 states with EnumSet transition rules, `canTransition()` validation
- **Admin Status Transitions** — PATCH endpoint with ADMIN-only access for order lifecycle progression
- **Return/Refund Flow** — customer-initiated return request, ownership validation, stock restoration on RETURNED
- **Javadoc + Logging** — Log4j2 loggers + Javadoc across all Order and Cart classes

### Phase 4 — Market Data Service (Reactive)

- **Real-Time Stock Prices** — WebClient + Alpha Vantage API (non-blocking I/O via Reactor Netty)
- **Redis Price Cache** — manual cache-aside pattern with StringRedisTemplate (opsForValue, 30s TTL, atomic SET+expire)
- **MongoDB Price History** — PriceHistory documents saved asynchronously (CompletableFuture.runAsync) on cache miss, TTL index auto-deletes after 90 days
- **Resilience4j** — @CircuitBreaker (3 states: CLOSED/OPEN/HALF-OPEN), @Retry (3 attempts, 2s wait), @RateLimiter (5 calls/60s) on Alpha Vantage client
- **Company Health Score** — composite score (0-100) from 4 signals: price change, change percent, weekly trend (MongoDB), volume
- **SSE Live Streaming** — Server-Sent Events via Flux + concatMap, distinctUntilChanged suppresses duplicate prices
- **Batch Price Lookup** — multi-symbol price query in single request
- **Cache Management** — ADMIN-only cache eviction endpoint
- **Javadoc + Logging** — Log4j2 loggers + Javadoc across all market-data classes

### Phase 5 — Portfolio & Stock-Back Engine

- **Portfolio Management** — per-user portfolio with holdings (one-to-many, cascade + orphanRemoval)
- **Holdings** — fractional share support (BigDecimal scale=6), weighted-average buy price recalculation on add, composite unique constraint (portfolio + ticker)
- **Manual Trading (BUY/SELL)** — TradeService with double-entry ledger recording (DEBIT HOLDING_ASSET / CREDIT CASH on BUY, reverse on SELL)
- **Optimistic Locking** — @Version on holdings with retry loop (3 attempts) for concurrent trade safety
- **Stock-Back Rewards** — StockBackReward entity with PENDING/VESTED/CANCELLED lifecycle, 30-day vesting delay, idempotent granting (one reward per order)
- **Vesting Scheduler** — @Scheduled job (60s interval) queries PENDING rewards past vesting date, delegates to VestingHelper (@Transactional(REQUIRES_NEW) for per-reward isolation)
- **Sell to Spend** — cross-domain atomic transaction: sell shares → record ledger → confirm order, all in one @Transactional
- **Portfolio Analytics** — cost basis breakdown, per-holding portfolio weight (%), aggregated reward statistics (pending/vested counts, total shares, total dollar value)
- **Facade Pattern** — PortfolioFacade maps between controller DTOs and service entities, composes multi-service data for analytics
- **Javadoc + Logging** — Log4j2 loggers + Javadoc across all portfolio and ledger classes

### Phase 5 — Ledger Service

- **Double-Entry Bookkeeping** — every financial event creates balanced DEBIT + CREDIT pairs with shared transactionId
- **Account Types** — WALLET, STOCK, REWARD, PLATFORM for categorizing ledger entries
- **Audit Trail** — immutable ledger entries provide complete financial history (sum of debits = sum of credits)

### Phase 6 — Event-Driven Architecture (Kafka)

- **Transactional Outbox Pattern** — atomic DB write + async Kafka delivery, eliminates dual-write data loss
- **Order Delivered → Stock-Back Reward** — Kafka event triggers fractional share reward calculation per brand/ticker
- **Order Returned → Reward Cancellation** — cancels PENDING rewards, logs warning for already-VESTED
- **Multi-Ticker Rewards** — composite unique constraint (orderId + tickerSymbol) allows multiple brand rewards per order
- **Dead Letter Queue (DLQ)** — 3 retries with 1s backoff, then divert poison messages to .DLT topic
- **Generic Outbox Poller** — re-hydrates JSON payload via Class.forName(FQCN), handles any event type without code changes
- **Consumer Group Isolation** — separate group IDs for reward granting vs cancellation (independent offset tracking)
- **Javadoc + Logging** — Log4j2 loggers + Javadoc across all Kafka classes

## API Endpoints

### Authentication

| Method | Endpoint             | Access | Description             |
| ------ | -------------------- | ------ | ----------------------- |
| POST   | `/api/auth/register` | Public | Register new user       |
| POST   | `/api/auth/login`    | Public | Login, returns JWT pair |
| POST   | `/api/auth/refresh`  | Public | Refresh access token    |
| POST   | `/api/auth/logout`   | Auth   | Revoke refresh token    |

### Cart

| Method | Endpoint                    | Access | Description              |
| ------ | --------------------------- | ------ | ------------------------ |
| POST   | `/api/cart/items`           | Auth   | Add item to cart         |
| GET    | `/api/cart`                 | Auth   | Get current user's cart  |
| DELETE | `/api/cart/items/{productId}` | Auth | Remove item from cart    |
| DELETE | `/api/cart`                 | Auth   | Clear entire cart        |

### Orders

| Method | Endpoint                     | Access | Description                          |
| ------ | ---------------------------- | ------ | ------------------------------------ |
| POST   | `/api/order`                 | Auth   | Place order from cart                |
| GET    | `/api/order`                 | Auth   | Get all orders for current user      |
| GET    | `/api/order/{orderId}`       | Auth   | Get order by ID                      |
| PATCH  | `/api/order/{orderId}/status`| ADMIN  | Update order status (state machine)  |
| PATCH  | `/api/order/{orderId}/return`| Auth   | Request return (owner only)          |

### Products

| Method | Endpoint               | Access       | Description                   |
| ------ | ---------------------- | ------------ | ----------------------------- |
| GET    | `/api/products`        | Auth         | Search/filter with pagination |
| GET    | `/api/products/{id}`   | Auth         | Get product by ID             |
| POST   | `/api/products`        | ADMIN/SELLER | Create product                |
| PUT    | `/api/products/{id}`   | ADMIN/SELLER | Update product                |
| DELETE | `/api/products/{id}`   | ADMIN        | Delete product                |
| POST   | `/api/products/import` | ADMIN        | Bulk import from CSV          |

### Brands

| Method | Endpoint           | Access | Description     |
| ------ | ------------------ | ------ | --------------- |
| GET    | `/api/brands`      | Auth   | List all brands |
| GET    | `/api/brands/{id}` | Auth   | Get brand by ID |
| POST   | `/api/brands`      | ADMIN  | Create brand    |
| PUT    | `/api/brands/{id}` | ADMIN  | Update brand    |
| DELETE | `/api/brands/{id}` | ADMIN  | Delete brand    |

### Categories

| Method | Endpoint               | Access | Description         |
| ------ | ---------------------- | ------ | ------------------- |
| GET    | `/api/categories`      | Auth   | List all categories |
| GET    | `/api/categories/{id}` | Auth   | Get category by ID  |
| POST   | `/api/categories`      | ADMIN  | Create category     |
| PUT    | `/api/categories/{id}` | ADMIN  | Update category     |
| DELETE | `/api/categories/{id}` | ADMIN  | Delete category     |

### Brand-Ticker Mappings

| Method | Endpoint                          | Access | Description       |
| ------ | --------------------------------- | ------ | ----------------- |
| GET    | `/api/brand-ticker-mappings`      | Auth   | List all mappings |
| POST   | `/api/brand-ticker-mappings`      | ADMIN  | Create mapping    |
| DELETE | `/api/brand-ticker-mappings/{id}` | ADMIN  | Delete mapping    |

### Market Data

| Method | Endpoint                               | Access | Description                          |
| ------ | -------------------------------------- | ------ | ------------------------------------ |
| GET    | `/api/market-data/price/{symbol}`      | Auth   | Get current stock price              |
| GET    | `/api/market-data/prices?symbols=A,B`  | Auth   | Batch price lookup                   |
| GET    | `/api/market-data/history/{symbol}`    | Auth   | Historical prices (default 7 days)   |
| GET    | `/api/market-data/health/{symbol}`     | Auth   | Company health score (0-100)         |
| GET    | `/api/market-data/stream/{symbol}`     | Auth   | SSE live price stream (5s interval)  |
| DELETE | `/api/market-data/price/{symbol}/cache`| ADMIN  | Evict price cache                    |

### Portfolio

| Method | Endpoint                      | Access | Description                                |
| ------ | ----------------------------- | ------ | ------------------------------------------ |
| GET    | `/api/portfolio`              | Auth   | Get user's portfolio with all holdings     |
| POST   | `/api/portfolio/holdings`     | Auth   | Add or update a holding                    |
| POST   | `/api/portfolio/trade`        | Auth   | Execute BUY or SELL trade                  |
| POST   | `/api/portfolio/sell-to-spend` | Auth  | Sell stock to fund a pending order         |
| GET    | `/api/portfolio/rewards`      | Auth   | Get stock-back reward history              |
| GET    | `/api/portfolio/analytics`    | Auth   | Portfolio analytics (cost basis, weights)  |

## How to Build & Run

```bash
# Build all modules
./gradlew build

# Run the monolith
./gradlew :app:bootRun

# Build fat JAR
./gradlew :app:bootJar
# JAR output: app/build/libs/equitycart-exec.jar

# Format code with Spotless
./gradlew spotlessApply

# Check formatting without fixing
./gradlew spotlessCheck
```

## Prerequisites

- JDK 21
- PostgreSQL (running on localhost:5432, database: equitycart)
- Redis (running on localhost:6379 — via Docker: `docker run -d --name redis -p 6379:6379 redis`)
- MongoDB (running on localhost:27017 — via Docker: `docker run -d --name mongodb -p 27017:27017 mongo`)
- Apache Kafka (running on localhost:9092 — via Docker: `docker run -d --name kafka -p 9092:9092 apache/kafka:latest`)

## Configuration

Key application properties (`app/src/main/resources/application.yml`):

| Property                              | Description                               |
| ------------------------------------- | ----------------------------------------- |
| `spring.datasource.url`               | PostgreSQL connection URL                 |
| `spring.jpa.hibernate.ddl-auto`       | Schema generation strategy (update)       |
| `jwt.secret`                          | HMAC secret for signing JWT tokens        |
| `jwt.access-token-expiry`             | Access token TTL in milliseconds          |
| `jwt.refresh-token-expiry`            | Refresh token TTL in milliseconds         |
| `spring.batch.jdbc.initialize-schema` | Auto-create Spring Batch metadata tables  |
| `spring.batch.job.enabled`            | Disable auto-run of batch jobs on startup |
| `spring.data.redis.host`              | Redis server hostname (default: localhost)|
| `spring.data.redis.port`              | Redis server port (default: 6379)         |
| `spring.cache.type`                   | Cache provider (redis)                    |
| `spring.cache.redis.time-to-live`     | Default TTL for cache entries (ms)        |
| `spring.data.mongodb.uri`             | MongoDB connection URI                    |
| `alphavantage.base-url`               | Alpha Vantage API base URL                |
| `alphaVantage.api-key`                | Alpha Vantage API key (env var)           |

| `spring.kafka.bootstrap-servers`      | Kafka broker address (default: localhost:9092) |
| `spring.kafka.consumer.group-id`      | Default consumer group ID                     |

## Project Documents

| File                            | Purpose                                           |
| ------------------------------- | ------------------------------------------------- |
| `equitycart-roadmap.md`         | Full 10-phase, 20-26 week development roadmap     |
| `progress.md`                   | Current phase status, steps completed, next steps |
| `learning_log.md`               | Roadblocks, concepts learned, and interview Q&A   |
| `kafka-learning.md`             | Deep-dive Kafka concepts (topics, partitions, serialization, DLQ) |
| `microservice-patterns.md`      | Microservice patterns (Outbox, Saga, Circuit Breaker) |
| `test-commands.md`              | Consolidated curl test commands for all phases    |
| `learning-instructor-agent.md`  | Agent system prompt and teaching methodology      |
| `project-development-prompt.md` | Project vision, roles, and requirements           |

## Current Status

| Phase   | Name                           | Status                                       |
| ------- | ------------------------------ | -------------------------------------------- |
| Phase 0 | Foundation & Setup             | COMPLETE                                     |
| Phase 1 | User Service & Security        | COMPLETE (unit tests deferred)               |
| Phase 2 | Product Catalog & Batch Import | COMPLETE (unit tests deferred)               |
| Phase 3 | Order Service & Cart           | COMPLETE (unit tests deferred)               |
| Phase 4 | Market Data Service (Reactive) | COMPLETE (unit tests deferred)               |
| Phase 5 | Portfolio & Stock-Back Engine  | COMPLETE (reward grant deferred to Phase 6)  |
| Phase 6 | Event-Driven Architecture     | FUNCTIONAL COMPLETE (e2e testing pending)    |

## Known Issues

- **403 instead of 401** for unauthenticated requests — needs custom `AuthenticationEntryPoint` (planned fix)

## Roadmap Ahead

- **Phase 7**: Microservices Decomposition (Eureka, Gateway, Config Server)
- **Phase 8**: Security Hardening (OAuth2/Keycloak, rate limiting)
- **Phase 9**: Observability (Prometheus, Grafana, distributed tracing)
- **Phase 10**: Advanced Features & Scale (Kubernetes, CI/CD, load testing)
