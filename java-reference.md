# Java Language Evolution & Design Patterns — Deep Dive Reference

> Comprehensive reference covering Java's evolution from pre-8 through 21, with EquityCart examples.
> Each section includes what changed, why, how it works internally, and best practices.

---

## Part 1: Java Language Evolution

### 1.1 Pre-Java 8 Era (Java 1.0–7) — The "Verbose OOP" Period

Java before version 8 was purely object-oriented with no functional programming support. Everything required explicit class/interface implementations.

**Anonymous Inner Classes (the predecessor to lambdas):**

```
// Pre-Java 8: sorting a list required an anonymous class
Collections.sort(names, new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.compareTo(b);
    }
});
```

**Problems with pre-8 Java:**

- Boilerplate explosion: 5 lines for what should be 1 expression
- No way to pass "behavior" without wrapping it in an object
- Checked exceptions in every API (IOException on close(), etc.)
- Thread handling via `Runnable` anonymous classes
- Date/time APIs were broken (`java.util.Date` is mutable, months 0-indexed, no timezone support)
- Collections were mutable by default, no factory methods

**Best Practice (historical):** In pre-8 code, if you see `new Runnable() { ... }` or `new Comparator<>() { ... }`, these are candidates for lambda conversion during modernization.

---

### 1.2 Java 8 (2014) — The Functional Revolution

Java 8 was the most transformative release in Java's history. It introduced functional programming to a language that was purely object-oriented for 19 years.

**Why 2014?** Oracle acquired Sun Microsystems in 2010 and inherited a Java community demanding functional features (Scala, Groovy, Kotlin were gaining traction). Project Lambda (JSR 335) was the response.

#### Lambda Expressions

A lambda is an anonymous function — code that can be passed around like data.

```
// Before (anonymous inner class):
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("Running");
    }
};

// After (lambda):
Runnable r = () -> System.out.println("Running");
```

**How it works internally (invokedynamic):**

Lambdas are NOT anonymous inner classes compiled to separate `.class` files. They use `invokedynamic` (introduced in Java 7 for dynamic languages on JVM):

```
1. Compiler generates a PRIVATE STATIC method in the enclosing class containing the lambda body
2. At the lambda call site, compiler emits an invokedynamic instruction
3. First execution: JVM calls LambdaMetafactory.metafactory() — a bootstrap method
4. LambdaMetafactory generates a class at runtime (via ASM bytecode generation)
   that implements the functional interface and delegates to the static method
5. Subsequent executions: the generated class is cached — no re-creation
```

**Why not anonymous inner classes?** Each anonymous class = separate `.class` file = class loading overhead + memory for each instance. Lambdas share a single generated class per call site, and stateless lambdas can be singletons.

**EquityCart usage:**

```
// ProductSpecification.java — lambda returning Specification
public static Specification<Product> hasName(String name) {
    return (root, query, cb) -> cb.like(root.get("name"), "%" + name + "%");
}

// KafkaTemplate callback
kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
    if (ex != null) log.warn("Send failed: {}", ex.getMessage());
});
```

#### Functional Interfaces

A functional interface has exactly ONE abstract method. The `@FunctionalInterface` annotation is documentation (compiler enforces single-abstract-method rule).

| Interface           | Signature           | Purpose                        | EquityCart Example                        |
| ------------------- | ------------------- | ------------------------------ | ----------------------------------------- |
| `Function<T,R>`     | `R apply(T t)`      | Transform input → output       | Stream .map()                             |
| `Predicate<T>`      | `boolean test(T t)` | Test a condition               | Specification filters                     |
| `Consumer<T>`       | `void accept(T t)`  | Perform action, return nothing | .forEach(), .peek()                       |
| `Supplier<T>`       | `T get()`           | Provide a value (lazy)         | @Builder.Default(() -> new ArrayList<>()) |
| `BiFunction<T,U,R>` | `R apply(T t, U u)` | Two inputs → output            | Comparator                                |
| `UnaryOperator<T>`  | `T apply(T t)`      | Same type in and out           | String transformations                    |

**Best Practice:** Prefer standard functional interfaces over creating custom ones. Only create custom when you need: (a) multiple type parameters, (b) checked exceptions, or (c) self-documenting intent.

#### Stream API

Streams provide a declarative pipeline for processing collections (filter → transform → collect).

```
// Imperative (pre-8):
List<String> result = new ArrayList<>();
for (StockBackReward r : rewards) {
    if (r.getStatus() == PENDING) {
        result.add(r.getTickerSymbol());
    }
}

// Declarative (Java 8+):
List<String> result = rewards.stream()
    .filter(r -> r.getStatus() == PENDING)
    .map(StockBackReward::getTickerSymbol)
    .collect(Collectors.toList());
```

**How streams work internally:**

```
┌─────────────────────────────────────────────────────────────────────┐
│ Stream Pipeline (lazy evaluation)                                    │
│                                                                      │
│ Source: Collection.stream() → creates a Spliterator over the data    │
│                                                                      │
│ Intermediate ops: .filter(), .map(), .flatMap(), .sorted()           │
│   → Each returns a NEW Stream object (not a new collection)          │
│   → No data is processed yet — ops are recorded as a pipeline       │
│   → Internally: each op wraps the previous stage's Spliterator      │
│                                                                      │
│ Terminal op: .collect(), .forEach(), .reduce(), .count()              │
│   → TRIGGERS execution of the entire pipeline                        │
│   → Elements flow through ALL stages one-at-a-time (not batch)       │
│   → Pipeline is consumed — stream cannot be reused                   │
└─────────────────────────────────────────────────────────────────────┘
```

**Key insight:** Streams process elements one at a time through all stages (vertical execution), not stage-by-stage (horizontal). This means `.filter()` can short-circuit — if `.findFirst()` is the terminal op, processing stops at the first match.

**EquityCart usage:**

```
// StockBackRewardConsumer — grouping order items by ticker
Map<String, List<OrderItemEvent>> itemsByTicker = event.getItems().stream()
    .filter(item -> tickerMap.containsKey(item.getProductId()))
    .collect(Collectors.groupingBy(item -> tickerMap.get(item.getProductId())));
```

**Best Practices:**

- DO: Use streams for transform/filter/collect chains on collections
- DO: Use method references (`User::getEmail`) over lambdas when equivalent
- DON'T: Use streams for simple iteration with side effects — use `for` loop
- DON'T: Use `.parallel()` without measuring — overhead of thread coordination often exceeds gains for small collections (< 10,000 elements)
- DON'T: Mutate external state inside stream operations (breaks parallelism assumptions)

#### Optional<T>

A container that may or may not hold a value. Eliminates `null` return values from APIs.

```
// Before:
User user = userRepository.findByEmail(email);
if (user == null) throw new ResourceNotFoundException("User not found");

// After:
User user = userRepository.findByEmail(email)
    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
```

