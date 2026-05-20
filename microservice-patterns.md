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

---

## 2. (Placeholder: Saga Pattern — coming in later phases)

---

## 3. (Placeholder: API Gateway Pattern — coming in later phases)

---

## 4. (Placeholder: Circuit Breaker Pattern — coming in later phases)
