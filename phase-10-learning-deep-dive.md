# Phase 10: Interview-Grade Learning & Deep-Dive Q&A

## SECTION 1: CQRS PATTERN (Command Query Responsibility Segregation)

### Q1: What is CQRS and why would anyone use it?

**Background (history)**
In the 1980s–2000s, applications had one unified model: User clicks → Service reads DB → Service writes DB → User sees result. Single model, one database, works fine for simple apps.

But Netflix (1M users), Amazon (10B queries/day), and Stripe (1M events/sec) faced a problem: read patterns and write patterns are COMPLETELY DIFFERENT. A product page gets 1M views/day but stock gets updated 1K times/day. Treating both equally causes waste.

In 2012, Greg Young coined CQRS (Command Query Responsibility Segregation): separate the write path from the read path. Write model stays normalized (ACID, fewer writes, slower). Read model is denormalized (optimized for queries, eventually consistent, fast).

**What CQRS is:**
```
Traditional (Pre-CQRS):
┌─────────────────────────────────────────────┐
│ Unified Database                            │
│  ├─ User                                    │
│  ├─ Product                                 │
│  ├─ Order                                   │
│  └─ Portfolio                               │
│                                             │
│ Same model for:                             │
│  • Writing (INSERT/UPDATE)                  │
│  • Reading (SELECT)                         │
└─────────────────────────────────────────────┘

CQRS (Separated):
┌──────────────────┐        ┌──────────────────┐
│ WRITE MODEL      │ Kafka  │ READ MODEL       │
│ (PostgreSQL)     │───────→│ (MongoDB)        │
│                  │ Event  │                  │
│ Normalized       │ Stream │ Denormalized     │
│ ACID             │        │ Eventual         │
│ Slow writes OK   │        │ Consistency      │
│                  │        │ Fast queries     │
│ User creates     │        │ (no joins)       │
│  order → write   │        │                  │
│                  │        │ Dashboard reads  │
│                  │        │  from read model │
└──────────────────┘        └──────────────────┘
```

**The fundamental insight:**
Writes need one data structure (normalized, ACID, slow is OK).  
Reads need a different structure (denormalized, fast, eventual consistency OK).  
Forcing both to use the same structure is like using a hammer for every job.

### Q2: When do I actually NEED CQRS? What ratio of reads to writes triggers it?

**Answer: When your read/write ratio exceeds 10:1 AND reads are slow**

| Scenario | Ratio | CQRS Needed? | Why |
|----------|-------|------------|-----|
| Comment system (write = post, read = fetch feed) | 1:5 | No | Writes almost as frequent as reads |
| Product catalog (view item = 1000x, update stock = 1x) | 1000:1 | YES | Huge asymmetry, reads dominate |
| Order processing (order created once, queried 10x) | 10:1 | Maybe | Depends on query complexity |
| Portfolio dashboard (user checks 100x/day, trades 1x/day) | 100:1 | YES | Classic CQRS candidate |
| Email inbox (fetch = 50x/day, receive = 10x/day) | 5:1 | No | Overhead not worth it |

**EquityCart specifically:**
Portfolio queries:
- User checks holdings: 10 times/day per user
- 100K users = 1M portfolio queries/day
- But trades happen maybe 10 times/week = 14K trades/week

Ratio = 1M reads / 14K writes = **71:1** → CQRS justified ✅

Query complexity:
- Current: SELECT * FROM holdings JOIN market_price ON ticker = 3 table joins = slow
- CQRS: Single MongoDB document lookup = fast ✅

### Q3: How do I actually build a CQRS system? What are the steps?

**Step 1: Identify write model (existing)**
Already have: Portfolio writes (trade execution, holding updates) → PostgreSQL with JPA

**Step 2: Design read model schema (MongoDB)**
Denormalize for the query use case:
```javascript
// Write model (PostgreSQL — normalized):
holdings table: {holding_id, portfolio_id, ticker, qty, avg_cost, ...}
market_price table: {ticker, current_price, ...}
// Query requires: holdings JOIN market_price JOIN portfolio

// Read model (MongoDB — denormalized):
portfolioReadModel: {
  _id: ObjectId,
  userId: 123,
  username: "alice",
  totalValue: 50000.00,
  totalCost: 45000.00,
  gain: 5000.00,
  gainPercent: 11.11,
  holdings: [
    {
      ticker: "AAPL",
      qty: 10,
      avgCost: 150.00,
      currentPrice: 180.00,
      currentValue: 1800.00,
      gain: 300.00,
      updatedAt: "2026-08-08T10:00:00Z"
    },
    {
      ticker: "MSFT",
      qty: 5,
      avgCost: 300.00,
      currentPrice: 420.00,
      currentValue: 2100.00,
      gain: 600.00,
      updatedAt: "2026-08-08T10:00:00Z"
    }
  ],
  updatedAt: "2026-08-08T10:05:00Z"
}
```

