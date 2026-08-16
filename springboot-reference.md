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

| What you add                   | What you get                                                                         | Default HTTP protection                      |
| ------------------------------ | ------------------------------------------------------------------------------------ | -------------------------------------------- |
| `spring-security-core`         | Core types: `Authentication`, `SecurityContext`, `@PreAuthorize`, `GrantedAuthority` | **None** — no filter chain, no auto-config   |
| `spring-boot-starter-security` | Core types + `SecurityAutoConfiguration`                                             | **Yes** — all routes require auth by default |

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

| What is registered                                              | Controlled by              | Scans for                                         |
| --------------------------------------------------------------- | -------------------------- | ------------------------------------------------- |
| `@Component`, `@Service`, `@Controller`, `@Configuration` beans | `@ComponentScan`           | Classes with those annotations                    |
| JPA repository proxy beans                                      | `@EnableJpaRepositories`   | Interfaces extending `JpaRepository`              |
| JPA entity / MappedSuperclass registration                      | `@EntityScan`              | Classes annotated `@Entity` / `@MappedSuperclass` |
| MongoDB repository proxy beans                                  | `@EnableMongoRepositories` | Interfaces extending `MongoRepository`            |

The key insight: expanding `@ComponentScan` does **not** automatically expand `@EntityScan` or `@EnableJpaRepositories`, and vice versa. You must configure each pipeline explicitly when its targets live outside the main class's package tree.

### 15.2 Decision Guide: Which Scanner to Use

| Need                                                   | Use                                            |
| ------------------------------------------------------ | ---------------------------------------------- |
| Use a foreign `@Service` or `@Component` bean directly | `@ComponentScan` covering that package         |
| Use only a foreign `JpaRepository` proxy               | `@EnableJpaRepositories` covering that package |
| Use a foreign `@Entity` / `@MappedSuperclass`          | `@EntityScan` covering that package            |

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

| `matchIfMissing` value | Property absent          | Bean created? |
| ---------------------- | ------------------------ | ------------- |
| `false` (default)      | condition NOT met        | No            |
| `true`                 | condition treated as met | Yes           |

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

---

## Section 16: Spring Cloud OpenFeign - Declarative HTTP Clients

### 16.1 History: RestTemplate (2009) to OpenFeign (2012-2015)

**RestTemplate** (Spring 3.0, 2009): Imperative HTTP client. Requires hardcoding URLs, manual header management, no contract, and custom error handling per call.

**Netflix Feign** (2012, open-sourced 2013): Invented at Netflix alongside Eureka and Ribbon. Core insight: an HTTP API is a contract, and the same Java interface mechanism that defines local contracts can define HTTP contracts across services. Feign generates the HTTP implementation at startup from an annotated interface - no implementation code needed.

**Spring Cloud OpenFeign** (integrated ~2015): Wraps Feign with Spring MVC annotations (@GetMapping, @PathVariable, etc.) and wires in Spring encoder/decoder, load balancer, and error handling. @EnableFeignClients triggers the entire startup wiring.

---

### 16.2 Startup Flow: What @EnableFeignClients Does

1. @EnableFeignClients(basePackages = "...") imports FeignClientsRegistrar
2. FeignClientsRegistrar scans packages for interfaces annotated with @FeignClient
3. For each interface, registers a FeignClientFactoryBean bean definition in the context
4. When the context requests a ProductFeignClient bean, FeignClientFactoryBean.getObject() fires:
   - Builds Feign.Builder with: SpringMvcContract (interprets @GetMapping etc.), SpringEncoder, SpringDecoder, FeignErrorDecoder, FeignBlockingLoadBalancerClient (lb:// via Eureka)
   - Calls ReflectiveFeign.newInstance() -> Proxy.newProxyInstance() -> JDK Dynamic Proxy
5. The proxy is injected as the bean wherever ProductFeignClient is declared

**JDK Dynamic Proxy vs CGLIB:**

- JDK Dynamic Proxy: works on Java interface only. Generates a class at runtime that implements the interface and delegates all method calls to an InvocationHandler.
- CGLIB: generates bytecode subclassing a concrete class. Cannot be used here - there is no concrete class, only an interface.
- Feign uses JDK Dynamic Proxy exclusively.

---

### 16.3 Runtime Call Flow (Debug-Level Trace)

When productFeignClient.getProductById(42L) is called:

1. JVM routes to FeignInvocationHandler.invoke() (the InvocationHandler behind the proxy)
2. FeignInvocationHandler looks up SynchronousMethodHandler for the getProductById method
3. SynchronousMethodHandler builds a RequestTemplate: fills /api/products/{id} -> /api/products/42
4. Passes RequestTemplate to FeignBlockingLoadBalancerClient
5. LoadBalancer queries Eureka, selects a live PRODUCT-SERVICE instance (e.g., localhost:8089)
6. Substitutes instance into URL: http://localhost:8089/api/products/42
7. Makes a blocking HTTP GET
8. Response (200 OK + JSON body) returned to SynchronousMethodHandler
9. SpringDecoder calls Jackson ObjectMapper.readValue(body, ProductDTO.class)
10. Jackson populates the ProductDTO record from matching JSON fields
11. Proxy returns ProductDTO to the calling code

---

### 16.4 DTO Projection - Jackson Subset Deserialization

When JSON response has more fields than the target type, Jackson silently drops the extras.

**Why it works:** Spring Boot sets DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false globally. Jackson only maps fields that exist in BOTH the JSON AND the target class.

**Pattern (DTO Projection):** Each consuming service declares its own DTO with only the fields it needs. No coupling to the full API response shape.

Example: ProductResponse (12 fields in product-service) vs ProductDTO (6 fields in commons). Jackson maps the 6 matching fields and drops the other 6.

| Scenario                           | Result                                   |
| ---------------------------------- | ---------------------------------------- |
| JSON has field, target has field   | Mapped normally                          |
| JSON has field, target lacks field | Silently dropped (FAIL_ON_UNKNOWN=false) |
| Target has field, JSON lacks field | Field is null or primitive default       |
| Incompatible types                 | Deserialization exception                |

---

### 16.5 FeignErrorDecoder - Non-2xx Interception

Feign default on non-2xx: throws FeignException subclass (FeignException.NotFound for 404, FeignException.Conflict for 409). These are library types with no domain meaning.

FeignErrorDecoder intercepts the Response object before the default exception is thrown, mapping HTTP status codes to domain exceptions:

HTTP 409 -> FeignErrorDecoder.decode() -> throw InsufficientStockException
HTTP 404 -> FeignErrorDecoder.decode() -> new Default().decode() -> FeignException.NotFound

Registration: @Component on the decoder causes Spring to wire it into every Feign client in the context automatically.

**Cannot intercept 200-but-failure responses:** The decoder only fires on non-2xx. For 200-but-failure: (1) return type as envelope (ApiResponse<T>), (2) custom Decoder that inspects the body, or (3) ResponseInterceptor (Feign 12+).

---

### 16.6 @RequestParam vs @RequestBody in Feign Interfaces

- @RequestParam: single scalar passed as query parameter (?quantity=5). Use for scalar inputs on PUT/GET without a body.
- @RequestBody: multi-field payload serialized as JSON in the request body. Use for structured input.

Rule: single scalar -> @RequestParam. Structured payload -> @RequestBody with a DTO.

Example from ProductFeignClient:
void deductStock(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);

---

## 17. Spring Cloud Config Server — Property Resolution in Docker

### 17.1 How spring.config.import Works

`spring.config.import=configserver:http://config-server:8888` tells Spring Boot:

1. Before building the ApplicationContext, contact the config server
2. Fetch properties for this application's name (spring.application.name)
3. MERGE those properties with the local application.yml (not replace)

Priority order (highest wins):

1. OS environment variables (SPRING_DATASOURCE_URL=...)
2. Config Server properties (fetched remotely)
3. Embedded application.yml in the JAR

**Critical insight:** `spring.config.import` is ADDITIVE. It doesn't replace local properties — it merges additional sources. This means a property defined in both the local YAML and the config server will use the config server's value (higher priority), but properties only in local YAML remain untouched.

### 17.2 The Placeholder Pattern for Dual-Environment Configs

Config server's YAML files contain placeholders, not final values:

```yaml
# equitycart-config/application.yml (served to all services):
eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
```

The config server sends this LITERALLY to the client as `${EUREKA_URL:http://localhost:8761/eureka/}`. The CLIENT resolves the placeholder using its own environment. In Docker Compose, `EUREKA_URL=http://discovery:8761/eureka/` is set on the client container → resolved to the Docker hostname.

**Why not resolve at config-server?** Because the same config must work for services running locally (use localhost default) AND in Docker (use container hostname). Client-side resolution with env vars gives flexibility without multiple config profiles.

### 17.3 spring.config.import Is NOT a Standard Spring Property

Unlike `spring.datasource.url` which can be overridden by setting `SPRING_DATASOURCE_URL` env var via relaxed binding, `spring.config.import` does NOT support relaxed binding override from env vars.

To make it configurable, you MUST use an explicit placeholder:

```yaml
# WRONG — cannot be overridden by any env var:
spring:
  config:
    import: configserver:http://localhost:8888

# RIGHT — CONFIG_SERVER_URL env var overrides the default:
spring:
  config:
    import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

### 17.4 Config Server Git Backend — Clone and Cache

1. Config server starts → clones Git repo to /tmp/config-repo-RANDOM/
2. All subsequent requests read from this local clone
3. Periodically (controlled by `refresh-rate`), it tries `git fetch` to pull updates
4. If fetch fails (DNS, network), it continues serving from cached clone — no error to clients
5. Health indicator separately polls the repo — disable with `health.enabled: false`

### 17.5 Eureka prefer-ip-address and Docker

When `eureka.instance.prefer-ip-address: true`, Spring calls `InetUtils.findFirstNonLoopbackAddress()` to determine the IP to register with Eureka. In Docker, this returns the container's IP on the bridge network (172.18.0.x).

**DO NOT set `eureka.instance.ip-address: 127.0.0.1` in Docker.** Each service would register its own loopback — gateway would route requests to itself instead of the target service.

Let Spring auto-detect the container IP. The Docker DNS resolver handles the rest.
// quantity is a single int -> @RequestParam (maps to ?quantity=5 in the URL)

---

## 18. RequestContextHolder — Spring's ThreadLocal Request Storage

### What It Is

`RequestContextHolder` is Spring's mechanism for storing the current `HttpServletRequest` in a `ThreadLocal` variable, making it accessible anywhere on the same thread without passing it as a method parameter.

### Internal Architecture

```java
// From org.springframework.web.context.request.RequestContextHolder:

private static final ThreadLocal<RequestAttributes> requestAttributesHolder =
    new NamedThreadLocal<>("Request attributes");

private static final ThreadLocal<RequestAttributes> inheritableRequestAttributesHolder =
    new NamedInheritableThreadLocal<>("Request context");
```

Two ThreadLocal fields:

- `requestAttributesHolder` — plain ThreadLocal (default, thread-confined)
- `inheritableRequestAttributesHolder` — InheritableThreadLocal (propagates to child threads, disabled by default)

### Lifecycle: Who Sets and Clears It

```
Request arrives at Tomcat → Thread-N assigned from pool
    │
    ▼
FrameworkServlet.processRequest(request, response)
    │
    ├── previousAttributes = RequestContextHolder.getRequestAttributes()  // save old (usually null)
    ├── requestAttributes = new ServletRequestAttributes(request, response)
    ├── RequestContextHolder.setRequestAttributes(requestAttributes)      // ★ STORE IN ThreadLocal
    │
    ├── try {
    │       doService(request, response)  →  DispatcherServlet.doDispatch()
    │           → HandlerMapping → HandlerAdapter → YourController.method()
    │           → [entire request processing happens here, ThreadLocal available]
    │   }
    │
    └── finally {
            RequestContextHolder.setRequestAttributes(previousAttributes)  // ★ RESTORE/CLEAR
            requestAttributes.requestCompleted()                           // signal lifecycle end
        }
    │
    ▼
Thread-N returns to Tomcat pool (ThreadLocal is now null/previous)
```

### Key Method: getRequestAttributes()

```java
public static RequestAttributes getRequestAttributes() {
    RequestAttributes attributes = requestAttributesHolder.get();      // check plain ThreadLocal first
    if (attributes == null) {
        attributes = inheritableRequestAttributesHolder.get();         // fallback to inheritable
    }
    return attributes;  // null if current thread has no HTTP request context
}
```

Returns `null` when called from:

- Kafka consumer threads (started by Kafka poller, no HTTP request)
- @Scheduled threads (started by Spring's TaskScheduler)
- @Async child threads (new thread from executor, ThreadLocal not inherited by default)
- Thread pool workers (CompletableFuture.supplyAsync())

### Why Plain ThreadLocal (Not InheritableThreadLocal)?

Spring defaults to `threadContextInheritable = false` in FrameworkServlet for safety:

| Risk          | Explanation                                                                                                                          |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Memory leak   | Child thread holds reference to HttpServletRequest object → prevents GC → request/response buffers retained long after response sent |
| Security leak | Parent thread gets reused from pool for new user → child thread still references OLD user's request → accessing stale auth data      |
| Stale data    | Request completed and response committed, but child thread still reads from the request object → undefined behavior                  |

MDC (Log4j ThreadContext) uses InheritableThreadLocal safely because it stores lightweight String values that don't reference heavy objects or sensitive data.

### Usage Pattern in EquityCart

```
FeignAuthorizationInterceptor:
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    // Safe: Feign calls execute synchronously on the same servlet thread
    // The ThreadLocal is guaranteed to contain the original request

MdcCorrelationFilter:
    // Does NOT use RequestContextHolder — uses its own MDC ThreadContext
    // MDC is InheritableThreadLocal, so correlationId propagates to child threads
```

### Comparison with Other Spring ThreadLocal Mechanisms

| Mechanism             | Stores                        | ThreadLocal Type                        | Cleanup By                         |
| --------------------- | ----------------------------- | --------------------------------------- | ---------------------------------- |
| RequestContextHolder  | HttpServletRequest + Response | Plain (default)                         | FrameworkServlet finally block     |
| SecurityContextHolder | Authentication object         | Configurable (MODE_THREADLOCAL default) | SecurityContextPersistenceFilter   |
| MDC / ThreadContext   | Key-value String pairs        | InheritableThreadLocal                  | MdcCorrelationFilter finally block |
| LocaleContextHolder   | Locale + TimeZone             | Plain (default)                         | FrameworkServlet finally block     |

---

## 19. Reactive Web Stack — GlobalFilter, Mono, and ServerWebExchange

### Why Spring Cloud Gateway Cannot Use Servlet APIs

Spring Boot offers two web stacks (mutually exclusive per application):

| Stack                  | Dependency                    | Threading Model                                 | APIs                                          |
| ---------------------- | ----------------------------- | ----------------------------------------------- | --------------------------------------------- |
| **Servlet (MVC)**      | `spring-boot-starter-web`     | 1 thread per request (Tomcat pool, 200 default) | HttpServletRequest, FilterChain, @Controller  |
| **Reactive (WebFlux)** | `spring-boot-starter-webflux` | Event loop (Netty, ~4 threads for ALL requests) | ServerWebExchange, Mono/Flux, @RestController |

Spring Cloud Gateway uses **WebFlux** because a gateway handles thousands of concurrent connections (mostly waiting for downstream responses) — event loop model is far more efficient than blocking one thread per connection.

### GlobalFilter Interface

```java
public interface GlobalFilter {
    Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain);
}
```

- `ServerWebExchange`: contains both request (`getRequest()`) and response (`getResponse()`)
- `GatewayFilterChain`: call `chain.filter(exchange)` to proceed, or return a Mono that writes a response to short-circuit
- `Mono<Void>`: represents an asynchronous computation that completes without a value (equivalent to `void` in async world)

### Mono<Void> — What It Means

```
Mono<Void> = "a signal that something will complete in the future, producing no value"

