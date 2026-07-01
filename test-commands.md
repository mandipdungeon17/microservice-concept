# EquityCart — API Test Commands

> Consolidated curl commands for testing all phases.
> Replace `<Token>` with a valid JWT from `/api/auth/login`.

---

## Docker Compose — Full Stack (Phase 7)

### Start Everything (Recommended)

```bash
# From equitycart/docker/ directory:

# Step 1: Build all Docker images (run once, or after code changes)
sh build-images.sh

# Step 2: Start infrastructure (PostgreSQL, Kafka, Redis, MongoDB, MailHog, Debezium)
sh start-pets.sh

# Step 3: Start all services (discovery → config-server → all business services)
sh start-services.sh

# Verify: All services should appear at http://localhost:8761 (Eureka Dashboard)
```

### Stop Everything

```bash
# Stop services only (infra stays running)
cd equitycart/
docker compose -f docker/docker-compose-services.yml down

# Stop infrastructure only
docker compose -f docker/docker-pets.yml down

# Stop both (preserves volumes/data)
docker compose -f docker/docker-pets.yml -f docker/docker-compose-services.yml down

# Nuclear: stop + delete all data (volumes removed)
docker compose -f docker/docker-pets.yml -f docker/docker-compose-services.yml down -v
```

### Useful Docker Commands

```bash
# See all running containers
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# View logs for a specific service
docker logs docker-order-service-1 -f --tail 50

# View config-server logs (check for DNS errors)
docker logs docker-config-server-1 --tail 100

# Check Eureka registrations
curl -s http://localhost:8761/eureka/apps | grep "<app>"

# Kafka: List topics
docker exec kafka sh -c '/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092'

# Kafka: Consume from topic
docker exec kafka sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic portfolio-notification --from-beginning'

# Redis CLI
docker exec -it redis redis-cli

# MongoDB shell
docker exec -it mongodb mongosh

# PostgreSQL: connect to specific database
docker exec -it postgres psql -U postgres -d equitycart_order

# MailHog Web UI (view caught emails)
# Open in browser: http://localhost:8025
```

---

## Docker — Legacy Individual Containers (Phase 6 — local dev without Compose)

```bash
# Start PostgreSQL
docker run -d \
  --name postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=equitycart \
  -p 5432:5432 \
  postgres:16

# Start Redis
docker run -d \
  --name redis \
  -p 6379:6379 \
  redis:7

# Start MongoDB
docker run -d \
  --name mongodb \
  -p 27017:27017 \
  mongo:7

# Start Kafka (KRaft mode — no ZooKeeper)
# Dual-listener: PLAINTEXT for host apps, DOCKER for containers (Debezium)
docker run -d \
  --name kafka \
  -p 9092:9092 \
  -p 29092:29092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,DOCKER://:29092 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092,DOCKER://host.docker.internal:29092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,DOCKER:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  apache/kafka:latest

# Start Debezium (Kafka Connect with PostgreSQL connector)
# Only needed when using CDC mode (spring.profiles.active=cdc)
docker run -d \
  --name debezium \
  -p 8083:8083 \
  -e GROUP_ID=equitycart-connect \
  -e BOOTSTRAP_SERVERS=host.docker.internal:29092 \
  -e CONFIG_STORAGE_TOPIC=equitycart-connect-configs \
  -e OFFSET_STORAGE_TOPIC=equitycart-connect-offsets \
  -e STATUS_STORAGE_TOPIC=equitycart-connect-status \
  debezium/connect:latest

# Verify all containers are running
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# Stop all
docker stop postgres redis mongodb kafka debezium

# Start again (after stop)
docker start postgres redis mongodb kafka debezium

# Remove all (destroys data)
docker rm -f postgres redis mongodb kafka debezium
```

---

## Prerequisites

```bash
# Login as ADMIN
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@equitycart.com","password":"Test@1234"}'

# Login as CUSTOMER
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"customer@equitycart.com","password":"Test@1234"}'

# Register a new customer
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"customer@equitycart.com","password":"password123"}'
```

---

## Phase 1: Product Catalog (Categories, Brands, Brand-Ticker Mapping, Products)

### Categories

```bash
# Create categories (ADMIN)
curl -s -X POST http://localhost:8080/api/categories \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Electronics","description":"Electronic devices and accessories"}'

curl -s -X POST http://localhost:8080/api/categories \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Sports & Outdoors","description":"Sports equipment and outdoor gear"}'

# Create subcategory (parentId = Electronics ID)
curl -s -X POST http://localhost:8080/api/categories \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Smartphones","description":"Mobile phones","parentId":1}'

# Get top-level categories
curl -s http://localhost:8080/api/categories/top-level \
  -H "Authorization: Bearer <Token>"

# Get subcategories of Electronics
curl -s http://localhost:8080/api/categories/1/subcategories \
  -H "Authorization: Bearer <Token>"

# Get category by ID
curl -s http://localhost:8080/api/categories/1 \
  -H "Authorization: Bearer <Token>"
```

### Brands

```bash
# Create brands (ADMIN)
curl -s -X POST http://localhost:8080/api/brands \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Apple","description":"Apple Inc.","logoUrl":"https://example.com/apple-logo.png"}'

curl -s -X POST http://localhost:8080/api/brands \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Nike","description":"Nike Inc.","logoUrl":"https://example.com/nike-logo.png"}'

# Get all brands
curl -s http://localhost:8080/api/brands \
  -H "Authorization: Bearer <Token>"

# Get brand by ID
curl -s http://localhost:8080/api/brands/1 \
  -H "Authorization: Bearer <Token>"
```

### Brand-Ticker Mappings

```bash
# Map Apple -> AAPL
curl -s -X POST http://localhost:8080/api/brand-ticker-mappings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"brandId":1,"tickerSymbol":"AAPL","exchange":"NASDAQ","stockBackPercentage":2.50}'

# Map Nike -> NKE
curl -s -X POST http://localhost:8080/api/brand-ticker-mappings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"brandId":2,"tickerSymbol":"NKE","exchange":"NYSE","stockBackPercentage":3.00}'

# Get mappings for Apple
curl -s http://localhost:8080/api/brand-ticker-mappings/brand/1 \
  -H "Authorization: Bearer <Token>"
```

### Products

