# Spring Boot Internals — Deep Dive Reference

> How Spring Boot works under the hood: auto-configuration, proxy mechanism, @Transactional, @Async, @Cacheable,
> bean lifecycle, dependency injection, and more. Each section is written in "debug mode" — tracing the internal
> execution path as if stepping through with a debugger.

---

## 1. Spring Boot Auto-Configuration — How "Magic" Actually Works

### 1.1 The Problem Auto-Configuration Solves

Before Spring Boot (plain Spring Framework, 2004–2013), configuring a web application required:

- XML configuration files (applicationContext.xml) or @Configuration classes for EVERY bean
- Manual DataSource, EntityManagerFactory, TransactionManager setup
- Manual DispatcherServlet registration
- Manual view resolver, message converter, exception handler setup
- 200+ lines of configuration before writing business code

Spring Boot (2014, Pivotal/Phil Webb) introduced **convention over configuration**: if you have `spring-boot-starter-data-jpa` on the classpath AND `postgresql` driver AND `spring.datasource.url` in properties → Spring Boot auto-creates DataSource, EntityManagerFactory, TransactionManager FOR you.

### 1.2 Debug Mode: What Happens When You Run `EquityCartApplication.main()`

```
STEP 1: main() → SpringApplication.run(EquityCartApplication.class, args)
─────────────────────────────────────────────────────────────────────────────
  Creates a SpringApplication instance:
  - Detects web application type (SERVLET, REACTIVE, NONE)
    → Checks classpath for DispatcherServlet → SERVLET
  - Loads META-INF/spring.factories (Spring Boot 2.x) or
    META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (3.x)
    → Discovers ALL auto-configuration classes on classpath (~150 classes)
  - Creates ApplicationContext (AnnotationConfigServletWebServerApplicationContext)

STEP 2: Environment Preparation
─────────────────────────────────────────────────────────────────────────────
  Property sources loaded (in priority order — later overrides earlier):
  1. Command-line args (--server.port=9090)
  2. System environment variables (SPRING_DATASOURCE_URL)
  3. application.yml / application.properties
  4. @PropertySource annotations
  5. Default properties (SpringApplication.setDefaultProperties)

  Profile resolution:
  - spring.profiles.active=cdc → loads application-cdc.yml overlay
  - Activates/deactivates @Profile beans

STEP 3: Bean Definition Registration (Component Scanning)
─────────────────────────────────────────────────────────────────────────────
  @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan

  @ComponentScan scans from the package of the main class downward:
    com.equitycart → scans ALL sub-packages
    Finds: @Component, @Service, @Repository, @Controller, @Configuration
    Each found class → registered as a BeanDefinition (blueprint, not instance yet)

  EquityCart scans: com.equitycart.user.*, com.equitycart.portfolio.*, etc.
  (all modules because they're on the same classpath in the monolith)

STEP 4: Auto-Configuration Processing
─────────────────────────────────────────────────────────────────────────────
  @EnableAutoConfiguration triggers AutoConfigurationImportSelector:
  - Reads all auto-configuration class names from imports file
  - Applies CONDITIONAL FILTERING (this is the key insight):

    @ConditionalOnClass(DataSource.class)         → Is DataSource on classpath? YES (spring-data-jpa)
    @ConditionalOnProperty("spring.datasource.url") → Is property defined? YES
    @ConditionalOnMissingBean(DataSource.class)    → Did user define their own? NO
    → DataSourceAutoConfiguration APPLIES: creates HikariDataSource bean

    @ConditionalOnClass(KafkaTemplate.class)       → Is spring-kafka on classpath? YES
    @ConditionalOnProperty("spring.kafka.bootstrap-servers") → YES
    → KafkaAutoConfiguration APPLIES: creates KafkaTemplate, ConsumerFactory, etc.

    @ConditionalOnClass(RedisConnectionFactory.class) → YES (spring-data-redis)
    → RedisAutoConfiguration APPLIES: creates LettuceConnectionFactory

  Each auto-config class is a @Configuration that creates beans ONLY if conditions pass.

STEP 5: Bean Instantiation + Dependency Injection
─────────────────────────────────────────────────────────────────────────────
  Spring determines instantiation order via dependency graph:
  - TradeServiceImpl needs LedgerService → create LedgerServiceImpl first
  - LedgerServiceImpl needs LedgerEntryRepository → create proxy repo first
  - Repository proxy needs EntityManagerFactory → create that first
  - EntityManagerFactory needs DataSource → create HikariDataSource first

  For each bean:
  1. Invoke constructor (with injected dependencies)
  2. Set @Value fields (from Environment)
  3. Call @PostConstruct methods
  4. Apply BeanPostProcessors (this is where AOP proxies are created!)

STEP 6: Web Server Startup
─────────────────────────────────────────────────────────────────────────────
  EmbeddedTomcat started on port 8080 (configured via server.port)
  DispatcherServlet registered at "/"
  All @RequestMapping methods indexed for URL routing

  Application ready in ~5 seconds.
```

### 1.3 Best Practices

- DO: Rely on auto-configuration unless you need custom behavior
- DO: Use `@ConditionalOnProperty` for feature toggles (e.g., saga vs transactional strategy)
- DON'T: Override auto-configured beans unless you understand what you're replacing
- DON'T: Add starters you don't use — each one activates auto-configuration

---

## 2. The CGLIB Proxy Mechanism — Foundation of Spring's "Magic"

### 2.1 Why Proxies Exist

Annotations like `@Transactional`, `@Cacheable`, `@Async`, `@Retry`, `@CircuitBreaker` need to execute code BEFORE and AFTER your method. But Java doesn't support method interception natively. Solution: **proxies**.

### 2.2 How Spring Creates Proxies

```
STEP 1: During Bean Instantiation (Step 5 above)
─────────────────────────────────────────────────────────────────────────────
  BeanPostProcessor "AnnotationAwareAspectJAutoProxyCreator" scans each bean:
  → Does TradeServiceImpl have any AOP-relevant annotations? (@Transactional? @Cacheable?)
  → YES → Need to create a proxy

STEP 2: CGLIB Proxy Generation
─────────────────────────────────────────────────────────────────────────────
  Spring uses CGLIB (Code Generation Library) to create a SUBCLASS at runtime:

  // What Spring generates (conceptually):
  class TradeServiceImpl$$SpringCGLIB$$0 extends TradeServiceImpl {
      private MethodInterceptor[] interceptors; // transaction, cache, etc.

      @Override
      public Holding executeTrade(Long userId, TradeRequest request) {
          // Before: start transaction
          interceptors[0].intercept(this, method, args, methodProxy);
          // Inside intercept(): call super.executeTrade() (the real method)
          // After: commit or rollback transaction
      }
  }

  The proxy IS-A TradeServiceImpl (subclass), so it can be injected wherever
  TradeServiceImpl is expected. But it adds behavior around each method call.

STEP 3: Bean Registration
─────────────────────────────────────────────────────────────────────────────
  Spring registers the PROXY (not the original object) in the ApplicationContext.
  Any bean that @Autowires TradeServiceImpl gets the proxy.

  PortfolioFacadeImpl → constructor(TradeService tradeService)
                         ↓ receives
                      TradeServiceImpl$$SpringCGLIB$$0 (the proxy)
```

