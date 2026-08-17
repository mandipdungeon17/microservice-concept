# Microservice Patterns — Deep Dive Learning Document

> This document covers microservice architecture patterns in depth as they relate to the EquityCart project.
> Updated progressively as new patterns are implemented across Phase 6+.
> For Kafka-specific concepts, see `kafka-learning.md`.

## Current Phase 10 Pattern Check

The verified baseline in this repo is the transactional outbox + event-driven CQRS read model pattern. This is intentionally preferred over speculative push layers or over-engineered notification infrastructure. The implementation is constrained to the project's actual repository structure and real event boundaries.

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

| Aspect              | Polling Variant                              | CDC Variant                              |
| ------------------- | -------------------------------------------- | ---------------------------------------- |
| Relay mechanism     | Java @Scheduled job (SELECT → send → UPDATE) | Debezium reads PostgreSQL WAL            |
| Latency             | Up to poll interval (5s)                     | Milliseconds (WAL is near-real-time)     |
| DB load             | Repeated SELECT queries every 5s             | Zero queries (reads WAL stream)          |
| Status column       | Meaningful: PENDING → SENT                   | Vestigial: stays PENDING forever         |
| `__TypeId__` header | Present (KafkaTemplate adds it)              | Absent (Debezium is not Spring-aware)    |
| Infrastructure      | Just Java code (no external deps)            | Kafka Connect + Debezium container       |
| Resume on restart   | Re-reads PENDING rows                        | Resumes from WAL LSN position            |
| Delivery guarantee  | Confirmed: `.get()` blocks until Kafka ACK   | Confirmed: Kafka Connect offset tracking |

**Why the status column is vestigial in CDC mode:**

In polling mode, the status column has operational meaning — the poller reads PENDING rows and marks them SENT after Kafka confirms. In CDC mode, Debezium reads the INSERT directly from the WAL the moment it's committed. Nothing updates the row because:

1. Debezium has no write-back mechanism to the source database
2. The OutboxPoller is disabled via `@Profile("!cdc")`
3. There's no feedback loop from "Kafka received it" back to the outbox table

The outbox table in CDC mode is a **write-only append log**. Maintenance: periodic `DELETE WHERE created_at < NOW() - INTERVAL '7 days'` regardless of status.

**CDC drawbacks and failure modes:**

| Drawback                   | Impact                                                                                   | Mitigation                                                                      |
| -------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| WAL disk growth            | `logical` WAL level produces more data than `replica`                                    | Monitor `pg_wal` size, tune `wal_keep_size`                                     |
| Replication slot retention | If Debezium is down, PostgreSQL retains WAL segments until it reconnects — can fill disk | Alerting on `pg_replication_slots.active = false`, set `max_slot_wal_keep_size` |
| No `__TypeId__` header     | Spring consumers can't auto-detect type                                                  | `spring.json.value.default.type` per listener                                   |
| `@Lob` incompatibility     | OID storage invisible to WAL                                                             | Use `@Column(columnDefinition = "text")`                                        |
| Snapshot on first start    | Dumps all existing rows (duplicates)                                                     | `snapshot.mode=never` for outbox tables                                         |
| Timezone mismatch          | Host timezone vs Docker UTC for timestamp fields                                         | Don't use app timestamps as Kafka message timestamps                            |
| Column naming              | Hibernate snake_case vs Debezium default camelCase                                       | Explicit `table.field.*` mappings in connector config                           |
| Operational complexity     | Kafka Connect cluster to manage, monitor, upgrade                                        | Worth it only at scale; polling is simpler for low-volume                       |

**When to use which:**

| Scenario                               | Recommended Variant                            |
| -------------------------------------- | ---------------------------------------------- |
| Learning/prototyping                   | Polling (simpler, no infra)                    |
| Low-volume monolith (< 100 events/min) | Polling (adequate, minimal ops)                |
| High-volume or latency-sensitive       | CDC (sub-second delivery, no polling overhead) |
| Multiple databases/services            | CDC (one Debezium cluster serves all)          |
| No Docker/container infrastructure     | Polling (pure Java, no external deps)          |

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

|                     | Orchestration                             | Choreography                                          |
| ------------------- | ----------------------------------------- | ----------------------------------------------------- |
| **Coordination**    | Central orchestrator drives each step     | Each service reacts to events from previous step      |
| **Flow visibility** | Entire saga readable in one class         | Logic scattered across multiple event listeners       |
| **Coupling**        | Orchestrator knows all steps              | Services know only their own step + next event        |
| **Error handling**  | Orchestrator decides what to compensate   | Each service must know its own compensation trigger   |
| **Best for**        | Complex multi-step flows, clear sequences | Simple 2-3 step flows, loose coupling                 |
| **Example**         | EquityCart Sell-to-Spend Saga             | Order-Delivered → Reward-Consumer (Phase 6 Steps 4-5) |

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

| Strategy            | How it works                                                        | EquityCart example                                                                                  |
| ------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Status gate         | Check saga status before executing — skip if already past this step | If status already `HOLDING_REDUCED`, don't call `reduceHolding()` again                             |
| Natural idempotency | The operation itself rejects duplicates                             | `updateOrderStatus(CONFIRMED)` on already-confirmed order throws `InvalidStatusTransitionException` |
| Unique constraints  | DB constraint prevents double-write                                 | Saga entity with `orderId` lookup prevents duplicate saga creation                                  |

**Topic 8 Deep Dive - ClawbackSaga Idempotency Pattern:**

The clawback saga (return/refund flow for VESTED rewards) combines all three idempotency strategies:

```
ClawbackSaga flow (simplified):
Status progression: INITIATED → LEDGER_ADJUSTED → HOLDING_REDUCED → COMPLETED

Step 1: Perform ledger reversal (VESTED reward → record reversal entry)
  if (saga.status >= ClawbackStatus.LEDGER_ADJUSTED) {
    log.info("Ledger reversal already recorded, skipping");
    return;  // Status gate: already past this step
  }
  
  LedgerEntry reversal = ledgerService.createReversal(saga.rewardId, saga.vaultPosition);
  // Natural idempotency: createReversal uses idempotency key (rewardId + CLAWBACK_TYPE)
  // Database constraint: UNIQUE(rewardId, ledgerType) prevents duplicate reversals
  
  saga.ledgerEntryId = reversal.id;
  saga.status = ClawbackStatus.LEDGER_ADJUSTED;
  sagaRepository.save(saga);

Step 2: Reduce holding
  if (saga.status >= ClawbackStatus.HOLDING_REDUCED) {
    log.info("Holding already reduced, skipping");
    return;  // Status gate
  }
  
  portfolioService.reduceHolding(saga.userId, saga.ticker, saga.shares);
  // Natural idempotency: Kafka idempotency key + outbox idempotency key
  
  saga.status = ClawbackStatus.HOLDING_REDUCED;
  sagaRepository.save(saga);
```

**Why multiple strategies are necessary:**

1. **Status gate** (first line of defense): If a Kafka message is retried after app restart, the saga record already exists in the database. Checking `saga.status` before executing step logic prevents re-invoking business operations.

2. **Natural idempotency** (second line): Even if status check passes (due to a bug or unforeseen edge case), the underlying operation is idempotent. `createReversal()` with the same idempotency key (rewardId + operation type) produces the same ledger entry, not a duplicate.

3. **Unique constraints** (third line): The database schema enforces uniqueness. Even if both status gate and natural idempotency fail, the constraint prevents duplicate saga creation or duplicate ledger entries.

**Common mistake:** Developers often think "I'll just check status before the step and skip if already done" — forgetting that the check itself can fail (database connection lost, saga not fetched yet). Relying on only the status gate without natural idempotency can lead to partial retries or missed compensations.

**Topic 2 Deep Dive - GiftSaga Idempotency Pattern:**

The gifting saga (peer-to-peer stock transfer between users) adds a client-level idempotency dimension: idempotency keys from REST clients:

```
GiftSaga flow (simplified):
Status progression: INITIATED → DEBITING_GIVER → CREDITING_RECEIVER → LEDGER_RECORDED → COMPLETED

Idempotency at two levels:

1) CLIENT LEVEL (HTTP retry safety):
   Request contains: idempotencyKey = UUID.randomUUID()
   
   First request:
     → Server checks: findByIdempotencyKey(idempotencyKey) → not found
     → Proceed to create saga
     → Save saga with idempotencyKey
     → Return GiftResponse with sagaId
   
   Retry (before client receives response):
     → Server checks: findByIdempotencyKey(idempotencyKey) → found
     → Return cached GiftResponse immediately (no saga re-execution)
   
   This prevents double-debit/double-credit during network timeouts.

2) SAGA-LEVEL (post-processing idempotency):
   After saga acceptance, steps are protected by status gates + natural idempotency:
   
   Step 1: Debit giver holding
     if (saga.status >= GiftStatus.DEBITING_GIVER) {
       return;  // Already completed
     }
     portfolioService.reduceHolding(saga.giverId, saga.ticker, saga.quantity, saga.transferPricePerShare);
     saga.status = GiftStatus.DEBITING_GIVER;
     sagaRepository.save(saga);
   
   Step 2: Credit receiver holding
     if (saga.status >= GiftStatus.CREDITING_RECEIVER) {
       return;
     }
     portfolioService.addOrUpdateHolding(saga.receiverId, saga.ticker, saga.quantity, saga.transferPricePerShare);
     saga.status = GiftStatus.CREDITING_RECEIVER;
     sagaRepository.save(saga);
   
   Step 3: Record ledger entry
     if (saga.status >= GiftStatus.LEDGER_RECORDED) {
       return;
     }
     ledgerService.recordTransaction(saga.giverId, saga.receiverId, saga.ticker, saga.transferDollarValue, ReferenceType.GIFT_TRANSFER);
     saga.status = GiftStatus.LEDGER_RECORDED;
     sagaRepository.save(saga);
```

**Key differences from ClawbackSaga idempotency:**

- ClawbackSaga: uses `findByOrderIdAndRewardIdAndStatusNotIn()` (server-generated aggregate keys)
- GiftSaga: uses `findByIdempotencyKey()` (client-supplied key for REST layer) + status gates for saga steps
- ClawbackSaga: no client-level idempotency (triggered by async Kafka consumer, not HTTP)
- GiftSaga: dual-layer idempotency (client HTTP layer + saga step layer) for correctness under retries

**Why monetary values on saga entity matter:**

Transfer happens at a specific price (`transferPricePerShare = giver's average buy price`). If price varies per execution, idempotency breaks:
- First attempt: transfer at $10/share = 100 shares * $10 = $1000 ledger entry
- Timeout, retry: if system used current price ($12/share), retry would record $1200 = INCONSISTENT

By capturing `transferPricePerShare` and `transferDollarValue` at saga creation, all retry attempts use identical values. Ledger entry is deterministic.

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

**Topic 8 Deep Dive - ClawbackSagaTimeoutDetector Implementation:**

The clawback saga timeout detector extends the basic pattern with explicit retry logic and escalation:

```java
@Scheduled(fixedRate = 30000)
void detectAndRecoverTimedOutClawbacks() {
  List<ClawbackSaga> timedOut = clawbackSagaRepository.findStuck(
    notTerminal: [INITIATED, LEDGER_ADJUSTED, HOLDING_REDUCED],
    olderThan: now - 30_seconds
  );
  
  for (ClawbackSaga saga : timedOut) {
    log.warn("Timeout detected for clawback saga {}, attempting recovery", saga.id);
    
    // Determine how far the saga progressed before getting stuck
    ClawbackStatus lastKnownStep = saga.status;
    
    if (saga.attemptCount >= MAX_RETRY_ATTEMPTS) {
      // Give up: saga has been retried too many times, enter compensation
      compensateAndFail(saga);
      log.error("Saga {} exceeded max retries, entering compensation", saga.id);
    } else {
      // Retry from the last known-good state
      retryFromStep(saga, lastKnownStep);
      saga.attemptCount++;
      sagaRepository.save(saga);
      log.info("Saga {} retry #{} initiated", saga.id, saga.attemptCount);
    }
  }
}

void compensateAndFail(ClawbackSaga saga) {
  // HOLDING_REDUCED → reverse to INITIATED
  if (saga.status == HOLDING_REDUCED) {
    portfolioService.addOrUpdateHolding(saga.userId, saga.ticker, saga.shares);
    saga.status = ClawbackStatus.COMPENSATING_HOLDING;
  }
  
  // LEDGER_ADJUSTED or COMPENSATING_HOLDING → reverse ledger entry
  if (saga.status == LEDGER_ADJUSTED || saga.status == COMPENSATING_HOLDING) {
    ledgerService.createReversal(saga.ledgerEntryId);  // Creates undo entry
    saga.status = ClawbackStatus.COMPENSATING_LEDGER;
  }
  
  saga.status = ClawbackStatus.FAILED;
  saga.failureReason = "Timeout + max retries exceeded. Manual investigation required.";
  sagaRepository.save(saga);
  
  // Alert ops team
  alertingService.sendAlert(AlertLevel.CRITICAL, 
    "Clawback saga {} for user {} failed after {redeemable retry attempts", 
    saga.id, saga.userId, saga.attemptCount);
}
```

**Key aspects:**

1. **Stuck detection criteria:** Status NOT in terminal states AND last update > threshold. This avoids false positives from sagas that are actually progressing but slowly.

2. **Retry vs Compensation decision:** Track `attemptCount` to distinguish "transient failures we can retry" from "systematic failures requiring compensation." Without this distinction, a permanently-broken downstream service causes infinite retry loops instead of graceful escalation.

3. **Reverse-order compensation:** If saga stopped at HOLDING_REDUCED, first undo holding, then undo ledger. This mirrors the saga's original forward order (ledger first, holding second).

4. **Alerting on failure:** Once compensation is exhausted (MAX_RETRIES), send critical alert to ops. Manual intervention is needed to determine root cause (was the refund actually approved? Did the reward really exist?) and potentially retry with corrected data.

**Compensation differs from retry:**
- **Retry:** Re-attempt the same operation (may succeed if transient issue is fixed)
- **Compensation:** Execute a NEW forward operation that semantically undoes the previous effect

### 2.7 Saga vs. @Transactional — When to Use Which

| Criteria                | @Transactional     | Saga                                               |
| ----------------------- | ------------------ | -------------------------------------------------- |
| Same database           | ✅ Use this        | Overkill                                           |
| Separate databases      | Not possible       | ✅ Required                                        |
| Code complexity         | ~50 lines          | ~300+ lines                                        |
| Consistency             | Strong (ACID)      | Eventual                                           |
| Intermediate visibility | None (isolated)    | Visible (other transactions can see partial state) |
| Failure recovery        | Automatic rollback | Manual compensation                                |
| Performance             | Single commit      | Multiple commits + saga saves                      |

**Rule of thumb:** Use `@Transactional` when you can. Use Sagas when you must (separate databases, separate deployments, cross-network boundaries).

### 2.8 EquityCart Implementation

Two main saga implementations demonstrate the pattern:

**1. SellToSpendSaga (Phase 6 - Trading → Ledger → Order confirmation)**
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

**2. ClawbackSaga (Topic 8 - Refund/Return clawback of VESTED rewards)**
```
equitycart/portfolio/src/main/java/com/equitycart/portfolio/saga/
├── enums/ClawbackStatus.java                ← State machine enum (5 states: INITIATED→LEDGER_ADJUSTED→HOLDING_REDUCED→COMPLETED; or COMPENSATING→FAILED)
├── entity/ClawbackSaga.java                 ← JPA entity with rewardId, userId, shares, attemptCount
├── repository/ClawbackSagaRepository.java   ← findByRewardId(), findStuck(), findExpired()
├── orchestrator/ClawbackSagaOrchestrator.java ← Orchestrates: 1) ledger reversal, 2) holding reduction, 3) completion
├── event/ClawbackOutboxWriter.java          ← Publishes saga lifecycle events (CLAWBACK_INITIATED, LEDGER_REVERSED, HOLDING_REDUCED, COMPLETED, COMPENSATED)
└── timeout/ClawbackSagaTimeoutDetector.java ← @Scheduled poller, detects stuck sagas, triggers retry or compensation
```

**Key differences between SellToSpendSaga and ClawbackSaga:**

| Aspect | SellToSpendSaga | ClawbackSaga |
|--------|-----------------|--------------|
| **Trigger** | User initiates trade (sell holdings + use proceeds) | System initiates on refund approval (undo VESTED reward) |
| **Data flow** | Portfolio → Ledger → Order (forward, business transaction) | Portfolio ← Ledger ← Reward (reverse, remediation) |
| **Steps** | 3 (reduce holding, record ledger, confirm order) | 3 (reverse ledger, reduce holding, mark complete) |
| **Compensation trigger** | Order confirmation fails → undo ledger, restore holding | Timeout or failure → undo all steps in reverse order |
| **State machine** | STARTED → REDUCING_HOLDING → HOLDING_REDUCED → RECORDING_LEDGER → CONFIRMING_ORDER → COMPLETED | INITIATED → LEDGER_ADJUSTED → HOLDING_REDUCED → COMPLETED |
| **Failure path** | COMPENSATING → FAILED | COMPENSATING → FAILED |
| **Compensation order** | Undo ledger (reverse entry), restore holding | Undo holding (re-add shares), undo ledger (reverse entry) |
| **Idempotency key** | orderId (unique per user trade) | rewardId + CLAWBACK_TYPE (unique per reward) |

Both follow the same orchestration pattern but represent different business flows — SellToSpendSaga moves forward through a user action, ClawbackSaga reverses a previously-completed transaction.

### 2.9 Compensating Transaction Design Rules

1. **Compensations are forward operations** — never try to "undo" at the database level (DELETE the row). Instead, create a new operation that semantically reverses the effect.
2. **Compensations must be idempotent** — they may be retried if the saga crashes during compensation.
3. **Order matters** — compensate in REVERSE order of execution (last completed step first).
4. **Not all steps need compensation** — the last step in a saga never needs compensation (nothing runs after it to fail).
5. **Compensation can fail** — if it does, the saga is FAILED and requires manual intervention (alerts, admin dashboard).

**Topic 8 Deep Dive - ClawbackSaga Compensation Examples:**

**Scenario 1: Normal happy path (compensation never needed)**
```
ClawbackSaga for reward-123 (user=42, ticker=AAPL, shares=0.125):
Step 1: INITIATED → ledgerService.createReversal(reward-123)
        → Creates reversal ledger entry: VESTED_REWARD_REVERSED (matches original VESTED_REWARD_GRANTED in opposite direction)
        → Status: LEDGER_ADJUSTED
        → Persists: saga.ledgerEntryId = reversal-entry-456

Step 2: LEDGER_ADJUSTED → portfolioService.reduceHolding(userId=42, ticker=AAPL, shares=0.125)
        → Reduces user's AAPL holdings by 0.125 (removes vested shares from portfolio)
        → Status: HOLDING_REDUCED
        
Step 3: HOLDING_REDUCED → markComplete()
        → Status: COMPLETED
        → Clawback done, reward state updated to CLAWED_BACK

(Compensation never runs — all steps succeeded)
```

**Scenario 2: Timeout after step 1 (ledger reversed, but holding reduction never attempted)**
```
ClawbackSaga stuck at LEDGER_ADJUSTED (> 30 seconds old, no further progress)
TimeoutDetector triggers compensateAndFail():

Compensation step 1 (undo ledger reversal):
  if (saga.status == LEDGER_ADJUSTED) {
    ledgerService.createReversal(saga.ledgerEntryId);
    // This is counter-intuitive: we reverse the reversal
    // Original: VESTED_REWARD_GRANTED (200 shares)
    // Step 1: VESTED_REWARD_REVERSED (-200 shares) → ledger entry 456
    // Compensation: VESTED_REWARD_REVERSAL_UNDONE (+200 shares) → ledger entry 789
    // Net effect: 200 - 200 + 200 = +200 (back to original)
    saga.status = ClawbackStatus.COMPENSATING_LEDGER;
  }

(No holding compensation needed because step 2 never ran — holdings were never reduced)

Final status: FAILED (clawback did not complete, but no partial state left behind)
Alert ops: "ClawbackSaga for reward-123 failed after 3 retries. Manual review: was refund actually approved?"
```

