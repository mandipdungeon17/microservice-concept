# Phase 10: Advanced Features & Scale — Design & Learning Plan

> **Status**: Pre-Implementation Design Phase
> **Duration**: 4 weeks (Weeks 23–26)
> **Primary Focus**: Production-grade features, performance optimization, and scale validation

---

## 📋 PHASE 10 OVERVIEW

### What we''re building
Phase 10 is the **final capstone** of the EquityCart project. After successfully deploying all core microservices (Phases 1–9), Phase 10 adds:

1. **Advanced query patterns** (CQRS) — separate write and read models
2. **High-performance features** (Flash sales, distributed locking, watchlists)
3. **Scale validation** (Load testing, performance tuning, bottleneck identification)
4. **Complex business workflows** (Stock gifting saga, dividend reinvestment, tax reporting)

### Why it matters

Most applications work fine at small scale. Phase 10 teaches you how to:
- **Scale reads** when thousands of users query portfolio simultaneously
- **Handle extreme writes** during flash sales or scheduled events
- **Lock and coordinate** across distributed systems reliably
- **Measure real performance** under realistic load
- **Identify and fix bottlenecks** (CPU, I/O, network, DB)

### Success criteria
✅ CQRS read model serving portfolio queries in <100ms (p99)  
✅ Flash sale can handle 1000 concurrent buyers without overselling  
✅ Distributed lock prevents double-spend across service boundaries  
✅ Portfolio leaderboard aggregation completes in <5s for 1M users  
✅ Load test validates 500 TPS capacity at 95% CPU utilization  

---

## 🏗️ ARCHITECTURE DESIGN

### Current State (End of Phase 9)
```
User Service (Auth)
    ↓
API Gateway (8080)
    ↓
    ├── Order Service (8088) — OLTP write-heavy
    ├── Product Service (8089) — Static catalog reads
    ├── Portfolio Service (8084) — Complex trades + portfolio queries
    ├── Ledger Service (8086) — Double-entry bookkeeping
    ├── Notification Service (8087) — Async event listener
    ├── Market Data Service (8085) — Real-time prices (Reactive)
    └── Discovery/Config/Monitoring
    
Database Layer:
    ├── PostgreSQL — user, product, order, portfolio, ledger (normalised)
    ├── MongoDB — portfolio events (event sourcing)
    ├── Redis — cache + distributed locks
    └── Kafka — inter-service messaging + Outbox patterns

Observability:
    ├── Prometheus — metrics collection
    ├── Grafana — dashboards + alerts
    ├── Zipkin — distributed tracing
    └── ELK/Core-Loglens — structured logs
```

### Phase 10 Architecture Changes

#### **1. CQRS for Portfolio Queries**

```
WRITE MODEL (Current)                   READ MODEL (New - MongoDB)
┌─────────────────────┐                ┌──────────────────────┐
│ Portfolio Service   │                │ Portfolio Read       │
│  (PostgreSQL)       │                │  Service             │
│                     │                │  (MongoDB)           │
│ Buy → Ledger +      │                │                      │
│  Event Sourcing     │                │ Query: Holdings      │
│                     │   Kafka event  │  in <100ms           │
│ Trade → Holding     │──────────────→ │  (no joins)          │
│  update             │   via Kafka    │                      │
│                     │   consumer     │ Denormalized view:   │
│                     │                │ {                    │
│ Sell → Ledger +     │                │   userId,            │
│  Event Sourcing     │                │   holdings: [        │
│                     │                │     {ticker,         │
│                     │                │      qty,            │
│                     │                │      avgPrice,       │
│                     │                │      currentPrice}   │
│                     │                │   ],                 │
│                     │                │   totalValue,        │
│                     │                │   gain/loss          │
│                     │                │ }                    │
└─────────────────────┘                └──────────────────────┘
```

**Why CQRS here?**
- Portfolio queries are read-heavy (users check holdings constantly)
- Current design joins `Holding + Brand + MarketPrice` = 3 table lookups
- MongoDB denormalized view: single document read + no network calls to market-data
- Trade writes are infrequent compared to reads (100:1 ratio typical)
- Eventual consistency acceptable (users see portfolio updated in <5s)

