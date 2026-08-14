# Phase 10 - Advanced Features and Scale (Master Design Plan)

> This is the full Phase 10 plan across all topics (not only Topic 1).  
> It captures current status, completed work, pending work, sequencing, and execution strategy for next sessions.

---

## 1) Phase 10 Objective

Phase 10 moves EquityCart from "working microservices" to "scale-ready, advanced behavior" by adding:

1. CQRS read scaling patterns
2. advanced distributed workflows (saga-heavy features)
3. high-concurrency controls
4. analytical/reporting capabilities
5. performance validation and tuning

This phase is where architecture choices become production-grade operating patterns.

---

## 2) Phase 10 Topic Tracker (at a glance)

| Topic | Status | Owner | Next Action | Risk |
| --- | --- | --- | --- | --- |
| Topic 1 - CQRS Portfolio Read Model | In Progress (Core done) | You + Assistant | Finish JavaDoc/comments/logging pass in main repo workspace and run compile verification | Medium |
| Topic 2 - Stock Gifting Saga | In Progress (Implementation + compile verified) | You + Assistant | Manual E2E validation (happy path, duplicate key, step-failure compensation, timeout recovery) and docs closeout | High |
| Topic 3 - Flash Sale Stock Drops | Not Started | You + Assistant | Design distributed lock strategy, oversell protection, and burst-load behavior | High |
| Topic 4 - Price Alert Watchlist | Not Started | You + Assistant | Define watchlist model and async evaluation pipeline | Medium |
| Topic 5 - Dividend DRIP | Not Started | You + Assistant | Design batch workflow and reinvestment idempotency rules | High |
| Topic 6 - Tax Report Generation | Not Started | You + Assistant | Define report schema and batch output format (CSV/PDF) | Medium |
| Topic 7 - Portfolio Leaderboard | Not Started | You + Assistant | Define ranking rules and Mongo aggregation plan | Medium |
| Topic 8 - Return Clawback Saga | Completed | You + Assistant | Monitor with manual E2E and timeout/compensation checks | Medium |
| Topic 9 - Load Testing | Not Started | You + Assistant | Build test scenarios and baseline SLA metrics | Medium |
| Topic 10 - Performance Tuning | Not Started | You + Assistant | Tune from load-test evidence (DB, pool, consumer, queries) | Medium |

---

## 3) Phase 10 Topic Map (from roadmap)

1. Topic 1 - CQRS Portfolio Read Model (SQL write + Mongo read projection)
2. Topic 2 - Stock Gifting (atomic portfolio-to-portfolio transfer using saga)
3. Topic 3 - Flash Sale Stock Drops (distributed lock + burst control)
4. Topic 4 - Price Alert Watchlist (async rule evaluation + push delivery)
5. Topic 5 - Dividend DRIP (scheduled reinvestment workflow)
6. Topic 6 - Tax Report Generation (batch CSV/PDF)
7. Topic 7 - Portfolio Leaderboard (Mongo aggregation ranking)
8. Topic 8 - Return Clawback Saga (compensating transaction for returned orders)
9. Topic 9 - Load Testing (k6/Gatling and bottleneck analysis)
10. Topic 10 - Performance Tuning (DB pools, thread pools, query/index tuning)

---

## 4) Current Status Snapshot

## 4.1 Completed

### Topic 1 - CQRS Portfolio Read Model

Implemented and verified (core objective complete):

- Mongo read model layer (`portfolio_read_models`)
- CQRS read controller + DTOs + repository
- feature-flag-based route switching with legacy fallback
- portfolio outbox entity/repo/writer/poller scaffolding
- Debezium connector for portfolio outbox
- Kafka consumer projecting events to Mongo
- write-path outbox coverage for:
  - buy/sell
  - reward grant/vest/cancel
  - refund restoration
  - sell-to-spend + compensation
- duplicate-key risk fixed using upsert-by-`userId`
- compile verification completed

Detailed learning + technical narrative is in:

- `phase-10-learning-deep-dive.md`

### Topic 1 caveat (known and accepted for now)

- projector is correctness-first: rebuild full user snapshot per event  
  (valid now, optimization deferred)

## 4.2 In progress

- Topic 2 stock gifting saga:
  - saga implementation complete (enum/entity/repository/orchestrator/outbox/service/controller)
  - targeted compile passed (`:portfolio:compileJava`)
  - pending: manual E2E validation and final closure checklist

## 4.3 Not started (implementation)

- Topics 3, 4, 5, 6, 7, 9, 10

