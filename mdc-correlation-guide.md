# MDC Correlation ID — Complete Guide

## What Problem Does This Solve?

Without Correlation ID, a bug report says "order placement failed around 3pm." You have 8 services, each logging hundreds of lines per second. How do you find the exact log lines for that one failed request?

With Correlation ID, every log line for that request — across every service — carries the same UUID:

```
2026-06-10 15:03:21 [http-nio-8080] [a1b2c3d4-e5f6-...] INFO  GatewayFilter - Routing to PORTFOLIO-SERVICE
2026-06-10 15:03:21 [http-nio-8084] [a1b2c3d4-e5f6-...] INFO  SellToSpendServiceImpl - Sell-to-spend: sold 5 shares
2026-06-10 15:03:21 [http-nio-8081] [a1b2c3d4-e5f6-...] INFO  OrderServiceImpl - Order 42 confirmed
```

One `grep a1b2c3d4-e5f6 *.log` reconstructs the entire call chain.

---

## Architecture: How It Flows

```
HTTP Client (browser / curl)
        │
        │  Request: GET /api/portfolio
        │  [No X-Correlation-Id header]
        ▼
┌─────────────────────────────────────────────────┐
│              API GATEWAY (:8080)                 │
│  Spring Cloud Gateway filter                     │
│  → Generates UUID: a1b2c3d4-e5f6-...            │
│  → Adds X-Correlation-Id header to request       │
└────────────────────┬────────────────────────────┘
                     │  Request + X-Correlation-Id: a1b2c3d4
                     ▼
┌─────────────────────────────────────────────────┐
│          PORTFOLIO-SERVICE (:8084)               │
│                                                  │
│  1. MdcCorrelationFilter (OncePerRequestFilter)  │
│     → reads X-Correlation-Id header              │
│     → ThreadContext.put("correlationId", value)  │
│     → response header: X-Correlation-Id echoed   │
│                                                  │
│  2. Controller → Service → SagaOrchestrator      │
│     → every log line now prints [a1b2c3d4]       │
│                                                  │
│  3. OrderFeignClient.updateOrderStatus(...)      │
│     → FeignCorrelationInterceptor fires          │
│     → reads ThreadContext.get("correlationId")   │
│     → adds X-Correlation-Id to outgoing request  │
│                                                  │
│  4. finally: ThreadContext.remove("correlationId")│
└────────────────────┬────────────────────────────┘
                     │  Feign HTTP + X-Correlation-Id: a1b2c3d4
                     ▼
┌─────────────────────────────────────────────────┐
│            ORDER-SERVICE (:8081)                 │
│                                                  │
│  1. MdcCorrelationFilter reads the header        │
│     → same UUID from PORTFOLIO-SERVICE           │
│     → ThreadContext.put("correlationId", value)  │
│                                                  │
│  2. Controller → OrderServiceImpl                │
│     → log lines carry [a1b2c3d4] — same ID       │
│                                                  │
│  3. ThreadContext.remove() in finally            │
└─────────────────────────────────────────────────┘
```

---

## The Three Components

### 1. MdcCorrelationFilter (in `commons/filter/`)

**What:** A `OncePerRequestFilter` registered in every service that uses `commons`.

**What `OncePerRequestFilter` means:** The Servlet specification allows a filter to be registered multiple times — once in the Spring Security filter chain, once in the application filter chain, etc. `OncePerRequestFilter` uses a request attribute flag to guarantee the actual filter logic runs exactly once per request regardless of how many chains reference it.

**The lifecycle of one request:**

```java
// Step 1: Read header, generate if missing
String correlationId = request.getHeader("X-Correlation-Id");
if (correlationId == null || correlationId.isBlank()) {
    correlationId = UUID.randomUUID().toString();  // this service is the origin
}

// Step 2: Store in thread-local map (MDC / ThreadContext)
ThreadContext.put("correlationId", correlationId);

// Step 3: Echo in response so the caller knows what was assigned
response.setHeader("X-Correlation-Id", correlationId);

try {
    // Step 4: Execute the request — ALL log calls on this thread now include the ID
    filterChain.doFilter(request, response);
} finally {
    // Step 5: MANDATORY cleanup — servlet containers pool threads
    ThreadContext.remove("correlationId");
}
```

**Why `finally` is non-negotiable:** Tomcat maintains a thread pool (default 200 threads). After your request completes, the thread is returned to the pool and reused for the next request. If you skip `remove()`, the next request on that thread inherits your Correlation ID in its MDC — it will log with the wrong ID until it overwrites the key. This is a silent bug that corrupts your trace data.

