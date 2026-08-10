# Pattern Implementation Mapping — EquityCart Code References

> Exact classes, files, and code snippets showing how design patterns appear in the live codebase.  
> Use these for interview explanations with concrete examples.

---

## 1. Facade Pattern (Phase 1 — Auth Orchestration)

### What the pattern does
A facade provides a **unified, simplified interface** over a complex subsystem. The caller makes one method call; the facade internally coordinates multiple components.

### File location
`equitycart/user/src/main/java/com/equitycart/user/service/impl/AuthServiceImpl.java`

### The facade methods

The AuthServiceImpl class wraps three core operations:

**1. register(RegisterRequest)** - Coordinates:
- Email validation (UserRepository)
- Password encoding (PasswordEncoder)
- User persistence (UserRepository)
- Role assignment (RoleRepository + UserRoleRepository)
- Wallet creation (WalletAccountRepository)
- Token generation (JwtService)

**2. login(LoginRequest)** - Coordinates:
- User lookup (UserRepository)
- Password verification (PasswordEncoder)
- Status validation (enabled, not locked)
- Role retrieval (UserRoleRepository)
- Token generation (JwtService)

**3. refreshToken(RefreshRequest)** - Coordinates:
- Token validation (RefreshTokenRepository)
- Expiry check
- User lookup
- Old token revocation
- New token generation

### Why this is Facade
- Single entry point (register, login, refresh)
- Coordinates multiple repos/services (User, UserRole, WalletAccount, RefreshToken, Jwt)
- Handles complex workflow (validation → persistence → token generation)
- Caller sees clean API (one method call returns a response)

### Interview angle
"We used Facade to keep auth complexity hidden. The controller calls `authService.register(request)` and doesn't know about password encoding, role assignment, wallet creation, or JWT. The service handles all coordination internally. This keeps the controller thin and reusable."

---

## 2. Template Method Pattern (Phase 2 — Spring Batch CSV Import)

### What the pattern does
A framework defines the **algorithm skeleton**; you customize specific steps.  
The parent class/framework provides the structure; subclasses/configurations provide the details.

### File location
`equitycart/product/src/main/java/com/equitycart/product/batch/ProductBatchConfig.java`

### The Template Method (Spring Batch provides the skeleton)

Spring Batch defines the SKELETON:
1. Read chunk from file
2. Process each item
3. Write batch to DB
4. Commit transaction
5. Handle errors

**We plug in custom implementations:**

**STEP 1: Reader** (`productCsvReader`)
- Reads CSV file line by line
- Skips header row
- Maps columns to ProductCsvRow DTO
- Spring Batch calls: `item = reader.read()`

**STEP 2: Processor** (`productProcessor`)
- Validates ProductCsvRow
- Looks up Brand and Category
- Transforms to Product entity
- Spring Batch calls: `processed = processor.process(item)`

**STEP 3: Writer** (`productWriter`)
- Saves Product entities to database
- Uses ProductRepository
- Spring Batch calls: `writer.write(chunk)`

**STEP 4: Step** (`productImportStep`)
- Configures chunk size (50 items)
- Defines transaction boundary
- Spring Batch orchestrates the loop

**JOB** (`productImportJob`)
- Chains the step
- Spring Batch executes the job

### The algorithm skeleton (Spring Batch internal)
```
while ((item = reader.read()) != null) {
  processed = processor.process(item)
  chunk.add(processed)
  
  if (chunk.size() >= 50) {
    writer.write(chunk)
    transactionManager.commit()
    chunk.clear()
  }
}
```

### Why this is Template Method
- Skeleton is fixed (Spring Batch framework)
- Algorithm steps are customizable (reader/processor/writer)
- You don't control the loop (framework does)
- You don't handle commit/retry (framework does)

### Interview angle
"Spring Batch is a Template Method pattern in action. The framework defines 'read-process-write-commit' as the skeleton. We plug in our CSV reader, transformation processor, and repository writer. Spring Batch handles retry logic, transaction boundaries, and error scenarios. We just implement the business-specific parts."

---

## 3. Domain Service Pattern (Phase 5 — Trade + Portfolio Logic)