chain.filter(exchange)     → "proceed to next filter, return when entire chain completes"
response.writeWith(mono)   → "write body bytes, return when write completes"
Mono.empty()               → "do nothing, complete immediately"
```

### ServerWebExchange vs HttpServletRequest

| Operation      | Servlet                            | Reactive                                                        |
| -------------- | ---------------------------------- | --------------------------------------------------------------- |
| Get header     | `request.getHeader("X")`           | `exchange.getRequest().getHeaders().getFirst("X")`              |
| Get path       | `request.getRequestURI()`          | `exchange.getRequest().getPath().value()`                       |
| Set status     | `response.setStatus(401)`          | `exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED)` |
| Write body     | `response.getWriter().write(json)` | `exchange.getResponse().writeWith(Mono.just(buffer))`           |
| Mutate request | Not standard (wrapper)             | `exchange.mutate().request(mutatedRequest).build()`             |

### Ordered Interface for Filter Ordering

```java
public interface Ordered {
    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;  // runs FIRST
    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;   // runs LAST
    int getOrder();
}
```

EquityCart gateway filter order:

```
HIGHEST_PRECEDENCE     → CorrelationIdGatewayFilter (assign trace ID)
HIGHEST_PRECEDENCE + 1 → JwtValidationGatewayFilter (reject bad tokens)
(default order)        → Spring Cloud Gateway routing filters
```

### Blocking on the Event Loop — The Cardinal Sin

```
Netty event loop (4 threads handling thousands of connections):

Thread-1: request A → JJWT parse (CPU, 50μs) ✓ → route to downstream → waiting...
Thread-2: request B → JJWT parse (CPU, 50μs) ✓ → route to downstream → waiting...
Thread-3: request C → Thread.sleep(5000) ✗ → ALL requests on Thread-3 STALL for 5 seconds
Thread-4: request D → JDBC query (blocking I/O) ✗ → ALL requests on Thread-4 STALL

Rule: NEVER block on event loop threads.
- CPU work < 1ms: OK (JJWT parsing, AntPathMatcher matching)
- Blocking I/O: FORBIDDEN (JDBC, synchronous HTTP, file reads)
- Thread.sleep(): FORBIDDEN
- Locks that wait: FORBIDDEN
```

If you need blocking I/O in a reactive context, use `Mono.fromCallable(() -> blockingCall).subscribeOn(Schedulers.boundedElastic())` to offload to a dedicated thread pool.

---

## 20. @ComponentScan vs @EntityScan — Bean Registration Mechanics (Phase 8)

### The Problem: Classes on Classpath ≠ Spring Beans

This is the single most common multi-module Spring Boot misconception:

```
Gradle dependency:  implementation project(':commons')

What it DOES:     Makes all .class files from commons available at compile time and runtime
What it does NOT: Register ANY class as a Spring-managed bean

Result: DTOs, entities, exceptions → work (no Spring needed)
        @Component, @Configuration, @Service → NOT instantiated (need scanning)
```

### How @SpringBootApplication Scanning Works Internally

```
@SpringBootApplication on OrderServiceApplication.java (package: com.equitycart.order)
    │
    │── internally composed of:
    │   @SpringBootConfiguration
    │   @EnableAutoConfiguration
    │   @ComponentScan   ← THIS is what matters
    │
    └── @ComponentScan with NO explicit basePackages
        │
        │── Spring resolves default: "scan the package of the annotated class"
        │   Source: ComponentScanAnnotationParser.parse()
        │     if (basePackages.isEmpty()) {
        │         basePackages.add(ClassUtils.getPackageName(declaringClass));
        │     }
        │
        │── Resolved: basePackages = ["com.equitycart.order"]
        │
        └── Spring scans ONLY com.equitycart.order.** for:
            @Component, @Service, @Repository, @Controller, @Configuration, @RestController

            DOES NOT SCAN: com.equitycart.commons.** (different package tree!)
```

### The Three Scanning Mechanisms — They Are Independent

```
┌────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot Scanning Mechanisms                       │
├──────────────────────┬─────────────────────┬───────────────────────────┤
│   @ComponentScan     │    @EntityScan      │  @EnableJpaRepositories   │
├──────────────────────┼─────────────────────┼───────────────────────────┤
│ Finds:               │ Finds:              │ Finds:                    │
│  @Component          │  @Entity            │  interfaces extending     │
│  @Configuration      │  @MappedSuperclass  │    JpaRepository          │
│  @Service            │  @Embeddable        │    CrudRepository         │
│  @Repository (bean)  │  @Converter         │    etc.                   │
│  @Controller         │                     │                           │
│  @RestController     │                     │                           │
├──────────────────────┼─────────────────────┼───────────────────────────┤
│ Registers as:        │ Registers with:     │ Generates:                │
│  Spring beans in     │  Hibernate's        │  Proxy implementations    │
│  ApplicationContext  │  MetadataFactory    │  of repository interfaces │
├──────────────────────┼─────────────────────┼───────────────────────────┤
│ Without it:          │ Without it:         │ Without it:               │
│  Bean not created    │  Table not created  │  NoSuchBeanDefinition     │
│  No injection        │  Column mapping     │  for repository           │
│  Silent failure!     │  errors             │                           │
├──────────────────────┼─────────────────────┼───────────────────────────┤
│ Default scope:       │ Default scope:      │ Default scope:            │
│  Package of @SBA     │  Package of @SBA    │  Package of @SBA          │
│  class + subpkgs     │  class + subpkgs    │  class + subpkgs          │
└──────────────────────┴─────────────────────┴───────────────────────────┘

CRITICAL: @EntityScan does NOT trigger @ComponentScan and vice versa.
Having @EntityScan(basePackages = "com.equitycart.commons") means:
  ✅ BaseEntity (@MappedSuperclass) is registered with Hibernate
  ❌ SecurityAutoConfig (@Configuration) is NOT a Spring bean
  ❌ GlobalExceptionHandler (@RestControllerAdvice) is NOT a Spring bean
  ❌ MdcCorrelationFilter (@Component) is NOT a Spring bean
```

### Debug Trace: Bean Discovery at Startup

```
APPLICATION STARTUP — OrderServiceApplication
═══════════════════════════════════════════════