### 2.3 The Self-Invocation Trap

```
@Service
@Transactional
public class MyService {
    public void methodA() {
        this.methodB();  // ← BYPASSES the proxy! No @Transactional on methodB call
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void methodB() { ... }
}
```

**Why:** `this` refers to the raw object inside the proxy. The proxy only intercepts EXTERNAL calls (from other beans). Internal `this.method()` calls go directly to the real object.

**Solution (EquityCart):** Extract `methodB` into a separate `@Service` bean. External bean → calls go through the proxy.

- `VestingHelperImpl` is separate from `PortfolioServiceImpl` for exactly this reason
- `vestSingleReward()` needs `REQUIRES_NEW` but is called from `vestPendingRewards()`

### 2.4 CGLIB vs JDK Dynamic Proxy

|                     | CGLIB Proxy                                     | JDK Dynamic Proxy                |
| ------------------- | ----------------------------------------------- | -------------------------------- |
| Mechanism           | Generates subclass                              | Implements interface             |
| Requirement         | Class cannot be `final`                         | Bean must implement an interface |
| Spring Boot default | YES (since Spring Boot 2.0)                     | Only if explicitly configured    |
| Performance         | Slightly slower creation, same invocation speed | —                                |

**Best Practice:** Spring Boot defaults to CGLIB for all beans. Don't mark `@Service` classes as `final` — the proxy can't subclass them.

---

## 3. @Transactional — Complete Debug-Mode Walkthrough

### 3.1 What @Transactional Actually Does

`@Transactional` is NOT a database feature. It's a Spring AOP proxy that wraps your method in a try-finally block that manages the EntityManager/Connection lifecycle.

### 3.2 Debug Mode: A BUY Trade in TradeServiceImpl

```
STEP 1: PortfolioFacade calls tradeService.executeTrade(userId, request)
─────────────────────────────────────────────────────────────────────────────
  tradeService is actually TradeServiceImpl$$SpringCGLIB$$0 (the proxy)

STEP 2: Proxy intercepts the call → TransactionInterceptor.invoke()
─────────────────────────────────────────────────────────────────────────────
  TransactionInterceptor reads @Transactional annotation metadata:
  - propagation = REQUIRED (default)
  - isolation = DEFAULT (use DB default: READ_COMMITTED for PostgreSQL)
  - readOnly = false
  - rollbackFor = RuntimeException.class (default)
  - timeout = -1 (no timeout)

STEP 3: TransactionManager.getTransaction()
─────────────────────────────────────────────────────────────────────────────
  JpaTransactionManager:
  1. Check: is there an existing transaction in ThreadLocal? (TransactionSynchronizationManager)
     - REQUIRED: if yes → join it (participate). If no → create new.
     - REQUIRES_NEW: always create new (suspend existing if present)
  2. No existing transaction → create new:
     a. Get Connection from HikariDataSource (HikariCP connection pool)
     b. connection.setAutoCommit(false)  ← THIS is what "begins" the transaction
     c. Bind Connection to current thread (ThreadLocal)
     d. Create EntityManager, bind to thread

STEP 4: Actual method execution — super.executeTrade(userId, request)
─────────────────────────────────────────────────────────────────────────────
  Now your business code runs:
  - holdingRepository.findByPortfolioAndTickerSymbol(...)
    → Uses the SAME Connection/EntityManager bound to this thread
    → Query executes within the transaction
  - holding.setQuantity(newQty)
    → Hibernate dirty-checks: field changed, marks entity as dirty
  - ledgerService.recordEntry(...)
    → LedgerServiceImpl is also @Transactional(REQUIRED)
    → TransactionInterceptor checks: existing transaction? YES → JOIN (no new Connection)
    → Ledger write uses SAME Connection/transaction
  - notificationPublisher.publish(event)
    → Kafka send (fire-and-forget, outside DB transaction — no effect on commit/rollback)

STEP 5: Method returns normally → Proxy commits
─────────────────────────────────────────────────────────────────────────────
  TransactionInterceptor catches no exception:
  1. EntityManager.flush()
     → Hibernate writes all dirty entities to DB:
       UPDATE holding SET quantity = ?, average_buy_price = ?, version = version+1 WHERE id = ? AND version = ?
       INSERT INTO ledger_entry (...)
     → If optimistic lock fails here (version mismatch) → OptimisticLockException thrown
  2. connection.commit()
     → PostgreSQL commits the transaction
     → All changes visible to other transactions
  3. Release Connection back to HikariCP pool
  4. Unbind EntityManager from thread

STEP 5-ALT: Method throws RuntimeException → Proxy rolls back
─────────────────────────────────────────────────────────────────────────────
  TransactionInterceptor catches exception:
  1. Check: should we rollback? (RuntimeException → YES by default)
  2. connection.rollback()
     → PostgreSQL discards all changes since setAutoCommit(false)
     → Holding quantity unchanged, ledger entry discarded
  3. Release Connection, unbind EntityManager
  4. Re-throw the exception (propagates to controller → GlobalExceptionHandler)
```

### 3.3 Propagation Levels Explained

| Propagation        | Behavior                                    | EquityCart Usage                                                 |
| ------------------ | ------------------------------------------- | ---------------------------------------------------------------- |
| REQUIRED (default) | Join existing TX or create new              | All service methods                                              |
| REQUIRES_NEW       | Suspend existing, create new independent TX | VestingHelper.vestSingleReward()                                 |
| SUPPORTS           | Join if exists, otherwise run without TX    | Read-only queries that might be called from TX or non-TX context |
| NOT_SUPPORTED      | Suspend existing TX, run without            | Audit logging that must succeed even if parent rolls back        |
| MANDATORY          | Must have existing TX, throw if not         | Methods that should never be called standalone                   |
| NEVER              | Must NOT have existing TX, throw if present | —                                                                |
| NESTED             | Create savepoint within existing TX         | Not supported by JPA/Hibernate (JDBC savepoints only)            |

### 3.4 readOnly = true — What It Actually Does

```
@Transactional(readOnly = true)
public Portfolio getPortfolio(Long userId) { ... }
```

1. **Hibernate level:** Sets FlushMode to MANUAL → no dirty checking at end of method → no accidental writes
2. **Connection level:** Hint to JDBC driver/DB → PostgreSQL may route to a read replica (if configured)
3. **Performance:** Skip dirty-check loop over all managed entities (significant for large result sets)

### 3.5 Best Practices