**Trade-offs explained:**
- ✅ Faster reads (denormalized, indexed, single lookup)
- ✅ Decoupled Portfolio writes from read model rebuilds
- ✅ Can replay MongoDB view if corrupted (reprocess Kafka topic)
- ❌ More infrastructure (separate MongoDB collection + Kafka consumer)
- ❌ Eventual consistency (user sees slightly stale portfolio for 5s after trade)
- ❌ Complexity: projection rebuilds, version tracking, catch-up logic

---

#### **2. Distributed Locking for Flash Sales**

```
Flash Sale Scenario: 1000 users, 100 discounted shares
┌──────────────────────────────────────────────────────┐
│ User 1: BUY 1 @ discount                             │
│   ↓                                                   │
│   Product Service                                    │
│   └─→ Redis SETNX("flash-sale-lock", "user-1", 30s) │
│       ✅ ACQUIRED LOCK                               │
│   └─→ Check stock: 100 available                     │
│   └─→ Deduct: 100 - 1 = 99                          │
│   └─→ DELETE Redis key (release lock)                │
│   └─→ Order created, charge wallet                   │
│                                                      │
│ User 2: BUY 50 @ discount (concurrent)              │
│   ↓                                                   │
│   Product Service                                    │
│   └─→ Redis SETNX(...) — blocks 30s, fails          │
│   └─→ Retry with backoff                            │
│   └─→ Eventually acquires lock                       │
│   └─→ Check stock: 99 available                      │
│   └─→ Deduct: 99 - 50 = 49                          │
│   └─→ DELETE Redis key (release lock)                │
│   └─→ Order created, charge wallet                   │
│                                                      │
│ User 3: BUY 60 @ discount (concurrent)              │
│   ↓                                                   │
│   Product Service                                    │
│   └─→ Waits for lock...                              │
│   └─→ Eventually acquires lock                       │
│   └─→ Check stock: 49 available — NOT ENOUGH        │
│   └─→ Reject: "Only 49 remaining"                    │
│   └─→ DELETE Redis key (release lock)                │
│   └─→ User receives error (no charge)                │
└──────────────────────────────────────────────────────┘

RESULT: Exactly 100 shares sold (no overselling)
```

**Why distributed locking?**
- Multiple instances of Product Service running (horizontal scale)
- Database locks (SELECT FOR UPDATE) work within one DB but not across services
- Redis SETNX atomic, visible across all service instances
- Timeout prevents deadlock if instance crashes mid-transaction

**Technology choice: Redisson vs raw Redis commands**
- **Raw Redis**: `SETNX` + manual retry = 20 lines of error-prone code
- **Redisson**: `RedissonClient.getLock()` + `lock.lock()` = clean, battle-tested
- Redisson handles: retry logic, auto-renewal, reentrancy, deadlock prevention
- Industry standard: used by Netflix, Yahoo, Alibaba for this exact use case

---

#### **3. Price Alert Watchlist (WebSocket + Async Evaluation)**

```
User sets alert: "BUY AAPL if price falls below $150"
┌────────────────────────────────────────────────────────┐
│ Portfolio Service Watchlist                            │
│  └─→ Stores alert in PostgreSQL (userId, ticker,     │
│      condition, price_threshold)                      │
│  └─→ Subscribes to WebSocket connection for user      │
│                                                        │
│ Market Data Service (runs every 5s)                   │
│  └─→ Fetches AAPL price from API: $149.50             │
│  └─→ Publishes MarketPriceTick event to Kafka         │
│                                                        │
│ Portfolio Service Consumer                            │
│  └─→ Receives MarketPriceTick(AAPL, 149.50)          │
│  └─→ Queries: SELECT * FROM watchlist WHERE           │
│      ticker=AAPL AND condition=BELOW                  │
│  └─→ Evaluates: 149.50 < 150? YES                    │
│  └─→ Sends WebSocket frame to subscribed user         │
│  └─→ Push notification: "AAPL fell to $149.50"        │
│                                                        │
│ User sees real-time alert in browser                  │
└────────────────────────────────────────────────────────┘
```