### What the pattern does
Business rules that **transcend single entities** are implemented in domain-focused services.  
Entities hold state; services enforce cross-entity invariants and workflows.

### File location
`equitycart/portfolio/src/main/java/com/equitycart/portfolio/service/impl/TradeServiceImpl.java`

### The Domain Service

**TradeServiceImpl** implements trade execution, which is NOT a simple CRUD operation.

**It orchestrates across multiple domains:**

1. **Portfolio domain** - `PortfolioService.addOrUpdateHolding()` or `reduceHolding()`
   - Acquires or disposes shares
   - Updates Holding entity

2. **Ledger domain** - `LedgerService.recordTransaction()`
   - Records double-entry bookkeeping
   - BUY: DEBIT HOLDING_ASSET, CREDIT CASH
   - SELL: DEBIT CASH, CREDIT HOLDING_ASSET

3. **Event sourcing domain** - `PortfolioEventStore.append()`
   - Appends immutable trade event
   - Maintains audit trail
   - Enables event replay

4. **Metrics domain** - `PortfolioMetrics.recordTrade()`
   - Records "BUY" or "SELL" trades
   - Feeds observability pipeline

5. **Notification domain** - `NotificationPublisher.publishAsync()`
   - Notifies user of trade execution
   - Asynchronous, non-blocking

### The executeTrade() method

**For BUY:**
- Validate trade type
- Update holding (Portfolio)
- Record ledger transaction (Ledger)
- Append event (Event Store)
- Record metric (Observability)
- Publish notification (Notifications)
- All atomic: @Transactional

**For SELL:**
- Same steps, with adjusted direction
- Ledger: CASH → HOLDING_ASSET instead

### Why this is Domain Service

| Aspect | What it is | Why it matters |
|---|---|---|
| **Coordinates** | Multiple domain services (portfolio, ledger, event, metrics) | No single entity "owns" the trade |
| **Enforces rules** | Trade amount = price × qty; double-entry must balance | Business logic, not data access |
| **Single transaction** | All steps succeed or all rollback | Atomic across domains |
| **Not in entity** | Trade logic doesn't live in Holding class | Entities should be dumb; services are smart |
| **Not a CRUD layer** | Not just reading/writing Holding rows | Orchestrates a business process |

### Interview angle
"A Domain Service is where complex business workflows live that involve multiple entities or bounded contexts. Trade execution isn't just saving a Holding row; it's coordinating portfolio updates, ledger double-entry bookkeeping, event recording, metrics, and notifications all in one atomic transaction. That's why we created TradeServiceImpl as a dedicated domain service rather than embedding that logic in Holding or PortfolioService."

---

## 4. Strangler Fig Pattern (Phase 7 — Microservice Extraction)

### What the pattern does
**Incrementally replace** a monolith by creating new microservices "around" the old system.  
Traffic is gradually redirected to the new services while the old system remains operational.  
Once all functionality is replaced, the old system is decommissioned.

### File location
`equitycart/user/src/main/java/com/equitycart/user/UserServiceApplication.java`
+ Gateway routing configuration
+ Config Server integration

### How Strangler Fig was applied in EquityCart

**Phase 1-6: Monolithic**
```
Browser
  ↓
localhost:8080 (EquityCartApplication.java)
  ├── User service logic
  ├── Product service logic
  ├── Order service logic
  ├── Portfolio service logic
  └── Notification service logic
```

**Phase 7 Early: Strangler begins**
```
Browser
  ↓
API Gateway (port 8080)
  ├── /api/auth/** → lb://user-service (NEW microservice)
  ├── /api/users/** → lb://user-service (NEW microservice)
  ├── /api/products/** → lb://product-service (NEW microservice)
  └── /api/order/** → lb://monolith:8082 (OLD — not yet migrated)
  
Eureka Registry:
  ├── USER-SERVICE (8081) — running standalone
  ├── PRODUCT-SERVICE (8089) — running standalone
  └── APP (8082) — monolith still running (legacy)
```

