# Phase 10 Topic 1 - Portfolio CQRS Deep Dive (Learning + Interview + Handoff)

> This document captures Topic 1 end-to-end: baseline architecture, why the design changed, exact flow, trade-offs, failure handling, and interview-grade reasoning.

---

## 1) Topic 1 Objective

Implement a production-usable CQRS read path for portfolio data so read APIs become:

- fast (precomputed Mongo snapshots),
- scalable (async projection),
- operationally safe (feature flag + fallback),
- consistent enough for real business workflows (eventual consistency with repair guardrails).

In short:

- **Write side:** PostgreSQL (transactional source of truth)
- **Read side:** MongoDB (`portfolio_read_models`)
- **Sync path:** Transactional outbox -> Debezium CDC -> Kafka -> projection consumer

---

## 2) What existed before Topic 1

Portfolio service already had:

1. JPA write model in PostgreSQL:
   - `Portfolio`
   - `Holding`
   - `StockBackReward`

2. Event-sourcing stream in Mongo (`portfolio_events`) via `PortfolioEventStore`.

3. Kafka integration for business workflows (not CQRS projection):
   - Consuming order events (`order-delivered`, `order-returned`, `order-refunded`)
   - Publishing user notification events

What was missing:

- A dedicated denormalized read model for query performance.
- An explicit read projection pipeline with reliability semantics.

---

## 3) First approach and why it was reconsidered

Initial implementation direction used scheduled rebuilding:

- periodic polling (every few seconds),
- event-store scans,
- periodic reconciliation.

### Why this became a concern

1. Repeated scans cause avoidable DB and CPU work.
2. Same users can be rebuilt repeatedly even when no meaningful change happened.
3. Batch limits (for example, `limit(1000)`) can create uncertainty under burst traffic.
4. Harder to reason about deterministic ordering compared to Kafka partition order.

Result: architecture shifted to outbox + CDC projection for cleaner event-driven behavior.

---

## 4) Final design (conceptual)

### 4.1 High-level architecture

```
Client -> Portfolio Write API
          -> Postgres transaction:
               - mutate write model
               - insert outbox event row
          -> Commit

Debezium (CDC) tails WAL
  -> routes outbox payload to Kafka topic (portfolio-readmodel-events)

Portfolio projector consumer
  -> parse projection event
  -> rebuild/upsert Mongo read model by userId

Read API
  -> CQRS Mongo path (feature-flag gated)
  -> fallback to legacy Postgres path when needed
```

### 4.2 Why this pattern is used in real systems

- You avoid dual-write risk (`DB write + Kafka publish` race).
- Outbox row is atomic with business transaction.
- CDC moves publish responsibility out of app request path.
- Async projector decouples read SLA from write transaction cost.

This is a standard enterprise pattern in payments, commerce, ledger-style domains, and high-throughput event-driven microservices.

---

## 5) Concrete implementation surfaces

## 5.1 CQRS read-model package

Implemented under `portfolio/cqrs/...`:

- `model/PortfolioReadModel`, `ReadModelHolding`, `ReadModelRewards`
- `repository/PortfolioReadModelRepository`
- `dtos/PortfolioReadResponse`, `HoldingReadResponse`
- `controller/PortfolioReadController`
- `bootstrap/ReadModelBootstrapper`
- `consumer/PortfolioReadModelOutboxConsumer`
- `synchronizer/PortfolioReadModelSynchronizer`
- `reconciliation/ReadModelReconciliation`

### Purpose of each

- **Model/Repository:** Mongo projection store.
- **Controller:** fast read endpoints for portfolio and analytics.
- **Bootstrapper:** startup backfill for existing users.
- **Consumer:** Kafka to projection trigger.
- **Synchronizer:** canonical rebuild logic per user.
- **Reconciliation:** low-frequency drift repair safety net.

## 5.2 Feature flag route control

- `CQRSFeatureFlag`
- `PortfolioController` routing:
  - if enabled -> try CQRS read controller
  - if CQRS data unavailable -> fallback to legacy facade

This lets rollout be gradual and reversible without API contract breakage.

---

## 6) Outbox + Debezium implementation detail

## 6.1 Portfolio outbox components

Implemented under `portfolio/async/...`:

- `entity/OutboxEvent`
- `enums/OutboxStatus`
- `repository/OutboxEventRepository`
- `event/OutboxPoller` (fallback path for non-CDC profile)
- `dto/PortfolioProjectionEvent`
- `event/PortfolioOutboxWriter`

## 6.2 Event payload design

`PortfolioProjectionEvent` is user-centric and compact:

- `eventId`, `eventType`, `userId`, `tickerSymbol`, quantity/price/value, `occurredAt`, metadata

Design choice:

- key projection by `userId` so all events for a user can be projected in consistent order per partitioning strategy.

## 6.3 Debezium connector (portfolio)

Added connector file:

- `equitycart/docker/debezium/register-portfolio-connector.json`

Core behavior:

- watches `equitycart_portfolio.public.outbox_events`
- unwraps/reroutes using EventRouter pattern
- publishes to logical topic from outbox `topic` field (for projection topic fan-out control)

---

## 7) Write-path coverage (what events are emitted and why)

Projection outbox events wired from real state mutations:

1. `TradeServiceImpl`
   - `SHARES_PURCHASED`
   - `SHARES_SOLD`

2. `PortfolioServiceImpl`
   - `REWARD_GRANTED`

3. `VestingHelperImpl`
   - `REWARD_VESTED`

4. `StockBackRewardConsumer`
   - `REWARD_CANCELLED`
   - `REFUND_RESTORED`

5. `SellToSpendSagaOrchestrator`
   - `SELL_TO_SPEND`
   - `SELL_TO_SPEND_COMPENSATED`

