# Progress Tracking

## Status: Phase 5 - Portfolio & Stock-Back Engine (FUNCTIONAL COMPLETE)

## Project: EquityCart
- Hybrid domain: E-Commerce + Stock Market
- Core loop: browse -> buy -> earn stock -> trade -> spend stock -> buy

## Saved Documents
- `learning-instructor-agent.md` — Agent system prompt
- `project-development-prompt.md` — Project vision, roles, learning areas
- `equitycart-roadmap.md` — Full 26-week roadmap with 10 phases
- `learning_log.md` — Roadblocks, concepts, interview questions per phase

## Phase 0: Foundation & Setup — COMPLETE (2025-04-09)
- [x] Finalized domain: Hybrid (E-Commerce + Stock Market = EquityCart)
- [x] Brainstormed features (consumer-facing + technical)
- [x] Created full project roadmap (10 phases, 26 weeks)
- [x] Defined tech stack (100% open-source)
- [x] Defined microservice boundaries
- [x] Gradle multi-module skeleton (root + 7 modules)
- [x] Root build.gradle with plugins, subprojects, dependencyManagement, BOM
- [x] settings.gradle with module-to-folder mappings
- [x] gradle.properties with centralized versions
- [x] commons/build.gradle (java-library)
- [x] 5 service build.gradle files (java-library + starter-web + starter-data-jpa)
- [x] app/build.gradle (spring-boot plugin + all module deps + postgres + mongodb)
- [x] EquityCartApplication.java (main class)
- [x] application.yml (PostgreSQL + MongoDB + server config)
- [x] BUILD SUCCESSFUL — all 7 modules compile cleanly

## Phase 1: User Service & Security — IN PROGRESS (started 2025-04-09)
### Design Completed
- [x] User entity design discussion (multiple entities vs monolith entity)
- [x] Roles decided: CUSTOMER, SELLER, ADMIN (many-to-many via UserRole table)
- [x] Entity split: User, UserProfile, Role, UserRole, KycDetail, RefreshToken, WalletAccount
- [x] Authentication architecture: JWT (Access + Refresh Token pattern)
- [x] Spring Security filter chain flow designed
- [x] All 6 query methods explained (Derived, JPQL, Native SQL, Criteria, Specifications, QueryDSL)
- [x] Security dependencies added to user-service build.gradle

### Implementation Completed
- [x] Step 2: Create entity classes — COMPLETE (2026-04-13)
  - BaseEntity in commons: @MappedSuperclass with id, createdAt, updatedAt + lifecycle callbacks
  - commons/build.gradle: added `api 'spring-boot-starter-data-jpa'` (api scope, not implementation)
  - User: lean auth entity (email, password, enabled, accountLocked)
  - Role: separate table (not enum) for multi-role support
  - UserRole: explicit join entity with composite unique constraint (user_id, role_id)
  - UserProfile: personal details separated from auth for SRP + performance
  - KycDetail: optional KYC with status enum (PENDING/VERIFIED/REJECTED)
  - RefreshToken: @ManyToOne to User (multiple sessions), revocable from DB
  - WalletAccount: BigDecimal(19,4) for financial precision
  - KycStatus enum in separate enums package
  - BUILD SUCCESSFUL — all modules compile cleanly
- [x] Step 3: Create Repository interfaces — COMPLETE (2026-04-15)
  - 7 repository interfaces in `user/src/main/java/com/equitycart/user/repository/`
  - UserRepository: findByEmail (login), existsByEmail (registration duplicate check)
  - RoleRepository: findByName (role lookup for registration)
  - UserRoleRepository: findByUserId (load roles for JWT)
  - RefreshTokenRepository: findByToken, findByUserIdAndRevokedFalse, deleteByUserId
  - UserProfileRepository, KycDetailRepository: basic CRUD for now
  - WalletAccountRepository: findByUserId (balance queries)
  - BUILD SUCCESSFUL
- [x] Step 4: Create AuthService (register, login, refresh) — COMPLETE (2026-04-20)
  - 4 DTOs as Java records: RegisterRequest, LoginRequest, AuthResponse, RefreshRequest
  - Validation: @NotBlank, @Email, @Size(min=8) on request DTOs
  - SecurityConfig: PasswordEncoder @Bean returning BCryptPasswordEncoder
  - AuthService interface + AuthServiceImpl (interface/impl split)
  - Constructor injection via @RequiredArgsConstructor + private final fields (no @Autowired)
  - register(): email duplicate check → hash password → save User → assign CUSTOMER role → create wallet → return tokens (placeholder)
  - login(): find by email → verify password with BCrypt.matches() → check enabled/locked → return tokens (placeholder)
  - refreshToken(): find by token → check revoked/expired → return tokens (placeholder)
  - @Transactional on register (3 writes must be atomic)
  - BUILD SUCCESSFUL

- [x] Step 5: Create JwtService + Wire into AuthService — COMPLETE (2026-04-20)
  - JwtService interface: generateAccessToken, generateRefreshToken, extractAllClaims, extractUserId, extractRoles, validateToken
  - JwtServiceImpl using JJWT 0.12.6: HMAC-SHA256 signing, base64-decoded secret key
  - generateAccessToken: userId as subject, roles as custom claim, configurable expiry via @Value
  - generateRefreshToken: opaque UUID (not JWT — stored in DB for revocation)
  - extractAllClaims: Jwts.parser().verifyWith().parseSignedClaims() — validates signature + expiry
  - validateToken: wraps extractAllClaims in try-catch (expired/tampered → false)
  - Removed isTokenExpired() — redundant because JJWT throws ExpiredJwtException during parsing
  - JWT config (secret, access-token-expiry, refresh-token-expiry) in app/application.yml
  - Wired JwtService into AuthServiceImpl — replaced all "TODO_ACCESS"/"TODO_REFRESH" placeholders
  - Helper method generateAuthAndRefreshTokens(): generates both tokens, saves RefreshToken entity to DB
  - Refresh token rotation in refreshToken(): revokes old token before issuing new pair
  - @Value for refresh-token-expiry (days) used in RefreshToken entity expiry calculation
  - Fixed: hardcoded expiry → config value, extractRoles data corruption, validateToken always-true, YAML location
  - BUILD SUCCESSFUL

- [x] Step 6: Create JwtAuthFilter — COMPLETE (2026-04-20)
  - JwtAuthFilter extends OncePerRequestFilter (runs exactly once per request)
  - Extracts "Bearer " token from Authorization header
  - Null check on header — unauthenticated requests pass through safely
  - Validates token via JwtService, extracts userId and roles
  - Converts roles to SimpleGrantedAuthority with "ROLE_" prefix (Spring Security convention)
  - Creates authenticated UsernamePasswordAuthenticationToken (3-arg constructor)
  - Sets authentication in SecurityContextHolder
  - @Component (not @Service — filter is infrastructure, not business logic)
  - Fixed: null header NPE, @Service→@Component, 2-arg→3-arg auth token constructor

