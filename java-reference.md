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

- `SellToSpendSagaOrchestrator` drives 3 steps sequentially
- Each step commits independently (no wrapping @Transactional)
- Failure at step N → compensate steps N-1 through 1 in reverse order
- Saga entity persisted at each boundary (crash recovery)

**Best Practice:** Use orchestration (central coordinator) when steps have complex dependencies. Use choreography (event-driven, no coordinator) when steps are independent. EquityCart uses orchestration because step 2 (ledger) depends on step 1's output.

---

### 2.11 Outbox Pattern

**Intent:** Ensure reliable event publishing alongside database writes without distributed transactions.

**EquityCart implementation:**

- Business write + outbox insert in ONE @Transactional
- Separate poller reads outbox and publishes to Kafka
- Guaranteed: if the business write commits, the event WILL be published (eventually)

---

### 2.12 Chain of Responsibility Pattern — Security Filter Chains

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
| State                   | Behavioral | OrderStatus, SagaStatus enums                         | Enforced valid transitions                                 |
| Decorator               | Structural | Resilience4j stacking, ServerHttpResponseDecorator    | Layered cross-cutting concerns                             |
| Template Method         | Behavioral | Spring Batch chunk processing                         | Framework controls skeleton                                |
| Repository              | Domain     | Spring Data JPA interfaces                            | Collection-like data access                                |
| Singleton               | Creational | All Spring beans (default scope)                      | One instance, DI-managed                                   |
| Saga                    | Enterprise | SellToSpendSagaOrchestrator                           | Distributed transaction recovery                           |
| Outbox                  | Enterprise | OutboxEvent + OutboxPoller                            | Reliable event publishing                                  |
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