Why denormalized?
- No joins needed (fast read)
- All data in one place (consistent view)
- Can add computed fields (gain, gainPercent)
- Indexes on userId, updatedAt for queries + sorting

**Step 3: Create projection logic (Kafka consumer)**
When trade happens in Portfolio Service:
```
TradeExecuted event → Kafka topic: portfolio-events
  ├─ userId: 123
  ├─ ticker: AAPL
  ├─ qty: +10
  ├─ price: 150.00
  ├─ eventTime: "2026-08-08T10:00:00Z"
  
Projection Consumer:
  ├─ Read TradeExecuted from Kafka
  ├─ Load MongoDB doc for userId 123
  ├─ Update holdings[AAPL].qty += 10
  ├─ Fetch current price from cache (market-data-service)
  ├─ Recalculate: currentValue, gain, gainPercent
  ├─ Set updatedAt = now
  └─ Save back to MongoDB
```

**Step 4: Handle catch-up (idempotent projection)**
Problem: Projection consumer crashes for 1 hour. What happens to the read model?
- Portfolio writes continue (Portfolio Service independent)
- MongoDB becomes stale
- User sees old portfolio

Solution:
```
Consumer restart:
  ├─ Read stored offset from Kafka: "message 1000"
  ├─ Resume from message 1001
  ├─ Replay: messages 1001–1050 (events from the outage hour)
  ├─ For each event: update MongoDB (idempotent = same result if replayed)
  ├─ Catch up complete
  └─ MongoDB now current
```

Idempotency requirement:
- If same event applied twice, result is identical
- Solution: include `eventId` in each event, MongoDB tracks seen events
- Or: version field in MongoDB document (only accept updates > current version)

### Q4: What are the trade-offs of CQRS?

**Pros:**
✅ Reads are **fast** (denormalized, no joins, single lookup)  
✅ **Scalability** (read model and write model scale independently)  
✅ **Flexibility** (each model optimized for its use case)  
✅ **Event sourcing** (CQRS pairs well with event sourcing for audit trail)  

**Cons:**
❌ **Complexity** (manage two models instead of one)  
❌ **Eventual consistency** (user sees portfolio updated after 5 sec delay)  
❌ **Infrastructure** (extra Kafka consumers, extra MongoDB)  
❌ **Projection bugs** (if projection code is wrong, read model is wrong permanently)  

**When NOT to use CQRS:**
- Small team (overhead > benefit)
- Simple queries (no N+1 problem)
- Strong consistency required (financial settlement)
- Low read/write ratio (<5:1)

---

## SECTION 2: DISTRIBUTED LOCKING

### Q5: Why is locking hard in a distributed system?

**Background (the problem)**

Single-server database locking (PostgreSQL SELECT FOR UPDATE):
```sql
BEGIN;
SELECT * FROM products WHERE id = 100 FOR UPDATE; -- locks this row
UPDATE products SET stock = stock - 1 WHERE id = 100; -- atomic
COMMIT; -- lock released
```

Works perfectly if one process. But EquityCart has:
- Product Service (3 instances, ports 8089, 8090, 8091)
- All three running simultaneously
- All three trying to modify same product's stock
- Database lock only prevents conflicts WITHIN one database connection

The problem: Locks are per-connection, not per-application.

**Visual breakdown:**
```
Instance 1:                    Instance 2:                 Instance 3:
  │                              │                           │
  ├─ Flash sale: Buy 40 shares   ├─ Flash sale: Buy 30    ├─ Flash sale: Buy 50
  │                              │                           │
  ├─→ Check stock: 100 ✅       ├─→ Check stock: 100 ✅   ├─→ Check stock: 100 ✅
  │                              │                           │
  ├─→ Deduct: 100-40=60          ├─→ Deduct: 100-30=70    ├─→ Deduct: 100-50=50
  │                              │                           │
  ├─→ Save: 60 ✅               ├─→ Save: 70 ✅           ├─→ Save: 50 ✅
  │                              │                           │
  └─→ Order created              └─→ Order created          └─→ Order created
  
RESULT: 3 orders created, but only 1 should have succeeded!
Overselling by 100 shares! Lost revenue.
```