- [x] Step 7: Create SecurityConfig — COMPLETE (2026-04-20)
  - Disables CSRF (not needed for JWT — CSRF protects cookie-based auth)
  - Session policy STATELESS (JWT is self-contained, no server-side session)
  - /api/auth/** permitAll (login/register/refresh accessible without token)
  - anyRequest().authenticated() (everything else requires valid JWT)
  - JwtAuthFilter registered before UsernamePasswordAuthenticationFilter
  - Lambda DSL used (non-lambda authorizeHttpRequests() is deprecated)
  - Fixed: missing final on field, old non-lambda DSL, single * wildcard, hardcoded URL instead of anyRequest()

- [x] Step 8: Create AuthController — COMPLETE (2026-04-20)
  - @RestController + @RequestMapping("/api/auth")
  - POST /register → 201 CREATED, POST /login → 200 OK, POST /refresh → 200 OK
  - Thin controller — delegates to AuthService, no business logic
  - No try-catch — exceptions will be handled by global @RestControllerAdvice (later)
  - Fixed: duplicate method names (all named register), duplicate path mapping (/register on two endpoints), removed catch-all error handling

- [x] Step 9: Test the application — COMPLETE (2026-04-21)
  - Build successful, app starts on port 8080
  - Fixed: database name case-sensitivity (PostgreSQL is case-sensitive)
  - Fixed: data.sql not running — needed `spring.sql.init.mode: always` (Spring Boot 2.5+ only runs data.sql for embedded DBs by default)
  - Fixed: data.sql running before table creation — needed `spring.jpa.defer-datasource-initialization: true`
  - Fixed: YAML indentation — `defer-datasource-initialization` was nested under `spring.data` instead of `spring.jpa`
  - All 3 endpoints tested: POST /register (201), POST /login (200), POST /refresh (200)
  - Verified: multiple active refresh tokens per user is acceptable for e-commerce (multi-device sessions)

- [x] Step 10: JSON-based DataSeeder — COMPLETE (2026-04-21)
  - CommandLineRunner reads `seedData/roles.json` from classpath
  - Jackson ObjectMapper deserializes to `List<RoleSeedData>` (record DTO)
  - Idempotent: checks `existsByName()` before inserting
  - Injected ObjectMapper (Spring-managed bean, not `new ObjectMapper()`)
  - Removed `data.sql` approach — DataSeeder replaces it (CommandLineRunner runs after full context load, no ordering tricks needed)
  - Cleaned up: removed `spring.sql.init.mode` and `defer-datasource-initialization` from YAML

- [x] Step 11: Global exception handler — COMPLETE (2026-04-23)
  - ErrorResponse record in commons/dto (status, error, message, timestamp)
  - 4 custom exceptions in commons/exception: ResourceNotFoundException (404), DuplicateResourceException (409), AuthenticationException (401), AccountDisabledException (403)
  - GlobalExceptionHandler in commons/handler with @RestControllerAdvice — one @ExceptionHandler per exception + catch-all for 500
  - Fixed: initial attempt put @RestControllerAdvice on exception classes (wrong — exceptions shouldn't handle themselves)
  - AuthServiceImpl updated: all RuntimeExceptions replaced with specific custom exceptions

- [x] Step 12: Input validation — COMPLETE (2026-04-24)
  - Bean Validation annotations on DTOs: @NotBlank, @Email, @Size on RegisterRequest/LoginRequest, @NotBlank on RefreshRequest
  - @Valid added to all controller method parameters
  - Separate ValidationErrorResponse record with nested FieldError record — clean separation from ErrorResponse
  - GlobalExceptionHandler catches MethodArgumentNotValidException → 400 with field-level errors
  - Learned: separate response types for different error shapes (industry standard), List<FieldError> over Map for multiple errors per field

- [x] Step 13: Test protected endpoints — COMPLETE (2026-04-24)
  - 10 test scenarios: 9 pass, 1 expected (no controller for /api/users/me yet)
  - Auth endpoints: register (201), login (200), refresh (200), validation (400), duplicate (409), wrong password (401)
  - Protected endpoints: blocked without token (403), blocked with bogus token (403)
  - Refresh token rotation verified: revoked token returns 401
  - Known issue: 403 instead of 401 for unauthenticated — needs custom AuthenticationEntryPoint (future)
  - Known issue: valid token on non-existent endpoint returns 500 — no controller yet (expected)

- [x] Step 14: Logout API — COMPLETE (2026-04-24)
  - POST /api/user/logout (protected — requires JWT)
  - Separate UserController + UserService (not on AuthController — logout is a user action, not auth)
  - Revokes all active refresh tokens for the user
  - Void return, no try-catch — exceptions propagate to GlobalExceptionHandler
  - userId extracted from Authentication principal (set by JwtAuthFilter via ThreadLocal SecurityContext)
  - Learned: SecurityContextHolder is ThreadLocal (per-thread isolation), addFilterBefore positions in filter chain, DelegatingFilterProxy bridges Servlet ↔ Spring

- [x] Step 15: RBAC on endpoints — COMPLETE (2026-04-24)
  - @EnableMethodSecurity added to SecurityConfig
  - URL-based rules: /api/admin/** → ADMIN only, POST /api/products/** → SELLER or ADMIN
  - Method-level: @PreAuthorize("hasRole('ADMIN')") on test endpoint
  - Used UserRoles enum in URL rules to avoid hardcoded strings
  - Correct matcher ordering: most specific first, anyRequest().authenticated() last
  - Tested: CUSTOMER gets 403 on admin endpoint, ADMIN gets through

### Phase 1 Remaining
- [ ] Step 16: Unit + Integration tests (Testcontainers) — DEFERRED (will write after Phase 2)

## Phase 2: Product Catalog & Batch Import — IN PROGRESS (started 2026-04-27)
### Design Completed
- [x] Entity design: Category (self-referential tree), Brand, BrandTickerMapping, Product
- [x] Product module structure: controller → service → repository in product-service module
- [x] Soft delete strategy for products (active flag, not physical delete)
- [x] Category hierarchy: self-referential @ManyToOne (parent/children) for unlimited nesting

### Implementation Completed
- [x] Step 1: Create Product module entities — COMPLETE (2026-04-27)
  - Category: self-referential parent-child with @ManyToOne + @OneToMany, unique name constraint
  - Brand: name + description + logoUrl, unique name constraint
  - BrandTickerMapping: maps brand → stock ticker (AAPL, NKE), unique composite (brandId + tickerSymbol)
  - Product: name, description, sku (unique), price (BigDecimal), stockQuantity, imageUrl, active flag
  - Product has @ManyToOne to Brand and Category (LAZY fetch)
  - All entities extend BaseEntity (id, createdAt, updatedAt)
  - BUILD SUCCESSFUL

- [x] Step 2: Create Repository interfaces — COMPLETE (2026-04-27)
  - CategoryRepository: findByParentIsNull (top-level), findByParentId (subcategories), existsByName
  - BrandRepository: findByName, existsByName
  - BrandTickerMappingRepository: findByBrandId, existsByBrandIdAndTickerSymbol
  - ProductRepository: findByBrandId, findByCategoryId, findBySku, existsBySku
  - BUILD SUCCESSFUL

- [x] Step 3: Create DTOs (request/response records) — COMPLETE (2026-04-27)
  - Request DTOs with Bean Validation: CategoryRequest, BrandRequest, BrandTickerMappingRequest, ProductRequest
  - Response DTOs: CategoryResponse, BrandResponse, BrandTickerMappingResponse, ProductResponse
  - ProductResponse includes nested brandName and categoryName (not just IDs)
  - BrandTickerMappingRequest: @DecimalMin("0.0") + @DecimalMax("100.0") for stockBackPercentage
  - BUILD SUCCESSFUL

- [x] Step 4: Create Service layer (interface + impl) — COMPLETE (2026-04-27)
  - CategoryService: create, getById, getTopLevel, getSubcategories
  - BrandService: create, getById, getAll
  - BrandTickerMappingService: create, getByBrandId
  - ProductService: create, getById, update, delete (soft delete — sets active=false)
  - All services use custom exceptions: ResourceNotFoundException (404), DuplicateResourceException (409)
  - Product create validates brand and category exist before saving
  - @Transactional on write operations
  - BUILD SUCCESSFUL

- [x] Step 5: Create Controllers + Test all APIs — COMPLETE (2026-04-27)
  - CategoryController: POST /api/categories, GET /api/categories/{id}, GET /api/categories/top-level, GET /api/categories/{id}/subcategories
  - BrandController: POST /api/brands, GET /api/brands/{id}, GET /api/brands
  - BrandTickerMappingController: POST /api/brand-ticker-mappings, GET /api/brand-ticker-mappings/brand/{brandId}
  - ProductController: POST /api/products, GET /api/products/{id}, PUT /api/products/{id}, DELETE /api/products/{id}
  - POST endpoints secured: ADMIN or SELLER role required
  - GET endpoints: any authenticated user
  - Fixed: `-parameters` compiler flag missing in build.gradle — @PathVariable without explicit name failed at runtime
  - All APIs tested and working (POST, GET, PUT, DELETE, RBAC)

- [x] Step 6: Search/Filter with JPA Specifications + Pagination — COMPLETE (2026-04-28)
  - ProductRepository extended with JpaSpecificationExecutor<Product> (interface multiple inheritance)
  - ProductSpecification utility class: 6 static methods returning Specification<Product> (hasName, hasBrandId, hasCategoryId, hasMinPrice, hasMaxPrice, isActive)
  - Each specification returns Specification.unrestricted() for null params (replaces deprecated Specification.where() null-handling)
  - ProductSearchRequest record: all-optional filter fields (name, brandId, categoryId, minPrice, maxPrice, active)
  - PagedResponse<T> generic record in commons: reusable paginated response wrapper with static factory from(Page<T>)
  - Specification.allOf() composes all filters with AND — null-safe via unrestricted()
  - Pageable auto-resolved from query params (page, size, sort) by Spring's PageableHandlerMethodArgumentResolver
  - GET /api/products with query params: search, filter, sort, paginate — all tested and working
  - Fixed: isActive specification missing null check — caused empty results when active param not passed
  - `-parameters` compiler flag moved to root build.gradle subprojects block (applies to all modules)

- [x] Step 7: Batch Product Import with Spring Batch (CSV) — COMPLETE (2026-04-29)
  - Spring Batch dependency added to product build.gradle
  - ProductCsvRow DTO: mutable class with @Data (not record — FlatFileItemReader needs setters)
  - ProductBatchConfig: @Configuration with 5 @Bean methods:
    - FlatFileItemReader<ProductCsvRow>: @StepScope, reads CSV line-by-line, skips header, maps columns to DTO fields
    - ItemProcessor<ProductCsvRow, Product>: looks up Brand/Category by ID, builds Product entity, returns null to skip invalid rows
    - RepositoryItemWriter<Product>: writes via productRepository.save()
    - Step: chunk-oriented (50 items per transaction), wires reader → processor → writer
    - Job: single-step job named "productImportJob"
  - ProductImportController: POST /api/products/import, multipart file upload, saves to temp file, launches job via JobLauncher
  - application.yml: spring.batch.jdbc.initialize-schema=always (metadata tables), spring.batch.job.enabled=false (no auto-run)
  - Tested: 5 products imported from CSV, verified via search API, RBAC enforced (ADMIN only)

- [x] Step 8: Redis Caching for Product Listings — COMPLETE (2026-04-30)
  - Added spring-boot-starter-data-redis + spring-boot-starter-cache to product build.gradle
  - Configured Redis connection (localhost:6379) + cache TTL (10 min) in application.yml
  - RedisCacheConfig: @EnableCaching + RedisCacheManager bean with GenericJackson2JsonRedisSerializer (JSON, not Java serialization)
  - @Cacheable("product", key="#productId") on getProductById — cache single product
  - @Cacheable("products", key="#request.toString() + page + size") on searchProduct — cache search results
  - @CacheEvict("products", allEntries=true) on createProduct — evict stale list cache
  - @CacheEvict("products") + @CachePut("product") on updateProduct — evict lists, update single cache
  - @Caching(evict = {@CacheEvict("products"), @CacheEvict("product")}) on deleteProduct — evict from both caches
  - Tested: cache HIT/MISS via Hibernate SQL logs, verified keys in redis-cli, eviction on writes confirmed
  - Docker Redis: `docker run -d --name redis -p 6379:6379 redis`

### Phase 2 Remaining
- [ ] Step 9: Unit + Integration tests — DEFERRED (will write after Phase 3)

## Phase 3: Order Service & Cart — IN PROGRESS (started 2026-05-03)
### Design Completed
- [x] Cart design: Redis Hash (per-user key, product fields, 30-min TTL)
- [x] Cart API design: add/remove/get/clear with userId from SecurityContext
- [x] Order entity design: Order + OrderItem + OrderStatus enum
- [x] Order lifecycle state machine: CREATED → CONFIRMED → ... → DELIVERED → RETURN_REQUESTED → RETURNED → REFUNDED
- [x] Design decisions: price snapshot in OrderItem, no FK to Product (bounded context), idempotencyKey on Order, CascadeType.ALL + orphanRemoval

### Implementation Completed
- [x] Step 1-6: Cart implementation — COMPLETE (2026-05-03)
  - Dependencies added: spring-boot-starter-data-redis, spring-boot-starter-validation, spring-security-core
  - AddToCartRequest, CartItemResponse, CartResponse — Java records with Bean Validation
  - CartRedisRepository: StringRedisTemplate + HashOperations (HSET/HGETALL/HDEL), 30-min TTL reset on write, ObjectMapper for JSON serialization
  - CartService interface with Javadoc + CartServiceImpl: DTO transformation, total calculation, TTL-based expiresAt
  - CartController: POST /api/cart/items, DELETE /api/cart/items/{productId}, GET /api/cart, DELETE /api/cart
  - Corrective fixes: added Logger + Javadoc + logging to all Cart classes, fixed URI from /api/v1/cart to /api/cart (consistent with project), added Javadoc to CartService interface (source for {@inheritDoc})
  - BUILD SUCCESSFUL

- [x] Step 7: Cart end-to-end testing — COMPLETE (2026-05-03)
  - All CRUD operations tested via curl with JWT authentication
  - Verified: add item (201), get cart (200 with items/total/expiresAt), remove item (204), clear cart (204), empty cart response
  - Validation tested: missing/invalid fields return 400
  - Redis verified: KEYS, HGETALL, TTL in redis-cli

- [x] Step 8: SecurityContext integration — COMPLETE (2026-05-03)
  - Replaced {userId} path variable with Authentication parameter from SecurityContext
  - userId extracted via authentication.getPrincipal().toString() — matches JwtAuthFilter's principal (Long userId)
  - URLs simplified: no more userId in path, cart is scoped to authenticated user
  - Security: users can only access their own cart

- [x] Step 9: Order entity design discussion — COMPLETE (2026-05-03)
  - Entity diagram and relationships approved
  - OrderStatus enum: 9 states with valid transition rules
  - Key decisions: @Enumerated(STRING) not ORDINAL, BigDecimal(19,4) for money, productId+productName snapshot (no FK to Product), @Builder.Default on items list, addItem() helper for bidirectional consistency

- [x] Step 10: Create Order + OrderItem + OrderStatus entities — COMPLETE (2026-05-03)
  - OrderStatus enum in enums package (consistent with UserRoles)
  - Order entity: @Table("orders") avoiding SQL reserved keyword, userId (Long, no FK), status, totalAmount, idempotencyKey (unique), shippingAddress, paymentMethod, @OneToMany with cascade ALL + orphanRemoval
  - OrderItem entity: @ManyToOne(LAZY) to Order, productId, productName, quantity, priceAtPurchase, subTotal — all snapshots
  - addItem() helper: public, sets both sides of bidirectional relationship
  - BUILD SUCCESSFUL

- [x] Step 11: Create Order repositories — COMPLETE (2026-05-03)
  - OrderRepository: findByUserId (order history), findByIdempotencyKey (duplicate prevention), findByUserIdAndStatus (filtered history)
  - OrderItemRepository: findByOrderId (line items for an order)
  - BUILD SUCCESSFUL

- [x] Step 12: Create Order DTOs — COMPLETE (2026-05-04)
  - PlaceOrderRequest: idempotencyKey, shippingAddress, paymentMethod with @NotBlank validation
  - OrderResponse: record with orderId, userId, status, totalAmount, items, timestamps
  - OrderItemResponse: record with productId, productName, quantity, priceAtPurchase, subTotal
  - UpdateOrderStatusRequest: @NotBlank status string
  - BUILD SUCCESSFUL

- [x] Step 13: OrderService — placeOrder with pessimistic locking — COMPLETE (2026-05-04)
  - Idempotency check first (findByIdempotencyKey → return existing if present)
  - Cart retrieval + empty check (items().isEmpty())
  - Pessimistic write lock on ProductRepository.findByProductId (JPQL + @Lock)
  - Stock validation → InsufficientStockException, product not found → ResourceNotFoundException
  - Stock decrement + OrderItem snapshot (price, name at purchase time)
  - Order saved with CascadeType.ALL, cart cleared after successful save
  - Fixed 5 bugs: JPQL entity name, builder overwrite, null check vs isEmpty, hardcoded values, swapped exceptions

- [x] Step 14: OrderController — COMPLETE (2026-05-04)
  - POST /api/order — place order (201 CREATED), userId from Authentication principal
  - GET /api/order/{orderId} — get single order (200 OK)
  - GET /api/order — get all orders for authenticated user (200 OK)
  - @ResponseStatus on each endpoint, @Valid on request body

- [x] Step 15: Order status transitions (state machine) — COMPLETE (2026-05-05)
  - OrderStatus enum enhanced: static Map<OrderStatus, EnumSet<OrderStatus>> TRANSITIONS
  - canTransition(OrderStatus next) method on enum — validates allowed transitions
  - PATCH /api/order/{orderId}/status — @PreAuthorize("hasRole('ADMIN')")
  - InvalidStatusTransitionException in commons/exception
  - updateOrderStatus() in OrderService — stock restoration on RETURNED transition
  - Fixed: control flow bug (throw always reached), enum valueOf without try-catch, missing @Transactional

- [x] Step 16: Return/Refund initiation flow — COMPLETE (2026-05-05)
  - POST /api/order/{orderId}/return — customer-facing return request
  - requestReturn() validates ownership (userId matches order.getUserId)
  - Only DELIVERED orders can be returned — transitions to RETURN_REQUESTED
  - Security: returns generic 404 for non-owned orders (doesn't leak existence)
  - Stock restoration: updateOrderStatus RETURNED → iterates items, pessimistic lock on product, adds quantity back
  - Full lifecycle: DELIVERED → RETURN_REQUESTED (customer) → RETURNED (admin, restocks) → REFUNDED (admin, terminal)

### Phase 3 Remaining
- [x] Step 17: Test all Order APIs end-to-end — COMPLETE (2026-05-05)
  - 15 curl tests covering: place order, idempotency duplicate detection, get by ID, get by user, status transitions (valid + invalid + invalid string), full lifecycle (CREATED → CONFIRMED → PROCESSING → SHIPPED → DELIVERED), customer return request, stock restoration on RETURNED, REFUNDED terminal state, validation errors, empty cart rejection
  - All tests passed with expected HTTP status codes and response bodies

- [x] Step 18: End-of-phase re-audit — COMPLETE (2026-05-05)
  - Added Javadoc to ALL uncommitted files: Order, OrderItem, OrderRepository, OrderItemRepository, OrderStatus, PlaceOrderRequest, OrderResponse, OrderItemResponse, UpdateOrderStatusRequest
  - Fixed logging inconsistency: replaced @Slf4j with Log4j (LogManager.getLogger) in OrderServiceImpl and OrderController
  - Fixed exception class Javadoc: corrected "400 Not Found" → "400 Bad Request"
  - Updated learning_log.md with all Phase 3 concepts, roadblocks, and interview questions
  - Updated progress.md with all step completions

## Phase 4: Market Data Service — Reactive (started 2026-05-05) — FUNCTIONAL COMPLETE
### Design Completed
- [x] Architecture decision: WebClient + SSE within MVC monolith (not full WebFlux/Netty switch)
- [x] External API: Alpha Vantage (free tier, rate-limited — forces proper caching/resilience)
- [x] Module structure: client, config, controller, dto, service, repository
- [x] Step sequence approved (8 steps)

### Implementation
- [x] Step 1: Dependencies + WebClient Configuration — COMPLETE (2026-05-08)
  - market-data/build.gradle: removed spring-boot-starter-data-jpa, added webflux, data-mongodb, data-redis, resilience4j-spring-boot3, resilience4j-reactor, validation
  - WebClientConfig: @Configuration + @Bean WebClient with Reactor Netty HttpClient, 5s connect timeout, 10s response timeout, base URL from @Value
  - application.yml: added alphavantage.api-key (env var with demo fallback) + alphavantage.base-url
  - Learned: @Configuration + @Bean lifecycle, WebClient as non-blocking HTTP client (RestTemplate replacement), timeout configuration via HttpClient.option() + responseTimeout()

- [x] Step 2: Alpha Vantage Client + DTOs — COMPLETE (2026-05-08)
  - StockQuote record: internal DTO for parsed Alpha Vantage response (symbol, price, change, changePercent, volume, latestTradingDay, timestamp)
  - StockPriceResponse record: external API response DTO (same fields + cachedAt for freshness indicator)
  - AlphaVantageClient: @Component with WebClient + ObjectMapper, Mono<StockQuote> getStockQuote(String symbol) reactive chain
  - Reactive chain: webClient.get().uri(...).retrieve().bodyToMono(String.class).flatMap(parse JSON → StockQuote)
  - JSON parsing via ObjectMapper.readTree() → JsonNode tree navigation (path("Global Quote").path("05. price"))
  - Error handling: Mono.error() for missing/empty Global Quote node, catch-all for parse failures
  - Learned: JsonNode tree structure (ObjectNode/TextNode/ArrayNode), readTree() tokenization + tree construction, .path() vs .get() (MissingNode vs null), flatMap vs map in reactive (flatMap can return Mono.error)

- [x] Step 3: MarketDataService + Redis Price Cache — COMPLETE (2026-05-09)
  - MarketDataService interface: getPrice, getPrices, evictPriceCache
  - MarketDataServiceImpl: manual Redis cache (StringRedisTemplate + opsForValue), 30s TTL, cache-aside pattern
  - Cache-aside flow: check Redis → MISS → call AlphaVantageClient → .block() → store in Redis → return
  - .block() at service boundary bridges reactive Mono to synchronous MVC controller
  - Atomic set+TTL: redisTemplate.opsForValue().set(key, json, CACHE_TTL) — 3-arg overload
  - Learned: opsForValue vs opsForHash (data structure choice based on access pattern), .block() boundary placement

- [x] Step 4: Resilience4j — Circuit Breaker + Retry + Rate Limiter — COMPLETE (2026-05-09)
  - @Retry, @CircuitBreaker, @RateLimiter annotations on AlphaVantageClient.getStockQuote()
  - Circuit breaker: sliding-window-size=10, failure-rate-threshold=50%, wait-duration-in-open-state=30s
  - Retry: max-attempts=3, wait-duration=2s
  - Rate limiter: limit-for-period=5, limit-refresh-period=60s, timeout-duration=0s
  - Fallback method: getStockQuoteFallback returns Mono.error() (not fake data)
  - spring-boot-starter-aop dependency added (required for AspectJ @Aspect scanning)
  - Resilience4j YAML: instances.alphaVantage (named instance, not default)
  - Learned: Circuit breaker 3 states (CLOSED/OPEN/HALF-OPEN), annotation stacking order, native Advisor vs AspectJ @Aspect AOP mechanisms, CGLIB proxy wraps real object (two objects in memory), private fallback via reflection (setAccessible)

- [x] Step 5: MongoDB — Historical Price Storage — COMPLETE (2026-05-09)
  - PriceHistory entity: @Document("price_history"), BigDecimal price/change, volume, tradingDay, @Indexed(expireAfter="90d") on fetchedAt for automatic TTL cleanup
  - PriceHistoryRepository: findBySymbolOrderByFetchedAtDesc (paginated recent snapshots), findBySymbolAndFetchedAtBetween (time-range queries)
  - Fire-and-forget persistence: CompletableFuture.runAsync() saves PriceHistory on cache MISS without blocking the price response
  - getHistory() service method: queries MongoDB for snapshots within the last N days using Instant-based time range
  - Learned: MongoDB @Document vs JPA @Entity, TTL indexes for automatic document expiry, CompletableFuture.runAsync for fire-and-forget side effects

- [x] Step 6: Company Health Score Endpoint — COMPLETE (2026-05-10)
  - HealthScoreResponse record: symbol, score (0–100), signals map (signal→contribution), calculatedAt
  - getHealthScore() in MarketDataServiceImpl: composite score from 4 signals — priceChange (±15), changePercent magnitude (±10 if >2%), weeklyTrend from MongoDB (±15), volume (>1M → +10)
  - Base score 50, clamped to [0, 100] via Math.max(0, Math.min(100, score))
  - LinkedHashMap for signals to preserve insertion order
  - MarketDataController: 6 endpoints — GET /price/{symbol}, GET /prices, GET /history/{symbol}, GET /health/{symbol}, DELETE /price/{symbol}/cache (ADMIN), GET /stream/{symbol} (SSE)
  - Learned: BigDecimal.compareTo() for financial comparisons (never == with BigDecimal), LinkedHashMap preserves insertion order vs HashMap

- [x] Step 7: SSE Endpoint for Live Price Streaming — COMPLETE (2026-05-10)
  - streamPrice() in MarketDataServiceImpl: Flux.interval(5s) → concatMap (ordered, waits for each Mono) → toResponse → onErrorResume (skip failures) → distinctUntilChanged(StockPriceResponse::price)
  - Controller: GET /stream/{symbol} with produces=TEXT_EVENT_STREAM_VALUE, returns Flux<ServerSentEvent<StockPriceResponse>>
  - ServerSentEvent wrapper: provides SSE fields (id, event, retry, comment) — standard pattern over raw Flux
  - concatMap vs flatMap: concatMap preserves ordering (waits for each inner Mono), flatMap can interleave if API calls take longer than interval
  - No .block() in the stream — fully reactive end-to-end, Spring handles chunked transfer encoding
  - Learned: SSE vs WebSocket vs Polling (SSE = server-push, unidirectional, plain HTTP, auto-reconnect; WebSocket = full-duplex, protocol upgrade; Polling = wasteful repeated requests), Flux.interval for time-based emission, distinctUntilChanged with key selector

- [x] Step 8: Test All Endpoints + End-of-Phase Re-Audit — COMPLETE (2026-05-10)
  - All 6 endpoints tested: GET /price/{symbol}, GET /prices, GET /history/{symbol}, GET /health/{symbol}, DELETE /price/{symbol}/cache (ADMIN), GET /stream/{symbol} (SSE)
  - RBAC verified: ADMIN-only cache evict returns 403 for CUSTOMER
  - Health score verified against raw data: score=85 matches manual calculation (50+15+10+0+10)
  - SSE stream verified: events push every 5 seconds, distinctUntilChanged suppresses duplicates
  - Redis verified: cache HIT/MISS via logs, KEYS/GET/TTL in redis-cli
  - MongoDB verified: price_history documents saved on cache miss, queryable via mongosh
  - Zscaler SSL issue resolved: imported Zscaler Root CA into Java truststore (keytool -import)
  - Re-audit: Javadoc + Log4j logger on all 10 uncommitted Java files, stale comment removed from AlphaVantageClient
  - Debug-mode walkthrough written in learning_log.md: full request flow traces for all 6 endpoints + resilience scenarios
  - Fixed: @Value on final field causing UnsatisfiedDependencyException (removed final), flatMap→concatMap for SSE ordering, Math.max(0,...) lower bound on health score

## Phase 5: Portfolio & Stock-Back Engine — IN PROGRESS (started 2026-05-12)

### Step Plan (derived from equitycart-roadmap.md Phase 5 deliverables)

| Step | Deliverable | Status |
|------|-------------|--------|
| 1 | Ledger entity + double-entry bookkeeping (committed in prior session) | COMPLETE |
| 2 | Design: Portfolio entities, stock-back concept, relationships | COMPLETE |
| 3 | Dependencies (portfolio build.gradle) | COMPLETE |
| 4 | Portfolio, Holding, StockBackReward entities + VestingStatus enum | COMPLETE |
| 5 | Repositories + PortfolioService + VestingHelper (with REQUIRES_NEW) | COMPLETE |
| 6 | PortfolioController (REST API) + DTOs + Facade | COMPLETE |
| 7 | BUY/SELL Trade APIs (TradeService — manual trading) | COMPLETE |
| 8 | Ledger Integration + "Sell to Spend" atomic transaction | COMPLETE |
| 9 | Portfolio Analytics (cost basis, weights, reward summary) | COMPLETE |
| 10 | End-to-end testing + end-of-phase re-audit | PENDING |

Note: Kafka Consumer (order-filled event → stock-back + holding) moved to Phase 6 (Event-Driven Architecture) per roadmap alignment.

### Design Completed
- [x] Portfolio entity design: Portfolio → Holdings (1:N via CascadeType.ALL + orphanRemoval)
- [x] Holding: composite unique (portfolio_id + ticker_symbol), @Version for optimistic locking, BigDecimal precision (scale=6 qty, scale=4 price)
- [x] StockBackReward: one-per-order (unique orderId), vesting lifecycle (PENDING → VESTED | CANCELLED), 30-day delay for return window
- [x] VestingStatus enum: state machine with terminal states (VESTED, CANCELLED)
- [x] Transaction strategy: class-level @Transactional(REQUIRED) + method-level overrides (readOnly, REQUIRES_NEW)
- [x] VestingHelper extracted as separate @Service to solve Spring proxy self-invocation problem
- [x] Stock-back reward concept: fractional share rewards on order completion, zero cost-basis, dollarValue for tax reporting

### Implementation Completed
- [x] Step 1: Ledger-service (committed dac0aae) — COMPLETE
  - LedgerEntry entity with double-entry bookkeeping (DEBIT/CREDIT)
  - AccountType enum (WALLET, STOCK, REWARD, PLATFORM)
  - EntryType enum (DEBIT, CREDIT)
  - ReferenceType enum (for linking entries to source events)
  - LedgerEntryRepository + LedgerService interface + LedgerServiceImpl

- [x] Step 2: Design phase — COMPLETE (2026-05-12)
  - Entity relationships approved (Portfolio 1:N Holding, StockBackReward standalone)
  - Stock-back reward lifecycle designed (PENDING → VESTED/CANCELLED)
  - Vesting delay rationale: 30-day return window before shares become real

- [x] Step 3: Portfolio module dependencies — COMPLETE (2026-05-12)
  - portfolio/build.gradle: spring-boot-starter-data-jpa, spring-boot-starter-web, spring-boot-starter-validation
  - Inherits from root subprojects block (postgres, lombok, commons module dependency)

- [x] Step 4: Create Portfolio module entities — COMPLETE (2026-05-12)
  - Portfolio: userId (unique), OneToMany holdings with cascade + orphanRemoval, Builder.Default for list
  - Holding: tickerSymbol, quantity (scale=6 for fractional), averageBuyPrice (scale=4), @Version, ManyToOne(LAZY) to Portfolio, composite unique constraint (uk_portfolio_ticker)
  - StockBackReward: orderId (unique for idempotency), userId, tickerSymbol, sharesEarned, dollarValue, VestingStatus, vestingDate, vestedAt
  - VestingStatus: PENDING, VESTED, CANCELLED with state transition rules documented
  - BUILD SUCCESSFUL

- [x] Step 5: Repositories + Service layer — COMPLETE (2026-05-12)
  - PortfolioRepository: findByUserId
  - HoldingRepository: findByPortfolioAndTickerSymbol
  - StockBackRewardRepository: findByStatusAndVestingDateBefore, findByOrderId
  - PortfolioService interface (4 methods) + PortfolioServiceImpl
  - VestingHelper interface + VestingHelperImpl (@Transactional(REQUIRES_NEW))
  - getOrCreatePortfolio: lazy portfolio creation
  - addOrUpdateHolding: weighted-average price recalculation + optimistic lock retry (3 attempts)
  - grantReward: idempotent (findByOrderId check, skip duplicates)
  - vestPendingRewards: @Scheduled(fixedDelay=60000) + @Transactional(readOnly=true), delegates to VestingHelper
  - @EnableScheduling added to EquityCartApplication
  - BUILD SUCCESSFUL

### Concepts Learned (Phase 5 so far)
- @Transactional class-level vs method-level: class sets default, method overrides for specific propagation/readOnly
- All 7 propagation types: REQUIRED, REQUIRES_NEW, SUPPORTS, NOT_SUPPORTED, MANDATORY, NEVER, NESTED
- REQUIRES_NEW suspends outer transaction, commits independently (used for per-reward isolation)
- NESTED uses JDBC savepoints (not available in JPA/Hibernate)
- Spring proxy self-invocation trap: internal this.method() bypasses proxy → no @Transactional processing
- Solution: extract into separate @Service bean so calls go through the proxy
- @Transactional(readOnly=true): optimizes Hibernate flush mode (no dirty checking), gives DB optimization hints
- Optimistic locking retry pattern: catch OptimisticLockingFailureException, re-read entity, recalculate, retry N times
- Stock-back reward model: fractional shares as loyalty reward, zero cost-basis, dollarValue for tax/reporting, vesting delay for return protection
- Proxy commit-time exceptions: try-catch inside @Transactional method doesn't catch flush/commit failures — those propagate from the proxy layer after method returns

- [x] Step 6: PortfolioController + DTOs + Facade — COMPLETE (2026-05-12)
  - 4 DTOs as Java records: HoldingRequest (with validation), HoldingResponse, PortfolioResponse, StockBackRewardResponse
  - PortfolioFacade interface + PortfolioFacadeImpl: thin mapping layer between controller DTOs and service entities
  - PortfolioController: 3 endpoints — GET /api/portfolio, POST /api/portfolio/holdings (201), GET /api/portfolio/rewards
  - userId extracted from Authentication principal (no path variable — scoped to authenticated user)
  - Facade pattern (GoF): simplified interface over service subsystem, keeps controller thin, service entity-focused
  - Service stays primitive/entity level — same methods callable from facade, VestingHelper, and future Kafka consumers
  - Added getRewards(userId) to PortfolioService + findByUserId to StockBackRewardRepository
  - Fixed: initial impl had facade calling repository directly — restructured to route through service layer
  - BUILD SUCCESSFUL

### Phase 5 Remaining
- [x] Step 7: BUY/SELL Trade APIs — COMPLETE (2026-05-13)
  - TradeType enum (BUY, SELL), TradeRequest/TradeResponse DTOs (Java records, String tradeType for DTO-enum decoupling)
  - TradeService interface (returns Holding entity) + TradeServiceImpl: delegates BUY to addOrUpdateHolding, SELL to reduceHolding
  - PortfolioService.reduceHolding(): validates holding exists + sufficient shares, optimistic lock retry, deletes holding at zero quantity
  - InsufficientSharesException (commons) + GlobalExceptionHandler mapping → 400 Bad Request
  - PortfolioFacade.executeTrade(): maps Holding entity → TradeResponse DTO
  - PortfolioController: POST /api/portfolio/trade (200 OK)
  - Fixed: log bug (old qty logged after setQuantity), IllegalArgumentException→InsufficientSharesException/ResourceNotFoundException, zero-qty phantom holdings, missing optimistic lock retry, 201→200 status
  - Fixed: NullPointerException on full sell — reduceHolding sets quantity to zero before delete, returns holding for response mapping
  - Circular dependency (PortfolioServiceImpl ↔ VestingHelperImpl): @Lazy on field ignored by Lombok @RequiredArgsConstructor — fixed with @Autowired field injection
  - BUILD SUCCESSFUL
- [x] Step 8: Ledger Integration + Sell to Spend — COMPLETE (2026-05-14)
  - **8A: Ledger wired into TradeService**
    - Added `implementation project(':ledger-service')` to portfolio/build.gradle
    - TradeServiceImpl injects LedgerService, records double-entry after each trade
    - BUY: DEBIT HOLDING_ASSET, CREDIT CASH (user gains shares, loses cash)
    - SELL: DEBIT CASH, CREDIT HOLDING_ASSET (user gains cash, loses shares)
    - Ledger call inside same @Transactional — if ledger fails, holding change rolls back
  - **8B: Sell to Spend (cross-domain atomic transaction)**
    - Added `implementation project(':order-service')` to portfolio/build.gradle
    - SellToSpendRequest/SellToSpendResponse DTOs
    - SellToSpendService interface + SellToSpendServiceImpl: orchestrates portfolio + ledger + order in one @Transactional
    - Flow: validate order (CREATED, belongs to user) → validate proceeds ≥ total → sell shares → record ledger → confirm order
    - Guard clause pattern: early-return validations instead of nested if-else
    - POST /api/portfolio/sell-to-spend (200 OK) via facade + controller
    - Fixed: InsufficientSharesException → IllegalArgumentException for insufficient proceeds (different semantic)
    - Key learning: monolith advantage (one @Transactional wraps all), preview of Saga pattern for microservices
  - BUILD SUCCESSFUL
- [x] Step 9: Portfolio Analytics — COMPLETE (2026-05-14)
  - 3 new DTOs: HoldingAnalyticsResponse (per-holding cost basis + portfolio weight), RewardSummaryResponse (aggregate reward stats), PortfolioAnalyticsResponse (top-level dashboard view)
  - Analytics logic lives in PortfolioFacadeImpl.getAnalytics() — composes data from getOrCreatePortfolio + getRewards, computes derived values
  - Cost basis per holding: qty × avgBuyPrice
  - Portfolio weight: (costBasis / totalCostBasis) × 100, with zero-division guard
  - Reward summary: counts by status (pending/vested), totals (sharesEarned, dollarValue)
  - Fixed: BigDecimal divide precision — multiply first then divide with explicit scale (2 decimal places)
  - GET /api/portfolio/analytics (200 OK)
  - BUILD SUCCESSFUL
- [x] Step 10: End-to-end testing + re-audit — COMPLETE (2026-05-16)
  - Re-audit: all 20 uncommitted Java files verified — Javadoc + Log4j logger present
  - test-commands.md created: consolidated curl commands for all phases (1–5), Redis/MongoDB/PostgreSQL CLI verification, Docker infrastructure setup
  - All 6 portfolio endpoints tested: GET /portfolio, POST /holdings, POST /trade, POST /sell-to-spend, GET /rewards, GET /analytics
  - Error paths verified: InsufficientSharesException (400), ResourceNotFoundException (404), invalid trade type (400), insufficient proceeds (400), already-confirmed order (400)

### Phase 5 Deferred to Phase 6
- **Reward granting** (creating StockBackReward with PENDING status on order delivery): requires cross-module event flow (order → product → market-data → portfolio). This is the natural trigger for the Kafka event pipeline in Phase 6.
- **Vesting job activation**: VestingHelper + @Scheduled job exist and are correct, but have no rewards to process since grant step is missing. Will activate naturally once reward granting is implemented in Phase 6.
- These two items complete the stock-back loop: order delivered → reward granted (PENDING) → vesting job runs → reward vested → holding created.

## Phase Checklist
- [x] Phase 0: Foundation & Setup (Week 1)
- [~] Phase 1: User Service & Security (Weeks 2-3) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 2: Product Catalog & Batch Import (Weeks 4-5) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 3: Order Service & Cart (Weeks 6-7) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 4: Market Data - Reactive (Weeks 8-9) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 5: Portfolio & Stock-Back Engine (Weeks 10-12) — FUNCTIONAL COMPLETE (reward grant deferred to Phase 6)
- [ ] Phase 6: Event-Driven Architecture (Weeks 13-15)
- [ ] Phase 7: Microservices Decomposition (Weeks 16-18)
- [ ] Phase 8: Security Hardening (Weeks 19-20)
- [ ] Phase 9: Observability (Weeks 21-22)
- [ ] Phase 10: Advanced Features & Scale (Weeks 23-26)

### Known Issues
- 403 instead of 401 for unauthenticated requests — needs custom AuthenticationEntryPoint (future)

## Session Log
- **2025-04-07**: Project conceived, domain finalized (Hybrid), roadmap created
- **2025-04-07**: Phase 0 complete — Gradle multi-module skeleton built, all 7 modules compile, application.yml configured for PostgreSQL + MongoDB
- **2025-04-09**: Phase 1 started — Entity design complete, security architecture decided (JWT), query methods studied, security deps added. Next: create entity classes.
- **2026-04-13**: Step 2 complete — BaseEntity in commons + 7 user entities created. All JPA mappings, constraints, and Lombok annotations reviewed. BUILD SUCCESSFUL. Next: Repository interfaces.
- **2026-04-15**: Step 3 complete — 7 repository interfaces created. Fixed: existsBy return type, derived query field names matching entity fields. BUILD SUCCESSFUL. Next: AuthService.
- **2026-04-20**: Step 4 complete — DTOs (records + validation), SecurityConfig (BCryptPasswordEncoder bean), AuthService with register/login/refresh. Fixed: @Autowired vs constructor injection, passwordEncoder.matches() arg order, raw strings → DTOs. BUILD SUCCESSFUL. Next: JwtService.
- **2026-04-20**: Step 5 complete — JwtService (JJWT 0.12.6) + wired into AuthService. Fixed 5 issues: hardcoded expiry, extractRoles corruption, validateToken always-true, redundant isTokenExpired, YAML location. Refresh token rotation implemented. All TODO placeholders replaced. Next: JwtAuthFilter.
- **2026-04-20**: Step 6 complete — JwtAuthFilter (OncePerRequestFilter). Extracts Bearer token, validates, sets SecurityContext. Fixed: null header NPE, @Service→@Component, unauthenticated 2-arg→authenticated 3-arg constructor. Next: SecurityConfig.
- **2026-04-20**: Step 7 complete — SecurityConfig with SecurityFilterChain bean. CSRF disabled, STATELESS sessions, /api/auth/** public, all else authenticated. Fixed: missing final, non-lambda DSL, single-star wildcard, hardcoded URL. Next: AuthController.
- **2026-04-20**: Step 8 complete — AuthController with 3 POST endpoints. Thin delegation to AuthService. Fixed: duplicate method names, duplicate path mappings, removed catch-all try-catch. Next: build and test.
- **2026-04-21**: Step 9 complete — All 3 auth endpoints tested and working. Fixed: DB name case-sensitivity, data.sql not running (needed `sql.init.mode: always`), YAML indentation bug, understood Java field defaults vs DB seed data. Next: JSON-based DataSeeder, then @RestControllerAdvice.
- **2026-04-21**: Step 10 complete — DataSeeder with CommandLineRunner + Jackson + roles.json. Removed data.sql approach. Learned: CommandLineRunner runs after full context (no ordering issues), inject ObjectMapper don't create it, TypeReference for generic deserialization. Next: @RestControllerAdvice.
- **2026-04-23**: Step 11 complete — GlobalExceptionHandler with @RestControllerAdvice. 4 custom exceptions + catch-all. Key lesson: exceptions carry error info, handlers decide the response (separation of concerns). Next: input validation.
- **2026-04-24**: Step 12 complete — Bean Validation on DTOs + @Valid in controller. Separate ValidationErrorResponse with nested FieldError record. Learned: separate response types for different error shapes, List over Map for multiple errors per field, warn not error for validation failures. Next: test protected endpoints.
- **2026-04-24**: Step 13 complete — 10 curl tests, all auth flows verified. JWT filter blocks unauthenticated requests. Refresh token rotation works. Known: 403 vs 401 needs AuthenticationEntryPoint, valid token on missing endpoint gives 500. Next: Logout API.
- **2026-04-24**: Step 14 complete — Logout API with separate UserController/UserService. Revokes all active refresh tokens. Learned: SecurityContextHolder is ThreadLocal, filter chain order via addFilterBefore, DelegatingFilterProxy bridges Servlet ↔ Spring. Next: RBAC.
- **2026-04-24**: Step 15 complete — RBAC with @EnableMethodSecurity + URL-based rules + @PreAuthorize. URL rules use UserRoles enum, correct matcher ordering. Tested CUSTOMER → 403, ADMIN → 200. Learned: form login vs JWT auth, FilterChainProxy registration, DelegatingFilterProxy is sibling to DispatcherServlet (not child), catch-all exception handler flow. Next: Unit + Integration tests.
- **2026-04-27**: Phase 2 started — Product module entities (Category, Brand, BrandTickerMapping, Product), repositories, DTOs, services, controllers all created. Self-referential Category hierarchy, soft delete for products, BigDecimal for price, composite unique constraints. Fixed: `-parameters` compiler flag needed for @PathVariable name resolution in Spring Boot 3. All CRUD APIs tested and working with RBAC. Next: Search/Filter with JPA Specifications + Pagination.
- **2026-04-28**: Step 6 complete — JPA Specifications + Pagination for product search. ProductSpecification utility (6 composable specs), PagedResponse<T> generic wrapper in commons, Specification.allOf() with unrestricted() for null-safe composition. Fixed: isActive missing null check caused empty results. `-parameters` flag moved to root build.gradle. All search/filter/sort/pagination combos tested and working. Next: Batch Import.
- **2026-04-29**: Step 7 complete — Spring Batch CSV import. ProductBatchConfig with Job/Step/Reader/Processor/Writer, chunk-oriented processing (50 per transaction). ProductImportController with multipart file upload + JobLauncher. Batch metadata tables auto-created. 5 products imported from CSV, verified via search API. Phase 2 FUNCTIONAL COMPLETE (tests deferred). Next: Redis caching.
- **2026-04-30**: Step 8 complete — Redis caching for product listings. RedisCacheConfig with @EnableCaching + JSON serializer. @Cacheable on reads, @CacheEvict on writes. Verified cache HIT/MISS via SQL logs + redis-cli KEYS. Phase 2 FULLY COMPLETE. Next: Phase 3 — Order Service & Cart.
- **2026-05-03**: Phase 3 started — Cart implementation complete (Steps 1-8). Redis Hash for cart storage, StringRedisTemplate + ObjectMapper, CartController with SecurityContext userId extraction (no path variable). All CRUD tested via curl. Corrective audit: added Logger/Javadoc/logging to all Cart classes, fixed URI consistency, added Javadoc source to CartService interface. Next: Order entities.
- **2026-05-03**: Steps 9-11 complete — Order entity design approved. Order + OrderItem + OrderStatus entities created. OrderRepository (3 query methods) + OrderItemRepository created. Learned: @Data vs individual annotations on JPA entities (equals/hashCode/toString dangers), @OneToMany/@ManyToOne bidirectional mapping (owning vs inverse side), property path traversal in Spring Data derived queries. BUILD SUCCESSFUL. Next: Order DTOs.
- **2026-05-04**: Steps 12-15 in progress — Order DTOs (PlaceOrderRequest, OrderResponse, OrderItemResponse, UpdateOrderStatusRequest) created. OrderServiceImpl completed: placeOrder with pessimistic locking + idempotency, getOrderById, getOrdersByUserId. OrderController with 3 endpoints (POST, GET/{id}, GET). Order status transitions: OrderStatus enum with EnumSet transition map + canTransition(), PATCH endpoint with ADMIN-only access. Fixed bugs: JPQL entity name (Product not Products), swapped exception types, missing path variable braces. Next: fix updateOrderStatus control flow bug, then test all APIs.
- **2026-05-05**: Steps 15-16 complete — Status transitions fixed (control flow, valueOf try-catch, @Transactional). Return/Refund flow: PATCH /api/order/{id}/return (customer), ownership validation, stock restoration on RETURNED with pessimistic lock. Fixed: @Slf4j→Log4j consistency, added Javadoc to exception classes. Learned: Pessimistic vs Optimistic locking (trade-offs, when to use), idempotency keys (client-generated UUID, check-before-create pattern, Stripe history). Next: test all Order APIs end-to-end.
- **2026-05-08**: Phase 4 Steps 1-2 complete — market-data module dependencies updated (webflux, mongodb, redis, resilience4j). WebClientConfig with Reactor Netty timeouts. AlphaVantageClient with reactive WebClient chain + JsonNode parsing. StockQuote + StockPriceResponse DTOs. Learned: @Configuration/@Bean lifecycle, WebClient vs RestTemplate (buzzer vs dedicated waiter), Mono<T> as deferred promise, JsonNode tree structure (ObjectNode→TextNode), readTree() tokenization, .path() null-safety vs .get(). Next: MarketDataService + Redis price cache.
- **2026-05-09**: Phase 4 Steps 3-4 complete — MarketDataServiceImpl with manual Redis cache (opsForValue, 30s TTL, atomic set+expire). Resilience4j annotations (@Retry, @CircuitBreaker, @RateLimiter) on AlphaVantageClient with fallback returning Mono.error(). Fixed: TTL 30min→30s, two-step set+expire→atomic 3-arg set, YAML default→named instances, case mismatch in instance names, fallback returning fake data→Mono.error(). Deep dives: opsForValue vs opsForHash choice, Spring native Advisor vs AspectJ @Aspect AOP (why starter-aop needed), CGLIB proxy wraps real object (both in memory), private fallback via reflection setAccessible. Next: MongoDB historical price storage.
- **2026-05-09**: Phase 4 Step 5 complete — PriceHistory MongoDB entity with TTL index (90d auto-expiry), PriceHistoryRepository with time-range and paginated queries. Fire-and-forget CompletableFuture.runAsync() persists snapshots on cache miss. getHistory() queries MongoDB for last N days. Next: health score endpoint.
- **2026-05-10**: Phase 4 Steps 6-7 complete — HealthScoreResponse DTO, getHealthScore() with 4-signal composite (priceChange ±15, changePercent ±10, weeklyTrend ±15, volume +10), base 50, clamped [0,100]. MarketDataController with 6 endpoints. SSE streaming: Flux.interval(5s) + concatMap (ordered) + distinctUntilChanged(price). ServerSentEvent wrapper for standard SSE fields. Fixed: flatMap→concatMap for ordering, Math.max(0,...) lower bound. Javadoc + Log4j logger added to all uncommitted files. Next: test all endpoints end-to-end.
- **2026-05-10**: Phase 4 Step 8 complete — All 6 endpoints tested (price, prices, history, health, cache evict, SSE stream). MongoDB via Docker (`docker run -d --name mongodb -p 27017:27017 mongo`). Zscaler SSL resolved (imported root CA into Java truststore). Health score verified: 85 = 50+15+10+0+10. Re-audit: all 10 files have Javadoc + Log4j. Debug-mode walkthrough written (startup bean wiring, 6 request flow traces, 3 resilience scenarios). Fixed: @Value on final field (UnsatisfiedDependencyException). Phase 4 FUNCTIONAL COMPLETE.
- **2026-05-12**: Phase 5 started — Portfolio module: entities (Portfolio, Holding, StockBackReward, VestingStatus), repositories (3), PortfolioService + VestingHelper. Learned: @Transactional propagation (7 types), proxy self-invocation problem, REQUIRES_NEW for batch isolation, optimistic lock retry, stock-back reward business model (fractional shares, vesting delay, zero cost-basis). Next: PortfolioController + DTOs.
- **2026-05-14**: Steps 7-9 complete — TradeService (BUY/SELL with ledger double-entry), SellToSpendService (cross-domain atomic transaction: portfolio + ledger + order), Portfolio Analytics (cost basis, weights, reward summary). Fixed: circular dependency (@Lazy + @Autowired field injection), BigDecimal divide precision, NullPointerException on full sell, log-after-mutation bug. Learned: guard clause pattern, facade as compositor, monolith @Transactional advantage, Saga pattern preview.
- **2026-05-16**: Phase 5 FUNCTIONAL COMPLETE — Step 10 re-audit done (20 files verified). test-commands.md created with all phases (1-5) + Docker/Redis/MongoDB/PostgreSQL CLI. Identified gap: reward granting (creating PENDING StockBackReward on order delivery) not implemented — requires cross-module event chain (order→product→market-data→portfolio). Deferred to Phase 6 as first Kafka event. Vesting job exists but idle until rewards are granted. Next: Phase 6 — Event-Driven Architecture.
