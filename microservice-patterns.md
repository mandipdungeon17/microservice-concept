# Microservice Patterns — Deep Dive Learning Document

> This document covers microservice architecture patterns in depth as they relate to the EquityCart project.
> Updated progressively as new patterns are implemented across Phase 6+.
> For Kafka-specific concepts, see `kafka-learning.md`.

---

## 1. The Outbox Pattern

**Why "Outbox"?** The name comes from physical mail. Before email, offices had an "outbox" tray on the desk — you'd place finished letters there, and a mail clerk would pick them up periodically and deliver them to the post office. You didn't walk to the post office yourself (risky — you might get hit by a bus on the way). Similarly, in the pattern: your service writes the message to a database "outbox" table (safe, atomic with your business write), and a separate "clerk" process (the poller) picks it up and delivers it to Kafka. The service never talks to Kafka directly — just like you never walked to the post office.

### 1.1 The Problem: Dual Writes

When a service needs to both **update its database** AND **publish an event to Kafka**, you have two independent systems that cannot share a transaction. This is the "dual-write problem."

```
Service Method:
  1. orderRepository.save(order)       ← DB transaction commits
  2. kafkaTemplate.send(event)          ← Network call to Kafka broker

Failure Scenarios:
  A) Step 1 succeeds, step 2 fails (Kafka down/timeout)
     → Order is DELIVERED in DB, but no event published
     → Consumer never runs, user never gets stock-back reward
     → DATA PERMANENTLY INCONSISTENT

  B) Step 2 succeeds, step 1 rolls back (constraint violation after send)
     → Event published for an order that isn't actually DELIVERED
     → Consumer grants a reward for a non-delivered order
     → DATA PERMANENTLY INCONSISTENT
```

**Root cause:** You're coordinating two systems without a shared transaction boundary. Neither system knows whether the other succeeded.

**Why not distributed transactions (2PC)?** Two-Phase Commit (XA transactions) exists but is impractical for Kafka:

- Kafka doesn't participate in XA (not a JTA resource)
- 2PC has terrible latency (coordinator round-trips)
- A single coordinator failure blocks all participants
- Almost no modern microservice architecture uses 2PC for this reason

### 1.2 The Solution: Write to One System, Relay to the Other

Instead of writing to DB AND Kafka simultaneously, write ONLY to the DB — but include the event payload in a special "outbox" table within the same DB transaction. A separate background process reads the outbox and publishes to Kafka.

```
BEFORE (dual write — unsafe):
  ┌──────────────────────────────────────────────────┐
  │ updateOrderStatus()                              │
  │   1. order.setStatus(DELIVERED)                  │
  │   2. orderRepository.save(order)    → DB         │
  │   3. kafkaTemplate.send(event)      → Kafka      │  ← can fail independently
  └──────────────────────────────────────────────────┘

AFTER (outbox — safe):
  ┌──────────────────────────────────────────────────┐
  │ updateOrderStatus()                              │
  │   @Transactional                                 │
  │   1. order.setStatus(DELIVERED)                  │
  │   2. orderRepository.save(order)    → DB         │
  │   3. outboxRepository.save(event)   → DB (same!) │  ← ATOMIC: both or neither
  └──────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────┐
  │ OutboxPoller (background, every N seconds)       │
  │   1. SELECT * FROM outbox WHERE status=PENDING   │
  │   2. kafkaTemplate.send(row.payload)  → Kafka    │
  │   3. UPDATE outbox SET status=SENT               │
  └──────────────────────────────────────────────────┘
```

### 1.3 Why This Works — Guarantees

| Scenario                                           | What Happens                       | Result                                     |
| -------------------------------------------------- | ---------------------------------- | ------------------------------------------ |
| DB transaction commits                             | Both order + outbox row exist      | Poller will eventually publish             |
| DB transaction rolls back                          | Neither order nor outbox row exist | Nothing to publish, no inconsistency       |
| Poller publishes, then crashes before marking SENT | Row stays PENDING                  | Next poll retries → at-least-once delivery |
| Kafka down during poll                             | Send fails, row stays PENDING      | Retried next poll cycle                    |

**Delivery guarantee:** At-least-once. The consumer must be **idempotent** — our `findByOrderIdAndTickerSymbol` check in `grantReward()` handles duplicate deliveries gracefully.

### 1.4 The Outbox Table Schema