```bash
# Create product (Apple iPhone under Smartphones category, Apple brand)
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"iPhone 16 Pro","description":"Latest iPhone with A18 chip","sku":"AAPL-IP16P-256","price":999.99,"stockQuantity":100,"imageUrl":"https://example.com/iphone16.png","brandId":1,"categoryId":3}'

# Duplicate SKU — should return 409 Conflict
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"iPhone 16 Pro","description":"Latest iPhone with A18 chip","sku":"AAPL-IP16P-256","price":999.99,"stockQuantity":100,"imageUrl":"https://example.com/iphone16.png","brandId":1,"categoryId":3}'

# Get product by ID
curl -s http://localhost:8080/api/products/1 \
  -H "Authorization: Bearer <Token>"

# Update product (change price and stock)
curl -s -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"iPhone 16 Pro","description":"Latest iPhone with A18 chip","sku":"AAPL-IP16P-256","price":899.99,"stockQuantity":85,"imageUrl":"https://example.com/iphone16.png","brandId":1,"categoryId":3}'

# Soft delete product
curl -s -X DELETE http://localhost:8080/api/products/1 \
  -H "Authorization: Bearer <Token>"
```

### RBAC Verification

```bash
# CUSTOMER creating brand — should return 403
curl -s -X POST http://localhost:8080/api/brands \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Adidas","description":"Adidas AG"}'

# CUSTOMER creating product — should return 403
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"name":"Test","sku":"TEST-001","price":10.00,"brandId":1,"categoryId":1}'

# CUSTOMER reading brands — should return 200
curl -s http://localhost:8080/api/brands \
  -H "Authorization: Bearer <Token>"
```

---

## Phase 2: Product Search + Pagination

```bash
# All products, default pagination
curl -s "http://localhost:8080/api/products?page=0&size=10" \
  -H "Authorization: Bearer <Token>"

# Search by name (partial, case-insensitive)
curl -s "http://localhost:8080/api/products?name=iphone" \
  -H "Authorization: Bearer <Token>"

# Filter by brand
curl -s "http://localhost:8080/api/products?brandId=1" \
  -H "Authorization: Bearer <Token>"

# Filter by price range
curl -s "http://localhost:8080/api/products?minPrice=100&maxPrice=500" \
  -H "Authorization: Bearer <Token>"

# Combined filters + sorting
curl -s "http://localhost:8080/api/products?name=nike&categoryId=2&minPrice=50&maxPrice=200&sort=price,asc" \
  -H "Authorization: Bearer <Token>"

# Only active products
curl -s "http://localhost:8080/api/products?active=true" \
  -H "Authorization: Bearer <Token>"
```

---

## Phase 3: Cart, Order, Ledger (Redis Cart + PostgreSQL Orders + Double-Entry Ledger)

### Cart (Redis)

```bash
# Get cart
curl -s http://localhost:8080/api/cart \
  -H "Authorization: Bearer <Token>"

# Add item to cart
curl -s -X POST http://localhost:8080/api/cart/user1/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId": 2, "quantity": 1, "price": 149.50}'

# Delete cart
curl -s -X DELETE http://localhost:8080/api/cart/user1 \
  -H "Authorization: Bearer <Token>"
```

### Order

```bash
# Place order (idempotent)
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-001-user1","shippingAddress":"123 MG Road, Mumbai 400001","paymentMethod":"UPI"}'

# Duplicate order (idempotent — same response)
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-001-user1","shippingAddress":"Different Address","paymentMethod":"CARD"}'

# Get order by ID
curl -s http://localhost:8080/api/order/1 \
  -H "Authorization: Bearer <Token>"

# Status transitions: CREATED -> CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED
curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"CONFIRMED"}'

curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"PROCESSING"}'

curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}'

curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"DELIVERED"}'

# Invalid status — should return 400
curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"BLAH"}'

# Return + Refund flow (after DELIVERED)
curl -s -X PATCH http://localhost:8080/api/order/1/return \
  -H "Authorization: Bearer <Token>"

curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"RETURNED"}'

curl -s -X PATCH http://localhost:8080/api/order/1/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"REFUNDED"}'
```

---

## Phase 4: Market Data (WebClient, Resilience4j, MongoDB, SSE)

```bash
# Get single stock price (cached in Redis, 30s TTL)
curl -s http://localhost:8080/api/market-data/price/AAPL \
  -H "Authorization: Bearer <Token>"

# Batch prices
curl -s "http://localhost:8080/api/market-data/prices?symbols=AAPL,MSFT" \
  -H "Authorization: Bearer <Token>"

# Price history (from MongoDB)
curl -s http://localhost:8080/api/market-data/history/AAPL \
  -H "Authorization: Bearer <Token>"

# Company health score
curl -s http://localhost:8080/api/market-data/health/AAPL \
  -H "Authorization: Bearer <Token>"

# Evict price cache
curl -s -X DELETE http://localhost:8080/api/market-data/price/AAPL/cache \
  -H "Authorization: Bearer <Token>" -w "\n%{http_code}"

# SSE live price stream (Ctrl+C to stop)
curl -N http://localhost:8080/api/market-data/stream/AAPL \
  -H "Authorization: Bearer <Token>"
```

---

## Phase 5: Portfolio & Stock-Back Engine

### Get Portfolio

```bash
# Get user's portfolio (all holdings)
curl -s http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <Token>"
```

### Add Holding (directly, e.g. for testing or manual entry)

```bash
# Add AAPL holding: 10 shares at $195.50
curl -s -X POST http://localhost:8080/api/portfolio/holdings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":10,"price":195.50}'

# Add NKE holding: 5 shares at $78.25
curl -s -X POST http://localhost:8080/api/portfolio/holdings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"NKE","quantity":5,"price":78.25}'

# Add more AAPL (weighted average price recalculated)
curl -s -X POST http://localhost:8080/api/portfolio/holdings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":5,"price":200.00}'
```

### Trade (BUY / SELL)

```bash
# BUY 3 shares of MSFT at $420.00
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"MSFT","quantity":3,"price":420.00,"tradeType":"BUY"}'

# SELL 2 shares of AAPL at $210.00
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":2,"price":210.00,"tradeType":"SELL"}'

# SELL more than owned — should return 400 (InsufficientSharesException)
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":9999,"price":210.00,"tradeType":"SELL"}'

# Invalid trade type — should return 400
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":1,"price":210.00,"tradeType":"INVALID"}'

# SELL non-existent holding — should return 404
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"ZZZZ","quantity":1,"price":10.00,"tradeType":"SELL"}'
```

### Sell to Spend (fund an order by selling stock)

