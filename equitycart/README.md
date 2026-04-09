# EquityCart

A hybrid E-Commerce + Stock Market platform where users earn fractional stocks as shopping rewards, trade independently, and liquidate stocks to fund purchases.

## Core Concept

**Micro-Investing meets E-Commerce**: Users shop for products and earn "Stock-Back" rewards (fractional shares instead of cash discounts). They can also trade stocks manually and sell holdings to fund future purchases — creating a circular economy.

## Architecture

**Monolith-first** (Phases 0-6) evolving to **Microservices** (Phase 7+).

### Modules

| Module                | Package                     | Purpose                                           |
| --------------------- | --------------------------- | ------------------------------------------------- |
| `commons`             | `com.equitycart.commons`    | Shared DTOs, exceptions, constants                |
| `user-service`        | `com.equitycart.user`       | Authentication, authorization, user profiles, KYC |
| `order-service`       | `com.equitycart.order`      | Cart, orders, inventory, idempotency              |
| `portfolio-service`   | `com.equitycart.portfolio`  | Holdings, trading, stock-back rewards, vesting    |
| `market-data-service` | `com.equitycart.marketdata` | Real-time prices, brand-ticker mapping, WebFlux   |
| `ledger-service`      | `com.equitycart.ledger`     | Double-entry bookkeeping, wallet, audit trail     |
| `app`                 | `com.equitycart`            | Monolith aggregator (runs all modules as one JAR) |

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
├── user/                     (user-service module)
├── order/                    (order-service module)
├── portfolio/                (portfolio-service module)
├── market-data/              (market-data-service module)
└── ledger/                   (ledger-service module)
```

## Tech Stack

| Layer             | Technology                                    |
| ----------------- | --------------------------------------------- |
| Language          | Java 21 (LTS)                                 |
| Framework         | Spring Boot 3.5.8, Spring WebFlux             |
| Build             | Gradle 8.14.2 (Groovy DSL)                    |
| SQL Database      | PostgreSQL                                    |
| NoSQL Database    | MongoDB                                       |
| Cache             | Redis                                         |
| Message Broker    | Apache Kafka                                  |
| Security          | Spring Security + JWT (later Keycloak/OAuth2) |
| API Gateway       | Spring Cloud Gateway                          |
| Service Discovery | Netflix Eureka                                |
| Monitoring        | Prometheus + Grafana                          |
| Containerization  | Docker + Kubernetes                           |

## Key Features

### Consumer-Facing

- Stock-Back Reward System (fractional shares as discounts)
- Unified Wealth Account (shopping credits + investment portfolio)
- Independent Stock Trading (BUY/SELL any supported ticker)
- Asset Liquidation for Shopping ("Sell to Spend")
- Vesting Transparency (Pending/Vested status per reward)
- Brand-Contextual Insights (Company Health score)

### Technical

- Brand-to-Ticker Mapping Service
- Reactive Market Data Wrapper (WebFlux)
- Equity Calculation Engine
- Spring Batch Vesting Job (daily)
- Atomic Transaction Manager (Pay with Stock)
- Double-Entry Ledger System
- Event-Driven Order Pipeline (Kafka)
- Idempotency Logic (request-key deduplication)

## How to Build

```bash
# Build all modules
./gradlew build

# Run the monolith
./gradlew :app:bootRun

# Build fat JAR
./gradlew :app:bootJar
# JAR output: app/build/libs/equitycart-exec.jar
```

## Prerequisites

- JDK 21
- PostgreSQL (running on localhost:5432, database: equitycart)
- MongoDB (running on localhost:27017)

## Project Documents

- `equitycart-roadmap.md` — Full 10-phase, 26-week development roadmap
- `progress.md` — Current phase status and next steps
- `learning_log.md` — Roadblocks, concepts, and interview questions per phase
- `learning-instructor-agent.md` — Agent system prompt
- `project-development-prompt.md` — Project vision and roles

## Current Status

- **Phase 0**: Foundation & Setup — COMPLETE
- **Phase 1**: User Service & Security — IN PROGRESS (entity design done, implementation next)