```
outbox_events
┌──────────────┬───────────────────────────────────────────────────────────────┐
│ Column       │ Purpose                                                       │
├──────────────┼───────────────────────────────────────────────────────────────┤
│ id           │ PK (auto-generated)                                           │
│ aggregateType│ "Order" — domain object that triggered the event              │
│ aggregateId  │ orderId — the specific instance (useful for debugging/queries)│
│ eventType    │ "ORDER_DELIVERED" — what happened (enum-like string)           │
│ topic        │ "order-delivered" — Kafka topic to publish to                  │
│ payload      │ JSON string — the complete event DTO, serialized              │
│ status       │ PENDING → SENT — lifecycle of this outbox row                 │
│ publishedAt  │ Timestamp when poller successfully published to Kafka         │
│ createdAt    │ When the row was inserted (via BaseEntity)                    │
└──────────────┴───────────────────────────────────────────────────────────────┘
```

**Why store JSON string?** The outbox is a generic relay mechanism. It doesn't need to understand the event structure — it just stores bytes and forwards them. This means one outbox table handles ALL event types across the service.

### 1.5 Serialization Flow

```
PRODUCER SIDE (OrderOutboxWriter):
  OrderDeliveredEvent (Java object)
       │
       ▼  objectMapper.writeValueAsString(event)
  "{\"orderId\":42,\"userId\":5,...}" (JSON String)
       │
       ▼  stored in outbox_events.payload column
  [DB Row: PENDING]

POLLER SIDE (OutboxPoller):
  [DB Row: PENDING] → reads payload string
       │
       ▼  objectMapper.readValue(payload, Class.forName(payloadType))
  OrderDeliveredEvent (Java object — re-hydrated)
       │
       ▼  kafkaTemplate.send(topic, key, event)  ← JsonSerializer adds __TypeId__ header
  [Kafka message with headers: __TypeId__=com.equitycart.commons.event.OrderDeliveredEvent]

CONSUMER SIDE (StockBackRewardConsumer):
  [Kafka message] → JsonDeserializer reads __TypeId__ header
       │
       ▼  Deserializes JSON bytes into OrderDeliveredEvent
  handleOrderDelivered(OrderDeliveredEvent event)  ← works unchanged
```

**Key insight:** If the poller sends a raw String via KafkaTemplate, the `JsonSerializer` sets `__TypeId__: java.lang.String`. The consumer's `JsonDeserializer` then tries to create a String — NOT your event DTO. The consumer breaks with ClassCastException.

**Solution:** The poller must re-hydrate the JSON back into the original event DTO (using `objectMapper.readValue()`), THEN send the typed object through KafkaTemplate. This way, `JsonSerializer` adds the correct `__TypeId__` header, and the consumer works unchanged.

### 1.6 The Poller — Design Considerations

**Why blocking `.get()` instead of async `whenComplete()`?**

```java
// WRONG — callback runs on Kafka's I/O thread, outside Spring transaction
kafkaTemplate.send(...).whenComplete((result, e) -> {
    outboxEvent.setStatus(SENT);        // ← no @Transactional context here
    outboxEventRepository.save(event);  // ← may throw TransactionRequiredException
});

// CORRECT — blocks within @Transactional method
SendResult result = kafkaTemplate.send(...).get();  // blocks until Kafka ACKs
outboxEvent.setStatus(SENT);
outboxEventRepository.save(event);  // ← inside active transaction
```

The `.get()` call blocks the poller thread until Kafka acknowledges the message. This is intentional — the poller is a background job, not a user-facing request. Blocking is acceptable here because:

1. It ensures `save(SENT)` runs within the same `@Transactional` boundary
2. It simplifies error handling (try-catch around `.get()`)
3. Poller throughput is bounded by batch size, not latency sensitivity

**Polling frequency:** 5 seconds is a reasonable default. This adds up to 5s latency between order delivery and consumer processing. For most business events this is acceptable. For lower latency, consider Change Data Capture (CDC) with Debezium.

### 1.7 Outbox Pattern Variants

| Variant                               | How It Works                                         | Trade-off                                                                   |
| ------------------------------------- | ---------------------------------------------------- | --------------------------------------------------------------------------- |
| **Polling Publisher** (what we built) | Scheduled job polls outbox table                     | Simple, slight latency, DB load from polling                                |
| **Transaction Log Tailing (CDC)**     | Debezium reads MySQL/Postgres WAL, publishes changes | Near-zero latency, no polling, but complex infra (Debezium + Kafka Connect) |
| **Listen/Notify** (Postgres)          | DB triggers notify app on insert                     | Low latency, Postgres-specific, less portable                               |

### 1.8 Historical Context