```bash
# First, place an order (must be in CREATED status)
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-sellspend-001","shippingAddress":"456 Test Lane","paymentMethod":"STOCK"}'

# Sell to Spend: sell 5 shares of AAPL at $200 to fund order (orderId from above)
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":5,"pricePerShare":200.00,"orderId":2}'

# Sell to Spend with insufficient proceeds — should return 400
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"NKE","quantity":1,"pricePerShare":0.01,"orderId":3}'

# Sell to Spend on already confirmed order — should return 400
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":1,"pricePerShare":200.00,"orderId":2}'
```

### Sell to Spend — Saga Mode Verification

```bash
# ═══════════════════════════════════════════════════════════════════════
# SAGA TEST DATA SETUP (run in order — each step depends on the previous)
# ═══════════════════════════════════════════════════════════════════════

# Prerequisites:
# 1. equitycart.sell-to-spend.strategy=saga in application.yml
# 2. Docker: PostgreSQL, Redis, Kafka running
# 3. App started fresh (tables auto-created by Hibernate)

# ──────────────────────────────────────────────────────────────────────
# STEP 1: Login as ADMIN (for product/brand setup)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@equitycart.com","password":"Test@1234"}'
# → Save the accessToken as <AdminToken>

# ──────────────────────────────────────────────────────────────────────
# STEP 2: Create Category + Brand + Product (needed for cart/order)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/categories \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <AdminToken>" \
  -d '{"name":"Electronics","description":"Electronic devices"}'

curl -s -X POST http://localhost:8080/api/brands \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <AdminToken>" \
  -d '{"name":"Apple","description":"Apple Inc."}'

curl -s -X POST http://localhost:8080/api/brand-ticker-mappings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <AdminToken>" \
  -d '{"brandId":1,"tickerSymbol":"AAPL","stockBackPercentage":5.0}'

curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <AdminToken>" \
  -d '{"name":"iPhone 15","description":"Latest iPhone","sku":"AAPL-IP15","price":999.00,"stockQuantity":100,"brandId":1,"categoryId":1}'

# ──────────────────────────────────────────────────────────────────────
# STEP 3: Login as CUSTOMER (for the saga flow)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"customer@equitycart.com","password":"Test@1234"}'
# → Save the accessToken as <Token>

# ──────────────────────────────────────────────────────────────────────
# STEP 4: Buy AAPL shares (create holdings for sell-to-spend)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":10,"pricePerShare":200.00,"tradeType":"BUY"}'
# → Verify: 200 OK, holding with 10 shares of AAPL at $200

# Confirm holdings exist:
curl -s http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <Token>"

# ──────────────────────────────────────────────────────────────────────
# STEP 5: Add item to cart + Place order (creates CREATED order)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/cart/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId":1,"productName":"iPhone 15","quantity":1,"price":999.00}'

curl -s -X POST http://localhost:8080/api/order \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"idempotencyKey":"saga-test-001","shippingAddress":"123 Saga Lane","paymentMethod":"STOCK"}'
# → Note the orderId from response (e.g., orderId=1)
# → Order is in CREATED status — ready for sell-to-spend

# ──────────────────────────────────────────────────────────────────────
# STEP 6: SAGA HAPPY PATH — Sell to Spend
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":5,"pricePerShare":200.00,"orderId":1}'
# Expected: 200 OK
# Response: {"orderId":1,"tickerSymbol":"AAPL","quantity":5,"saleProceeds":1000.00,"orderStatus":"CONFIRMED"}
# → 5 shares × $200 = $1000 proceeds ≥ $999 order total ✅

# ──────────────────────────────────────────────────────────────────────
# STEP 7: VERIFY — Check saga table, holdings, ledger, order status
# ──────────────────────────────────────────────────────────────────────

# 7a. Check portfolio (should have 5 AAPL shares remaining)
curl -s http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <Token>"

# 7b. Check order status (should be CONFIRMED)
curl -s http://localhost:8080/api/order/1 \
  -H "Authorization: Bearer <Token>"

# 7c. PostgreSQL — Saga table
# SELECT saga_id, order_id, status, failure_reason, ticker_symbol, quantity FROM sell_to_spend_sagas;
# Expected: status = COMPLETED, failure_reason = NULL

# 7d. PostgreSQL — Outbox events for saga lifecycle
# SELECT event_type, aggregate_type, payload FROM outbox_events WHERE aggregate_type = 'SellToSpendSaga' ORDER BY created_at;
# Expected: SAGA_STARTED, SAGA_STEP_COMPLETED, SAGA_STEP_COMPLETED, SAGA_STEP_COMPLETED, SAGA_COMPLETED

# 7e. PostgreSQL — Ledger entries
# SELECT debit_account, credit_account, amount, reference_type, description FROM ledger_entries WHERE reference_type = 'SELL_TO_SPEND';
# Expected: CASH/HOLDING_ASSET, amount=1000.00

# 7f. Kafka — Saga lifecycle events (if Kafka running)
# docker exec kafka kafka-console-consumer.sh --topic sell-to-spend-saga --from-beginning --bootstrap-server localhost:9092

# ═══════════════════════════════════════════════════════════════════════
# SAGA ERROR SCENARIOS
# ═══════════════════════════════════════════════════════════════════════

# ──────────────────────────────────────────────────────────────────────
# ERROR 1: Insufficient proceeds (validation fails before saga starts)
# ──────────────────────────────────────────────────────────────────────
# First, place another order
curl -s -X POST http://localhost:8080/api/cart/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId":1,"productName":"iPhone 15","quantity":1,"price":999.00}'

curl -s -X POST http://localhost:8080/api/order \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"idempotencyKey":"saga-test-002","shippingAddress":"456 Error Ave","paymentMethod":"STOCK"}'
# → Note orderId (e.g., orderId=2)

# Try with proceeds < order total (1 share × $200 = $200 < $999)
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":1,"pricePerShare":200.00,"orderId":2}'
# Expected: 400 Bad Request — "Sale proceeds (200.00) do not cover order total (999.00)"
# No saga created — validation fails BEFORE orchestrator is called

# ──────────────────────────────────────────────────────────────────────
# ERROR 2: Already confirmed order (validation fails)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":5,"pricePerShare":200.00,"orderId":1}'
# Expected: 400 — "Order is not in a valid state for sell-to-spend: CONFIRMED"

# ──────────────────────────────────────────────────────────────────────
# ERROR 3: Insufficient shares (saga step 1 fails — no compensation needed)
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":100,"pricePerShare":200.00,"orderId":2}'
# Expected: 500 — InsufficientSharesException (only 5 shares remain)
# Saga created in FAILED state (step 1 failed, nothing to compensate)
# SELECT * FROM sell_to_spend_sagas WHERE status = 'FAILED';

# ──────────────────────────────────────────────────────────────────────
# COMPARISON: Switch to transactional mode
# ──────────────────────────────────────────────────────────────────────
# Change application.yml: equitycart.sell-to-spend.strategy=transactional
# Restart app, run same test — observe:
#   - No saga table entries (doesn't use saga entity)
#   - Same end result (order CONFIRMED, shares reduced)
#   - On failure: DB state NEVER changes (atomic rollback vs compensation)
```