**Best Practices:**

- DO: Return `Optional<T>` from methods that might have no result (repository queries)
- DO: Use `.orElseThrow()` for mandatory lookups, `.orElse(default)` for optional with fallback
- DON'T: Use Optional as a field type or method parameter — it's designed for return types only
- DON'T: Call `.get()` without `.isPresent()` check — defeats the purpose (just use null then)
- DON'T: Return `Optional.of(null)` — it throws NPE. Use `Optional.ofNullable()`

#### CompletableFuture

Composable asynchronous computation — the replacement for raw `Future<T>` (which had no composition).

```
// EquityCart: async MongoDB write (fire-and-forget)
CompletableFuture.runAsync(() -> {
    portfolioEventStore.append(event);
});
```

**Best Practice:** Use `CompletableFuture` for IO-bound work you want off the main thread. For CPU-bound work, consider parallel streams. Always handle exceptions via `.exceptionally()` or `.whenComplete()`.

#### java.time (JSR 310)

Replaced the broken `java.util.Date` and `Calendar` with immutable, thread-safe date/time types.

| Old (broken)       | New (Java 8+)              | Why broken                             |
| ------------------ | -------------------------- | -------------------------------------- |
| `Date`             | `Instant`, `LocalDateTime` | Mutable, months 0-indexed, no timezone |
| `Calendar`         | `ZonedDateTime`            | Mutable, thread-unsafe                 |
| `SimpleDateFormat` | `DateTimeFormatter`        | Thread-unsafe (shared instance = bugs) |

**EquityCart usage:** `LocalDateTime.now()` for timestamps in NotificationEvent, `Instant.now()` in StockQuote.

---

### 1.3 Java 9 (2017) — Modularity & Conveniences

**Module System (Project Jigsaw):**

The JDK itself was split into ~70 modules (java.base, java.sql, java.logging, etc.). Applications can optionally declare modules via `module-info.java`. In practice, most Spring Boot apps don't use the module system yet — they run on the classpath (unnamed module).

**Collection Factory Methods:**

```
// Before: 4 lines for an immutable list
List<String> list = Collections.unmodifiableList(Arrays.asList("a", "b", "c"));

// After: 1 line
List<String> list = List.of("a", "b", "c");
Map<String, Integer> map = Map.of("key", 1, "key2", 2);
Set<String> set = Set.of("a", "b", "c");
```

**EquityCart usage:** `Map.of("tradeType", "BUY")` in NotificationPublisher metadata.

**Important:** `List.of()` / `Map.of()` return UNMODIFIABLE collections. Attempting `.add()` throws `UnsupportedOperationException`. Use `new ArrayList<>(List.of(...))` if mutation is needed.

**Private Interface Methods (Java 9):**

```
public interface NotificationChannelStrategy {
    void send(Long userId, String subject, String body);

    // Java 9: private helper shared between default methods
    private String formatTimestamp(LocalDateTime time) {
        return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
```

**Best Practice:** Use private interface methods to share logic between multiple default methods — avoids duplicating code in the interface.

---

### 1.4 Java 11 (2018, LTS) — Cleanup & `var`

Java 11 was the first LTS after Java 8 (Java 9 and 10 were short-term releases, 6 months each).

**Local Variable Type Inference (`var`):**

```
// Explicit type (pre-11):
Map<String, List<NotificationEvent>> eventsByType = new HashMap<>();

// Type-inferred (Java 11+):
var eventsByType = new HashMap<String, List<NotificationEvent>>();
```

**How it works:** `var` is compile-time only. The compiler infers the type from the right-hand side and replaces `var` with the concrete type in bytecode. No runtime impact. Not dynamic typing.

**Best Practices:**

- DO: Use `var` when the type is obvious from context (`var list = new ArrayList<String>()`)
- DO: Use `var` for complex generic types that are hard to read
- DON'T: Use `var` when it makes the type ambiguous (`var result = service.process()` — what type?)
- DON'T: Use `var` for fields, method parameters, or return types — only local variables

**New String Methods:**

```
"  hello  ".strip()       // "hello" (Unicode-aware, unlike trim())
"  hello  ".isBlank()     // false
"hello\nworld".lines()    // Stream<String> of lines
"ha".repeat(3)            // "hahaha"
```

**HTTP Client (java.net.http):**

Replaced the ancient `HttpURLConnection` with a modern, async-capable client. In Spring Boot, we use WebClient (Reactor Netty) instead — but the JDK's HTTP client exists for non-Spring applications.

---

### 1.5 Java 17 (2021, LTS) — Records, Sealed Classes, Pattern Matching

Java 17 was the LTS that most enterprise apps migrated to from Java 8/11. It accumulated features from Java 12–16.

#### Records (Java 16, production-ready in 17)

An immutable data carrier class — compiler generates constructor, getters, equals(), hashCode(), toString().

```
// Before (40+ lines with Lombok @Data or manual code):
public class NotificationEvent {
    private final Long userId;
    private final String notificationType;
    // ... constructor, getters, equals, hashCode, toString
}

// After (1 line):
public record NotificationEvent(Long userId, String notificationType, ...) {}
```

**What the compiler generates:**

```
1. private final field for each component
2. canonical constructor (all-args)
3. accessor method for each component (userId(), not getUserId())
4. equals() — compares ALL components
5. hashCode() — hashes ALL components
6. toString() — includes all component values
```

**EquityCart usage:** All DTOs, all Kafka events, all response objects.

- `NotificationEvent`, `OrderDeliveredEvent`, `SagaLifecycleEvent` (Kafka DTOs)
- `TradeRequest`, `TradeResponse`, `HoldingResponse` (REST DTOs)
- `PagedResponse<T>` (generic response wrapper)

**Records vs Lombok @Data:**

|                       | Record                                            | @Data                        |
| --------------------- | ------------------------------------------------- | ---------------------------- |
| Immutable             | Yes (final fields, no setters)                    | No (mutable by default)      |
| Inheritance           | Cannot extend classes (implicitly extends Record) | Normal class                 |
| Annotation processing | None needed                                       | Requires Lombok on classpath |
| Custom methods        | Can add — but cannot override accessor names      | Full flexibility             |
| JPA entities          | NOT suitable (needs no-arg constructor, mutable)  | Suitable                     |
| When to use           | DTOs, events, value objects                       | Mutable entities only        |

**Best Practices:**

- DO: Use records for all data carriers (DTOs, events, config holders)
- DO: Add compact constructors for validation: `public record TradeRequest { TradeRequest { if (quantity.signum() <= 0) throw ...; } }`
- DON'T: Use records for JPA entities (Hibernate needs no-arg constructor + mutable fields)
- DON'T: Add business logic to records — they are data, not behavior

#### Sealed Classes (Java 17)

Restrict which classes can extend/implement a type — exhaustive hierarchies.

