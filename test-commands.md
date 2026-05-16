# EquityCart — API Test Commands

> Consolidated curl commands for testing all phases.
> Replace `<Token>` with a valid JWT from `/api/auth/login`.

---

## Docker — Start Infrastructure

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

# Verify all containers are running
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# Stop all
docker stop postgres redis mongodb

# Start again (after stop)
docker start postgres redis mongodb

# Remove all (destroys data)
docker rm -f postgres redis mongodb
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