**Why async evaluation?**
- Synchronous: price tick arrives → loop 100K watchlists → 5s latency ❌
- Async: price tick → Kafka consumer evaluates subscribed watchlists → 100ms ✅
- Kafka consumer parallelism: 10 consumer instances × 100ms = 100K/sec throughput

**WebSocket vs HTTP polling:**
- Polling: browser requests every 5s = 17K requests/day per user ❌ (waste)
- WebSocket: persistent connection, push-based = 1 connection per user ✅ (efficient)
- Graceful degradation: if WebSocket unavailable, fall back to REST poll

---

#### **4. Dividend DRIP (Scheduled Batch Reinvestment)**

```
Daily at 9:00 AM UTC:
┌────────────────────────────────────────────────────────┐
│ Spring Batch Job: ProcessDividendDRIP                 │
│                                                        │
│ Step 1: Read                                          │
│   SELECT * FROM portfolio_dividend_drip               │
│   WHERE enabled=true AND last_executed < TODAY        │
│   → 50,000 accounts returned (chunked)                │
│                                                        │
│ Step 2: Process                                       │
│   For each account:                                   │
│   - Calculate: dividend payout = cash_balance * 2%    │
│   - Fetch current market price (via Market Data SVC)  │
│   - Calculate shares to buy = payout / price          │
│   - Create BUY transaction                            │
│                                                        │
│ Step 3: Write                                         │
│   Save transactions in batch to ledger-service        │
│   Update portfolio holdings (batch)                   │
│   Update drip status (last_executed = TODAY)          │
│   Publish DividendReinvested event to Kafka           │
│                                                        │
│ Step 4: Skip                                          │
│   If account has < $10 cash → skip (not worth it)     │
│   If market price unavailable → log error + skip      │
│                                                        │
│ Result: 50,000 reinvestments processed in 2 minutes   │
└────────────────────────────────────────────────────────┘

Why Spring Batch for this?
- 50K accounts too many for one HTTP request
- Need retry/skip logic if market data API unavailable
- Need transaction atomicity per account
- Spring Batch handles chunking, pagination, error recovery
```

---

#### **5. Portfolio Leaderboard (MongoDB Aggregation Pipeline)**

```
Query: Top 100 portfolios by total return (YTD)
┌────────────────────────────────────────────────────────┐
│ Traditional SQL (SLOW)                                │
│                                                        │
│ SELECT u.username, SUM(h.qty * mp.current_price)     │
│   - SUM(h.qty * h.avg_cost)                           │
│   AS return                                           │
│ FROM holdings h                                       │
│ JOIN portfolio p ON h.portfolio_id = p.id             │
│ JOIN market_price mp ON h.ticker = mp.ticker          │
│ JOIN user u ON p.user_id = u.id                       │
│ WHERE h.created_at > DATE_SUB(TODAY, 365 DAYS)        │
│ GROUP BY u.id                                         │
│ ORDER BY return DESC                                  │
│ LIMIT 100                                             │
│                                                        │
│ Execution time: 45 seconds (4 table joins, index miss) │
│                                                        │
│ ────────────────────────────────────────────────────  │
│                                                        │
│ MongoDB Aggregation (FAST)                            │
│                                                        │
│ db.portfolioReadModel.aggregate([                     │
│   {$match: {createdAt: {$gt: oneYearAgo}}},          │
│   {$project: {                                        │
│      userId: 1,                                       │
│      username: 1,                                     │
│      return: {$subtract: [                            │
│        {$sum: "$holdings.currentValue"},              │
│        {$sum: "$holdings.costBasis"}                  │
│      ]}                                               │
│    }},                                                │
│   {$sort: {return: -1}},                             │
│   {$limit: 100}                                       │
│ ]).toArray()                                          │
│                                                        │
│ Execution time: 2 seconds (denormalized, no joins)    │
│                                                        │
└────────────────────────────────────────────────────────┘

Why MongoDB aggregation pipeline?
- Denormalized data (no joins needed)
- Aggregation operations happen on Mongo server (not in app)
- Can use MongoDB indexes on createdAt + return fields
- Scales better: MongoDB can parallelize across shards if needed
```