---

### 2. Log4j2 Pattern: `%X{correlationId}`

In `equitycart-config/application.yml`:

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n"
```

**`%X{correlationId}`** reads from the MDC / ThreadContext at log-call time. If the key exists, it prints the value. If it doesn't exist (e.g., before the filter runs, or in a scheduled task), it prints an empty string `[]`.

**Where the value lives:** Log4j2's `ThreadContext` is a `ThreadLocal<Map<String, String>>`. The pattern `%X{key}` calls `ThreadContext.get(key)` internally when rendering each log event. You never call it explicitly in service code — it's automatic.

---

### 3. FeignCorrelationInterceptor (in `commons/feign/`)

**What:** A Feign `RequestInterceptor` that copies the Correlation ID from the current thread's MDC into outgoing Feign HTTP requests.

**Why it's needed:** MDC is thread-local. When `SellToSpendServiceImpl` calls `orderFeignClient.updateOrderStatus(...)`, Feign creates an HTTP request and sends it over the network. The HTTP request object has no knowledge of the calling thread's MDC. This interceptor bridges the gap.

**Auto-registration:** Any `@Component` implementing `RequestInterceptor` is automatically discovered by Spring's Feign factory and injected into every `@FeignClient` bean. There is no `@Configuration` class needed — one `@Component` registration covers all Feign clients in the service.

```java
@Override
public void apply(RequestTemplate requestTemplate) {
    String correlationId = ThreadContext.get("correlationId");
    if (correlationId != null) {
        requestTemplate.header("X-Correlation-Id", correlationId);
    }
    // null check: background threads (e.g., @Scheduled) have no MDC value — skip header
}
```

**Invocation timing:** `apply()` fires immediately before each Feign HTTP call, on the calling thread. It reads the MDC at call time, not at bean construction, so it always captures the current request's ID.

---

## MDC vs ThreadContext: The Doubt Explained

### The Short Answer

|                                          | Import            | What happens                                   |
| ---------------------------------------- | ----------------- | ---------------------------------------------- |
| `org.slf4j.MDC`                          | SLF4J abstraction | Delegates to Log4j2 `ThreadContext` via bridge |
| `org.apache.logging.log4j.ThreadContext` | Log4j2 native     | Direct — no bridge layer                       |

Both write to the same underlying thread-local map. `%X{correlationId}` in the Log4j2 pattern reads correctly from either.

### Why SLF4J MDC Works in a Log4j2 Project

When your `build.gradle` includes `log4j-slf4j-impl` (the Log4j2 ← SLF4J bridge), it registers a `StaticMDCBinder` implementation that routes every `MDC.put()` call to `ThreadContext.put()`. The bridge is:

```
org.slf4j.MDC.put("key", value)
    → log4j-slf4j-impl bridge
        → org.apache.logging.log4j.ThreadContext.put("key", value)
```

So the original code **worked** — it just took an unnecessary indirection.

### Why This Project Prefers ThreadContext Directly

1. **Consistency:** Every logger in the project is `LogManager.getLogger(...)` (Log4j2 native). Mixing `org.slf4j.MDC` creates two different API namespaces in the same codebase.
2. **Clarity:** A reader sees `ThreadContext.put()` and immediately knows it's Log4j2. They don't need to know that SLF4J MDC bridges to ThreadContext.
3. **No extra dependency:** If the SLF4J bridge were ever removed from the classpath (e.g., switching to `log4j-jul` or `log4j-jcl`), `MDC` calls would break silently. `ThreadContext` has no such dependency.

### The Fix Applied

In `MdcCorrelationFilter`:

```java
// Remove:  import org.slf4j.MDC;
// Add:     import org.apache.logging.log4j.ThreadContext;

ThreadContext.put("correlationId", correlationId);   // was MDC.put(...)
// ...
ThreadContext.remove("correlationId");               // was MDC.remove(...)
```

In `FeignCorrelationInterceptor`:

```java
// Remove:  import org.slf4j.MDC;
// Add:     import org.apache.logging.log4j.ThreadContext;