```
public sealed interface PaymentMethod permits CreditCard, BankTransfer, Wallet {}

// Only these 3 can implement PaymentMethod — compiler enforces this
final class CreditCard implements PaymentMethod { ... }
final class BankTransfer implements PaymentMethod { ... }
final class Wallet implements PaymentMethod { ... }
```

**Why it matters:** The compiler knows ALL subtypes, so `switch` expressions on sealed types can be exhaustive (no `default` branch needed). If you add a new subtype, all switches break at compile time — forces you to handle the new case.

**Best Practice:** Use sealed classes for domain types that have a FIXED set of variants (payment methods, notification channels, saga states). Don't use for types where third-party extension is expected.

#### Pattern Matching for `instanceof` (Java 16)

```
// Before:
if (obj instanceof String) {
    String s = (String) obj;
    System.out.println(s.length());
}

// After:
if (obj instanceof String s) {
    System.out.println(s.length());  // s is already cast and scoped
}
```

#### Switch Expressions (Java 14)

```
// Before (statement — falls through, needs break):
String label;
switch (status) {
    case PENDING: label = "Waiting"; break;
    case VESTED: label = "Complete"; break;
    default: label = "Unknown"; break;
}

// After (expression — no fall-through, returns a value):
String label = switch (status) {
    case PENDING -> "Waiting";
    case VESTED -> "Complete";
    default -> "Unknown";
};
```

#### Text Blocks (Java 15)

```
// Before:
String json = "{\n" +
    "  \"userId\": 42,\n" +
    "  \"type\": \"TRADE_EXECUTED\"\n" +
    "}";

// After:
String json = """
    {
      "userId": 42,
      "type": "TRADE_EXECUTED"
    }
    """;
```

---

### 1.6 Java 21 (2023, LTS) — Virtual Threads & Pattern Matching Maturity

Java 21 is the current LTS that EquityCart targets. Major features:

#### Virtual Threads (Project Loom)

The biggest change to Java's concurrency model since Java 5 (`java.util.concurrent`).

**The problem:** Traditional (platform) threads are OS-managed. Each thread = ~1MB stack memory. An app with 10,000 concurrent HTTP requests needs 10,000 threads = 10GB just for stacks. This is why reactive programming (WebFlux, Reactor) exists — to avoid threads.

**The solution:** Virtual threads are JVM-managed, lightweight (~few KB), and can have millions simultaneously. When a virtual thread blocks on I/O, the JVM unmounts it from the carrier (platform) thread, and the carrier handles another virtual thread.

```
// Platform thread (old):
Thread.ofPlatform().start(() -> handleRequest());

// Virtual thread (Java 21):
Thread.ofVirtual().start(() -> handleRequest());

// ExecutorService with virtual threads:
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> handleRequest());  // each task gets its own virtual thread
}
```

**How it works internally:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Virtual Thread Lifecycle                                                      │
│                                                                              │
│ 1. Created: JVM allocates a Continuation (stack frames stored on heap)       │
│ 2. Scheduled: JVM's ForkJoinPool (default carrier) picks it up               │
│ 3. Running: Executes on a carrier (platform) thread                          │
│ 4. Blocking I/O (e.g., socket.read(), JDBC query):                           │
│    → JVM detects the blocking call                                           │
│    → UNMOUNTS the virtual thread from the carrier                            │
│    → Stores continuation (stack state) on heap                               │
│    → Carrier thread is FREE to run another virtual thread                    │
│ 5. I/O completes: JVM re-mounts the virtual thread on any available carrier  │
│ 6. Continues execution from where it left off                                │
│                                                                              │
│ Platform thread count: = CPU cores (small, fixed pool)                       │
│ Virtual thread count: = millions (bounded only by heap memory)               │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Spring Boot 3.2+ integration:** Set `spring.threads.virtual.enabled=true` in application.yml — all request-handling threads become virtual. No code changes.

**Best Practices:**

- DO: Use virtual threads for I/O-bound workloads (HTTP servers, database access)
- DO: Set `spring.threads.virtual.enabled=true` for Spring Boot 3.2+
- DON'T: Use virtual threads for CPU-bound work (they don't help — still limited by cores)
- DON'T: Pool virtual threads (defeats the purpose — create new ones per task)
- DON'T: Use `synchronized` blocks with virtual threads (pins the carrier thread). Use `ReentrantLock` instead

**Why we still use WebFlux in EquityCart:** The market-data module uses WebClient + Reactor because (a) it was built before virtual threads were production-ready, (b) it demonstrates reactive programming as a learning exercise, (c) WebClient's composable Mono/Flux API is cleaner for HTTP call composition than blocking code.

#### Sequenced Collections (Java 21)

New interfaces with first/last access and reversed views:

```
SequencedCollection<E>:  .getFirst(), .getLast(), .reversed()
SequencedSet<E>:         extends SequencedCollection
SequencedMap<K,V>:       .firstEntry(), .lastEntry(), .reversed()
```

Before Java 21, getting the last element of a List required `list.get(list.size() - 1)`. Now: `list.getLast()`.

#### Pattern Matching for Switch (Java 21, production)

```
// Combined with sealed classes — exhaustive matching:
String message = switch (event) {
    case TradeEvent t     -> "Trade: " + t.ticker();
    case VestingEvent v   -> "Vested: " + v.shares();
    case SagaEvent s      -> "Saga: " + s.status();
    // No default needed — compiler knows all subtypes of sealed EventType
};
```

#### Record Patterns (Java 21)

Destructure records directly in pattern matching:

```
// Before:
if (obj instanceof NotificationEvent event) {
    Long userId = event.userId();
    String type = event.notificationType();
}

// After (record pattern — destructures in-place):
if (obj instanceof NotificationEvent(Long userId, String type, ...)) {
    // userId and type are already extracted
}
```

---

### 1.7 Java Version Summary Table

| Version | Year | Key Feature                                             | EquityCart Usage                             |
| ------- | ---- | ------------------------------------------------------- | -------------------------------------------- |
| 8       | 2014 | Lambdas, Streams, Optional, java.time                   | Specifications, callbacks, stream processing |
| 9       | 2017 | Modules, List.of(), Map.of()                            | Map.of() for metadata                        |
| 11      | 2018 | var, String methods, HTTP Client                        | Local variable inference                     |
| 14      | 2020 | Switch expressions                                      | NotificationDispatcher switch                |
| 16      | 2021 | Records                                                 | All DTOs, events, responses                  |
| 17      | 2021 | Sealed classes, pattern matching instanceof             | —                                            |
| 21      | 2023 | Virtual threads, sequenced collections, record patterns | Target runtime (JDK 21 LTS)                  |

---

## Part 2: Design Patterns Used in EquityCart

### 2.1 Strategy Pattern

**Intent (GoF):** Define a family of algorithms, encapsulate each one, and make them interchangeable. Strategy lets the algorithm vary independently from clients that use it.