---

## 5) Why the earlier file looked "reverted"

The previous version became Topic-1-centric because recent implementation and verification were concentrated on CQRS/outbox/debezium.  
This updated file restores full Phase 10 scope while preserving Topic 1 detail.

---

## 6) Sequencing Strategy for Remaining Topics

To reduce risk, Phase 10 should proceed in dependency-aware order:

## Wave A - Consistency-critical financial workflows

1. Topic 8 - Return Clawback Saga ✅ Completed
2. Topic 2 - Stock Gifting Saga ← current
3. Topic 5 - Dividend DRIP

Why first:

- these directly affect holdings, balances, and ledger correctness.

## Wave B - Scale and user-facing advanced features

4. Topic 4 - Price Alert Watchlist
5. Topic 7 - Portfolio Leaderboard
6. Topic 3 - Flash Sale Stock Drops

Why second:

- user engagement + high-traffic control, built on stable transactional core.

## Wave C - Reporting and non-functional hardening

7. Topic 6 - Tax Report Generation
8. Topic 9 - Load Testing
9. Topic 10 - Performance Tuning

Why third:

- performance tuning should be informed by real load results, not guessed early.

---

## 7) Topic-wise Design Intent, Deliverables, and Acceptance

## Topic 2 - Stock Gifting Saga

### Design intent

- transfer shares from giver to receiver atomically at workflow level.
- use orchestrated saga with compensation on downstream failure.

### Core deliverables

- gift request API + idempotency key
- saga state model and orchestration steps
- debit giver holdings -> credit receiver holdings
- compensation path to restore giver on failure
- audit trail + notification events

### Acceptance criteria

- no net share loss/creation under retry/failure
- duplicate requests do not double-transfer
- saga states observable and recoverable

### Detailed Design (Phase 10 Implementation)

#### GiftSagaStatus State Machine
```
INITIATED → DEBITING_GIVER → GIVER_DEBITED → CREDITING_RECEIVER 
  → RECEIVER_CREDITED → RECORDING_LEDGER → LEDGER_RECORDED → COMPLETED

Compensation (on failure):
(any state) → COMPENSATING → COMPENSATED | FAILED

Terminal states: COMPLETED, COMPENSATED, FAILED
```

#### Saga Steps (Orchestration)

**Step 1: Debit Giver Holding**
- Remove shares from giver's portfolio
- Status: INITIATED → GIVER_DEBITED
- Failure: triggers compensation

**Step 2: Credit Receiver Holding**
- Add shares to receiver's portfolio
- Status: GIVER_DEBITED → RECEIVER_CREDITED
- Failure: undo Step 1, then compensate

**Step 3: Record Ledger Entries**
- Create dual ledger entries (debit giver, credit receiver)
- Status: RECEIVER_CREDITED → LEDGER_RECORDED → COMPLETED
- Ledger records after holdings to ensure consistency

#### Compensation Path
Reverses in opposite order:
- If RECORDING_LEDGER/LEDGER_RECORDED: write reversal ledger entry (never delete audit rows)
- If CREDITING_RECEIVER/RECEIVER_CREDITED: reduce receiver holding
- If DEBITING_GIVER/GIVER_DEBITED: restore giver holding
- Final: status = COMPENSATED

#### Idempotency
- idempotencyKey (unique constraint) prevents duplicate requests
- Same key sent twice returns existing saga result
- Checked BEFORE saga execution

#### Timeout Detection
- @Scheduled job polls for sagas exceeding 30-second threshold
- Resumes from last persisted state (intermediate states visible)
- Timeout detector respects @Version optimistic locking

#### Validation (Pre-Saga)
```
1. idempotencyKey uniqueness check
2. giver.userId != receiverId (not self-gift)
3. Holding(giver, ticker).quantity >= requested quantity
4. User(receiverId) exists
5. quantity > 0
```

#### API Contract
```
POST /api/portfolio/gift
{
  "receiverId": 456,
  "tickerSymbol": "AAPL",
  "quantity": "10.500000",
  "idempotencyKey": "gift-2024-08-12-uuid"
}

Response (200):
{
  "sagaId": "uuid",
  "status": "INITIATED|COMPLETED",
  "giver": { "userId": 123, "holdingAfter": {...} },
  "receiver": { "userId": 456, "holdingAfter": {...} },
  "createdAt": "2026-08-12T12:45:54Z"
}
```

#### Database Entities