String correlationId = ThreadContext.get("correlationId");  // was MDC.get(...)
```

---

## Correlation ID vs TraceId vs SpanId

### The Doubt: Are They the Same? Why Not Use TraceId?

They solve the same problem (correlating requests across services) at different levels of complexity and infrastructure.

### The Hierarchy

```
Correlation ID (what we built — Phase 11)
─────────────────────────────────────────
One UUID per user request.
Generated at gateway, propagated via X-Correlation-Id header.
Stored in MDC / ThreadContext.
Appears on every log line.
No extra infrastructure.
Find all logs for a request: grep by UUID.

        ↑ simple subset of ↑

TraceId + SpanId (OpenTelemetry / Micrometer Tracing — Phase 9)
───────────────────────────────────────────────────────────────
TraceId: one ID per entire request tree (same purpose as Correlation ID)
SpanId: one ID per unit of work within the trace (service call, DB query)
ParentSpanId: links child span back to parent — forms a tree

Requires:
- Micrometer Tracing dependency
- An exporter (Zipkin / Jaeger)
- A running Zipkin/Jaeger server
- Produces structured span data (start time, end time, tags, parent-child links)
- Visualized in a flame-chart UI showing timing breakdown per service
```

### Side-by-Side Comparison

|                | Correlation ID (Phase 11)            | TraceId + SpanId (Phase 9)                |
| -------------- | ------------------------------------ | ----------------------------------------- |
| Scope          | One UUID per request                 | TraceId = request; SpanId = unit of work  |
| Infrastructure | None (UUID + HTTP header + MDC)      | Zipkin / Jaeger server required           |
| Data captured  | "This log line belongs to request X" | Timing, parent-child call graph, tags     |
| Visualization  | grep / log aggregator filter         | Flame chart (Zipkin UI / Jaeger UI)       |
| Propagation    | Manual (our filter + interceptor)    | Automatic via Micrometer instrumentation  |
| Spans created  | 1 per request total                  | 1 per service call + 1 per DB query + ... |
| Cost           | Zero                                 | Memory + network to exporter              |
| When to use    | Log correlation for debugging        | Performance profiling + deep tracing      |

### SpanId Is NOT Service-Level

The doubt phrased SpanId as "service-level." More precisely: **one SpanId per unit of work**. A single request touching 3 services produces:

```
TraceId: abc123 (same across all)
  Span 1: (gateway)            parentSpanId = null
  Span 2: (portfolio-service)  parentSpanId = span1
  Span 3: (order-service)      parentSpanId = span2
  Span 4: (DB query in order)  parentSpanId = span3
```

This tree structure is what Zipkin renders as a waterfall diagram. Correlation ID produces none of this — it just tags log lines.

### Why Not Use TraceId Immediately?

1. **Zero-infrastructure phase:** Phase 11 goal is log correlation with no new services to run. TraceId requires Zipkin/Jaeger as a dependency.
2. **Learning sequence:** Understanding manual propagation (what we built) makes OpenTelemetry's automatic propagation more meaningful — you already know what it's doing under the hood.
3. **TraceId IS the natural evolution:** In Phase 9, `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` will be added. Once those are on the classpath, Micrometer automatically propagates `X-B3-TraceId` / `X-B3-SpanId` headers (Brave B3 format) or `traceparent` (W3C format). At that point, `%X{correlationId}` in the pattern can be replaced with `%X{traceId}` — which Micrometer populates automatically.

---

## API Gateway: CorrelationIdGatewayFilter — Deep Dive

### Why the Gateway Needs Its Own Filter

The gateway is the **first service** a request hits. A browser or mobile app sends a request with NO `X-Correlation-Id` header. If the gateway doesn't generate one before forwarding, each downstream service's `MdcCorrelationFilter` would generate its own independent UUID — defeating the entire purpose of correlation.

The gateway must: (1) generate a UUID if the header is missing, (2) add it to the forwarded request, (3) echo it in the response.

---

### Why `default-filters` (YAML) Was Tried and Why It Failed

Spring Cloud Gateway's YAML has a `default-filters` section that applies built-in filters to all routes:

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - name: AddResponseHeader
          args:
            name: X-Correlation-Id
            value: "#{T(java.util.UUID).randomUUID().toString()}"
```

This was a reasonable first attempt: "add a header to every request." But it fails for three reasons:

| Problem | Why it breaks |
|---------|--------------|
| **Wrong direction** | `AddResponseHeader` adds to the **response** (back to browser). It does NOT add to the **forwarded request** (to downstream services). Downstream services never see the header. |
| **SpEL evaluated at startup** | `#{T(java.util.UUID).randomUUID().toString()}` is a Spring Expression evaluated once when the route configuration loads — not per request. Every request gets the same static UUID. |
| **No conditional logic** | Even if you used `AddRequestHeader` (correct direction), YAML filters cannot express "if header missing, generate; else preserve existing." You'd always overwrite caller-supplied IDs. |