### Sell to Spend — Refund Flow (Stock Restoration)

> **Pre-requisite:** A completed sell-to-spend saga exists (run happy path above first).

```bash
# ──────────────────────────────────────────────────────────────────────
# REFUND FLOW: Order paid via STOCK gets refunded → shares restored
# ──────────────────────────────────────────────────────────────────────

# 1. Verify current holdings BEFORE refund
curl -s http://localhost:8080/api/portfolio/holdings \
  -H "Authorization: Bearer <Token>"
# Note current AAPL share count (should be reduced from sell-to-spend)

# 2. Verify saga exists in COMPLETED state
# SELECT * FROM sell_to_spend_sagas WHERE order_id = <orderId> AND status = 'COMPLETED';
# Note: is_refunded should be false

# 3. Transition order to REFUNDED (as ADMIN)
curl -s -X PUT http://localhost:8080/api/orders/<orderId>/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Admin-Token>" \
  -d '{"status":"REFUNDED"}'
# Expected: 200 OK

# 4. Verify outbox event was written
# SELECT * FROM outbox_events WHERE event_type = 'ORDER_REFUNDED' ORDER BY created_at DESC LIMIT 1;
# Should show: topic=order-refunded, payload contains paymentMethod="STOCK"

# 5. Wait for OutboxPoller to publish to Kafka (~5 seconds)
# Then verify holdings are restored
curl -s http://localhost:8080/api/portfolio/holdings \
  -H "Authorization: Bearer <Token>"
# Expected: AAPL shares restored to pre-sell quantity

# 6. Verify saga marked as refunded
# SELECT is_refunded FROM sell_to_spend_sagas WHERE order_id = <orderId>;
# Expected: true

# 7. Verify ledger reversal entry
# SELECT * FROM ledger_entries WHERE reference_type = 'SELL_TO_SPEND_REVERSAL' ORDER BY created_at DESC LIMIT 1;
# Expected: debit=HOLDING_ASSET, credit=CASH, amount = original saleProceeds

# 8. Idempotency: refund again should be a no-op
# Manually insert another outbox event or re-trigger — consumer logs "Refund already processed"

# ──────────────────────────────────────────────────────────────────────
# EDGE CASE: Non-STOCK payment refund (should be skipped)
# ──────────────────────────────────────────────────────────────────────
# Create an order with paymentMethod="CARD", complete it, then refund
# Consumer should log "Non-STOCK payment method for orderId=X, skipping refund processing"
# No holdings changes, no ledger entries
```

### Rewards

```bash
# Get stock-back rewards for the authenticated user
curl -s http://localhost:8080/api/portfolio/rewards \
  -H "Authorization: Bearer <Token>"
```

### Analytics

```bash
# Get portfolio analytics (cost basis, per-holding weight, reward summary)
curl -s http://localhost:8080/api/portfolio/analytics \
  -H "Authorization: Bearer <Token>"
```

### Event Sourcing — Portfolio Event Store (MongoDB)

> **Pre-requisites:** MongoDB container running on port 27017.
> Events are only captured for operations performed AFTER Step 12 was integrated.

```bash
# ──────────────────────────────────────────────────────────────────────
# EVENT TIMELINE — all portfolio events for the authenticated user
# ──────────────────────────────────────────────────────────────────────

# 1. Get full event timeline (ordered by sequenceNumber)
curl -s http://localhost:8080/api/portfolio/events \
  -H "Authorization: Bearer <Token>"

# 2. Filter by ticker
curl -s "http://localhost:8080/api/portfolio/events?ticker=AAPL" \
  -H "Authorization: Bearer <Token>"

# 3. Filter by time range (ISO-8601 Instant format)
curl -s "http://localhost:8080/api/portfolio/events?from=2026-05-27T00:00:00Z&to=2026-05-28T00:00:00Z" \
  -H "Authorization: Bearer <Token>"

# ──────────────────────────────────────────────────────────────────────
# PROJECTION — rebuild holdings from events (demonstrates event replay)
# ──────────────────────────────────────────────────────────────────────

# 4. Rebuild portfolio state entirely from events
curl -s http://localhost:8080/api/portfolio/events/projection \
  -H "Authorization: Bearer <Token>"
# Returns: {ticker → {tickerSymbol, quantity, averageBuyPrice}} computed from event replay

# 5. Validate consistency (compare projected vs PostgreSQL state)
curl -s http://localhost:8080/api/portfolio/events/projection/validate \
  -H "Authorization: Bearer <Token>"
# Returns: {ticker → "MATCH" or "MISMATCH: projected=..., actual=..."}
# Note: pre-Step-12 holdings will show MISMATCH (no historical events)

# ──────────────────────────────────────────────────────────────────────
# VERIFICATION FLOW — fresh operations for accurate validation
# ──────────────────────────────────────────────────────────────────────

# 6. Execute a BUY trade (creates SHARES_PURCHASED event)
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"MSFT","quantity":5,"price":400.00,"tradeType":"BUY"}'

# 7. Execute a SELL trade (creates SHARES_SOLD event)
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"MSFT","quantity":2,"price":410.00,"tradeType":"SELL"}'

# 8. Check events — should show SHARES_PURCHASED then SHARES_SOLD for MSFT
curl -s "http://localhost:8080/api/portfolio/events?ticker=MSFT" \
  -H "Authorization: Bearer <Token>"

# 9. Validate — MSFT should show MATCH (3 shares at weighted avg)
curl -s http://localhost:8080/api/portfolio/events/projection/validate \
  -H "Authorization: Bearer <Token>"
```

### Data Verification — MongoDB Event Store

```bash
# Connect to MongoDB
docker exec -it mongodb mongosh

# Inside mongosh:
use equitycart
db.portfolio_events.find()                                    # All events
db.portfolio_events.find({userId: 1}).sort({sequenceNumber: 1})  # User 1 timeline
db.portfolio_events.find({eventType: "SHARES_PURCHASED"})     # Filter by type
db.portfolio_events.countDocuments()                           # Total event count
db.portfolio_events.getIndexes()                              # Verify indexes created
```

---