**EquityCart implementation:**

- `NotificationChannelStrategy` interface + 3 implementations (Log, Email, Webhook)
- Runtime selection via `Map<String, NotificationChannelStrategy>` bean injection
- Config-driven: `equitycart.notification.channel=LOG` → bean name `logChannel`

**Why it works:** Adding a new channel (SMS, Slack) means writing ONE new class. Zero changes to dispatcher, consumer, or configuration infrastructure.

**Best Practices:**

- DO: Use when you have multiple algorithms for the same task and want runtime switching
- DO: Leverage Spring's auto-collection (`Map<String, Interface>` or `List<Interface>`)
- DON'T: Use Strategy for 2 simple options — an if-else is clearer than a pattern

---

### 2.2 Observer Pattern (Distributed)

**Intent (GoF):** Define a one-to-many dependency between objects so that when one object changes state, all dependents are notified automatically.

**EquityCart implementation:**

- Publishers (TradeServiceImpl, VestingHelper) emit events to Kafka
- Consumer (NotificationConsumer) reacts independently
- Zero coupling: publisher doesn't know who (or if anyone) listens

**Classical vs Distributed:**

| Classical                       | Kafka-based                              |
| ------------------------------- | ---------------------------------------- |
| `subject.addObserver(observer)` | No registration — subscribe to topic     |
| Same JVM, synchronous           | Cross-process, asynchronous              |
| Observer failure blocks subject | Consumer failure doesn't affect producer |
| No persistence                  | Events persisted (replay possible)       |

