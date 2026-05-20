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
| Setting | Behavior | Trade-off |
|---------|----------|-----------|
| `acks=0` | Fire and forget (don't wait for broker) | Fastest, may lose messages |
| `acks=1` | Wait for leader to acknowledge | Balanced (default) |
| `acks=all` | Wait for ALL replicas to acknowledge | Slowest, strongest durability |

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

| Component | Role |
|-----------|------|
| `DefaultErrorHandler` | Replaces Spring's default (infinite retry). Catches exceptions, manages retry count. |
| `FixedBackOff(1000L, 3)` | Retry strategy: wait 1 second between attempts, max 3 retries. |
| `DeadLetterPublishingRecoverer` | After retries exhausted, publishes the failed message (with error headers) to the DLT topic. |
| `addNotRetryableExceptions(...)` | Exception types that skip retries entirely — sent to DLT immediately on first failure. |

### Retryable vs Non-Retryable

| Retryable (temporary) | Non-Retryable (permanent) |
|------------------------|---------------------------|
| `DataAccessException` (DB hiccup) | `DeserializationException` (bad JSON) |
| `TimeoutException` (network blip) | `NullPointerException` (code bug) |
| `HttpServerErrorException` (upstream 500) | `ClassCastException` (wrong type) |

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

_This document will be expanded as Phase 6 progresses with: Saga patterns, exactly-once semantics, and production tuning._