- DO: Place `@Transactional` at the SERVICE layer (not controller, not repository)
- DO: Use `readOnly = true` for queries (performance + safety)
- DO: Use `REQUIRES_NEW` when you need independent commit (per-item processing, audit logging)
- DON'T: Put `@Transactional` on private methods (proxy can't intercept them)
- DON'T: Catch exceptions inside @Transactional and swallow them — the proxy won't know to rollback
- DON'T: Do network calls (HTTP, Kafka) inside a transaction — they hold the DB connection open
- DON'T: Use `@Transactional` on the same class as the caller (self-invocation trap)

---

## 4. @Cacheable — Complete Debug-Mode Walkthrough

### 4.1 How @Cacheable Works Internally

```
STEP 1: ProductController calls productService.getProductById(42L)
─────────────────────────────────────────────────────────────────────────────
  productService is ProductServiceImpl$$SpringCGLIB$$0 (proxy)

STEP 2: Proxy intercepts → CacheInterceptor.invoke()
─────────────────────────────────────────────────────────────────────────────
  Reads @Cacheable annotation:
    @Cacheable(value = "product", key = "#productId")

  Resolves:
  - Cache name: "product"
  - Key: SpEL expression "#productId" → evaluates to 42L → String "42"
  - Cache manager: RedisCacheManager (auto-configured)

STEP 3: Cache Lookup
─────────────────────────────────────────────────────────────────────────────
  RedisCacheManager.getCache("product")
    → Returns RedisCacheConfiguration with:
       - TTL: 10 minutes (from application.yml)
       - Serializer: GenericJackson2JsonRedisSerializer (JSON in Redis)
       - Key prefix: "product::"

  cache.get("product::42")
    → RedisTemplate.opsForValue().get("product::42")
    → Lettuce client sends: GET product::42
    → Redis responds:
       CACHE HIT: returns JSON bytes → deserialize to Product object → return to caller
       CACHE MISS: returns null → proceed to step 4

STEP 4 (MISS only): Execute actual method
─────────────────────────────────────────────────────────────────────────────
  super.getProductById(42L)
    → JPA query: SELECT * FROM product WHERE id = 42
    → Returns Product entity

STEP 5 (MISS only): Store result in cache
─────────────────────────────────────────────────────────────────────────────
  cache.put("product::42", product)
    → GenericJackson2JsonRedisSerializer serializes Product → JSON:
      {"@class":"com.equitycart.product.entity.Product","id":42,"name":"iPhone",...}
    → RedisTemplate sends: SET product::42 <json> EX 600
      (EX 600 = expire in 600 seconds = 10 minutes)

STEP 6: Return result to caller
```

### 4.2 @CacheEvict — When Data Changes

```
@CacheEvict(value = "products", allEntries = true)
public Product createProduct(ProductRequest request) { ... }
```

When a new product is created:

1. Proxy intercepts createProduct()
2. Executes the actual method (INSERT INTO product)
3. After method returns → evicts ALL entries from "products" cache
   → RedisTemplate sends: DEL product::\* (all keys with prefix)
4. Next getProducts() call → MISS → fresh DB query → re-cache

### 4.3 @CachePut — Update Without Eviction

```
@CachePut(value = "product", key = "#productId")
public Product updateProduct(Long productId, ProductRequest request) { ... }
```

Unlike @Cacheable: ALWAYS executes the method, then stores the result in cache. Used when you want to update the cached value with the new data (avoid stale read on next access).

### 4.4 Cache Serialization — Why GenericJackson2JsonRedisSerializer

| Serializer                                | Stored Format       | Pros                       | Cons                                                                 |
| ----------------------------------------- | ------------------- | -------------------------- | -------------------------------------------------------------------- |
| JdkSerializationRedisSerializer (default) | Java binary         | Zero config                | Unreadable in Redis CLI, class version changes break deserialization |
| GenericJackson2JsonRedisSerializer        | JSON with @class    | Human-readable, debuggable | Slightly larger payload                                              |
| Jackson2JsonRedisSerializer               | JSON without @class | Compact                    | Must specify type at read time                                       |

**EquityCart uses Generic** because:

- You can `redis-cli GET product::42` and read the JSON directly
- Survives minor class changes (field additions don't break old cache entries)
- `@class` field enables polymorphic deserialization (knows which class to instantiate)

### 4.5 Best Practices

- DO: Cache at the service layer, not repository (service applies business logic + transformations)
- DO: Use meaningful cache names ("product", "products") not generic ("cache1")
- DO: Always pair @Cacheable reads with @CacheEvict on writes (prevent stale data)
- DO: Set TTL (time-to-live) — eventual consistency is fine, infinite stale data is not
- DON'T: Cache mutable objects that callers might modify (return defensive copies or use immutable types)
- DON'T: Cache results that change every call (defeats purpose)
- DON'T: Use @Cacheable on void methods (nothing to cache)
- DON'T: Forget that caching crosses transaction boundaries — a rollback doesn't un-cache

---

## 5. @Async — Complete Debug-Mode Walkthrough

### 5.1 How @Async Works

`@Async` moves method execution to a separate thread pool. The caller returns immediately (with void or CompletableFuture).

```
STEP 1: Some service calls asyncMethod()
─────────────────────────────────────────────────────────────────────────────
  The target bean is proxied (same CGLIB mechanism).

STEP 2: Proxy intercepts → AsyncExecutionInterceptor
─────────────────────────────────────────────────────────────────────────────
  1. Gets the configured TaskExecutor (thread pool)
     - Default: SimpleAsyncTaskExecutor (creates new thread per task — UNBOUNDED!)
     - Custom: ThreadPoolTaskExecutor with bounded queue + core/max threads
  2. Wraps the actual method call in a Callable
  3. Submits the Callable to the TaskExecutor
  4. Returns immediately to the caller:
     - void return → nothing returned
     - CompletableFuture<T> → returns incomplete future (completed when task finishes)

STEP 3: Thread pool executes the task (asynchronously)
─────────────────────────────────────────────────────────────────────────────
  A worker thread picks up the Callable:
  - Executes the real method
  - If method throws → exception is LOST (void return) or propagated to Future

  IMPORTANT: The async thread has NO access to:
  - The caller's SecurityContext (different thread → different ThreadLocal)
  - The caller's @Transactional connection (transaction is thread-bound)
  - The caller's MDC logging context (MDC uses ThreadLocal)
```

### 5.2 Configuration Required

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);       // 5 threads always alive
        executor.setMaxPoolSize(10);       // Scale up to 10 under load
        executor.setQueueCapacity(50);     // Queue 50 tasks before rejecting
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // If queue full, caller runs it
        return executor;
    }
}
```

### 5.3 EquityCart Usage

```java
// Event Store writes are async (fire-and-forget, non-critical)
CompletableFuture.runAsync(() -> portfolioEventStore.append(event));
```

This isn't `@Async` annotation — it uses `CompletableFuture.runAsync()` directly with the ForkJoinPool.commonPool(). Same concept: offload non-critical work to avoid blocking the main transaction thread.

### 5.4 Best Practices

- DO: Always configure a bounded ThreadPoolTaskExecutor (never use unbounded default)
- DO: Return `CompletableFuture<T>` if the caller needs the result
- DO: Propagate SecurityContext manually if the async task needs auth info
- DON'T: Use `@Async` on private methods (proxy can't intercept)
- DON'T: Call `@Async` methods from within the same class (self-invocation trap)
- DON'T: Use `@Async` inside `@Transactional` — the transaction won't cover the async work
- DON'T: Assume exceptions are visible — void @Async swallows them silently (configure AsyncUncaughtExceptionHandler)

---

## 6. @Scheduled — Periodic Task Execution

### 6.1 How @Scheduled Works Internally

```
STEP 1: On startup, ScheduledAnnotationBeanPostProcessor scans all beans
─────────────────────────────────────────────────────────────────────────────
  Finds methods annotated with @Scheduled:
  - VestingHelper.vestPendingRewards() → fixedDelay = 60000
  - SagaTimeoutDetector.detectTimedOutSagas() → fixedRate = 30000
  - OutboxPoller.pollAndPublish() → fixedRate = 5000