## Phase 6: Event-Driven Architecture (Kafka + Outbox + DLQ)

> **Pre-requisites:** Kafka container running (see Docker section above).
> Ensure Phase 1 data exists (categories, brands, brand-ticker mappings, products).
> Use ADMIN token for order status transitions, CUSTOMER token for placing orders.

### Setup: Ensure Brand-Ticker Mappings Exist

```bash
# Verify Apple → AAPL mapping exists (should return data if Phase 1 was run)
curl -s http://localhost:8080/api/brand-ticker-mappings/brand/1 \
  -H "Authorization: Bearer <Token>"

# Verify Nike → NKE mapping exists
curl -s http://localhost:8080/api/brand-ticker-mappings/brand/2 \
  -H "Authorization: Bearer <Token>"
```

### Happy Path: Order Delivered → Stock-Back Reward Created

```bash
# 1. Add items to cart (as CUSTOMER)
curl -s -X POST http://localhost:8080/api/cart/user1/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId": 1, "quantity": 1, "price": 899.99}'

# 2. Place order
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-kafka-test-001","shippingAddress":"789 Kafka Lane","paymentMethod":"UPI"}'

# 3. Transition order to DELIVERED (note the orderId from step 2)
curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"CONFIRMED"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"PROCESSING"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"DELIVERED"}'

# 4. Wait ~10 seconds (5s poller + consumer processing)

# 5. Verify reward created (PENDING status)
curl -s http://localhost:8080/api/portfolio/rewards \
  -H "Authorization: Bearer <Token>"

# 6. Wait 60+ seconds for vesting job, then check portfolio for new holding
curl -s http://localhost:8080/api/portfolio \
  -H "Authorization: Bearer <Token>"
```

### Multi-Ticker Reward: Apple + Nike in Same Order

```bash
# 1. Add Apple product and Nike product to cart
curl -s -X POST http://localhost:8080/api/cart/user1/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId": 1, "quantity": 1, "price": 899.99}'

curl -s -X POST http://localhost:8080/api/cart/user1/items \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"productId": 2, "quantity": 1, "price": 149.50}'

# 2. Place order
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-multi-ticker-001","shippingAddress":"Multi Ticker Ave","paymentMethod":"UPI"}'

# 3. Transition all the way to DELIVERED
curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"CONFIRMED"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"PROCESSING"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}'

curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"DELIVERED"}'

# 4. Wait ~10 seconds, then verify TWO reward rows (AAPL + NKE)
curl -s http://localhost:8080/api/portfolio/rewards \
  -H "Authorization: Bearer <Token>"
```

### Idempotency: Duplicate Event Does Not Create Duplicate Reward

```bash
# The outbox poller guarantees at-least-once delivery.
# To simulate: manually re-publish the same event from outbox.
# Easiest verification: check that delivering the same order again
# (already DELIVERED) returns an error — status machine prevents it.

# Or: query stock_back_rewards and verify unique constraint holds:
# SELECT * FROM stock_back_rewards WHERE order_id = <orderId>;
# Should show exactly 1 row per ticker, never duplicates.
```

### Return Cancellation: PENDING Reward → CANCELLED

```bash
# 1. Place + deliver an order (reuse steps above with new idempotencyKey)
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"order-return-test-001","shippingAddress":"Return St","paymentMethod":"UPI"}'

# ... transition to DELIVERED (same 4 PATCH calls as above) ...

# 2. Wait for reward to be created (~10s), verify it's PENDING
curl -s http://localhost:8080/api/portfolio/rewards \
  -H "Authorization: Bearer <Token>"

# 3. Initiate return
curl -s -X PATCH http://localhost:8080/api/order/<orderId>/return \
  -H "Authorization: Bearer <Token>"

# 4. Transition to RETURNED
curl -s -X PATCH http://localhost:8080/api/order/<orderId>/status \
  -H "Authorization: Bearer <Token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"RETURNED"}'

# 5. Wait ~10 seconds, verify reward is now CANCELLED
curl -s http://localhost:8080/api/portfolio/rewards \
  -H "Authorization: Bearer <Token>"
```

### No Ticker Mapping: Brand Without Stock-Back

```bash
# Create a brand without a BrandTickerMapping, create a product under it,
# order that product → deliver → verify NO reward is created (no error either)
# The consumer simply skips items whose brand has no ticker mapping.
```

### Kafka CLI Verification

> **NOTE:** If using Git Bash on Windows, paths like `/opt/kafka/bin/` get mangled.
> Use **PowerShell** or **Docker Desktop terminal** for these commands.

```bash
# List all topics (should include order-delivered, order-returned, and .DLT variants)
# PowerShell (recommended on Windows):
docker exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# Consume from order-delivered topic (see published events)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --topic order-delivered `
  --from-beginning `
  --bootstrap-server localhost:9092

# Consume from order-returned topic
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --topic order-returned `
  --from-beginning `
  --bootstrap-server localhost:9092

# Check Dead Letter Topic (should be empty unless errors occurred)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --topic order-delivered.DLT `
  --from-beginning `
  --bootstrap-server localhost:9092

# Delete a topic (useful for clearing poison messages)
docker exec kafka /opt/kafka/bin/kafka-topics.sh --delete --topic order-delivered --bootstrap-server localhost:9092
docker exec kafka /opt/kafka/bin/kafka-topics.sh --delete --topic order-returned --bootstrap-server localhost:9092

# Describe a topic (partitions, replication, config)
docker exec kafka /opt/kafka/bin/kafka-topics.sh --describe --topic order-delivered --bootstrap-server localhost:9092

# List consumer groups
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --list --bootstrap-server localhost:9092

# Check consumer group lag (how far behind the consumer is)
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh `
  --describe --group equitycart-reward-group `
  --bootstrap-server localhost:9092
```

### Debezium CDC Verification

```bash
# Check connector status (should show RUNNING for both connector and task)
curl -s http://localhost:8083/connectors/equitycart-outbox-connector/status | python -m json.tool

# List all registered connectors
curl -s http://localhost:8083/connectors

# Register the outbox connector (first time setup)
curl -s -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @equitycart/docker/debezium/register-connector.json

# Delete connector (for re-registration after config changes)
curl -s -X DELETE http://localhost:8083/connectors/equitycart-outbox-connector

# Restart a failed connector task
curl -s -X POST http://localhost:8083/connectors/equitycart-outbox-connector/tasks/0/restart

# Check Debezium container logs
docker logs debezium --tail 50

# Check Kafka Connect worker status
curl -s http://localhost:8083/ | python -m json.tool

# Verify PostgreSQL WAL level (must be 'logical' for CDC)
# In DBeaver or psql:
SHOW wal_level;

# Check active replication slots (Debezium creates one)
SELECT slot_name, plugin, active FROM pg_replication_slots;
```