The Outbox Pattern was popularized by **Chris Richardson** in "Microservices Patterns" (2018), though the underlying idea (write intent to local DB, relay asynchronously) dates back to the **Transactional Outbox** concept in enterprise integration patterns from the early 2000s.

The pattern became essential with the rise of microservices (2014-2016) when teams discovered that splitting a monolith's single ACID transaction across services created data consistency nightmares. Netflix, Uber, and Airbnb all wrote blog posts documenting their version of this pattern between 2016-2018.

**Debezium** (Red Hat, 2016) later industrialized this with Change Data Capture — instead of polling the outbox table, it reads the database's transaction log directly. This eliminated polling overhead and reduced latency to milliseconds.

### 1.9 The Generic Poller — `payloadType` Column

A naive outbox poller hardcodes the deserialization target:

```java
// BAD — one method per event type, doesn't scale
OrderDeliveredEvent event = objectMapper.readValue(payload, OrderDeliveredEvent.class);
```

A generic poller uses the `payloadType` column (FQCN stored at write time):

```java
// GOOD — handles ANY event type without modification
Class<?> clazz = Class.forName(outboxEvent.getPayloadType());
Object event = objectMapper.readValue(outboxEvent.getPayload(), clazz);
kafkaTemplate.send(outboxEvent.getTopic(), key, event).get();
```

**Why this matters:** When you add a third event type (e.g., `OrderCancelledEvent`), you only need to:
1. Create the DTO class
2. Call `outboxWriter.writeOutboxEvent(...)` with the new event

The poller handles it automatically — zero changes. This is the Open/Closed Principle applied to infrastructure: open for extension (new event types), closed for modification (poller code never changes).

**How `payloadType` is populated:**
```java
event.getClass().getName()  // → "com.equitycart.commons.event.OrderDeliveredEvent"
```

Using `.getClass().getName()` instead of hardcoded strings ensures the FQCN is always correct, even after package renames (the compiler would catch the broken reference).

### 1.10 The Outbox as Infrastructure vs. Domain

The outbox has ONE job: reliably relay messages from DB to Kafka. It has:
- **No domain knowledge** — doesn't care if the event is "delivered" or "returned"
- **One lifecycle**: `PENDING → SENT`
- **No business logic** — just serialize, store, poll, send, mark done

Domain concepts (`RETURNED`, `CANCELLED`, `VESTED`) belong in domain enums. Mixing them into `OutboxStatus` would couple infrastructure to business rules — meaning every new order status requires modifying the outbox system.

### 1.11 EquityCart Phase 6 — Complete Architecture Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           ORDER-SERVICE                                          │
│                                                                                 │
│  ┌──────────────────────────┐     ┌──────────────────────────┐                  │
│  │   OrderServiceImpl       │     │   OrderOutboxWriter      │                  │
│  │                          │     │                          │                  │
│  │  updateOrderStatus()     │     │  writeOutboxDelivered()  │                  │
│  │    1. order.setStatus()  │────▶│  writeOutboxReturned()   │                  │
│  │    2. orderRepo.save()   │     │    - serialize to JSON   │                  │
│  │    3. outboxWriter.write │     │    - store FQCN          │                  │
│  │                          │     │    - save PENDING row    │                  │
│  └──────────────────────────┘     └────────────┬─────────────┘                  │
│         @Transactional (atomic)                │                                │
│                                                ▼                                │
│                                    ┌──────────────────────┐                     │
│                                    │   outbox_events (DB)  │                    │
│                                    │   status = PENDING    │                    │
│                                    └──────────┬───────────┘                     │
│                                               │                                 │
│  ┌────────────────────────────────────────────┼──────────────────────────────┐  │
│  │   OutboxPoller (@Scheduled every 5s)       │                              │  │
│  │                                            ▼                              │  │
│  │   1. SELECT * FROM outbox WHERE status=PENDING                            │  │
│  │   2. Class.forName(payloadType) → re-hydrate JSON to DTO                  │  │
│  │   3. kafkaTemplate.send(topic, key, event).get()  ← blocks until ACK      │  │
│  │   4. UPDATE status=SENT, publishedAt=now                                  │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                                               │                                 │
└───────────────────────────────────────────────┼─────────────────────────────────┘
                                                │ Kafka
                                                ▼
                  ┌─────────────────────────────────────────────────────┐
                  │                  KAFKA BROKER                        │
                  │                                                     │
                  │   Topics:                                           │
                  │   ┌─────────────────────┐  ┌─────────────────────┐ │
                  │   │  order-delivered    │  │  order-returned     │ │
                  │   └─────────┬───────────┘  └──────────┬──────────┘ │
                  │             │                          │            │
                  │   ┌─────────┴───────────┐  ┌──────────┴──────────┐ │
                  │   │ order-delivered.DLT │  │ order-returned.DLT  │ │
                  │   │ (Dead Letter Topic) │  │ (Dead Letter Topic) │ │
                  │   └─────────────────────┘  └─────────────────────┘ │
                  └───────────────────┬────────────────────┬───────────┘
                                      │                    │
                                      ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        PORTFOLIO-SERVICE                                         │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │   StockBackRewardConsumer                                                │   │