**Scenario 3: Timeout after step 2 (both ledger and holding changed, full compensation needed)**
```
ClawbackSaga stuck at HOLDING_REDUCED (ledger reversed + holdings reduced, but completion never committed)

Compensation step 1 (reverse holding reduction):
  if (saga.status == HOLDING_REDUCED) {
    portfolioService.addOrUpdateHolding(userId=42, ticker=AAPL, shares=0.125);
    // Reverses the holding reduction from step 2
    saga.status = ClawbackStatus.COMPENSATING_HOLDING;
  }

Compensation step 2 (reverse the ledger reversal):
  if (saga.status == COMPENSATING_HOLDING) {
    ledgerService.createReversal(saga.ledgerEntryId);
    // Creates reversal of reversal (undo step 1)
    saga.status = ClawbackStatus.COMPENSATING_LEDGER;
  }

Final result:
  Ledger: VESTED_REWARD_GRANTED → VESTED_REWARD_REVERSED → VESTED_REWARD_REVERSAL_UNDONE = net zero
  Portfolio: Holdings = original (shares re-added)
  Reward status: Back to VESTED (clawback did not complete, reverted to pre-clawback state)
  Saga status: FAILED
```

**Why forward operations for compensation?**

❌ **Bad (database-level undo):**
```java
// DO NOT DO THIS
saga.status = LEDGER_ADJUSTED;
ledgerRepository.deleteById(saga.ledgerEntryId);  // Removes the reversal entry
// Problem: If this crashes, the ledger entry is partially deleted, audit trail is broken
// Problem: Other transactions may have already read this entry for reconciliation
// Problem: Not idempotent — a retry will fail (entry already deleted)
```

✅ **Good (forward compensation operation):**
```java
// Correct approach
ledgerService.createReversal(saga.ledgerEntryId);  
// New ledger entry undoes the previous one semantically
// Creates new entry: VESTED_REWARD_REVERSAL_UNDONE
// Audit trail preserved, idempotent (repeat creates same reversal), no deletions
```

**Common mistake:** Confusing "compensation" with "rollback." Compensation writes NEW data (ledger entries, outbox events). Rollback deletes/overwrites existing data. In distributed systems, rollback is unsafe; compensation is idempotent and auditable.

---

## 3. Event Sourcing Pattern

### 3.1 Core Concept

Event Sourcing stores every state change as an immutable event in an append-only log. Instead of overwriting the current state (`UPDATE holdings SET quantity = 5`), you record the fact that caused the change (`SHARES_PURCHASED: qty=5, price=150`). The current state is always derivable by replaying all events from the beginning.

**Traditional CRUD:** State → Overwrite → State (history lost)
**Event Sourcing:** Event₁ → Event₂ → ... → Eventₙ → replay → Current State

### 3.2 Key Components

| Component       | Purpose                                             | EquityCart Implementation                      |
| --------------- | --------------------------------------------------- | ---------------------------------------------- |
| Event Store     | Append-only persistence of events                   | MongoDB `portfolio_events` collection          |
| Event           | Immutable fact with type, data, timestamp, sequence | `PortfolioEvent` @Document                     |
| Projection      | Function that replays events → read model           | `PortfolioProjectionService.rebuildHoldings()` |
| Sequence Number | Total ordering per aggregate                        | Per-user monotonic counter                     |

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

| Aspect            | CRUD                      | Outbox                                       | Event Sourcing              |
| ----------------- | ------------------------- | -------------------------------------------- | --------------------------- |
| State storage     | Current only              | Current + outbox events                      | Events only (state derived) |
| History           | Lost on update            | Events have delivery purpose                 | Full append-only history    |
| Replay            | Impossible                | Not designed for replay                      | Core feature                |
| Complexity        | Low                       | Medium                                       | High                        |
| Query performance | Direct SQL                | Direct SQL                                   | Requires projections        |
| Audit trail       | Requires separate logging | Events are transient (deleted after publish) | Built-in and permanent      |

### 3.7 Sequence Numbers vs Timestamps

| Strategy             | Pros                          | Cons                                    |
| -------------------- | ----------------------------- | --------------------------------------- |
| Timestamps           | Simple, human-readable        | Clock skew, same-millisecond collisions |
| Auto-increment (DB)  | Guaranteed unique             | Requires centralized sequence generator |
| Application sequence | Per-aggregate, gap-detectable | Query before write (slight overhead)    |
| Kafka offset         | Natural ordering in streams   | Only works within Kafka partitions      |

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

| Event                     | Holding Impact       | Triggered By                     |
| ------------------------- | -------------------- | -------------------------------- |
| SHARES_PURCHASED          | +qty, recalc avg     | Manual BUY trade                 |
| SHARES_SOLD               | -qty, avg unchanged  | Manual SELL trade                |
| REWARD_GRANTED            | None (informational) | Order delivered → stock-back     |
| REWARD_VESTED             | +qty at price=0      | Scheduled vesting job            |
| REWARD_CANCELLED          | None (informational) | Order returned                   |
| SELL_TO_SPEND             | -qty                 | Saga step 1 / transactional sell |
| SELL_TO_SPEND_COMPENSATED | +qty (reversal)      | Saga compensation                |
| REFUND_RESTORED           | +qty (reversal)      | Order refund (Kafka)             |

### 3.10 Event Sourcing vs CQRS — Relationship & Differences

**They are separate patterns** that complement each other but are independently usable:

|                   | Event Sourcing                                | CQRS                                                      |
| ----------------- | --------------------------------------------- | --------------------------------------------------------- |
| **Concern**       | How you **store** state (as immutable events) | How you **separate** reads from writes (different models) |
| **Core question** | "What happened?" (record facts)               | "Who needs what shape of data?" (optimized paths)         |
| **Standalone?**   | Yes — single store for reads + writes         | Yes — read replicas + write master, no events             |
| **Origin**        | Martin Fowler (2005), Greg Young (2006)       | Greg Young / Bertrand Meyer's CQS (1988) evolved          |

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

## 4. API Gateway Pattern (Spring Cloud Gateway)

### 4.1 The Problem: Client-to-Microservice Coupling

Without a gateway, every client (browser, mobile app, third-party integration) must know the address of every microservice it needs. In a system with 10 services, each on a different port, the client becomes a hardcoded routing table:

```
WITHOUT Gateway — Client Knows Everything:

Browser/Mobile App
├── http://192.168.1.50:8081/api/auth/login     (user-service)
├── http://192.168.1.50:8088/api/order          (order-service)
├── http://192.168.1.50:8084/api/portfolio      (portfolio-service)
├── http://192.168.1.50:8089/api/products       (product-service)
├── http://192.168.1.50:8085/api/market-data    (market-data-service)
├── http://192.168.1.50:8086/api/ledger         (ledger-service)
└── http://192.168.1.50:8087/api/notifications  (notification-service)

Problems:
1. Client breaks if ANY service moves to a different host/port
2. Client must handle service discovery itself (which instance is healthy?)
3. Cross-cutting concerns (auth, rate limiting, logging) duplicated in every service
4. Internal service topology exposed to the public internet
5. CORS configuration needed on every service individually
6. Can't scale/replace services independently without client changes
```

### 4.2 The Solution: Single Entry Point (Facade for the Network)

The API Gateway pattern places a single reverse proxy between ALL clients and ALL backend services. Clients know ONE address; the gateway knows the rest.

```
WITH Gateway — Client Knows ONE Address:

Browser/Mobile App
│
│  All requests → http://localhost:8080/api/...
│
▼
┌─────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (port 8080)                        │
│                                                                  │
│  Responsibilities:                                               │
│  ① Routing      — /api/auth/** → user-service                  │
│  ② Discovery    — "user-service" → ask Eureka → 172.18.0.5:8081│
│  ③ Load Balance — round-robin across healthy instances           │
│  ④ Cross-cutting— correlation ID, auth check, rate limit, CORS │
│  ⑤ Abstraction  — internal topology invisible to clients        │
│                                                                  │
│  Path Match          Route To                                    │
│  ─────────────────── ─────────────────────                      │
│  /api/auth/**        lb://user-service                          │
│  /api/users/**       lb://user-service                          │
│  /api/order/**       lb://order-service                         │
│  /api/cart/**        lb://order-service                         │
│  /api/portfolio/**   lb://portfolio-service                     │
│  /api/market-data/** lb://market-data-service                   │
│  /api/products/**    lb://product-service                       │
│  /api/ledger/**      lb://ledger-service                        │
│  /api/notifications/**lb://notification-service                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
            ┌──────────────┼──────────────────┐
            ▼              ▼                  ▼
     user-service   order-service   portfolio-service  ...
     (8081)         (8088)          (8084)
```

### 4.3 Historical Context: Gateway Evolution

| Era          | Technology                 | Model                                            | Limitations                                            |
| ------------ | -------------------------- | ------------------------------------------------ | ------------------------------------------------------ |
| 2005–2012    | Apache HTTP Server / Nginx | Static reverse proxy, config-file routing        | No service discovery, manual reload on topology change |
| 2012–2015    | Netflix Zuul 1.x           | Servlet-based (blocking), integrated with Eureka | One thread per request → thread exhaustion under load  |
| 2015–2018    | Netflix Zuul 2.x           | Non-blocking (Netty), async I/O                  | Netflix-internal, limited community adoption           |
| 2018–present | **Spring Cloud Gateway**   | Reactive (Project Reactor + Netty), non-blocking | Requires understanding reactive programming            |
| 2019–present | Envoy / Istio              | Service mesh sidecar proxy                       | Infrastructure-level, higher operational complexity    |

**Why Spring Cloud Gateway replaced Zuul 1:** Zuul 1 uses a thread-per-request model (Servlet API). Under 1000 concurrent connections, each connection holds a thread. If downstream services are slow (2s response), 1000 threads are blocked simultaneously — the gateway's thread pool is exhausted and it stops accepting new connections. Spring Cloud Gateway uses Netty's event loop (non-blocking I/O) — a single thread can handle thousands of concurrent connections because it never blocks waiting for a downstream response.

### 4.4 Reactive vs Servlet — Why the Gateway Is Different

Every other EquityCart service (order, portfolio, product) runs on **Tomcat** (Servlet container, blocking I/O). The API Gateway runs on **Netty** (non-blocking, event-loop). This is not an arbitrary choice — it's dictated by the gateway's unique workload:

```
Downstream Service (e.g., order-service):
  - Receives a request
  - Does CPU work (validate, query DB, build response)
  - Returns response
  - Thread usage: most time spent in useful computation
  - Model: thread-per-request (Tomcat) is efficient

API Gateway:
  - Receives a request
  - Adds headers, checks auth
  - Forwards to downstream (network I/O)
  - WAITS for downstream to respond (pure I/O wait)
  - Returns downstream's response
  - Thread usage: 95% of time is I/O waiting, 5% is actual work
  - Model: thread-per-request WASTES resources (threads just sit idle)
  - Better model: event-loop (one thread handles many connections)
```

**Consequence:** You cannot use `jakarta.servlet.Filter` (like `OncePerRequestFilter`) in the gateway. Instead, you use `org.springframework.cloud.gateway.filter.GlobalFilter` which operates on reactive types (`ServerWebExchange`, `Mono<Void>`).

| Concept          | Servlet (downstream services) | Reactive (gateway)                           |
| ---------------- | ----------------------------- | -------------------------------------------- |
| Request type     | `HttpServletRequest`          | `ServerWebExchange`                          |
| Filter base      | `OncePerRequestFilter`        | `GlobalFilter`                               |
| Return type      | `void` (blocking)             | `Mono<Void>` (non-blocking)                  |
| Thread model     | One thread per request        | Event loop (few threads, many requests)      |
| Container        | Tomcat                        | Netty                                        |
| Request mutation | `request.setAttribute(...)`   | `exchange.mutate().request(...)` (immutable) |

### 4.5 Route Resolution — How a Request Finds Its Service

When a request arrives at the gateway, this sequence executes:

```
Step 1: HTTP request arrives → GET /api/portfolio/holdings
        │
        ▼
Step 2: Route Predicate Matching (evaluated in definition order)
        │
        │  Route "user-service":      Path=/api/auth/**,/api/users/**     → NO MATCH
        │  Route "order-service":     Path=/api/order/**,/api/cart/**     → NO MATCH
        │  Route "portfolio-service": Path=/api/portfolio/**              → MATCH ✓
        │
        ▼
Step 3: URI Resolution
        │
        │  Matched route URI: lb://portfolio-service
        │  "lb://" prefix triggers Spring Cloud LoadBalancer
        │
        ▼
Step 4: Service Discovery (Eureka Lookup)
        │
        │  Gateway asks Eureka: "give me instances of PORTFOLIO-SERVICE"
        │  Eureka responds: [172.18.0.8:8084, 172.18.0.9:8084]
        │  (if multiple instances are registered)
        │
        ▼
Step 5: Load Balancing (Round-Robin)
        │
        │  LoadBalancer selects: 172.18.0.8:8084
        │  (alternates between instances on each request)
        │
        ▼
Step 6: Filters Execute (pre-routing)
        │
        │  CorrelationIdGatewayFilter (HIGHEST_PRECEDENCE):
        │    - Reads X-Correlation-Id header (if present)
        │    - If absent → generates UUID
        │    - Mutates request → adds header to forwarded request
        │
        ▼
Step 7: Proxy the Request
        │
        │  Gateway sends: GET http://172.18.0.8:8084/api/portfolio/holdings
        │  With headers: X-Correlation-Id: abc-123-def
        │  (original client headers preserved + gateway-added headers)
        │
        ▼
Step 8: Response Returns
        │
        │  Downstream responds with body + status
        │  Post-filter: adds X-Correlation-Id to response headers
        │  Gateway forwards response to the original client
```

### 4.6 The `lb://` URI Scheme — Service Discovery at Runtime

`lb://` is not a real network protocol — it's a Spring Cloud convention that triggers the `ReactorLoadBalancerClientFilter`. When the gateway encounters this scheme:

```
URI: lb://portfolio-service
      ^^   ^^^^^^^^^^^^^^^^^^
      │    │
      │    └── Logical service name (must match spring.application.name
      │        of the target service, case-insensitive)
      │
      └── Triggers LoadBalancerClientFilter instead of direct HTTP

Resolution chain:
  lb://portfolio-service
    → LoadBalancerClient.choose("portfolio-service")
      → EurekaDiscoveryClient.getInstances("portfolio-service")
        → returns ServiceInstance(host=172.18.0.8, port=8084)
      → RoundRobinLoadBalancer selects one instance
    → rewrite URI: http://172.18.0.8:8084
  Final request: GET http://172.18.0.8:8084/api/portfolio/holdings
```

**What happens if no instances are registered?** The gateway returns HTTP 503 (Service Unavailable) to the client immediately — no timeout waiting. The LoadBalancer detects an empty instance list and short-circuits.

**What happens if the selected instance is down?** The request fails (connection refused or timeout). By default, no automatic retry. In Phase 8+, a retry filter can be added to try the next instance.

### 4.7 Route Predicates — The Matching Language

Spring Cloud Gateway supports many predicate types beyond simple path matching:

| Predicate          | Example                      | Use Case                                  |
| ------------------ | ---------------------------- | ----------------------------------------- |
| `Path`             | `Path=/api/auth/**`          | Most common — match URL path pattern      |
| `Method`           | `Method=GET,POST`            | Route only specific HTTP methods          |
| `Header`           | `Header=X-Request-Id, \d+`   | Route based on header presence/regex      |
| `Query`            | `Query=version, v2`          | Route based on query parameter            |
| `Host`             | `Host=api.equitycart.com`    | Route based on Host header (multi-tenant) |
| `Weight`           | `Weight=group1, 8`           | Traffic splitting (canary releases)       |
| `After` / `Before` | `After=2026-07-01T00:00:00Z` | Time-based routing (feature launches)     |

**EquityCart uses only `Path` predicates** — simplest and sufficient for path-based microservice routing. Other predicates become relevant for:

- **Canary deployments** — route 10% of traffic to v2 (`Weight`)
- **A/B testing** — route based on query parameter or header
- **API versioning** — `Path=/v2/api/**` → new service, `Path=/v1/api/**` → legacy

### 4.8 Filters — Cross-Cutting Concerns

Filters are the gateway's middleware layer. They intercept requests before routing (pre-filters) and responses after routing (post-filters).

```
Client Request
     │
     ▼
┌────────────────────────────────────────────────────────────┐
│  GLOBAL FILTERS (apply to ALL routes, ordered by getOrder())│
│                                                            │
│  ① CorrelationIdGatewayFilter (HIGHEST_PRECEDENCE)         │
│     - Generate/propagate X-Correlation-Id                  │
│     - PRE: mutate request with header                      │
│     - POST: echo header in response (.then() block)        │
│                                                            │
│  ② [Future: AuthenticationFilter]                          │
│     - Validate JWT token                                   │
│     - Reject 401 if invalid/expired                        │
│     - PRE only (no post action)                            │
│                                                            │
│  ③ [Future: RateLimitingFilter]                            │
│     - Track request count per client IP                    │
│     - Reject 429 if limit exceeded                         │
│                                                            │
│  ④ ReactorLoadBalancerClientFilter (built-in)              │
│     - Resolves lb:// → actual host:port                    │
│     - Routes request to selected instance                  │
│                                                            │
│  ⑤ NettyRoutingFilter (built-in, lowest precedence)        │
│     - Actually sends the HTTP request to downstream         │
│     - Returns the response as a Mono                       │
└────────────────────────────────────────────────────────────┘
     │
     ▼
Downstream Service
```

**Two types of filters:**

| Type          | Scope                    | Registration                    | Example                           |
| ------------- | ------------------------ | ------------------------------- | --------------------------------- |
| GlobalFilter  | ALL routes automatically | `@Component` on class           | CorrelationIdGatewayFilter        |
| GatewayFilter | Specific route only      | YAML `filters:` list or factory | `AddRequestHeader`, `StripPrefix` |

### 4.9 GlobalFilter Implementation — CorrelationIdGatewayFilter Deep Dive

The gateway cannot use `MdcCorrelationFilter` (which downstream services use) because that filter extends `OncePerRequestFilter` — a Servlet API class that doesn't exist in the reactive WebFlux stack.

```
Downstream service (Tomcat):           Gateway (Netty):
─────────────────────────────          ─────────────────────────────
OncePerRequestFilter                   GlobalFilter
  doFilterInternal(                      filter(
    HttpServletRequest,                    ServerWebExchange,
    HttpServletResponse,                   GatewayFilterChain
    FilterChain)                         ) → Mono<Void>
  │                                      │
  │ request.getHeader("X-Corr-Id")       │ exchange.getRequest()
  │ ThreadContext.put(...)               │   .getHeaders().getFirst(...)
  │ filterChain.doFilter(req, res)       │
  │ ThreadContext.remove(...)            │ exchange.mutate()
  │                                      │   .request(mutated).build()
  │ (mutable request object)            │ (immutable — must create new)
  └─ return void                        └─ return chain.filter(mutated)
                                              .then(Mono.fromRunnable(...))
```

**Why `exchange.mutate()`?** In reactive programming, objects are immutable by design (thread-safety without locks). You can't call `request.addHeader(...)` — instead you create a copy with modifications:

```
// Can't do this (immutable):
exchange.getRequest().getHeaders().add("X-Correlation-Id", id);  // ✗ UnsupportedOperation

// Must do this (create modified copy):
ServerHttpRequest mutated = exchange.getRequest()
    .mutate()
    .header("X-Correlation-Id", id)
    .build();
ServerWebExchange newExchange = exchange.mutate()
    .request(mutated)
    .build();
return chain.filter(newExchange);  // forward the new immutable exchange
```

**Filter ordering via `Ordered` interface:**

```
HIGHEST_PRECEDENCE = Integer.MIN_VALUE = -2147483648
  → Runs FIRST (before everything else)

LOWEST_PRECEDENCE = Integer.MAX_VALUE = 2147483647
  → Runs LAST (after everything else)

CorrelationIdGatewayFilter.getOrder() → HIGHEST_PRECEDENCE
  Why: correlation ID must be available before any other filter logs or routes
```

### 4.10 Gateway vs Reverse Proxy vs Load Balancer — Distinctions

These terms are often confused. Each has a different primary concern:

| Component                              | Primary Concern                         | Aware of Service Registry?          | Application Logic?                        |
| -------------------------------------- | --------------------------------------- | ----------------------------------- | ----------------------------------------- |
| **Load Balancer** (e.g., AWS ALB)      | Distribute traffic across instances     | No (static target groups)           | No                                        |
| **Reverse Proxy** (e.g., Nginx)        | Forward requests, terminate TLS, cache  | No (static upstreams config)        | Minimal (rewrite rules)                   |
| **API Gateway** (Spring Cloud Gateway) | Route + discover + cross-cut            | **Yes** (queries Eureka at runtime) | **Yes** (custom filters, auth, transform) |
| **Service Mesh** (Istio/Envoy sidecar) | Transparent inter-service communication | Yes (control plane)                 | Yes (policy-driven)                       |

**Spring Cloud Gateway combines** all three roles for EquityCart: it's the load balancer (round-robin via `lb://`), the reverse proxy (forwards requests transparently), and the application gateway (runs custom filters).

### 4.11 Why Not Just Use Nginx?

Nginx is a production-grade reverse proxy, but for microservices it lacks:

| Need                          | Nginx                                           | Spring Cloud Gateway                              |
| ----------------------------- | ----------------------------------------------- | ------------------------------------------------- |
| Service discovery integration | Manual upstream blocks, reload on change        | `lb://` auto-discovers from Eureka in real-time   |
| Dynamic routing               | Config file reload (SIGHUP)                     | Runtime route refresh via Config Server           |
| Custom Java filters           | Lua scripting (limited)                         | Full Java/Kotlin with Spring ecosystem            |
| Circuit breaking              | Passive health checks only                      | Can integrate Resilience4j per route              |
| JWT validation                | Requires auth_request module + external service | Spring Security OAuth2 resource server in-process |

**When to use Nginx instead:** In production, Nginx often sits IN FRONT of Spring Cloud Gateway as a TLS terminator and static content server. The architecture becomes: Client → Nginx (TLS, static files) → Spring Cloud Gateway (routing, auth, discovery) → Microservices.

### 4.12 Configuration Externalization — Why Routes Live in Config Server

EquityCart's gateway route config lives in `equitycart-config/api-gateway.yml` (a Git-backed repository served by Config Server), NOT embedded in the gateway's own `application.yml`.

```
Gateway's embedded application.yml (in the JAR):
  spring:
    config:
      import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
    application:
      name: api-gateway

Config Server fetches api-gateway.yml from Git → merges into gateway's config.

Why externalize routes?
┌────────────────────────────────────────────────────────────┐
│ Without Config Server:                                      │
│   Change a route → edit code → rebuild JAR → redeploy       │
│   Downtime: minutes                                         │
│                                                            │
│ With Config Server:                                         │
│   Change a route → git push to config repo                 │
│   Gateway picks up on next refresh (or POST /actuator/refresh)│
│   Downtime: zero (hot-reload possible)                     │
└────────────────────────────────────────────────────────────┘
```

### 4.13 Security at the Gateway Level — Phase 8 Complete

**Phase 7 state:** The gateway performed NO authentication. All requests passed through to downstream services. Security relied on network isolation (Docker bridge — only port 8080 exposed to host).

**Phase 8 implementation — full security stack at gateway:**

```
Client (Authorization: Bearer <RS256 token>)
    │
    ▼
API Gateway (Netty/WebFlux)
    ├── SecurityWebFilterChain (@EnableWebFluxSecurity)
    │   ├── NimbusReactiveJwtDecoder → JWKS → RS256 validation
    │   └── AuthorizationWebFilter → /api/auth/** permit, else authenticated
    ├── RequestRateLimiter → Redis token bucket (10/sec per user, per IP anon)
    ├── SecurityHeadersGlobalFilter → 6 OWASP headers on every response
    └── ProxyExchange → Authorization header forwarded unchanged
        → Downstream services re-validate independently (defense in depth)
```

**Why each service ALSO validates (not just the gateway):**

- Direct port access (debug tools, internal services, misconfiguration) bypasses the gateway
- Defense in depth: attacker compromising the gateway doesn't gain access to service data
- Zero-trust: services never assume a request is authenticated just because it came from the gateway

### 4.14 Gateway Patterns Implemented in Phase 8

| Pattern                                | Implementation                                                 | Key Detail                                                       |
| -------------------------------------- | -------------------------------------------------------------- | ---------------------------------------------------------------- |
| **Edge Authentication (OAuth2 RS256)** | `SecurityWebFilterChain` + `NimbusReactiveJwtDecoder` via JWKS | `@EnableWebFluxSecurity` — reactive stack only                   |
| **Rate Limiting (Token Bucket)**       | `RequestRateLimiter` default-filter + Redis Lua script         | Per-userId (authenticated), per-IP (anonymous)                   |
| **Security Headers**                   | `SecurityHeadersGlobalFilter` at `LOWEST_PRECEDENCE`           | `chain.filter().then()` — sets headers after downstream response |
| **Token Relay**                        | `ProxyExchange` forwards `Authorization: Bearer` unchanged     | No `TokenRelay` config needed with standard proxy behavior       |

### 4.15 API Gateway Anti-Patterns

| Anti-Pattern                         | Why It's Bad                                                                 | EquityCart's Approach                                                |
| ------------------------------------ | ---------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| **Business logic in the gateway**    | Gateway becomes a monolith again; all teams coupled to one codebase          | Gateway only routes and cross-cuts; zero business logic              |
| **Gateway as data aggregator**       | Couples gateway to multiple service schemas; changes cascade                 | Each client calls one service per request (Phase 7)                  |
| **Single gateway for all clients**   | Mobile needs different data shapes than web; one gateway can't optimize both | Single gateway is fine at current scale; BFF pattern if mobile added |
| **No health checks on routes**       | Gateway routes to dead instances until Eureka deregisters them (90s default) | Eureka heartbeat + LoadBalancer skips unhealthy                      |
| **Storing session state in gateway** | Gateway instances can't scale independently; sticky sessions needed          | Stateless — JWT token carries identity                               |

### 4.16 Comparison: API Gateway Implementations

| Feature           | Spring Cloud Gateway     | Kong                               | AWS API Gateway              | Nginx + Lua                     |
| ----------------- | ------------------------ | ---------------------------------- | ---------------------------- | ------------------------------- |
| Language          | Java (reactive)          | Lua + Go                           | Managed service              | C + Lua                         |
| Service discovery | Eureka, Consul, K8s      | DNS, Consul                        | AWS Cloud Map                | Manual upstream                 |
| Config model      | Java code + YAML         | Admin API + DB                     | AWS Console / CloudFormation | nginx.conf                      |
| Custom filters    | Java GlobalFilter        | Lua plugins                        | Lambda authorizers           | Lua scripts                     |
| Performance       | High (Netty event loop)  | Very high (Nginx core)             | Managed (auto-scale)         | Very high                       |
| Deployment        | Self-hosted (your JVM)   | Self-hosted or Cloud               | Fully managed                | Self-hosted                     |
| Best for          | Spring ecosystem, Eureka | Multi-language, plugin marketplace | AWS-native, serverless       | Raw performance, simple routing |

**Why Spring Cloud Gateway for EquityCart:** The entire backend is Spring Boot + Eureka. The gateway integrates natively — shares the same config server, same service registry, same security libraries. No polyglot overhead.

### 4.17 Interview-Ready Concepts

**Q: What's the difference between a gateway and a reverse proxy?**
A: A reverse proxy forwards requests based on static configuration (Nginx upstream blocks). An API gateway adds dynamic service discovery (asks Eureka at runtime), programmable cross-cutting logic (authentication, rate limiting via custom filters), and operates as an application-level component aware of your service topology.

**Q: Why is Spring Cloud Gateway non-blocking?**
A: A gateway's job is mostly I/O wait (proxy request → wait for downstream → return response). Blocking thread-per-request model (Zuul 1) wastes threads on idle waiting. Non-blocking event loop (Netty) handles thousands of concurrent connections with few threads because it never blocks — it registers a callback and moves to the next request.

**Q: What happens to requests if the gateway crashes?**
A: All traffic stops. The gateway is a single point of failure. Production mitigation: run multiple gateway instances behind a load balancer (AWS ALB / Nginx). Eureka registers multiple gateway instances; the external LB distributes across them. EquityCart Phase 7 uses a single instance (acceptable for learning).

**Q: Why not put authentication in each microservice?**
A: You CAN, but you duplicate JWT validation logic in 7 services (same library, same config, same token parsing). Gateway-level auth centralizes it: validate once at the edge, forward trusted identity (userId header) to downstream. Downstream trusts the gateway (network-internal only). Trade-off: if a service is accessed outside the gateway (debugging, internal tool), it has no auth protection — Phase 8 adds per-service fallback validation.

---

### 4.18 Gateway as Edge Security Enforcer

The API Gateway sits at the single entry point for all external traffic. Centralizing security enforcement here gives you:

| Concern          | Without Gateway Centralization         | With Gateway Centralization                                         |
| ---------------- | -------------------------------------- | ------------------------------------------------------------------- |
| JWT validation   | Each service validates independently   | Gateway rejects bad tokens before network hop                       |
| Rate limiting    | Each service implements its own limits | One Redis-backed limit covers all 7 services                        |
| Security headers | Each service adds headers              | One GlobalFilter adds headers to all responses                      |
| Key rotation     | Each service polls JWKS independently  | All gateway validations share one NimbusReactiveJwtDecoder instance |

**The dual-validation principle:** Gateway validates AND each service validates independently. This is "defense in depth" — if an attacker bypasses the gateway (direct port access, network misconfiguration), services still reject unauthorized requests. Services never trust the gateway's opinion of authentication.

---

### 4.19 Edge Authentication Pattern (Reactive OAuth2 Resource Server)

**Context:** API Gateway runs on Netty/WebFlux — not Tomcat/Servlet. Every security class must be the reactive variant.

```
Reactive (Gateway)                   Servlet (Services)
─────────────────────────────────    ─────────────────────────────────
SecurityWebFilterChain               SecurityFilterChain
ServerHttpSecurity DSL               HttpSecurity DSL
.authorizeExchange()                 .authorizeHttpRequests()
ReactiveSecurityContextHolder        SecurityContextHolder (ThreadLocal)
AuthenticationWebFilter              BearerTokenAuthenticationFilter
NimbusReactiveJwtDecoder             NimbusJwtDecoder
Converter<Jwt,Mono<AuthToken>>       Converter<Jwt,AuthToken>
@EnableWebFluxSecurity               @EnableWebSecurity/@EnableMethodSecurity
```

**Pattern — why the converter returns `Mono<>`:**
The reactive `AuthenticationWebFilter` internally calls `.flatMap(converter::convert)`. A `Mono<>` return type is required to chain into the next reactive operator. The conversion is synchronous, but it must fit the reactive contract: wrap with `Mono.just(result)`.

**Pattern — token forwarding without explicit config:**
The `ProxyExchange` in Spring Cloud Gateway reads the original `ServerHttpRequest` from the incoming `ServerWebExchange` and forwards all headers (including `Authorization: Bearer`) to the downstream service unchanged. No explicit `TokenRelay` configuration needed when using the standard HTTP proxy behavior.

---

### 4.20 Gateway Rate Limiting Pattern (Redis Token Bucket)

**Problem:** How do you enforce per-user request limits across multiple gateway instances without race conditions?

**Solution:** Redis-backed token bucket with a Lua script for atomic check-and-decrement.

```
┌────────────────────────────────────────────────────────────────┐
│  Multi-Instance Rate Limiting — Why Redis is Required           │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  In-memory (WRONG):                                             │
│    Gateway-1: user "1" count = 8                               │
│    Gateway-2: user "1" count = 0  ← different counter!         │
│    User hits limit on Gateway-1, but Gateway-2 still allows    │
│    → effective limit = instances × per-instance limit          │
│                                                                 │
│  Redis shared state (CORRECT):                                  │
│    Gateway-1 reads redis → user "1" tokens = 8                 │
│    Gateway-2 reads redis → user "1" tokens = 7  (already used) │
│    Both see same shared counter                                 │
│    → effective limit = configured limit, regardless of routing  │
│                                                                 │
│  Lua script atomicity:                                          │
│    Without atomic: Thread A reads 1, Thread B reads 1,         │
│    both allow, both decrement → -1 (both served 1 token)       │
│    With Lua: A's script runs entirely → B's script sees 0       │
│    → only A is served, B gets 429                               │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

**KeyResolver pattern — key determines the bucket:**

- Authenticated request → `ReactiveSecurityContextHolder.getContext().map(ctx -> ctx.getAuthentication().getPrincipal().toString())` → userId string → one bucket per user (all devices share)
- Anonymous request → `exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()` → IP string → one bucket per IP (login brute-force protection)
- The `.defaultIfEmpty(ip)` only fires when the SecurityContext `Mono` is empty (unauthenticated path)

**SpEL bean reference pattern:** `key-resolver: "#{@userKeyResolver}"` in YAML — `#{}` is Spring Expression Language, `@beanName` dereferences the bean from `ApplicationContext`. The `@Bean` method name must match exactly.

---

### 4.21 Centralized Response Header Pattern (GlobalFilter)

**Problem:** How do you add the same HTTP response headers to every response from every service without modifying 7 services?

**Solution:** `GlobalFilter` at the gateway with `LOWEST_PRECEDENCE`.

```
Request lifecycle in the reactive gateway:
    │
    ├── GlobalFilter A (HIGHEST_PRECEDENCE = Integer.MIN_VALUE)
    │   → CorrelationIdGatewayFilter: generates X-Correlation-Id
    │
    ├── SecurityWebFilterChain
    │   → validates token, populates ReactiveSecurityContextHolder
    │
    ├── RequestRateLimiter (default-filter)
    │   → checks Redis bucket
    │
    ├── ProxyExchange
    │   → forwards to downstream service
    │   → receives response from service
    │
    └── GlobalFilter B (LOWEST_PRECEDENCE = Integer.MAX_VALUE)
        → SecurityHeadersGlobalFilter: adds OWASP headers to response
        → .then(Mono.fromRunnable()) runs AFTER downstream response received
        → headers set on buffered response before Netty flushes to client
```

**`chain.filter(exchange).then(Mono.fromRunnable())` explained:**

- `chain.filter(exchange)` = run everything downstream (auth, rate limit, proxy, receive response) → returns `Mono<Void>`
- `.then(...)` = subscribe to a second `Mono` only AFTER the first completes
- `Mono.fromRunnable(lambda)` = wrap a synchronous side-effect in a `Mono<Void>`
- Combined: "proxy to service → receive full response → THEN set headers → THEN Netty flushes"

**Discovery requirement:** Spring Cloud Gateway's `GatewayAutoConfiguration` collects beans implementing `GlobalFilter` at startup. The bean must exist in the `ApplicationContext` — requires `@Component` (or `@Bean` in a `@Configuration` class). Without it, the class is never instantiated, never collected, headers are never set.

---

### 4.22 Interview Questions — Gateway Security Patterns

**Q: "How does your gateway handle a Keycloak token rotation without restarting services?"**

A: `NimbusReactiveJwtDecoder` (gateway) and `NimbusJwtDecoder` (services) both cache JWKS public keys in memory keyed by `kid` (key ID). When Keycloak generates a new RSA key pair: new tokens carry the new `kid` in their JWT header. The decoder looks up the new `kid` in cache → not found → fetches the JWKS endpoint → both old and new keys now in cache → validates new token. Old tokens still validate from cache using old key. No restart needed. The cache self-heals on first request with an unknown `kid`.

**Q: "What happens to rate limiting when the same user's request hits different gateway instances?"**

A: Both instances read and write the SAME Redis key (`request_rate_limiter.{userId}.tokens`). The Lua script runs atomically on Redis — regardless of which gateway instance issued the command. Combined rate across all instances is correctly enforced. Redis is the single source of truth; the gateway instances are stateless workers. This is why in-memory rate limiting breaks with horizontal scaling.

**Q: "Why is `@EnableWebFluxSecurity` at the gateway but `@EnableMethodSecurity` at the services?"**

A: The gateway has no `@RestController` methods to annotate. It only routes. `@EnableWebFluxSecurity` is sufficient — it enables path-based rules via `.authorizeExchange()`. Services have controllers with `@PreAuthorize("hasRole('SELLER')")` annotations. `@EnableMethodSecurity` activates AOP weaving that intercepts those method calls and checks the annotation. Without it, `@PreAuthorize` annotations are silently ignored — any authenticated user can reach any endpoint regardless of role.

**Q: "What is the difference between a GlobalFilter, a GatewayFilter, and a default-filter?"**