### Data Verification — Outbox Table (PostgreSQL)

```bash
# Connect to PostgreSQL
docker exec -it postgres psql -U postgres -d equitycart

# Inside psql:
SELECT id, aggregate_type, aggregate_id, event_type, status, published_at, created_at
  FROM outbox_events ORDER BY created_at DESC;

# POLLING MODE: Verify all rows have status = 'SENT' and published_at is populated
SELECT * FROM outbox_events WHERE status = 'PENDING';  -- should be empty

# CDC MODE: All rows stay PENDING (expected — Debezium reads WAL, doesn't update rows)
# Verify events ARE being captured by checking Kafka topics (not the status column)
SELECT count(*) FROM outbox_events WHERE status = 'PENDING';

# CDC MODE: Cleanup old rows (optional — prevents table growth)
DELETE FROM outbox_events WHERE created_at < NOW() - INTERVAL '7 days';

# Check stock_back_rewards
SELECT id, order_id, user_id, ticker_symbol, shares_earned, dollar_value, status, vesting_date
  FROM stock_back_rewards ORDER BY created_at DESC;
```

---

## Data Verification — Redis CLI

```bash
# Connect to Redis
docker exec -it redis redis-cli

# Inside Redis CLI:
KEYS *                    # List all keys
GET price:AAPL            # Get cached stock price for AAPL
TTL price:AAPL            # Check remaining TTL in seconds
KEYS cart:*               # List all cart keys
GET cart:user1            # Get user1's cart data
```

---

## Data Verification — MongoDB CLI

```bash
# Connect to MongoDB
docker exec -it mongodb mongosh

# Inside mongosh:
use equitycart
db.price_history.find()                          # All price history documents
db.price_history.find({symbol: "AAPL"})          # Filter by symbol
db.price_history.countDocuments()                 # Total count
db.price_history.find().sort({fetchedAt: -1})     # Newest first
db.price_history.getIndexes()                     # Show indexes (TTL index on fetchedAt)
```

---

## Data Verification — PostgreSQL CLI

```bash
# Connect to PostgreSQL
docker exec -it postgres psql -U postgres -d equitycart

# Inside psql:
SELECT * FROM portfolios;
SELECT * FROM holdings WHERE portfolio_id = 1;
SELECT * FROM stock_back_rewards WHERE user_id = 1;
SELECT * FROM ledger_entries ORDER BY created_at DESC LIMIT 10;
SELECT * FROM orders WHERE user_id = 1 ORDER BY created_at DESC;
```

---

## Phase 7: Notification Service (Observer Pattern via Kafka + Strategy Pattern)

> **Pre-requisites:** Kafka container running, app started.
> Notifications are published as a side-effect of trades, vesting, and saga flows.
> Default channel: `LOG` (console output). Switch via `equitycart.notification.channel` property.

### Docker — MailHog (required for EMAIL channel testing)

```bash
# Start MailHog (SMTP trap: catches all outgoing mail in a web UI)
docker run -d \
  --name mailhog \
  -p 1025:1025 \
  -p 8025:8025 \
  mailhog/mailhog

# SMTP on port 1025 (Spring Mail connects here)
# Web UI on port 8025 (view caught emails in browser)
# Open http://localhost:8025 to see trapped emails
```

### Trigger Notifications (via existing flows)

```bash
# ──────────────────────────────────────────────────────────────────────
# 1. TRADE_EXECUTED notification — execute a BUY or SELL trade
# ──────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/portfolio/trade \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"MSFT","quantity":3,"price":420.00,"tradeType":"BUY"}'
# → Check console for: [NOTIFICATION] userId=1, subject=Trade Executed: ...

# ──────────────────────────────────────────────────────────────────────
# 2. REWARD_VESTED notification — wait for vesting scheduler (60s cycle)
# ──────────────────────────────────────────────────────────────────────
# Pre-req: deliver an order so a PENDING reward exists, wait >30 days (or manually adjust vestingDate in DB)
# UPDATE stock_back_rewards SET vesting_date = NOW() - INTERVAL '1 day' WHERE status = 'PENDING';
# Wait 60s for scheduler → notification appears in console/email

# ──────────────────────────────────────────────────────────────────────
# 3. SELL_TO_SPEND_COMPLETED notification — run successful saga
# ──────────────────────────────────────────────────────────────────────
# (Requires saga mode: equitycart.sell-to-spend.strategy=saga)
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":5,"pricePerShare":200.00,"orderId":<orderId>}'
# → SELL_TO_SPEND_COMPLETED notification dispatched

# ──────────────────────────────────────────────────────────────────────
# 4. SELL_TO_SPEND_FAILED notification — trigger saga compensation
# ──────────────────────────────────────────────────────────────────────
# Sell more shares than owned (step 1 fails) — or use an already-used orderId for step 3 failure
curl -s -X POST http://localhost:8080/api/portfolio/sell-to-spend \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <Token>" \
  -d '{"tickerSymbol":"AAPL","quantity":9999,"pricePerShare":200.00,"orderId":<orderId>}'
# → SELL_TO_SPEND_FAILED notification dispatched in compensate() finally block
```

### Query Notification History (REST API)

```bash
# Get all notifications for authenticated user (most recent first)
curl -s http://localhost:8080/api/notifications \
  -H "Authorization: Bearer <Token>"

# Filter by type
curl -s "http://localhost:8080/api/notifications?type=TRADE_EXECUTED" \
  -H "Authorization: Bearer <Token>"

curl -s "http://localhost:8080/api/notifications?type=REWARD_VESTED" \
  -H "Authorization: Bearer <Token>"

curl -s "http://localhost:8080/api/notifications?type=SELL_TO_SPEND_COMPLETED" \
  -H "Authorization: Bearer <Token>"

curl -s "http://localhost:8080/api/notifications?type=SELL_TO_SPEND_FAILED" \
  -H "Authorization: Bearer <Token>"
```

### Kafka Topic Verification

```bash
# Consume from portfolio-notification topic (see published events)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --topic portfolio-notification \
  --from-beginning \
  --bootstrap-server localhost:9092

# Check consumer group lag
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --describe --group equitycart-notification-group \
  --bootstrap-server localhost:9092
```

### Switch Notification Channel

```yaml
# In application.yml — change channel and restart:
equitycart:
  notification:
    channel: EMAIL     # or WEBHOOK or LOG
```