│  │                                                                          │   │
│  │   @KafkaListener("order-delivered", group="equitycart-reward-group")     │   │
│  │   handleOrderDelivered(OrderDeliveredEvent):                             │   │
│  │     1. For each item → lookup Product → Brand → BrandTickerMapping       │   │
│  │     2. Group by ticker, sum (subtotal × stockBackPercentage)             │   │
│  │     3. Get current price from MarketDataService                          │   │
│  │     4. sharesEarned = rewardDollarValue / currentPrice                   │   │
│  │     5. portfolioService.grantReward() [idempotent on orderId+ticker]     │   │
│  │                                                                          │   │
│  │   @KafkaListener("order-returned", group="equitycart-cancellation-group")│   │
│  │   handleOrderReturned(OrderReturnedEvent):                               │   │
│  │     1. Find all rewards by orderId                                       │   │
│  │     2. If PENDING → set CANCELLED, save                                  │   │
│  │     3. If VESTED → log warning (manual review needed)                    │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │   Error Handling (KafkaConsumerConfig — DefaultErrorHandler)             │   │
│  │                                                                          │   │
│  │   Exception → Retry 1 (1s) → Retry 2 (1s) → Retry 3 (1s) → DLT        │   │
│  │   Non-retryable (DeserializationException, NPE) → DLT immediately       │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │   Reward Lifecycle (StockBackReward entity)                              │   │
│  │                                                                          │   │
│  │   PENDING ──(30 days vesting)──▶ VESTED ──▶ Holding credited            │   │
│  │      │                                                                   │   │
│  │      └──(order returned)──▶ CANCELLED                                    │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 1.12 Outbox Pattern — CDC Variant (Debezium)

The polling variant has a fundamental trade-off: **latency vs DB load**. Polling every 5 seconds adds up to 5s delivery delay and generates constant SELECT queries regardless of whether events exist. The CDC variant eliminates both.

**How it works:**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     CDC (Debezium) Variant                                │
│                                                                         │
│  ┌──────────────┐    INSERT     ┌──────────────┐    WAL stream         │
│  │ Application  │──────────────▶│ PostgreSQL   │──────────────┐        │
│  │              │  (same tx as  │              │              │        │
│  │  1. save()   │   business    │ outbox_events│              ▼        │
│  │  2. outbox() │   write)      └──────────────┘   ┌──────────────┐   │
│  └──────────────┘                                  │ Debezium     │   │
│                                                    │ (Kafka Connect)│  │
│  OutboxPoller: DISABLED (@Profile("!cdc"))         │              │   │
│                                                    │ Outbox Event │   │
│                                                    │ Router SMT:  │   │
│                                                    │ - extract    │   │
│                                                    │   payload    │   │
│                                                    │ - route to   │   │
│                                                    │   topic col  │   │
│                                                    └───────┬──────┘   │
│                                                            │          │
│                                                            ▼          │
│                                                    ┌──────────────┐   │
│                                                    │ Kafka Topic  │   │
│                                                    │ (order-      │   │
│                                                    │  delivered)  │   │
│                                                    └──────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key differences from Polling variant:**

| Aspect | Polling Variant | CDC Variant |
|--------|----------------|-------------|
| Relay mechanism | Java @Scheduled job (SELECT → send → UPDATE) | Debezium reads PostgreSQL WAL |
| Latency | Up to poll interval (5s) | Milliseconds (WAL is near-real-time) |
| DB load | Repeated SELECT queries every 5s | Zero queries (reads WAL stream) |
| Status column | Meaningful: PENDING → SENT | Vestigial: stays PENDING forever |
| `__TypeId__` header | Present (KafkaTemplate adds it) | Absent (Debezium is not Spring-aware) |
| Infrastructure | Just Java code (no external deps) | Kafka Connect + Debezium container |
| Resume on restart | Re-reads PENDING rows | Resumes from WAL LSN position |
| Delivery guarantee | Confirmed: `.get()` blocks until Kafka ACK | Confirmed: Kafka Connect offset tracking |