STEP 2: Creates ScheduledFuture for each
─────────────────────────────────────────────────────────────────────────────
  Uses a ScheduledThreadPoolExecutor (default: 1 thread!)

  fixedDelay=60000: wait 60s AFTER previous execution completes, then run again
  fixedRate=30000: run every 30s regardless of previous execution duration
  cron="0 0 2 * * ?": run at 2:00 AM daily (cron expression)

STEP 3: On each tick
─────────────────────────────────────────────────────────────────────────────
  The scheduler thread invokes the method (through the proxy if annotations present)
  → For vestPendingRewards: goes through @Transactional proxy → gets DB connection → queries
```

### 6.2 fixedDelay vs fixedRate

```
fixedRate = 5000:
  Run 1 starts at T+0, takes 3s → Run 2 starts at T+5s → Run 3 at T+10s
  Run 1 starts at T+0, takes 8s → Run 2 starts at T+8s (missed T+5) → Run 3 at T+10s
  ^ If execution exceeds interval, runs STACK UP

fixedDelay = 5000:
  Run 1 starts at T+0, takes 3s → Run 2 starts at T+8s → Run 3 at T+16s
  Run 1 starts at T+0, takes 8s → Run 2 starts at T+13s → Run 3 at T+21s
  ^ Always waits 5s AFTER completion. No stacking.
```

### 6.3 Best Practices

- DO: Use `fixedDelay` for jobs that shouldn't overlap (database polls)
- DO: Configure `@EnableScheduling` + `spring.task.scheduling.pool.size=3` for multiple concurrent jobs
- DON'T: Use `fixedRate` for long-running jobs (they'll pile up)
- DON'T: Rely on `@Scheduled` in distributed environments (all instances run it). Use distributed locks (ShedLock) or leader election.

---

## 7. Spring Data JPA — How Repository Interfaces Become Implementations

### 7.1 Debug Mode: Repository Proxy Creation

```
STEP 1: Component scan finds HoldingRepository interface
─────────────────────────────────────────────────────────────────────────────
  @EnableJpaRepositories (included in Spring Boot auto-config) triggers
  JpaRepositoriesRegistrar:
  - Scans for interfaces extending JpaRepository<T, ID>
  - For each: registers a BeanDefinition with JpaRepositoryFactoryBean