```bash
# After switching to EMAIL, execute a trade, then check MailHog:
# Open http://localhost:8025 → email should appear

# After switching to WEBHOOK, execute a trade, check webhook receiver logs
# (Start a simple listener: python -m http.server 9999, or use webhook.site)
```

### Data Verification — PostgreSQL (notification_logs table)

```bash
# Connect to PostgreSQL
docker exec -it postgres psql -U postgres -d equitycart

# Inside psql:
SELECT id, user_id, notification_type, notification_channel, notification_status, subject, created_at
  FROM notification_logs ORDER BY created_at DESC;

# Count by status
SELECT notification_status, COUNT(*) FROM notification_logs GROUP BY notification_status;

# Count by type
SELECT notification_type, COUNT(*) FROM notification_logs GROUP BY notification_type;
```

---

## Phase 8: Keycloak Identity Provider (OAuth2/OIDC)

> **Pre-requisites:** Keycloak running via docker-pets.yml (port 8180).
> Admin console: http://localhost:8180 (admin/admin → select "equitycart" realm).
> First boot creates all realm config from equitycart-realm.json automatically.

### Keycloak Admin Console

```bash
# Verify Keycloak is up (OIDC discovery — returns all endpoint URLs)
curl -s http://localhost:8180/realms/equitycart/.well-known/openid-configuration | python -m json.tool

# Verify JWKS endpoint (public keys for RS256 token verification)
curl -s http://localhost:8180/realms/equitycart/protocol/openid-connect/certs | python -m json.tool
```

### Get Token via ROPC (Resource Owner Password Credentials)

> Direct Access Grants (ROPC) — sends username+password directly. For testing only, NOT production.

```bash
# Login as CUSTOMER (customer1)
curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=customer1" \
  -d "password=Test@1234"

# Login as SELLER (seller1)
curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=seller1" \
  -d "password=Test@1234"

# Login as ADMIN (admin1)
curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=admin1" \
  -d "password=Test@1234"
```

### Get Service Token via Client Credentials

> Machine-to-machine flow — no user involved. Returns token with SERVICE role.

```bash
curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=equitycart-services" \
  -d "client_secret=services-secret"
```

### Decode & Verify Token Structure

```bash
# Extract access_token from response, then decode payload (base64 middle segment)
# Using bash:
TOKEN="<paste access_token here>"
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | python -m json.tool

# Expected structure:
# {
#   "sub": "<uuid>",
#   "roles": ["CUSTOMER"],        ← flattened by roles-mapper (NOT nested in realm_access)
#   "userId": 1,                   ← injected by userId-mapper (maps to database ID)
#   "iss": "http://localhost:8180/realms/equitycart",
#   "exp": <timestamp>,
#   "iat": <timestamp>,
#   ...
# }
```

### Keycloak Configuration Management

```bash
# Re-import realm after JSON changes (drop keycloak DB + restart container):
docker exec postgres psql -U postgres -c "DROP DATABASE keycloak; CREATE DATABASE keycloak;"
docker compose -f docker/docker-pets.yml restart keycloak
# Wait for OIDC discovery to respond (~30-60s)

# Alternative: nuclear reset (all volumes wiped — recreates ALL databases)
cd equitycart
docker compose -f docker/docker-pets.yml down -v
sh docker/start-pets.sh

# View Keycloak logs (check for import errors, warnings)
docker logs keycloak --tail 100

# Create keycloak DB manually (if init-db.sh didn't run — volume already existed)
docker exec postgres psql -U postgres -c "CREATE DATABASE keycloak;"
```

### Step 6 Verification — OAuth2 Resource Server (product-service in oauth2 mode)

```bash
# 1. Get Keycloak token for customer1
TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=customer1" \
  -d "password=Test@1234" | jq -r .access_token)

# 2. Call product-service through gateway with Keycloak token (RS256)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/products | python -m json.tool
# Expected: 200 OK with products list (product-service validates via JWKS)

# 3. Call without token (should get 401)
curl -s http://localhost:8080/api/products
# Expected: 401 Unauthorized

# 4. Call order-service (still mode=custom) with OLD custom token
# First get custom token:
CUSTOM_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"customer1@equitycart.com","password":"Test@1234"}' | jq -r .accessToken)
# Then use it on order-service:
curl -s -H "Authorization: Bearer $CUSTOM_TOKEN" http://localhost:8080/api/order | python -m json.tool
# Expected: 200 OK (order-service still validates HS256 via custom filter)

# 5. Verify Keycloak token does NOT work on custom-mode services
# (because they validate HS256 signature, but Keycloak token is RS256)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/order
# Expected: 401 (signature mismatch — order-service expects HS256, token is RS256)

# 6. Get seller token and test RBAC on product-service
SELLER_TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=seller1" \
  -d "password=Test@1234" | jq -r .access_token)
# Test seller-only endpoint (if product creation requires SELLER role):
curl -s -X POST -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/products -d '{"name":"Test","price":100}'
# Expected: 200 (SELLER role present)

# 7. Verify CUSTOMER cannot create products (RBAC enforcement)
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/products -d '{"name":"Test","price":100}'
# Expected: 403 Forbidden (CUSTOMER lacks SELLER role)
```

### Useful Keycloak Admin REST API

```bash
# Get realm info
curl -s -H "Authorization: Bearer <admin_token>" \
  http://localhost:8180/admin/realms/equitycart | python -m json.tool

# List all users in realm
curl -s -H "Authorization: Bearer <admin_token>" \
  http://localhost:8180/admin/realms/equitycart/users | python -m json.tool

# Get admin token (from master realm)
curl -s -X POST http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin"
```

---

## Phase 8 — Security Hardening (Steps 6-10)

### Phase 8 Steps 1-5: Completed in Previous Phases
- Step 1-2: Commons JWT library + all services enforce auth
- Step 3: Feign Authorization propagation  
- Step 4: Gateway JwtValidationGatewayFilter (now replaced by Step 7)
- Step 5: Keycloak Docker setup

### Phase 8 Step 6: OAuth2 Resource Server (Keycloak JWKS Validation)

**Prerequisite:** Keycloak running + services configured with mode=oauth2

**1. Verify Keycloak realm exists:**

```bash
curl -s http://localhost:8180/realms/equitycart | jq '.realm, .enabled'
# Expected: "equitycart", true
```

**2. Get JWKS endpoint (cached public keys):**

```bash
curl -s http://localhost:8180/realms/equitycart/protocol/openid-connect/certs | jq '.keys[0] | {kty, alg, kid}'
# Expected: {"kty":"RSA","alg":"RS256","kid":"..."}
```