1. ConfigurationClassPostProcessor.processConfigBeanDefinitions()
   │
   │── Reads @ComponentScan metadata from OrderServiceApplication.class
   │   basePackages = ["com.equitycart.order", "com.equitycart.commons"]  ← after fix
   │   excludeFilters = [@SpringBootApplication annotation]
   │
   2. ClassPathBeanDefinitionScanner.doScan(basePackages)
      │
      ├── Scan package: com.equitycart.order.**
      │   Found: OrderController, OrderServiceImpl, CartServiceImpl, ...
      │
      ├── Scan package: com.equitycart.commons.**
      │   Found candidates:
      │     com.equitycart.commons.config.SecurityAutoConfig     → @Configuration ✓
      │     com.equitycart.commons.config.KafkaConsumerConfig    → @Configuration ✓
      │     com.equitycart.commons.filter.JwtAuthenticationFilter → @Component ✓
      │     com.equitycart.commons.filter.MdcCorrelationFilter   → @Component ✓
      │     com.equitycart.commons.handler.GlobalExceptionHandler → @RestControllerAdvice ✓
      │     com.equitycart.commons.feign.FeignCorrelationInterceptor → @Component ✓
      │     com.equitycart.commons.feign.FeignAuthorizationInterceptor → @Component ✓
      │     com.equitycart.commons.security.impl.JwtTokenValidatorImpl → @Component ✓
      │
      │   Apply excludeFilter:
      │     com.equitycart.commons.SomeApplication → @SpringBootApplication → EXCLUDED
      │     (prevents scanning other modules' main classes from multi-module classpath)
      │
      3. For SecurityAutoConfig specifically:
         │
         │── ConditionEvaluator.shouldSkip(SecurityAutoConfig)
         │   Evaluates: @ConditionalOnProperty(name="equitycart.security.enabled", havingValue="true")
         │
         │── PropertyResolver.getProperty("equitycart.security.enabled")
         │   Checks (in order):
         │     a) System properties (-D flag) → not found
         │     b) Environment variables (EQUITYCART_SECURITY_ENABLED) → not found
         │     c) application.yml (from Config Server) → not found
         │     d) order-service.yml (from Config Server) → FOUND: "true"
         │
         │── havingValue="true" matches "true" → condition PASSES
         │── Bean definition registered: SecurityAutoConfig
         │
         4. SecurityAutoConfig bean creation:
            │── @RequiredArgsConstructor injects JwtAuthenticationFilter
            │── @Bean securityFilterChain(HttpSecurity) → creates SecurityFilterChain
            │── @EnableMethodSecurity → registers MethodSecurityInterceptor
            │── log.info("Enabling JWT-based security auto-configuration...")
```

### Why @ComponentScan.excludeFilters Is Required

```java
@ComponentScan(
    basePackages = {"com.equitycart.order", "com.equitycart.commons"},
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class))
```

**Scenario without excludeFilter:**

In a multi-module Gradle project, all modules' compiled .class files can end up on the classpath during builds.
If `PortfolioServiceApplication.class` (annotated with @SpringBootApplication) is reachable from
order-service's classpath:

```
Without excludeFilter:
  Scanner finds PortfolioServiceApplication.class in com.equitycart.portfolio
  → @SpringBootApplication is meta-annotated with @Configuration
  → Spring tries to process it as a configuration class
  → Cascading @ComponentScan from THAT class triggers scanning of com.equitycart.portfolio.**
  → Pulls in portfolio-service beans into order-service context
  → CHAOS: wrong repositories, wrong service implementations, conflicting bean names
```

The `excludeFilters` says: "If you find a class annotated with @SpringBootApplication, do NOT register it as a bean." This is a defensive measure for multi-module builds.

### @ConditionalOnProperty — The Activation Gate

```
Spring's @ConditionalOnProperty evaluation flow:
═══════════════════════════════════════════════

@ConditionalOnProperty(name = "equitycart.security.enabled", havingValue = "true")

Step 1: Spring finds SecurityAutoConfig as a candidate during scanning
Step 2: Before creating the bean, Spring evaluates ALL @Conditional annotations
Step 3: OnPropertyCondition.getMatchOutcome() runs:
        │
        │── Calls environment.getProperty("equitycart.security.enabled")
        │
        │── Property resolution order (Spring Cloud Config client):
        │   1. JVM -D system properties
        │   2. OS environment variables (EQUITYCART_SECURITY_ENABLED)
        │   3. bootstrap.yml / application.yml (local)
        │   4. Config Server: application.yml (shared across all services)
        │   5. Config Server: {service-name}.yml (service-specific)
        │   6. Config Server: {service-name}-{profile}.yml
        │
        │── Resolution: found in order-service.yml → value = "true"
        │
        │── Compare: value.equalsIgnoreCase(havingValue) → "true" == "true" → MATCH
        │
        └── Outcome: MATCH → bean is created

        If property missing and matchIfMissing NOT set (default false):
        │
        └── Outcome: NO MATCH → bean definition SKIPPED silently
            No error, no warning, no log line. The class exists but is never instantiated.
            This is why "security not working" is hard to debug — it fails silently.
```

### Diagnostic: How to Confirm Beans Are Loaded

```bash
# Method 1: Actuator beans endpoint (if available)
GET http://localhost:8088/actuator/beans
# Search for "securityAutoConfig" in the JSON response

# Method 2: Startup logs (with DEBUG logging)
# In order-service.yml:
logging:
  level:
    org.springframework.context.annotation: DEBUG
    org.springframework.boot.autoconfigure.condition: DEBUG

# Look for:
#   "SecurityAutoConfig matched" (bean created)
#   "SecurityAutoConfig did not match" (condition failed — property missing!)

# Method 3: Add a log statement to SecurityAutoConfig's @Bean method
# If you see "Enabling JWT-based security auto-configuration" at startup → it's working
```

### Interview Questions

**Q: "What is the difference between @ComponentScan and @EntityScan?"**
A: They serve completely different Spring subsystems. @ComponentScan finds @Component/@Configuration/@Service beans and registers them in the ApplicationContext for dependency injection. @EntityScan finds @Entity/@MappedSuperclass classes and registers them with Hibernate's MetamodelFactory for ORM mapping. Having one does NOT imply the other. A class annotated @Entity will NOT become a Spring bean via @EntityScan.

**Q: "Your commons module has @Component classes. Why don't they load automatically when another service depends on it?"**
A: Gradle `implementation project(':commons')` only puts .class files on the classpath. Spring's @ComponentScan (from @SpringBootApplication) defaults to scanning only the main class's package tree. If the main class is in `com.equitycart.order`, Spring will never look at `com.equitycart.commons` unless explicitly told to. The classes are available (you can `new` them) but are never Spring-managed (no DI, no proxies, no lifecycle).

**Q: "How do Spring Boot starters get their configuration loaded without @ComponentScan?"**
A: Via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Spring Boot's auto-configuration mechanism reads this file at startup and processes the listed classes regardless of package structure. This is separate from @ComponentScan — it's how `spring-boot-starter-data-jpa` registers HibernateJpaAutoConfiguration without you scanning `org.springframework.boot.**`. Custom modules can use the same mechanism (Option B in our design), but explicit @ComponentScan (Option A) is simpler for learning projects.

**Q: "What happens if @ConditionalOnProperty's property is missing and matchIfMissing is not set?"**
A: The condition evaluates to NO MATCH. The bean definition is silently skipped — no error, no warning, no log message at INFO level. The class exists on the classpath but is never instantiated. This is the most common cause of "my configuration isn't applying" bugs in Spring Boot. Enable DEBUG logging on `org.springframework.boot.autoconfigure.condition` to see match/no-match decisions.

---

## Section 11: Reactive Response Lifecycle — ReadOnlyHttpHeaders and ServerHttpResponseDecorator

### The Problem: UnsupportedOperationException on Response Headers

In Spring Cloud Gateway (WebFlux/Netty), a common pattern for modifying response headers is:

```java
chain.filter(exchange).then(Mono.fromRunnable(() -> {
    exchange.getResponse().getHeaders().add("X-Correlation-Id", correlationId);
}));
```

This throws `UnsupportedOperationException` at runtime. The response headers work fine for requests that return small bodies but ALWAYS fail.

### Root Cause: Response Commit Lifecycle

In reactive Netty (unlike Servlet/Tomcat), response headers are flushed to the network wire as part of the FIRST `writeWith()` call — the moment the first byte of the response body is written:

```
1. Gateway receives downstream response
2. First body chunk arrives → writeWith(Publisher<DataBuffer>) called
3. Headers are serialized and flushed to client (HTTP/1.1 headers come before body)
4. Headers become ReadOnlyHttpHeaders (wrapper that throws on mutation)
5. Body chunks continue streaming
6. .then() runs AFTER the entire body is written → headers are already ReadOnly → BOOM
```

The `.then(Mono.fromRunnable(...))` callback executes after the response Mono completes — meaning the ENTIRE body has been written. By that point, headers were sealed in step 4.

### The Fix: ServerHttpResponseDecorator

```java
ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        getHeaders().add("X-Correlation-Id", correlationId);  // Headers still mutable here
        return super.writeWith(body);                          // THEN flush to wire
    }
};
```

The decorator intercepts the write call BEFORE it happens. At the point `writeWith()` is called, headers have NOT been committed yet — they're still mutable. The decorator adds the header, then delegates to the real `writeWith()` which commits headers + streams the body.

**Three override points cover all response types:**

- `writeWith()` — normal responses with a body (200 with JSON)
- `writeAndFlushWith()` — SSE/streaming responses (Server-Sent Events)
- `setComplete()` — empty-body responses (204 No Content, 304 Not Modified, redirects)

### Servlet vs Reactive Response Models

| Aspect               | Servlet (Tomcat)                                           | Reactive (Netty)                        |
| -------------------- | ---------------------------------------------------------- | --------------------------------------- |
| Response type        | `HttpServletResponse`                                      | `ServerHttpResponse`                    |
| Header mutability    | Mutable until `response.flushBuffer()` or `writer.flush()` | Mutable until first `writeWith()` call  |
| Lifecycle hook       | `HandlerInterceptor.afterCompletion()`                     | `chain.filter().then()` (too late!)     |
| Correct interception | `OncePerRequestFilter` (headers still open in doFilter)    | `ServerHttpResponseDecorator`           |
| Headers after commit | Silently ignored (Tomcat)                                  | `UnsupportedOperationException` (Netty) |

### Interview Questions

**Q: "You're adding a response header in Spring Cloud Gateway but getting UnsupportedOperationException. What's happening?"**
A: The response headers have already been committed (flushed to the wire). In WebFlux/Netty, headers become read-only after the first byte of the body is written. Using `.then()` or `doOnSuccess()` runs AFTER the response is complete — too late. The fix is `ServerHttpResponseDecorator`: override `writeWith()` to inject the header BEFORE delegating to the real write, while headers are still mutable.

**Q: "What's the difference between OncePerRequestFilter and ServerHttpResponseDecorator?"**
A: They solve the same problem (modifying responses) in different programming models. `OncePerRequestFilter` is Servlet API — it wraps the synchronous request/response lifecycle, headers are available after `chain.doFilter()` returns (response not yet committed in most cases). `ServerHttpResponseDecorator` is WebFlux — it wraps the reactive response publisher, intercepting the moment bytes are about to be written. You cannot mix them: Gateway runs on Netty (no Servlet), downstream services run on Tomcat (no reactive decorators).

---

## Section 12: HttpURLConnection PATCH Limitation and feign-hc5

### The Problem: Invalid HTTP method: PATCH

When a Feign client declares a `@PatchMapping` method and the default HTTP transport is used, the call fails with:

```
java.net.ProtocolException: Invalid HTTP method: PATCH
```

### Root Cause: HttpURLConnection (JDK Default)

OpenFeign's default HTTP client is `java.net.HttpURLConnection` — a class written in the late 1990s (JDK 1.1). It only supports the methods defined in HTTP/1.0 + original RFC 2068:

- GET, POST, PUT, DELETE, HEAD, OPTIONS, TRACE

The PATCH method was defined in RFC 5789 (2010). Sun/Oracle never updated `HttpURLConnection` to support it. The method validation is a hardcoded `switch` statement that rejects any unrecognized verb.

### The Fix: feign-hc5 (Apache HttpClient 5)

```gradle
implementation 'io.github.openfeign:feign-hc5'
```

This replaces OpenFeign's HTTP transport with Apache HttpClient 5, which supports all standard HTTP methods including PATCH. Spring Cloud OpenFeign auto-detects `feign-hc5` on the classpath and configures it automatically (no @Bean needed).

**Historical progression of Feign HTTP clients:**

- `feign-httpclient` (Apache HttpClient 4) — legacy, works but older API
- `feign-okhttp` (OkHttp 3/4) — popular, good HTTP/2 support
- `feign-hc5` (Apache HttpClient 5) — current recommendation, modern async API

### Interview Questions

**Q: "Your PATCH endpoint works with Postman but fails through Feign. What's the issue?"**
A: OpenFeign defaults to `java.net.HttpURLConnection`, which predates RFC 5789 and doesn't support PATCH. Postman uses its own HTTP stack (Chromium's). Fix: add `feign-hc5` dependency — it replaces the transport with Apache HttpClient 5 which handles all standard methods. No code changes needed; auto-configured by Spring Cloud OpenFeign.

---

## Section 13: Kafka Consumer Thread Authentication Context

### The Problem

Spring Security's `SecurityContextHolder` uses `ThreadLocal` (MODE_THREADLOCAL by default). When a Kafka consumer thread processes a message:

- No `DispatcherServlet` involved → no `RequestContextHolder` attributes
- No `JwtAuthenticationFilter` ran → no `SecurityContext` set
- Any `@PreAuthorize` check returns false → 403
- Any Feign call through `FeignAuthorizationInterceptor` has no token to propagate

### Thread Context Availability Matrix

| Thread Type                | RequestContextHolder         | SecurityContextHolder        | MDC (Log4j)           |
| -------------------------- | ---------------------------- | ---------------------------- | --------------------- |
| Tomcat HTTP thread         | ✓ (set by DispatcherServlet) | ✓ (set by JwtAuthFilter)     | ✓ (set by MdcFilter)  |
| Kafka consumer thread      | ✗ (null)                     | ✗ (empty)                    | ✗ (must set manually) |
| @Async thread              | ✗ (not inherited)            | ✗ (not inherited by default) | ✗ (not inherited)     |
| @Scheduled thread          | ✗ (null)                     | ✗ (empty)                    | ✗ (must set manually) |
| CompletableFuture.runAsync | ✗ (null)                     | ✗ (not inherited)            | ✗ (not inherited)     |

### Solutions by Context

| Need                           | Solution                                                                                                           |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| Feign calls from Kafka         | `ServiceTokenProvider` (generates fresh JWT)                                                                       |
| SecurityContext in @Async      | `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` or `DelegatingSecurityContextExecutorService` |
| MDC in Kafka                   | Manually set `ThreadContext.put("correlationId", ...)` from message header                                         |
| RequestContextHolder in @Async | Pass extracted values before spawning async task                                                                   |

### Interview Questions

**Q: "A Kafka consumer calls a Feign client to another service. The call fails with 403. Why?"**
A: Kafka consumer threads are managed by Spring Kafka's `ConcurrentMessageListenerContainer`, not by Tomcat's `DispatcherServlet`. They never pass through the servlet filter chain, so `RequestContextHolder` is null and `SecurityContextHolder` is empty. The `FeignAuthorizationInterceptor` finds no Authorization header to propagate → downstream service receives unauthenticated request → 403. Fix: detect the missing context in the interceptor and fall back to a `ServiceTokenProvider` that generates a machine-identity token.

**Q: "Why does SecurityContextHolder use ThreadLocal instead of something that works across threads?"**
A: ThreadLocal provides thread-safety without synchronization — each thread's context is isolated. Using InheritableThreadLocal would propagate to child threads but creates a security risk: if a thread pool reuses a thread with stale security context, a request could execute with another user's identity. The framework chooses safety over convenience, requiring explicit propagation where needed (`DelegatingSecurityContextExecutor`).

---

## Section 14: The @Component Filter Registration Trap — Servlet vs WebFlux

### The Bug That Appeared in Phase 8

When `JwtAuthenticationFilter` (a `OncePerRequestFilter`) had `@Component`, services started in `mode=oauth2` still ran the HS256 validation filter — rejecting RS256 tokens before Spring Security's OAuth2 Resource Server could process them.

Root cause: **`@Component` on a Servlet filter does two things simultaneously in Spring Boot**, and only one of them is intentional.

---

### What @Component on a Servlet Filter Actually Does

```
INTENT:    Register the filter with Spring's IoC container (dependency injection)
SIDE EFFECT: Spring Boot's FilterRegistrationBean auto-registration runs it
             OUTSIDE the SecurityFilterChain, as a raw servlet filter
