# Apache Kafka — Deep Dive Learning Document

> This document covers Kafka concepts in depth as they relate to the EquityCart project.
> Updated progressively as new Kafka patterns are implemented across Phase 6+.

---

## 1. What Is Kafka?

Apache Kafka is a **distributed event streaming platform**. Think of it as a durable, high-throughput message bus that decouples producers (who create events) from consumers (who react to events).

**Origin:** Built by LinkedIn in 2010 (Jay Kreps, Neha Narkhede, Jun Rao) to solve N×N inter-service communication. With 30+ services, direct connections became unmanageable. Kafka became the single backbone — every service publishes to Kafka, every interested service consumes from Kafka independently. Open-sourced to Apache in 2011. Named after Franz Kafka (the author), because "it's a system optimized for writing" (Kreps's joke).

**Core value proposition:** Kafka is NOT a traditional message queue (like RabbitMQ) — it's a **distributed commit log**. Messages are durable, ordered, and replayable. This makes it suitable for both messaging AND event sourcing.

---

## 2. Core Concepts

### 2.1 Topic

A **named stream of messages**. Analogous to a database table (but append-only — no UPDATE or DELETE).

```
Topic: "order-delivered"
┌────────┬──────────────────────────────────────────────┐
│ Offset │ Message                                      │
├────────┼──────────────────────────────────────────────┤
│ 0      │ {orderId: 1, userId: 5, totalAmount: 999.99} │
│ 1      │ {orderId: 2, userId: 3, totalAmount: 149.50} │
│ 2      │ {orderId: 7, userId: 5, totalAmount: 450.00} │
│ ...    │ ...                                          │
└────────┴──────────────────────────────────────────────┘
```

- Topics are created explicitly or auto-created on first message (configurable)
- Each topic has a **retention period** (default 7 days) — messages are deleted after this, regardless of whether they've been consumed
- Topics are purely logical — physically, they're split into partitions

### 2.2 Partition

Each topic is divided into **partitions** — the unit of parallelism and ordering.

```
Topic: "order-delivered" (3 partitions)

Partition 0: [offset 0][offset 1][offset 2][offset 3] → ...
Partition 1: [offset 0][offset 1][offset 2] → ...
Partition 2: [offset 0][offset 1][offset 2][offset 3][offset 4] → ...
```

**Key properties:**

- Messages within ONE partition are **strictly ordered** (offset 0 before 1 before 2)
- Across partitions, there is **NO ordering guarantee**
- Each partition lives on ONE broker (leader) with copies on other brokers (followers/replicas)
- More partitions = more parallelism (multiple consumers can read in parallel)

**How partition assignment works (message key):**

```java
// Producer sends with key:
kafkaTemplate.send("order-delivered", "42", event);
//                  topic             key   value

// Kafka computes: partition = hash(key) % numPartitions
// hash("42") % 3 = 1  → always goes to partition 1
// hash("43") % 3 = 2  → always goes to partition 2
// hash("42") % 3 = 1  → same key = same partition = ordered!
```

**Why ordering matters for EquityCart:**
All events for order #42 (DELIVERED, then RETURNED) must be processed in order.
Same key (orderId) guarantees same partition → guaranteed ordering.

**If key is null:** Kafka uses sticky round-robin (batches messages to one partition, then rotates). No ordering guarantee — but maximum throughput distribution.

### 2.3 Offset

A sequential number assigned to each message within a partition. Starts at 0, increments by 1 for each new message.

```
Partition 0:
   offset 0       offset 1       offset 2       offset 3
  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ order #1 │  │ order #4 │  │ order #7 │  │ order #10│
  └──────────┘  └──────────┘  └──────────┘  └──────────┘
                                    ▲
                                    │
                            Consumer is HERE
                            (committed offset = 2)
                            (next read = offset 2)
```

**Committed offset** = "I have successfully processed everything up to here."
On restart, consumer resumes from committed offset + 1.

Offsets are stored in a special internal topic: `__consumer_offsets`.

### 2.4 Broker

A Kafka server process. Responsibilities:

- Store partition data on disk (append-only log segments)
- Serve producer writes (append to partition log)
- Serve consumer reads (fetch from partition log at requested offset)
- Replicate partitions to other brokers (fault tolerance)

```
Cluster (3 brokers):
┌─────────────────────────────────┐
│ Broker 1                        │
│ ┌──────────┐ ┌──────────┐      │
│ │ topic-A  │ │ topic-B  │      │
│ │ part 0   │ │ part 1   │      │
│ │ (LEADER) │ │ (FOLLOWER)│     │
│ └──────────┘ └──────────┘      │
└─────────────────────────────────┘
┌─────────────────────────────────┐
│ Broker 2                        │
│ ┌──────────┐ ┌──────────┐      │
│ │ topic-A  │ │ topic-B  │      │
│ │ part 0   │ │ part 1   │      │
│ │ (FOLLOWER)│ │ (LEADER) │     │
│ └──────────┘ └──────────┘      │
└─────────────────────────────────┘
```

- Only the **leader** handles reads/writes for a partition
- **Followers** replicate data (stay in sync) and take over if leader fails
- In dev (our setup): 1 broker, no replication (acceptable for learning)

### 2.5 Producer

An application that publishes messages to topics.

```
┌──────────────┐        ┌─────────────────────────────┐
│  OrderService │       │ Kafka Broker                 │
│              │        │                             │
│ updateStatus()│       │ Topic: "order-delivered"    │
│     │         │  send │ ┌─────┬─────┬─────┐        │
│     ▼         │──────▶│ │ P0  │ P1  │ P2  │        │
│ kafkaTemplate │       │ └─────┴─────┴─────┘        │
│   .send()    │       │                             │
└──────────────┘        └─────────────────────────────┘
```

**Producer guarantees (acks config):**
| Setting    | Behavior                                | Trade-off                     |
| ---------- | --------------------------------------- | ----------------------------- |
| `acks=0`   | Fire and forget (don't wait for broker) | Fastest, may lose messages    |
| `acks=1`   | Wait for leader to acknowledge          | Balanced (default)            |
| `acks=all` | Wait for ALL replicas to acknowledge    | Slowest, strongest durability |

Spring Kafka default: `acks=1` (leader acknowledgement).

### 2.6 Consumer

An application that reads messages from topics.

**Polling model:** Unlike RabbitMQ (broker pushes to consumer), Kafka consumers **pull** — they call `poll()` periodically. This gives consumers control over their read rate (backpressure).

```java
// Simplified internal loop (Spring Kafka does this for you):
while (true) {
    ConsumerRecords<String, OrderDeliveredEvent> records = consumer.poll(Duration.ofMillis(500));
    for (ConsumerRecord record : records) {
        handleOrderDelivered(record.value());  // your business logic
    }
    consumer.commitSync();  // mark these offsets as processed
}
```

Spring Kafka's `@KafkaListener` abstracts this — you just write the handler method.

### 2.7 Consumer Group

Multiple consumer instances that SHARE the work of reading a topic.

```
Topic: "order-delivered" (3 partitions)

Consumer Group: "equitycart-reward-group"
┌────────────┐  ┌────────────┐  ┌────────────┐
│ Consumer A │  │ Consumer B │  │ Consumer C │
│ reads P0   │  │ reads P1   │  │ reads P2   │
└────────────┘  └────────────┘  └────────────┘

Each message is processed by EXACTLY ONE consumer in the group.
```

**Scaling rules:**

- Consumers ≤ Partitions → each consumer gets ≥1 partition
- Consumers > Partitions → extra consumers are idle (standby)
- 1 consumer, 6 partitions → that consumer reads all 6 (overloaded)
- 6 consumers, 6 partitions → perfect 1:1 assignment
- 9 consumers, 6 partitions → 6 active, 3 idle (wasted resources)

**Multiple groups = independent consumption (pub-sub):**

```
Topic: "order-delivered"
     │
     ├──▶ Group "equitycart-reward-group"  → calculates stock-back rewards
     │    (gets ALL messages)
     │
     └──▶ Group "equitycart-notification-group"  → sends email notifications
          (gets ALL messages, independently)
```

### 2.8 Serialization & Deserialization

Kafka stores raw **bytes**. It has no understanding of your Java objects, JSON, or Protobuf. The transformation is:

```
PRODUCER SIDE:
┌─────────────────┐    ┌──────────────┐    ┌───────────────┐
│ Java Object     │───▶│ Serializer   │───▶│ Raw Bytes     │──▶ Kafka
│ OrderDelivered  │    │ (JsonSerializer)  │ [7B 22 6F 72..│
│ Event           │    │              │    │  JSON bytes]  │
└─────────────────┘    └──────────────┘    └───────────────┘

CONSUMER SIDE:
                  ┌───────────────┐    ┌──────────────┐    ┌─────────────────┐
Kafka ──▶         │ Raw Bytes     │───▶│ Deserializer │───▶│ Java Object     │
                  │ [7B 22 6F 72..│    │(JsonDeserial)│    │ OrderDelivered  │
                  │  JSON bytes]  │    │              │    │ Event           │
                  └───────────────┘    └──────────────┘    └─────────────────┘
```

**Type header mechanism:**
When `JsonSerializer` serializes a message, it adds a Kafka header:

```
__TypeId__ = com.equitycart.commons.event.OrderDeliveredEvent
```

The `JsonDeserializer` reads this header to know which Java class to instantiate.
This is why `trusted.packages` is required — security gate against malicious type injection.

---

## 3. ZooKeeper → KRaft Evolution

### 3.1 What Was ZooKeeper?

Apache ZooKeeper (Yahoo, 2006) is a **distributed coordination service** — a small, fast, highly-reliable key-value store for distributed systems to agree on shared state.

**Analogy:** ZooKeeper is a **notary public** for distributed systems. When 5 Kafka brokers need to agree on "who is the leader for partition 3?", they don't vote amongst themselves — they register their claim with ZooKeeper, and ZooKeeper declares the winner.

### 3.2 ZooKeeper's Roles in Kafka

| Role                 | What It Did                                          | Example                                      |
| -------------------- | ---------------------------------------------------- | -------------------------------------------- |
| Controller Election  | Pick ONE broker as cluster controller                | Broker 1 dies → ZK detects → elects Broker 2 |
| Topic Metadata       | Store topic config (partitions, replication)         | "order-delivered" = 3 partitions, RF=2       |
| Partition Leadership | Track leader/follower for each partition             | Partition 0 leader = Broker 2                |
| Broker Liveness      | Detect dead brokers via heartbeat (ephemeral znodes) | Broker 3 missed 3 heartbeats → declared dead |
| ACLs                 | Access control for topics/consumers                  | "App X can produce to topic Y"               |

**Architecture (ZooKeeper era):**

```
┌────────────────────────────────────────────────────────────────┐
│                     KAFKA CLUSTER                               │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐                   │
│  │ Broker 1│    │ Broker 2│    │ Broker 3│                   │
│  │         │    │(Control)│    │         │                   │
│  └────┬────┘    └────┬────┘    └────┬────┘                   │
│       │              │              │                         │
└───────┼──────────────┼──────────────┼─────────────────────────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
              ┌────────┴─────────┐
              │  ZOOKEEPER       │
              │  ENSEMBLE        │  (separate 3-5 node cluster)
              │  ┌───┐┌───┐┌───┐│
              │  │ZK1││ZK2││ZK3││
              │  └───┘└───┘└───┘│
              └──────────────────┘

Problem: TWO distributed systems to deploy, monitor, and troubleshoot.
```

### 3.3 Why ZooKeeper Was Removed

| Problem                  | Impact                                                                                                    |
| ------------------------ | --------------------------------------------------------------------------------------------------------- |
| **Operational overhead** | Two clusters to deploy, configure, monitor, upgrade on different release cycles                           |
| **Partition limit**      | ZK stores ALL metadata in memory → ceiling at ~200K partitions (LinkedIn hit this)                        |
| **Slow failover**        | New controller must reload ALL state from ZK → minutes of unavailability for large clusters               |
| **Split-brain risk**     | If ZK loses quorum, Kafka degrades. ZK's consensus (ZAB) has different semantics than what Kafka needs    |
| **Version coupling**     | ZK bugs surfaced only under Kafka's access patterns. Different release cycles caused compatibility issues |
| **Learning curve**       | Operators needed to understand BOTH systems' failure modes, config tuning, monitoring                     |

### 3.4 KRaft Mode — Kafka's Own Consensus

**KRaft = Kafka + Raft** (consensus algorithm from Stanford, 2014)

Kafka now manages its own metadata using a Raft-based replicated log. No external coordination service.

```
┌────────────────────────────────────────────────────────────────┐
│                     KAFKA CLUSTER (KRaft)                       │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Node 1      │  │  Node 2      │  │  Node 3      │        │
│  │  role: broker │  │  role: both  │  │  role: broker │        │
│  │              │  │  (controller │  │              │        │
│  │              │  │   + broker)  │  │              │        │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘        │
│         │                 │                 │                 │
│         └─────────────────┼─────────────────┘                 │
│                           │                                    │
│              Internal Raft Consensus                            │
│              (metadata in __cluster_metadata topic)             │
│                                                                │
│              NO EXTERNAL SYSTEM NEEDED                          │
└────────────────────────────────────────────────────────────────┘
```

**Key mechanism:** Metadata is stored in a special internal topic `__cluster_metadata`. The active controller writes to it. All other nodes replicate it via Raft. If the controller dies, Raft elects a new one in seconds (not minutes) because the metadata log is already replicated — no "load from external source" needed.

### 3.5 KRaft Advantages — Comparison Table

| Aspect                   | ZooKeeper Mode             | KRaft Mode                        |
| ------------------------ | -------------------------- | --------------------------------- |
| Components to deploy     | 2 clusters (Kafka + ZK)    | 1 cluster                         |
| Partition scalability    | ~200K (ZK memory limit)    | Millions (tested at scale)        |
| Controller failover time | Minutes (rebuild from ZK)  | Seconds (Raft log already local)  |
| Startup sequence         | ZK first, then Kafka       | Single process                    |
| Configuration files      | kafka.properties + zoo.cfg | kafka.properties only             |
| Monitoring               | Kafka JMX + ZK JMX         | Kafka JMX only                    |
| Port usage               | 9092 (client) + 2181 (ZK)  | 9092 (client) + 9093 (controller) |
| Minimum nodes for HA     | 3 ZK + 3 Kafka = 6         | 3 combined nodes                  |

### 3.6 Our Docker Setup — Explained

```bash
docker run -d --name kafka \
  -p 9092:9092 \                                          # Client port
  -e KAFKA_NODE_ID=1 \                                    # Unique node identifier
  -e KAFKA_PROCESS_ROLES=broker,controller \              # Combined mode (dev only)
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \    # Raft voter list
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \  # Two listeners
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \  # How clients find us
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \         # Which listener for Raft
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \                # Pre-generated cluster UUID
  apache/kafka:latest
```

**PROCESS_ROLES=broker,controller** — In production, you'd run separate controller-only and broker-only nodes. For development, one node does both.

**CONTROLLER_QUORUM_VOTERS=1@localhost:9093** — Raft needs to know all voters. Format: `nodeId@host:port`. Multi-node: `1@host1:9093,2@host2:9093,3@host3:9093`

**CLUSTER_ID** — A unique identifier so all nodes know they belong to the same cluster. In ZooKeeper mode, this was stored in ZK. In KRaft, you pre-generate it and pass to all nodes.

**Two listeners:**

- `PLAINTEXT://:9092` — client traffic (your Spring Boot app connects here)
- `CONTROLLER://:9093` — internal Raft protocol (inter-controller communication)

---

## 4. Spring Kafka application.yml — Property Reference

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: equitycart-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties.spring.json.trusted.packages: com.equitycart.commons.event
```

### 4.1 bootstrap-servers

**What:** Initial broker address(es) for cluster discovery.

**Why "bootstrap":** In a 5-broker cluster, your app doesn't need all 5 addresses. It connects to any one, asks "who else is in the cluster?", and gets the full broker list. You list 2-3 for redundancy (if one is down, try another).

```yaml
# Development (single broker):
bootstrap-servers: localhost:9092

# Production (3 brokers, any 2 reachable is enough):
bootstrap-servers: kafka-1:9092,kafka-2:9092,kafka-3:9092
```

### 4.2 producer.key-serializer

**What:** Converts the message KEY from Java String to bytes.

**Why String?** The key determines partition routing: `hash(key) % numPartitions`. We use `orderId.toString()` — ensures all events for the same order go to the same partition → ordered processing.

```java
kafkaTemplate.send("order-delivered", "42", event);
//                                     ^^^ this "42" goes through StringSerializer
```

### 4.3 producer.value-serializer (JsonSerializer)

**What:** Converts the message VALUE (your event DTO) from Java object to JSON bytes.

**What it does internally:**

```java
// Equivalent to what JsonSerializer does:
byte[] bytes = objectMapper.writeValueAsBytes(orderDeliveredEvent);
// Also adds header: __TypeId__ = "com.equitycart.commons.event.OrderDeliveredEvent"
```

**Why not Java's built-in Serializable?** Java serialization is: slow, produces large payloads, tightly coupled to class structure (changing a field breaks existing messages), and has known security vulnerabilities (deserialization attacks). JSON is: human-readable, cross-language, schema-flexible, safe.

### 4.4 consumer.group-id

**What:** Identifies which consumer group this application belongs to.

**Behavior by scenario:**

```
Scenario 1: ONE app instance, group "equitycart-group"
Topic "order-delivered" (3 partitions)
→ This instance reads ALL 3 partitions

Scenario 2: THREE app instances, ALL with group "equitycart-group"
Topic "order-delivered" (3 partitions)
→ Each instance reads 1 partition (load balanced)
→ If instance B dies, its partition reassigned to A or C

Scenario 3: TWO DIFFERENT groups
Group "equitycart-reward-group"  → gets ALL messages (grants rewards)
Group "equitycart-notify-group"  → gets ALL messages (sends emails)
→ Same message processed by BOTH groups independently
```

**This is the Queue vs Pub-Sub duality:**

- Same group-id = Queue (work divided among members)
- Different group-ids = Pub-Sub (each group gets full copy)

### 4.5 consumer.auto-offset-reset

**What:** What to do when a consumer has NO previously committed offset (first time reading a topic, or offset was deleted/expired).

| Value      | Behavior                              | When To Use                                                         |
| ---------- | ------------------------------------- | ------------------------------------------------------------------- |
| `earliest` | Read from beginning (offset 0)        | Can't afford to miss events (rewards, payments, orders)             |
| `latest`   | Read from current end (new msgs only) | Old events are irrelevant (metrics, monitoring, live dashboard)     |
| `none`     | Throw exception                       | Strict production: must have offset, something is wrong if we don't |

**Example:** Consumer restarts after 2 hours of downtime. 50 orders were delivered.

- `earliest` (first run only) / committed offset (subsequent runs): processes all 50 → grants all 50 rewards ✓
- `latest` (first run): skips all 50, only sees future events ✗

**Important nuance:** This setting ONLY matters when there's no committed offset. After your consumer runs once and commits, subsequent restarts resume from the committed offset regardless of this setting.

### 4.6 consumer.key-deserializer / value-deserializer

**What:** Mirrors of the producer serializers — converts bytes back to Java objects.

```
Producer: String "42"         → StringSerializer   → bytes [52, 50]
Consumer: bytes [52, 50]      → StringDeserializer → String "42"

Producer: OrderDeliveredEvent → JsonSerializer     → JSON bytes
Consumer: JSON bytes          → JsonDeserializer   → OrderDeliveredEvent
```

### 4.7 consumer.properties.spring.json.trusted.packages

**What:** Security allowlist for deserialization.

**The attack vector without it:** An attacker who can write to your Kafka topic could set the `__TypeId__` header to a dangerous class (e.g., `java.lang.Runtime`) — triggering arbitrary code execution during deserialization. This is the same class of vulnerability that plagued Java's `ObjectInputStream` (Apache Commons Collections exploit, 2015; WebLogic, Jenkins, JBoss all vulnerable).

**What happens without this config:**

```
org.apache.kafka.common.errors.SerializationException:
The class 'com.equitycart.commons.event.OrderDeliveredEvent'
is not in the trusted packages: [java.util, java.lang]
```

**Options:**

```yaml
# Specific package (recommended for production):
properties.spring.json.trusted.packages: com.equitycart.commons.event

# Multiple packages:
properties.spring.json.trusted.packages: com.equitycart.commons.event,com.equitycart.commons.dto

# Trust everything (development only, NEVER in production):
properties.spring.json.trusted.packages: "*"
```

---

## 5. Message Flow Visualization — EquityCart

### 5.1 Order Delivered → Reward Granted (Happy Path)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│  ① OrderServiceImpl.updateOrderStatus(orderId=42, DELIVERED)                │
│     └─ orderRepository.save(order)    ← PostgreSQL transaction              │
│     └─ orderEventPublisher.publishOrderDelivered(order)                     │
│                                                                              │
│  ② OrderEventPublisher.publishOrderDelivered(order):                        │
│     └─ Builds: OrderDeliveredEvent{orderId:42, userId:1, items:[...]}       │
│     └─ kafkaTemplate.send("order-delivered", "42", event)                   │
│                                                                              │
│  ③ SERIALIZATION (producer side):                                           │
│     └─ Key "42" → StringSerializer → bytes                                  │
│     └─ Event → JsonSerializer → JSON bytes                                  │
│     └─ Header added: __TypeId__ = c.e.commons.event.OrderDeliveredEvent     │
│     └─ Partition = hash("42") % numPartitions → Partition 1                 │
│                                                                              │
│  ④ KAFKA BROKER:                                                            │
│     └─ Receives message → appends to "order-delivered" Partition 1          │
│     └─ Assigns offset (e.g., offset 7)                                      │
│     └─ ACKs producer                                                        │
│                                                                              │
│  ⑤ CONSUMER POLL (runs every ~500ms):                                       │
│     └─ StockBackRewardConsumer.poll() → broker returns message at offset 7  │
│     └─ JsonDeserializer reads __TypeId__ → instantiates OrderDeliveredEvent │
│     └─ @KafkaListener method handleOrderDelivered(event) invoked            │
│                                                                              │
│  ⑥ REWARD CALCULATION:                                                      │
│     └─ event.items[0].productId=1 → Product → Brand (Apple, id=1)          │
│     └─ BrandTickerMapping: brandId=1 → ticker=AAPL, stockBackPct=2.50%     │
│     └─ rewardDollarValue = 999.99 × 2.50 / 100 = $25.00                    │
│     └─ marketDataService.getPrice("AAPL") → $200.00                        │
│     └─ sharesEarned = 25.00 / 200.00 = 0.125000 shares                     │
│     └─ vestingDate = now + 30 days                                          │
│     └─ portfolioService.grantReward(42, 1, "AAPL", 0.125, 25.00, vDate)   │
│                                                                              │
│  ⑦ OFFSET COMMIT:                                                           │
│     └─ Consumer commits: "equitycart-reward-group, P1, offset 8"           │
│     └─ Next poll starts from offset 8                                       │
│                                                                              │
│  ⑧ VESTING (later, @Scheduled job every 60s):                              │
│     └─ Finds: StockBackReward{orderId:42, status:PENDING, vestingDate:past} │
│     └─ VestingHelper: status → VESTED, addOrUpdateHolding(AAPL, 0.125, 0)  │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Failure Scenarios

```
SCENARIO A: Consumer crashes DURING processing (after step ⑤, before ⑦)
┌─────────────────────────────────────────────────────────────┐
│ Offset was NOT committed → Kafka still thinks offset 7      │
│ is unprocessed. On restart, consumer re-reads offset 7      │
│ and processes it again.                                      │
│                                                              │
│ Risk: Duplicate processing → grantReward called twice        │
│ Safety: grantReward is IDEMPOTENT (checks findByOrderId)    │
│ Result: Second call finds existing reward, skips. Safe! ✓   │
└─────────────────────────────────────────────────────────────┘

SCENARIO B: Producer fails to send (Kafka down)
┌─────────────────────────────────────────────────────────────┐
│ kafkaTemplate.send() returns failed CompletableFuture        │
│ Order is saved in PostgreSQL but event is LOST.             │
│                                                              │
│ Risk: Reward never granted for this order                    │
│ Fix: OUTBOX PATTERN (Step 6) — write event to DB first,    │
│      separate poller sends to Kafka. Same transaction as    │
│      order save = atomic.                                    │
└─────────────────────────────────────────────────────────────┘

SCENARIO C: Consumer can't deserialize (corrupt/unknown message)
┌─────────────────────────────────────────────────────────────┐
│ JsonDeserializer throws SerializationException               │
│ Without DLQ: consumer retries forever, blocks partition      │
│                                                              │
│ Fix: DEAD LETTER QUEUE (Step 7) — after 3 retries,         │
│      message sent to "order-delivered.DLT" topic for        │
│      manual inspection. Consumer continues with next msg.   │
└─────────────────────────────────────────────────────────────┘

SCENARIO D: Consumer too slow (processing takes > 5 minutes)
┌─────────────────────────────────────────────────────────────┐
│ max.poll.interval.ms (default 300000 = 5min) exceeded       │
│ Kafka thinks consumer is dead → triggers REBALANCE          │
│ Partition reassigned to another consumer in the group        │
│                                                              │
│ Fix: Ensure processing is fast. If inherently slow,         │
│      increase max.poll.interval.ms or reduce max.poll.records│
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Kafka vs Traditional Message Queues (RabbitMQ, ActiveMQ)

| Feature            | Kafka                                            | RabbitMQ / ActiveMQ                    |
| ------------------ | ------------------------------------------------ | -------------------------------------- |
| Model              | Distributed commit log                           | Message broker (queue/exchange)        |
| Retention          | Messages kept for N days (configurable)          | Messages deleted after acknowledgement |
| Replay             | Yes — rewind offset to re-read old messages      | No — once consumed, gone               |
| Ordering           | Per-partition guarantee                          | Per-queue guarantee                    |
| Throughput         | 100K–1M+ messages/sec per broker                 | 10K–50K messages/sec                   |
| Consumer model     | Pull (consumer calls poll())                     | Push (broker delivers to consumer)     |
| Multiple consumers | Yes — consumer groups (each group gets all msgs) | Requires explicit exchanges/bindings   |
| Use case           | Event streaming, audit logs, high-throughput     | Task queues, RPC, routing logic        |

**Why Kafka for EquityCart?** Order events are high-value business facts — we want them durable, replayable (for rebuilding portfolio state), and consumable by multiple services independently (rewards + notifications + analytics). Kafka's log-based model fits perfectly.

---

## 7. Kafka on Disk — How Storage Works

Kafka doesn't keep messages in memory — it writes to disk sequentially (append-only). This is counter-intuitively **fast** because:

1. **Sequential I/O** — HDDs/SSDs excel at sequential writes (100MB/s+). Random access is slow. Kafka only appends, never updates in-place.
2. **OS Page Cache** — Linux caches recently-written files in RAM. Consumers reading "recent" messages (most common case) read from cache, not disk.
3. **Zero-copy** — Kafka uses `sendfile()` syscall to transfer data from page cache directly to network socket, bypassing user-space copying.

```
Topic: order-delivered, Partition 0, on disk:
/kafka-logs/order-delivered-0/
├── 00000000000000000000.log     ← segment file (messages offset 0-999)
├── 00000000000000000000.index   ← offset-to-position index
├── 00000000000000001000.log     ← next segment (messages offset 1000-1999)
├── 00000000000000001000.index
└── ...
```

Each segment is ~1GB. Old segments are deleted when they exceed retention time (7 days default) or total size limit.

---

## 8. `__TypeId__` Header — Spring Kafka's Type System

Spring Kafka's `JsonSerializer` and `JsonDeserializer` work together using a **type header** embedded in each Kafka message.

### How It Works

```
PRODUCER SIDE (JsonSerializer):
  kafkaTemplate.send("order-delivered", "42", orderDeliveredEvent)
       │
       ▼  JsonSerializer inspects the Java object's class
  Adds header: __TypeId__ = "com.equitycart.commons.event.OrderDeliveredEvent"
       │
       ▼  Serializes the object to JSON bytes (message value)
  [Kafka Message: headers={__TypeId__=...}, value={"orderId":42,...}]

CONSUMER SIDE (JsonDeserializer):
  [Kafka Message received]
       │
       ▼  JsonDeserializer reads __TypeId__ header
  Target class = "com.equitycart.commons.event.OrderDeliveredEvent"
       │
       ▼  objectMapper.readValue(jsonBytes, OrderDeliveredEvent.class)
  Passes typed object to @KafkaListener method parameter
```

### Why This Matters for the Outbox Pattern

If you send a raw `String` through `KafkaTemplate<String, Object>`:
```java
kafkaTemplate.send(topic, key, jsonString);  // jsonString is a java.lang.String
```
The `JsonSerializer` sees a `String` object → sets `__TypeId__: java.lang.String`. The consumer's `JsonDeserializer` reads that header → tries to instantiate a `String` → your `@KafkaListener(OrderDeliveredEvent event)` gets a `ClassCastException`.

**Solution:** The outbox poller must re-hydrate the JSON payload back into the original DTO type before sending. This way `JsonSerializer` sees the real class and sets the correct `__TypeId__` header:
```java
Class<?> clazz = Class.forName(outboxEvent.getPayloadType());       // FQCN from DB
Object event = objectMapper.readValue(outboxEvent.getPayload(), clazz); // re-hydrate
kafkaTemplate.send(topic, key, event);  // JsonSerializer → correct __TypeId__
```

### Security: `trusted.packages`

The `JsonDeserializer` uses `__TypeId__` to call `Class.forName()` internally — this is a potential remote code execution vector. An attacker who can write to your Kafka topic could set `__TypeId__` to a gadget class (like the 2015 Apache Commons Collections exploit). The `trusted.packages` config restricts which packages are allowed:

```yaml
properties.spring.json.trusted.packages: com.equitycart.commons.event
```

Only classes in `com.equitycart.commons.event` can be instantiated. Any other `__TypeId__` value is rejected with `IllegalArgumentException`.

---

## 9. Dead Letter Queue (DLQ / DLT)

### The Problem: Poison Messages

A "poison message" is one that will fail on every retry — malformed JSON, missing required fields, referencing a deleted entity. Without a DLQ, it blocks the consumer at that offset forever (infinite retry loop), halting ALL processing for that partition.

### Spring Kafka's DLQ Implementation

```
Message → @KafkaListener → Exception thrown
    → DefaultErrorHandler catches it
    → Retry 1 (after 1s backoff) → still fails
    → Retry 2 → still fails
    → Retry 3 → still fails (max retries exhausted)
    → DeadLetterPublishingRecoverer → publishes to "<topic>.DLT"
    → Original offset committed → consumer moves on
```

**Naming convention:** Spring Kafka appends `.DLT` (Dead Letter Topic) to the original topic name:
- `order-delivered` → `order-delivered.DLT`
- `order-returned` → `order-returned.DLT`

### Key Components

| Component                        | Role                                                                                         |
| -------------------------------- | -------------------------------------------------------------------------------------------- |
| `DefaultErrorHandler`            | Replaces Spring's default (infinite retry). Catches exceptions, manages retry count.         |
| `FixedBackOff(1000L, 3)`         | Retry strategy: wait 1 second between attempts, max 3 retries.                               |
| `DeadLetterPublishingRecoverer`  | After retries exhausted, publishes the failed message (with error headers) to the DLT topic. |
| `addNotRetryableExceptions(...)` | Exception types that skip retries entirely — sent to DLT immediately on first failure.       |

### Retryable vs Non-Retryable

| Retryable (temporary)                     | Non-Retryable (permanent)             |
| ----------------------------------------- | ------------------------------------- |
| `DataAccessException` (DB hiccup)         | `DeserializationException` (bad JSON) |
| `TimeoutException` (network blip)         | `NullPointerException` (code bug)     |
| `HttpServerErrorException` (upstream 500) | `ClassCastException` (wrong type)     |

Non-retryable exceptions are configured explicitly:
```java
errorHandler.addNotRetryableExceptions(
    DeserializationException.class,
    NullPointerException.class
);
```

### DLT Message Headers

When a message lands in the DLT, Spring Kafka adds headers for investigation:
- `kafka_dlt-exception-fqcn` — exception class name
- `kafka_dlt-exception-message` — error message
- `kafka_dlt-exception-stacktrace` — full stack trace
- `kafka_dlt-original-topic` — where it came from
- `kafka_dlt-original-offset` — original position

### How `DefaultErrorHandler` Integrates (No Code Changes to Listeners)

Spring Boot auto-configures `ConcurrentKafkaListenerContainerFactory`. When a `DefaultErrorHandler` bean exists in the context, the factory picks it up automatically. Your `@KafkaListener` methods remain unchanged — the error handling is declarative infrastructure, not per-listener code.

---

## 10. Retry Strategies — Fixed vs Exponential vs Jitter

### The Problem: Thundering Herd on Fixed Retries

With `FixedBackOff(1000L, 3)`, every failed consumer retries at exactly T+1s, T+2s, T+3s. If a database blip causes 50 consumers to fail simultaneously, they ALL hit the recovering database with 50 concurrent queries at each tick — making recovery harder or impossible.

```
Fixed Backoff (50 consumers fail at T=0):
T+1s: 50 retries hit DB simultaneously
T+2s: 50 retries hit DB simultaneously
T+3s: 50 retries hit DB simultaneously → all fail → 50 messages to DLT
```

### Exponential Backoff — Spread the Load

Exponential backoff increases the delay between retries by a multiplier. With `initialInterval=1s, multiplier=2.0`:

```
Retry 1: wait 1 second
Retry 2: wait 2 seconds (1 × 2)
Retry 3: wait 4 seconds (2 × 2)
Total elapsed: ~7 seconds before DLT
```

The increasing gaps give the recovering resource progressively more breathing room. `maxInterval` caps the delay so it doesn't grow unbounded on higher retry counts.

### History

Exponential backoff was formalized for Ethernet collision resolution in the 1970s (IEEE 802.3 "binary exponential backoff" algorithm). The insight: when a shared resource is contended, spreading retries over increasing intervals gives the resource time to recover. Adopted by TCP congestion control (1988, Van Jacobson), then became standard in cloud SDKs:
- AWS SDK (2015+): exponential + full jitter by default
- Google Cloud (2016+): truncated exponential backoff
- Stripe API (2017+): exponential with idempotency keys

### Jitter — Desynchronize Retries

Even with exponential backoff, 50 consumers that fail at the same instant still retry at the same exponential intervals (1s, 2s, 4s) — synchronized peaks reduced in frequency but not eliminated.

**Jitter** adds randomness to each interval: `delay = baseDelay × random(0.8, 1.2)`. This desynchronizes retries so they spread evenly rather than hitting in bursts.

```
Without jitter:  Consumer A: 1s, 2s, 4s    Consumer B: 1s, 2s, 4s  (identical)
With jitter:     Consumer A: 0.9s, 2.3s, 3.7s    Consumer B: 1.1s, 1.8s, 4.4s  (spread)
```

Three jitter strategies (AWS research, 2015 blog by Marc Brooker):
- **Full jitter**: `delay = random(0, baseDelay)` — most aggressive spread
- **Equal jitter**: `delay = baseDelay/2 + random(0, baseDelay/2)` — guaranteed minimum wait
- **Decorrelated jitter**: `delay = random(baseDelay, previousDelay × 3)` — self-adjusting

Spring Kafka's `ExponentialBackOffWithMaxRetries` does NOT include jitter natively. For production systems with many consumers, you'd wrap it with a custom `BackOff` implementation that adds jitter.

### Spring Kafka Implementation

| Class                                          | Behavior                                               |
| ---------------------------------------------- | ------------------------------------------------------ |
| `FixedBackOff(interval, maxAttempts)`          | Constant delay. Simple. Thundering herd risk.          |
| `ExponentialBackOff`                           | Growing delay. No max-retries (uses `maxElapsedTime`). |
| `ExponentialBackOffWithMaxRetries(maxRetries)` | Growing delay + explicit retry cap. Recommended.       |

```java
// ExponentialBackOffWithMaxRetries extends ExponentialBackOff
// and overrides stop logic to count retries instead of elapsed time
ExponentialBackOff backOff = new ExponentialBackOffWithMaxRetries(3);
backOff.setInitialInterval(1000L);  // 1s first retry
backOff.setMultiplier(2.0);         // 1s → 2s → 4s
backOff.setMaxInterval(10000L);     // never exceed 10s per retry
```

### When to Use Which

| Scenario                               | Strategy                                                |
| -------------------------------------- | ------------------------------------------------------- |
| Low-volume, single consumer            | FixedBackOff (simplicity wins)                          |
| Multiple consumers, shared DB          | ExponentialBackOff (spread retries)                     |
| High-volume production, many instances | Exponential + Jitter (eliminate synchronized storms)    |
| Idempotent consumers, fast recovery    | Aggressive retries OK (lower multiplier, more attempts) |
| Non-idempotent consumers               | Fewer retries, alert quickly, manual DLT review         |

---

## 11. Debezium CDC — Change Data Capture for Outbox Relay

### The Problem: Polling vs CDC

The Outbox Poller (`OutboxPoller.java`) polls the database every 5 seconds for PENDING rows. This has trade-offs:

| Aspect     | Polling (OutboxPoller)             | CDC (Debezium)                          |
| ---------- | ---------------------------------- | --------------------------------------- |
| Latency    | Up to poll interval (5s)           | Near-real-time (ms)                     |
| DB load    | Repeated SELECT queries            | Reads WAL stream (no queries)           |
| Complexity | Simple Java code                   | External infrastructure (Kafka Connect) |
| Scaling    | Multiple pollers need coordination | Single connector per table              |
| Recovery   | Re-reads PENDING on restart        | Resumes from WAL position (LSN)         |

### What Is Change Data Capture?

CDC captures **row-level changes** (INSERT, UPDATE, DELETE) from a database's internal change log and streams them as events. No application code runs — the database itself is the event source.

```
Traditional (Application-Level):
┌─────────────┐    INSERT     ┌──────────────┐
│ Application │──────────────▶│ PostgreSQL   │
│ (OutboxPoller)│             │              │
│ polls every 5s│◀───SELECT───│ outbox_events│
│ publishes to  │────────────▶│              │
│ Kafka         │             └──────────────┘
└─────────────┘

CDC (Database-Level):
┌─────────────┐    INSERT     ┌──────────────┐    WAL stream    ┌───────────┐
│ Application │──────────────▶│ PostgreSQL   │─────────────────▶│ Debezium  │
│ (writes only)│              │              │                  │ Connector │
│ no polling   │              │ outbox_events│                  │           │
└─────────────┘               └──────────────┘                  └─────┬─────┘
                                                                      │ publish
                                                                      ▼
                                                               ┌──────────────┐
                                                               │ Kafka Topic  │
                                                               │ order-delivered│
                                                               └──────────────┘
```

### PostgreSQL WAL (Write-Ahead Log)

Every PostgreSQL write goes through the WAL **before** hitting the actual table files. This guarantees crash recovery (replay WAL after crash). Debezium reads this same WAL stream.

```
Client INSERT → WAL (append-only log on disk) → Background Writer → Table Data Files
                     ▲
                     │
              Debezium reads here
              (logical replication slot)
```

**WAL Levels:**

| Level               | What's Logged               | Use Case                   |
| ------------------- | --------------------------- | -------------------------- |
| `minimal`           | Crash recovery only         | Standalone, no replication |
| `replica` (default) | + physical replication data | Streaming replicas         |
| `logical`           | + row-level changes decoded | CDC, logical replication   |

CDC requires `wal_level = logical` — this adds decoded row data to the WAL that tools like Debezium can interpret.

```sql
-- Check current level:
SHOW wal_level;

-- Change (requires restart):
ALTER SYSTEM SET wal_level = 'logical';
-- Then restart PostgreSQL service
```

### Debezium Architecture

Debezium runs as a **Kafka Connect connector** — it's not a standalone application, but a plugin inside the Kafka Connect framework.

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Kafka Connect (Debezium Container)                │
│                                                                     │
│  ┌────────────────────────────────────────────────────┐            │
│  │  Source Connector: equitycart-outbox-connector       │            │
│  │  Class: io.debezium.connector.postgresql.PostgresConnector │     │
│  │                                                      │            │
│  │  1. Connects to PostgreSQL (JDBC)                    │            │
│  │  2. Creates logical replication slot                  │            │
│  │  3. Reads WAL stream (INSERT/UPDATE/DELETE events)    │            │
│  │  4. Applies SMTs (Single Message Transforms)         │            │
│  │  5. Publishes to Kafka topic                         │            │
│  └───────────────────┬────────────────────────────────┘            │
│                      │                                              │
│  ┌───────────────────▼────────────────────────────────┐            │
│  │  Outbox Event Router (SMT)                          │            │
│  │  - Extracts `payload` column → Kafka value          │            │
│  │  - Extracts `aggregate_id` → Kafka key              │            │
│  │  - Routes to topic from `topic` column              │            │
│  │  - Removes Debezium envelope (no wrapper needed)    │            │
│  └────────────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  Kafka Broker   │
│  Topics:        │
│  - order-delivered│
│  - order-returned │
└─────────────────┘
```

### Outbox Event Router — The Key SMT

Without the Outbox Event Router, Debezium publishes a raw change event for the `outbox_events` table — including all columns, Debezium metadata, schema information. The Outbox Event Router transforms this into a clean event routed to the correct topic:

```
WITHOUT Outbox Event Router (raw Debezium event):
{
  "schema": {...},
  "payload": {
    "before": null,
    "after": {
      "id": 6,
      "aggregate_type": "Order",
      "aggregate_id": 6,
      "event_type": "ORDER_DELIVERED",
      "topic": "order-delivered",
      "payload": "{\"orderId\":6,...}",     ← buried inside
      "status": "PENDING"
    },
    "source": {"version":"2.x", "connector":"postgresql", ...},
    "op": "c",
    "ts_ms": 1779578721050
  }
}
Topic: equitycart-db.public.outbox_events  ← generic table-change topic

WITH Outbox Event Router (clean extracted event):
Kafka Key: "6"                              ← aggregate_id
Kafka Value: {"orderId":6, "userId":1, ...} ← payload column content
Topic: order-delivered                      ← routed by `topic` column
```

### Connector Configuration — Field Mappings

The Outbox Event Router needs to know which column serves which purpose:

```json
{
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.id": "id",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.aggregate.type": "aggregate_type",
  "transforms.outbox.table.field.aggregate.id": "aggregate_id",
  "transforms.outbox.table.field.event.type": "event_type",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.route.by.field": "topic",
  "transforms.outbox.route.topic.replacement": "${routedByValue}"
}
```

**Column naming gotcha:** Debezium defaults assume camelCase column names (`aggregateType`, `aggregateId`). Hibernate's default naming strategy generates snake_case (`aggregate_type`, `aggregate_id`). You MUST explicitly map every column name that differs from Debezium's default expectation.

### Docker Networking — Dual-Listener Pattern

Debezium runs inside Docker. Kafka also runs inside Docker. The Spring Boot app runs on the host. The challenge: Kafka's `ADVERTISED_LISTENERS` tells clients "connect to me at X" — but the correct address differs depending on WHERE the client is:

```
┌──────────────────────────────────────────────────────────────────┐
│ HOST MACHINE (Windows)                                            │
│                                                                  │
│  ┌──────────────────┐                                            │
│  │ Spring Boot App  │  connects to: localhost:9092               │
│  │ (Kafka client)   │  (PLAINTEXT listener)                     │
│  └────────┬─────────┘                                            │
│           │                                                      │
│  ─────────┼──────────── Docker Network ─────────────────────────│
│           │                                                      │
│  ┌────────▼─────────┐        ┌───────────────────────┐         │
│  │ Kafka Container  │        │ Debezium Container    │         │
│  │                  │◀───────│ connects to:          │         │
│  │ Listener 1:      │        │ host.docker.internal  │         │
│  │  PLAINTEXT:9092  │        │ :29092                │         │
│  │  (for host apps) │        │ (DOCKER listener)     │         │
│  │                  │        └───────────────────────┘         │
│  │ Listener 2:      │                                           │
│  │  DOCKER:29092    │                                           │
│  │  (for containers)│                                           │
│  └──────────────────┘                                           │
└──────────────────────────────────────────────────────────────────┘
```

**Why `localhost:9092` fails from Debezium container:** Inside Debezium's container, `localhost` means the Debezium container itself — not the host or the Kafka container. Kafka needs to advertise a different address for container-to-container traffic.

**Solution:** Two listeners with different advertised addresses:
- `PLAINTEXT://localhost:9092` — advertised to host applications (Spring Boot)
- `DOCKER://host.docker.internal:29092` — advertised to other containers (Debezium)

### @Profile("!cdc") — Toggling Outbox Relay Modes

The outbox table can be relayed by EITHER the poller OR Debezium — not both (that would duplicate events). Spring profiles control which is active:

```
application.yml:
  spring.profiles.active: cdc     ← Debezium handles relay

OutboxPoller.java:
  @Profile("!cdc")                ← only activates when profile is NOT "cdc"
  @Component
  public class OutboxPoller { ... }
```

| Profile Active | OutboxPoller | Debezium      | Who publishes to Kafka? |
| -------------- | ------------ | ------------- | ----------------------- |
| (none/default) | ENABLED      | (not running) | OutboxPoller            |
| `cdc`          | DISABLED     | RUNNING       | Debezium via WAL        |

**Outbox status in CDC mode:** With the poller, rows transition `PENDING → SENT`. With Debezium CDC, the status column stays `PENDING` forever — Debezium reads the WAL INSERT event and doesn't update the row. This is expected behavior. A separate cleanup job can mark old rows or delete them.

### `__TypeId__` Header Problem — CDC vs Spring Kafka

Spring Kafka's `JsonSerializer` adds a `__TypeId__` header to every message (e.g., `com.equitycart.commons.event.OrderDeliveredEvent`). The `JsonDeserializer` reads this header to know which class to instantiate.

Debezium does NOT add this header — it publishes raw payload content without Spring-specific metadata. The consumer sees a message without `__TypeId__` and throws `SerializationException`.

```
Spring-published message:        Debezium-published message:
├─ Headers:                      ├─ Headers:
│  __TypeId__: c.e.c.event...   │  (no __TypeId__)
├─ Value:                        ├─ Value:
│  {"orderId":6,...}             │  {"orderId":6,...}
                                 │
Consumer: reads __TypeId__ →     Consumer: no __TypeId__ →
  Class.forName() → deserialize    SerializationException!
```

**Fix:** Set a default type per `@KafkaListener` so Spring knows what to deserialize even without the header:

```java
@KafkaListener(
    topics = "order-delivered",
    groupId = "equitycart-reward-group",
    properties = "spring.json.value.default.type=com.equitycart.commons.event.OrderDeliveredEvent"
)
void handleOrderDelivered(OrderDeliveredEvent event) { ... }
```

### `@Lob` vs `@Column(columnDefinition = "text")` — CDC Gotcha

In PostgreSQL + Hibernate, `@Lob` on a String field stores content as a **Large Object (OID)**:
- The actual JSON goes into `pg_largeobject` internal catalog
- The column stores only an OID reference number (e.g., 18110)

Debezium reads the WAL, sees the column value (18110), and publishes THAT — it cannot follow OID references to `pg_largeobject`.

```
@Lob (OID storage):
┌────────────────────────┐       ┌────────────────────────┐
│ outbox_events table    │       │ pg_largeobject         │
│                        │       │ (internal catalog)     │
│ payload: 18110 (OID)───┼──────▶│ OID 18110:             │
│                        │       │ {"orderId":6,...}      │
└────────────────────────┘       └────────────────────────┘
                                         ▲
Debezium reads: 18110                    │
JPA/Hibernate reads: follows OID ────────┘ (transparent)
Consumer receives: 18110 → MismatchedInputException!
```

**Fix:** Use `@Column(columnDefinition = "text")` — stores JSON inline in the row. Debezium reads the actual JSON content directly from the WAL.

|                    | `@Lob` (OID)                 | `@Column(columnDefinition = "text")` |
| ------------------ | ---------------------------- | ------------------------------------ |
| Storage            | `pg_largeobject` catalog     | Inline (TOAST if > 2KB)              |
| JPA reads          | Transparent (follows OID)    | Transparent                          |
| Debezium/CDC reads | **Broken** (sees OID number) | Works (sees JSON)                    |
| Max size           | Unlimited                    | Unlimited                            |

### `value.converter` — Kafka Connect Serialization Layer

Kafka Connect wraps every message through a **value converter** before writing to Kafka. The default (`JsonConverter` with `schemas.enable=true`) adds schema metadata:

```
Default JsonConverter output:
{"schema":null,"payload":"{\"orderId\":6,...}"}  ← wrapped!

StringConverter output:
{"orderId":6,...}                                 ← raw, as-is
```

For the Outbox Event Router (which already extracts the payload), `StringConverter` is correct — it publishes the payload column content without wrapping:

```json
"value.converter": "org.apache.kafka.connect.storage.StringConverter"
```

### `snapshot.mode` — Initial Table Scan

When a Debezium connector starts for the first time, it performs an **initial snapshot** — reading all existing rows from the monitored table. For the outbox table, this means publishing ALL existing PENDING rows.

| Mode                | Behavior                                           | When to Use                                         |
| ------------------- | -------------------------------------------------- | --------------------------------------------------- |
| `initial` (default) | Full table scan on first start, then WAL streaming | Need to capture existing data                       |
| `never`             | Skip snapshot, only stream new WAL changes         | Outbox table (old rows already processed by poller) |
| `always`            | Snapshot on every connector restart                | Recovery/debugging                                  |

For the outbox pattern: `"snapshot.mode": "never"` — existing rows were already published by the OutboxPoller before switching to CDC. Only new INSERTs need capturing.

### Issues Faced and Resolutions

| Issue                                                       | Root Cause                                                                            | Resolution                                                                           |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Debezium can't connect to Kafka                             | `advertised.listeners=localhost:9092` — inside Debezium container, localhost = itself | Added dual-listener: DOCKER on port 29092 advertised as `host.docker.internal:29092` |
| Connector FAILED: "aggregatetype is not a valid field name" | Hibernate snake_case columns vs Debezium's expected camelCase defaults                | Added explicit `table.field.*` mappings for all snake_case columns                   |
| Consumer infinite loop: "No type information in headers"    | Debezium messages lack `__TypeId__` header that Spring's JsonDeserializer requires    | Added `spring.json.value.default.type` property to each `@KafkaListener`             |
| InvalidTimestampException: "Timestamp out of range"         | `created_at` column used as Kafka timestamp — host timezone (IST) vs Docker UTC       | Removed `transforms.outbox.table.field.event.timestamp` from connector config        |
| Consumer receives number (18110) instead of JSON            | `@Lob` stores OID reference — Debezium reads OID, not the referenced content          | Replaced `@Lob` with `@Column(columnDefinition = "text")` for inline storage         |
| order-delivered-dlt auto-created immediately                | Old poison messages on topic from failed snapshot attempts                            | Deleted topics, added `snapshot.mode=never`, re-registered connector                 |
| Git Bash mangles docker exec paths                          | Git Bash converts `/opt/...` to Windows paths                                         | Use PowerShell or Docker Desktop terminal instead                                    |

### CDC Drawbacks and Production Considerations

While CDC eliminates polling overhead, it introduces its own failure modes:

| Drawback                               | Why It Matters                                                                                                                                          |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **WAL disk growth**                    | `wal_level=logical` generates more WAL data than `replica`. If consumers fall behind or replication slots stall, WAL accumulates and can fill disk.     |
| **Replication slot retention**         | If Debezium goes down, PostgreSQL holds WAL segments indefinitely (won't recycle them). This can fill the disk in hours on write-heavy systems.         |
| **No delivery confirmation to source** | Unlike the poller (which marks rows SENT after Kafka ACK), CDC has no feedback mechanism to the outbox table. You can't query "was this row published?" |
| **Operational complexity**             | Kafka Connect is another distributed system to deploy, monitor, upgrade. Connector failures require manual restart via REST API.                        |
| **Schema evolution**                   | Adding/renaming columns in the outbox table can break the Debezium connector if field mappings aren't updated simultaneously.                           |
| **Snapshot poisoning**                 | Re-registering a connector triggers a full table scan (default `snapshot.mode=initial`), publishing stale rows that may have already been processed.    |
| **Timezone mismatches**                | Docker containers default to UTC; host apps use local time. Any timestamp column used as Kafka message timestamp will mismatch.                         |
| **Debugging difficulty**               | Issues surface as Kafka Connect REST API errors or consumer failures — harder to trace than application-level exceptions in the poller.                 |

**When polling wins over CDC:**
- Low event volume (< 100/min) — polling overhead is negligible
- No Docker/container infrastructure available
- Team lacks Kafka Connect operational expertise
- Rapid prototyping or learning environment (simpler debugging)

**When CDC wins over polling:**
- High event volume where 5s latency is unacceptable
- Multiple databases need event capture
- Operational team exists to manage Kafka Connect
- Need to capture ALL table changes (not just outbox — e.g., audit logging)

### Complete CDC Flow (End State)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ① OrderServiceImpl.updateOrderStatus(orderId=6, DELIVERED)                │
│     └─ @Transactional: order.save() + outboxEvent.save() ← ATOMIC         │
│                                                                             │
│  ② PostgreSQL WAL captures INSERT on outbox_events                         │
│     └─ WAL entry: {table:outbox_events, op:INSERT, id:6, payload:"..."}    │
│                                                                             │
│  ③ Debezium (Kafka Connect) reads WAL via logical replication slot         │
│     └─ Outbox Event Router extracts payload column → Kafka value           │
│     └─ Routes to topic based on `topic` column value ("order-delivered")   │
│     └─ Key = aggregate_id column value ("6")                               │
│                                                                             │
│  ④ Kafka Broker receives message on "order-delivered" topic                │
│     └─ No __TypeId__ header (Debezium doesn't add it)                      │
│                                                                             │
│  ⑤ StockBackRewardConsumer polls topic                                     │
│     └─ spring.json.value.default.type → deserializes as OrderDeliveredEvent│
│     └─ Calculates reward, calls grantReward() (idempotent)                 │
│                                                                             │
│  ⑥ Vesting Job (60s) picks up PENDING reward after vestingDate passes      │
│     └─ PENDING → VESTED, creates actual Holding in portfolio               │
│                                                                             │
│  Note: outbox_events.status stays PENDING in CDC mode (expected)           │
│  Note: OutboxPoller is disabled via @Profile("!cdc")                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

_This document will be expanded as Phase 6 progresses with: Saga patterns, exactly-once semantics, and production tuning._

---

## 14. Notification Topic — Observer Pattern via Kafka Pub/Sub

### Topic: `portfolio-notification`

| Field              | Value                                       |
| ------------------ | ------------------------------------------- |
| Topic name         | `portfolio-notification`                    |
| Producer           | NotificationPublisher (portfolio-service)   |
| Consumer           | NotificationConsumer (notification-service) |
| Consumer group     | `equitycart-notification-group`             |
| Key                | userId (String)                             |
| Value              | NotificationEvent (JSON)                    |
| Delivery guarantee | Fire-and-forget (best-effort, no outbox)    |

### Why fire-and-forget (no Outbox)?

Unlike order events (business-critical — lost event = lost reward), notification events are low-severity. A missed notification is annoying but not data-corrupting. The user can always check the API. This justifies simpler KafkaTemplate.send() without the complexity of the Outbox Pattern.

```
Trade completes in TradeServiceImpl:
  ↓
  notificationPublisher.publish(NotificationEvent{
    userId, TRADE_EXECUTED, ticker, qty, price, totalValue, metadata, timestamp
  })
  ↓
  KafkaTemplate.send("portfolio-notification", userId.toString(), event)
  ↓ (async, wrapped in try-catch — failure logged at WARN, never propagates)
  ↓
Kafka broker stores message
  ↓
NotificationConsumer.handleNotification(@Payload event)
  ↓ @KafkaListener(topics="portfolio-notification", groupId="equitycart-notification-group")
  ↓
NotificationDispatcher.dispatch(event)
  ↓ resolves channel from config: LOG | EMAIL | WEBHOOK
  ↓
  ├── LogChannelStrategy: logs at INFO level
  ├── EmailChannelStrategy: JavaMailSender → MailHog SMTP (port 1025)
  └── WebhookChannelStrategy: WebClient POST to configured URL
  ↓
NotificationLog entity persisted to PostgreSQL (audit trail)
```

### Event Types Published

| Event Type              | Publisher                   | Trigger                            |
| ----------------------- | --------------------------- | ---------------------------------- |
| TRADE_EXECUTED          | TradeServiceImpl            | After BUY or SELL trade completes  |
| REWARD_VESTED           | VestingHelperImpl           | After reward transitions to VESTED |
| SELL_TO_SPEND_COMPLETED | SellToSpendSagaOrchestrator | Saga reaches COMPLETED state       |
| SELL_TO_SPEND_FAILED    | SellToSpendSagaOrchestrator | Saga reaches terminal failure      |

### Kafka in Docker — Single-Broker Configuration

When running Kafka with one broker (development), these three settings prevent `INVALID_REPLICATION_FACTOR` errors:

```yaml
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
```

Without these, Kafka tries to replicate internal topics (`__consumer_offsets`, `__transaction_state`) to 3 brokers that don't exist.

### Docker Kafka Dual-Listener (Container-to-Container + Host-to-Container)

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,DOCKER://:29092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,DOCKER://kafka:29092
```

- `PLAINTEXT://localhost:9092` — advertised to host applications connecting from outside Docker
- `DOCKER://kafka:29092` — advertised to other containers (Debezium, Spring Boot services in Docker)

When a Kafka client connects, the broker responds with its advertised listener address. If Kafka only advertised `localhost:9092`, a container trying to connect would use `localhost` — which inside that container means itself, not Kafka.

---

## 15. Phase 10 Topic 1: CQRS Portfolio Read Model — Event-Driven Projection

### The Problem: Sync Read Models After Business Events

When an order is delivered or a trade is executed, multiple services need fast, denormalized query access to the user's portfolio state:
- CQRS Read Model query: "Get my holdings" (not "execute a complex JOIN")
- Problem: Write model (PostgreSQL `Portfolio`, `Holding`, `Reward`) is normalized for transactional consistency. Querying it requires expensive JOINs across multiple tables.
- Solution: Maintain a separate **read model** in MongoDB (denormalized snapshot per user) that's kept in sync via Kafka events.

### Event-Driven Rebuild: Each Kafka Message Triggers Full Snapshot

Unlike incremental delta projection (update only the changed fields), Topic 1 implements **correctness-first full rebuild**:

```
1. TradeServiceImpl.recordBuy() → writes Portfolio, Holding, Reward to PostgreSQL
2. PortfolioOutboxWriter.writeSharesPurchasedEvent() → writes OutboxEvent row (atomically, same transaction)
3. Debezium CDC reads PostgreSQL WAL → publishes PortfolioProjectionEvent to Kafka topic
   (Kafka partition key = userId, ensuring all user events go to same partition)
4. PortfolioReadModelOutboxConsumer.handleProjectionEvent() receives event
5. PortfolioReadModelSynchronizer.rebuildReadModelForUser(userId) queries PostgreSQL:
   SELECT all Holdings + Rewards + metadata for userId
   → Upserts entire user snapshot to MongoDB (replace on userId match)
6. API queries MongoDB (fast denormalized read) → serves response
```

**Why full rebuild vs delta projection?**
- Delta projection: update only qty/price for the holding — faster, but risky if event order corrupted or payload complex
- Full rebuild: fetch fresh from source-of-truth → guaranteed consistency even if events reordered. Trades compute for correctness.
- **Optimization decision deferred**: Once metrics show rebuild is bottleneck (e.g., >100ms per user, millions of users), switch to incremental projection.

### MongoDB Schema — Denormalized Snapshot

```json
{
  "_id": ObjectId("..."),
  "userId": "user-123",
  "totalValue": 5000.50,
  "totalRewards": 1500.00,
  "holdings": [
    {
      "ticker": "AAPL",
      "quantity": 10,
      "averageCost": 150.25,
      "currentPrice": 180.50,
      "marketValue": 1805.00
    }
  ],
  "rewards": [
    {
      "id": "rew-456",
      "status": "PENDING",      // PENDING | VESTED | CANCELLED
      "vestingDate": "2026-01-15",
      "ticker": "AAPL",
      "quantity": 2
    }
  ],
  "lastUpdatedAt": "2026-01-08T10:30:45Z",
  "version": 42                  // internal version tracking
}
```

**Key design decisions:**
- No `_id` generation during projection — MongoDB auto-generates it
- `userId` is queried field (frequently filtered), so indexed separately
- `version` field not used (for future optimistic locking if needed)
- `holdings` and `rewards` arrays denormalized (no separate collections) — single upsert atomic

### Idempotency via MongoDB Upsert by userId

**The idempotency challenge:** Kafka at-least-once semantics means events can be retried. Processing the same event twice must produce the same result.

```java
// Implementation (Spring MongoTemplate):
Query query = new Query(Criteria.where("userId").is(userId));
Update update = new Update()
    .set("totalValue", computedTotal)
    .set("holdings", holdings)
    .set("rewards", rewards)
    .set("lastUpdatedAt", Instant.now());

mongoTemplate.upsert(query, update, ReadModelPortfolio.class);
// Upsert: if userId exists → update fields; if not → insert new doc with userId
```

**Why this guarantees idempotency:**
1. First event received: user doc doesn't exist → INSERT (MongoDB adds _id)
2. Retry of same event: user doc exists (same userId) → UPDATE all fields
3. Third retry: same UPDATE applied again (idempotent — same result)
4. Unique index on userId ensures no duplicate user docs

**Alternative approach (rejected):** Using `repository.save(newDoc)` always inserts → duplicate docs with different ObjectIds → manual deduplication logic → complex.

**Idempotency in Saga State Machines (Topic 8 - Clawback):**

The same principle applies to saga orchestration. When a `ClawbackSagaOrchestrator` receives a message for an in-flight saga (e.g., during timeout recovery or retry), it must not duplicate state transitions:

```java
// ClawbackSaga saga state machine (persisted to database):
// Initial: INITIATED (vested reward, refund approved, status CLAWBACK_INITIATED)
// Step 1 → LEDGER_ADJUSTED (reversal ledger entry recorded)
// Step 2 → HOLDING_REDUCED (shares removed from portfolio)
// Step 3 → COMPLETED (clawback finished)

// Idempotency pattern:
if (saga.status == ClawbackStatus.LEDGER_ADJUSTED) {
    // Kafka retry or timeout recovery calls this method again
    // Skip ledger reversal (already done), proceed to step 2
    skipToStep(ClawbackStep.HOLDING_REDUCTION);
} else if (saga.status == ClawbackStatus.INITIATED) {
    // First time seeing this saga, execute normally
    performLedgerReversal(saga);
    saga.status = ClawbackStatus.LEDGER_ADJUSTED;
    sagaRepository.save(saga);
}
```

**Key difference from read-model upsert:**
- **Read models** (MongoDB): Idempotency via upsert (latest value wins)
- **Sagas** (state machines): Idempotency via status gate (skip completed steps)

Both achieve eventual consistency: repeated events produce the same outcome, but the mechanism differs. Sagas must guard against re-executing already-committed step logic (which may have side effects), while read-model projections can safely re-apply the same state transformation.

### @Lob Gotcha: PostgreSQL WAL + Debezium CDC

**Problem discovered in Phase 10:**
- If `@Lob` used on JSON field → PostgreSQL creates OID (Object Identifier) reference
- Debezium reads WAL and publishes the OID number (e.g., `12345`) not the JSON content
- Consumer receives `{"payload": "12345"}` → deserialization fails

**Solution (applied to OutboxEvent):**
```java
@Column(columnDefinition = "text")
private String payload;  // NOT @Lob
```

**Why this works:**
- `columnDefinition = "text"` stores entire JSON inline in PostgreSQL
- Debezium publishes the JSON string directly (not OID reference)
- Consumer deserializes from actual payload content
- Performance: text column slightly slower than BYTEA, but negligible for event payloads

### Kafka Partition Key Strategy: userId as Aggregated ID

```
Kafka Topic: portfolio-projection
  Partition 0: Events for users [0-999]
  Partition 1: Events for users [1000-1999]
  Partition 2: Events for users [2000-2999]
  ...

Each user's events always route to the same partition.
```

**Why this matters:**
1. **Rebuild ordering:** User 123's TRADE_1, TRADE_2, TRADE_3 arrive in order (same partition, single consumer per partition)
2. **Concurrent rebuilds:** Multiple users (different partitions) rebuild in parallel safely
3. **Saga compensation:** Sell-to-spend saga sends events in sequence (SELL_TO_SPEND_INITIATED, SELL_TO_SPEND_COMPLETED or COMPENSATED). Same user partition guarantees order.

**Topic 8 Addition - Clawback Saga Compensation Ordering:**

The partition key strategy is **critical for compensation safety**. The clawback saga for a vested reward follows this sequence:

```
Partition key: userId (same for all events in the clawback flow)

Event Sequence (MUST arrive in order):
1. CLAWBACK_INITIATED → saga persisted with status INITIATED
2. LEDGER_REVERSAL_RECORDED → saga updated, status LEDGER_ADJUSTED
3. HOLDING_REDUCED → saga updated, status HOLDING_REDUCED
4. CLAWBACK_COMPLETED → saga terminal state
   OR
   CLAWBACK_COMPENSATING → saga entering compensation path (timeout or failure)
   LEDGER_REVERSAL_UNDONE → restore ledger to original
   HOLDING_RESTORED → restore shares
   CLAWBACK_FAILED → saga terminal state (manual intervention needed)
```

**Partition key guarantee:** By routing all clawback events for user 123 to the same Kafka partition, the consumer processes them sequentially. This ensures:
- Compensation steps cannot execute before the steps they're undoing have committed
- No race condition where HOLDING_REDUCED arrives before LEDGER_REVERSAL_RECORDED is persisted
- If timeout detection retriggers compensation, the events arrive in order again (idempotency via saga status gate)

**Without the partition key strategy (hash-based on different keys):**
```
User 123's clawback events scattered across partitions:
  Partition 0: HOLDING_REDUCED (consumer A)
  Partition 1: LEDGER_REVERSAL_RECORDED (consumer B)
  Partition 2: CLAWBACK_INITIATED (consumer C)

Result: Consumer A processes HOLDING_REDUCED before saga is even created (CLAWBACK_INITIATED).
        Foreign key lookup fails or creates inconsistent state.
        Compensation safety violated.
```

This is why `ClawbackOutboxWriter` must use `userId` as the Kafka message key, not `orderId`, `refundId`, or any other domain identifier.

### How Scheduled Reconciliation Handles Drift

**Background job (ReadModelReconciliation, 24-hour interval):**

```java
// Pseudocode: check for drift
for each user:
  postgresPortfolio = fetch from write-model (source of truth)
  mongoPortfolio = fetch from read-model (projection)
  
  if (postgresPortfolio != mongoPortfolio):
    PortfolioReadModelSynchronizer.rebuildReadModelForUser(userId)
    log.warn("Detected drift for user {}, reconciled", userId)
```

**Why separate reconciliation job?**
- Event-driven rebuild (via Kafka consumer) handles happy path (99.9% of cases)
- Reconciliation job handles edge cases: Kafka message loss (rare), consumer crash+replay, CDC connector downtime
- 24-hour interval acceptable because projection is not critical-path (caching/read-only) — business operations use write-model

**Topic 8 Addition - Saga Timeout Detection (Different Purpose, Similar Mechanism):**

While `ReadModelReconciliation` detects projection drift, `ClawbackSagaTimeoutDetector` (also scheduled, 30-second interval for dev/test) detects stuck sagas:

```java
@Scheduled(fixedRate = 30000)  // Every 30 seconds in dev; hours in production
void detectTimedOutSagas() {
  List<ClawbackSaga> stuck = sagaRepository.findByStatusNotInAndUpdatedAtBefore(
    terminalStates: [COMPLETED, FAILED],
    threshold: now - 30_seconds
  );
  
  for (ClawbackSaga saga : stuck) {
    ClawbackStatus lastKnownStep = deriveLastCompletedStep(saga);
    if (saga.attemptCount >= MAX_RETRIES) {
      compensateAndFail(saga);  // Give up, go to FAILED state
    } else {
      retryFromStep(saga, lastKnownStep);  // Resume from known-good state
    }
  }
}
```

**Key differences between reconciliation and saga recovery:**

| Aspect | ReadModelReconciliation | ClawbackSagaTimeoutDetector |
|--------|------------------------|----------------------------|
| **Purpose** | Detect read-model divergence (state mismatch) | Detect stuck sagas (incomplete transactions) |
| **Source of truth** | PostgreSQL write-model | ClawbackSaga state machine + Kafka offset history |
| **Action on detection** | Rebuild entire user portfolio from events | Retry last known step or enter compensation |
| **Frequency** | 24 hours (acceptable drift window) | 30 seconds (dev) to minutes (prod) |
| **Business impact if missed** | Stale reads, eventual consistency delay | Uncredited rewards (financial reconciliation miss) |

Both are **eventual consistency repair mechanisms** — neither is a fast path. Fast paths are event-driven:
- Read-model: Debezium CDC + Kafka consumer (< 1 second)
- Saga: Kafka listener on `clawback-saga-events` (milliseconds)

Scheduled jobs are **insurance policies** for failure modes that escape the fast path.

### Why synchronizeReadModels Is Commented Out

Initial design (Phase 5) scheduled a polling job:

```java
@Scheduled(fixedDelay = 5000)  // Every 5 seconds
private void synchronizeReadModels() {
  // Query all portfolios, check each against MongoDB, rebuild if needed
}
```

**Replaced by event-driven approach in Phase 10:**

1. **Primary path (Debezium CDC):**
   - PostgreSQL WAL → Debezium Kafka Connect → portfolio-projection topic
   - Most reliable, lowest latency (sub-second)

2. **Fallback path (OutboxPoller):**
   - Application polls `outbox_events` table periodically
   - Publishes to portfolio-projection topic
   - Enabled when CDC not running (feature flag `@Profile("!cdc")`)

3. **Event-driven rebuild:**
   - Each event triggers `PortfolioReadModelOutboxConsumer.handleProjectionEvent()`
   - Calls `PortfolioReadModelSynchronizer.rebuildReadModelForUser(userId)`
   - MongoDB upsert ensures idempotency

4. **24-hour reconciliation job:**
   - Separate scheduled task
   - Detects and repairs drift (independent of event stream)

**Code kept for reference:** Shows the polling pattern (useful for systems without CDC capability). Java comments explain the three-part strategy above.

### @Profile("!cdc") — Feature Flag for Outbox Relay Mode

```yaml
# application.yml (production, CDC enabled)
spring.profiles.active: cdc

# application-cdc.yml overlay
kafka.cdc.enabled: true

# Spring Bean Conditional Registration
@Configuration
@Profile("!cdc")
public class OutboxPollerConfig {
  @Bean
  public OutboxPoller outboxPoller() { ... }  // Only created if NOT cdc profile
}
```

**Why feature flag?**
- **CDC mode:** Debezium running in Docker, tailing PostgreSQL WAL → portfolio-projection
  - No polling, no OutboxPoller polling `outbox_events` table
- **Polling mode:** Debezium not running, OutboxPoller polls table every 2s → publishes to same topic
- **Single Spring app, two deployment modes** with one config change

### Kafka Consumer Group Strategy

```
Topic: portfolio-projection
Consumer Group: equitycart-portfolio-read-model-sync

Kafka ensures:
- Each partition assigned to ONE consumer (or rebalanced if consumer fails)
- Offset committed per partition
- At-least-once semantics per event
```

**For Phase 10:**
- Single instance of `PortfolioReadModelOutboxConsumer` listening
- Future: scale horizontally by adding more instances (auto-rebalance across partitions)

### Testing Strategy — Manual E2E (No Full JUnit Suite Yet)

Phase 10 uses manual E2E validation checklist (auto-testing deferred):

```
✓ BUY → outbox row written → Debezium publishes → consumer rebuilds → API returns updated holdings
✓ SELL → outbox row written → Debezium publishes → consumer rebuilds → API returns updated portfolio
✓ REWARD_GRANTED → outbox row written → Debezium publishes → consumer rebuilds → API returns pending reward
✓ REWARD_VESTED → outbox row written → Debezium publishes → consumer rebuilds → API returns vested reward
✓ SELL_TO_SPEND → outbox row written → Debezium publishes → consumer rebuilds → API returns completed state
✓ REFUND_RESTORED (compensation) → outbox row written → Debezium publishes → consumer rebuilds → API shows restored holdings

Each flow verified: PostgreSQL write-model → outbox_events → Kafka → MongoDB read-model → CQRS API response
```

### Lessons Learned (Phase 10 Topic 1 + Topic 8 Reinforcement)

1. **Correctness-first over optimization:** Full rebuild is simpler, safer, deferred premature optimization to delta projection
2. **Idempotency via upsert by aggregateId:** Kafka at-least-once + MongoDB upsert by userId = guaranteed exactly-once semantics
3. **@Lob incompatible with CDC:** Must use `@Column(columnDefinition = "text")` for Debezium to read payload
4. **Event-driven rebuild > polling:** No 5-second poll interval + unnecessary queries; events trigger rebuild immediately
5. **Partition key strategy matters:** userId as Kafka key ensures replay order and saga compensation safety
6. **Feature flags for deployment flexibility:** `@Profile("!cdc")` toggles between Debezium and OutboxPoller without code change
7. **Separate reconciliation for drift repair:** CDC handles happy path, scheduled 24h job handles edge cases

**Topic 8 Reinforcement (Clawback Saga):**

8. **Saga compensation ≠ automatic retry:** Compensation is a NEW forward-operation (ledger reversal, holding restoration) that semantically undoes a failed saga step. It is NOT a database rollback. Confused compensation with retry (thinking "just re-exec the failed step") leads to partial state left behind or duplicate side effects.
9. **Status gates prevent duplicate saga steps:** Idempotency for sagas doesn't use upsert (like read-models); it uses status checks before each step. If saga.status already shows LEDGER_ADJUSTED, skip `performLedgerReversal()` again even if Kafka retries the same message.
10. **Timeout detection is event-independent reconciliation:** ClawbackSagaTimeoutDetector runs on a schedule, independent of event streams. It's insurance for "events that never arrived." Contrast with event-driven paths (Debezium → consumer) which are the fast/primary repair mechanism.
11. **Partition key must propagate through entire saga flow:** The `userId` key that routed the initial `CLAWBACK_INITIATED` event must be used for ALL downstream events (LEDGER_REVERSAL_RECORDED, HOLDING_REDUCED, etc.). Mixing keys (orderId on one step, userId on another) breaks partition-level ordering guarantees and can cause compensation steps to execute out-of-order.
12. **Manual E2E is sufficient for CQRS + Saga validation:** Full JUnit suite would require mocking Kafka/MongoDB/saga state complexity; manual checklist provides business confidence