**Why the status column is vestigial in CDC mode:**

In polling mode, the status column has operational meaning — the poller reads PENDING rows and marks them SENT after Kafka confirms. In CDC mode, Debezium reads the INSERT directly from the WAL the moment it's committed. Nothing updates the row because:
1. Debezium has no write-back mechanism to the source database
2. The OutboxPoller is disabled via `@Profile("!cdc")`
3. There's no feedback loop from "Kafka received it" back to the outbox table

The outbox table in CDC mode is a **write-only append log**. Maintenance: periodic `DELETE WHERE created_at < NOW() - INTERVAL '7 days'` regardless of status.

**CDC drawbacks and failure modes:**

| Drawback | Impact | Mitigation |
|----------|--------|------------|
| WAL disk growth | `logical` WAL level produces more data than `replica` | Monitor `pg_wal` size, tune `wal_keep_size` |
| Replication slot retention | If Debezium is down, PostgreSQL retains WAL segments until it reconnects — can fill disk | Alerting on `pg_replication_slots.active = false`, set `max_slot_wal_keep_size` |
| No `__TypeId__` header | Spring consumers can't auto-detect type | `spring.json.value.default.type` per listener |
| `@Lob` incompatibility | OID storage invisible to WAL | Use `@Column(columnDefinition = "text")` |
| Snapshot on first start | Dumps all existing rows (duplicates) | `snapshot.mode=never` for outbox tables |
| Timezone mismatch | Host timezone vs Docker UTC for timestamp fields | Don't use app timestamps as Kafka message timestamps |
| Column naming | Hibernate snake_case vs Debezium default camelCase | Explicit `table.field.*` mappings in connector config |
| Operational complexity | Kafka Connect cluster to manage, monitor, upgrade | Worth it only at scale; polling is simpler for low-volume |

**When to use which:**

| Scenario | Recommended Variant |
|----------|-------------------|
| Learning/prototyping | Polling (simpler, no infra) |
| Low-volume monolith (< 100 events/min) | Polling (adequate, minimal ops) |
| High-volume or latency-sensitive | CDC (sub-second delivery, no polling overhead) |
| Multiple databases/services | CDC (one Debezium cluster serves all) |
| No Docker/container infrastructure | Polling (pure Java, no external deps) |

**Mode switching safety:** If switching from CDC to polling (removing `cdc` profile), the OutboxPoller will pick up ALL rows with `status=PENDING` — including ones already published by Debezium. This causes duplicate publishing. Mitigation: truncate the outbox table before switching modes, or add a `created_at` filter to the poller (only process rows newer than switch timestamp).

---

## 2. The Saga Pattern

**Why "Saga"?** The term comes from a 1987 paper by Hector Garcia-Molina and Kenneth Salem at Princeton. They needed a way to handle "long-lived transactions" (LLTs) that spanned minutes or hours — too long to hold database locks. Their solution: break the LLT into a sequence of smaller transactions, each with a corresponding "compensating transaction" to undo it. They called this sequence a "saga" — like a narrative with chapters that can be undone in reverse order.

### 2.1 The Problem: Distributed Transactions

In a monolith, you wrap multiple operations in one `@Transactional`:

```
@Transactional
sellToSpend():
  1. portfolioService.reduceHolding()     ← same DB
  2. ledgerService.recordTransaction()    ← same DB
  3. orderService.updateOrderStatus()     ← same DB
  → All succeed or ALL roll back (ACID)
```

In microservices (separate databases per service), this breaks:

```
sellToSpend():
  1. POST portfolio-service/reduce        ← Portfolio DB
  2. POST ledger-service/record           ← Ledger DB
  3. POST order-service/confirm           ← Order DB
  → Step 3 fails. Steps 1+2 already committed. No rollback possible!
```

**Why can't you use distributed transactions (2PC)?** Two-Phase Commit requires all participants to hold locks until the coordinator says "commit." This creates tight coupling, latency (synchronous round-trip), and single point of failure (coordinator). Google's Spanner does this with atomic clocks — most systems can't.

### 2.2 The Solution: Saga with Compensating Transactions

Instead of rolling back, you **undo** completed steps with new forward operations:

```
Saga: Sell-to-Spend
┌─────────────────────────────────────────────────────────────────┐
│  Step 1: reduceHolding()         Compensate: addOrUpdateHolding()  │
│  Step 2: recordTransaction()     Compensate: recordReversal()      │
│  Step 3: updateOrderStatus()     Compensate: (not needed — last)   │
└─────────────────────────────────────────────────────────────────┘
```