**The underlying distinction:**

| | `default-filters` (YAML) | `GlobalFilter` (Java) |
|--|--|--|
| What it is | Declarative, built-in filter catalog | Full Java code hook |
| Available logic | String-named factories with fixed args | Any Java expression |
| Evaluated when | Route config load time (startup) | Per request (runtime) |
| Conditional? | No — always applies as configured | Yes — if/else, null checks, anything |
| Can read existing headers? | No | Yes — full access to `ServerWebExchange` |
| Examples | `AddRequestHeader`, `SetPath`, `RateLimiter` | Our `CorrelationIdGatewayFilter` |

**Bottom line:** Whenever your filter logic has a conditional ("if X then do Y"), you need a `GlobalFilter` Java bean. YAML filters are for unconditional, static transformations.

---

### Why `GlobalFilter` and Not `OncePerRequestFilter`?

Spring Cloud Gateway runs on **Netty** (non-blocking, event-loop) — NOT on Tomcat (blocking, thread-per-request). This means the entire Servlet API (`HttpServletRequest`, `HttpServletResponse`, `OncePerRequestFilter`) does not exist in the gateway.

| | API Gateway | Downstream Services (portfolio, order, etc.) |
|--|--|--|
| Server runtime | Netty (NIO event loop) | Tomcat (Servlet container) |
| Request type | `ServerWebExchange` (reactive) | `HttpServletRequest` (blocking) |
| Response type | `Mono<Void>` (non-blocking) | `void` (blocking) |
| Filter type | `GlobalFilter` | `OncePerRequestFilter` |
| Thread model | Event loop threads (few, never block) | Thread pool (200 threads, one per request) |

This is why you have **two different filter implementations** that do the same logical thing: one for the reactive gateway (GlobalFilter), one for all Servlet-based services (OncePerRequestFilter/MdcCorrelationFilter).

---

### The `Ordered` Interface — Why `HIGHEST_PRECEDENCE`?

Spring Cloud Gateway can have many `GlobalFilter` beans registered — security filters, rate limiters, logging filters, etc. `Ordered.getOrder()` determines their execution sequence.

```
HIGHEST_PRECEDENCE (-2147483648) ← our CorrelationIdGatewayFilter runs FIRST
    ...
    security filter (order 0)
    ...
    logging filter (order 100)
    ...
LOWEST_PRECEDENCE (2147483647) ← runs last
```

We want `HIGHEST_PRECEDENCE` because every other filter that logs (or that passes headers downstream) should already see the Correlation ID by the time it executes.

---

### Line-by-Line Walkthrough of `filter()` Method

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
```

**`ServerWebExchange`** — the reactive equivalent of `HttpServletRequest + HttpServletResponse` bundled together. Provides access to request headers, path, query params, AND the response object.

**`GatewayFilterChain`** — the reactive equivalent of `FilterChain.doFilter()`. Calling `chain.filter(exchange)` passes the request to the next filter in the chain (and eventually to the routed service).

**Return type `Mono<Void>`** — in reactive programming, nothing executes immediately. You build a pipeline (like a recipe), and the Netty event loop executes it when the subscriber asks. Returning `Mono<Void>` means "this filter does something eventually, then completes with no value." Unlike Servlet filters where `doFilter()` blocks the thread until the response comes back, here the thread is released immediately and the response is handled by a callback.

```java
    String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
    boolean wasGenerated = correlationId == null;
```

Read the header from the incoming request. If the browser or caller sent one, use it. The `wasGenerated` flag is just for the log message (informational).

```java
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
```

If no header (or an empty/blank one), generate a fresh UUID. This is the moment the Correlation ID is "born" for this request.

```java
    log.info("Incoming X-Correlation-Id: {} ({})", correlationId, wasGenerated ? "generated" : "forwarded");
```

Log whether we generated or reused. Useful for debugging header propagation issues.

```java
    ServerHttpRequest mutateRequest =
        exchange.getRequest().mutate().header("X-Correlation-Id", correlationId).build();