**3. Get access token via Resource Owner Password Credentials:**

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=<client-secret-from-realm-json>" \
  -d "username=customer1" \
  -d "password=password" | jq -r '.access_token')

echo $TOKEN
```

**4. Decode token (verify RS256 structure, userId, roles):**

```bash
echo $TOKEN | cut -d. -f2 | base64 -d | jq .
# Expected: { "sub": "...", "userId": "1", "roles": ["CUSTOMER"], "iss": "...", "exp": ..., ... }
```

**5. Test API with Keycloak token (through gateway):**

```bash
curl -s -X GET http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" | jq '.content[0] | {id, name, price}'
```

**6. Test product-service directly (verify mode=oauth2):**

```bash
curl -s -X GET http://localhost:8082/api/products \
  -H "Authorization: Bearer $TOKEN" | jq '.content[0] | {id, name, price}'
# Should succeed without WARN from JwtTokenValidatorImpl
```

**7. Test missing token → 401:**

```bash
curl -s -X GET http://localhost:8080/api/products | jq '.error'
# Expected: "Unauthorized" with 401 status
```

---

### Phase 8 Step 7: Gateway Token Relay (Reactive OAuth2)

**1. Verify SecurityWebFilterChain is active:**

```bash
docker logs docker-api-gateway-1 | grep -i "websecurity\|oauth2"
```

**2. Verify TokenRelay propagates token:**

```bash
TOKEN=<keycloak-token>
curl -v -X GET http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" 2>&1 | grep "< HTTP"

# Check downstream logs
docker logs docker-product-service-1 | tail -20 | grep -i "authenticated"
```

**3. Verify token propagates through Feign calls:**

```bash
curl -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "order-'$(date +%s)'",
    "shippingAddress": "123 Main St",
    "paymentMethod": "CARD",
    "items": [{"productId": 1, "quantity": 1}]
  }' | jq '.orderId'

# Verify in order-service logs: token forwarded to product-service
docker logs docker-order-service-1 | tail -30 | grep -i "feign\|propagated"
```

---

### Phase 8 Step 8: Rate Limiting (Redis Token Bucket)

**1. Verify RateLimiterConfig loaded:**

```bash
docker logs docker-api-gateway-1 | grep -i "ratelimit"
```

**2. Check rate limiter keys in Redis (verify key format):**

```bash
docker exec -it redis redis-cli
KEYS "request_rate_limiter.*"
# Expected keys: request_rate_limiter.1.tokens, request_rate_limiter.1.timestamp
# (key format uses dots, not colons — Spring Gateway Lua script convention)
GET "request_rate_limiter.1.tokens"
# Expected: integer 0-20 (current tokens in bucket for userId=1)
```

**3. Test rate limiting — make 25 requests rapidly:**

```bash
TOKEN=<keycloak-token>

for i in {1..25}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/products
  sleep 0.05
done

# Expected: Requests 1-20 → 200 OK, Requests 21-25 → 429 Too Many Requests
```

**4. Wait for bucket replenishment (10 req/sec):**

```bash
sleep 2
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/products
# Expected: 200 OK (bucket refilled)
```

---

### Phase 8 Step 9: OWASP Security Headers

**Context:** SecurityHeadersGlobalFilter runs at LOWEST_PRECEDENCE. Requires `@Component` to be discovered by Spring Cloud Gateway. All 6 headers must appear on EVERY response (authenticated or not, success or error).

**1. Verify ALL 6 OWASP headers present:**

```bash
curl -s -I http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" \
  | grep -iE "x-content-type-options|x-frame-options|strict-transport-security|content-security-policy|referrer-policy|permissions-policy"

# Expected output (all 6 lines):
# x-content-type-options: nosniff
# x-frame-options: DENY
# strict-transport-security: max-age=31536000; includeSubDomains
# content-security-policy: default-src 'self'
# referrer-policy: strict-origin-when-cross-origin
# permissions-policy: camera=(), microphone=(), geolocation=()
```

**2. Verify headers present even on 401 (unauthenticated):**

```bash
curl -s -I http://localhost:8080/api/products \
  | grep -iE "x-frame-options|x-content-type"
# Expected: headers still present even on rejected requests
```

**3. Verify bean is registered (if headers missing, check this first):**

```bash
curl -s http://localhost:8080/actuator/beans \
  | jq '.contexts[].beans | keys[] | select(contains("securityHeaders"))'
# Expected: "securityHeadersGlobalFilter"
# If absent: @Component annotation missing from SecurityHeadersGlobalFilter
```

**4. Verify secrets NOT hardcoded (12-factor App Factor III):**

```bash
grep -r "jwt.secret:" equitycart-config/
# Expected: only ${JWT_SECRET:default-base64-value}, no bare secrets
```

---

### Phase 8 Step 10: Full E2E Test

**Test 1: Invalid token → 401**

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer invalid.token" \
  http://localhost:8080/api/products
# Expected: 401
```

**Test 2: CUSTOMER on SELLER endpoint → 403**

```bash
TOKEN=<customer-token>
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"test","sku":"TEST123","price":9.99,"stockQuantity":10,"categoryId":1,"brandId":1}'
# Expected: 403 (RBAC enforced)
```

**Test 3: Full flow — Register → Login → Browse → Cart → Order**

```bash
# 1. Register
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"Test@1234567"}' | jq '.accessToken' > /tmp/token1

# 2. Get Keycloak token
TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password&client_id=equitycart-gateway&client_secret=<secret>&username=customer1&password=password" \
  | jq -r '.access_token')

# 3. Browse products
curl -s -X GET http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" | jq '.totalElements'

# 4. Add to cart
curl -s -X POST http://localhost:8080/api/cart/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}' | jq '.items | length'

# 5. Place order
curl -s -X POST http://localhost:8080/api/order \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"e2e-'$(date +%s)'","shippingAddress":"123 Main","paymentMethod":"CARD"}' \
  | jq '{orderId: .orderId, status: .status, userId: .userId}'

echo "✓ E2E flow complete"
```

---

## Quick Phase 8 Verification Checklist

- [ ] Keycloak token is RS256 (decode shows alg:RS256)
- [ ] Gateway accepts Keycloak token (no RS256 rejection)
- [ ] Product-service oauth2 mode (no WARN logs)
- [ ] Rate limiter rejects at request 21+
- [ ] Security headers in responses
- [ ] Invalid token → 401
- [ ] Wrong role → 403
- [ ] E2E flow succeeds