STEP 2: JpaRepositoryFactoryBean creates the implementation
─────────────────────────────────────────────────────────────────────────────
  Uses JDK Dynamic Proxy (not CGLIB — it's an interface):

  The proxy implements HoldingRepository and routes method calls to:
  - Built-in methods (save, findById, delete) → SimpleJpaRepository (concrete class)
  - Derived query methods (findByPortfolioAndTickerSymbol) → Query derivation engine
  - @Query methods → JPQL/native SQL execution

STEP 3: Derived Query Method Resolution
─────────────────────────────────────────────────────────────────────────────
  findByPortfolioAndTickerSymbol(Portfolio portfolio, String tickerSymbol)

  Spring Data parses the method name:
  - "findBy" → SELECT query
  - "Portfolio" → property "portfolio" on Holding entity
  - "And" → AND conjunction
  - "TickerSymbol" → property "tickerSymbol" on Holding entity

  Generates JPQL:
    SELECT h FROM Holding h WHERE h.portfolio = ?1 AND h.tickerSymbol = ?2

  Compiled to SQL:
    SELECT * FROM holding WHERE portfolio_id = ? AND ticker_symbol = ?

STEP 4: At runtime — method call on proxy
─────────────────────────────────────────────────────────────────────────────
  holdingRepository.findByPortfolioAndTickerSymbol(portfolio, "AAPL")
  → Proxy's InvocationHandler receives the call
  → Looks up the pre-compiled Query for this method name
  → Binds parameters (?1 = portfolio.id, ?2 = "AAPL")
  → Executes via EntityManager.createQuery().getResultList()
  → Wraps result in Optional<Holding>
```

### 7.2 Best Practices

- DO: Use derived queries for simple lookups (findByUserId, findByStatus)
- DO: Use `@Query` for complex queries (joins, subqueries, aggregations)
- DON'T: Create methods with 4+ conditions in the name (unreadable → use @Query or Specifications)
- DON'T: Return entities directly to controllers (use DTOs — prevents lazy-load exceptions and data leakage)

---

## 8. Bean Lifecycle — Creation to Destruction

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ SPRING BEAN LIFECYCLE (complete)                                              │
│                                                                              │
│ 1. Instantiation (constructor call)                                          │
│    → Dependencies injected via constructor (@RequiredArgsConstructor)         │
│                                                                              │
│ 2. Property Population                                                       │
│    → @Value fields resolved from Environment                                 │
│    → @Autowired field injection (if used)                                    │
│                                                                              │
│ 3. BeanPostProcessor.postProcessBeforeInitialization()                        │
│    → @PostConstruct methods called here                                      │
│    → CommonAnnotationBeanPostProcessor handles @PostConstruct                │
│                                                                              │
│ 4. InitializingBean.afterPropertiesSet() (if implemented)                    │
│                                                                              │
│ 5. BeanPostProcessor.postProcessAfterInitialization()                         │
│    → AOP PROXIES CREATED HERE                                                │
│    → AbstractAutoProxyCreator wraps bean in CGLIB proxy if annotations found │
│    → After this step, the bean in the context IS the proxy                   │
│                                                                              │
│ 6. Bean is ready (registered in ApplicationContext, injectable)               │
│                                                                              │
│ ... application runs ...                                                      │
│                                                                              │
│ 7. @PreDestroy methods called (ApplicationContext closing)                    │
│ 8. DisposableBean.destroy() (if implemented)                                 │
│ 9. Bean garbage collected                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key insight for AOP:** Proxies are created in step 5 (AFTER construction + initialization). This means `@PostConstruct` methods run on the RAW object (not the proxy). If you call a @Transactional method from @PostConstruct, the transaction annotation is NOT active yet.

### 8.1 Best Practices

- DO: Use constructor injection (immutable, testable, fails fast if dependency missing)
- DO: Use @PostConstruct for one-time initialization that needs injected dependencies
- DON'T: Use @Autowired field injection in production code (harder to test, hides dependencies)
- DON'T: Call proxied methods (transactional, cached) from @PostConstruct (proxy not ready)

---

## 9. @Value and Property Resolution

### 9.1 How @Value Works Internally

```
@Value("${equitycart.notification.channel}")
String activeChannel;
```

1. During bean property population (lifecycle step 2)
2. `AutowiredAnnotationBeanPostProcessor` processes @Value
3. SpEL (Spring Expression Language) evaluates `${equitycart.notification.channel}`
4. `PropertySourcesPlaceholderConfigurer` resolves from Environment:
   - Checks system properties → not found
   - Checks environment variables → not found
   - Checks application.yml → found: "LOG"
5. Sets field value to "LOG"

**If not found:** Throws `BeanCreationException: Could not resolve placeholder`. App won't start.

**Default values:**

```
@Value("${equitycart.notification.sender-email:noreply@equitycart.local}")
String senderEmail;  // "noreply@equitycart.local" if property not defined
```

### 9.2 Best Practices

- DO: Provide defaults for non-critical properties (`:defaultValue` syntax)
- DO: Use `@ConfigurationProperties` for groups of related properties (type-safe, validated)
- DON'T: Use @Value for complex objects (lists, maps) — use @ConfigurationProperties
- DON'T: Hardcode values that differ between environments — always externalize

---

## 10. Exception Handling with @RestControllerAdvice

### 10.1 Debug Mode: How an Exception Becomes an HTTP Response

```
STEP 1: Service throws ResourceNotFoundException("User not found")
─────────────────────────────────────────────────────────────────────────────
  Exception propagates up through:
  - Service method → proxy → Facade → Controller method

STEP 2: DispatcherServlet catches the unhandled exception
─────────────────────────────────────────────────────────────────────────────
  HandlerExceptionResolverComposite iterates through resolvers:
  1. ExceptionHandlerExceptionResolver → checks for @ExceptionHandler methods

STEP 3: Finds matching handler in GlobalExceptionHandler
─────────────────────────────────────────────────────────────────────────────
  @RestControllerAdvice
  public class GlobalExceptionHandler {
      @ExceptionHandler(ResourceNotFoundException.class)
      @ResponseStatus(HttpStatus.NOT_FOUND)
      public ErrorResponse handle(ResourceNotFoundException ex) {
          return new ErrorResponse(404, ex.getMessage());
      }
  }

  Match: ResourceNotFoundException → handle() method
  - Invokes handler method
  - Serializes ErrorResponse to JSON
  - Sets HTTP status 404
  - Returns response to client

STEP 4: Client receives
─────────────────────────────────────────────────────────────────────────────
  HTTP/1.1 404 Not Found
  Content-Type: application/json

  {"status": 404, "message": "User not found"}
```

### 10.2 Exception Handler Priority

```
1. @ExceptionHandler in the SAME @Controller (highest priority)
2. @ExceptionHandler in @RestControllerAdvice (global)
3. If multiple @RestControllerAdvice, @Order determines priority
4. Spring's default handlers (last resort):
   - MethodArgumentNotValidException → 400
   - HttpRequestMethodNotSupportedException → 405
   - NoHandlerFoundException → 404
```

### 10.3 Best Practices

- DO: Create domain-specific exceptions (ResourceNotFoundException, InsufficientSharesException)
- DO: Handle at ONE level (@RestControllerAdvice), not in every service method
- DO: Return consistent error response shape across all endpoints
- DON'T: Catch and re-throw just to change the message — let it propagate
- DON'T: Expose stack traces or internal details in production error responses
- DON'T: Use checked exceptions for business errors in Spring (they don't trigger @Transactional rollback by default)

---

## 11. Spring Kafka Internals — Consumer Lifecycle

### 11.1 Debug Mode: From Topic Message to @KafkaListener Method

```
STEP 1: KafkaListenerAnnotationBeanPostProcessor scans beans at startup
─────────────────────────────────────────────────────────────────────────────
  Finds @KafkaListener on NotificationConsumer.handleNotificationEvent()
  Registers a MessageListenerContainer for this listener:
  - topic: "portfolio-notification"
  - groupId: "equitycart-notification-group"
  - Deserializer: JsonDeserializer<NotificationEvent>

STEP 2: Container creates a KafkaConsumer and starts a dedicated thread
─────────────────────────────────────────────────────────────────────────────
  The container's thread runs a poll loop:
  while (!stopped) {
      ConsumerRecords<K,V> records = consumer.poll(Duration.ofMillis(5000));
      for (ConsumerRecord<K,V> record : records) {
          invoke listener method with record.value();
      }
      consumer.commitSync(); // or commitAsync() depending on AckMode
  }

STEP 3: Deserialization (inside poll())
─────────────────────────────────────────────────────────────────────────────
  Kafka broker sends raw bytes to consumer.
  JsonDeserializer:
  1. Reads __TypeId__ header from message → class name
     OR uses spring.json.value.default.type property → fallback class
  2. ObjectMapper.readValue(bytes, NotificationEvent.class)
  3. Returns deserialized object

  If deserialization fails:
  → DeserializationException wrapped in SerializationException
  → ErrorHandler kicks in → routes to DLT (Dead Letter Topic) immediately

STEP 4: Method invocation
─────────────────────────────────────────────────────────────────────────────
  container thread calls: handleNotificationEvent(event)
  → Method executes on the container's thread (NOT the main thread)
  → Any @Transactional on the method gets a NEW connection (this is a different thread)

STEP 5: Offset commit (AckMode.BATCH — default)
─────────────────────────────────────────────────────────────────────────────
  After processing all records in the poll batch:
  consumer.commitSync({partition → offset+1})
  → Kafka broker stores: this consumer group has processed up to offset X
  → On restart: consumer resumes from offset X+1 (no re-processing)
```

### 11.2 Error Handling in Kafka Consumers

```
DefaultErrorHandler (Spring Kafka 3.x):
  Record fails → retry according to BackOff policy:
    ExponentialBackOffWithMaxRetries(3): 1s → 2s → 4s

  After retries exhausted:
    DeadLetterPublishingRecoverer sends record to: <topic>.DLT
    Original record committed (won't be re-delivered)

  Non-retryable exceptions (configured):
    DeserializationException, NullPointerException → skip retries → DLT immediately
```

### 11.3 Best Practices

- DO: Configure explicit deserializer types (avoid class-not-found across module boundaries)
- DO: Set `spring.json.trusted.packages` (security: don't deserialize arbitrary classes)
- DO: Use DLT (Dead Letter Topic) for messages that fail after retries
- DON'T: Do heavy processing in the listener thread (blocks polling → consumer timeout → rebalance)
- DON'T: Throw exceptions from listeners without error handling configured (infinite retry loop)
- DON'T: Use auto-commit (messages lost if crash between commit and processing)

---

## 12. Dependency Injection — Constructor vs Field vs Setter

### 12.1 Constructor Injection (Recommended)

```java
@Service
@RequiredArgsConstructor  // Lombok generates constructor
public class TradeServiceImpl implements TradeService {
    private final PortfolioService portfolioService;  // injected via constructor
    private final LedgerService ledgerService;
    private final NotificationPublisher notificationPublisher;
}
```

**Why constructor is best:**

1. **Immutable:** Fields are `final` — can't be reassigned after construction
2. **Testable:** Pass mocks via constructor in unit tests (no reflection needed)
3. **Fail-fast:** Missing dependency → BeanCreationException at startup (not NullPointerException at runtime)
4. **No reflection:** Spring calls the constructor normally (no accessibility hacking)

### 12.2 Field Injection (Avoid in Production)

```java
@Service
public class MyService {
    @Autowired
    private PortfolioService portfolioService;  // injected via reflection
}
```

**Why it's discouraged:**

1. Requires reflection (slower, bypasses encapsulation)
2. Can't declare `final` (mutable after construction)
3. Harder to test (need Spring context or ReflectionTestUtils)
4. Hides dependencies (class looks simple until you count @Autowired fields)

**When field injection is acceptable:** Circular dependency workaround (EquityCart used this for VestingHelper ↔ PortfolioService before extraction).

### 12.3 Best Practices

- DO: Always use constructor injection (with Lombok @RequiredArgsConstructor)
- DO: Keep constructor parameter count under ~7 (more = class does too much)
- DON'T: Mix injection styles in the same class
- DON'T: Use @Autowired on constructors (unnecessary since Spring 4.3 — single constructor is auto-detected)

---

## 13. Spring Profiles and @ConditionalOnProperty

### 13.1 How EquityCart Uses Conditionals

```java
@Service
@ConditionalOnProperty(name = "equitycart.sell-to-spend.strategy", havingValue = "saga")
public class SellToSpendSagaServiceImpl implements SellToSpendService { ... }

@Service
@ConditionalOnProperty(name = "equitycart.sell-to-spend.strategy",
    havingValue = "transactional", matchIfMissing = true)
public class SellToSpendServiceImpl implements SellToSpendService { ... }
```

**How it works:** During bean definition registration, Spring evaluates the condition:

- If `equitycart.sell-to-spend.strategy=saga` → SagaServiceImpl is registered, Transactional is NOT
- If property absent (matchIfMissing=true) → Transactional is registered
- Both implement same interface → controller/facade code unchanged

### 13.2 @Profile vs @ConditionalOnProperty

|              | @Profile                            | @ConditionalOnProperty                           |
| ------------ | ----------------------------------- | ------------------------------------------------ |
| Activated by | spring.profiles.active              | Any property value                               |
| Granularity  | Environment-level (dev, prod, test) | Feature-level (enable/disable specific behavior) |
| Use case     | Different infrastructure per env    | Feature flags, strategy toggle                   |
| EquityCart   | @Profile("!cdc") on OutboxPoller    | sell-to-spend.strategy toggle                    |

### 13.3 Best Practices

- DO: Use @Profile for infrastructure differences (DataSource, mail server)
- DO: Use @ConditionalOnProperty for feature toggles (algorithm selection)
- DON'T: Use @Profile for business logic branching
- DON'T: Have both conditional beans match simultaneously (ambiguous — Spring throws NoUniqueBeanDefinitionException)

---

## 14. Spring Boot 3.x — What Changed from 2.x

| Area                     | Spring Boot 2.x           | Spring Boot 3.x                                      |
| ------------------------ | ------------------------- | ---------------------------------------------------- |
| Java baseline            | Java 8+                   | Java 17+ (required)                                  |
| Jakarta namespace        | javax.\*                  | jakarta.\* (big migration)                           |
| Auto-config registration | META-INF/spring.factories | META-INF/spring/AutoConfiguration.imports            |
| Observability            | Sleuth + Zipkin           | Micrometer Tracing (unified)                         |
| Native compilation       | Experimental              | Production-ready (GraalVM)                           |
| Virtual threads          | Not supported             | spring.threads.virtual.enabled=true (3.2+)           |
| HTTP interface clients   | Not available             | @HttpExchange (declarative, like Feign but built-in) |
| Problem Details          | Manual                    | RFC 7807 ProblemDetail built-in                      |

**EquityCart runs on Spring Boot 3.5.x** — uses Jakarta namespace, Java 21, and can enable virtual threads.

---

## 15. Common Spring Boot Pitfalls

| Pitfall                     | Symptom                                 | Root Cause                                | Fix                                                                     |
| --------------------------- | --------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------------- |
| @Transactional not working  | DB changes not committing/rolling back  | Self-invocation (calling from same class) | Extract to separate @Service                                            |
| LazyInitializationException | Error accessing entity field outside TX | Entity accessed after session closed      | Use `@Transactional` on caller, or fetch eagerly, or use DTO projection |
| Circular dependency         | BeanCurrentlyInCreationException        | A→B→A constructor injection               | Redesign (extract shared logic), or @Lazy on one side                   |
| Cache not evicting          | Stale data returned                     | Missing @CacheEvict on write methods      | Add eviction on every mutation                                          |
| @Scheduled not firing       | Job never runs                          | Missing @EnableScheduling                 | Add to main class or @Configuration                                     |
| @Async not async            | Method runs synchronously               | Same-class call, or no @EnableAsync       | Extract to separate bean + add @EnableAsync                             |
| N+1 query problem           | 100 SQL queries instead of 1            | Lazy-loaded collections iterated          | Use @EntityGraph, JOIN FETCH, or DTO projections                        |

---

## Spring Cloud Config + bootstrap.yml (Phase 7 — 2026-06-02)

### Breaking Change: bootstrap.yml Deprecated in Spring Boot 3.5.8 + Spring Cloud 2025.0.0

In Spring Boot 3.x / Spring Cloud 2025.0.0+, `bootstrap.yml` is no longer processed. This is a breaking change from Spring Cloud 2024.x.

**Historical context:**
- Spring Cloud < 2024.0: `bootstrap.yml` processed in a separate "bootstrap phase" before `application.yml`
- Spring Cloud 2024.0+: Bootstrap phase merged into normal startup
- Spring Cloud 2025.0.0: `bootstrap.yml` deprecated, ignored silently

**Symptom:** Error at startup: `"No spring.config.import property has been defined"` even though the property exists in bootstrap.yml.

**Fix:** Move `spring.config.import` to `application.yml`:
```yaml
# application.yml (NOT bootstrap.yml)
spring:
  application:
    name: api-gateway
  config:
    import: configserver:http://localhost:8888
```

**Key rule:** `spring.application.name` MUST remain in local `application.yml` — Config Server uses it to determine which service-specific YAML to fetch. If missing, Config Server is called with "UNKNOWN" and service-specific configs never load.

---

### @EnableDiscoveryClient — Annotation Activation Model (Phase 7)

`@EnableDiscoveryClient` is an activation annotation — it enables beans provided by a discovery dependency. It does NOT provide discovery functionality on its own.

**Required dependency:**
```gradle
implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'
```

**Without this dependency:** Annotation silently does nothing. No errors thrown. No registration logs. Dashboard shows zero instances.

**Diagnosis checklist when Eureka registration fails:**
1. Is `spring-cloud-starter-netflix-eureka-client` in build.gradle? → Add it
2. Is `@EnableDiscoveryClient` on main class or `@Configuration`? → Must be Spring-scanned
3. Is `spring.application.name` set in application.yml? → Required for Eureka registration name
4. Are startup logs showing "Registering application ... with eureka"? → If not, dependency is missing

**Note:** `spring-cloud-starter-gateway` does NOT transitively include `spring-cloud-starter-netflix-eureka-client`.

---

### Actuator Endpoints — Configuration + Spring Security Interaction (Phase 7)

Actuator configuration and Spring Security are INDEPENDENT layers. Both must allow access.

**Enable endpoints (management layer):**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: always
```

**Allow access (security layer):**
```java
http.authorizeHttpRequests(authz -> authz
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated()
);
```

**Symptom if security is missing:** HTTP 403 on `/actuator/health` even though it's in `exposure.include`.

**Endpoint sensitivity guide:**
- `health`, `info` — safe (expose publicly)
- `metrics` — moderate (internal use / admin only)
- `env`, `configprops` — sensitive (may expose credentials — never public)

---

## 12. Transitive Dependencies — `api` vs `implementation` in Gradle Multi-Module Projects

### 12.1 Why This Matters for Spring Boot

Spring Boot auto-configuration fires based on what is ON the classpath. If a dependency reaches your service's classpath transitively (even if you never asked for it), auto-configuration for that library will activate. This caused a real startup failure in EquityCart Phase 7:

- `commons/build.gradle` declared: `api 'spring-boot-starter-data-jpa'`
- `market-data/build.gradle` declared: `implementation project(':commons')`
- Result: JPA landed on market-data's classpath transitively
- Spring Boot saw JPA → fired `DataSourceAutoConfiguration` → looked for `spring.datasource.url` → found nothing → **crash**

Market-data has no SQL entities. It never needed JPA. But because of `api` scope, it inherited JPA silently.

### 12.2 `api` vs `implementation` — The Difference

```
Module A: commons/build.gradle
  api 'spring-boot-starter-data-jpa'       ← leaks to consumers
  implementation 'some-internal-lib'        ← private, NOT leaked

Module B: market-data/build.gradle
  implementation project(':commons')

Result:
  - market-data CAN use commons public types (BaseEntity, etc.)
  - market-data ALSO gets spring-boot-starter-data-jpa on its classpath (api leak)
  - market-data CANNOT see 'some-internal-lib' (implementation = private)
```

### 12.3 Decision Rule

Use `api` scope only when the dependency's types appear in your module's **public API signatures** — method parameter types, return types, or public field types that consumers must use.

```java
// commons/BaseEntity.java — public type
@MappedSuperclass               // ← this annotation is FROM spring-data-jpa
public abstract class BaseEntity {
    @Id                         // ← this annotation is FROM spring-data-jpa
    @GeneratedValue
    private Long id;
}
```

Because `BaseEntity` extends/uses JPA annotations in its public declaration, any module that subclasses `BaseEntity` needs JPA to compile. So `api` is correct here. The problem is that market-data (which doesn't extend BaseEntity) inherited it unnecessarily by depending on commons.

### 12.4 Two Fix Options When You Inherit Unwanted Auto-Configuration

**Option A — Exclude auto-configuration at startup (keep the dependency):**
```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
public class MarketDataServiceApplication { ... }
```
Use when: you need some commons types but not the auto-configured bean (e.g., you use `BaseEntity` for ID generation but have no SQL).

**Option B — Remove the dependency entirely:**
```groovy
// market-data/build.gradle — comment out the unused commons dependency
// implementation project(':commons')
```
Use when: the module genuinely has no use for commons at all. This is the cleaner fix.

### 12.5 Detecting Transitive Dependency Leaks

```bash
# List all resolved dependencies for a module (shows transitive chain)
./gradlew :market-data:dependencies --configuration compileClasspath

# Look for unexpected entries like:
# +--- project :commons
# |    +--- org.springframework.boot:spring-boot-starter-data-jpa (transitive)
```

---

## 13. Spring Security in Standalone Microservices — The Monolith vs Standalone Gap

### 13.1 The Problem

In a monolith, ONE `SecurityFilterChain` bean (defined in any module on the shared classpath) protects ALL modules. When you extract a service as a standalone Spring Boot app, it no longer shares a classpath — it must define its own security configuration.

**EquityCart example:** In the monolith, `user-service/SecurityConfig` was compiled into the same JVM as `market-data`. All market-data endpoints required JWT authentication because SecurityConfig applied to the entire application. After extraction, standalone market-data has no SecurityConfig, so all endpoints are open.

### 13.2 `spring-security-core` vs `spring-boot-starter-security`

| What you add | What you get | Default HTTP protection |
|---|---|---|
| `spring-security-core` | Core types: `Authentication`, `SecurityContext`, `@PreAuthorize`, `GrantedAuthority` | **None** — no filter chain, no auto-config |
| `spring-boot-starter-security` | Core types + `SecurityAutoConfiguration` | **Yes** — all routes require auth by default |

The typical reason to use only `spring-security-core` in a microservice:
- You need to work with security types (e.g., parse a JWT passed from a gateway) but don't want to impose a specific filter chain
- You're in a transition phase and will add full security later
- You're behind an API gateway that already validates tokens — downstream services only need to extract the userId from a forwarded header

### 13.3 `@PreAuthorize` Without `@EnableMethodSecurity` — Silent No-Op

```java
// ❌ This annotation does NOTHING if @EnableMethodSecurity is not declared anywhere
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> evictCache(String symbol) { ... }
```

`@PreAuthorize` is just metadata (a Java annotation). Spring only processes it if the method security AOP advisor is registered. The advisor is registered only when a `@Configuration` class in the same application context declares `@EnableMethodSecurity`.

In the monolith, `user-service`'s `SecurityConfig` had `@EnableMethodSecurity` and was on the shared classpath — all modules benefited. In a standalone service, you must declare it yourself.

**To enable method security in a standalone service:**
```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    // Even an empty class makes @PreAuthorize active
}
```

### 13.4 Phase 8 Plan: Per-Service JWT Validation

The correct approach for Phase 8 (Security Hardening) is Spring Security's OAuth2 Resource Server:

```groovy
// Each service build.gradle
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

```yaml
# Each service's config YAML
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Either point to a JWK Set URI for public key retrieval,
          # or use the shared secret for HMAC validation
```

The resource server auto-configuration creates a `JwtAuthenticationConverter` → `BearerTokenAuthenticationFilter` chain that validates JWT on every request. This replaces the manual `JwtAuthFilter` in the monolith with a declarative, standardized approach.


---

## 15. Spring Scanning Pipelines in Multi-Module Applications

### 15.1 The Four Independent Scanning Pipelines

Spring Boot registers beans through four entirely separate mechanisms. None covers the others automatically:

| What is registered | Controlled by | Scans for |
|---|---|---|
| `@Component`, `@Service`, `@Controller`, `@Configuration` beans | `@ComponentScan` | Classes with those annotations |
| JPA repository proxy beans | `@EnableJpaRepositories` | Interfaces extending `JpaRepository` |
| JPA entity / MappedSuperclass registration | `@EntityScan` | Classes annotated `@Entity` / `@MappedSuperclass` |
| MongoDB repository proxy beans | `@EnableMongoRepositories` | Interfaces extending `MongoRepository` |

The key insight: expanding `@ComponentScan` does **not** automatically expand `@EntityScan` or `@EnableJpaRepositories`, and vice versa. You must configure each pipeline explicitly when its targets live outside the main class's package tree.

### 15.2 Decision Guide: Which Scanner to Use

| Need | Use |
|---|---|
| Use a foreign `@Service` or `@Component` bean directly | `@ComponentScan` covering that package |
| Use only a foreign `JpaRepository` proxy | `@EnableJpaRepositories` covering that package |
| Use a foreign `@Entity` / `@MappedSuperclass` | `@EntityScan` covering that package |

**Rule:** only expand a scanner when the target class lives **outside** the main class's package tree. Within the tree, defaults cover everything.

If you only need a repository, use the narrower `@EnableJpaRepositories` — it loads no `@Configuration` classes and avoids auto-configuration side effects. Only escalate to `@ComponentScan` when you need the full service layer.

### 15.3 The `@ComponentScan` Breadth Hazard

`@ComponentScan` is broad: it loads ALL `@Component`, `@Service`, and `@Configuration` classes from scanned packages. This has three cascading effects in multi-module projects:

**1. BeanDefinitionOverrideException from other services' main classes**

When `@ComponentScan` covers a package that contains another service's `@SpringBootApplication` class, Spring finds it and processes it as a `@Configuration` (because `@SpringBootApplication` meta-annotates `@SpringBootConfiguration`). That main class carries its own `@EnableJpaRepositories`, triggering duplicate repository registrations.

**Fix:** `excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class)` — skip classes annotated with `@SpringBootApplication` while still scanning all other beans in those packages.

**2. Transitive auto-configuration triggers**

`implementation project(':product-service')` puts that module's entire compiled classpath into your service. When `@ComponentScan` covers `com.equitycart.product.*`, it loads `ProductBatchConfig` (a `@Configuration` carrying `spring-batch`). Spring Boot's `BatchAutoConfiguration` then fires, expecting `spring.batch.jdbc.initialize-schema` and `spring.batch.job.enabled` to be present.

With `@EnableJpaRepositories` instead: only JPA proxy beans are registered — `ProductBatchConfig` is never loaded, `BatchAutoConfiguration` never fires.

**3. Transitive `@Value` property requirements**

Any `@Configuration` or `@Service` loaded via `@ComponentScan` that reads `@Value("${some.property}")` requires that property in your YAML. In EquityCart: scanning `com.equitycart.marketdata` loads `WebClientConfig`, which reads `${alphavantage.api-key}` — forcing portfolio-service to supply that value even though it never uses Alpha Vantage directly.

**Summary:** `@ComponentScan` expansion is the primary source of startup complexity during Strangler Fig service extraction. Services that can be extracted without it start cleanly. Services requiring it inherit all their dependencies' startup requirements.

### 15.4 `@EntityScan` Independent from `@EnableJpaRepositories`

`@EntityScan` and `@EnableJpaRepositories` each override only their own scanning default — they are entirely independent. You can need one without the other.

**Pattern:** `@EntityScan` is needed when an `@Entity` or `@MappedSuperclass` lives outside the main class's package tree — even when all repository interfaces are inside it (so `@EnableJpaRepositories` is unnecessary). In EquityCart, ledger-service and notification-service need `@EntityScan` (for `BaseEntity` at `com.equitycart.commons.entity`) but NOT `@EnableJpaRepositories` (their own repositories are in-scope by default).

Without `@EntityScan` covering `com.equitycart.commons`, Hibernate does not register `BaseEntity` as a managed superclass, and the inherited `id`, `createdAt`, `updatedAt` columns are omitted from schema generation.

### 15.5 `@ConditionalOnProperty(matchIfMissing = true)` — Default-ON Beans

`@ConditionalOnProperty` has a `matchIfMissing` parameter controlling what happens when the named property is absent:

| `matchIfMissing` value | Property absent | Bean created? |
|---|---|---|
| `false` (default) | condition NOT met | No |
| `true` | condition treated as met | Yes |

Used to make a strategy bean **default-active** without requiring an explicit YAML entry. The property only needs to be set when overriding the default.

Example: `@ConditionalOnProperty(name = "equitycart.sell-to-spend.strategy", havingValue = "saga", matchIfMissing = true)` makes `SellToSpendSagaOrchestrator` the active strategy whenever the property is absent or equals `"saga"`.

### 15.6 `@Value` Inline Defaults — `${property:default}`

Spring's `@Value` annotation supports an inline fallback with the `:` separator:

```java
@Value("${equitycart.saga.timeout-seconds:30}")
private int timeoutSeconds;
```

When the property is absent, Spring substitutes `30` directly — no YAML entry required. The bean is always created; only the field value changes.

**Contrast with `matchIfMissing`:**
- `@ConditionalOnProperty(matchIfMissing = true)` controls **whether the bean exists at all**
- `@Value("${prop:default}")` controls **what value a field gets** inside a bean that already exists

**Rule of thumb:** only add a property to YAML when you need to override its designed default. Config files should express deviations from defaults, not repeat them.