```

**The immutability problem:** `ServerHttpRequest` in WebFlux is immutable — there is no `request.addHeader(...)` method. You cannot modify it in place.

**The solution: `.mutate()`** returns a `Builder` that copies all existing data (method, URI, all headers, body) and lets you override specific fields. `.header("X-Correlation-Id", correlationId)` adds (or replaces) this single header. `.build()` produces a NEW `ServerHttpRequest` object.

The original request is untouched. This is deliberate — immutability prevents accidental side effects in the reactive pipeline where multiple operators might reference the same exchange.

```java
    ServerWebExchange mutatedExchange = exchange.mutate().request(mutateRequest).build();
```

Same pattern one level up: the `ServerWebExchange` is also immutable. Mutate it, plug in the new request, build a new exchange. Now `mutatedExchange` has the Correlation ID header in the request.

```java
    String finalCorrelationId = correlationId;
```

**Why this line exists:** Lambdas in Java can only capture **effectively final** local variables. `correlationId` was reassigned inside the `if` block (from null to UUID), so it's not "effectively final." Assigning to a new variable that is never reassigned makes it capturable by the lambda below.

```java
    return chain
        .filter(mutatedExchange)
```

Forward the **mutated exchange** (with the header) to the next filter in the chain, and eventually to the downstream service. This is where the request leaves the gateway and travels to portfolio-service / order-service.

```java
        .then(
            Mono.fromRunnable(
                () -> {
                  exchange.getResponse().getHeaders().add("X-Correlation-Id", finalCorrelationId);
                }));
```

**`.then(Mono.fromRunnable(...))`** — this is the "after the response comes back" hook. In Servlet filters, you'd put this code after `filterChain.doFilter()`. In reactive, the timeline is:

1. `chain.filter(mutatedExchange)` — sends request downstream, waits for response (non-blocking)
2. `.then(...)` — executes AFTER the downstream response has been fully received
3. Inside: add `X-Correlation-Id` to the response headers — so the browser/client can see it

**`Mono.fromRunnable()`** wraps a synchronous `Runnable` into a reactive `Mono`. Since `.add()` is a simple synchronous operation (no I/O), this is appropriate.

**Why `exchange.getResponse()` (original exchange) and not `mutatedExchange.getResponse()`?** The mutated exchange shares the same response object — mutation only changes the request. Both references point to the same `ServerHttpResponse`. Using either is identical; using `exchange` is convention.

---

### Why the Gateway Has NO MDC/ThreadContext

Notice the gateway filter does NOT call `ThreadContext.put(...)`. Why?

The gateway runs on Netty event loop threads — a **small fixed pool** (typically `2 * CPU cores`) that handles ALL concurrent requests via non-blocking I/O. A single thread might process fragments of 1000 different requests interleaved. MDC (thread-local) would be overwritten constantly and would produce garbage data.

MDC/ThreadContext only makes sense where one thread handles one request from start to finish (Servlet containers like Tomcat). In the reactive gateway, the Correlation ID is propagated exclusively via HTTP headers — no thread-local needed.

Downstream services (Tomcat-based) then extract the header and store it in ThreadContext for their local log lines.

---

## Request Flow: Step-by-Step Example

Scenario: User calls `POST /api/sell-to-spend` → gateway → portfolio-service → order-service

```
Step 1: Browser sends request with no X-Correlation-Id header

Step 2: API Gateway CorrelationIdGatewayFilter runs
        → correlationId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        → adds X-Correlation-Id to forwarded request

Step 3: PORTFOLIO-SERVICE receives request
        → MdcCorrelationFilter reads X-Correlation-Id: a1b2c3d4-...
        → ThreadContext.put("correlationId", "a1b2c3d4-...")
        → Log4j2 pattern now prints [a1b2c3d4-...] on every line

Step 4: SellToSpendSagaOrchestrator.executeSaga() runs
        → log.info("Saga created: sagaId=..., orderId=...")
        Console: 2026-06-10 15:03:21 [http-nio-8084-exec-3] [a1b2c3d4-...] INFO  SellToSpendSagaOrchestrator - Saga created...

Step 5: OrderFeignClient.updateOrderStatus() called
        → FeignCorrelationInterceptor.apply() fires
        → reads ThreadContext.get("correlationId") = "a1b2c3d4-..."
        → adds X-Correlation-Id: a1b2c3d4-... to the outgoing HTTP request

Step 6: ORDER-SERVICE receives the Feign HTTP request
        → MdcCorrelationFilter reads X-Correlation-Id: a1b2c3d4-...
          (header was set in step 5 — it already exists)
        → ThreadContext.put("correlationId", "a1b2c3d4-...")
        → all log lines in order-service carry the same ID