```

**The mechanism step-by-step:**

```
STEP 1: Spring Boot starts up
        → ComponentScan finds JwtAuthenticationFilter (has @Component)
        → Creates a bean in ApplicationContext

STEP 2: Spring Boot auto-configuration fires
        → SecurityFilterAutoConfiguration runs
        → Detects ALL beans of type javax.servlet.Filter / jakarta.servlet.Filter
        → For each one found: creates a FilterRegistrationBean wrapping it
        → FilterRegistrationBean registers the filter directly with Tomcat

STEP 3: Tomcat's filter pipeline now has:
        [JwtAuthenticationFilter]  ← registered by FilterRegistrationBean (order: Integer.MAX_VALUE)
        [DelegatingFilterProxy → FilterChainProxy]  ← Spring Security's entry point
               └── SecurityFilterChain
                   ├── UsernamePasswordAuthenticationFilter
                   ├── BearerTokenAuthenticationFilter  ← OAuth2 Resource Server
                   └── ... other security filters

STEP 4: On every HTTP request:
        Tomcat runs ALL its registered filters in order
        → JwtAuthenticationFilter runs FIRST (raw HS256 validation)
        → RS256 token fails HS256 signature check → 401 returned immediately
        → DelegatingFilterProxy NEVER REACHED
        → BearerTokenAuthenticationFilter NEVER RUNS
```

**Visual: @Component PRESENT (broken)**

```
HTTP Request
     │
     ▼
[Tomcat Pipeline]
     │
     ├─→ [JwtAuthenticationFilter]  ← FilterRegistrationBean put it HERE
     │         │ RS256 token → HS256 check fails → 401
     │         ✗ STOPS HERE
     │
     └─→ [DelegatingFilterProxy]    ← never reached
              └─→ [SecurityFilterChain]
                       └─→ [BearerTokenAuthenticationFilter]  ← never runs
```

**Visual: @Component REMOVED (correct)**

```
HTTP Request
     │
     ▼
[Tomcat Pipeline]
     │
     └─→ [DelegatingFilterProxy]    ← only registered filter
              └─→ [SecurityFilterChain]
                       └─→ [BearerTokenAuthenticationFilter]
                                 │ RS256 → JWKS → validates ✓
                                 │ SecurityContext populated
                                 ▼
                       [Controller method executes]
```

---

### The @ConditionalOnMissingBean Nuance

`SecurityFilterAutoConfiguration` does NOT unconditionally register every `@Component` Filter. Spring Boot checks `FilterRegistrationBean` is not already provided:

```
if (no FilterRegistrationBean for this filter exists AND filter is @Component)
    → auto-register via FilterRegistrationBean

if (FilterRegistrationBean already exists in context)
    → skip (developer controls registration)
```

This is why the CORRECT pattern when you DO want a filter in the SecurityFilterChain is:

| Approach                                                                  | Result                                                                     |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `@Component` alone                                                        | Auto-registered by FilterRegistrationBean OUTSIDE SecurityFilterChain      |
| `@Component` + define a `FilterRegistrationBean` with `setEnabled(false)` | Prevents auto-reg; only SecurityFilterChain places it                      |
| Remove `@Component`                                                       | Bean not in IoC; manually placed via `addFilterBefore()` in SecurityConfig |
| `@Bean` in `@Configuration` without `FilterRegistrationBean`              | Same trap as @Component — Spring treats @Bean Filter the same way          |

In EquityCart's case, `JwtAuthenticationFilter` is wired into `SecurityAutoConfig.securityFilterChain()` via `addFilterBefore()`. The filter does NOT need `@Component` — `SecurityAutoConfig` creates it via `new JwtAuthenticationFilter(...)` and adds it directly to the chain.

---

### WebFlux Gateway: @Component Behaves Differently

This is why `SecurityHeadersGlobalFilter` DOES need `@Component` at the Gateway:

```
Servlet (Tomcat):
@Component Filter → FilterRegistrationBean auto-registers → runs OUTSIDE SecurityFilterChain
                                                                    ↑ BUG SOURCE

WebFlux (Netty):
@Component GlobalFilter → GatewayAutoConfiguration collects all GlobalFilter beans
                        → registers them in WebFilterChainProxy's ordered chain
                        → runs as part of the reactive filter pipeline ✓
```

No `FilterRegistrationBean` exists in WebFlux — there is no Servlet API. Spring Cloud Gateway's `GatewayAutoConfiguration` uses a simple `@Autowired List<GlobalFilter>` to collect all `@Component`-annotated GlobalFilters and merge them into the chain.

**Comparison table:**

|                              | Servlet Filter + @Component                            | WebFlux GlobalFilter + @Component                    |
| ---------------------------- | ------------------------------------------------------ | ---------------------------------------------------- |
| Registration mechanism       | `FilterRegistrationBean` (Spring Boot auto-config)     | `GatewayAutoConfiguration` autowired list            |
| Runs outside security chain? | YES (bug trap)                                         | NO (no Servlet concept of "outside")                 |
| Correct pattern for security | Remove @Component, wire manually in SecurityConfig     | @Component works fine                                |
| Discovery mechanism          | `SecurityFilterAutoConfiguration` detects Filter beans | GatewayAutoConfiguration collects GlobalFilter beans |

---

### Interview Questions

**Q: "Your JwtAuthenticationFilter implements OncePerRequestFilter and has @Component. It's also wired into the SecurityFilterChain via addFilterBefore(). How many times does it run per request?"**
A: Twice. `FilterRegistrationBean` auto-registration (triggered by `@Component`) registers it directly in Tomcat's filter pipeline. The `addFilterBefore()` call places it inside the `SecurityFilterChain` as well. The result: the filter runs once outside Spring Security's chain and once inside it. To run it only inside the SecurityFilterChain, remove `@Component` and let `SecurityAutoConfig` own the bean creation.

**Q: "What is FilterRegistrationBean and when does it matter?"**
A: `FilterRegistrationBean` is a Spring Boot abstraction that wraps a `jakarta.servlet.Filter` and registers it with the embedded Tomcat (or Jetty/Undertow) servlet container. Spring Boot's `SecurityFilterAutoConfiguration` auto-creates one for every `@Component` Filter it finds. This is separate from Spring Security's `SecurityFilterChain`. Tomcat's pipeline runs all `FilterRegistrationBean`-registered filters first, THEN the `DelegatingFilterProxy` which delegates into the `SecurityFilterChain`. If your security filter lands in both places, you have a double-run and potential bypass.

**Q: "Does the same @Component trap apply to Spring Cloud Gateway GlobalFilters?"**
A: No. Spring Cloud Gateway runs on Netty (WebFlux), not Tomcat. There is no Servlet container, no `FilterRegistrationBean`, and no `SecurityFilterAutoConfiguration`. `GatewayAutoConfiguration` collects all `@Component GlobalFilter` beans via Spring's standard `@Autowired List<GlobalFilter>` injection and places them in the reactive filter chain. `@Component` is the CORRECT and REQUIRED registration mechanism for GlobalFilters.

**Q: "How would you diagnose whether a filter is running outside the SecurityFilterChain?"**
A: Three ways. (1) Enable `DEBUG` logging on `org.springframework.security` — log shows which filters the `SecurityFilterChain` includes; compare against your filter's actual logs to see if it fires before them. (2) Set a breakpoint or log statement in the filter; check the call stack — if you see `FilterRegistrationBean` or Tomcat's `ApplicationFilterChain` in the stack (without `DelegatingFilterProxy`), it's outside. (3) Add `http.addFilterBefore()` and temporarily throw an exception inside it — if the exception occurs twice per request, you have double registration.

---

## Section 15: OAuth2 Resource Server Auto-Configuration — NimbusJwtDecoder Internals

### What spring-boot-starter-oauth2-resource-server Does

```
Dependency added: spring-boot-starter-oauth2-resource-server