**GiftSaga**:
- sagaId (UUID, unique) - correlation ID
- userId (Long) - giver
- receiverId (Long) - receiver
- tickerSymbol (String) - stock
- quantity (BigDecimal, scale=6)
- idempotencyKey (String, unique)
- status (GiftSagaStatus enum)
- giftStartedAt, compensationStartedAt (LocalDateTime)
- failureReason (String)
- version (Long) - optimistic locking

**GiftSagaRepository queries**:
- findByIdempotencyKey() - idempotency check
- findActiveGiftsByUserId() - user-centric queries
- findTimedOutGifts(LocalDateTime) - timeout detection
- findByReceiverId() - receiver-side audit

#### Key Design Decisions

1. **NOT @Transactional on orchestrator**
   - Each step commits independently (eventual consistency)
   - Allows timeout detector to resume from intermediate states
   - If crash mid-saga, no automatic rollback (must compensate explicitly)

2. **Separate DEBITING_GIVER and CREDITING_RECEIVER steps**
   - Clear responsibility boundary per step
   - Compensation maps 1:1 to steps
   - Easier to test and debug

3. **Record ledger AFTER holdings update**
   - Holdings = source of truth (actual portfolio state)
   - Ledger = audit trail (can be regenerated)
   - If ledger fails, holdings correct; reconciliation can fix it

4. **Separate idempotencyKey from sagaId**
   - sagaId is internal system ID (correlation across events/logs)
   - idempotencyKey is API contract
   - Decoupled concerns

5. **Orchestration not choreography**
   - Single orchestrator class shows full flow
   - Easier to read, test, and maintain

#### Edge Cases

| Case | Handling |
| --- | --- |
| Giver has 10, requests 15 | Validation rejects (400) |
| Receiver doesn't exist | Validation rejects (400) |
| Self-gift (userId == receiverId) | Validation rejects (400) |
| Duplicate idempotencyKey | Idempotency check returns existing saga |
| Step 2 fails | Exception caught; compensation undoes Step 1 |
| Timeout during RECORDING_LEDGER | Timeout detector resumes, retries step 3 |
| Compensation fails | Saga marked FAILED; requires manual intervention |

#### Logging Strategy

**Entry** (GiftController): `DEBUG: Gift request received { receiverId, ticker, quantity, idempotencyKey }`

**Steps** (GiftSagaOrchestrator):
```
DEBUG: [sagaId={sagaId}] Step 1 starting: debit giver userId={giver}
DEBUG: [sagaId={sagaId}] Step 1 success: holding reduced to {qty}
DEBUG: [sagaId={sagaId}] Step 2 starting: credit receiver userId={receiver}
DEBUG: [sagaId={sagaId}] Step 2 success: holding increased to {qty}
DEBUG: [sagaId={sagaId}] Step 3 starting: record ledger
DEBUG: [sagaId={sagaId}] Step 3 success: ledger recorded
DEBUG: [sagaId={sagaId}] Saga COMPLETED in {ms}ms
```

**Compensation** (on failure):
```
WARN: [sagaId={sagaId}] Exception in step {n}: {exception}
WARN: [sagaId={sagaId}] Starting compensation from status {status}
DEBUG: [sagaId={sagaId}] Undo step {n}: {action}
WARN: [sagaId={sagaId}] Saga COMPENSATED
```

#### E2E Test Scenarios

1. **Happy Path**: Giver 100→90, Receiver +10, ledger dual entries, COMPLETED
2. **Validation Error**: Insufficient holdings → 400, no saga created
3. **Idempotency**: Duplicate request with same key → same sagaId, no double-transfer
4. **Timeout Recovery**: Stuck saga resumed from intermediate state → completes successfully

#### Kafka Events

Events published via SagaOutboxWriter:
- GiftInitiatedEvent
- GiftCompletedEvent  
- GiftFailedEvent