Step 7: OrderServiceImpl.updateOrderStatus() runs
        Console: 2026-06-10 15:03:22 [http-nio-8081-exec-5] [a1b2c3d4-...] INFO  OrderServiceImpl - Order 42 status → CONFIRMED

Step 8: ORDER-SERVICE response returns
        → MdcCorrelationFilter finally: ThreadContext.remove("correlationId")

Step 9: PORTFOLIO-SERVICE Feign call completes
        → SagaOrchestrator continues, logs completion
        Console: 2026-06-10 15:03:22 [http-nio-8084-exec-3] [a1b2c3d4-...] INFO  SellToSpendSagaOrchestrator - Saga completed...
        → MdcCorrelationFilter finally: ThreadContext.remove("correlationId")

Step 10: Response reaches browser with X-Correlation-Id: a1b2c3d4-... header
         → Client-side JS can log this ID for frontend error reports
```

**Result:** `grep a1b2c3d4 *.log` across all service logs produces the complete call chain for this one request.

---

## ThreadContext Internals

`org.apache.logging.log4j.ThreadContext` stores two thread-local structures:

| Structure       | API                                            | Pattern token | Use                                     |
| --------------- | ---------------------------------------------- | ------------- | --------------------------------------- |
| Map (key-value) | `put(key, value)` / `get(key)` / `remove(key)` | `%X{key}`     | Correlation ID, userId, tenantId        |
| Stack           | `push(value)` / `pop()`                        | `%x`          | Nested diagnostic context (rarely used) |

**Thread-local means:** Each thread has its own independent copy of the map. `ThreadContext.put()` on thread A does not affect thread B's map. This is essential for servlet containers where threads handle different concurrent requests.

**The cleanup rule:**

- `ThreadContext.remove("correlationId")` removes only that key
- `ThreadContext.clearMap()` removes all keys
- Prefer `remove()` over `clearMap()` in a filter — other filters up the chain may have legitimately added their own MDC entries; clearing everything destroys their work too

---

## What Happens in Background Threads

`@Scheduled` tasks (like `SellToSpendSagaOrchestrator.detectTimedOutSagas()`) run on Spring's scheduled task executor threads — not HTTP request threads. They never pass through `MdcCorrelationFilter`, so `ThreadContext.get("correlationId")` returns `null`.

Effects:

1. Log pattern prints `[]` for correlationId — blank, not a real ID
2. `FeignCorrelationInterceptor.apply()` sees `null` and skips the header — correct behavior
3. The receiving service generates a fresh UUID for the background-initiated call

If you want scheduled tasks to carry a traceable ID:

```java
@Scheduled(fixedRate = 30000)
public void detectTimedOutSagas() {
    ThreadContext.put("correlationId", "SCHEDULED-" + UUID.randomUUID());
    try {
        // ... task body
    } finally {
        ThreadContext.remove("correlationId");
    }
}
```

---

## Verifying It Works

```bash
# 1. Hit any endpoint without a Correlation ID header
curl -v http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <token>"
# Observe: response headers include X-Correlation-Id: <generated-uuid>

# 2. Hit with a pre-set Correlation ID
curl -v http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <token>" \
  -H "X-Correlation-Id: my-test-id-001"
# Observe: response echoes X-Correlation-Id: my-test-id-001

# 3. Check logs across services
# portfolio-service log should show: [my-test-id-001]
# order-service log (if portfolio called it) should also show: [my-test-id-001]

# 4. Grep across combined logs
grep "my-test-id-001" logs/*.log
```

---

## Summary Table

| Component                          | File                 | What it does                                                  |
| ---------------------------------- | -------------------- | ------------------------------------------------------------- |
| `MdcCorrelationFilter`             | `commons/filter/`    | Per-request: read header → ThreadContext.put → echo → cleanup |
| `FeignCorrelationInterceptor`      | `commons/feign/`     | Per Feign call: ThreadContext.get → add as header             |
| `CorrelationIdGatewayFilter`       | `api-gateway` module | Per gateway request: generate UUID if header absent           |
| Log4j2 pattern `%X{correlationId}` | `application.yml`    | Prints MDC value on every log line, zero code change          |
| `X-Correlation-Id` header          | HTTP protocol        | The carrier that moves the ID across process boundaries       |