**Phase 7 End: All migrated**
```
Browser
  ↓
API Gateway (port 8080)
  ├── /api/auth/** → lb://user-service (8081)
  ├── /api/users/** → lb://user-service (8081)
  ├── /api/products/** → lb://product-service (8089)
  ├── /api/order/** → lb://order-service (8088)
  ├── /api/portfolio/** → lb://portfolio-service (8084)
  ├── /api/notifications/** → lb://notification-service (8087)
  ├── /api/ledger/** → lb://ledger-service (8086)
  └── /api/market-data/** → lb://market-data-service (8085)
  
Eureka Registry:
  ├── USER-SERVICE (8081) — independent
  ├── PRODUCT-SERVICE (8089) — independent
  ├── ORDER-SERVICE (8088) — independent
  ├── PORTFOLIO-SERVICE (8084) — independent
  └── [More services...]
  
APP (8082) — Monolith DECOMMISSIONED
```

### UserServiceApplication — the extracted service

```java
@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(UserServiceApplication.class, args);
  }
}
```

This demonstrates the Strangler Fig pattern:
1. User-Service was originally part of the monolith
2. We extracted it as a new standalone service
3. Gateway now routes /api/auth, /api/users to this service
4. Config Server provides service-specific config (db, jwt, eureka)
5. Eureka discovers and registers it
6. Old monolith still runs but is bypassed for user operations
7. Eventually, monolith is shut down (all functions migrated)

### Key benefits (why Strangler Fig vs Big Bang Rewrite)

| Aspect | Strangler Fig | Big Bang Rewrite |
|---|---|---|
| **Risk** | Low — old system still running | High — everything new at once |
| **Rollback** | Redirect traffic back to old service | Total disaster, revert entire rewrite |
| **Testing** | Test new service alongside old | Must test everything simultaneously |
| **Timeline** | Weeks/months of gradual migration | Months of development, one release date |
| **Team** | Some work on new services, some maintain old | Entire team rebuilds from scratch |

### Interview angle
"We used Strangler Fig to decompose a monolith safely. Instead of a risky big-bang rewrite, we extracted services incrementally: User-Service first, then Product, then Order, etc. The gateway routed requests to either the new service (if ready) or the old monolith (if not yet extracted). Once all services were extracted, the monolith was shut down. This let us validate each service independently, minimize risk, and keep the system running throughout."

---

## Summary Table

| Pattern | Location | Purpose | Key Components |
|---|---|---|---|
| **Facade** | `AuthServiceImpl` | Simplify complex auth workflow | UserRepository, RoleRepository, WalletRepository, PasswordEncoder, JwtService |
| **Template Method** | `ProductBatchConfig` | Skeleton provided by Spring Batch | Reader (CSV), Processor (Transform), Writer (DB) |
| **Domain Service** | `TradeServiceImpl` | Coordinate multi-domain trade logic | PortfolioService, LedgerService, EventStore, Metrics, Notifications |
| **Strangler Fig** | `UserServiceApplication` + Gateway | Incremental monolith extraction | Eureka, Gateway, Config Server, independent services |

---

## How to explain in an interview

**Facade:**  
"We centralized auth complexity in `AuthServiceImpl`. The controller calls one method (`register`, `login`, `refresh`), and the service internally handles password encoding, role assignment, wallet creation, and token generation. The caller doesn't know about these details — they see one clean API."

**Template Method:**  
"Spring Batch defines the 'read-process-write' algorithm skeleton. We provided the details: a CSV reader, a row-to-entity processor, and a repository writer. Spring Batch controlled retry logic, transactions, and chunking. We only implemented the business-specific parts."

**Domain Service:**  
"Trade execution isn't just updating a Holding row. It spans multiple domains: portfolio (update shares), ledger (record bookkeeping), events (append to event store), metrics (record trade), and notifications (notify user). We created `TradeServiceImpl` as a domain service to coordinate all these steps atomically."

**Strangler Fig:**  
"We didn't rewrite the system overnight. Instead, we extracted services incrementally. The gateway redirected requests to new services as they were ready, while the old monolith handled the rest. Once all services were extracted, the monolith was decommissioned. This gave us low-risk, continuous delivery throughout the migration."