### Why sell-to-spend events matter

Even if the order is paid by stock rather than cash:

- holdings are reduced/restored,
- portfolio snapshot changes,
- read model must reflect that.

Without these events, users can see stale holdings in read APIs.

---

## 8) Critical correctness issue discovered (and fixed)

## 8.1 The risk

If rebuild creates a new Mongo document with null id and writes with `save(...)` repeatedly:

- insert can be attempted again for same `userId`,
- unique index on `userId` may throw duplicate-key errors.

## 8.2 Fix applied

Use `mongoTemplate.upsert(...)` with query on `userId`:

- insert when missing,
- update when existing.

This made projection idempotent for repeated user events at document-key level.

---

## 9) Consistency model and trade-offs

## 9.1 What consistency you get

- Write-side is strongly consistent in PostgreSQL transaction.
- Read-side is eventually consistent through async projection.
- During lag/failure windows, read data can be briefly stale.

## 9.2 Why acceptable

- Read endpoints are optimized for query latency and scale.
- Fallback + reconciliation + replay capability reduce risk.
- This is common in distributed microservices where strict cross-store sync is expensive.

## 9.3 Current projector strategy

- On each event, rebuild full snapshot for one user.

Pros:

- simple, correct, easy to reason/debug

Cons:

- more compute than delta update model

Future optimization:

- event-type-specific delta projection logic.

---

## 10) Profile behavior clarification (`cdc` vs poller)

`@Profile("!cdc")` poller beans are active unless `cdc` profile is enabled.

Key implication:

- if runtime profile is misconfigured, poller might run unexpectedly.

Config update intent:

- ensure shared runtime config activates `cdc` so Debezium path is primary.

---

## 11) Operational failure scenarios and expected behavior

1. Debezium down, app up:
   - outbox rows keep accumulating in DB
   - once Debezium resumes, backlog gets streamed

2. Kafka consumer down:
   - topic retains events
   - consumer catches up after recovery

3. malformed payload:
   - consumer should fail visibly (not silently swallow)
   - replay/remediation procedure should exist

4. projection drift:
   - reconciliation job detects missing/mismatch and rebuilds

---

## 12) Why existing portfolio Kafka was not enough

Pre-existing Kafka usage in portfolio was for business integration with order and notifications.

It did not provide:

- transactional outbox guarantees for portfolio write mutations,
- explicit projection event stream for read-model maintenance.

Topic 1 adds this missing projection pipeline.

---

## 13) Manual E2E verification playbook (project reality)

This repo currently follows manual testing for these flows.

For each scenario:

- buy trade
- sell trade
- reward granted
- reward vested
- reward cancelled
- sell-to-spend
- sell-to-spend compensated
- refund restored

Validate evidence chain:

1. Write-side tables changed in PostgreSQL as expected.
2. Outbox row inserted with correct `eventType`, `topic`, payload.
3. Debezium publishes to `portfolio-readmodel-events`.
4. Consumer receives and triggers rebuild.
5. Mongo `portfolio_read_models` reflects expected snapshot.
6. CQRS endpoint serves updated data.
7. If CQRS temporarily unavailable, legacy fallback still works.

---

## 14) Interview-focused learning (high-value discussion points)

## Q1: Why not publish directly to Kafka inside service transaction?

Because DB and Kafka are different resource managers. Direct publish introduces dual-write race risk. Outbox keeps atomicity at DB boundary.

## Q2: Why Debezium over custom poller?

Debezium reads WAL changes, avoids repeated polling scans, and gives cleaner operational event flow.

## Q3: Why eventual consistency acceptable for read model?

Read model optimizes latency and scale. Domain tolerates small lag windows if write source remains authoritative.

## Q4: Why keep fallback to legacy reads?

Risk-managed rollout. If projection lags or fails, user-facing reads continue via old path.

## Q5: Why keep reconciliation if events already project?

Safety net for missed events, data bugs, one-off repair, and operational resilience.

## Q6: Why key projection by `userId`?

Portfolio snapshot is user-aggregated. User key preserves logical ordering per user and simplifies idempotent upsert strategy.

## Q7: Why is full rebuild per event still acceptable initially?

Correctness first. It reduces complex edge-case bugs while team is stabilizing infrastructure. Optimize later with evidence.

## Q8: What is the hidden danger with Mongo `save(newDoc)`?

It may attempt insert again, causing duplicate-key conflicts for unique business keys.

## Q9: How do you justify outbox events for saga compensation?

Compensation changes business state and must be projected exactly like forward path changes.

## Q10: What is the biggest operational dependency here?

Profile correctness (`cdc`), Debezium connector health, and Kafka consumer liveness.

---

## 15) Anti-patterns avoided

- Blind high-frequency polling as primary sync mechanism.
- Silent fallback that hides projection failures without logs.
- Partial event coverage (missing mutation pathways).
- Assuming build-tool stack incorrectly (Maven vs Gradle).
- Treating old business Kafka topics as CQRS projection stream.

---

## 16) What remains after Topic 1 (hardening backlog)

1. Improve projector from full rebuild to incremental projection.
2. Define DLQ/retry/replay strategy and operational runbook.
3. Add projection lag metrics and alerting.
4. Add automated tests when project introduces testing baseline.
5. Continue JavaDoc/logging polish only on uncommitted Topic 1 files in main repo workspace.

---

## 17) Final outcome

Topic 1 now has a coherent CQRS architecture:

- denormalized Mongo read model,
- transactional outbox emission for portfolio write mutations,
- Debezium CDC relay,
- Kafka-driven projector,
- user-keyed upsert-safe snapshot writes,
- feature-flag-based read routing with fallback.

Net impact:

- better read scalability and latency posture,
- cleaner separation of write/read concerns,
- safer migration path with operational resilience.