---

## 📚 LEARNING OBJECTIVES (Interview Prep)

### 1. **CQRS Pattern**
- **What**: Command Query Responsibility Segregation
- **When**: Read/write patterns highly asymmetric (100:1 ratio)
- **How**: Separate write model (PostgreSQL) from read model (MongoDB)
- **Trade-off**: More infrastructure, eventual consistency
- **Real-world**: Amazon uses CQRS for product catalog (1B reads/sec, 1K writes/sec), Stripe for customer dashboards

### 2. **Distributed Locking**
- **What**: Atomic, multi-service coordination
- **When**: Multiple instances, shared resource (inventory, balance)
- **How**: Redis SETNX or Redisson library
- **Gotcha**: Clock skew, network delays, zombie locks (solved by Redisson)
- **Real-world**: Uber uses Redis locks for ride assignment (can't double-book a driver)

### 3. **WebSocket Architecture**
- **What**: Persistent bidirectional connection (not polling)
- **When**: Real-time updates (alerts, live prices, notifications)
- **How**: Spring WebSocket + SockJS for fallback + STOMP message broker
- **Gotcha**: Stateful connections hard to scale (need Redis Pub/Sub for multi-instance)
- **Real-world**: Bloomberg terminals, trading platforms, live dashboards

### 4. **Spring Batch at Scale**
- **What**: Chunked, parallelizable batch processing
- **When**: 10K+ records, need retry/skip logic, atomic per-chunk
- **How**: ItemReader → ItemProcessor → ItemWriter, configurable chunk size
- **Tuning**: Thread pool size, chunk size, fetch size (database pagination)
- **Real-world**: Daily reconciliation jobs (JP Morgan processes $4T/day via batch)

### 5. **Performance Tuning Mindset**
- **Measure first**: Use load testing, profilers, APM tools
- **Identify bottleneck**: CPU, I/O wait, network, database
- **Fix one thing**: Thread pool size, connection pool, batch size, query optimization
- **Validate**: Re-run load test, confirm latency improved
- **Repeat**: Move to next bottleneck

---

## 🎯 PHASE 10 TOPICS (Week-by-Week Breakdown)

### **Week 23: CQRS & Event Projection**
1. Design portfolio read model schema (MongoDB)
2. Create projection service (Kafka consumer → MongoDB)
3. Implement projection rebuild logic (idempotent)
4. Add catch-up logic (resume from checkpoint)
5. Validation: Query latency <100ms with 1M portfolios

### **Week 24: Distributed Locking & Flash Sales**
1. Set up Redisson (Redis client library)
2. Implement distributed lock abstraction
3. Refactor flash sale logic (old: database lock → new: Redis lock)
4. Add lock timeout + TTL configuration
5. Validation: 1000 concurrent buyers, zero overselling

### **Week 25: WebSocket Alerts & Batch Reinvestment**
1. Implement WebSocket endpoint for watchlist alerts
2. Add market price consumer (Kafka → evaluate watches → WebSocket push)
3. Create dividend DRIP batch job (Spring Batch)
4. Configure scheduler (cron: daily 9:00 AM UTC)
5. Validation: Leaderboard query <5s, batch processes 50K/2 min

### **Week 26: Load Testing & Performance Tuning**
1. Set up load test framework (Gatling or k6)
2. Define realistic user scenarios (500 TPS, mixed read/write)
3. Run baseline test (identify bottlenecks)
4. Tune: thread pools, DB connection pools, Kafka consumer counts
5. Verify target SLAs: p99 latency <1s, p95 <500ms

---

## 🛠️ TECHNOLOGIES & PATTERNS

| Topic | Technology | Why This |
|-------|-----------|---------|
| **Read Model** | MongoDB | Denormalized, fast queries, scales horizontally |
| **Projection** | Kafka consumer | Decoupled, can replay, horizontal scale |
| **Distributed Lock** | Redisson | Simple API, handles retry/TTL/deadlock |
| **WebSocket** | Spring WebSocket + STOMP | Standard, Spring integration, SockJS fallback |
| **Batch** | Spring Batch | Chunking, retry, skip, Spring integration |
| **Load Test** | Gatling (Scala DSL) or k6 (JavaScript) | Realistic scenarios, easy reporting |
| **Monitoring** | Prometheus + Grafana (existing) | Already deployed, add Phase 10 metrics |

---

## 📊 SUCCESS METRICS (Testable)

| Metric | Baseline | Target | How to Measure |
|--------|----------|--------|-----------------|
| Portfolio query latency (p99) | 500ms | <100ms | Query response time logs |
| Flash sale overselling | N/A | 0% | Order count vs inventory |
| Watchlist alert latency (mean) | N/A | <100ms | WebSocket frame timestamp |
| Batch DRIP throughput | N/A | 50K/2min | Batch job execution logs |
| Leaderboard query (p50) | 45s | <5s | Query response time logs |
| Load test throughput | N/A | 500 TPS | Gatling/k6 report |
| Error rate under load | N/A | <0.1% | Gatling/k6 report |

---

## ⚠️ RISKS & MITIGATION

| Risk | Impact | Mitigation |
|------|--------|-----------|
| MongoDB projection falls behind (lag >5s) | Users see stale portfolios | Implement lag detection alert; replay logic for catch-up |
| Distributed lock contention (lock wait >1s) | Flash sale users see timeout | Tune lock timeout; add metric for lock wait times |
| WebSocket connection exhaustion (10K users = ?MB) | Service runs out of memory | Set max connection limit; monitor connections; add load balancer websocket support |
| Batch job runs long (>10 min) | Next execution skipped (missing dividend) | Add job restart logic; increase chunk size if bottleneck is I/O |
| Load test doesn't match production (test ≠ real) | Tuning won't help production | Include realistic network latency in test; use production-like data volume |

---

## 🔄 KNOWLEDGE DEPENDENCIES (MUST KNOW)

Before Phase 10, you need solid understanding of:
1. ✅ **Kafka** — topics, consumers, partitions, offset management (Phase 6)
2. ✅ **Event Sourcing** — immutable events, replay, projections (Phase 5–6)
3. ✅ **Distributed transactions** — saga pattern, compensation (Phase 6)
4. ✅ **Spring Batch** — used in Phase 2, extended here for scale
5. ✅ **Redis** — data structures, atomic operations (Phase 3, cached)
6. ✅ **Performance measurement** — load testing, profiling (Phase 9 metrics)

---

## 📖 HISTORICAL CONTEXT

Why do companies need Phase 10?

**Problem (2010s):**
- Monolith grows to 10M users
- Queries slow down (5s → 10s)
- High-volume sales cause inventory overselling (lost revenue)
- Batch jobs finish too late
- No one knows if system can handle Black Friday

**Solution:**
- CQRS separates scaling concerns (read model ≠ write model)
- Distributed locking prevents coordination bugs
- Batch processing handles volume without user-facing slowdown
- Load testing predicts problems before they hit production

**Industry examples:**
- **Netflix**: CQRS for 200M user profiles (reads >> writes)
- **Airbnb**: Distributed locks for inventory management (no double-booking)
- **Stripe**: Batch processing for daily settlement (1M+ transactions)
- **Uber**: Flash sales for surge pricing (demand locks prevent race conditions)

---

## 📝 NEXT STEPS

1. ✅ **Review & Approve** this design plan
2. 📖 **Deep-dive learning** (interview-style Q&A for each topic)
3. 🏗️ **Implementation roadmap** (step-by-step for each topic)
4. 💻 **Code implementation** (CQRS, locks, batch, WebSocket, tests)
5. 🧪 **Load testing & validation** (verify SLAs met)
6. 📊 **Performance tuning** (iterate until targets met)
7. 📚 **Documentation** (update progress.md, learning_log.md, etc.)