If step 3 fails:
1. Compensate step 2: record a REVERSAL ledger entry (HOLDING_ASSET ← CASH)
2. Compensate step 1: re-add the shares to the portfolio
3. Saga ends in COMPENSATED state

**Key insight:** Compensation is NOT a rollback. It's a new business operation that semantically undoes the previous one. The intermediate states (shares removed, ledger entry created) WERE visible to other transactions — this is **eventual consistency**, not ACID atomicity.

### 2.3 Orchestration vs. Choreography

| | Orchestration | Choreography |
|---|---|---|
| **Coordination** | Central orchestrator drives each step | Each service reacts to events from previous step |
| **Flow visibility** | Entire saga readable in one class | Logic scattered across multiple event listeners |
| **Coupling** | Orchestrator knows all steps | Services know only their own step + next event |
| **Error handling** | Orchestrator decides what to compensate | Each service must know its own compensation trigger |
| **Best for** | Complex multi-step flows, clear sequences | Simple 2-3 step flows, loose coupling |
| **Example** | EquityCart Sell-to-Spend Saga | Order-Delivered → Reward-Consumer (Phase 6 Steps 4-5) |

**EquityCart uses Orchestration** — the `SellToSpendSagaOrchestrator` class knows the full 3-step sequence and handles all compensation logic in one place.

### 2.4 Saga State Machine

The orchestrator persists a **saga entity** (`SellToSpendSaga`) to the database at every step boundary:

```
┌─────────┐     ┌──────────────────┐     ┌─────────────────┐
│ STARTED │────▶│ REDUCING_HOLDING │────▶│ HOLDING_REDUCED │
└─────────┘     └──────────────────┘     └─────────────────┘
                                                  │
                                                  ▼
┌───────────┐     ┌──────────────────┐     ┌──────────────────┐
│ COMPLETED │◀────│ CONFIRMING_ORDER │◀────│ RECORDING_LEDGER │
└───────────┘     └──────────────────┘     └──────────────────┘
                                                  │
                                           (on failure)
                                                  ▼
                  ┌──────────────┐          ┌──────────────┐
                  │ COMPENSATED  │◀─────────│ COMPENSATING │
                  └──────────────┘          └──────────────┘
                         │                         │
                    (success)                 (failure)
                                                  ▼
                                           ┌────────┐
                                           │ FAILED │
                                           └────────┘
```

**Why persist at every step?** If the app crashes between steps 1 and 2, the saga row shows `HOLDING_REDUCED`. On restart, the timeout detector finds it and either retries step 2 or compensates step 1. Without persistence, the saga state is lost on crash — shares removed but never compensated.

### 2.5 Idempotency

Sagas execute in an at-least-once environment (retries, timeout recovery). Every step must be safe to call twice:

| Strategy | How it works | EquityCart example |
|----------|-------------|-------------------|
| Status gate | Check saga status before executing — skip if already past this step | If status already `HOLDING_REDUCED`, don't call `reduceHolding()` again |
| Natural idempotency | The operation itself rejects duplicates | `updateOrderStatus(CONFIRMED)` on already-confirmed order throws `InvalidStatusTransitionException` |
| Unique constraints | DB constraint prevents double-write | Saga entity with `orderId` lookup prevents duplicate saga creation |

### 2.6 Timeout Detection

A scheduled job polls for "stuck" sagas — those in non-terminal states beyond a configurable threshold:

```
@Scheduled(fixedRate = 30000)
detectTimedOutSagas():
  → Find sagas where updatedAt < (now - 30s) AND status NOT IN (COMPLETED, COMPENSATED, FAILED)
  → Determine completedSteps from current status
  → Run compensation from last known-good state
```

In production (distributed services with network latency), timeouts are minutes to hours. For the EquityCart monolith (same-JVM calls), 30 seconds demonstrates the concept without slowing tests.

### 2.7 Saga vs. @Transactional — When to Use Which

| Criteria | @Transactional | Saga |
|----------|---------------|------|
| Same database | ✅ Use this | Overkill |
| Separate databases | Not possible | ✅ Required |
| Code complexity | ~50 lines | ~300+ lines |
| Consistency | Strong (ACID) | Eventual |
| Intermediate visibility | None (isolated) | Visible (other transactions can see partial state) |
| Failure recovery | Automatic rollback | Manual compensation |
| Performance | Single commit | Multiple commits + saga saves |

**Rule of thumb:** Use `@Transactional` when you can. Use Sagas when you must (separate databases, separate deployments, cross-network boundaries).

### 2.8 EquityCart Implementation