Spring Boot's auto-configuration machinery:
    reads META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    finds: OAuth2ResourceServerAutoConfiguration

OAuth2ResourceServerAutoConfiguration:
    @ConditionalOnClass(BearerTokenAuthenticationToken.class)  ← resource server on classpath
    @ConditionalOnWebApplication
    ↓
    Delegates to:
        OAuth2ResourceServerJwtConfiguration
        OAuth2ResourceServerOpaqueTokenConfiguration

OAuth2ResourceServerJwtConfiguration:
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
     OR
    @ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")
    ↓
    Creates: JwtDecoder bean (NimbusJwtDecoder internally)
    Creates: BearerTokenAuthenticationFilter (added to SecurityFilterChain)
```

**What the `JwtDecoder` bean does:**

```
NimbusJwtDecoder creation from jwk-set-uri:
    new NimbusJwtDecoder(JWKSource from JWKS endpoint)
    ↓
    STEP 1: On first JWT validation request
            → HTTP GET http://keycloak:8080/realms/equitycart/protocol/openid-connect/certs
            → Response: { "keys": [{ "kid": "abc123", "kty": "RSA", "n": "...", "e": "..." }] }
            → JWKSet cached in JWKSource (DefaultRemoteJWKSet)

    STEP 2: On each JWT arrival
            → Parse JWT header: { "alg": "RS256", "kid": "abc123" }
            → Lookup cached key by kid = "abc123"
            → Reconstruct RSAPublicKey from "n" and "e" fields
            → Verify RS256 signature using RSAPublicKey
            → Parse claims, validate exp, nbf

    STEP 3: Key rotation (automatic)
            → If kid not found in cache → re-fetch JWKS endpoint
            → Cache updated → validation continues
            → Zero application restarts needed
```

---

### jwk-set-uri vs issuer-uri — Why Docker Forced Our Hand

| Property      | Effect                                                                                                                                                |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jwk-set-uri` | Fetches keys from exact URL provided. No issuer validation. Services trust whatever keys come from the URI.                                           |
| `issuer-uri`  | Calls `.well-known/openid-configuration` discovery endpoint. Downloads JWKS URI + validates `iss` claim in every token matches the configured issuer. |

**Why `issuer-uri` fails in Docker:**

```
Keycloak inside Docker container:
    → advertises itself as: http://keycloak:8080 (Docker internal hostname)
    → OIDC discovery document: { "issuer": "http://keycloak:8080/realms/equitycart" }

Spring services also inside Docker:
    → configured with: spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak:8080/...
    → this WORKS for Docker-internal service-to-service communication

BUT: if services were on host and Keycloak on Docker (typical dev setup):
    → configured with: issuer-uri=http://localhost:8180/...
    → token acquired via: http://localhost:8180/realms/equitycart/...
    → token 'iss' claim: "http://keycloak:8080/realms/equitycart"  ← Docker hostname!
    → configured issuer: "http://localhost:8180/realms/equitycart"  ← host hostname!
    → MISMATCH → "The iss claim is not valid" → 401 on EVERY request

FIX: Use jwk-set-uri instead of issuer-uri
    → spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/.../certs

---

## 11. Redis Distributed Locking & Cache Management — Topic 3 Flash Sale Pattern

### 11.1 Redis SET NX EX for Distributed Locks

**Problem:** How do you protect a shared resource (product inventory, limited slots) across multiple service instances from concurrent over-mutation?

JVM `synchronized` blocks don't span multiple processes. Database row locks serialize all requests (poor UX under burst). Solution: Redis as external coordination point.

**Mechanism:**

```java
// Acquisition
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(
        "flash-sale:lock:123",      // key (product-scoped)
        "{ownerToken}",              // value (must be unique per holder)
        10,                           // TTL
        TimeUnit.SECONDS              // auto-expires on crash
    );
// Returns true if key didn't exist (you own lock)
// Returns false if key existed (someone else owns lock)

// Release (Lua script for atomic ownership check)
String script = "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                "  redis.call('DEL', KEYS[1]) " +
                "  return 1 " +
                "else " +
                "  return 0 " +
                "end";

Boolean released = redisTemplate.execute(
    new DefaultRedisScript<>(script, Boolean.class),
    List.of("flash-sale:lock:123"),
    "{ownerToken}"                  // must match acquisition token
);
```

**Why Lua for release?**

Without Lua, a race condition exists:

```
Thread A (old owner):
  1. GET key → "{tokenA}"
  2. [crash for 5 seconds]
  3. [recover]
  4. DEL key → ✓ (but this is WRONG — key is owned by Thread B now)

Thread B (new owner):
  1. [5 seconds pass, TTL expires]
  2. SET NX key "{tokenB}" → success
  3. Acquires lock, does work
  
Thread A wakes up:
  4. DEL key → DELETES Thread B'S LOCK !!!
  5. Thread C now acquires → data corruption
```

Lua fixes by making (GET + compare + DELETE) atomic in Redis:

```lua
if redis.call('GET', key) == "{tokenA}" then DELETE
else IGNORE
```

### 11.2 Spring @Cacheable, @CacheEvict, @Caching

**@Cacheable — Conditional Caching:**

```java
@Cacheable(value = "product", key = "#productId")
public Product getProduct(Long productId) {
    return productRepository.findById(productId)
        .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
}
```

Behavior:
```
Request 1: productId=123 → cache miss → query DB → cache result → return
Request 2: productId=123 → cache hit → return cached → DB not queried
Request 3: productId=456 → cache miss (different key) → query DB → cache
```

**Cache key generation:**
- `value` = cache name (logical grouping)
- `key` = cache key within that value (usually based on method parameters)
- Composite key example: `#userId + ':' + #productId`

**@CacheEvict — Invalidation:**

```java
@CacheEvict(value = "product", key = "#productId")
public void updateProduct(Long productId, Product updated) {
    productRepository.save(updated);
    // After method completes, cache entry for this productId is removed
}
```

Multiple caches in one annotation:

```java
@CacheEvict(value = "product", key = "#productId")
@CacheEvict(value = "products", allEntries = true)  // Invalidate entire cache
public void deductStock(Long productId, Integer quantity) {
    // After deduction:
    // - GET /api/products/{productId} misses cache → fresh query
    // - GET /api/products misses cache → fresh query (entire list)
}
```

**Why `allEntries = true` on "products"?**

```
Scenario: Product AAPL in "products" list cache

Deduction: AAPL stock 10 → 9
  Query 1: @Cacheable("product", key="123") → cache evicted (specific key)
  Query 2: @Cacheable("products") → LIST returns AAPL with stock=10 (STALE!)
  
User sees stock=10 but actual is 9 → bad UX (shows availability that's sold out)

With allEntries=true:
  Both specific + list cache evicted
  Next /api/products query forced to rebuild from DB
  List includes accurate stock=9
```

### 11.3 Spring Caching Mechanism — How Proxies Work

```java
@Service
public class ProductServiceImpl {
    
    @Cacheable("product")
    public Product getProduct(Long productId) {
        log.info("Querying DB for productId={}", productId);
        return repository.findById(productId).orElseThrow();
    }
}
```

**Execution path:**

```
request → Spring proxy
    → checks cache for key (productId=123)
    → if hit: return cached value, DON'T call real method
    → if miss: call real method → save result in cache → return result

Caller never knows method was cached — same interface, different performance.
```

**Critical gotcha — Self-invocation bypasses cache:**

```java
@Service
public class OrderServiceImpl {
    
    @Transactional
    public Order placeOrder(OrderRequest req) {
        Product product = getProduct(req.productId());  // ← CALLS THIS.METHOD()
        // ...
    }
    
    @Cacheable("product")
    public Product getProduct(Long productId) {
        return productRepository.findById(productId).orElseThrow();
    }
}
```

**Problem:** `getProduct()` is NOT cached because `this.method()` calls bypass the proxy.

**Solution:** Extract into separate @Service bean:

```java
@Service
public class ProductLoaderService {
    @Cacheable("product")
    public Product getProduct(Long productId) {
        return productRepository.findById(productId).orElseThrow();
    }
}

@Service
public class OrderServiceImpl {
    private ProductLoaderService productLoader;  // Injected bean
    
    @Transactional
    public Order placeOrder(OrderRequest req) {
        Product product = productLoader.getProduct(req.productId());  // ← proxy called
    }
}
```

Now `productLoader.getProduct()` routes through the proxy → caching works.

### 11.4 Cache Invalidation Timing — Dual-Layer Strategy (Topic 3)

**Pattern: Invalidate AFTER database write commits**

```java
@Caching(evict = {
    @CacheEvict(value = "products", allEntries = true),
    @CacheEvict(value = "product", key = "#productId")
})
@Transactional
public void deductStock(Long productId, Integer quantity) {
    Product product = productRepository.findById(productId).orElseThrow();
    product.setQuantity(product.getQuantity() - quantity);
    productRepository.save(product);  // ← DB commit happens here
    // After method returns and transaction commits, @CacheEvict fires
}
```

**Why after, not before?**

```
WRONG: Evict cache → call method

@CacheEvict(...)  // ← Fires BEFORE method
public void deductStock(...) {
    product.setQuantity(quantity - 1);
    save(product);  // May fail (FK violation, timeout)
}

If save() fails:
  - Cache is already evicted
  - Database still has old quantity
  - Next GET cache-misses → queries DB → reads old quantity
  - User thinks deduction didn't work, retries
  - Retry succeeds → double deduction

CORRECT: Method runs → DB commits → evict cache

public void deductStock(...) {  // Method runs first
    product.setQuantity(quantity - 1);
    save(product);  // Succeeds
}
// After method returns and TX commits, @CacheEvict fires
// → Next GET cache-misses → DB query returns new quantity
// → No double deduction
```

**Ordering guarantee:** Spring's @Transactional and @CacheEvict are both proxies; Spring ensures @CacheEvict fires AFTER transaction commits (via TransactionSynchronizationManager).

### 11.5 Redis Connection Configuration

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 60000  # 60 second socket timeout
      lettuce:
        pool:
          max-active: 8        # Max connections in pool
          max-idle: 8          # Max idle connections
          min-idle: 0          # Min idle (can go to 0)
          max-wait: -1ms       # Wait indefinitely for a connection
```

**Why Lettuce (not Jedis)?**

- Lettuce: async, non-blocking, uses Netty → better under high load, connection pooling automatic
- Jedis: sync/blocking, requires thread per connection → resource-intensive

For flash sale under 100s of concurrent requests, Lettuce is essential.

### 11.6 Lock Acquisition Metrics (Topic 3)

```java
private MeterRegistry meterRegistry;