Database lock doesn't prevent this because each instance has separate DB connection.

### Q6: How do distributed locks work? Why Redis?

**The Redis approach:**

Redis SETNX (SET if Not eXists): atomic, visible across all processes
```
Instance 1:                    Instance 2:                 Instance 3:
  │                              │                           │
  ├─ SETNX("flash-lock", 1)      ├─ SETNX("flash-lock", 1)├─ SETNX("flash-lock", 1)
  │ → Redis returns 1 ✅         │ → Redis returns 0 ❌    │ → Wait, retry
  │ (lock acquired)              │ (lock failed, locked)   │
  │                              │                           │
  ├─→ Check stock: 100           (Blocked, waiting...)    (Blocked, waiting...)
  │                              │                           │
  ├─→ Deduct: 100-40=60          ├─→ After 30ms Instance 1├─→ After 60ms
  │                              │   releases lock         │   Instance 2
  ├─→ Save: 60                   │                           │   releases lock
  │                              ├─ SETNX(...) → 1         │
  ├─→ DELETE("flash-lock")       │ (lock acquired)         ├─ SETNX(...) → 1
  │ (release lock)               │                           │ (lock acquired)
  │                              ├─→ Check stock: 60      │
  └─→ Order created              │   (only 60 left!)       ├─→ Check stock: 30
     Instance 1: 40 shares       │                           │
                                 ├─→ Reject: insufficient  │
                                 │   (no order)            ├─→ Check stock: 30
                                 │                           │
                                 ├─→ DELETE("flash-lock")   ├─→ Deduct: 30-30=0
                                 │ (release lock)          │
                                 └─→ No order             ├─→ Save: 0
                                    Instance 2: 0 shares  │
                                                          ├─→ DELETE("flash-lock")
                                                          │
                                                          └─→ Order created
                                                             Instance 3: 30 shares

RESULT: 2 orders, 70 shares sold (correct!)
```

**Why not plain database locks?**
- Locks are per-connection (not cross-service)
- Would need Postgres to be the "lock server", adds latency
- Doesn't work if database is down