Partition key: `giverId` (ensures user's gifts ordered by partition)

---

## Topic 3 - Flash Sale Stock Drops

### Design intent

- protect inventory and portfolio mutation consistency under burst traffic.

### Core deliverables

- distributed lock strategy (Redis/Redisson with expiration)
- oversell prevention + bounded retries
- cache burst invalidation strategy
- fairness approach (optional queue/windowing)

### Acceptance criteria

- no overselling under parallel load
- p95/p99 remains within defined SLA targets
- lock leak/fencing risk documented and mitigated

---

## Topic 4 - Price Alert Watchlist

### Design intent

- allow users to define alert rules and receive asynchronous notifications.

### Core deliverables

- watchlist CRUD model
- async evaluator (scheduled or stream-triggered)
- dedupe/cooldown logic to avoid alert spam
- push path (websocket/event notification)

### Acceptance criteria

- correct trigger behavior for threshold crossing
- no repeated spam for same steady-state condition
- bounded evaluation latency

---

## Topic 5 - Dividend DRIP

### Design intent

- periodically reinvest dividends into holdings while preserving ledger correctness.

### Core deliverables

- dividend accrual model
- scheduled DRIP batch job (idempotent)
- fractional share handling rules
- ledger + portfolio update linkage

### Acceptance criteria

- deterministic reinvestment calculations
- safe restart/replay of batch without double credit
- full auditability per user run

---

## Topic 6 - Tax Report Generation

### Design intent

- generate compliance-friendly annual/periodic user tax artifacts.

### Core deliverables

- report job inputs (user, year, jurisdiction mode)
- realized/unrealized gain calculation policy
- CSV/PDF output generation pipeline
- storage/download access control

### Acceptance criteria

- reproducible output for same input snapshot
- traceable source transactions per report row
- secure access only for owner/admin

---

## Topic 7 - Portfolio Leaderboard

### Design intent

- derive ranked portfolio insights with efficient read-side aggregations.

### Core deliverables

- ranking dimensions (returns, growth, consistency)
- Mongo aggregation pipelines
- pagination + caching strategy
- anti-gaming guardrails (minimum activity threshold, etc.)

### Acceptance criteria

- query performance under expected dataset
- stable ranking semantics across refresh windows
- no sensitive financial leakage

---

## Topic 8 - Return Clawback Saga

### Design intent

- on order returns/refunds, reverse prior stock-back effects through compensation flow.

### Core deliverables

- clawback eligibility and decision rules
- saga steps for reverse adjustments
- failure compensation path and retries
- linkage to existing reward lifecycle events

### Acceptance criteria

- holdings/rewards converge to expected post-return state
- idempotent under duplicate return events
- clear compensating action logs

---

## Topic 9 - Load Testing

### Design intent

- quantify real bottlenecks before tuning.

### Core deliverables

- scenario scripts (buy/sell/reward/read-heavy/flash-sale)
- baseline SLA targets
- resource telemetry capture during tests
- bottleneck report with ranked findings

### Acceptance criteria

- repeatable test scripts and environments
- clear throughput/latency/error breakdown
- actionable bottleneck ranking

---

## Topic 10 - Performance Tuning

### Design intent

- apply evidence-driven optimizations from Topic 9 findings.

### Core deliverables

- DB index/query plan improvements
- connection pool tuning (Hikari)
- thread pool and consumer concurrency tuning
- cache and serialization optimizations

### Acceptance criteria

- measurable improvement vs baseline
- no functional regression in transactional correctness
- tunings documented with rollback strategy

---

## 8) Cross-Topic Technical Guardrails

Apply to every remaining topic:

1. Idempotency on externally-triggered workflows
2. Explicit failure visibility (no silent catch-and-skip)
3. Correlation IDs across saga/event boundaries
4. Transaction boundary clarity for each step
5. Backward-compatible API evolution
6. Feature flags for risky rollouts

---

## 9) Dependency Notes from Current Architecture

1. Topic 1 produced read-model/event infrastructure that future topics should reuse for portfolio read updates.
2. Existing saga patterns from sell-to-spend can be reused as blueprint for Topics 2 and 8.
3. Observability from Phase 9 should be reused to track new Topic 10 workloads and SLIs.

---

## 10) What to execute next (immediate)

1. Finish Topic 1 doc/logging polish in **main repo workspace**.
2. Start Topic 8 design (Return Clawback Saga) as next high-value correctness feature.
3. Then Topic 2 design (Stock Gifting Saga) using same reliability principles.

---

## 11) Handoff prompt for next chat

Use:

> Continue Phase 10 using `phase-10-design-plan.md` and `phase-10-learning-deep-dive.md`.  
> First verify Topic 1 implementation state and close pending JavaDoc/logging polish on uncommitted Topic 1 files in main repo.  
> Then begin Topic 8 (Return Clawback Saga) design: data model, saga steps, idempotency, failure compensation, and manual E2E validation plan.

---

## 12) Final status summary

- **Phase 10 overall:** In progress (Topic 1 complete; Topics 2-10 pending)
- **Topic 1:** Functionally complete, with final code-quality polish pending in main repo workspace
- **Next best step:** Move to Topic 8 design after Topic 1 polish closure