private boolean acquireFlashSaleLock(Long productId, String token) {
    Timer.Sample sample = Timer.start(meterRegistry);
    int attempts = 0;
    
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        attempts++;
        boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, token, 10, TimeUnit.SECONDS);
        
        if (acquired) {
            sample.stop(Timer.builder("flash-sale.lock.acquisition-time")
                .description("Time to acquire flash-sale lock")
                .tag("productId", productId.toString())
                .tag("attempts", String.valueOf(attempts))
                .register(meterRegistry));
            return true;
        }
        
        if (attempt < MAX_ATTEMPTS) {
            Thread.sleep(50 * attempt);
        }
    }
    
    meterRegistry.counter("flash-sale.lock.failures", "productId", productId.toString())
        .increment();
    return false;
}
```

**What to monitor:**
- `flash-sale.lock.acquisition-time` histogram: p50/p95/p99 latency (should stay <50ms on first attempt)
- `flash-sale.lock.failures` counter: how often requests exceed max retries
- Alert if p99 > 300ms: indicates Redis saturation or network issues

    → Direct key fetch — no issuer validation
    → Tokens validate correctly regardless of hostname mismatch
```

---

### Custom JwtAuthenticationConverter — Maintaining Backward Compatibility

Spring's `BearerTokenAuthenticationFilter` creates the `Authentication` object using a `JwtAuthenticationConverter`. The default converter sets `authentication.getPrincipal()` to the `Jwt` object itself.

**Problem:** Existing controllers use:

```java
(Long) authentication.getPrincipal()  // expects Long userId
```

With default OAuth2 converter, this ClassCastException at runtime.

**Solution: Custom converter** that extracts `userId` attribute from Keycloak JWT and wraps in `UsernamePasswordAuthenticationToken(Long userId, ...)`:

```
Incoming Keycloak JWT claims:
{
  "sub": "f7a1c23d-...",   ← Keycloak's UUID (NOT our userId)
  "userId": "1",           ← userId attribute mapped by Keycloak realm mapper
  "roles": ["CUSTOMER"]    ← flat roles claim (mapped by roles-mapper in realm config)
}

Custom KeycloakJwtAuthenticationConverter:
    STEP 1: Read claims.get("userId") → "1"
    STEP 2: Long.parseLong("1") → 1L
    STEP 3: Read claims.get("roles") → ["CUSTOMER"]
    STEP 4: Convert to GrantedAuthority list: [ROLE_CUSTOMER]
    STEP 5: return new UsernamePasswordAuthenticationToken(1L, null, authorities)

Result: authentication.getPrincipal() == 1L (Long)
        → Existing controllers unchanged
        → Zero migration cost
```

**Reactive variant (Gateway):** The converter must return `Mono<AbstractAuthenticationToken>` instead of `AbstractAuthenticationToken`:

```
Servlet (Tomcat):
    JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken>
    → synchronous: return new UsernamePasswordAuthenticationToken(...)

WebFlux (Netty):
    Converter<Jwt, Mono<AbstractAuthenticationToken>>
    → reactive: return Mono.just(new UsernamePasswordAuthenticationToken(...))
    → WHY: AuthenticationWebFilter.authenticate() does .flatMap(converter::convert)
    → flatMap REQUIRES a Mono return — synchronous Converter cannot be used here
```

---

### NimbusJwtDecoder vs NimbusReactiveJwtDecoder

|                      | NimbusJwtDecoder                         | NimbusReactiveJwtDecoder                            |
| -------------------- | ---------------------------------------- | --------------------------------------------------- |
| Programming model    | Blocking (Servlet)                       | Non-blocking (WebFlux)                              |
| HTTP client for JWKS | `RestTemplate` (blocking)                | `WebClient` (reactive)                              |
| Integration point    | `JwtDecoder` bean in SecurityFilterChain | `ReactiveJwtDecoder` bean in SecurityWebFilterChain |
| Thread behavior      | Blocks HTTP thread while fetching JWKS   | Returns Mono — caller subscribes asynchronously     |
| Used at              | Services (order, product, etc.)          | API Gateway                                         |
| Auto-config triggers | `OAuth2ResourceServerJwtConfiguration`   | `ReactiveOAuth2ResourceServerJwtConfiguration`      |

---

### Interview Questions

**Q: "How does Spring Boot know to use Keycloak's public keys without you writing any JWKS-fetching code?"**
A: `spring-boot-starter-oauth2-resource-server` auto-configures `NimbusJwtDecoder` when `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` is set. Internally, `DefaultRemoteJWKSet` fetches the JWKS endpoint on first token validation, caches the key set, and performs `kid`-based lookup for each JWT. If a `kid` is missing from the cache (key rotation), it re-fetches automatically. You write zero JWKS code — the framework handles caching, rotation, and RSA reconstruction.

**Q: "Why does changing from issuer-uri to jwk-set-uri fix the 'iss claim is not valid' error?"**
A: `issuer-uri` triggers OIDC discovery (`/.well-known/openid-configuration`) and validates that every token's `iss` claim matches the configured URI exactly. In Docker, the Keycloak container advertises its internal hostname (e.g., `keycloak:8080`) in the `iss` claim, but the service is configured with `localhost:8180`. The string comparison fails. `jwk-set-uri` skips issuer validation entirely — it only fetches public keys from the exact URL you provide. The tradeoff: no automatic discovery, and you lose issuer claim verification (acceptable in controlled dev environments, not for production).

**Q: "Why does the OAuth2 JwtAuthenticationConverter need to return Mono in WebFlux but not in Servlet?"**
A: `AuthenticationWebFilter` (WebFlux) calls the converter via `flatMap()`: `Mono<Authentication> auth = jwtDecoder.decode(token).flatMap(converter::convert)`. `flatMap` requires a function that returns a `Publisher` (Mono/Flux) — a synchronous return value would prevent composition with upstream reactive streams. In Servlet, `AbstractSecurityInterceptor` calls the converter synchronously as part of a blocking thread — no reactive composition involved, so a plain `Converter<Jwt, AbstractAuthenticationToken>` suffices.

**Q: "How does Spring automatically detect and validate JWT tokens on every request?"**
A: `BearerTokenAuthenticationFilter` (added to `SecurityFilterChain` by `OAuth2ResourceServerAutoConfiguration`) runs before your controllers. It extracts the `Authorization: Bearer <token>` header, calls `JwtDecoder.decode()` which validates the RS256 signature via JWKS, validates `exp`/`nbf` claims, then calls the `JwtAuthenticationConverter` to produce an `Authentication`. It stores this in `SecurityContextHolder`. The rest of the filter chain sees an authenticated request. If decoding fails, a 401 is returned immediately.

---

## Section 16: @EnableWebFluxSecurity vs @EnableWebSecurity — Two Separate Security Stacks

### The Two Security Stacks Cannot Mix

Spring Security has two completely independent implementations for the two web stacks:

```
Spring MVC (Tomcat/Servlet):          Spring WebFlux (Netty/Reactive):
────────────────────────────          ──────────────────────────────────
@EnableWebSecurity                    @EnableWebFluxSecurity
    ↓                                     ↓
WebSecurityConfiguration              WebFluxSecurityConfiguration
    ↓                                     ↓
FilterChainProxy                      WebFilterChainProxy
    ↓                                     ↓
SecurityFilterChain                   SecurityWebFilterChain
    ↓                                     ↓
HttpSecurity DSL                      ServerHttpSecurity DSL
    ↓                                     ↓
OncePerRequestFilter (javax.servlet)  WebFilter (org.springframework.web.server)
    ↓                                     ↓
SecurityContextHolder (ThreadLocal)   ReactiveSecurityContextHolder (Reactor Context)
    ↓                                     ↓
@EnableMethodSecurity works           @EnableMethodSecurity DOES NOT APPLY
                                      (@EnableReactiveMethodSecurity for WebFlux)
```

**Critical difference:** The two stacks have ZERO shared code in their filter/web infrastructure. You CANNOT use `HttpSecurity` in a WebFlux application — `HttpSecurity` is not on the classpath when only `spring-boot-starter-webflux` is present (no `spring-boot-starter-web`).

---

### What @EnableWebFluxSecurity Activates

```
@EnableWebFluxSecurity
    │
    ├─→ Imports WebFluxSecurityConfiguration
    │       ├─→ Creates WebFilterChainProxy bean
    │       ├─→ Injects all SecurityWebFilterChain beans (ordered)
    │       └─→ Registers WebFilterChainProxy as a WebFilter with priority -100
    │
    ├─→ Enables ReactiveAuthenticationManager infrastructure
    │       └─→ UserDetailsRepositoryReactiveAuthenticationManager (for form login)
    │           OR ReactiveJwtAuthenticationManager (for OAuth2 Resource Server)
    │
    └─→ Enables ReactiveMethodSecurityConfiguration (if @EnableReactiveMethodSecurity present)
```

**What @EnableWebFluxSecurity does NOT do:**

- Does NOT enable method security (`@PreAuthorize` still inactive without explicit enablement)
- Does NOT create any SecurityWebFilterChain — YOU must declare a `@Bean` of type `SecurityWebFilterChain`
- Does NOT interfere with Spring MVC if somehow on the same classpath (though mixing stacks is wrong)

---

### SecurityWebFilterChain vs SecurityFilterChain

|                         | SecurityFilterChain (Servlet)                                              | SecurityWebFilterChain (WebFlux)                        |
| ----------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------- |
| DSL builder             | `HttpSecurity`                                                             | `ServerHttpSecurity`                                    |
| Builds from             | `WebSecurityConfigurerAdapter` (deprecated) or `@Bean SecurityFilterChain` | `@Bean SecurityWebFilterChain`                          |
| Token validation filter | `BearerTokenAuthenticationFilter`                                          | `AuthenticationWebFilter`                               |
| Authentication storage  | `SecurityContextHolder` (ThreadLocal)                                      | `ReactiveSecurityContextHolder` (Reactor Context)       |
| Authorization filter    | `AuthorizationFilter`                                                      | `AuthorizationWebFilter`                                |
| CSRF                    | `CsrfFilter` (enabled by default)                                          | `CsrfWebFilter` (disabled by default in stateless APIs) |

---

### ReactiveSecurityContextHolder — Why ThreadLocal Cannot Work in WebFlux

```
Servlet (Tomcat — one thread per request):
    Thread-1 handles Request A entirely:
    ┌─────────────────────────────────────────┐
    │ Thread-1 ThreadLocal:                   │
    │   SecurityContextHolder: User-A context │
    │   RequestContextHolder: Request-A data  │
    └─────────────────────────────────────────┘
    ThreadLocal is safe: Thread-1 = Request-A always

WebFlux (Netty — event loop, many requests on few threads):
    EventLoop thread handles Step 1 of Request A
    EventLoop thread handles Step 1 of Request B  ← same thread!
    EventLoop thread resumes Step 2 of Request A  ← same thread again!

    If SecurityContext stored in ThreadLocal on EventLoop thread:
    - Thread handles Request-A → stores User-A in ThreadLocal
    - Same thread handles Request-B → overwrites ThreadLocal with User-B
    - Same thread resumes Request-A → reads User-B context → SECURITY BREACH

Solution: Reactor Context (per-subscription, not per-thread)
    Each reactive Mono/Flux subscription has its own Context key-value store.
    ReactiveSecurityContextHolder stores/retrieves from Reactor Context:
        Context.write(SecurityContext.class, securityContext)
    Never touches ThreadLocal — safe across thread switches.
```

---

### Why @EnableMethodSecurity Does Nothing at API Gateway

`@EnableMethodSecurity` activates AOP advice that intercepts `@PreAuthorize`/`@PostAuthorize` on Spring MVC controller methods. The API Gateway:

1. Has no `@RestController` or `@Controller` — it's a pure routing layer
2. Runs on WebFlux stack — `@EnableMethodSecurity` targets Servlet MVC AOP
3. Uses `@EnableWebFluxSecurity` — the reactive stack ignores `@EnableMethodSecurity` entirely