```
equitycart/portfolio/src/main/java/com/equitycart/portfolio/saga/
├── enums/SagaStatus.java                    ← State machine enum
├── entity/SellToSpendSaga.java              ← JPA entity (recovery log)
├── repository/SellToSpendSagaRepository.java ← Queries for idempotency + timeout
├── orchestrator/SellToSpendSagaOrchestrator.java ← The brain
├── service/SellToSpendSagaServiceImpl.java  ← SellToSpendService impl (saga mode)
└── event/SagaOutboxWriter.java              ← Lifecycle events to Kafka
```

**Toggle:** `equitycart.sell-to-spend.strategy=saga` vs `transactional` in `application.yml`. Both implement the same `SellToSpendService` interface — controller/facade code unchanged.

### 2.9 Compensating Transaction Design Rules

1. **Compensations are forward operations** — never try to "undo" at the database level (DELETE the row). Instead, create a new operation that semantically reverses the effect.
2. **Compensations must be idempotent** — they may be retried if the saga crashes during compensation.
3. **Order matters** — compensate in REVERSE order of execution (last completed step first).
4. **Not all steps need compensation** — the last step in a saga never needs compensation (nothing runs after it to fail).
5. **Compensation can fail** — if it does, the saga is FAILED and requires manual intervention (alerts, admin dashboard).

---

## 3. Event Sourcing Pattern

### 3.1 Core Concept

Event Sourcing stores every state change as an immutable event in an append-only log. Instead of overwriting the current state (`UPDATE holdings SET quantity = 5`), you record the fact that caused the change (`SHARES_PURCHASED: qty=5, price=150`). The current state is always derivable by replaying all events from the beginning.

**Traditional CRUD:** State → Overwrite → State (history lost)
**Event Sourcing:** Event₁ → Event₂ → ... → Eventₙ → replay → Current State

### 3.2 Key Components

| Component | Purpose | EquityCart Implementation |
|-----------|---------|--------------------------|
| Event Store | Append-only persistence of events | MongoDB `portfolio_events` collection |
| Event | Immutable fact with type, data, timestamp, sequence | `PortfolioEvent` @Document |
| Projection | Function that replays events → read model | `PortfolioProjectionService.rebuildHoldings()` |
| Sequence Number | Total ordering per aggregate | Per-user monotonic counter |

### 3.3 Event Document Structure

```
{
  eventId: UUID (idempotency key),
  userId: Long (aggregate ID),
  eventType: "SHARES_PURCHASED",
  tickerSymbol: "AAPL",
  quantity: 5.000000,
  pricePerShare: 150.0000,
  totalValue: 750.0000,
  metadata: { tradeType: "BUY" },
  timestamp: 2026-05-27T12:00:00Z,
  sequenceNumber: 1
}
```

### 3.4 Projection Replay Logic

```
state = {}
for each event in order:
  if event is ADD type (BUY, VEST, COMPENSATE, REFUND):
    state[ticker].qty += event.qty
    state[ticker].avg = weighted_average(old, new)
  if event is REMOVE type (SELL, SELL_TO_SPEND):
    state[ticker].qty -= event.qty
    // avg price unchanged on sells
return state
```

### 3.5 Dual-Write Architecture (EquityCart Approach)

```
Service Operation
    │
    ├──① PostgreSQL: UPDATE holding (authoritative state)
    │
    └──② MongoDB: INSERT event (best-effort audit trail)
         │
         └── try-catch: failure logged as WARN, doesn't break ①
```

**Why dual-write instead of pure event sourcing?**
- No risky migration of existing PostgreSQL infrastructure
- Portfolio read operations still hit fast indexed SQL (not replay)
- Event store adds audit/history without changing core behavior
- Can validate both stores agree via projection endpoint