A: `GlobalFilter` applies to EVERY request through the gateway (all routes). Registered by implementing the `GlobalFilter` interface + `@Component`. `GatewayFilter` applies to a SPECIFIC route (configured under a route's `filters:` in YAML). `default-filters` applies to ALL routes but configured via YAML (like `RequestRateLimiter`) rather than a `@Component`. The difference: `GlobalFilter` is Java code always active; `default-filter` is YAML-configured but globally applied. Both are run for every matching request.

---

## 5. Circuit Breaker Pattern (Resilience4j)

### 5.1 The Problem: Cascading Failures

When Service A calls Service B over the network, and Service B is slow or down, Service A's threads block waiting for responses. Under load, ALL of Service A's threads become stuck → Service A itself becomes unresponsive → Service C, which depends on A, also dies. This domino effect is a **cascading failure**.

```
Normal Operation:
  Client → Service A → Service B (responds in 50ms) → ✓

Service B goes down:
  Client → Service A → Service B (10s timeout...)   → thread stuck
  Client → Service A → Service B (10s timeout...)   → thread stuck
  Client → Service A → Service B (10s timeout...)   → thread stuck
  ... 200 threads stuck ...
  Client → Service A → no threads available → Service A DOWN
  Client → Service C → calls Service A → also stuck → Service C DOWN
  ↑ CASCADING FAILURE — one downstream outage kills everything upstream
```

**Root cause:** Service A keeps attempting calls to a service it already knows is broken, consuming resources (threads, sockets, memory) on futile attempts.

### 5.2 The Solution: Circuit Breaker (Michael Nygard, "Release It!", 2007)

The Circuit Breaker pattern is borrowed from electrical engineering: a fuse that trips when current exceeds a threshold, protecting the circuit from damage. In software: the breaker monitors call failures, and when failures exceed a threshold, it **stops making calls entirely** for a configured period — failing fast instead of waiting for timeouts.

```
┌─────────────────────────────────────────────────────────────────────┐
│                 CIRCUIT BREAKER STATE MACHINE                         │
│                                                                      │
│  ┌────────┐   failure rate        ┌──────┐   wait duration    ┌──────────┐
│  │ CLOSED │ ──── ≥ threshold ───▶ │ OPEN │ ──── expires ────▶ │HALF-OPEN │
│  │        │                       │      │                    │          │
│  │ (calls │                       │(fail │                    │(trial    │
│  │  pass  │                       │ fast)│                    │ calls)   │
│  │through)│                       │      │                    │          │
│  └───┬────┘                       └──┬───┘                    └────┬─────┘
│      │                               │                             │
│      │ failures below threshold      │  any new call               │ if trial calls succeed
│      └──── stays CLOSED ◀────────────┘ → immediate exception      └──── transition to CLOSED
│                                                                     │
│                                          if trial calls fail        │
│                                          └──── back to OPEN ◀───────┘
└─────────────────────────────────────────────────────────────────────┘
```

**Three states:**

| State     | Behavior                                                                                             | Analogy                                   |
| --------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| CLOSED    | All calls pass through normally. Failures are counted in a sliding window.                           | Normal wiring — electricity flows         |
| OPEN      | All calls are rejected immediately (CallNotPermittedException). No network call made. Timer running. | Tripped fuse — electricity blocked        |
| HALF-OPEN | A limited number of trial calls are permitted. If they succeed → CLOSED. If they fail → OPEN again.  | Electrician testing if the fault is fixed |

### 5.3 Resilience4j — Lightweight Fault Tolerance Library

Resilience4j (inspired by Netflix Hystrix, which was deprecated in 2018) provides:

- **Circuit Breaker** — fail-fast when downstream is unhealthy
- **Retry** — automatic retry with configurable delay
- **Rate Limiter** — throttle outgoing calls to respect API quotas
- **Bulkhead** — limit concurrent calls to prevent resource exhaustion
- **Time Limiter** — enforce max execution duration

Unlike Hystrix (which used thread-pool isolation and had a large footprint), Resilience4j is designed for Java 8+ with lightweight function decoration and no external dependencies beyond Vavr.

### 5.4 EquityCart Implementation

The `AlphaVantageClient` (market-data module) calls an external stock-price API that has rate limits, occasional timeouts, and downtime. Three Resilience4j mechanisms protect this integration:

```
AlphaVantageClient.getStockQuote("AAPL")
       │
       ▼
┌─────────────────────────────────────────────────────────────┐
│ ANNOTATION STACK (applied as CGLIB proxy decorators)         │
│                                                              │
│  @Retry(name = "alphaVantage")                               │
│    └─ wraps ↓                                                │
│  @CircuitBreaker(name = "alphaVantage", fallback = "...")     │
│    └─ wraps ↓                                                │
│  @RateLimiter(name = "alphaVantage")                         │
│    └─ wraps ↓                                                │
│  actual WebClient HTTP call                                   │
│                                                              │
│  Execution order (outermost to innermost):                   │
│  Retry → CircuitBreaker → RateLimiter → HTTP call            │
│                                                              │
│  This means:                                                 │
│  • Rate Limiter checks FIRST: "am I within 5 calls/60s?"    │
│  • Circuit Breaker checks NEXT: "am I OPEN?"                │
│  • If both pass: the HTTP call executes                       │
│  • If call fails: Circuit Breaker records the failure         │
│  • Retry re-invokes the ENTIRE chain (including CB + RL)     │
└─────────────────────────────────────────────────────────────┘
```

### 5.5 Configuration (application.yml)

```yaml
resilience4j:
  retry:
    instances:
      alphaVantage:
        max-attempts: 3 # Try up to 3 times total (1 initial + 2 retries)
        wait-duration: 2s # Wait 2 seconds between retry attempts

  circuitbreaker:
    instances:
      alphaVantage:
        failure-rate-threshold: 50 # Open when ≥50% of calls in window fail
        wait-duration-in-open-state: 30s # Stay OPEN for 30s before trying HALF-OPEN
        permitted-number-of-calls-in-half-open-state: 3 # Allow 3 trial calls in HALF-OPEN
        sliding-window-size: 10 # Track the last 10 calls for failure rate

  rate-limiter:
    instances:
      alphaVantage:
        limit-for-period: 5 # Max 5 calls per refresh period
        limit-refresh-period: 60s # Period resets every 60 seconds
        timeout-duration: 0s # Don't wait — reject immediately if limit exceeded
```

### 5.6 Detailed Behavior Walkthrough

#### Retry

```
Call 1: HTTP request → timeout (10s response timeout from WebClientConfig)
  └─ Retry counts this as attempt 1, waits 2s
Call 2: HTTP request → timeout again
  └─ Retry counts this as attempt 2, waits 2s
Call 3: HTTP request → success!
  └─ Returns the Mono<StockQuote> to caller

OR

Call 3: HTTP request → fails
  └─ max-attempts (3) exhausted → propagates the exception up
  └─ Circuit Breaker records this as a FAILURE in its sliding window
```

#### Circuit Breaker — Sliding Window

The sliding window tracks the last N calls (N = `sliding-window-size` = 10):

```
Window: [✓, ✓, ✓, ✓, ✓, ✗, ✗, ✗, ✗, ✗]  → 5 failures / 10 calls = 50%
                                                ≥ threshold (50%)
                                                → TRANSITION TO OPEN

In OPEN state (next 30 seconds):
  Any call to getStockQuote() → immediate CallNotPermittedException
  → No HTTP request made (fail-fast, no resource waste)
  → Fallback method invoked: getStockQuoteFallback(symbol, throwable)
    → Returns Mono.error("Unable to fetch stock quote for AAPL due to: ...")
    → Caller gets error immediately instead of waiting 10s for timeout

After 30 seconds → TRANSITION TO HALF-OPEN:
  Allow 3 trial calls through:
  [✓, ✓, ✓] → all succeed → TRANSITION TO CLOSED (recovered!)
  [✓, ✗, _] → failure detected → TRANSITION BACK TO OPEN (still broken)
```

#### Rate Limiter — API Quota Protection

```
Alpha Vantage free tier: 5 calls/minute hard limit (HTTP 429 beyond that).

Our config: limit-for-period=5, limit-refresh-period=60s, timeout-duration=0s

Call timeline:
  T+0s:  call 1 → permitted (1/5 used)
  T+5s:  call 2 → permitted (2/5 used)
  T+10s: call 3 → permitted (3/5 used)
  T+20s: call 4 → permitted (4/5 used)
  T+30s: call 5 → permitted (5/5 used)
  T+35s: call 6 → REJECTED immediately (timeout-duration=0s, no waiting)
         → throws RequestNotPermitted exception
         → Circuit Breaker counts this as a failure? NO — it's configured not to
            (rate limiter rejection is not a downstream failure)
  T+60s: period refreshes → call 7 → permitted (1/5 used in new period)

timeout-duration=0s means: "If the limit is reached, reject IMMEDIATELY."
  vs timeout-duration=5s: "Wait up to 5s for a permit to become available."
  We chose 0s because: waiting 55s for a rate limit permit is worse UX than
  failing fast and telling the user to try again in a minute.
```

### 5.7 The CGLIB Proxy Mechanism — Why Annotations Work

Resilience4j annotations (`@Retry`, `@CircuitBreaker`, `@RateLimiter`) are processed by Spring AOP (Aspect-Oriented Programming). At startup:

```
1. Spring finds @Component class AlphaVantageClient
2. Spring detects Resilience4j annotations on getStockQuote()
3. Spring creates a CGLIB PROXY (subclass) of AlphaVantageClient
4. The proxy overrides getStockQuote() to wrap it in Resilience4j logic
5. All beans that @Autowire AlphaVantageClient receive the PROXY, not the real object

When MarketDataServiceImpl calls client.getStockQuote("AAPL"):
  → Hits the PROXY's method
  → Proxy: check Retry → check CircuitBreaker → check RateLimiter → call real method
  → If real method fails → Proxy handles retry/fallback/rate-limit logic
```

**Critical constraint:** Self-calls bypass the proxy.

```java
// THIS WOULD NOT WORK:
@Component
public class AlphaVantageClient {
    @CircuitBreaker(name = "alphaVantage")
    public Mono<StockQuote> getStockQuote(String symbol) { ... }

    public void someOtherMethod() {
        this.getStockQuote("AAPL");  // ← calls the REAL method, NOT the proxy!
                                      // Circuit breaker is BYPASSED
    }
}
```

This is the same limitation that affects `@Transactional` — both rely on Spring AOP proxies. The proxy only intercepts external calls (from other beans). Internal calls go directly to `this`.

### 5.8 The Fallback Method — Design Decisions

```java
private Mono<StockQuote> getStockQuoteFallback(String symbol, Throwable t) {
    return Mono.error(new RuntimeException(
        "Unable to fetch stock quote for symbol: " + symbol + " due to: " + t.getMessage(), t));
}
```

The fallback is invoked when:

- Circuit Breaker is OPEN (CallNotPermittedException)
- All retry attempts are exhausted
- Rate Limiter rejects the call (RequestNotPermitted)

**Design choice: error propagation, not fake data.**

| Strategy                  | When appropriate                                                       |
| ------------------------- | ---------------------------------------------------------------------- |
| Return cached/stale data  | When approximate data is better than no data (dashboards, analytics)   |
| Return default value      | When a safe default exists (e.g., default config values)               |
| Return error (our choice) | When incorrect data is worse than no data (financial systems, trading) |

For stock prices: showing an outdated price could lead to bad trade decisions. Better to tell the user "market data unavailable" than to show $150 when the price dropped to $120.

### 5.9 Network Timeouts (WebClientConfig)

```java
WebClient.builder()
    .baseUrl(alphaVantageBaseUrl)
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)   // TCP handshake: 5 seconds
            .responseTimeout(Duration.ofSeconds(10))))            // Full response: 10 seconds
    .build();
```

| Timeout                     | What it bounds                               | Default without config |
| --------------------------- | -------------------------------------------- | ---------------------- |
| CONNECT_TIMEOUT_MILLIS (5s) | TCP SYN → SYN-ACK (establishing connection)  | 30s (Netty default)    |
| responseTimeout (10s)       | Time from request sent → first response byte | Infinite (no timeout!) |

**Why these values matter for Resilience4j:**

Without explicit timeouts, a hanging connection would block for 30s+ before Retry even kicks in.
With 10s response timeout: each retry attempt is bounded to ~10s max.
Total worst case: 3 attempts × (10s timeout + 2s wait) = 36 seconds before final failure.

### 5.10 Annotation Stacking Order (Resilience4j Default Priority)

Resilience4j defines a default decoration order (highest → lowest priority):

```
1. Retry          (outermost — retries the entire inner chain)
2. CircuitBreaker (records successes/failures for state transitions)
3. RateLimiter    (innermost — checks quota before calling)
4. TimeLimiter    (if present)
5. Bulkhead       (if present)
```

This order means:

- **Retry wraps CircuitBreaker:** A retry re-checks the circuit breaker state each time. If the CB opened during retry wait → next retry fails immediately (no pointless call).
- **CircuitBreaker wraps RateLimiter:** A rate-limit rejection (RequestNotPermitted) is NOT counted as a circuit breaker failure — it's a local throttle, not a downstream problem.

You can customize this order via `resilience4j.circuitbreaker.circuitBreakerAspectOrder` properties, but defaults are correct for most use cases.

### 5.11 When to Use Circuit Breaker vs Other Patterns

| Scenario                         | Pattern                                                       |
| -------------------------------- | ------------------------------------------------------------- |
| External API might be down       | Circuit Breaker (fail-fast, protect threads)                  |
| Transient network blip           | Retry (brief wait, try again)                                 |
| API has call quota               | Rate Limiter (enforce quota locally)                          |
| Prevent thread exhaustion        | Bulkhead (limit concurrent calls)                             |
| Need all four                    | Stack them (as in EquityCart's AlphaVantageClient)            |
| Calling your own database        | Typically none — if your DB is down, you're down anyway       |
| Calling another internal service | Circuit Breaker + Retry (network boundaries = failure points) |

### 5.12 EquityCart Implementation Files

```
equitycart/market-data/src/main/java/com/equitycart/marketdata/
├── client/AlphaVantageClient.java       ← @Retry + @CircuitBreaker + @RateLimiter annotated
├── config/WebClientConfig.java          ← Reactor Netty HttpClient with connect + response timeouts
└── (application.yml)                    ← resilience4j.retry/circuitbreaker/ratelimiter config

Dependencies (market-data/build.gradle):
  implementation 'io.github.resilience4j:resilience4j-spring-boot3'
  implementation 'org.springframework.boot:spring-boot-starter-aop'  (required for @Retry etc.)
```

### 5.13 Historical Context

- **2007**: Michael Nygard publishes "Release It!" — first popular description of the Circuit Breaker pattern for software
- **2011**: Netflix builds Hystrix (internal) to handle cascading failures in their microservices (200+ services calling each other)
- **2012**: Netflix open-sources Hystrix — becomes the de facto Java circuit breaker library
- **2018**: Netflix announces Hystrix maintenance mode — recommends alternatives
- **2018**: Resilience4j 1.0 released — designed as Hystrix successor, lighter weight, Java 8+, no thread-pool isolation (uses decorators instead)
- **Today**: Resilience4j is the standard for Spring Boot 3.x applications (Spring Cloud Circuit Breaker wraps it)

Key philosophical difference: Hystrix used thread-pool isolation (each downstream got its own thread pool). Resilience4j uses semaphore-based bulkheads by default — less overhead, less isolation. Trade-off: Hystrix guaranteed that a slow downstream couldn't starve other downstreams' threads; Resilience4j trusts your timeouts to be correct.

---

## Phase 7: Infrastructure Patterns — Eureka, Config Server, Gateway (2026-06-02)

### Pattern: Service Discovery (Eureka Registry)

**Problem:** Services scale dynamically — IPs change, instances die. Hardcoded IPs break immediately.

**Solution:** Eureka maintains an in-memory registry. Services self-register; clients query it.

**Registration lifecycle:**

```
Service startup → POST /eureka/apps/{appName} (host, port, status)
Service running → Heartbeat every 30s → refreshes 90s TTL lease
Service failure → Misses 3 heartbeats → Eureka evicts (if self-preservation OFF)
Other services  → Fetch full registry every 30s → cache locally
```

**Self-preservation mode:** In production, leave enabled (prevents eviction during network partitions). In dev, disable (`enable-self-preservation: false`) so failures are immediately visible.

**Discovery Server config:**

```yaml
eureka:
  client:
    register-with-eureka: false # This IS the server
    fetch-registry: false # Server doesn't need its own registry
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 10000
```

**Client service requirements:**

1. `spring-cloud-starter-netflix-eureka-client` in build.gradle
2. `@EnableDiscoveryClient` on main class
3. `spring.application.name` set in application.yml
4. `eureka.client.serviceUrl.defaultZone: http://localhost:8761/eureka/` in config

---

### Pattern: Centralized Configuration (Config Server + Git Backend)

**Problem:** Service configs differ per environment, but rebuilding JARs for config changes is expensive and error-prone.

**Solution:** Config Server reads from Git (single source of truth), serves merged configs to clients.

**Git repository structure:**

```
equitycart-config/
├── application.yml         # Shared: JPA, Kafka, logging (all services)
├── api-gateway.yml         # Gateway-specific: port, routes, actuator
├── user-service.yml        # User-service-specific: port, DB settings
├── portfolio-service.yml   # Portfolio-specific
└── ... (one per service)
```

**Merge order (highest priority last wins):**

```
Spring defaults ← application.yml (base) ← service.yml (overrides) ← local application.yml
```

**Client bootstrap:**

```yaml
# local application.yml (MUST be in application.yml, NOT bootstrap.yml in Spring Cloud 2025.0.0)
spring:
  application:
    name: api-gateway # ← Config Server uses this to find api-gateway.yml
  config:
    import: configserver:http://localhost:8888 # ← Fetch from Config Server
```

**Why separate config repo?**

- Ops can change environment configs without rebuilding/redeploying code
- Git history provides audit trail for all config changes
- Config rollback = Git revert (no DB migration needed)
- Supports multiple environments (dev/staging/prod) via Spring profiles

---

### Pattern: API Gateway as Service Mesh Entry Point

**Problem:** N clients × M services = N×M connections, duplicated auth/logging, hardcoded service IPs.

**Solution:** Single gateway entry point handles routing, cross-cutting concerns, service discovery.

**Routing via Eureka (lb:// URIs):**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service # Eureka resolves this dynamically
          predicates:
            - Path=/api/auth/**,/api/users/**
```

**How lb:// works:**

```
Client: POST /api/auth/login
  → Gateway: matches Path=/api/auth/**
  → lb://user-service → Eureka lookup: [localhost:8081, localhost:8091 (if scaled)]
  → LoadBalancer picks instance (round-robin)
  → Gateway forwards to http://localhost:8081/api/auth/login
  → Returns response to client
```

**Gateway as cross-cutting concern host:**

- Authentication (verify JWT before forwarding) — Phase 8
- Rate limiting (per client/IP) — Phase 8
- Correlation ID injection (add X-Correlation-Id header) — Phase 7 Step 11
- Request/response logging — Phase 9

**Port allocation (EquityCart Phase 7):**

```
8080 → API Gateway (single entry point for clients)
8081 → user-service (future)
8082 → equitycart app (current monolith, transitional)
8083 → order-service (future)
8084 → portfolio-service (future)
8085 → market-data-service (future)
8086 → ledger-service (future)
8087 → notification-service (future)
8761 → Eureka Discovery Server
8888 → Config Server
```

---

### Database-per-Service vs Single Database + Multiple Schemas

**Shared database (single DB, multiple schemas):**

- Pros: ACID transactions across schemas, single backup, lower infra cost
- Cons: Schema changes affect all services, shared connection pool, tight coupling
- Example: Real-world `momentum` app — 25+ schemas in one PostgreSQL database

**Database-per-service (recommended for true microservices):**

- Pros: Independent deployments, independent scaling, technology choice per service
- Cons: No cross-service ACID, requires Saga pattern for distributed transactions, more infra

**EquityCart decision:** Database-per-service for independence. Multi-service transactions use Saga Orchestrator (implemented in Phase 6 SellToSpendSagaOrchestrator). Each extracted service (Steps 4-9) gets its own PostgreSQL database.

---

## 7. The Strangler Fig Pattern (Phase 7, Step 4)

**Why "Strangler Fig"?** The name comes from a tropical plant. The strangler fig starts as a vine on the outside of a large host tree, slowly grows roots and branches around it, and eventually completely encases and replaces the host tree — which dies, leaving the fig tree standing in its shape. Martin Fowler coined this analogy for monolith decomposition in 2004: the new microservices system grows around the old monolith, taking over function by function, until the monolith can be switched off.

### 7.1 The Problem: Big Bang Rewrites

The naive decomposition strategy: stop everything, rewrite all services from scratch, switch over on day one. This is the "Big Bang" rewrite.

Why it fails:

- Rewrites take months/years — business cannot freeze feature development during that time
- New system complexity is underestimated (edge cases only appear in production)
- No rollback path if the new system has bugs on day one
- Team has no operational experience with the new system before it's live
- Martin Fowler: "Big bang rewrites are the riskiest strategy possible"

### 7.2 The Solution: Incremental Extraction

Extract one bounded context at a time. Run the new service alongside the monolith, routing a subset of traffic to it via the gateway. Each extraction is independently deployable, testable, and reversible. The monolith shrinks service by service until it handles nothing.

```
EquityCart extraction timeline (Phase 7):

Steps 1-3:  Infrastructure (Eureka + Config Server + Gateway)
            Monolith still handles ALL requests directly

Step 4:     User-Service extracted (port 8081)
            Gateway: /api/auth/** and /api/users/** → user-service
            Monolith: still running on 8082 (user code present, routes bypassed)

Step 5:     Market-Data-Service extracted (port 8085)
            Gateway: /api/market-data/** → market-data-service

Steps 6-9:  Order, Portfolio, Ledger, Notification extracted one by one

Step 10:    OpenFeign replaces direct project() dependencies
            Monolith project() imports removed one by one

Step 12:    Docker Compose — monolith retired, all services standalone
```

### 7.3 The Gateway is the Strangler Facade

In the physical analogy, the fig tree grows a new outer structure around the old tree. In the pattern, the **API Gateway is that outer structure** — it intercepts all client requests and routes them to either the extracted microservice (new) or falls through to the monolith (old). Clients never change their URLs. The migration is transparent.

```
Before extraction:
  Client → monolith (8082) → all business logic in one JVM

After Step 4:
  Client → Gateway (8080) → lb://user-service → User-Service (8081)   [auth requests: NEW path]
  Client → monolith (8082)                                             [other requests: OLD path]

Both paths coexist. User-Service is the fig growing around the monolith's user domain.
```

### 7.4 Dual Plugin Pattern (Gradle multi-module monorepo)

When extracting from a Gradle monorepo, the service was a `java-library` (library for the monolith) and needs to become `org.springframework.boot` (executable for standalone deployment). Both are needed during the transition.

```
Monolith needs:    plain jar (so implementation project(':user-service') can compile it)
Standalone needs:  executable bootJar (for java -jar user-service.jar)

Solution: apply both plugins + re-enable plain jar:

plugins {
    id 'org.springframework.boot'         // builds executable bootJar
    id 'io.spring.dependency-management'
}
jar { enabled = true }                    // re-enable plain jar (disabled by spring-boot plugin by default)
bootJar { archiveBaseName.set('user-service') ... }

Result — two artifacts in build/libs/:
  user-service-1.0.0.jar         <- plain jar (monolith classpath dependency)
  user-service-1.0.0-exec.jar    <- executable bootJar (standalone deployment)
```

Modules that are ONLY standalone (discovery-server, api-gateway, config-server) do NOT need `jar { enabled = true }` — no other module depends on them as libraries.

### 7.5 Config Duplication During Transition (Expected, Temporary)

While a service is being extracted, the monolith and standalone service need identical config values. This creates intentional duplication:

```
app/application.yml:
  jwt.secret: XYZ            <- monolith needs this (still compiles user-service code)

equitycart-config/user-service.yml:
  jwt.secret: XYZ            <- standalone user-service also needs it

Cleanup rule: remove from app/application.yml ONLY AFTER removing
              implementation project(':user-service') from app/build.gradle.
              Premature removal breaks the monolith.
```

### 7.6 Database Transition Strategy

Each extracted service gets its own database (database-per-service). During transition both the monolith and the extracted service may have tables for the same domain:

```
Before Step 4:
  monolith → equitycart database (all tables including users, roles, refresh_tokens)

After Step 4:
  monolith → equitycart database (user tables still exist but routes bypassed by gateway)
  user-service → equitycart_user database (NEW — Hibernate creates tables on first start via ddl-auto: update)

  Authoritative path: all /api/auth/** goes through gateway → user-service (8081)
  Monolith user tables: legacy data, no new writes via gateway routes

After all services extracted:
  Each service → its own database (equitycart_user, equitycart_order, equitycart_portfolio, ...)
  equitycart (original monolith database) → abandoned, safely dropped
```

### 7.7 ddl-auto Hierarchy During Extraction

Config Server merge priority in action:

```
equitycart-config/application.yml (base — all services):
  spring.jpa.hibernate.ddl-auto: validate   <- production-safe default
  Rationale: prod schemas maintained by Flyway, not auto-DDL; auto-DDL on prod is dangerous

equitycart-config/user-service.yml (service-specific override):
  spring.jpa.hibernate.ddl-auto: update     <- dev convenience override
  Rationale: new equitycart_user schema starts empty, needs tables created automatically

Merge result for user-service: update (service overrides base)
Other services: still get validate until they add their own override

In production: add application-prod.yml to equitycart-config with ddl-auto: validate,
deploy with spring.profiles.active=prod, use Flyway for controlled schema migrations.
```

### 7.8 Eureka Default vs Explicit Registration

Spring Cloud's auto-configured default: `eureka.client.serviceUrl.defaultZone = http://localhost:8761/eureka/`

This works locally. In Docker Compose or Kubernetes, Eureka runs on a container hostname (`eureka-server:8761`). Services relying on the default silently fail to register in non-local environments.

**Rule:** Always set `defaultZone` explicitly in every service's config YAML, even if the default would work locally. Explicit config is self-documenting and environment-portable.

### 7.9 Interview Questions on Strangler Fig

**"What is the Strangler Fig pattern and why is it preferred over Big Bang rewrites?"**

Big Bang: stop all development, rewrite from scratch, switch over at once. High risk (no rollback), long timeline (months with no business value), lost domain knowledge, no operational experience. Strangler Fig: extract one service at a time, route traffic via gateway, monolith and microservices coexist. Each extraction is independently testable, rollback is a gateway config change. Business features continue shipping during the migration.

**"How does the API Gateway enable the Strangler Fig pattern?"**

The gateway is the Strangler Facade — the single entry point that routes requests to either extracted microservices or the legacy monolith. Clients never change their URLs. As each service is extracted, the gateway gains a new route rule. The monolith handles fewer requests over time. When the monolith handles zero, it can be shut down.

**"What happens to data during Strangler Fig extraction?"**

For read-heavy domains: dual-read (both systems serve reads) until confident, then cut over. For write-heavy domains: the extracted service becomes the authoritative write path via gateway routing. The monolith database for that domain stops receiving writes. After verification, the monolith tables are deprecated. In EquityCart: equitycart_user is the authoritative source once user-service is registered with Eureka and gateway routes to it.

---

### 7.10 Phase-Based Extraction: The Six-Service Comparison

Not all services in a Strangler Fig decomposition can be extracted simultaneously — the order depends on inter-service dependencies. Annotation complexity is a direct proxy for coupling complexity:

| Service              | Annotations beyond minimum                                                                                                                              | Reason                                                                     |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| User-service         | `@EnableDiscoveryClient` only                                                                                                                           | All beans in `com.equitycart.user.*`, no cross-module deps                 |
| Market-data-service  | `@EnableDiscoveryClient` only, removed `commons` dep                                                                                                    | Transitive JPA blast — `commons` `api` scope leaked JPA to a no-DB service |
| Order-service        | `@EnableDiscoveryClient`, `@EnableJpaRepositories`, `@EntityScan`, `@EnableScheduling`                                                                  | Cross-module `ProductRepository` + cross-module entities + OutboxPoller    |
| Portfolio-service    | `@ComponentScan` + `excludeFilters`, `@EnableJpaRepositories`, `@EntityScan`, `@EnableMongoRepositories`, `@EnableDiscoveryClient`, `@EnableScheduling` | Full service layer from 5 modules needed                                   |
| Ledger-service       | `@EnableDiscoveryClient`, `@EntityScan`                                                                                                                 | `BaseEntity` in commons is sole out-of-scope class                         |
| Notification-service | `@EnableDiscoveryClient`, `@EntityScan`                                                                                                                 | Same as ledger — `NotificationLog` extends `BaseEntity`                    |

Portfolio-service needs the most annotations because it has the most cross-module dependencies — every annotation signals one more inter-service coupling that will be eliminated in Phase 10.

### 7.11 Library Service Extraction Constraint

A service acting as a shared library **cannot** be extracted as a standalone microservice until all its consumers have migrated to HTTP.

In EquityCart, `product-service` is consumed as a library by two services:

- `order-service`: `implementation project(':product-service')` → `OrderServiceImpl` injects `ProductRepository` directly for pessimistic stock locking
- `portfolio-service`: `@ComponentScan` covers `com.equitycart.product.*` → `ProductServiceImpl` is loaded as a live Spring bean

Removing `project(':product-service')` from either build without a replacement causes startup failures — missing beans and unresolvable class imports at compile time.

**Resolution (Phase 10):** OpenFeign HTTP clients replace both direct dependencies. Order-service gets a `ProductFeignClient` that calls `GET /api/products/{id}` over HTTP. Portfolio-service stops scanning `com.equitycart.product.*` and uses the Feign client instead. At that point, product-service becomes a true standalone with REST endpoints and no consumers left on its classpath.

**Strangler Fig principle:** Extract independent services first. Break remaining tight couplings only after the HTTP boundary is established. Never force an extraction before the consumer migration is ready — an incomplete extraction that requires hacks to compile is worse than the monolith state you started from.

---

## 7.12 Distributed Transaction Problem - Why Cross-Service Atomicity Is Hard

### The Problem

In a monolith, a single @Transactional method calling multiple repositories is atomic: all changes commit or none do. In microservices, each service has its own database and connection pool. There is no shared transaction coordinator.

EquityCart example: OrderServiceImpl.placeOrder() calls:

1. productFeignClient.deductStock() - product-service commits a stock decrement to its database
2. orderRepository.save(order) - order-service commits the order to its database

If step 2 fails after step 1 commits, stock is decremented but no order exists. There is no automatic rollback - the HTTP call already completed and the product-service transaction is closed.

---

### Why Distributed Transactions Are Not the Answer

**Two-Phase Commit (2PC):** Coordinates commit/rollback across databases via a shared coordinator. Problems: synchronous blocking (both databases held in prepared state), coordinator is a single point of failure, does not work across heterogeneous datastores (PostgreSQL + MongoDB + Redis).

**XA Transactions:** Standard Java implementation of 2PC via JTA. Same fundamental problems.

Not used in modern microservice architectures.

---

### The Solution: Saga Pattern

A Saga is a sequence of local transactions, each with a compensating transaction that undoes it if a later step fails.

Forward: deductStock() -> saveOrder() -> clearCart()
Compensate: restoreStock() <- [triggered if saveOrder fails]

Two variants:

- **Choreography:** Each service publishes an event; the next service listens and reacts. No central coordinator. Harder to trace.
- **Orchestration:** A central orchestrator drives the sequence and compensations explicitly. EquityCart SellToSpendSagaOrchestrator follows this pattern.

Order placement (deductStock -> saveOrder) is a Phase 10 known limitation - full Saga coverage for this flow is a future phase.

---

### ACID vs BASE

Microservices trade ACID for BASE (Basically Available, Soft-state, Eventually consistent):

- Operations are not globally atomic - they become consistent eventually via compensations and retries
- Designing for compensation from the start is mandatory; retrofitting is expensive

---

## 7. Docker Compose Patterns for Microservices

### 7.1 Two-File Split (Infrastructure vs Application)

Separate infrastructure (databases, brokers) from application services:

- `docker-pets.yml` — PostgreSQL, Kafka, Redis, MongoDB, Debezium, MailHog
- `docker-compose-services.yml` — Spring Boot microservices

**Why:** Different lifecycles. You restart services 10x/day during development; you restart databases rarely. Splitting prevents accidental data loss and speeds up iteration.

### 7.2 Startup Ordering Pattern

`depends_on` only ensures container start order, not application readiness. For Spring Cloud services:

```
Infrastructure (must be READY: accepting connections)
  → Discovery Server (must be READY: accepting registrations)
    → Config Server (must be READY: serving configs)
      → Business Services (can start in parallel after config-server)
```

Readiness scripts poll HTTP endpoints before proceeding:

```bash
until curl -s http://localhost:8761/actuator/health | grep -q '"status":"UP"'; do
  sleep 5
done
```

### 7.3 Config Externalization Pattern

Same Docker image works in ANY environment via environment variables:

```yaml
environment:
  - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/equitycart_order
  - EUREKA_URL=http://discovery:8761/eureka/
```

The JAR is immutable — behavior changes come from environment, not code changes. This is the Twelve-Factor App methodology (Factor III: Store config in the environment).

### 7.4 Service Mesh (preview — current state)

Current inter-service communication in EquityCart:

- **Synchronous:** OpenFeign (HTTP) via Eureka service discovery (lb:// URIs)
- **Asynchronous:** Kafka events (order-delivered, portfolio-notification, sell-to-spend-saga)
- **Observability:** Correlation ID propagated via Gateway GlobalFilter → Feign RequestInterceptor

Future (Phase 9-10): Istio service mesh would replace Eureka/Feign with sidecar proxies for mTLS, traffic management, and circuit breaking at the infrastructure level.

---

## 8. Observer Pattern (Distributed) — Kafka Pub/Sub

### GoF Observer vs Kafka Observer

| GoF Observer (in-memory)                      | Kafka Observer (distributed)                            |
| --------------------------------------------- | ------------------------------------------------------- |
| Subject maintains `List<Observer>`            | Producer has no knowledge of consumers                  |
| `subject.notifyAll()` — synchronous, blocking | `kafkaTemplate.send()` — async, non-blocking            |
| Observer failure blocks subject               | Consumer failure doesn't affect producer                |
| Same JVM, same thread                         | Cross-process, cross-machine                            |
| No persistence                                | Events retained, replayable                             |
| Adding observer = code change                 | Adding consumer = new consumer group (zero code change) |

### EquityCart Implementation

Portfolio services are **subjects** (publishers). Notification service is the **observer** (subscriber). Kafka broker is the **intermediary** — the original GoF pattern lacks this, limiting it to same-JVM communication.

Key benefit: TradeServiceImpl has ZERO knowledge of how or whether notifications are sent. It publishes an event and moves on. The notification service can be down, restarted, or replaced without affecting trade execution.

---

## 9. Strategy Pattern — Pluggable Notification Channels

### Pattern Structure

```
NotificationChannelStrategy (interface)
  │
  ├── LogChannelStrategy      (logs at INFO level — zero infrastructure)
  ├── EmailChannelStrategy    (JavaMailSender → MailHog SMTP)
  └── WebhookChannelStrategy  (WebClient POST to configurable URL)
```

### Runtime Channel Selection

```yaml
equitycart:
  notification:
    channel: LOG # Change to EMAIL or WEBHOOK without code modification
```

The dispatcher uses Spring's `Map<String, NotificationChannelStrategy>` auto-injection (bean name → bean instance) to resolve the active channel at runtime. New channels can be added by implementing the interface and annotating with `@Component("newChannel")` — zero changes to existing code.

- The Outbox Pattern (already implemented for order events) ensures event delivery is durable even if the broker is temporarily down

---

## 10. Token Propagation Pattern — RequestContextHolder Deep Dive

### The Problem

After Phase 8 Step 2, every service enforces JWT authentication. But consider this scenario:

```
User (Browser)                    order-service                    product-service
     │                                 │                                 │
     │── POST /api/orders ────────────→│                                 │
     │   Authorization: Bearer eyJ...  │                                 │
     │                                 │── GET /api/products/123 ───────→│
     │                                 │   (NO Authorization header!)     │
     │                                 │                                 │
     │                                 │←─── 401 Unauthorized ───────────│
     │←── 500 Internal Error ─────────│                                 │
```

The user's token arrives at order-service, but when order-service calls product-service via Feign, it creates a **brand new HTTP request** — the original headers are not copied automatically. Product-service sees no token → rejects with 401.

### The Solution: FeignAuthorizationInterceptor

```
User (Browser)                    order-service                    product-service
     │                                 │                                 │
     │── POST /api/orders ────────────→│                                 │
     │   Authorization: Bearer eyJ...  │                                 │
     │                                 │  [FeignAuthorizationInterceptor]│
     │                                 │  reads original request header   │
     │                                 │  copies to Feign RequestTemplate │
     │                                 │                                 │
     │                                 │── GET /api/products/123 ───────→│
     │                                 │   Authorization: Bearer eyJ...  │
     │                                 │                                 │
     │                                 │←─── 200 OK (product data) ──────│
     │←── 201 Created ────────────────│                                 │
```

---

### Spring Internals: How RequestContextHolder Works

#### What is ThreadLocal?

Java's `ThreadLocal<T>` is a variable where each thread has its own independent copy. Think of it as a per-thread HashMap:

```
Thread Pool (Tomcat default: 200 threads)
┌─────────────────────────────────────────────────┐
│ Thread-1: ThreadLocal → { requestAttributes: req_A }  │  ← handling User A's request
│ Thread-2: ThreadLocal → { requestAttributes: req_B }  │  ← handling User B's request
│ Thread-3: ThreadLocal → { requestAttributes: null  }  │  ← idle thread
│ ...                                                     │
│ Thread-200: ThreadLocal → { requestAttributes: req_C }│  ← handling User C's request
└─────────────────────────────────────────────────┘
```

No synchronization needed — each thread reads/writes only its own copy. Thread-1 can NEVER see Thread-2's data.

#### The Full Request Lifecycle (Debug Mode)

Here's EXACTLY what happens when a request arrives, traced through Spring source code:

```
STEP 1: HTTP request arrives at Tomcat
─────────────────────────────────────────
Tomcat assigns Thread-42 from its pool to handle this request.

STEP 2: FrameworkServlet.service() (Spring MVC entry point)
─────────────────────────────────────────
Class: org.springframework.web.servlet.FrameworkServlet
Method: service(HttpServletRequest req, HttpServletResponse res)

  Internally calls → processRequest(request, response)

STEP 3: FrameworkServlet.processRequest() — THE KEY METHOD
─────────────────────────────────────────
This is where RequestContextHolder gets populated:

  // Spring source (simplified):
  RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
  ServletRequestAttributes requestAttributes = new ServletRequestAttributes(request, response);

  // ★ THIS IS THE LINE THAT STORES THE REQUEST IN ThreadLocal ★
  RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);

  try {
      doService(request, response);  // → DispatcherServlet.doService()
  } finally {
      // ★ CLEANUP: removes request from ThreadLocal after response is sent ★
      RequestContextHolder.resetRequestAttributes();
      requestAttributes.requestCompleted();
  }

STEP 4: RequestContextHolder internal storage
─────────────────────────────────────────
Class: org.springframework.web.context.request.RequestContextHolder

  // The actual ThreadLocal fields:
  private static final ThreadLocal<RequestAttributes> requestAttributesHolder =
      new NamedThreadLocal<>("Request attributes");

  private static final ThreadLocal<RequestAttributes> inheritableRequestAttributesHolder =
      new NamedInheritableThreadLocal<>("Request context");

  public static void setRequestAttributes(RequestAttributes attributes, boolean inheritable) {
      if (inheritable) {
          inheritableRequestAttributesHolder.set(attributes);  // propagates to child threads
      } else {
          requestAttributesHolder.set(attributes);  // default: thread-local only
      }
  }

  public static RequestAttributes getRequestAttributes() {
      RequestAttributes attributes = requestAttributesHolder.get();
      if (attributes == null) {
          attributes = inheritableRequestAttributesHolder.get();
      }
      return attributes;  // returns null if neither is set (non-HTTP thread)
  }

STEP 5: DispatcherServlet routes to your Controller
─────────────────────────────────────────
doService() → doDispatch() → HandlerAdapter → YourController.method()

  At this point, Thread-42's ThreadLocal contains:
  requestAttributesHolder = ServletRequestAttributes(originalRequest)

STEP 6: Controller calls Feign client
─────────────────────────────────────────
Class: com.equitycart.order.controller.OrderController
Method: createOrder(...)

  // Inside your controller code:
  ProductResponse product = productFeignClient.getProduct(productId);

  // This triggers Feign's internal pipeline...

STEP 7: Feign builds the outgoing request
─────────────────────────────────────────
Class: feign.SynchronousMethodHandler
Method: executeAndDecode(RequestTemplate template, Options options)

  // Feign calls ALL registered RequestInterceptor beans:
  for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);  // ← YOUR interceptor runs here
  }

STEP 8: FeignAuthorizationInterceptor.apply() — YOUR CODE
─────────────────────────────────────────
  // Still on Thread-42! Same thread that received the original request.

  ServletRequestAttributes attrs =
      (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
  // ↑ reads Thread-42's ThreadLocal → finds the original HttpServletRequest

  String header = attrs.getRequest().getHeader("Authorization");
  // ↑ extracts "Bearer eyJhbGciOiJIUzI1NiJ9..." from original request

  template.header("Authorization", header);
  // ↑ copies it to the outgoing Feign request

STEP 9: Feign sends the HTTP request to product-service
─────────────────────────────────────────
  The outgoing request now carries:
  GET /api/products/123 HTTP/1.1
  Host: product-service:8089
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
  X-Correlation-Id: 7f3a9c2b-...  (added by FeignCorrelationInterceptor)

STEP 10: product-service receives the request
─────────────────────────────────────────
  product-service's JwtAuthenticationFilter validates the token
  → extracts userId + roles → sets SecurityContext
  → @PreAuthorize("hasRole('ADMIN')") can now evaluate correctly
  → Returns product data

STEP 11: Response flows back
─────────────────────────────────────────
  product-service → order-service (Feign decodes response) → user (HTTP response)

STEP 12: Cleanup (back in FrameworkServlet.processRequest)
─────────────────────────────────────────
  finally {
      RequestContextHolder.resetRequestAttributes();
      // Thread-42's ThreadLocal is now empty
      // Thread-42 returns to Tomcat's pool, ready for next request
  }
```

---

### Visual Flow: Complete Request Chain with ThreadLocal State

```
┌──────────────────────────────── Thread-42 ────────────────────────────────┐
│                                                                            │
│  [Tomcat receives request]                                                 │
│       │                                                                    │
│       ▼                                                                    │
│  FrameworkServlet.processRequest()                                          │
│       │                                                                    │
│       │── RequestContextHolder.setRequestAttributes(req) ─┐                │
│       │                                                    │                │
│       │         ┌─────────────────────────────────┐        │                │
│       │         │     ThreadLocal<RequestAttrs>   │        │                │
│       │         │  ┌───────────────────────────┐  │        │                │
│       │         │  │ HttpServletRequest object  │  │◀───────┘                │
│       │         │  │  .getHeader("Authorization")│ │                        │
│       │         │  │  = "Bearer eyJ..."         │  │                        │
│       │         │  └───────────────────────────┘  │                        │
│       │         └─────────────────────────────────┘                        │
│       ▼                            ▲                                        │
│  DispatcherServlet.doDispatch()    │ reads                                  │
│       │                            │                                        │
│       ▼                            │                                        │
│  OrderController.createOrder()     │                                        │
│       │                            │                                        │
│       ▼                            │                                        │
│  productFeignClient.getProduct()   │                                        │
│       │                            │                                        │
│       ▼                            │                                        │
│  SynchronousMethodHandler          │                                        │
│       │                            │                                        │
│       ├── FeignCorrelationInterceptor.apply()                               │
│       │       reads MDC ThreadContext → adds X-Correlation-Id header        │
│       │                            │                                        │
│       ├── FeignAuthorizationInterceptor.apply() ──────────┘                 │
│       │       reads RequestContextHolder → adds Authorization header        │
│       │                                                                    │
│       ▼                                                                    │
│  [HTTP call to product-service with both headers]                          │
│       │                                                                    │
│       ▼                                                                    │
│  [Response received, decoded, returned to controller]                      │
│       │                                                                    │
│       ▼                                                                    │
│  FrameworkServlet finally block                                             │
│       │── RequestContextHolder.resetRequestAttributes() ──→ ThreadLocal=null│
│       │                                                                    │
│       ▼                                                                    │
│  [Thread-42 returns to Tomcat pool]                                        │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

---

### Where This BREAKS: Non-HTTP Threads

#### Case 1: Kafka Consumer

```
┌──────────────────── KafkaListenerThread-1 ───────────────────────┐
│                                                                    │
│  [Kafka poll() returns message]                                    │
│       │                                                            │
│       ▼                                                            │
│  @KafkaListener OrderEventConsumer.handleOrderCreated()            │
│       │                                                            │
│       │  ThreadLocal<RequestAttrs> = null                          │
│       │  (no HTTP request started this thread!)                    │
│       │                                                            │
│       ▼                                                            │
│  portfolioFeignClient.createHolding()                              │
│       │                                                            │
│       ▼                                                            │
│  FeignAuthorizationInterceptor.apply()                             │
│       │                                                            │
│       │── RequestContextHolder.getRequestAttributes() → null       │
│       │── if(requestAttributes != null) → FALSE                    │
│       │── SKIPS propagation (no crash, but no token sent)          │
│       │                                                            │
│       ▼                                                            │
│  [Feign call to portfolio-service WITHOUT Authorization header]    │
│       │                                                            │
│       ▼                                                            │
│  portfolio-service → 401 Unauthorized                              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘

SOLUTION: For Kafka-triggered Feign calls, use a service-account token:
  - OAuth2 Client Credentials flow (Phase 8 Step 6)
  - Or: the event payload includes a "serviceToken" field generated by the producer
```

#### Case 2: @Async / CompletableFuture

```
┌──────── Thread-42 (original) ─────────┐     ┌──── AsyncThread-7 ────────────┐
│                                         │     │                               │
│  OrderController.createOrder()          │     │                               │
│       │                                 │     │                               │
│       │── CompletableFuture.supplyAsync │────→│  notificationService.send()   │
│       │     (() -> sendNotification())  │     │       │                       │
│       │                                 │     │       ▼                       │
│  ThreadLocal = ServletRequestAttributes │     │  ThreadLocal = null           │
│  (still has original request)           │     │  (child thread, NOT inherited)│
│                                         │     │                               │
└─────────────────────────────────────────┘     └───────────────────────────────┘

WHY: Java's ThreadLocal does NOT propagate to child threads by default.
     InheritableThreadLocal DOES propagate, but Spring uses plain ThreadLocal
     unless threadContextInheritable=true (disabled by default for safety).

SAFETY CONCERN: If Spring used InheritableThreadLocal by default, a long-lived
     child thread could hold a reference to a completed request object (memory leak)
     or access stale request data from a previous user (security vulnerability).
```

#### Case 3: @Scheduled (cron/fixedRate)

```
┌──────── ScheduledThread-1 ────────────────────┐
│                                                 │
│  @Scheduled(fixedRate = 60000)                  │
│  marketDataService.refreshPrices()              │
│       │                                         │
│       │  ThreadLocal = null                     │
│       │  (scheduler thread, never had a request)│
│       │                                         │
│       ▼                                         │
│  alphaVantageFeignClient.getQuote("AAPL")       │
│       │                                         │
│       │  No Authorization needed here           │
│       │  (external API uses API key, not JWT)   │
│       │                                         │
└─────────────────────────────────────────────────┘

NOTE: This case is fine — Alpha Vantage uses an API key in the URL parameter,
not an Authorization header. But if a @Scheduled method tried to call another
internal service via Feign, it would hit the same 401 problem as Kafka.
```

---

### RequestContextHolder vs MDC ThreadContext — Comparison

| Aspect                         | RequestContextHolder                          | MDC / ThreadContext                                                    |
| ------------------------------ | --------------------------------------------- | ---------------------------------------------------------------------- |
| **What it stores**             | Full HttpServletRequest object                | Key-value string pairs (correlationId)                                 |
| **Managed by**                 | FrameworkServlet (set) → Spring MVC lifecycle | MdcCorrelationFilter (set) → your filter code                          |
| **ThreadLocal type**           | NamedThreadLocal (non-inheritable default)    | InheritableThreadLocal (Log4j2 default)                                |
| **Available in child threads** | NO (unless inheritable mode enabled)          | YES (Log4j2 uses InheritableThreadLocal)                               |
| **Available in Kafka thread**  | NO (no HTTP request)                          | YES if Kafka message carries correlationId and filter re-populates MDC |
| **Cleanup**                    | FrameworkServlet finally block (automatic)    | MdcCorrelationFilter finally block (manual)                            |
| **Used by**                    | FeignAuthorizationInterceptor                 | FeignCorrelationInterceptor                                            |

Key insight: **Correlation ID survives across threads** (because MDC uses InheritableThreadLocal), but **Authorization header does NOT** (because RequestContextHolder uses plain ThreadLocal). This is by design — security context should not leak to unrelated threads.

---

### Token Propagation vs Token Exchange

| Aspect              | Token Propagation (our approach)                                | Token Exchange (OAuth2 standard)                                |
| ------------------- | --------------------------------------------------------------- | --------------------------------------------------------------- |
| **Mechanism**       | Copy original token to outgoing request                         | Call IdP to exchange token for new scoped token                 |
| **Network calls**   | 0 extra (just header copy)                                      | 1 extra per hop (to IdP token endpoint)                         |
| **Downstream sees** | Full user identity + ALL roles                                  | Reduced-scope token (e.g., only "read:products")                |
| **Security**        | If product-service is compromised, attacker has full user token | If compromised, attacker has limited-scope token                |
| **Complexity**      | Simple (3 lines of code)                                        | Requires IdP support + token exchange grant type                |
| **When to use**     | Internal trusted network, same trust boundary                   | Cross-organizational, microservices with different trust levels |
| **Standard**        | Ad-hoc pattern (widely used)                                    | RFC 8693 (OAuth 2.0 Token Exchange)                             |

**Our current approach (Propagation)** is correct for Phase 8 Steps 1-4 because all services are in the same trust boundary and owned by the same team. Phase 8 Step 6 (Keycloak) enables Token Exchange as an option for production.

---

### Interview Questions This Pattern Answers

1. **"How do you propagate authentication context between microservices?"**
   → FeignAuthorizationInterceptor reads from RequestContextHolder ThreadLocal, copies to outgoing request.

2. **"What happens to SecurityContext in async threads?"**
   → ThreadLocal does not propagate to child threads. Use SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL) or extract the token before spawning async work.

3. **"How does Feign know about all your interceptors?"**
   → Spring auto-discovers all @Component beans implementing RequestInterceptor and registers them into the Feign builder. No explicit wiring per client.

4. **"What is the difference between token propagation and token exchange?"**
   → Propagation = same token forwarded (simple, less secure). Exchange = new scoped token from IdP (complex, least-privilege). Choose based on trust boundary.

5. **"Can a Kafka consumer propagate user context to downstream services?"**
   → No, because Kafka threads have no RequestContextHolder. Solutions: include token in event payload, use service-account via client-credentials, or implement a custom SecurityContext propagation mechanism.

6. **"Why does Spring use plain ThreadLocal for request attributes instead of InheritableThreadLocal?"**
   → Safety. InheritableThreadLocal would leak request references to long-lived child threads, causing memory leaks (request objects held past their lifecycle) and security vulnerabilities (child thread accessing stale user data from a previous request that reused the parent thread from the pool).

---

## 11. Cross-Cutting Concerns Distribution Pattern — Commons Module as Custom Starter (Phase 8)

### Problem Statement

In a microservice architecture, certain concerns apply to ALL services identically:

- JWT authentication filter
- Correlation ID propagation
- Global exception handling
- Feign interceptors (auth + correlation)
- Kafka consumer configuration

Without a shared mechanism, each service copy-pastes these (~500 lines × 7 services = 3500 duplicated lines). A bug fix requires updating 7 files. A new interceptor requires touching 7 modules.

### The Commons Module Pattern

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                        Commons Module Architecture                               │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  commons/                                                                       │
│  ├── build.gradle                    ← uses 'java-library' plugin (NOT 'application')
│  │                                      api scope → transitive to all consumers │
│  ├── src/main/java/com/equitycart/commons/                                      │
│  │   ├── config/                                                                │
│  │   │   ├── SecurityAutoConfig.java      @Configuration @ConditionalOnProperty │
│  │   │   └── KafkaConsumerConfig.java     @Configuration                        │
│  │   ├── filter/                                                                │
│  │   │   ├── JwtAuthenticationFilter.java @Component (OncePerRequestFilter)     │
│  │   │   └── MdcCorrelationFilter.java    @Component (OncePerRequestFilter)     │
│  │   ├── feign/                                                                 │
│  │   │   ├── FeignCorrelationInterceptor.java  @Component (RequestInterceptor)  │
│  │   │   ├── FeignAuthorizationInterceptor.java @Component (RequestInterceptor) │
│  │   │   └── ProductFeignClient.java      @FeignClient interface                │
│  │   ├── handler/                                                               │
│  │   │   └── GlobalExceptionHandler.java  @RestControllerAdvice                 │
│  │   ├── security/                                                              │
│  │   │   ├── api/JwtTokenValidator.java   interface (abstraction)               │
│  │   │   └── impl/JwtTokenValidatorImpl.java @Component (HMAC-SHA256)           │
│  │   ├── dto/                             ← plain POJOs (no Spring needed)      │
│  │   ├── entity/                          ← @MappedSuperclass (JPA, not Spring) │
│  │   └── exception/                       ← plain exceptions (no Spring needed) │
│  │                                                                              │
│  └── No main class, no @SpringBootApplication (library, not application)        │
│                                                                                 │
└────────────────────────────────────────────────────────────────────────────────┘
```

### Gradle Dependency Scopes in java-library Plugin

```
┌────────────────────────────────────────────────────────────────────────────┐
│   commons/build.gradle — scope determines what consumers inherit           │
├──────────────────┬──────────────────────────────────────────────────────────┤
│ Scope            │ Effect on consumers (e.g., order-service)                │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ api              │ Available at COMPILE + RUNTIME for consumers             │
│                  │ Consumer can directly use classes from this dependency    │
│                  │ Example: api 'spring-boot-starter-security'              │
│                  │ → order-service can import SecurityFilterChain           │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ implementation   │ Available at RUNTIME for consumers (NOT compile time)    │
│                  │ Consumer cannot directly import classes                  │
│                  │ Example: implementation 'spring-kafka'                   │
│                  │ → order-service cannot import KafkaTemplate from here    │
│                  │   (but if order-service declares its own kafka dep, OK)  │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ runtimeOnly      │ Available at RUNTIME for consumers (propagated)          │
│                  │ Example: runtimeOnly 'jjwt-impl:0.12.6'                  │
│                  │ → JAR is in the classpath at runtime; ServiceLoader      │
│                  │   finds it; consumer never imports from it directly      │
├──────────────────┼──────────────────────────────────────────────────────────┤
│ compileOnly      │ NOT available to consumers at all                        │
│                  │ Only for this module's compilation                       │
└──────────────────┴──────────────────────────────────────────────────────────┘

Key insight for JJWT:
  api 'jjwt-api:0.12.6'         → consumers compile against the API interfaces
  runtimeOnly 'jjwt-impl:0.12.6' → implementation loaded at runtime via ServiceLoader
  runtimeOnly 'jjwt-jackson:0.12.6' → JSON serializer loaded at runtime

  This separation ensures consumers code to the API, not the implementation (Clean Architecture).
```

### Two Approaches to Load Commons Beans

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  OPTION A: Explicit @ComponentScan (our choice)                             │
│  ──────────────────────────────────────────────                             │
│                                                                             │
│  @SpringBootApplication                                                     │
│  @ComponentScan(                                                            │
│      basePackages = {"com.equitycart.order", "com.equitycart.commons"},     │
│      excludeFilters = @Filter(type=ANNOTATION, classes=SpringBootApp.class))│
│                                                                             │
│  ✅ Explicit — developer sees what packages are scanned                     │
│  ✅ Proven — portfolio-service already uses this pattern                    │
│  ✅ Debuggable — @ComponentScan in the main class, easy to find             │
│  ❌ Repetitive — each service must add the same annotation                  │
│  ❌ Forgettable — new services might miss it (fails silently)               │
│                                                                             │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  OPTION B: META-INF Auto-Configuration (Spring Boot starter pattern)        │
│  ────────────────────────────────────────────────────────────────           │
│                                                                             │
│  File: commons/src/main/resources/META-INF/spring/                          │
│        org.springframework.boot.autoconfigure.AutoConfiguration.imports     │
│  Content:                                                                   │
│    com.equitycart.commons.config.SecurityAutoConfig                         │
│    com.equitycart.commons.config.KafkaConsumerConfig                        │
│    com.equitycart.commons.handler.GlobalExceptionHandler                    │
│                                                                             │
│  ✅ Zero configuration per service — just add Gradle dependency             │
│  ✅ Official Spring Boot pattern (how starters like spring-data-jpa work)   │
│  ❌ "Magic" — not obvious where configuration comes from                    │
│  ❌ Debugging: must know to check META-INF files                            │
│  ❌ Cannot selectively exclude beans per service without extra conditions    │
│                                                                             │
│  How it works internally:                                                   │
│  1. SpringApplication.run() → AutoConfigurationImportSelector               │
│  2. Reads ALL META-INF/.../AutoConfiguration.imports from classpath JARs    │
│  3. Processes listed classes as @Configuration                              │
│  4. @Conditional annotations still apply (filter based on properties)       │
│                                                                             │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  WHY WE CHOSE OPTION A:                                                     │
│  - Learning project: explicit > implicit (understand what's happening)      │
│  - portfolio-service already established the pattern                        │
│  - Debugging: look at main class, see all scanned packages immediately      │
│  - When something doesn't work: question is "did I add @ComponentScan?"     │
│    not "is META-INF file structured correctly?"                             │
│                                                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

### What Loads vs What Doesn't — Complete Matrix

After @ComponentScan fix, here's what each service gets from commons:

| Commons Bean                  | Mechanism                               | Loads?                | Why/Why Not                                |
| ----------------------------- | --------------------------------------- | --------------------- | ------------------------------------------ |
| SecurityAutoConfig            | @ComponentScan + @ConditionalOnProperty | ✅ (if property=true) | Scanned AND property satisfied             |
| JwtAuthenticationFilter       | @ComponentScan                          | ✅                    | @Component in scanned package              |
| JwtTokenValidatorImpl         | @ComponentScan                          | ✅                    | @Component in scanned package              |
| GlobalExceptionHandler        | @ComponentScan                          | ✅                    | @RestControllerAdvice in scanned package   |
| MdcCorrelationFilter          | @ComponentScan                          | ✅                    | @Component in scanned package              |
| FeignCorrelationInterceptor   | @ComponentScan                          | ✅                    | @Component in scanned package              |
| FeignAuthorizationInterceptor | @ComponentScan                          | ✅                    | @Component in scanned package              |
| KafkaConsumerConfig           | @ComponentScan                          | ✅                    | @Configuration in scanned package          |
| BaseEntity                    | @EntityScan                             | ✅                    | @MappedSuperclass, separate mechanism      |
| ProductDTO, OrderEvent        | None needed                             | ✅                    | Plain POJOs — no Spring scanning required  |
| ProductFeignClient            | @EnableFeignClients                     | ✅ (if declared)      | @FeignClient interface, separate mechanism |

**Three independent discovery mechanisms, each with its own scope:**

1. @ComponentScan → Spring beans (DI container)
2. @EntityScan → JPA entities (Hibernate metamodel)
3. @EnableFeignClients → Feign client interfaces (proxy generation)

### The Silent Failure Problem

The most dangerous aspect of this pattern: when @ComponentScan is missing, services start successfully with NO errors. They just silently degrade:

```
Without @ComponentScan for commons:
  ❌ No JwtAuthenticationFilter → all requests pass without auth (security hole)
  ❌ No GlobalExceptionHandler → raw stack traces in responses (info leak)
  ❌ No MdcCorrelationFilter → correlationId not in MDC (broken distributed tracing)
  ❌ No FeignAuthorizationInterceptor → inter-service calls lack token (401s in chain)
  ❌ No KafkaConsumerConfig → default deserialization (may crash on complex events)

  But service starts! Actuator shows healthy! Eureka shows registered!
  Only functional testing reveals the missing behavior.
```

This is why Phase 8 Step 2 was a critical fix — these beans were NEVER loading in order, product, notification, ledger services throughout all of Phase 7.

### Interview Questions

1. **"How do you share cross-cutting concerns across microservices without coupling them?"**
   → Extract to a commons module with `java-library` plugin + `api` scope. Use @ConditionalOnProperty to gate features (services opt-in). Each service adds @ComponentScan for the commons package. This is effectively a custom Spring Boot starter without the META-INF auto-configuration.

2. **"What is the difference between api and implementation scope in Gradle java-library?"**
   → `api` = transitive to consumers at compile AND runtime (they can import your classes). `implementation` = NOT visible at compile time to consumers (they can't import), but IS on runtime classpath (ServiceLoader, reflection). Use `api` for types that appear in your public API (method signatures, return types). Use `implementation` for internal dependencies.

3. **"Your services were running fine without loading GlobalExceptionHandler — how?"**
   → Spring Boot's default error handling (BasicErrorController) catches unhandled exceptions and returns a generic JSON error. Without GlobalExceptionHandler, services use this default — functional but less informative (no structured error codes, no domain-specific messages). The service doesn't crash; it just provides inferior error responses.

4. **"How would you prevent a commons module from accidentally loading in config-server or discovery-server?"**
   → Two mechanisms: (1) Don't add @ComponentScan for commons in those services. (2) Use @ConditionalOnProperty on configuration classes — set `equitycart.security.enabled=false` (or don't set it at all) in those services' configs. The conditional gate ensures the bean is skipped even if scanned.

---

## Section 12: Service-to-Service Authentication Pattern (ServiceTokenProvider)

### Problem Statement

In a microservices architecture, some inter-service calls originate from non-HTTP contexts:

- **Kafka consumers** processing domain events (order-delivered → reward calculation)
- **@Scheduled tasks** (periodic data sync, cleanup jobs)
- **@Async threads** (fire-and-forget operations)

These threads have no incoming HTTP request, so `RequestContextHolder.getRequestAttributes()` returns null. The standard token propagation pattern (copy Authorization header from incoming request) fails completely.

### Pattern: Machine-Identity Token Generation

```
┌─────────────────────────────────────────────────────────────────────┐
│ HTTP Request Thread                                                   │
│                                                                       │
│  Client → Gateway → Service A → FeignInterceptor                     │
│                                    │                                  │
│                     RequestContextHolder has request?                 │
│                          ├── YES → propagate original token           │
│                          └── NO  → ??? (before: 401/403)             │
├─────────────────────────────────────────────────────────────────────┤
│ Non-HTTP Thread (Kafka, @Scheduled)                                  │
│                                                                       │
│  KafkaListener → Business Logic → FeignClient → FeignInterceptor    │
│                                                    │                  │
│                          RequestContextHolder has request?            │
│                               └── NO → ServiceTokenProvider          │
│                                         generates fresh JWT          │
│                                         (subject=0, role=SERVICE)    │
│                                         attaches as "Bearer <token>" │
└─────────────────────────────────────────────────────────────────────┘
```

### Design Principles

1. **Sentinel Identity:** Use a non-colliding identifier (userId=0) that satisfies existing parsing contracts (`Long.parseLong()`) without requiring schema changes or special-case handling in the validation filter.

2. **Dedicated Role:** The SERVICE role is distinct from user roles (CUSTOMER, SELLER, ADMIN). This allows `@PreAuthorize` rules to distinguish between user-initiated and service-initiated requests when needed.

3. **Short-Lived Tokens:** 60-second expiry limits blast radius. Each Feign call generates fresh — no caching, no revocation needed.

4. **Transparent to Downstream:** The downstream service's `JwtAuthenticationFilter` processes service tokens identically to user tokens — extract subject, extract roles, set SecurityContext. No code branching in the filter.

### Comparison: Token Propagation vs Token Generation vs Client Credentials

| Approach                              | When to Use                         | Pros                                     | Cons                                  |
| ------------------------------------- | ----------------------------------- | ---------------------------------------- | ------------------------------------- |
| **Propagation** (forward user token)  | HTTP thread with incoming request   | Simple, preserves user identity          | Fails in non-HTTP contexts            |
| **Generation** (ServiceTokenProvider) | Non-HTTP threads, symmetric signing | No external deps, fast (~0.1ms)          | Shared secret = any service can forge |
| **Client Credentials** (OAuth2)       | Production with Keycloak/IdP        | Proper identity per service, audit trail | Requires IdP, network call for token  |

### Config Migration Gap Pattern

**Problem discovered:** Properties defined in the monolith's `application.yml` (inside the `app/` module) do NOT automatically migrate to Config Server when you extract services.

**Example:** `equitycart.sell-to-spend.strategy=saga` was set in `app/src/main/resources/application.yml`. After microservice extraction, portfolio-service reads from Config Server's `portfolio-service.yml` — which didn't have the property. The strategy silently defaulted to `transactional` because `@ConditionalOnProperty(matchIfMissing=true)` activated the wrong implementation.

**Prevention checklist:**

1. Grep the monolith's application.yml for every `@Value` and `@ConditionalOnProperty` used by the extracted service
2. Copy those properties to the service's Config Server YAML
3. Push Config Server changes to Git BEFORE testing the extracted service
4. Never use `matchIfMissing=true` with a property that selects between implementations — it creates an invisible default

---

## Section 13: Docker Networking — Host vs Container Port Mapping

### The Mental Model

```
┌─────────── HOST (your laptop) ──────────────┐
│                                               │
│  DBeaver connects to → localhost:9432         │
│  Browser connects to → localhost:8080         │
│                           │                   │
│  ┌────── Docker Network (bridge) ──────────┐ │
│  │                                          │ │
│  │  postgres:5432 ←── order-service:5432    │ │
│  │  redis:6379    ←── portfolio-svc:6379    │ │
│  │  gateway:8080  (exposed to host:8080)    │ │
│  │                                          │ │
│  │  Host:9432 → postgres:5432 (port map)    │ │
│  └──────────────────────────────────────────┘ │
└───────────────────────────────────────────────┘
```

**Key rule:** Containers in the same Docker network communicate using the SERVICE NAME and INTERNAL PORT. The host port mapping (left side of `ports: "9432:5432"`) is ONLY for host-to-container access.

### Common Mistake

```yaml
# docker-pets.yml (infrastructure)
postgres:
  ports:
    - "9432:5432"  # Host:9432 → Container:5432

# docker-compose-services.yml (application services)
order-service:
  environment:
    # WRONG: Using host port inside Docker network
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:9432/orderdb

    # CORRECT: Containers use internal port
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderdb
```

**Why 9432 on host?** The developer's machine already runs PostgreSQL on port 5432 (organizational setup). Mapping to 9432 avoids conflict while leaving the container's internal port unchanged.

### Port Mapping Quick Reference (EquityCart)

| Service       | Host Port | Container Port | Who Uses Host Port  | Who Uses Container Port            |
| ------------- | --------- | -------------- | ------------------- | ---------------------------------- |
| PostgreSQL    | 9432      | 5432           | DBeaver, IDE        | order-svc, user-svc, portfolio-svc |
| Redis         | 6379      | 6379           | RedisInsight        | market-data-svc, portfolio-svc     |
| MongoDB       | 27017     | 27017          | Compass             | market-data-svc                    |
| API Gateway   | 8080      | 8080           | Browser, Postman    | (entry point)                      |
| Config Server | 8888      | 8888           | Browser (debug)     | All services                       |
| Eureka        | 8761      | 8761           | Browser (dashboard) | All services                       |

### Interview Questions

1. **"A service inside Docker can't connect to the database, but you can connect from your laptop. What's wrong?"**
   → Almost always a port mismatch: the service is using the HOST port (mapped side) instead of the container's INTERNAL port. Inside Docker's bridge network, services resolve each other by service name and communicate on internal ports. Host port mappings don't exist inside the network.

2. **"Why would you map PostgreSQL to a non-standard port?"**
   → Port conflict on the developer's machine. The standard port (5432) is occupied by a local/organizational PostgreSQL instance. Docker maps to an alternate host port (9432) to avoid conflict. Inside the container network, PostgreSQL still listens on 5432 — only the host-facing "door" changes.

3. **"Can two containers in the same network use the same internal port?"**
   → Yes. Port isolation is per-container. `postgres:5432` and `mysql:3306` in the same network don't conflict. Even two PostgreSQL instances can both listen on 5432 internally — they're distinguished by service name (DNS), not port. Conflicts only arise if you map both to the same HOST port.

---

## Section 14: Observability Pattern Stack (Phase 9)

Phase 9 introduced an observability architecture composed of four patterns working together:

1. **Correlation Pattern (request identity propagation)**
2. **Metrics Pattern (numerical time-series signals)**
3. **Tracing Pattern (causal span graph)**
4. **Alerting Pattern (policy on top of metrics)**

### 14.1 Correlation + Tracing Are Different Layers

| Concern  | Correlation ID             | Trace/Span                         |
| -------- | -------------------------- | ---------------------------------- |
| Goal     | Group logs for one request | Show distributed timing/call graph |
| Shape    | Single ID per request      | Trace ID + many span IDs           |
| Best for | Log search and debugging   | Latency bottleneck analysis        |
| Storage  | Logs                       | Tracing backend (Zipkin)           |

**Design insight:** Correlation IDs remained valuable even after trace rollout; logs and traces are separate data planes.

### 14.2 Metrics Pattern — Golden Signals Applied

Metrics were instrumented around:

- **Traffic:** request counters
- **Errors:** failure counters and error-rate ratios
- **Latency:** timers/percentiles (p99 focus)
- **Saturation proxies:** service-down and backlog-like conditions

Business-domain counters were added for:

- order placement outcomes
- portfolio trade/reward actions
- notification dispatch outcomes/channels

### 14.3 Alerting Pattern — Three Classes

| Alert Class  | Intent                | Example                  |
| ------------ | --------------------- | ------------------------ |
| Availability | Service unreachable   | `up == 0`                |
| Reliability  | Error ratio unhealthy | 5xx/error-rate threshold |
| Performance  | Tail latency degraded | p99 above SLO threshold  |

### 14.4 Environment-Constrained Logging Pattern

Planned centralized EFK/Fluentd logging was blocked by enterprise egress policy (Zscaler image pull denial from `docker.elastic.co`).

Adopted fallback:

- retain structured JSON logs per service
- aggregate/inspect via local `core-loglens`
- keep EFK as future enhancement when network policy allows

This preserves observability capability while documenting the operational constraint explicitly.

### 14.5 Interview Questions

1. **"If Zipkin already shows traces, why still create custom business metrics?"**  
   → Traces are per-request diagnostics. Metrics are aggregate signals for trend analysis, SLOs, and alerting.

2. **"Why can an alert stay in NoData even when Grafana dashboards show data?"**  
   → Rule queries can return empty vectors depending on label matchers/windowing. Dashboard panels may use different queries or transformations that still render data.

3. **"How do you justify a fallback instead of blocking the release until EFK is available?"**  
   → Observability is about operational visibility, not a single vendor stack. Structured logs + metrics + traces + alerting already satisfy core reliability goals; centralized log backend can be deferred behind a known external constraint.

---

## 15. CQRS (Command Query Responsibility Segregation) Pattern — Read-Write Separation

### 15.1 The Problem: Single Model for Everything

Traditional architectures use one data model for both writes and reads:

```
User places order:
  Controller → Service → ORM generates normalized INSERT/UPDATE (normalized for concurrency)
  
User queries their orders:
  Controller → Service → ORM joins 5 tables (Order, OrderItem, Product, ...) 
            → Maps to DTO → returns response
```

**Problems:**
- Normalized write-model (good for transactions) is bad for queries (expensive JOINs)
- Composite queries require cross-service HTTP calls (distributed JOINs) or separate DB
- Denormalization for reads conflicts with normalization for writes
- Caching becomes complex (invalidate on every write to stay fresh)

### 15.2 The Solution: CQRS — Separate Models

**Command Side (Write Model):**
- PostgreSQL, normalized for transactional consistency
- Business logic lives here (Portfolio, Holding, Reward entities)
- One source of truth for correctness

**Query Side (Read Model):**
- MongoDB, denormalized (one document per user)
- Pre-aggregated, ready-to-return data
- Events trigger rebuilds from write-model

```
Write Path:
  POST /portfolio/buy → TradeServiceImpl → Portfolio.addHolding()
                    → save to PostgreSQL → OutboxWriter writes event
                    ↓
  Debezium CDC reads WAL → publishes PortfolioProjectionEvent to Kafka

Read Path:
  Kafka consumer PortfolioReadModelOutboxConsumer receives event
  ↓
  PortfolioReadModelSynchronizer.rebuildReadModelForUser(userId)
  ↓
  Query PostgreSQL: get all Holdings + Rewards for userId
  ↓
  Upsert to MongoDB: replace user's read-model document
  ↓
  GET /portfolio → queries MongoDB → instant response (no JOINs)
```

### 15.3 Event-Driven Projection — Keeping Read Model in Sync

Each Kafka event triggers a **full rebuild** of the read model:

```
PortfolioProjectionEvent received:
  1. Query PostgreSQL for complete user snapshot (all holdings + rewards)
  2. Compute aggregates (totalValue, totalRewards)
  3. Upsert to MongoDB.userId
  4. Update lastUpdatedAt
```

**Why full rebuild?**
- Simple, safe, guaranteed consistency
- Kafka at-least-once semantics → MongoDB upsert ensures idempotency
- Defers incremental delta optimization until metrics show bottleneck

### 15.4 Idempotency via MongoDB Upsert

Kafka may retry events. MongoDB upsert ensures replayed events are idempotent:

```java
Query query = new Query(Criteria.where("userId").is(userId));
Update update = new Update()
   .set("holdings", holdings)
   .set("rewards", rewards)
   .set("totalValue", computed);

mongoTemplate.upsert(query, update, ReadModelPortfolio.class);
```

- First event: userId not found → INSERT new document
- Retry: userId exists → UPDATE (same result)
- Third retry: same UPDATE again (idempotent)

Unique index on `userId` prevents duplicate user documents.

### 15.5 CDC (Change Data Capture) vs Polling

**Debezium CDC (Primary):**
- Reads PostgreSQL WAL (Write-Ahead Log)
- Publishes events in real-time (sub-second latency)
- No polling queries, minimal DB load
- External infrastructure (Kafka Connect container required)

**OutboxPoller (Fallback):**
- Application polls `outbox_events` table
- Publishes to same Kafka topic
- Higher latency (5-10 second delay)
- No external infrastructure
- Feature flag `@Profile("!cdc")` toggles between modes

### 15.6 Scheduled Reconciliation for Drift Repair

Separate 24-hour job independent of event stream:

```
for each user:
  postgresPortfolio = fetch from write-model
  mongoPortfolio = fetch from read-model
  
  if (postgresPortfolio != mongoPortfolio):
   PortfolioReadModelSynchronizer.rebuildReadModelForUser(userId)
   log.warn("Detected drift for user {}, reconciled", userId)
```

Handles edge cases:
- CDC connector downtime
- Kafka message loss
- Consumer crash without offset commit
- Network partition causing stale read-model

Runs independently so reconciliation failures don't block main event stream.

### 15.7 Eventual Consistency Guarantee

```
       Write Model                              Read Model
       (PostgreSQL)                             (MongoDB)
            │                                        │
   Time T:  │ INSERT Holding                         │
            │ save()                                  │
            │                                        │
   Time T+1:│                     Debezium CDC        │
            │─────────────────────────────────────→  │
            │                                  rebuild│
            │                                        │
   Time T+2:└─────────────────────────────────────→  │ Consistent
                                                upsert│
                                                      │
```

Lag: ~0-2 seconds (depends on CDC/Kafka latency). Read model is eventually consistent but good enough for queries. Writes always go to PostgreSQL (consistent).

### 15.8 Pattern Comparison

| Aspect                    | Single Model | CQRS Event-Driven | CQRS Event Sourcing |
| ------------------------- | ------------ | ----------------- | ------------------- |
| **Write Model**           | Normalized   | Normalized        | Immutable event log |
| **Read Model**            | Same as write | Denormalized      | Derived from events |
| **Query Performance**      | Medium (JOIN) | Fast (pre-computed)| Slow (replay log)   |
| **Consistency**           | Strong       | Eventual (ms lag) | Eventual (ms lag)   |
| **Audit Trail**           | Limited (DML logs) | Full event stream | Full (all changes)  |
| **Complexity**            | Low          | Medium            | High                |
| **When to use**           | Monoliths    | Read-heavy services | Audit-critical     |

**EquityCart Phase 10:** Event-Driven CQRS. Event Sourcing deferred to later phases.

### 15.9 Bounded Eventual Consistency

CQRS is "eventually consistent," but not unbounded:

```
SLA: Read model lag ≤ 5 seconds

Monitoring:
  timestamp_write = write-model entity's updatedAt
  timestamp_read = read-model document's lastUpdatedAt
  lag = timestamp_read - timestamp_write
  
  Alert if lag > 5000ms → trigger reconciliation
```

Reconciliation job ensures convergence. Acceptable for internal APIs (backend-to-backend).

### 15.10 Interview Questions

**Q: "When should you use CQRS vs a single model?" (Phase 10)**  
A: Use single model for systems where read/write patterns are balanced and queries aren't expensive (small entity graphs, few JOINs). Use CQRS when reads are far more frequent than writes, or queries are expensive (big normalized schemas, cross-service queries). EquityCart uses CQRS for portfolio queries because users constantly check holdings (reads) while trading less frequently (writes).

**Q: "Why not just use a denormalized PostgreSQL table instead of MongoDB?" (Phase 10)**  
A: You could. MongoDB was chosen for flexible schema (holdings/rewards array varies per user) and fast single-document queries (no roundtrips). PostgreSQL would work too but requires more schema complexity (arrays as JSON columns). The key is separation of models, not the specific technology — what matters is the pattern, not the database.

**Q: "What if the reconciliation job fails?" (Phase 10)**  
A: Failed reconciliations are logged and retried nightly. If drift persists, it surfaces in alerts (read/write latency anomalies). In production, persistent drift would trigger manual investigation (check CDC logs, Kafka offsets, consumer lag). CQRS trades consistency for availability — eventual consistency is by design; the system doesn't block; it repairs asynchronously.

**Q: "How do you handle schema changes to the read model?" (Phase 10)**  
A: All events are replayed through the projection logic. Add new fields to the projection code (e.g., calculate a new metric). Next event triggers rebuild with the new field. Existing documents lack the new field until they're rebuilt. Backfill job can rebuild all users without waiting for events. Contrast: in Event Sourcing, all historical events must be replayed — much more complex.

---

## 16. Distributed Locking Pattern — High-Concurrency Resource Protection (Topic 3)

**Problem:** How do you protect a shared resource (product inventory, limited-time offer slots, flash-sale tickets) from concurrent over-mutation across multiple service instances without distributed transactions?

**Real-world example:** 10 concurrent users request to buy the last 3 shares of AAPL during a flash sale. Without coordination:
- User 1 sees: inventory=3 → buys 3 → saves
- User 2 sees: inventory=3 (not yet updated) → buys 3 → saves  
- User 3 sees: inventory=3 → buys 3 → saves
- Result: 9 shares sold, but only 3 existed. **OVERSELLING**

**Why not just use `synchronized` or database locks?**

| Locking Strategy        | When It Works                          | When It Fails                                      |
| ----------------------- | -------------------------------------- | -------------------------------------------------- |
| JVM `synchronized`      | Single-instance monolith              | Multiple service instances (doesn't span JVMs)    |
| Pessimistic DB lock     | Low contention                        | Scales badly at high concurrency; deadlock risk   |
| Optimistic DB lock      | Mostly non-conflicting writes          | Retry storms under extreme burst; poor UX         |
| Redis distributed lock  | **Distributed, fast, expires safely**   | External dependency; network partition risk      |
| Consensus (Zookeeper)   | Safety across network partitions       | Overkill for user-facing APIs; high latency      |

**EquityCart Topic 3 Solution: Redis-Based Distributed Lock**

```
Invariant: At most ONE request per productId holds the lock at any time.
Different productIds → different keys → concurrent execution possible.
```

### 16.1 Lock Mechanism: Redis SET NX EX + Lua Compare-and-Delete

**Acquisition (SET NX EX):**

```
REQUEST 1                      REQUEST 2 (same millisecond)
↓                              ↓
SET flash-sale:lock:123        SET flash-sale:lock:123
    "{ownerToken1}"                "{ownerToken2}"
    NX                             NX
    EX 10                          EX 10
    ↓                              ↓
  success                        nil (key exists)
  owns lock                       retry with backoff
```

**Release (Lua Script — prevents stale release race):**

```lua
-- Release script (only owner can delete)
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
else
    return 0  -- not the owner; ignore
end
```

Why Lua? Without it, a race condition exists:

```
Thread A (crashed after checkpoint):
  1. GET flash-sale:lock:123 → returns "{ownerToken1}"
  2. [crash for 5 seconds]
  
Thread B (different owner):
  1. [5 seconds pass]
  2. SET flash-sale:lock:123 "{ownerToken2}" NX EX 10 → success (TTL expired)
  3. Acquires lock, does work, saves
  
Thread A (recovering):
  4. DEL flash-sale:lock:123  ← !!!  DELETES THREAD B'S LOCK !!!
  5. Thread B's lock gone; Thread C now acquires
  → Data corruption (Threads B and C execute concurrently)
```

**Lua fixes this by making (GET + compare + DEL) atomic:**

```
if redis.call('GET', key) == "{ownerToken1}" then DELETE
else IGNORE
```

### 16.2 Dual-Phase Idempotency (Prevents Client Retry Duplicates)

**Phase 1: Fast-Path Check (Before Lock)**

```java
Optional<Order> cachedOrder = orderRepository.findByIdempotencyKey(req.idempotencyKey());
if (cachedOrder.isPresent()) {
    return cached;  // No lock needed; return immediately
}
```

**Phase 2: Race-Safe Check (After Lock)**

```java
boolean acquiredLock = lockManager.tryAcquire(productId, token, 10);
if (!acquiredLock) {
    throw new FlashSaleBusyException();  // Too many concurrent → retry client-side
}

try {
    // Re-check: concurrent request might have passed Phase 1 before we acquired lock
    Optional<Order> raceOrder = orderRepository.findByIdempotencyKey(req.idempotencyKey());
    if (raceOrder.isPresent()) {
        return raceOrder.get();  // Another thread won; return their result
    }
    
    // Safe to proceed: only this thread holds lock + no duplicate exists
    productService.deductStock(productId, quantity);
    Order order = orderRepository.save(new Order(...));
    return order;
} finally {
    lockManager.release(productId, token);
}
```

**Why both checks?**

```
Scenario: Client sends request 3 times due to timeout

Thread A (attempt 1):            Thread B (attempt 2):           Thread C (attempt 3):
  Phase 1: cache miss              Phase 1: ?                      Phase 1: ?
                                    
  Lock contention...               Lock contention...              Lock contention...
  (50ms backoff)                    (50ms backoff)                  (50ms backoff)
  
  Acquire lock → YES                Tries lock → BLOCKED            Tries lock → BLOCKED
  Phase 2: cache miss               ↓                               ↓
  Deduct stock: 100→99              (waiting)                       (waiting)
  Save order → SUCCESS              
  Release lock                       Acquire lock → YES              (still waiting)
  ↓                                  Phase 2: cache HIT!             ↓
  Returns orderId=1                  Return cached orderId=1         Acquire lock → YES
                                     Release lock                    Phase 2: cache HIT!
                                                                     Return cached orderId=1
                                                                     Release lock

RESULT: Only one order created. Client sees same orderId on all 3 attempts. ✓
WITHOUT Phase 2: Both A and B would proceed after lock, creating duplicate orders.
```

### 16.3 Bounded Retry Strategy (Exponential Backoff)

```java
private static final int MAX_ATTEMPTS = 3;
private static final long BASE_BACKOFF_MS = 50;

for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    boolean acquired = redis.setIfAbsent(lockKey, token, 10, TimeUnit.SECONDS);
    
    if (acquired) {
        return true;
    }
    
    if (attempt < MAX_ATTEMPTS) {
        long backoffMs = BASE_BACKOFF_MS * attempt;  // 50ms, 100ms, 150ms
        Thread.sleep(backoffMs);
    }
}

// Max wait: 50 + 100 + 150 = 300ms
throw new FlashSaleBusyException("Retry after 300ms");
```

**Why exponential backoff?**

```
Without backoff (thundering herd):
  ╔════════════════════════════════════════════════════╗
  ║ 100 concurrent requests all spinning, all retry   ║
  ║ simultaneously (microsecond precision)             ║
  ║ → Redis CPU spikes, latency thrashes              ║
  ╚════════════════════════════════════════════════════╝

With exponential backoff (staggered retries):
  Thread 1: locks, works, releases                    (0–50ms)
  Thread 2: sleeps 50ms, then locks, works, releases (50–100ms)
  Thread 3: sleeps 100ms, then locks, works           (100–150ms)
  ...
  → Redis sees smooth sequential lock requests
  → Each succeeds without contention
```

### 16.4 Stock Compensation on Failure

```java
boolean stockDeducted = false;

try {
    productService.deductStock(productId, quantity);
    stockDeducted = true;  // Track for compensation
    
    Order order = new Order(...);
    orderRepository.save(order);  // May fail (constraint, timeout, etc.)
    
    return order;
} catch (DataIntegrityViolationException e) {
    // Save failed but stock was deducted → restore
    if (stockDeducted) {
        productService.restoreStock(productId, quantity);
        log.warn("Compensated stock deduction for productId={}, qty={} due to: {}",
            productId, quantity, e.getMessage());
    }
    throw e;
}
```

**Why needed?**

```
Without compensation:
  1. Stock deducted: 100→97
  2. Order save fails (e.g., FK constraint: user deleted)
  3. Exception propagates to client (500 error)
  4. Client retries, but stock is already gone
  5. Inventory record now out-of-sync with order reality
  → Ghost deduction; stock can never be recovered

With compensation:
  1. Stock deducted: 100→97
  2. Order save fails
  3. Compensation runs: stock restored 97→100
  4. Exception propagates
  5. Client retries; stock still available
  6. Inventory remains consistent
  → No orphaned deductions
```

### 16.5 Cache Invalidation Strategy

```java
@Caching(evict = {
    @CacheEvict(value = "products", allEntries = true),  // Invalidate LIST cache
    @CacheEvict(value = "product", key = "#productId")   // Invalidate individual cache
})
public void deductStock(Long productId, Integer quantity) {
    // ... deduct logic ...
}
```

**Why dual-layer caching?**

```
API Pattern:
  GET /api/products                        → "products" cache (all products list)
  GET /api/products/{id}                   → "product" cache (specific product)
  POST /api/products/123/flash-sale-buy    → deductStock() evicts both

Without allEntries invalidation on "products":
  User calls GET /api/products
  → Cache returns stale list (still shows 100 units for AAPL)
  → User sees inventory that's already sold out
  → Bad UX (shows availability that's false)

With allEntries:
  Deduction invalidates entire "products" cache
  → Next GET /api/products forces fresh query
  → User sees accurate availability
```

### 16.6 Active Window Validation (Config-Driven)

```yaml
# application.yml
equitycart:
  flash-sale:
    enabled: true
    start-time: "2026-08-15T10:00:00Z"    # ISO-8601 Instant
    end-time: "2026-08-15T18:00:00Z"
```

```java
@Value("${equitycart.flash-sale.enabled:true}")
private boolean flashSaleEnabled;

@Value("${equitycart.flash-sale.start-time:}")
private String startTime;

private boolean isFlashSaleActive() {
    if (!flashSaleEnabled) return false;
    
    if (startTime.isBlank() || endTime.isBlank()) {
        return true;  // No bounds = always active
    }
    
    try {
        Instant now = Instant.now();
        Instant start = Instant.parse(startTime);
        Instant end = Instant.parse(endTime);
        
        return now.isAfter(start) && now.isBefore(end);
    } catch (DateTimeParseException e) {
        log.error("Invalid flash sale window. Treating as inactive.", e);
        return false;  // Fail-closed: on config error, disable sale
    }
}
```

**Key design decisions:**

- **Fail-closed on parse errors:** If timestamp format is wrong, sale is disabled (safe choice)
- **Blank times = open window:** Allows one-time sales (manually enabled/disabled via property)
- **Checked before lock:** Rejects upfront if window closed (saves lock acquisition on expired sales)

### 16.7 Lock Acquisition Logging (Observability)

```
DEBUG: Lock acquisition attempt for productId=123, attempt 1/3
DEBUG: Lock acquired for productId=123 on attempt=1
DEBUG: Flash sale active, window closes at 2026-08-15T18:00:00Z
INFO:  Processing order for productId=123, qty=10
DEBUG: Stock deducted: 123 → inventory now 90
INFO:  Order created: orderId=456, userId=789
DEBUG: Lock released for productId=123

----- Failure case -----
WARN:  Lock contention on productId=123, retrying in 50ms (attempt 1/3)
WARN:  Lock contention on productId=123, retrying in 100ms (attempt 2/3)
WARN:  Failed to acquire lock for productId=123 after 3 attempts (300ms max wait)
ERROR: FlashSaleBusyException: Too many concurrent buyers for productId=123
```

### 16.8 Concurrent Scenario Walkthrough (100 Users, 10 Units Available)

```
TIME  USER 1                          USER 2                           USER 3–100
────────────────────────────────────────────────────────────────────────────────────
0ms   Checks: sale active? YES        Checks: sale active? YES         (simultaneous)
      Checks: cache miss              Checks: cache miss               
      Tries lock → SET NX → YES       Tries lock → SET NX → nil        Tries: all get nil
      
5ms   Holds lock, deducts: 10→0       Backoff: sleep 50ms              Backoff: ~50ms
      Saves order
      
20ms  Releases lock                   (still sleeping)                 (still sleeping)

55ms                                  Tries lock → SET NX → YES        Tries: nil
                                      Deducts: 0→-10  FAIL!            (still sleeping)
                                      InsufficientStock exception
                                      
105ms                                 (sleeping)                       Tries lock → nil
                                                                        Backoff: ~100ms

155ms                                                                   Tries lock → nil
                                                                        Give up
                                                                        FlashSaleBusyException
                                                                        
RESULT: 1 user gets order. Others get either 400 (insufficient stock) or 409 (too busy).
        NO OVERSELLING. NO DATA CORRUPTION.
        Max latency: ~300ms for any request.
```

### 16.9 Production Considerations

| Consideration                 | Setting                              | Rationale                                                |
| ----------------------------- | ------------------------------------ | -------------------------------------------------------- |
| Lock TTL                      | 10 seconds                           | Balances: short enough to free quickly, long enough for ops |
| Max retries                   | 3 attempts                           | Covers transient contention; gives up fast under storm |
| Backoff strategy              | 50ms × attempt                       | Staggers retries; prevents herd behavior                 |
| Max total wait                | 300ms (50+100+150)                   | Acceptable for user-facing API, not too long            |
| Cache invalidation granularity | allEntries=true on "products"        | Safe but conservative; can be optimized if contention shows  |
| Window parsing                | Fail-closed on errors                | Security: if config wrong, sale disabled (not live)     |
| Compensation trigger          | Tracks `stockDeducted` flag          | Prevents orphaned deductions on order-save failure      |
| Idempotency key scope         | Client-provided, indexed in DB       | Enables retry safety and duplicate detection            |

### 16.10 When to Use vs When to Avoid

**Use Distributed Locking When:**
- Limited resource (inventory, seats, limited slots)
- High concurrency expected (burst traffic, flash sales)
- Single instance can't handle full workload
- You have operational capacity to monitor Redis

**Avoid (or Simplify) When:**
- Resource is unlimited (open inventory)
- Low concurrency (few concurrent users)
- Database pessimistic locks acceptable (low scale)
- You're not committed to maintaining Redis

**Don't Use For:**
- Message ordering (use Kafka partitions instead)
- Complex state machines (use sagas or event sourcing)
- Mutual exclusion beyond milliseconds (consensus algorithms better)

### 16.11 Interview Questions (Topic 3)

**Q: "Why not use database row-level locks instead of Redis?"**  
A: Database locks are pessimistic — they block other readers. At 100 concurrent requests, the database becomes the bottleneck. Redis locks are optimistic — most requests fail fast (409 Busy) instead of queuing. UX is better ("try again in a moment") than DB timeout (stuck for 30s). Also, Redis is in-process (sub-millisecond), while DB has network round-trip latency.

**Q: "What happens if Redis crashes mid-transaction?"**  
A: The lock expires via TTL (10 seconds). Next request acquires it. No permanent lock leak. Tradeoff: if the service holding the lock crashes, it won't release it, but the 10-second TTL ensures recovery. Compare to Zookeeper where nodes could block indefinitely.

**Q: "How do you test distributed locking locally?"**  
A: Docker Redis for testing. Mock the lockManager interface for unit tests (no actual lock contention). Integration tests use testcontainers + Redis to test retry behavior + backoff. Load tests (k6, Gatling) simulate burst traffic with many concurrent requests.

**Q: "Does idempotency key need to be globally unique?"**  
A: No, just unique per user for a given operation. Example: (userId, operation_type, timestamp) makes a good composite key. Client can use UUID + store on their end for retry. The key prevents *this user* from double-purchasing; it doesn't need to prevent *someone else* from buying.

**Q: "How does this pattern scale to microservices?"**  
A: Redis is already distributed (lives outside any single service). Multiple service instances use the same Redis. Lock key includes productId (not serviceId), so the lock is global across all services. Same 3-attempt retry logic applies. Caveat: Redis must be highly available (use Redis Sentinel or Cluster in production).

## 17. Scheduled Async Evaluation Pattern — Price Alert Watchlist (Topic 4)

### 17.1 Problem & context

A **watchlist alert** watches a market condition (e.g. "AAPL crosses $150") and notifies the user when it happens. Unlike Topic 1 (CDC event-driven) or Topic 3 (synchronous request-driven), there is **no incoming request or upstream event** to react to — the trigger is the passage of time plus a changing external price. This calls for a **pull/polling** design: periodically evaluate every active rule against the latest price.

### 17.2 Shape of the pattern

```
@Scheduled(fixedDelay)  ──▶  load active rules
        │                         │
        │                    for each rule:
        │                         ├─ fetch current price  (reuse MarketDataService, Redis-cached)
        │                         ├─ evaluate condition   (pure function, previous price from the row)
        │                         ├─ write back lastEvaluatedPrice
        │                         └─ if met AND cooldown elapsed:
        │                               ├─ publish NotificationEvent  (reuse NotificationPublisher → Kafka)
        │                               ├─ stamp lastTriggeredAt      (opens cooldown window)
        │                               └─ audit TRIGGERED
        ▼
   cycle repeats after the previous one finishes (fixedDelay, not fixedRate)
```

Key property: the evaluator holds **no in-memory state**. The database row is the single source of truth (active flag, cooldown timestamp, and the previous price for transition detection). This makes the service horizontally trivial and restart-safe.

### 17.3 `fixedDelay` vs `fixedRate` — why it matters

- `fixedRate` schedules the *next start* N ms after the *previous start* — runs can overlap if one is slow, and two overlapping cycles could double-fire an alert.
- `fixedDelay` schedules the next start N ms after the *previous finish* — cycles are serialized, so **no distributed lock is needed** at this scale. This is the correct default for a self-contained evaluator.

`@EnableScheduling` must be present on the app (it already was on portfolio for the vesting task). Delay/initial-delay are externalized: `@Scheduled(fixedDelayString = "${equitycart.alerts.evaluation.fixed-delay-ms:5000}")`.

### 17.4 Reuse-over-reinvent (the central lesson)

The first draft built a **parallel** stack inside portfolio: a `PortfolioPriceService` stub and a `NotificationService` with WebSocket/Email/SMS/InApp handler classes that didn't exist. It duplicated capabilities the platform already had and didn't compile.

The corrected design reuses two existing seams:

| Need | Reused component | Why |
| --- | --- | --- |
| Current price | `MarketDataService.getPrice()` (Redis-cached) | Already the platform's price source; caching means repeated tickers are cheap |
| Delivery | `NotificationPublisher` → `portfolio-notification` Kafka → `NotificationDispatcherImpl` | Notification-service owns channel selection + audit; portfolio only decides *that* to notify |

New code is limited to **domain + orchestration**. This is the difference between a feature that slots into the architecture and one that fights it.

### 17.5 Transition detection without a history store

CROSSING (`prev <= threshold && curr > threshold`) needs the previous price. Instead of joining market-data history each cycle, the alert row carries `lastEvaluatedPrice`, written back on **every** cycle (even non-matches). The stateful part of the computation lives on the row you're already reading and writing — no extra query, no coupling.

### 17.6 Cooldown as a debounce for level predicates

ABOVE/BELOW/BETWEEN are **level** conditions — true continuously while the price stays past the threshold. A naive poller would notify every cycle. Two defenses:

1. **Cooldown:** re-fire only after `lastTriggeredAt + cooldownMinutes`. Met-but-cooling-down → `COOLDOWN_SKIPPED` audit, no notification.
2. **CROSSING:** a transition predicate that fires once on the `≤threshold → >threshold` edge.

General rule: **any polling evaluator over a level predicate needs a debounce**, or it becomes a spam generator.

### 17.7 Extending a shared event contract cheaply

Cross-service change was minimal: add `PRICE_ALERT_TRIGGERED` to the notification-service `NotificationType` enum + one `switch` case in the dispatcher. The shared `NotificationEvent` record's `metadata` map carried alert-specific fields (`alertId`, `condition`, `threshold1`) with **no schema change** — a good example of why a flexible metadata bag on an event contract pays off.

### 17.8 Delivery guarantee & failure mode

`NotificationPublisher.publish()` is **fire-and-forget** (catches + logs publish failures). Consequence: on a broker blip the alert is still stamped `lastTriggeredAt` and audited `TRIGGERED`, but the message may be lost — acceptable for a best-effort watchlist. To make it **at-least-once**, apply the outbox pattern already used elsewhere: persist a notification-outbox row in the same transaction as the trigger, and let a poller publish with retries.

### 17.9 When to use / when to avoid

- **Use** scheduled evaluation when: no natural trigger event exists; a few seconds of latency is acceptable; the rule set fits a periodic scan.
- **Avoid / evolve** when: sub-second latency is required (go event/stream-driven), or the active-rule count is huge (shard by ticker, or index and page the scan). A full-table scan every cycle is fine at small scale and is the honest first design.

### 17.10 Interview Q/A

**Q: "Event-driven vs scheduled — how did you choose for alerts?"**  
A: There's no upstream event that means "the price is now above X"; the condition becomes true silently as the market moves. Polling on a fixed delay is the simplest correct design and bounds load to one pass per interval. I'd move to stream-driven only if latency requirements tightened.

**Q: "How do you prevent overlapping evaluation cycles?"**  
A: `fixedDelay` (not `fixedRate`) — the next cycle starts only after the previous finishes, so a single scheduler thread never overlaps itself. No distributed lock required at this scale.

**Q: "Where does the 'previous price' for CROSSING come from?"**  
A: It's persisted on the alert row (`lastEvaluatedPrice`) and updated every cycle. Keeps the evaluator a single-row read/write instead of a history join.

**Q: "Why publish to Kafka instead of sending notifications directly?"**  
A: Delivery is a separate bounded context. Notification-service already owns channel strategy + audit logging via the `portfolio-notification` topic. Portfolio decides *that* to notify; it publishes an event and stays decoupled from providers.