If method-level security were needed at the Gateway (unusual), `@EnableReactiveMethodSecurity` would be required. But authorization at the Gateway should be coarse-grained (authenticated? correct scope?) — fine-grained role checks (`@PreAuthorize("hasRole('ADMIN')")`) belong in the individual services where business context exists.

---

### Interview Questions

**Q: "You add @EnableMethodSecurity to a Spring Cloud Gateway application. @PreAuthorize annotations are on your filters. Does it work?"**
A: No. Spring Cloud Gateway runs on WebFlux (Netty), not Spring MVC. `@EnableMethodSecurity` activates an AOP proxy infrastructure tied to the Servlet stack and Spring MVC's `HandlerMethod`. WebFlux uses a different execution model (reactive pipelines, no `HandlerInterceptor`), so the Servlet-targeted AOP never fires. For method security in WebFlux, use `@EnableReactiveMethodSecurity`. But Gateway has no controllers — method security is inappropriate there. Authorization at the gateway should be via `ServerHttpSecurity.authorizeExchange()`, not annotation-based checks.

**Q: "What is the difference between SecurityContextHolder and ReactiveSecurityContextHolder?"**
A: `SecurityContextHolder` uses `ThreadLocal` — the security context is tied to the current OS thread. This works in Servlet (one thread per request), but WebFlux uses an event loop where a single thread processes many requests interleaved. ThreadLocal on an event loop thread would be overwritten by concurrent requests. `ReactiveSecurityContextHolder` stores context in the Reactor `Context` — a per-subscription key-value store that flows through the reactive operator chain regardless of which thread executes. It's immune to event-loop thread sharing because each Mono subscription carries its own context.

**Q: "Can you run both Servlet and WebFlux security in the same Spring Boot application?"**
A: Not in a meaningful way. While both can technically be on the classpath, Spring Boot's auto-configuration detects the primary web application type and activates one stack. Mixing them leads to undefined behavior — `FilterChainProxy` and `WebFilterChainProxy` have no coordination mechanism. The standard architecture is: separate your Servlet services from your reactive gateway. `@EnableWebFluxSecurity` and `@EnableWebSecurity` should never appear in the same application.

**Q: "How does SecurityWebFilterChain order affect security enforcement?"**
A: `SecurityWebFilterChain` beans can be annotated with `@Order`. The `WebFilterChainProxy` evaluates chains in ascending order and uses the FIRST chain whose `pathMatcher` matches the request. A chain with `@Order(1)` matching `/api/public/**` with no auth can short-circuit requests before `@Order(2)` matching `/**` with full auth. This enables different security policies per URL pattern without conditionals inside a single chain.

---

## 21. Phase 9 Observability Internals — Metrics, Traces, and Logging

### 21.1 Why Correlation ID Alone Was Not Enough

Correlation IDs answer: "which logs belong to this request?"  
They do **not** answer:

- Is latency increasing over time?
- Which endpoint is causing p99 spikes?
- Which service is failing most often?
- Where did the request spend time across service boundaries?

Phase 9 adds the missing three pillars:

- **Metrics** (Prometheus + Micrometer) for trends, SLOs, alerting
- **Tracing** (Micrometer Tracing + Zipkin) for cross-service request timelines
- **Structured logs** (Log4j2 JSON) for machine-queryable events

### 21.2 Spring Boot 3 Metrics Flow (Micrometer)

```
Controller/Service method
   └─ record metrics via MeterRegistry (Counter/Timer)
         └─ stored in Micrometer meter registry
               └─ exposed at /actuator/prometheus
                     └─ Prometheus scrapes periodically
                           └─ Grafana queries PromQL for dashboards/alerts
```

**Critical implementation dependency:** `/actuator/prometheus` requires the Prometheus registry dependency (`micrometer-registry-prometheus`). Actuator exposure alone is not enough.

### 21.3 Tracing Flow (Boot 3.x Preferred Path)

```
Incoming request
   └─ trace/span context created (or continued)
        └─ context propagates through HTTP client calls
             └─ spans exported to Zipkin endpoint
                  └─ Zipkin UI reconstructs full request graph
```

Boot 3.x guidance:

- Use Micrometer tracing properties
- Configure explicit Zipkin endpoint for exporter
- Keep sampling explicit in config (e.g., 1.0 in dev, lower in prod)

### 21.4 Structured Log Design Decisions

Per-service `log4j2-spring.xml` configured with:

- JSON layout for parseable logs
- stdout + rolling file appender
- MDC/correlation context included in each event

This design supports both:

- local debugging (human-friendly console)
- operational analysis tools (`core-loglens`, log shipping pipelines)

### 21.5 Alerts as Executable SLO Contracts

Phase 9 alert classes:

- **Availability:** service down (`up == 0`)
- **Reliability:** high 5xx/error ratio
- **Performance:** high p99 latency

These are not dashboard cosmetics; they are runtime enforcement of production expectations.

### 21.6 Interview Questions

**Q: "Why use both logs and metrics if metrics already show errors?"**  
A: Metrics tell you _that_ a problem exists and how big it is (rate, latency, percentile). Logs tell you _why_ it happened (stack trace, payload, domain context). They are complementary, not interchangeable.

**Q: "Why does p99 matter more than average latency?"**  
A: Average hides tail pain. Averages can look healthy while a minority of requests are extremely slow. p99 captures worst-user experience and is a better alert target for production APIs.

**Q: "What is the practical difference between correlation ID and trace ID?"**  
A: Correlation ID is usually an application-defined request identifier propagated for log grouping. Trace ID is part of standardized distributed tracing context with parent/child span relationships and timing metadata. Correlation IDs are useful for logs; trace IDs power full causal timelines.

---

## Section 22: @KafkaListener — Consumer Group Management and Offset Handling

### 22.1 The @KafkaListener Annotation

```java
@Service
public class PortfolioReadModelOutboxConsumer {
    
    @KafkaListener(
        topics = "portfolio-projection",
        groupId = "equitycart-portfolio-read-model-sync",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleProjectionEvent(
        @Payload PortfolioProjectionEvent event,
        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
        @Header(KafkaHeaders.OFFSET) long offset
    ) {
        // Process event
        portfolioReadModelSynchronizer.rebuildReadModelForUser(event.userId());
    }
}
```

**Key fields:**

- **topics:** Kafka topic(s) to subscribe to
- **groupId:** Consumer group name (determines partition assignment and offset tracking)
- **containerFactory:** Bean name of the `ConcurrentKafkaListenerContainerFactory` (optional; uses default if omitted)
- **@Payload:** deserialize message value to this type (Spring handles JSON deserialization)
- **@Header:** inject Kafka metadata (partition, offset, timestamp, topic)

### 22.2 Consumer Groups and Partition Assignment

```
Topic: portfolio-projection (3 partitions)
Consumer Group: equitycart-portfolio-read-model-sync

Scenario 1 (single consumer):
  Partition 0 → Instance A
  Partition 1 → Instance A
  Partition 2 → Instance A

Scenario 2 (two consumers):
  Partition 0 → Instance A
  Partition 1 → Instance B
  Partition 2 → Instance B (or A, depends on Kafka rebalancing algorithm)

Each partition is processed by exactly one consumer (guarantee).
```

**Rebalancing:** When a new instance joins or crashes, Kafka triggers a rebalance — partitions are reassigned to live consumers. During rebalance, the group temporarily stops processing (pause window, typically < 5 seconds).

### 22.3 Offset Management and Acknowledgment

By default, Spring Kafka uses **automatic offset commits** (commits every 5 seconds or N records):

```yaml
spring.kafka.consumer.enable-auto-commit: true
spring.kafka.consumer.auto-commit-interval: 5000  # milliseconds
```

**Flow:**

```
1. Message arrives at partition 0, offset 100
2. @KafkaListener method invoked with the message
3. Method completes successfully
4. Offset commit happens in background (or after auto-commit interval)
5. Broker records: "this consumer group has committed offset 100"
6. Next restart: consumer resumes from offset 100 (won't reprocess message 100)
```

**Manual acknowledgment (more control, riskier):**

```yaml
spring.kafka.listener.ack-mode: MANUAL_IMMEDIATE
```

```java
@KafkaListener(topics = "...")
public void handle(PortfolioProjectionEvent event, Acknowledgment ack) {
    try {
        processEvent(event);
        ack.acknowledge();  // commit only on success
    } catch (Exception e) {
        // Don't acknowledge → message will be reprocessed from last committed offset
        log.error("Failed", e);
    }
}
```

**Tradeoff:** Manual acknowledgment gives more control (commit only on success) but requires explicit code in every listener. Automatic is simpler but requires idempotent handlers (to handle retries).

**Phase 10 approach:** Automatic acknowledgment + MongoDB upsert ensures idempotency (replayed events produce same result).

### 22.4 Offset Reset Behavior

```yaml
spring.kafka.consumer.auto-offset-reset: earliest | latest | none
```

- **earliest:** On startup, if no committed offset exists, start from offset 0 (reprocess all messages). Useful for rebuilding read models.
- **latest:** Skip to the end of the topic (ignore historical messages). Useful for real-time metrics.
- **none:** Throw exception if no committed offset exists (fail-fast).

**Phase 10:** Uses `latest` for `PortfolioReadModelOutboxConsumer` (new consumers skip historical events; the separate reconciliation job handles drift).

### 22.5 Payload Type Deserialization

```java
@KafkaListener(topics = "portfolio-projection")
public void handle(@Payload PortfolioProjectionEvent event) { }
```

Spring deserializes the Kafka message value to `PortfolioProjectionEvent` using:

1. Check for `__TypeId__` header (set by Spring's `JsonSerializer` on producer side)
2. If header present, use that FQCN to instantiate the class
3. If header absent, use `spring.json.value.default.type` property to determine class
4. Deserialize JSON payload using `ObjectMapper`

**Configuration:**

```yaml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
    properties:
      spring.json.value.default.type: com.equitycart.portfolio.event.PortfolioProjectionEvent
```

### 22.6 Interview Questions

**Q: "What happens if a @KafkaListener method throws an exception?" (Phase 10)**  
A: By default (with DefaultErrorHandler), the exception triggers retries with exponential backoff. After max retries exhausted, the message is sent to a Dead Letter Topic (`.DLT` suffix). The consumer continues processing the next message. Exception does NOT propagate to crash the listener. If you want exceptions to pause consumption and retry indefinitely, configure `MINIMAL_ERROR_HANDLING` or a custom `ErrorHandler`.

**Q: "How do you ensure exactly-once processing semantics in Kafka consumers?" (Phase 10)**  
A: At-least-once (default) means messages may be reprocessed after failures. Exactly-once requires: (1) idempotent handlers (replaying same message produces same result), (2) idempotent database writes (MongoDB upsert by key), (3) offset management (commit only after successful processing). EquityCart uses: automatic offset commits + MongoDB upsert by userId → effectively exactly-once semantics.

**Q: "Why use a consumer group name instead of letting Kafka auto-generate it?" (Phase 10)**  
A: Consumer groups are identified by their name in Kafka's `__consumer_offsets` topic. Same group name resuming from old offsets (e.g., after redeployment) means continuing from where you left off. Auto-generated names (based on instance ID or UUID) create a new group every time the consumer starts → all historical messages reprocessed → rebuilding the read model from scratch. Explicit group names let you control this.

---

## Section 23: MongoTemplate — Upsert and Bulk Operations

### 23.1 MongoTemplate Setup

```java
@Configuration
public class MongoConfig {
    
    @Bean
    public MongoTemplate mongoTemplate(MongoClient mongoClient, MongoDatabaseFactory databaseFactory) {
        return new MongoTemplate(databaseFactory);
    }
}
```

Spring Boot auto-configures `MongoTemplate` if `spring-boot-starter-data-mongodb` is on the classpath. For Phase 10 CQRS read models, `MongoTemplate` is the primary API (vs Spring Data's `MongoRepository`).

### 23.2 The Upsert Operation

```java
Query query = new Query(Criteria.where("userId").is(userId));
Update update = new Update()
    .set("holdings", holdingsList)
    .set("rewards", rewardsList)
    .set("totalValue", computed)
    .set("lastUpdatedAt", Instant.now());

UpdateResult result = mongoTemplate.upsert(query, update, ReadModelPortfolio.class);
// result.getModifiedCount() → 0 or 1 (not 1+ because upsert never inserts multiple)
// result.getUpsertedId() → ObjectId if INSERT happened; null if UPDATE
```

**Behavior:**

```
Query matches 0 docs → INSERT new doc with all Update.set() fields
Query matches 1 doc  → UPDATE that doc's fields
Query matches N docs → UPDATE all N docs (risky; should use specific key)
```

**Idempotency guarantee (Phase 10):**

```
Event 1: upsert(userId, holdings) → INSERT (no doc exists)
Event 1 (retry): upsert(userId, holdings) → UPDATE (doc exists, same result)
Event 1 (3rd retry): upsert(userId, holdings) → UPDATE again (idempotent)
```

### 23.3 MongoDB Write Semantics vs SQL

```sql
-- SQL: explicit INSERT or UPDATE
INSERT INTO portfolio (userId, holdings) VALUES (...)  -- fail if userId exists
UPDATE portfolio SET holdings = ... WHERE userId = ... -- fail if userId missing

-- MongoDB: implicit upsert
db.portfolio.updateOne({ userId: "x" }, { $set: { holdings: [...] } }, { upsert: true })
// If userId exists → $set updates fields
// If userId missing → inserts new doc with userId and all $set fields
```

MongoTemplate's `upsert()` wraps MongoDB's `updateOne(..., { upsert: true })`.

### 23.4 Bulk Operations

For rebuilding multiple users' read models in one batch:

```java
BulkOperations bulk = mongoTemplate.bulkOps(BulkMode.ORDERED, ReadModelPortfolio.class);

for (String userId : userIds) {
    Query query = new Query(Criteria.where("userId").is(userId));
    Update update = new Update()
        .set("holdings", computeHoldings(userId))
        .set("rewards", computeRewards(userId));
    
    bulk.upsert(query, update);
}

BulkWriteResult result = bulk.execute();
// result.getInsertedCount() + result.getModifiedCount() = userIds.size()
```

**Ordered vs Unordered:**

- `ORDERED`: stops at first error (safest)
- `UNORDERED`: continues on error, reports all results (faster for large batches where some failures are acceptable)

### 23.5 Common Query Patterns

```java
// Find by business key
Query query = new Query(Criteria.where("userId").is(userId));

// Composite condition
Query query = new Query()
    .addCriteria(Criteria.where("userId").is(userId))
    .addCriteria(Criteria.where("status").is("VESTED"));

// Range query
Query query = new Query(Criteria.where("vestingDate").lte(LocalDateTime.now()));

// In list
Query query = new Query(Criteria.where("userId").in(userIds));

// Null check
Query query = new Query(Criteria.where("deletedAt").isNull());
```

### 23.6 Interview Questions

**Q: "Why use MongoTemplate.upsert() instead of repository.save() for CQRS projections?" (Phase 10)**  
A: `save()` always inserts if no `_id` field set, causing duplicate documents. Upsert queries by business key (userId), so it either updates an existing doc or inserts a new one — both operations produce the same end state (idempotent). Critical for Kafka at-least-once semantics where events may be retried.

**Q: "What's the difference between Query + Update vs plain `findAndReplace()`?" (Phase 10)**  
A: `findAndReplace()` does a full document replacement (equivalent to SQL DELETE + INSERT). `Query + Update` with `$set` modifies only specified fields, leaving others untouched. For CQRS projections, `upsert()` with specific `$set` fields is safer — it doesn't accidentally delete fields from a concurrent update.

**Q: "How does MongoDB enforce uniqueness in upsert?" (Phase 10)**  
A: Via unique indexes. `@Indexed(unique = true) String userId` creates a unique index on that field. During INSERT, MongoDB checks if the value already exists — if yes, rejects the insert. During UPDATE, the unique constraint is not re-checked. The pattern: add `@Indexed(unique = true)` to your business key field, and upsert queries by that field are guaranteed to create at most one document per unique value.

---

## Section 24: @Profile — Runtime Bean Activation for Feature Flags

### 24.1 Basic @Profile Usage

```java
@Configuration
@Profile("!cdc")  // active when profile is NOT "cdc"
public class OutboxPollerConfig {
    
    @Bean
    public OutboxPoller outboxPoller(OutboxEventRepository repo, KafkaTemplate<String, ?> kafka) {
        return new OutboxPoller(repo, kafka);
    }
}
```

Spring activates this `@Configuration` class only when:
- `spring.profiles.active` does NOT include `cdc`
- `spring.profiles.default` is set to something other than `cdc`

**Comparison:**

```yaml
# application.yml (default — polling mode)
spring:
  profiles:
    active: ""  # or omitted

# Result: OutboxPollerConfig ACTIVE

---

# application-cdc.yml (CDC mode overlay)
# No explicit profile setting needed; the file name is the profile

# To activate: spring.profiles.active=cdc

# Result: OutboxPollerConfig INACTIVE (because profile IS cdc)
```

### 24.2 Profile Expressions and Negation

```java
@Profile("prod")              // Active only in prod
@Profile("!prod")             // Active unless prod
@Profile("dev | staging")     // Active in dev OR staging
@Profile("!(prod | staging)") // Active unless prod or staging
@Profile("dev & !test")       // Active in dev AND NOT in test
```

### 24.3 Multi-Level Profile Activation

```
application.yml           (default values, any profile)
application-{profile}.yml (profile-specific overrides)
```

**Load order:**
1. `application.yml` (base)
2. `application-{spring.profiles.active}.yml` (overrides)

Example:

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092

# application-cdc.yml
# (empty — just activates the cdc profile)

# application-prod.yml
spring:
  kafka:
    bootstrap-servers: kafka-prod:9092
```

When `spring.profiles.active=prod`:
1. Load `application.yml` → kafka bootstrap = localhost:9092
2. Load `application-prod.yml` → kafka bootstrap = kafka-prod:9092 (overrides)
3. Result: bootstrap = kafka-prod:9092

### 24.4 Feature Flag Pattern Using @Profile + @ConditionalOnProperty

```java
@Configuration
public class SellToSpendConfig {
    
    @Bean
    @ConditionalOnProperty(name = "feature.saga-enabled", havingValue = "true", matchIfMissing = true)
    public SellToSpendSagaOrchestrator sagaOrchestrator(ServiceA svc) {
        return new SellToSpendSagaOrchestrator(svc);
    }
    
    @Bean
    @ConditionalOnProperty(name = "feature.saga-enabled", havingValue = "false")
    public SellToSpendTransactionalService transactionalService(ServiceA svc) {
        return new SellToSpendTransactionalService(svc);
    }
}
```

**Behavior:**

```yaml
# application.yml
feature.saga-enabled: true

# Result: SellToSpendSagaOrchestrator bean created
# Controller injects via interface → gets saga implementation

---

# application.yml
feature.saga-enabled: false

# Result: SellToSpendTransactionalService bean created
# Controller injects via interface → gets transactional implementation
```

### 24.5 @Profile vs @ConditionalOnProperty

| Aspect                | @Profile                              | @ConditionalOnProperty                        |
| --------------------- | ------------------------------------- | --------------------------------------------- |
| **Controlled by**     | Command line / environment variables  | application.yml properties                    |
| **Scope**             | Entire bean class or method           | Per-bean decision                             |
| **Use case**          | Deployment environment (dev/prod/cdc) | Feature flags (saga on/off, debug mode)      |
| **Override**          | `spring.profiles.active=cdc`          | `feature.saga-enabled=true` in yml            |
| **Typical pattern**   | One profile per deployment            | Multiple boolean properties per app           |

**Phase 10 pattern:**

```
@Profile("!cdc")        → OutboxPollerConfig (deployment: with/without Debezium)
@ConditionalOnProperty  → Future: feature toggles (saga on/off for A/B testing)
```

### 24.6 Interview Questions

**Q: "Why use @Profile("!cdc") instead of @Profile("polling")?" (Phase 10)**  
A: Negative profiles are more maintainable. `@Profile("!cdc")` means "use this config unless CDC is explicitly enabled." If you used `@Profile("polling")`, you'd need to explicitly set `spring.profiles.active=polling` — if someone forgets and the property is empty, neither `polling` nor `cdc` would be active, and the OutboxPoller wouldn't start. Negation makes the default explicit.

**Q: "How would you A/B test a feature flag with @ConditionalOnProperty?" (Phase 10)**  
A: Create two beans implementing the same interface, each with a different `@ConditionalOnProperty` condition. Controller injects via interface. YAML determines which bean is active: `feature.new-algorithm: true` → new bean active. This allows toggling algorithms without redeploying if properties are externalized (Config Server, environment variables).

**Q: "What happens if two @Profile-annotated beans both match?" (Phase 10)**  
A: The bean with higher `@Ordered` value wins (or arbitrary if no order specified). More commonly: use mutually-exclusive conditions (`@ConditionalOnProperty`) with `matchIfMissing` and `havingValue` to ensure exactly one bean activates.

**Q: "Can you mix @Profile and @ConditionalOnProperty on the same bean?" (Phase 10)**  
A: Yes. Both conditions must be satisfied (AND). Example: `@Profile("prod") @ConditionalOnProperty(name="feature.expensive-check", havingValue="true")` — bean active only if deployment is prod AND the feature flag is on. Useful for expensive features that should only run in production when explicitly enabled.

---

## Part 25: Spring Boot Saga Orchestration Patterns (Topic 2 Completion Learning)

### 1) Service boundary and transaction layering

In Spring Boot, saga orchestration typically sits in a dedicated `@Service` class.  
Use `@Transactional` at method level for **each step unit**, not as an assumption of end-to-end distributed atomicity.

### 2) Exception propagation strategy

Rule used in Topic 2 reasoning:

- step failure -> compensate -> persist failure status -> rethrow.

Why: `@KafkaListener` + container error handler needs the exception signal to trigger retry/DLT path.

### 3) Suggested Spring components

- `GiftSagaOrchestrator` (`@Service`) - step coordinator.
- `GiftSagaRepository` (`JpaRepository`) - saga lookup/idempotency/timeout queries.
- `GiftOutboxWriter` (`@Service`) - lifecycle event rows.
- `@Scheduled` timeout scanner - resumes/marks stale in-flight sagas.

### 4) Flow wiring diagram

```text
Controller / KafkaConsumer
          |
          v
  GiftSagaOrchestrator
   |    |     |     |
   v    v     v     v
holding repo  ledger  outbox  saga repo(status)
          |
          v
       exception?
          |
       yes -> compensate -> throw
       no  -> complete
```

### 5) Interview Q/A

**Q: "Why not annotate the whole orchestrator with one giant @Transactional?"**  
A: It gives false safety for distributed workflow boundaries. Saga correctness comes from persisted step state + compensators + retry policy, not only one transaction annotation.

**Q: "What role does @Scheduled play in saga?"**  
A: It detects stalled in-progress sagas (`updatedAt` threshold), enabling timeout handling and reducing orphaned workflow risk.