**Risk:** Store drift if MongoDB write fails. Mitigated by best-effort semantics — acceptable for audit/analytics, unacceptable for billing/compliance (where you'd use the Outbox Pattern to guarantee event persistence).

### 3.6 Comparison: Event Sourcing vs CRUD vs Outbox

| Aspect | CRUD | Outbox | Event Sourcing |
|--------|------|--------|----------------|
| State storage | Current only | Current + outbox events | Events only (state derived) |
| History | Lost on update | Events have delivery purpose | Full append-only history |
| Replay | Impossible | Not designed for replay | Core feature |
| Complexity | Low | Medium | High |
| Query performance | Direct SQL | Direct SQL | Requires projections |
| Audit trail | Requires separate logging | Events are transient (deleted after publish) | Built-in and permanent |

### 3.7 Sequence Numbers vs Timestamps

| Strategy | Pros | Cons |
|----------|------|------|
| Timestamps | Simple, human-readable | Clock skew, same-millisecond collisions |
| Auto-increment (DB) | Guaranteed unique | Requires centralized sequence generator |
| Application sequence | Per-aggregate, gap-detectable | Query before write (slight overhead) |
| Kafka offset | Natural ordering in streams | Only works within Kafka partitions |

EquityCart uses application-level per-user sequence numbers: lightweight, gap-detectable, and works with any storage backend.

### 3.8 When to Use Event Sourcing

**Use when:**
- You need a complete audit trail (finance, compliance, healthcare)
- Temporal queries are required ("portfolio at time T")
- You want to derive multiple read models from the same event stream
- Debugging requires reproducing exact state sequences

**Don't use when:**
- Simple CRUD with no history requirements
- Performance-critical reads that can't tolerate projection latency
- The domain has few state changes (overhead isn't justified)
- Team is unfamiliar with eventual consistency tradeoffs

### 3.9 EquityCart Event Types

| Event | Holding Impact | Triggered By |
|-------|---------------|--------------|
| SHARES_PURCHASED | +qty, recalc avg | Manual BUY trade |
| SHARES_SOLD | -qty, avg unchanged | Manual SELL trade |
| REWARD_GRANTED | None (informational) | Order delivered → stock-back |
| REWARD_VESTED | +qty at price=0 | Scheduled vesting job |
| REWARD_CANCELLED | None (informational) | Order returned |
| SELL_TO_SPEND | -qty | Saga step 1 / transactional sell |
| SELL_TO_SPEND_COMPENSATED | +qty (reversal) | Saga compensation |
| REFUND_RESTORED | +qty (reversal) | Order refund (Kafka) |

### 3.10 Event Sourcing vs CQRS — Relationship & Differences

**They are separate patterns** that complement each other but are independently usable:

| | Event Sourcing | CQRS |
|--|---|---|
| **Concern** | How you **store** state (as immutable events) | How you **separate** reads from writes (different models) |
| **Core question** | "What happened?" (record facts) | "Who needs what shape of data?" (optimized paths) |
| **Standalone?** | Yes — single store for reads + writes | Yes — read replicas + write master, no events |
| **Origin** | Martin Fowler (2005), Greg Young (2006) | Greg Young / Bertrand Meyer's CQS (1988) evolved |

**Why they're conflated:** Event Sourcing makes reads awkward (scanning N events per query). So you build separate read-optimized projections — which is CQRS. The two patterns naturally co-occur in production systems, but neither requires the other.

**Spectrum of adoption:**

```
Simple CRUD ──────── CQRS Only ──────── ES + CQRS Lite ──────── Full ES + CQRS
(one model,          (read replica       (dual-write:            (event store is
 one DB)             + write master)      state DB + event log)   sole truth,
                                                                  all reads from
                                                                  projections)
                                              ▲
                                              │
                                        EquityCart is HERE
```

**Full CQRS architecture (for comparison):**

```
Command Side                              Query Side
┌─────────────┐    publish    ┌─────────────────────────┐
│ Write API   │──── events ──▶│ Event Handler           │
│ (validates  │               │ (updates read model)    │
│  + appends) │               └───────────┬─────────────┘
└──────┬──────┘                           │
       │                                  ▼
       ▼                        ┌─────────────────────┐
┌─────────────┐                 │   Read Database      │
│ Event Store │                 │   (denormalized,     │
│ (only truth)│                 │    query-optimized)  │
└─────────────┘                 └─────────────────────┘
                                          │
                                          ▼
                                ┌─────────────────────┐
                                │   Read API           │
                                │   (fast queries)     │
                                └─────────────────────┘
```

**EquityCart's position (CQRS Lite):**
- Write path → PostgreSQL `holdings` (current state, fast UPDATE)
- Read/audit path → MongoDB `portfolio_events` (timeline, replay, temporal queries)
- Two stores optimized for different access patterns = CQRS principle applied lightly
- Not full CQRS because PostgreSQL is still the authority (not derived from events)

**When to upgrade from CQRS Lite to Full CQRS:**
- When you have multiple consumers needing different read shapes (search index, analytics, mobile view)
- When write throughput is bottlenecked by read queries on the same tables
- When you need independent scaling of reads vs writes
- When eventual consistency (100ms–few seconds lag) is acceptable for all read paths

---

## 4. (Placeholder: API Gateway Pattern — coming in later phases)

---

## 5. (Placeholder: Circuit Breaker Pattern — coming in later phases)
