# EquityCart

A hybrid E-Commerce + Stock Market platform where users earn fractional stocks as shopping rewards, trade independently, and liquidate stocks to fund purchases.

## Core Concept

**Micro-Investing meets E-Commerce**: Users shop for products and earn "Stock-Back" rewards (fractional shares instead of cash discounts). They can also trade stocks manually and sell holdings to fund future purchases — creating a circular economy.

## Architecture

**Monolith-first** (Phases 0-6) evolving to **Microservices** (Phase 7+).

### Modules

| Module                | Package                     | Purpose                                           | Status      |
| --------------------- | --------------------------- | ------------------------------------------------- | ----------- |
| `commons`             | `com.equitycart.commons`    | Shared DTOs, exceptions, constants                | Implemented |
| `user-service`        | `com.equitycart.user`       | Authentication, authorization, user profiles, KYC | Implemented |
| `product-service`     | `com.equitycart.product`    | Product catalog, brands, categories, batch import | Implemented |
| `order-service`       | `com.equitycart.order`      | Cart, orders, inventory, idempotency              | Planned     |
| `portfolio-service`   | `com.equitycart.portfolio`  | Holdings, trading, stock-back rewards, vesting    | Planned     |
| `market-data-service` | `com.equitycart.marketdata` | Real-time prices, brand-ticker mapping, WebFlux   | Planned     |
| `ledger-service`      | `com.equitycart.ledger`     | Double-entry bookkeeping, wallet, audit trail     | Planned     |
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
├── order/                    (order-service module — planned)
├── portfolio/                (portfolio-service module — planned)
├── market-data/              (market-data-service module — planned)
└── ledger/                   (ledger-service module — planned)
```

## Tech Stack

| Layer             | Technology                                    |
| ----------------- | --------------------------------------------- |
| Language          | Java 21 (LTS)                                 |
| Framework         | Spring Boot 3.5.8                             |
| Batch Processing  | Spring Batch (chunk-oriented CSV import)      |
| Build             | Gradle 8.14.2 (Groovy DSL)                    |
| Code Formatting   | Spotless (Google Java Format)                 |
| SQL Database      | PostgreSQL                                    |
| NoSQL Database    | MongoDB (planned)                             |
| Cache             | Redis (@Cacheable, TTL-based eviction)        |
| Message Broker    | Apache Kafka (planned)                        |
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

## API Endpoints

### Authentication

| Method | Endpoint             | Access | Description             |
| ------ | -------------------- | ------ | ----------------------- |
| POST   | `/api/auth/register` | Public | Register new user       |
| POST   | `/api/auth/login`    | Public | Login, returns JWT pair |
| POST   | `/api/auth/refresh`  | Public | Refresh access token    |
| POST   | `/api/auth/logout`   | Auth   | Revoke refresh token    |

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

## Project Documents

| File                            | Purpose                                           |
| ------------------------------- | ------------------------------------------------- |
| `equitycart-roadmap.md`         | Full 10-phase, 20-26 week development roadmap     |
| `progress.md`                   | Current phase status, steps completed, next steps |
| `learning_log.md`               | Roadblocks, concepts learned, and interview Q&A   |
| `learning-instructor-agent.md`  | Agent system prompt and teaching methodology      |
| `project-development-prompt.md` | Project vision, roles, and requirements           |

## Current Status

| Phase   | Name                           | Status                                       |
| ------- | ------------------------------ | -------------------------------------------- |
| Phase 0 | Foundation & Setup             | COMPLETE                                     |
| Phase 1 | User Service & Security        | COMPLETE (unit tests deferred)               |
| Phase 2 | Product Catalog & Batch Import | COMPLETE (unit tests deferred)               |
| Phase 3 | Order Service & Cart           | Next                                         |

## Known Issues

- **403 instead of 401** for unauthenticated requests — needs custom `AuthenticationEntryPoint` (planned fix)

## Roadmap Ahead

- **Phase 3**: Order Service & Cart (Cart entity, checkout flow, inventory management, idempotency)
- **Phase 4**: Market Data Service (WebFlux reactive, external stock API integration)
- **Phase 5**: Portfolio Service & Stock-Back Engine (holdings, trading, vesting)
- **Phase 6**: Event-Driven Architecture (Kafka, async order pipeline)
- **Phase 7**: Microservices Decomposition (Eureka, Gateway, Config Server)
- **Phase 8**: Security Hardening (OAuth2/Keycloak, rate limiting)
- **Phase 9**: Observability (Prometheus, Grafana, distributed tracing)
- **Phase 10**: Advanced Features & Scale (Kubernetes, CI/CD, load testing)