**Why Redis specifically?**
- ✅ In-memory (fast, <1ms)
- ✅ SETNX is atomic (can't partially succeed)
- ✅ Visible across all instances (shared, not local)
- ✅ Can set TTL (prevent dead locks if instance crashes)
- ✅ Already deployed in EquityCart (Phase 3)

### Q7: What's the difference between Redis SETNX and Redisson library?

**Raw Redis (DIY):**
```java
// Try to acquire lock
Boolean lockAcquired = redisTemplate.opsForValue()
  .setIfAbsent("flash-lock", "instance-1", 30, TimeUnit.SECONDS);

if (lockAcquired) {
  try {
    // Do protected work
    inventoryService.deductStock(productId, qty);
  } finally {
    redisTemplate.delete("flash-lock"); // Release
  }
} else {
  // Lock failed, retry
  Thread.sleep(100);
  // retry...
}
```

Problems:
1. Manual retry loops (error-prone)
2. What if clock skew? Instance 1 and Instance 2 both think they own lock
3. What if instance crashes before DELETE? Lock stuck forever
4. No support for reentrant locks (thread acquires lock twice)

**Redisson (industry-standard library):**
```java
RLock lock = redissonClient.getLock("flash-lock");

if (lock.tryLock(10, 30, TimeUnit.SECONDS)) { // wait 10s, TTL 30s
  try {
    // Do protected work
    inventoryService.deductStock(productId, qty);
  } finally {
    lock.unlock(); // Automatic release
  }
} else {
  throw new LockException("Could not acquire lock");
}
```

Redisson handles:
- ✅ Automatic retry with exponential backoff
- ✅ TTL + auto-renewal (prevents zombie locks)
- ✅ Fencing tokens (prevent clock skew bugs)
- ✅ Reentrant locks (same thread can acquire twice)
- ✅ Fair queues (FIFO lock ordering)

### Q8: What happens if the lock gets stuck? How do we prevent deadlocks?

**Scenario: Instance 1 crashes while holding lock**

```
t=0s: Instance 1 acquires lock with TTL=30s
      setIfAbsent("flash-lock", "instance-1", 30s)
      
t=5s: Instance 1 processes: read stock → save stock
      
t=8s: Instance 1 CRASHES 💥 (process dies, does not release lock)
      
t=8s–t=30s: Instance 2 waits for lock
            SETNX fails, retries every 100ms
            Stuck waiting...
            
t=30s: Redis TTL expires, lock auto-released! 🎉
       Instance 2: SETNX("flash-lock", "instance-2", 30s) → OK
       (lock acquired, can proceed)
```

**Why TTL solves this:**
- Without TTL: lock stuck forever (zombie lock), Instance 2 waits forever
- With TTL: lock auto-released after 30 seconds, system recovers

**Gotcha: TTL must be long enough**
- Too short (5s): instance still processing → TTL expires → two instances own lock → race condition
- Too long (5 min): if crash, recovery takes 5 minutes
- Sweet spot: 30–60 seconds (long enough to process, short enough to recover quickly)

---

## SECTION 3: WEBSOCKET FOR REAL-TIME ALERTS

### Q9: Why WebSocket instead of HTTP polling?

**HTTP Polling (traditional, wasteful):**
```
Browser (every 5 seconds):
  POST /api/watchlist/alerts?userId=123
    ↓ (wait for response)
  Server checks: any alerts for user 123? No.
  Server returns: []
    ↓
  Browser: ok, nothing new. Repeat in 5 seconds.
  
Over 1 hour:
- 1 hour = 3600 seconds
- 3600 / 5 = 720 requests
- Per user, 720 requests/hour
- For 1M users: 720M requests/hour
- For 1 day: 17B requests/day
- Infrastructure: handle 200K requests/sec (massive!)
- Latency: most requests return empty, but browser waits 5s for each
- User sees alert 5 seconds late (in worst case)
```

**WebSocket (efficient, real-time):**
```
Browser:
  WS /api/watchlist/alerts?userId=123
    ├─ Connection established
    ├─ Server maintains 1 persistent connection per user
    
When alert triggers:
  Portfolio Service → Kafka → Price Alert Consumer
    ├─ Check: is user 123 subscribed via WebSocket?
    ├─ YES → Send frame to WebSocket connection
    ├─ Browser receives frame instantly (<100ms)
    ├─ User sees alert in real-time
    
Over 1 hour:
  - 1 persistent connection (not 720 requests!)
  - Data sent only when alert triggers
  - For 1M users: 1M WebSocket connections
  - Infrastructure: maintain 1M connections (efficient)
  - Latency: <100ms (instant)
```

**Memory comparison:**
- 1M HTTP polling clients: ~17B requests/day, network saturated
- 1M WebSocket clients: 1M connections × ~1KB per connection = 1GB RAM (efficient)

### Q10: How do I implement WebSocket with Spring?

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│ Portfolio Service                                               │
│                                                                 │
│ ┌─────────────────────────────────────────────────────────┐   │
│ │ WebSocket Endpoint                                      │   │
│ │  @EnableWebSocket                                       │   │
│ │  @PostMapping("/api/watchlist/subscribe")              │   │
│ │   └─→ Stores user 123 connection in registry           │   │
│ │       registry[123] = WebSocket connection             │   │
│ └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│ ┌─────────────────────────────────────────────────────────┐   │
│ │ Market Price Kafka Consumer                             │   │
│ │  @KafkaListener(topics = "market-price-tick")          │   │
│ │  receives: MarketPrice(ticker=AAPL, price=150.00)      │   │
│ │   ├─ Query DB: SELECT * FROM watchlist                 │   │
│ │   │  WHERE ticker=AAPL AND condition=BELOW             │   │
│ │   │  → [user123, user456]                              │   │
│ │   ├─ For each user: registry[user123].sendMessage(...) │   │
│ │   └─→ Browser receives frame instantly                  │   │
│ └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## SECTION 4: SPRING BATCH AT SCALE

### Q11: Why do we need Spring Batch for dividend reinvestment?

**Naive approach (HTTP in a loop):**
```java
@Scheduled(cron = "0 0 9 * * *") // 9:00 AM daily
public void payDividends() {
  List<User> allUsers = userRepository.findAll(); // 50,000 users
  
  for (User user : allUsers) {
    // Calculate dividend
    BigDecimal cashBalance = user.getWallet().getBalance();
    BigDecimal dividend = cashBalance.multiply(new BigDecimal("0.02")); // 2%
    
    // Create order
    Order order = new Order();
    order.setUserId(user.getId());
    order.setAction("BUY");
    order.setPrice(getCurrentMarketPrice()); // REST call
    order.setQty(dividend.divide(order.getPrice()));
    
    orderRepository.save(order);
    
    // Notify
    notificationService.sendAsync(...);
  }
}
```

Problems:
1. **No pagination** → loads 50K users into memory (OOM)
2. **No chunking** → single transaction = 50K writes (slow)
3. **No skip logic** → one error stops entire job
4. **No restart** → if crashes at 25K, restart from 0 (duplicate work)
5. **No monitoring** → can't see progress

Result: Job takes 30 minutes, crushes the database, if it fails halfway through it's a mess.

**Spring Batch approach:**
```java
@Configuration
public class DividendBatchConfig {
  
  @Bean
  public ItemReader<User> userItemReader() {
    return new RepositoryItemReader<>(userRepository)
      .setPageSize(1000)  // Fetch in chunks of 1000
      .setSort(Map.of("id", Sort.Direction.ASC));
  }
  
  @Bean
  public ItemProcessor<User, Order> dividendProcessor() {
    return user -> {
      BigDecimal dividend = user.getWallet().getBalance().multiply(new BigDecimal("0.02"));
      Order order = new Order(..., dividend, ...);
      return order; // Process
    };
  }
  
  @Bean
  public ItemWriter<Order> orderWriter() {
    return orders -> orderRepository.saveAll(orders); // Batch write
  }
  
  @Bean
  public Step dividendStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
    return new StepBuilder("dividendStep", jobRepository)
      .<User, Order>chunk(500)  // Chunk size 500 = 500 writes per transaction
      .reader(userItemReader())
      .processor(dividendProcessor())
      .writer(orderWriter())
      .transactionManager(txManager)
      .build();
  }
  
  @Bean
  public Job dividendJob(JobRepository jobRepository, Step dividendStep) {
    return new JobBuilder("dividendJob", jobRepository)
      .start(dividendStep)
      .build();
  }
}
```

Benefits:
1. ✅ **Pagination** (reads 1000 at a time, respects memory)
2. ✅ **Chunking** (writes in batches of 500, respects DB)
3. ✅ **Skip logic** (@Skippable on processor, logs errors)
4. ✅ **Restart** (JobExecution stored, resumes from checkpoint)
5. ✅ **Monitoring** (Actuator exposes job metrics)

Result: Job finishes in 2 minutes, clean, restartable, observable.

---

## SECTION 5: LOAD TESTING MINDSET

### Q12: How do I know my system can handle production load?

**Approach: Measure, not guess**

1. **Define realistic load**
   - 500 TPS (transactions per second)
   - Mix: 70% reads, 20% writes, 10% complex queries
   - P99 latency target: <1 second

2. **Set up load test**
   - Gatling: scenario-based (user journey simulation)
   - k6: JavaScript DSL (easier to write, less setup)
   
3. **Run baseline test**
   - Inject load gradually (ramp up)
   - Measure: latency, throughput, errors
   - Identify bottleneck (CPU, I/O, network, database)

4. **Fix bottleneck**
   - If CPU high: thread pool too small, or algorithm slow
   - If I/O wait high: connection pool too small, or query slow
   - If database CPU high: index missing, or N+1 problem

5. **Validate improvements**
   - Re-run load test
   - Confirm latency improved
   - Move to next bottleneck

6. **Repeat until targets met**

**EquityCart Phase 10 load test:**
```
Test scenario:
  Users: 1000
  Ramp-up: 100 users/minute (10 minutes to full load)
  Mix:
    - 70% read portfolio (fast)
    - 10% place trade (medium)
    - 10% check watchlist (medium)
    - 10% update alerts (slow)
  Duration: 30 minutes
  
Success criteria:
  ✅ P50 latency < 200ms
  ✅ P99 latency < 1s
  ✅ Error rate < 0.1%
  ✅ Throughput ≥ 500 TPS
```