**Best Practice:** Use the distributed variant (Kafka) when observers are in different services or when delivery guarantees matter. Use the classical variant for same-JVM event dispatch (e.g., Spring's `ApplicationEventPublisher`).

---

### 2.3 Facade Pattern

**Intent (GoF):** Provide a unified interface to a set of interfaces in a subsystem. Facade defines a higher-level interface that makes the subsystem easier to use.

**EquityCart implementation:**

- `PortfolioFacade` sits between Controller and multiple services
- Composes data from PortfolioService + StockBackRewardRepository + calculations
- Maps between entity layer and DTO layer
- Controller stays thin (5 lines per method)

**Best Practice:** Use Facade when a controller would otherwise inject 4+ services and orchestrate their interactions. The facade absorbs the composition complexity.

---

### 2.4 Builder Pattern

**Intent (GoF):** Separate the construction of a complex object from its representation.

**EquityCart implementation:** Lombok `@Builder` on entities and DTOs.

```
NotificationLog.builder()
    .userId(event.userId())
    .notificationType(NotificationType.TRADE_EXECUTED)
    .notificationChannel(NotificationChannel.WEBHOOK)
    .notificationStatus(NotificationStatus.SENT)
    .subject(subject)
    .body(body)
    .metadata(metaData)
    .build();
```

**Why Builder over constructors:** When an object has 7+ fields, a constructor call becomes unreadable (positional arguments). Builder provides named parameters (self-documenting) and optional fields (skip what you don't need).

**Best Practices:**

- DO: Use `@Builder` on entities/DTOs with 5+ fields
- DO: Use `@Builder.Default` for fields that need non-null defaults (e.g., `List` fields → `new ArrayList<>()`)
- DON'T: Use Builder for objects with 2-3 fields — a constructor is simpler

---

### 2.5 State Pattern (Enum-Based)

**Intent (GoF):** Allow an object to alter its behavior when its internal state changes.

**EquityCart implementation:**

- `OrderStatus` enum with `canTransitionTo(OrderStatus next)` method + `EnumSet` transition rules
- `SagaStatus` with `isTerminal()` and progression rules
- `VestingStatus` with PENDING → VESTED | CANCELLED lifecycle

```
public enum OrderStatus {
    CREATED, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, ...;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        CREATED, EnumSet.of(CONFIRMED, CANCELLED),
        CONFIRMED, EnumSet.of(PROCESSING, CANCELLED),
        ...
    );

    public boolean canTransitionTo(OrderStatus next) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(next);
    }
}
```

**Best Practice:** For entities with lifecycle states, encode valid transitions IN the enum. This centralizes the state machine logic and prevents invalid transitions at the domain level (not just the service level).

---

### 2.6 Decorator Pattern

**Intent (GoF):** Attach additional responsibilities to an object dynamically.

**EquityCart implementation:** Resilience4j annotations decorate the `getStockQuote()` method:

```
@Retry(name = "alphaVantage")          // outermost decorator
@CircuitBreaker(name = "alphaVantage") // middle decorator
@RateLimiter(name = "alphaVantage")    // innermost decorator
public Mono<StockQuote> getStockQuote(String symbol) { ... }
```

Each annotation wraps the method in a layer of behavior (retry logic wraps circuit breaker logic wraps rate limiter logic). The actual HTTP call is the innermost operation. This is the Decorator pattern applied via AOP proxies.

---

### 2.7 Template Method Pattern

**Intent (GoF):** Define the skeleton of an algorithm in an operation, deferring some steps to subclasses.

**EquityCart implementation:** Spring Batch's chunk-oriented processing:

```
Step = Reader → Processor → Writer (template skeleton)
      ↓           ↓           ↓
FlatFileItemReader  custom    RepositoryItemWriter  (concrete steps we plug in)
```

The framework defines WHEN each step runs (read chunk → process each → write batch → commit). We provide WHAT each step does. We never call the steps ourselves — the framework template calls them.

---

### 2.8 Repository Pattern

**Intent (Martin Fowler):** Mediates between the domain and data mapping layers using a collection-like interface for accessing domain objects.

**EquityCart implementation:** Spring Data JPA repositories.

```
public interface HoldingRepository extends JpaRepository<Holding, Long> {
    Optional<Holding> findByPortfolioAndTickerSymbol(Portfolio portfolio, String tickerSymbol);
}
```

The interface LOOKS like a collection (`findBy...`, `save()`, `delete()`), but underneath Spring generates a full JPA implementation at startup via proxy + bytecode generation.

---

### 2.9 Singleton Pattern (Spring-Managed)

**Intent (GoF):** Ensure a class has only one instance and provide a global point of access.

**Spring implementation:** All `@Component`, `@Service`, `@Repository`, `@Controller` beans are singletons by default (scope = singleton). Spring guarantees ONE instance per application context.

**Why Spring singletons are better than GoF singletons:**

- No `static getInstance()` — constructor injection is testable
- No global state — scoped to the application context
- Thread-safe initialization (Spring handles it)
- Swappable in tests (@MockBean)

**Best Practice:** Never implement the classical singleton pattern (private constructor + static field) in Spring applications. Let Spring manage bean lifecycle.

---

### 2.10 Saga Pattern (Orchestration)

**Intent:** Manage distributed transactions as a sequence of local transactions with compensating actions for rollback.

**EquityCart implementation:**

**SellToSpendSaga (Phase 6):**
- `SellToSpendSagaOrchestrator` drives 3 steps sequentially
- Each step commits independently (no wrapping @Transactional)
- Failure at step N → compensate steps N-1 through 1 in reverse order
- Saga entity persisted at each boundary (crash recovery)

**ClawbackSaga (Topic 8):**
- `ClawbackSagaOrchestrator` handles refund-triggered reward clawback
- Two forward paths: normal (INITIATED → LEDGER_ADJUSTED → HOLDING_REDUCED → COMPLETED) OR timeout (→ COMPENSATING → FAILED)
- Compensation on timeout: undo holding reduction, undo ledger reversal, fail saga and alert ops
- Includes timeout detector (`@Scheduled` task) to find stuck sagas and either retry or compensate
- Idempotency via 3-layer strategy: status gates (skip completed steps), natural idempotency (ledger service uses idempotency keys), unique constraints (DB)
- Key propagation: `userId` is Kafka partition key for ALL events to ensure ordering

**Comparison:**

| Aspect | SellToSpendSaga | ClawbackSaga |
|--------|-----------------|--------------|
| **Trigger** | User-initiated trade | System-initiated on refund approval |
| **Flow** | Portfolio → Ledger → Order (forward) | Ledger ← Portfolio (reverse) |
| **Steps** | 3: reduce, record, confirm | 3: reverse ledger, reduce, complete |
| **Compensation** | Only on failure | On timeout or max retries reached |
| **State persistence** | After each step | After each step (+ attemptCount tracking) |
| **Error handling** | Simple: compensate if step fails | Advanced: retry vs compensate decision |

**Best Practice:** Use orchestration (central coordinator) when steps have complex dependencies or when compensation logic is intricate. Use choreography (event-driven, no coordinator) when steps are independent and each service owns its own compensation. EquityCart uses orchestration for both sagas because:
1. SellToSpendSaga: step 2 (ledger record) depends on step 1's output (shares reduced)
2. ClawbackSaga: compensation order is critical (undo in reverse of forward execution order)

---

### 2.11 Outbox Pattern

**Intent:** Ensure reliable event publishing alongside database writes without distributed transactions.

**EquityCart implementation:**

- Business write + outbox insert in ONE @Transactional
- Separate poller reads outbox and publishes to Kafka
- Guaranteed: if the business write commits, the event WILL be published (eventually)

---

### 2.12 Distributed Locking Pattern — Flash Sale Stock Drops (Topic 3)

**Intent:** Protect shared resource (product inventory) from concurrent over-mutation under burst traffic using a distributed lock.

**Problem:** Multiple concurrent requests for the same limited-stock item can all see inventory as "10 units available" and proceed to sell 15 units total (overselling). Local `synchronized` blocks don't work in distributed systems (multiple JVM instances).

**Solution: Redis-Based Optimistic Locking**

EquityCart Topic 3 uses Redis `SET NX EX` (set-if-not-exists with expiration) + Lua script for atomic compare-and-delete:

```java
// Acquire: SET flash-sale:lock:{productId} "{ownerToken}" NX EX 10
// Only succeeds if key doesn't exist (only one request per productId gets the lock)

// Release: Lua script validates owner before deleting
// if redis.call('get', key) == ownerToken then
//   redis.call('del', key)
// end
// Prevents stale release (clock skew between servers)
```

**Why Redis SET NX EX over other locking strategies:**

| Strategy             | Pros                                              | Cons                                           |
| -------------------- | ------------------------------------------------- | ---------------------------------------------- |
| `synchronized`       | JVM-native, zero overhead                         | Doesn't work across JVM instances (monolith ok) |
| Pessimistic DB lock  | Part of same transactional boundary              | Scales badly (blocks table rows, deadlock risk)  |
| Redis SET NX EX      | Distributed, fast, expires automatically          | External dependency (Redis must stay up)         |
| Consensus (Zookeeper)| Guaranteed safety across network partitions       | Overkill for flash sales; high latency          |

**EquityCart Topic 3 Implementation:**

```java
// Dual-phase idempotency: prevents duplicate orders even if request retries under lock
@Transactional
public OrderResponse placeFlashSaleOrder(FlashSalePurchaseRequest req) {
    // PHASE 1: Fast-path idempotency check (before lock)
    Optional<Order> cachedOrder = orderRepository.findByIdempotencyKey(req.idempotencyKey());
    if (cachedOrder.isPresent()) {
        return orderFacade.toResponse(cachedOrder.get());  // Return cached result, no lock needed
    }
    
    // Check if sale is active (config-driven ISO-8601 window)
    if (!isFlashSaleActive()) {
        throw new FlashSaleInactiveException("Window closed");
    }
    
    // PHASE 2: Acquire lock (product-scoped)
    String lockToken = UUID.randomUUID().toString();
    boolean acquiredLock = flashSaleLockManager.tryAcquireLock(req.productId(), lockToken, 10);  // 10s TTL
    
    if (!acquiredLock) {
        throw new FlashSaleBusyException("Too many concurrent purchases");  // Retry client-side
    }
    
    try {
        // PHASE 3: Re-check idempotency under lock (race-safe)
        Optional<Order> raceOrder = orderRepository.findByIdempotencyKey(req.idempotencyKey());
        if (raceOrder.isPresent()) {
            return orderFacade.toResponse(raceOrder.get());
        }
        
        // PHASE 4: Deduct stock (protected by lock)
        Product product = productService.deductStock(req.productId(), req.quantity());
        Order order = new Order(...);  // Create order
        boolean stockDeducted = true;
        
        try {
            Order saved = orderRepository.save(order);  // May fail (constraint, etc.)
            return orderFacade.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // Stock deducted but order save failed → restore
            if (stockDeducted) {
                productService.restoreStock(req.productId(), req.quantity());
            }
            throw e;
        }
    } finally {
        // Always release lock (even on exception)
        flashSaleLockManager.releaseLock(req.productId(), lockToken);  // Lua script validates owner
    }
}

// Why this works:
// 1. Lock serializes stock deduction: only 1 request per product mutates at a time
// 2. If request crashes after deduction, lock expires (10s TTL) → next requester can proceed
// 3. If request crashes after order save, compensation restores stock → no orphaned deductions
// 4. Dual-phase idempotency prevents duplicates even if request retries twice (both lock attempts)
// 5. Different productIds = different lock keys → concurrent buys of different products (e.g., 100 AAPL + 100 TSLA simultaneously)
```

**Cache Invalidation (Critical for consistency):**

```java
@Caching(evict = {
    @CacheEvict(value = "products", allEntries = true),      // Invalidate all cached products
    @CacheEvict(value = "product", key = "#productId")       // Invalidate specific product
})
public void deductStock(Long productId, Integer quantity) {
    // ... deduct logic ...
}

// Why both caches:
// - "products" cache: LIST operations (e.g., /api/products?category=electronics)
// - "product" cache: GET operations (e.g., /api/products/{id})
// - allEntries=true on "products" ensures any LIST result is fresh
// - key-specific on "product" is fine because product ID is predictable
```

**Lock Acquisition Retries (Bounded, Exponential Backoff):**

```java
private static final int FLASH_SALE_LOCK_RETRY_ATTEMPTS = 3;
private static final long FLASH_SALE_LOCK_BASE_BACKOFF_MS = 50;

public boolean acquireFlashSaleLock(Long productId, String token, int ttlSeconds) {
    for (int attempt = 1; attempt <= FLASH_SALE_LOCK_RETRY_ATTEMPTS; attempt++) {
        boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent("flash-sale:lock:" + productId, token, ttlSeconds, TimeUnit.SECONDS);
        
        if (acquired) {
            log.debug("Lock acquired for productId={} on attempt={}", productId, attempt);
            return true;
        }
        
        if (attempt < FLASH_SALE_LOCK_RETRY_ATTEMPTS) {
            long backoffMs = FLASH_SALE_LOCK_BASE_BACKOFF_MS * attempt;  // 50ms, 100ms, 150ms
            log.info("Lock contention on productId={}. Retrying in {}ms (attempt {}/{})",
                productId, backoffMs, attempt, FLASH_SALE_LOCK_RETRY_ATTEMPTS);
            Thread.sleep(backoffMs);
        }
    }
    
    log.warn("Failed to acquire lock for productId={} after {} attempts (300ms max wait)",
        productId, FLASH_SALE_LOCK_RETRY_ATTEMPTS);
    return false;  // Give up, throw FlashSaleBusyException to client
}

// Rationale:
// - 3 retries with exponential backoff covers transient lock contention
// - Max 300ms wait (50+100+150) is acceptable for user-facing API
// - Backoff prevents thundering herd (all 100 concurrent requests don't retry in lockstep)
// - If still locked after 300ms, external traffic is truly overwhelming → reject gracefully
```

**Active Window Validation (Config-Driven, Fail-Closed):**

```java
@Value("${equitycart.flash-sale.enabled:true}")
private boolean flashSaleEnabled;

@Value("${equitycart.flash-sale.start-time:}")  // Empty = always active
private String flashSaleStartTime;

@Value("${equitycart.flash-sale.end-time:}")
private String flashSaleEndTime;

private boolean isFlashSaleActive() {
    if (!flashSaleEnabled) {
        log.debug("Flash sale disabled via configuration");
        return false;
    }
    
    // If start/end times are empty, sale always runs (when enabled)
    if (flashSaleStartTime.isBlank() || flashSaleEndTime.isBlank()) {
        log.debug("Flash sale window open (no time bounds configured)");
        return true;
    }
    
    try {
        Instant now = Instant.now();
        Instant start = Instant.parse(flashSaleStartTime);
        Instant end = Instant.parse(flashSaleEndTime);
        
        boolean active = now.isAfter(start) && now.isBefore(end);
        if (!active) {
            log.warn("Flash sale window closed. Now={}, Start={}, End={}", now, start, end);
        }
        return active;
    } catch (DateTimeParseException e) {
        log.error("Invalid flash sale window timestamp format. Treating as inactive.", e);
        return false;  // Fail-closed: on config error, disable sale
    }
}

// Config example (application.yml):
// equitycart:
//   flash-sale:
//     enabled: true
//     start-time: "2026-08-15T10:00:00Z"    # ISO-8601 Instant
//     end-time: "2026-08-15T18:00:00Z"
```

**Concurrent Scenarios (How Multiple Users Experience This):**

```
Scenario: 100 concurrent requests for 10 AAPL shares at $150/each during flash sale

User 1 (Time=0ms):       User 2 (Time=0ms):        User 3 (Time=0ms):
  Lock key exists? NO       Lock key exists? YES      Lock key exists? YES
  SET NX succeeds           → return false            → return false
  Acquires lock             BackoffMs=50              BackoffMs=50
                            Thread.sleep(50)         Thread.sleep(50)
  
  Checks stock: 10 OK       User 1 deducts 10 units   User 1 deducts 10 units
  Deducts 10 units          Stock now = 0             Stock now = 0
  Saves order               (User 1 release lock)
  Releases lock
  → Success 201             (Time ~50ms)              (Time ~50ms)
                            Lock key exists? NO       Lock key exists? NO
                            SET NX succeeds           SET NX succeeds
                            Checks stock: 0 FAIL      Checks stock: 0 FAIL
                            Throws InsufficientShare  Throws InsufficientShare
                            exception 400             exception 400
                            
Users 4-100: Similar to User 2/3, fail with 400

RESULT: First user got 10 shares, rest got error. NO OVERSELLING.
        All responses < 300ms (lock wait bounded).
        Cache invalidated → /api/products/{productId} now shows 0 remaining.
```

**EquityCart Production Considerations:**

- **Lock TTL (10s):** Conservative to prevent permanent locks. In distributed deployment, may need longer for high-latency networks.
- **Backoff strategy (50ms × attempt):** Tuned for sub-millisecond Redis latency. High-latency environments may need longer backoffs.
- **Cache invalidation:** `allEntries=true` on "products" is safe but impacts other concurrent reads. If list endpoint shows stale data, product cache was not invalidated.
- **Configuration reload:** Changing `flash-sale.start-time` at runtime requires app restart (ConfigServer refresh not implemented). For true dynamic windows, consider a database table instead.

---

### 2.13 Chain of Responsibility Pattern — Security Filter Chains

**Intent (GoF):** Pass a request along a chain of handlers. Each handler decides whether to process the request or pass it to the next handler.

**EquityCart implementation (Phase 8):**

Both the Servlet and WebFlux stacks implement Chain of Responsibility for security processing:

```
Servlet SecurityFilterChain (each service — order, product, etc.):
    Request
      → CorrelationIdFilter       (sets MDC)
      → JwtAuthenticationFilter   (validates HS256 / OR)
      → BearerTokenAuthFilter     (validates RS256 via JWKS)
      → AuthorizationFilter       (checks roles)
      → DispatcherServlet         (controller)
    Each filter: process own concern → call chain.doFilter() to pass forward
    OR: set response + return WITHOUT calling chain.doFilter() to SHORT-CIRCUIT

WebFlux SecurityWebFilterChain (API Gateway):
    Request
      → NettyRoutingFilter        (reactive routing)
      → WebFilterChainProxy       (Spring Security entry point)
        → AuthenticationWebFilter (validates RS256 token via JWKS)
        → AuthorizationWebFilter  (checks authenticated)
        → GatewayFilterAdapter    (route-specific filters)
    Each WebFilter: process → return chain.filter(exchange) to continue
    OR: return Mono.error(new ResponseStatusException(401)) to SHORT-CIRCUIT
```

**Key difference from GoF:** In GoF's original pattern, each handler EITHER processes OR passes (not both). In Spring's filter chains, each handler BOTH processes (e.g., sets MDC) AND passes (calls `chain.doFilter()`). Security filters additionally SHORT-CIRCUIT by NOT calling the next handler.

**Why it matters for security:** The ordering within the chain is critical. If authentication runs after authorization, authorization always fails (no identity yet). If the custom HS256 filter runs before `BearerTokenAuthenticationFilter`, RS256 tokens are rejected before they can be validated by the correct handler. Order determines correctness.

**Best Practice:** In Spring Security, do not guess filter order. Use `http.addFilterBefore()` / `http.addFilterAfter()` / `http.addFilterAt()` with explicit reference to known filter positions (e.g., `UsernamePasswordAuthenticationFilter.class`). Never rely on `@Order` alone for security filters within the SecurityFilterChain.

---

### 2.13 Pattern Summary Table

| Pattern                 | Category   | Where Used                                            | Key Benefit                                                |
| ----------------------- | ---------- | ----------------------------------------------------- | ---------------------------------------------------------- |
| Strategy                | Behavioral | Notification channels                                 | Runtime algorithm swap                                     |
| Observer                | Behavioral | Kafka Pub/Sub notifications                           | Decoupled event reactions                                  |
| Facade                  | Structural | PortfolioFacade                                       | Simplified controller interface                            |
| Builder                 | Creational | Entity/DTO construction (@Builder)                    | Readable multi-field construction                          |
| State                   | Behavioral | OrderStatus, SagaStatus, ClawbackStatus enums         | Enforced valid transitions                                 |
| Decorator               | Structural | Resilience4j stacking, ServerHttpResponseDecorator    | Layered cross-cutting concerns                             |
| Template Method         | Behavioral | Spring Batch chunk processing                         | Framework controls skeleton                                |
| Repository              | Domain     | Spring Data JPA interfaces                            | Collection-like data access                                |
| Singleton               | Creational | All Spring beans (default scope)                      | One instance, DI-managed                                   |
| Saga                    | Enterprise | SellToSpendSagaOrchestrator, ClawbackSagaOrchestrator | Distributed transaction recovery + compensation            |
| Outbox                  | Enterprise | OutboxEvent + OutboxPoller / Debezium CDC             | Reliable event publishing with eventual consistency        |
| Chain of Responsibility | Behavioral | SecurityFilterChain, SecurityWebFilterChain (Phase 8) | Ordered security processing, short-circuit on auth failure |

---

## Part 3: Best Practices Cheat Sheet

### Naming

| DO                                   | DON'T                                             |
| ------------------------------------ | ------------------------------------------------- |
| `findByUserId` (repository)          | `getUserByUserId` (redundant "get")               |
| `NotificationEvent` (domain noun)    | `NotificationEventDTO` (suffix tells nothing new) |
| `dispatch(event)` (verb for actions) | `doDispatch(event)` (meaningless "do" prefix)     |
| `isTerminal()` (boolean methods)     | `checkTerminal()` (unclear return type)           |

### Immutability

| DO                                     | DON'T                                          |
| -------------------------------------- | ---------------------------------------------- |
| Use records for data carriers          | Use mutable POJOs for DTOs                     |
| Return `List.copyOf()` from getters    | Return internal mutable lists                  |
| Use `BigDecimal` for money (immutable) | Use `double` for money (floating point errors) |
| `final` fields wherever possible       | Mutable state in shared beans                  |

### Error Handling

| DO                                                      | DON'T                                          |
| ------------------------------------------------------- | ---------------------------------------------- |
| Throw domain exceptions (`InsufficientSharesException`) | Throw generic `RuntimeException("not enough")` |
| Let exceptions propagate to `@RestControllerAdvice`     | Catch-and-log in every method                  |
| Use `Optional.orElseThrow()` for missing entities       | Return null from service methods               |
| Validate at system boundaries (controller DTOs)         | Re-validate in every layer                     |

### Collections

| DO                                   | DON'T                                                    |
| ------------------------------------ | -------------------------------------------------------- |
| `List.of()` for constants            | `Arrays.asList()` (partially mutable)                    |
| `Map.of()` for small immutable maps  | `Collections.unmodifiableMap(new HashMap<>())` (verbose) |
| Stream + collect for transformations | Manual loops for filter/map/reduce                       |
| `EnumSet` for flag/transition sets   | `HashSet<MyEnum>` (EnumSet is bit-vector, faster)        |

---

## Part 4: Phase 9 Java APIs and Implementation Notes (Observability)

### 4.1 Metrics Instrumentation APIs

- `io.micrometer.core.instrument.MeterRegistry`
- `Counter` for monotonic event totals (success/failure/channel counts)
- `Timer` for latency measurements (request/business operation timing)

**Rule:** Counters should be incremented at definitive business outcome points, not at method entry.

### 4.2 Tracing APIs and Context

- Micrometer tracing auto-instrumentation handles trace/span lifecycle for HTTP flows.
- Correlation context remains in logging MDC/ThreadContext for log-level stitching.
- Trace context and log correlation context serve related but different diagnostic needs.

### 4.3 Log4j2 Runtime APIs

- `LogManager.getLogger(...)` for logger creation
- `ThreadContext.put/remove` for per-request contextual fields in logs
- `log4j2-spring.xml` controls structured JSON layout and rolling policies

### 4.4 Defensive Metric Design Patterns (from Phase 9)

1. **Idempotency-aware counting:** duplicate request return paths must not inflate failure metrics.
2. **Single-finalization timing:** timer stop should occur once in a `finally`-style completion point.
3. **Label discipline:** keep label cardinality bounded (avoid userId/orderId labels in metrics).

### 4.5 Interview Questions

**Q: "Why should high-cardinality labels be avoided in Prometheus metrics?"**  
A: High-cardinality labels (userId, orderId, sessionId) explode time-series count, increase memory/storage use, and degrade query performance. Use low-cardinality dimensions (service, endpoint, status, channel).

**Q: "When do you use Counter vs Timer?"**  
A: Counter tracks event totals (how many). Timer tracks duration distribution (how long) and also provides count/rate over time.

---

## Part 5: MongoDB & Spring Data MongoDB (Phase 10 CQRS)

### 5.1 MongoDB Upsert Pattern for Idempotent Projections

**Problem:** Kafka provides at-least-once delivery semantics — messages can be retried. When rebuilding a read model from Kafka events, the same event may be processed multiple times. The read model must idempotently converge to the same state regardless of replay count.

**Solution: MongoDB Upsert by Unique Key**

```java
// In PortfolioReadModelSynchronizer
Query query = new Query(Criteria.where("userId").is(userId));
Update update = new Update()
    .set("totalValue", computedTotal)
    .set("holdings", holdings)
    .set("rewards", rewards)
    .set("lastUpdatedAt", Instant.now());

mongoTemplate.upsert(query, update, ReadModelPortfolio.class);
```

**How it works:**

1. **First event:** `Query.where("userId").is("user-123")` matches ZERO documents (collection empty). Upsert INSERTs a new document with all fields set.
2. **Retry of same event:** Same query now matches ONE document (userId exists). Upsert UPDATEs all fields to the same values → idempotent (result unchanged).
3. **Third retry:** Same UPDATEs applied again → still idempotent.

**Why NOT `repository.save(newDoc)`?**
- `save()` always INSERTs if no ID provided → creates duplicate document with different ObjectId
- Deduplication logic required → complex queries to find and merge duplicates
- Upsert solves this atomically with one database operation

**Uniqueness enforcement:**

MongoDB creates unique index on `userId` (via `@Indexed(unique = true)` on the field):

```java
@Document("portfolio_read_models")
public class ReadModelPortfolio {
    @Id ObjectId id;
    
    @Indexed(unique = true)
    String userId;
    
    BigDecimal totalValue;
    // ...
}
```

First document INSERT with userId="123" succeeds. Second attempt to INSERT another doc with same userId fails (unique constraint). Upsert detects this, converts to UPDATE instead.

**Key insight:** Upsert is NOT a replacement for unique constraints. The constraint ensures that no two documents can have the same userId. Upsert's value is that it's atomic (either INSERT or UPDATE, depending on existence) without the application checking first.

### 5.2 @Document and @Indexed Annotations

**@Document(collection = "portfolio_read_models")**

Maps Java class to MongoDB collection name. Equivalent to JPA's `@Table(name = "...")`.

```java
@Document(collection = "portfolio_read_models")
public class ReadModelPortfolio {
    // fields...
}
```

Without `@Document`, Spring Data MongoDB still maps the class to a collection, but uses a default name derived from the class name (camelCase conversion). Explicit `@Document` is recommended for clarity.

**@Id — Primary Key Equivalent**

```java
@Id
ObjectId id;
```

Marks the field as MongoDB's `_id` (primary key). MongoDB auto-generates `ObjectId` if not provided. Can also use:

```java
@Id
String id;  // Manual string IDs
```

or

```java
@Id
Long id;    // Numeric IDs
```

When using upsert for denormalization, don't rely on `@Id`. Instead, create a business key unique index (`@Indexed(unique = true)`) to identify documents.

**@Indexed — Secondary Index**

```java
@Indexed(unique = true)
String userId;

@Indexed
LocalDateTime lastUpdatedAt;

@Indexed(sparse = true)
String promotionCode;  // Index ONLY non-null values
```

- `unique = true`: Creates unique index; MongoDB rejects inserts/updates that violate it
- `sparse = true`: Index skips documents where the field is null/missing. Useful when the field is optional.
- No parameters: Creates standard index for faster queries

**Why separate `@Id` from business key?**

```
_id: ObjectId("60d5ec49c1d2e8b3f4c2a1b0")  ← MongoDB system field, auto-generated
userId: "user-123"                          ← Business key, unique index, our upsert query uses this
```

MongoDB's `_id` is always present and unique. If you were to query `_id` for upserts, you'd need to generate IDs upfront (defeats the purpose). The pattern: let MongoDB manage `_id`, add your own business unique indexes for upserts.

### 5.3 MongoTemplate.upsert() API Reference

```java
mongoTemplate.upsert(
    new Query(Criteria.where("userId").is(userId)),
    new Update()
        .set("field1", value1)
        .set("field2", value2)
        .set("field3", value3),
    ReadModelPortfolio.class
);
```

**Parameters:**

- **Query:** filter condition (`WHERE userId = ?` in SQL terms)
- **Update:** fields to set (`SET field1 = ?, field2 = ? ...`)
- **Class:** collection/document type (Spring Data uses this to determine the collection name)

**Return value:** `UpdateResult` with `matchedCount`, `modifiedCount`, `upsertedId`.

**Behavior:**

- No matching documents: INSERT new doc with all `set()` fields
- One matching document: UPDATE its fields
- Multiple matching documents: UPDATE all of them (risky if query is not specific enough)

**Safety:** Always ensure Query is specific enough. `new Query()` with no Criteria matches ALL documents — upsert would update every row.

### 5.4 Common MongoTemplate Operations for Read Models

**Upsert (INSERT or UPDATE):**
```java
mongoTemplate.upsert(query, update, ReadModelPortfolio.class);
```

**Insert only (fails if exists):**
```java
mongoTemplate.insert(new ReadModelPortfolio(...));
```

**Save (INSERT if no _id, UPDATE if _id exists):**
```java
mongoTemplate.save(document, "collection_name");
```

**Find and delete:**
```java
DeleteResult result = mongoTemplate.remove(
    new Query(Criteria.where("userId").is(userId)),
    ReadModelPortfolio.class
);
```

**Bulk operations:**
```java
BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.ORDERED, ReadModelPortfolio.class);
for (String userId : userIds) {
    bulk.upsert(
        new Query(Criteria.where("userId").is(userId)),
        new Update().set("field", value)
    );
}
bulk.execute();
```

### 5.5 Interview Questions

**Q: "Why use `mongoTemplate.upsert()` instead of `mongoRepository.save()`?" (Phase 10)**  
A: `save()` always inserts if the document has no ID field set, creating duplicates. Upsert atomically checks if a document matching the query exists — if yes, updates it; if no, inserts. For CQRS projections receiving at-least-once Kafka events, upsert guarantees idempotency: replaying the same event produces the same result without manual deduplication logic.

**Q: "What happens if you upsert with a Query that matches multiple documents?" (Phase 10)**  
A: MongoTemplate updates ALL matching documents. This is usually a bug. The solution: always query by a unique business key (`userId`, `aggregateId`). Add `@Indexed(unique = true)` to enforce this at the database level — if your query is not specific enough, you'll get a unique constraint error before corrupting data.

**Q: "When should you use `@Indexed(sparse = true)`?" (Phase 10)**  
A: When the field is optional. Example: `promotionCode` is only present for users who applied a promo. Without `sparse = true`, MongoDB creates an index entry for every document (including those with null/missing promotionCode). This bloats the index. With `sparse = true`, MongoDB skips null/missing values, reducing index size. Trade-off: queries filtering for null values (`promotionCode == null`) won't use the sparse index.

**Q: "Why can't you use @Id for upsert queries in read models?" (Phase 10)**  
A: Upserts are designed to either insert OR update based on a business condition. MongoDB's `@Id` field is auto-generated on first insert, so you don't know the ID in advance. If you query by `_id`, you'd need to generate IDs upfront (defeating eventual consistency from events). The pattern: let MongoDB auto-manage `_id`, add your own business unique indexes (userId, aggregateId) that are known at upsert-time.

