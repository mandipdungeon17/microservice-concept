# Progress Tracking

## Status: Phase 10 Scope Narrowed — Topic 10 + CI/CD Next

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

| Step | Deliverable                                                           | Status   |
| ---- | --------------------------------------------------------------------- | -------- |
| 1    | Ledger entity + double-entry bookkeeping (committed in prior session) | COMPLETE |
| 2    | Design: Portfolio entities, stock-back concept, relationships         | COMPLETE |
| 3    | Dependencies (portfolio build.gradle)                                 | COMPLETE |
| 4    | Portfolio, Holding, StockBackReward entities + VestingStatus enum     | COMPLETE |
| 5    | Repositories + PortfolioService + VestingHelper (with REQUIRES_NEW)   | COMPLETE |
| 6    | PortfolioController (REST API) + DTOs + Facade                        | COMPLETE |
| 7    | BUY/SELL Trade APIs (TradeService — manual trading)                   | COMPLETE |
| 8    | Ledger Integration + "Sell to Spend" atomic transaction               | COMPLETE |
| 9    | Portfolio Analytics (cost basis, weights, reward summary)             | COMPLETE |
| 10   | End-to-end testing + end-of-phase re-audit                            | PENDING  |

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

## Phase 6: Event-Driven Architecture — Kafka (started 2026-05-16)

### Step Plan

| Step | Deliverable                                                                                    | Status   |
| ---- | ---------------------------------------------------------------------------------------------- | -------- |
| 1    | Kafka Infrastructure + Dependencies (Docker KRaft, spring-kafka, application.yml config)       | COMPLETE |
| 2    | Event DTOs in commons (OrderDeliveredEvent, OrderItemEvent, OrderReturnedEvent)                | COMPLETE |
| 3    | Kafka Producer in order-service (OrderEventPublisher + wire into OrderServiceImpl)             | COMPLETE |
| 4    | Stock-Back Reward Consumer in portfolio-service (StockBackRewardConsumer — grant per ticker)   | COMPLETE |
| 5    | Reward Cancellation Consumer (handleOrderReturned — cancel PENDING rewards)                    | COMPLETE |
| 6    | Outbox Pattern for reliable event publishing (OutboxEvent entity, OutboxPoller, generic relay) | COMPLETE |
| 7    | Dead Letter Queue — DLQ (KafkaConsumerConfig with retry + DeadLetterPublishingRecoverer)       | COMPLETE |
| 8    | End-to-end testing + end-of-phase re-audit                                                     | COMPLETE |
| 9    | Retry logic with exponential backoff (replace FixedBackOff in KafkaConsumerConfig)             | COMPLETE |
| 10   | Debezium CDC (alternative outbox relay via PostgreSQL WAL + Kafka Connect)                     | COMPLETE |
| 11   | Saga Orchestrator for "Sell to Spend" flow (compensating transactions)                         | COMPLETE |
| 12   | Event Sourcing for Portfolio changes (MongoDB append-only event log)                           | COMPLETE |
| 13   | Notification Service (new module — email/webhook on trade, vesting)                            | COMPLETE |

### Design Completed

- [x] Plan approved: Kafka KRaft (no Zookeeper), event-driven reward granting, outbox pattern, DLQ
- [x] Architecture: Kafka runs alongside monolith — producers/consumers are Spring beans in same JVM
- [x] Event flow: Order DELIVERED → Kafka → StockBackRewardConsumer → grantReward(PENDING) → vesting job → VESTED → holding
- [x] Outbox rationale: same-transaction write guarantees no lost events on crash between DB save and Kafka send
- [x] DLQ: 3 retries with 1s backoff, then route to .DLT topic for manual inspection
- [x] Composite unique constraint (orderId + tickerSymbol) — allows multi-ticker rewards per order

### Implementation Completed

- [x] Step 1: Kafka Infrastructure + Dependencies — COMPLETE (2026-05-17)
  - Docker: apache/kafka:latest (KRaft mode, no ZooKeeper), port 9092 client, 9093 controller
  - app/build.gradle: added spring-kafka dependency
  - application.yml: spring.kafka config — bootstrap-servers, producer (StringSerializer/JsonSerializer), consumer (group-id, earliest offset reset, JsonDeserializer, trusted packages)
  - kafka-learning.md created: comprehensive Kafka reference document (topics, partitions, offsets, brokers, consumer groups, serialization, ZooKeeper→KRaft history, Spring properties explained)

- [x] Step 2: Event DTOs in commons — COMPLETE (2026-05-17)
  - Package: com.equitycart.commons.event (new)
  - OrderDeliveredEvent: mutable POJO (no-arg + all-args constructors, getters/setters, equals/hashCode on orderId)
  - OrderItemEvent: product/pricing snapshot for reward calculation (productId, productName, quantity, priceAtPurchase, subtotal)
  - OrderReturnedEvent: orderId + userId + returnedAt for reward cancellation
  - All classes written as manual POJOs (not records) to learn Jackson deserialization lifecycle
  - Javadoc on each class documents record equivalent

- [x] Step 3: Kafka Producer in order-service — COMPLETE (2026-05-17)
  - order/build.gradle: added spring-kafka dependency
  - OrderEventPublisher: @Component with KafkaTemplate<String, Object>, publishes to "order-delivered" and "order-returned" topics
  - Message key = orderId.toString() (guarantees same partition → ordered processing)
  - CompletableFuture.whenComplete() callback for async success/failure handling
  - OrderServiceImpl.updateOrderStatus(): fires publishOrderDelivered on DELIVERED, publishOrderReturned on RETURNED (after save)
  - Fire-and-forget for now — Outbox pattern (Step 6) will replace with guaranteed delivery

- [x] Step 4: Stock-Back Reward Consumer — COMPLETE (2026-05-19)
  - StockBackRewardConsumer in portfolio-service/event package
  - @KafkaListener(topics="order-delivered", groupId="equitycart-reward-group")
  - Groups items by ticker, sums reward dollar values, calculates fractional shares
  - Calls grantReward() per ticker (idempotent via findByOrderIdAndTickerSymbol)
  - StockBackReward entity: @UniqueConstraint on (order_id, ticker_symbol) — multi-ticker rewards per order
  - portfolio/build.gradle: added spring-kafka, product-service, market-data-service deps

- [x] Step 5: Reward Cancellation Consumer — COMPLETE (2026-05-19)
  - handleOrderReturned() in same StockBackRewardConsumer class (single class, two listeners)
  - @KafkaListener(topics="order-returned", groupId="equitycart-cancellation-group")
  - Finds rewards by orderId → cancels PENDING, warns on VESTED (manual review)
  - VestingStatus enum: added CANCELLED value

- [x] Step 6: Outbox Pattern — COMPLETE (2026-05-20)
  - OutboxEvent entity: aggregateType, aggregateId, eventType, topic, payload(@Lob), payloadType(FQCN), status, publishedAt
  - OutboxStatus enum: PENDING, SENT (infrastructure-only, no domain leakage)
  - OrderOutboxWriter: serializes events to JSON + stores FQCN via event.getClass().getName()
  - OutboxPoller: @Scheduled(5s) + @Transactional, Class.forName() re-hydration, .get() blocking send, marks SENT
  - OrderServiceImpl: both DELIVERED and RETURNED route through outbox (atomic with order save)
  - Removed fire-and-forget KafkaTemplate from writer — poller is sole Kafka publisher
  - microservice-patterns.md created: comprehensive Outbox Pattern reference (dual-write problem, serialization flow, poller design, variants, history)

- [x] Step 7: Dead Letter Queue — COMPLETE (2026-05-20)
  - KafkaConsumerConfig in commons/config: DefaultErrorHandler + DeadLetterPublishingRecoverer
  - FixedBackOff(1000L, 3): retry 3 times with 1s delay
  - Non-retryable: DeserializationException, NullPointerException → DLT immediately
  - Auto-creates .DLT topics (order-delivered.DLT, order-returned.DLT)
  - Zero changes to existing listener code — declarative infrastructure

- [x] Step 8: End-to-end testing + re-audit — COMPLETE (2026-05-20)
  - All test scenarios passed: happy path (order→deliver→reward PENDING), return cancellation (PENDING→CANCELLED), multi-ticker rewards, idempotency (duplicate events don't create duplicate rewards), Kafka CLI verification (topics, events, DLT)
  - Re-audit: all 14 uncommitted Java files verified — Javadoc present on all, Log4j loggers on all service/component classes
  - Documentation complete: kafka-learning.md, microservice-patterns.md, test-commands.md (Phase 6 section), learning_log.md (Phase 6 section)

- [x] Step 9: Retry logic with exponential backoff — COMPLETE (2026-05-20)
  - Replaced FixedBackOff with ExponentialBackOffWithMaxRetries(3) in KafkaConsumerConfig
  - Config: initialInterval=1s, multiplier=2.0, maxInterval=10s
  - Retry pattern: 1s → 2s → 4s before routing to DLT
  - Learned: thundering herd problem, jitter strategies (full/equal/decorrelated)

- [x] Step 10: Debezium CDC — COMPLETE (2026-05-24)
  - PostgreSQL WAL level changed to `logical` (ALTER SYSTEM SET + service restart)
  - Docker infrastructure: Kafka dual-listener (PLAINTEXT:9092 for host, DOCKER:29092 for containers)
  - Debezium connector: PostgresConnector with Outbox Event Router SMT
  - Connector config: explicit snake_case column mappings, `snapshot.mode=never`, `value.converter=StringConverter`
  - OutboxPoller disabled via `@Profile("!cdc")` when `spring.profiles.active=cdc`
  - OutboxEvent entity: replaced `@Lob` with `@Column(columnDefinition = "text")` for CDC-compatible inline storage
  - StockBackRewardConsumer: added `spring.json.value.default.type` per `@KafkaListener` for Debezium messages lacking `__TypeId__` header
  - Issues resolved: Docker networking (dual-listener), Hibernate snake_case vs Debezium defaults, `@Lob` OID problem, timestamp timezone mismatch, `__TypeId__` header gap
  - E2E tested: order → deliver → Debezium WAL capture → Kafka → consumer grants reward → vesting → holding

- [x] Step 11: Saga Orchestrator for Sell-to-Spend — COMPLETE (2026-05-24)
  - Orchestration-based Saga pattern for the Sell-to-Spend flow (3 atomic steps + compensating transactions)
  - SagaStatus enum: 10 states (STARTED through COMPLETED/COMPENSATED/FAILED) with isTerminal()
  - SellToSpendSaga entity: persists saga progress, all input parameters, failureReason, @Version optimistic locking
  - SellToSpendSagaOrchestrator: drives steps, catches failures, runs compensation in reverse order
  - Compensation matrix: step 2 fails → undo step 1 (re-add shares); step 3 fails → undo steps 2+1 (reverse ledger + re-add shares)
  - Timeout detection: @Scheduled(30s) finds stuck sagas and compensates
  - SagaOutboxWriter: lifecycle events (STARTED, STEP_COMPLETED, COMPLETED, COMPENSATING, COMPENSATED, FAILED) published to `sell-to-spend-saga` Kafka topic via shared outbox
  - SagaLifecycleEvent record in commons/event for observability
  - @ConditionalOnProperty toggle: `equitycart.sell-to-spend.strategy=saga` vs `transactional` (matchIfMissing=true)
  - ReferenceType.SELL_TO_SPEND_REVERSAL added for compensation ledger entries
  - Key design: executeSaga() deliberately NOT @Transactional — each step commits independently (eventual consistency)
  - BUILD SUCCESSFUL

- [x] Step 12: Event Sourcing for Portfolio changes — COMPLETE (2026-05-28)
  - PortfolioEvent MongoDB document (portfolio_events collection) with sequenceNumber for ordering
  - PortfolioEventType enum: SHARES_PURCHASED, SHARES_SOLD, SELL_TO_SPEND, SELL_TO_SPEND_COMPENSATED, REWARD_VESTED
  - PortfolioEventStore interface + PortfolioEventStoreImpl: append-only writes with AtomicLong sequence
  - PortfolioProjectionService: rebuilds holdings entirely from event replay (demonstrates event sourcing projection)
  - PortfolioEventController: GET /events, GET /events/projection, GET /events/projection/validate
  - Integrated into TradeServiceImpl, VestingHelperImpl, SellToSpendSagaOrchestrator
  - E2E tested: event timeline, filtered by ticker/time-range, projection rebuild, consistency validation

- [x] Step 13: Notification Service (Observer Pattern + Strategy Pattern) — COMPLETE (2026-05-31)
  - New module: notification-service (14 Java files + build.gradle)
  - commons/event/NotificationEvent record — shared Kafka DTO (userId, notificationType, ticker, qty, price, totalValue, metadata, timestamp)
  - portfolio/event/NotificationPublisher — fire-and-forget KafkaTemplate.send() to `portfolio-notification` topic
  - notification/consumer/NotificationConsumer — @KafkaListener (dedicated group: equitycart-notification-group)
  - Strategy Pattern: NotificationChannelStrategy interface + 3 implementations (logChannel, emailChannel, webhookChannel)
  - Spring Map<String, Bean> auto-injection for runtime strategy resolution
  - NotificationDispatcherImpl: resolves channel from config, builds subject/body per event type, invokes strategy, persists audit log
  - NotificationLog entity + NotificationLogRepository (PostgreSQL audit trail)
  - NotificationController: GET /api/notifications with optional ?type= filter
  - 3 enums: NotificationType, NotificationChannel, NotificationStatus
  - Publishers integrated: TradeServiceImpl (TRADE_EXECUTED), VestingHelperImpl (REWARD_VESTED), SagaOrchestrator (COMPLETED/FAILED via finally block)
  - application.yml: spring.mail (MailHog localhost:1025), equitycart.notification.channel=LOG, webhook-url, recipient-email
  - E2E tested: BUY trade → TRADE_EXECUTED notification logged + persisted + queryable via REST API

## Phase 7: Microservices Decomposition (started 2026-06-01) — COMPLETE (2026-06-12)

### Step Plan

| Step | Deliverable                                                           | Status                           |
| ---- | --------------------------------------------------------------------- | -------------------------------- |
| 1    | Eureka Discovery Server (new module, port 8761)                       | COMPLETE                         |
| 2    | Config Server (new module, port 8888, Git-backed)                     | COMPLETE                         |
| 3    | Spring Cloud Gateway (new module, port 8080, routing + filters)       | COMPLETE                         |
| 4    | Extract User-Service as standalone (port 8081, Eureka client)         | COMPLETE                         |
| 5    | Extract Market-Data-Service as standalone (port 8085)                 | COMPLETE                         |
| 6    | Extract Order-Service as standalone (port 8088)                       | COMPLETE                         |
| 7    | Extract Portfolio-Service as standalone (port 8084)                   | COMPLETE                         |
| 8    | Extract Ledger-Service as standalone (port 8086)                      | COMPLETE                         |
| 9    | Extract Notification-Service as standalone (port 8087)                | COMPLETE                         |
| 10   | OpenFeign clients (replace direct module deps with HTTP calls)        | COMPLETE                         |
| 11   | Correlation ID propagation (MDC + Gateway filter + Feign interceptor) | COMPLETE                         |
| 12   | Docker Compose (full stack: all services + infrastructure)            | COMPLETE                         |
| 13   | End-to-end testing + re-audit                                         | DEFERRED (requires Phase 8 auth) |

### Design Completed

- [x] Phase 7 architecture designed: Eureka (service discovery), Config Server (centralized config), Gateway (routing/filtering), database-per-service pattern
- [x] 13-step implementation plan created and approved
- [x] Service extraction sequence determined (utility → business logic → complex)
- [x] Port allocations decided (8080 Gateway, 8081-8087 services, 8761 Eureka, 8888 Config)
- [x] OpenFeign strategy: replace direct `implementation project()` calls with `@FeignClient` HTTP stubs
- [x] Correlation ID flow: MDC in logs, propagated via Gateway filter + Feign interceptor

### Implementation Completed

- [x] Step 1: Eureka Discovery Server — COMPLETE (2026-06-01)
  - New module: `discovery-server/` with `DiscoveryServerApplication` main class
  - Port: 8761 (Eureka standard)
  - Annotations: `@SpringBootApplication` + `@EnableEurekaServer`
  - Configuration: `register-with-eureka=false`, `fetch-registry=false`, `enable-self-preservation=false` (strict learning mode)
  - Verified: Eureka dashboard accessible at http://localhost:8761, shows "Instances currently registered with Eureka" = 0

- [x] Step 2: Config Server — COMPLETE (2026-06-01)
  - New module: `config-server/` with `ConfigServerApplication` main class
  - Port: 8888 (Config Server standard)
  - Annotations: `@SpringBootApplication` + `@EnableConfigServer`
  - Git backend: Points to separate GitHub repo `equitycart-config` (hybrid monorepo: one main repo, one config repo)
  - Configuration: `spring.cloud.config.server.git.uri` points to https://github.com/mandipjdungcr7/equitycart-config
  - Verified: Config Server running, successfully fetches configs from remote Git repo
  - API tested: GET http://localhost:8888/user-service/default returns merged application.yml + user-service.yml
  - Javadoc added to both DiscoveryServerApplication and ConfigServerApplication (debug-mode explanations, internal flow, API details)

- [x] Step 3: Spring Cloud Gateway — COMPLETE (2026-06-02)
  - New module: `api-gateway/` with `GatewayApplication` main class
  - Port: 8080 (gateway entry point)
  - Annotations: `@SpringBootApplication` + `@EnableDiscoveryClient` (registers itself with Eureka, enables `lb://` URI resolution)
  - Dependencies: `spring-cloud-starter-gateway-server-webflux`, `spring-cloud-starter-config`, `spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-actuator`
  - Configuration: Centralized in `equitycart-config/api-gateway.yml` (fetched via `spring.config.import=configserver:http://localhost:8888`)
  - Routes defined: 6 service routes (user-service, order-service, portfolio-service, market-data-service, ledger-service, notification-service) with `lb://` Eureka-aware URIs
  - Actuator endpoints exposed: health, metrics, info (explicitly configured in management.endpoints.web.exposure.include)
  - Verified: Both api-gateway and equitycart registered with Eureka (http://localhost:8761 shows 2 instances)
  - Gateway properly routes requests to downstream services and resolves services via Eureka discovery
  - **Lessons Learned & Issues Resolved**:
    1. **bootstrap.yml doesn't work in Spring Boot 3.5.8 + Spring Cloud 2025.0.0**: Config import must be in `application.yml`, not `bootstrap.yml` (breaking change from earlier Spring Cloud versions)
    2. **Missing Eureka client dependency**: `spring-cloud-starter-netflix-eureka-client` was required in build.gradle for `@EnableDiscoveryClient` to actually register service with Eureka (without it, no registration logs appear)
    3. **YAML structure error**: Gateway routes were incorrectly under `server.cloud.gateway` instead of `spring.cloud.gateway` (fixed indentation in equitycart-config/api-gateway.yml)
    4. **Actuator endpoints returning 403 on monolithic app (8082) but 200 on gateway (8080)**: Spring Security was blocking `/actuator/**` paths — required explicit authorization rule `http.authorizeHttpRequests(authz -> authz.requestMatchers("/actuator/**").permitAll())`
    5. **All services need explicit `spring-cloud-starter-netflix-eureka-client` dependency** (not auto-provided by other starters)
    6. **Port conflict**: Both services initially on 8080 → changed to 8080 (gateway), 8082 (equitycart)

### Phase 7 Remaining

- [x] Step 4: Extract User-Service as standalone (port 8081, Eureka client) — COMPLETE (2026-06-03)
  - New main class: `UserServiceApplication` with `@SpringBootApplication` + `@EnableDiscoveryClient`
  - Plugin changed from `java-library` to `org.springframework.boot` + `jar { enabled = true }` (dual artifact for transition)
  - Added: `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-boot-starter-actuator`, `runtimeOnly postgresql` to user/build.gradle
  - Local `application.yml`: `spring.application.name: user-service` + `spring.config.import: configserver:`
  - `equitycart-config/user-service.yml` expanded: full datasource config, JPA (ddl-auto: update overrides base validate), JWT, actuator, explicit `eureka.client.serviceUrl.defaultZone`
  - Verified: registered in Eureka at http://localhost:8761, actuator health 200, register API tested and data created in `equitycart_user`
  - **Key lessons**: Strangler Fig pattern (parallel operation), dual Gradle plugin pattern, config duplication during transition is intentional, jwt config stays in app/application.yml until user-service removed from app/build.gradle, `jar { enabled = true }` only needed while monolith depends on module
- [x] Step 5: Extract Market-Data-Service as standalone (port 8085) — COMPLETE (2026-06-04)
  - New main class: `MarketDataServiceApplication` with `@SpringBootApplication` + `@EnableDiscoveryClient`
  - `commons` dependency commented out in market-data/build.gradle (fix for transitive JPA issue — `api` scope in commons leaked JPA to market-data, triggering DataSourceAutoConfiguration crash)
  - Added: `spring-cloud-starter-config`, `spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-actuator` to market-data/build.gradle
  - Local `application.yml`: `spring.application.name: market-data-service` + `spring.config.import: configserver:`
  - `equitycart-config/market-data-service.yml`: MongoDB URI, Redis host/port/timeout, alphaVantage API key, Resilience4j config (retry/circuit-breaker/rate-limiter), actuator, eureka defaultZone
  - `equitycart-config/application.yml` updated: added `eureka.instance.prefer-ip-address: true` + `ip-address: 127.0.0.1` to fix Windows Hyper-V hostname registration (`InetAddress.getLocalHost()` returns WSL2 virtual NAT adapter hostname)
  - Verified: MARKET-DATA-SERVICE registered in Eureka, price endpoint responds via gateway (`lb://market-data-service` route)
  - **Security gap noted**: `spring-security-core` without `spring-boot-starter-security` = no filter chain, no auto-config, all endpoints open; `@PreAuthorize` silently inactive without `@EnableMethodSecurity`; will be addressed in Phase 8
  - **Key lessons**: `api` vs `implementation` Gradle scope (transitive JPA blast radius), Eureka `prefer-ip-address` fix for Windows+Docker/WSL2, spring-security-core vs full starter, which services consume equitycart-config/application.yml
- [x] Step 6: Extract Order-Service as standalone (port 8088) — COMPLETE (2026-06-05)
  - New main class: `OrderServiceApplication` with `@SpringBootApplication`, `@EnableDiscoveryClient`, `@EnableScheduling` (for OutboxPoller), `@EnableJpaRepositories`, `@EntityScan`
  - Port changed to 8088 (8083 occupied by Debezium Kafka Connect REST API)
  - `@EnableJpaRepositories(basePackages = {"com.equitycart.order", "com.equitycart.product"})` — required because OrderServiceImpl injects ProductRepository directly for pessimistic stock locking
  - `@EntityScan(basePackages = {"com.equitycart.order", "com.equitycart.product", "com.equitycart.commons"})` — registers Product/Brand/Category entities + BaseEntity MappedSuperclass
  - Fixed YAML indentation bug: `spring.jpa` was nested under `spring.datasource` in order-service.yml
  - Gateway route updated: `Path=/api/order/**, /api/cart/**` (single predicate, comma-separated — multiple predicates = AND logic)
  - Added Kafka producer config to `equitycart-config/application.yml` base config
  - Removed `spring.profiles.active=cdc` (Debezium not watching equitycart_order DB; OutboxPoller must run)
  - Verified: ORDER-SERVICE registered in Eureka at port 8088, actuator 200, gateway routes both /api/order/** and /api/cart/**
  - **Key lessons**: Three Spring Boot scan pipelines (ComponentScan vs EnableJpaRepositories vs EntityScan), Gateway predicate AND vs OR, cross-module entity table bleed, port conflict awareness
- [x] Step 7: Extract Portfolio-Service as standalone (port 8084) — COMPLETE (2026-06-06)
  - New main class: `PortfolioServiceApplication` with `@SpringBootApplication`, `@ComponentScan` (6 packages + excludeFilters), `@EnableJpaRepositories`, `@EntityScan`, `@EnableMongoRepositories`, `@EnableDiscoveryClient`, `@EnableScheduling`
  - `@ComponentScan` required (not `@EnableJpaRepositories` alone) because portfolio-service injects full service beans (`LedgerServiceImpl`, `ProductServiceImpl`, `MarketDataServiceImpl`) not just repositories
  - `excludeFilters = @Filter(SpringBootApplication.class)` required to prevent `OrderServiceApplication` from being loaded as a configuration, which caused `BeanDefinitionOverrideException` on `orderItemRepository`
  - Two extra config blocks required at runtime: `spring.batch.*` (because `@ComponentScan` of `com.equitycart.product.*` loads `ProductBatchConfig`) and `alphaVantage.*` (because `@ComponentScan` of `com.equitycart.marketdata` loads `WebClientConfig` with `@Value`)
  - Key insight: `@EnableJpaRepositories` is surgical (repositories only, no `@Configuration` classes loaded); `@ComponentScan` is broad (all `@Configuration` beans loaded, triggering their auto-configuration side effects)
  - Javadoc + Log4j added to `PortfolioServiceApplication.java`
  - Q124–Q126 added to learning_log.md
- [x] Step 8: Extract Ledger-Service as standalone (port 8086) — COMPLETE (2026-06-06)
  - New main class: `LedgerServiceApplication` with `@SpringBootApplication` + `@EnableDiscoveryClient` + `@EntityScan({"com.equitycart.ledger", "com.equitycart.commons"})`
  - `@EntityScan` required: `LedgerEntry extends BaseEntity` (`@MappedSuperclass` in commons, outside default scan scope)
  - `@EnableJpaRepositories` NOT required: `LedgerEntryRepository` is within `com.equitycart.ledger.*` (in-scope by default)
  - `@ComponentScan` NOT required: no cross-module service beans needed — simplest extraction in Phase 7
  - No transitive contamination: no foreign `@ComponentScan` = no foreign `@Configuration` classes loaded = no unexpected auto-configuration triggers
  - No REST controllers yet; gateway route `/api/ledger/**` pre-wired for Phase 10 (Feign extraction)
  - Q127–Q128 added to learning_log.md
- [x] Step 9: Extract Notification-Service as standalone (port 8087) — COMPLETE (2026-06-06)
  - New main class: `NotificationServiceApplication` with `@SpringBootApplication` + `@EnableDiscoveryClient` + `@EntityScan({"com.equitycart.notification", "com.equitycart.commons"})`
  - Same clean extraction pattern as ledger-service: all beans in `com.equitycart.notification.*`, only BaseEntity in commons requires `@EntityScan`
  - No `@ComponentScan`, no `@EnableJpaRepositories`, no `@EnableMongoRepositories`, no `@EnableScheduling` needed
  - Kafka consumer config added to `notification-service.yml` (consumer group, deserializers, trusted packages)
  - `equitycart.notification.*` properties added (channel=LOG, webhook-url, recipient-email)
  - **Key clarifications**: product-service NOT extracted (order-service + portfolio-service have direct dependencies — deferred to Phase 10 Feign migration); saga/timeout properties omitted because `matchIfMissing=true` and `@Value` inline defaults cover the designed defaults
  - Q129–Q130 added to learning_log.md
- [x] Step 10: OpenFeign clients — COMPLETE (2026-06-09)
  - `ProductFeignClient` in commons: 4 methods — getProductById, deductStock, restoreStock, getTickerMappingsByBrandId; generates JDK Dynamic Proxy at startup; routes via lb://PRODUCT-SERVICE through Eureka
  - `BrandTickerMappingDTO` in commons: 3-field subset (brandId, tickerSymbol, stockBackPercentage) of full product-service response; Jackson DTO Projection drops remaining fields
  - `ProductDTO` in commons: 6-field subset (id, name, price, stockQuantity, brandId, active)
  - `OrderFeignClient` in portfolio module: 2 methods — getOrderById, updateOrderStatus; lives in portfolio (not commons) because OrderResponse/UpdateOrderStatusRequest are order-service types — moving to commons would create a circular dependency
  - order-service: removed direct ProductRepository injection; `deductStock`/`restoreStock` now use pessimistic lock endpoint in product-service; stock deduction/restoration calls go through ProductFeignClient
  - portfolio-service `SellToSpendServiceImpl` + `SellToSpendSagaOrchestrator`: removed OrderService injection; replaced with OrderFeignClient
  - portfolio-service `StockBackRewardConsumer`: removed ProductRepository + BrandTickerMappingRepository injection; replaced with ProductFeignClient
  - `PortfolioServiceApplication`: `@ComponentScan` no longer covers `"com.equitycart.order"` (prevented OrderServiceImpl loading its own ProductFeignClient dependency); `@EnableJpaRepositories` + `@EntityScan` still cover `"com.equitycart.order"` (OutboxEventRepository proxy + OutboxEvent entity needed by SagaOutboxWriter)
  - product-service: `jar { enabled = true }` + `bootJar { archiveClassifier.set('exec') }` dual artifact for library + executable during migration
  - `ProductServiceApplication` + all new/modified files: Javadoc + Log4j on all uncommitted files
  - **Startup error 1**: ProductFeignClient not found — OrderServiceImpl (loaded via @ComponentScan) injected ProductFeignClient; fix: remove com.equitycart.order from @ComponentScan
  - **Startup error 2**: OutboxEventRepository not found — removed from @EnableJpaRepositories too aggressively; fix: keep com.equitycart.order in @EnableJpaRepositories + @EntityScan
  - All 8 services verified registering in Eureka: API-GATEWAY (8080), USER-SERVICE (8081), ORDER-SERVICE (8088), PORTFOLIO-SERVICE (8084), PRODUCT-SERVICE (8089), MARKET-DATA-SERVICE (8085), LEDGER-SERVICE (8086), NOTIFICATION-SERVICE (8087)
  - openfeign-guide.md created: 12-section exhaustive reference (history, proxy mechanics, startup flow, runtime trace, DTO projection, FeignErrorDecoder, @RequestParam vs @RequestBody, load balancing, circular dependency rules, interview questions)
  - Q131–Q138 added to learning_log.md
- [x] Step 11: Correlation ID propagation — COMPLETE (2026-06-10)
  - `MdcCorrelationFilter` in commons/filter: `OncePerRequestFilter` — reads `X-Correlation-Id` header (generates UUID if absent), stores in Log4j2 `ThreadContext`, echoes in response, cleans up in `finally`
  - `FeignCorrelationInterceptor` in commons/feign: Feign `RequestInterceptor` — reads correlationId from `ThreadContext.get()`, adds as `X-Correlation-Id` header to every outgoing Feign HTTP call; auto-registered via `@Component` into all `@FeignClient` beans
  - `CorrelationIdGatewayFilter` in api-gateway/filter: Spring Cloud Gateway `GlobalFilter` + `Ordered` — generates/propagates UUID at entry point; uses `exchange.mutate()` (immutable WebFlux API); `HIGHEST_PRECEDENCE` ordering; `Mono.fromRunnable()` for response header
  - `equitycart-config/application.yml` logging pattern: `%X{correlationId}` in console pattern reads ThreadContext on every log event
  - **Key design decisions**: Gateway uses `GlobalFilter` (not `default-filters` YAML — SpEL evaluated at startup, not per-request); downstream services use `OncePerRequestFilter` (Tomcat/Servlet); gateway has NO MDC/ThreadContext (Netty event loop threads cannot use thread-local)
  - **MDC/ThreadContext resolution**: Replaced `org.slf4j.MDC` with `org.apache.logging.log4j.ThreadContext` in both filter and interceptor — project uses Log4j2 natively; SLF4J MDC bridged to ThreadContext via log4j-slf4j-impl but added unnecessary indirection
  - `mdc-correlation-guide.md` created: comprehensive reference covering request flow diagram, line-by-line filter explanation, GlobalFilter vs default-filters comparison, Netty vs Tomcat filter types, immutability/mutate() pattern, Correlation ID vs TraceId/SpanId hierarchy, background thread handling
  - Javadoc added to all 3 implementation files + `GatewayApplication.java` updated with `@see`
- [x] Step 12: Docker Compose (full stack: all services + infrastructure) — COMPLETE (2026-06-12)
  - Two-file architecture: `docker-pets.yml` (infrastructure) + `docker-compose-services.yml` (application)
  - Infrastructure: PostgreSQL (7 DBs via init-db.sh), Kafka (KRaft, dual-listener), Redis, MongoDB, Debezium, MailHog
  - Application: 10 services (discovery, config-server, gateway, 7 business services) all containerized
  - Start scripts: `start-pets.sh` (infra + readiness wait) + `start-services.sh` (discovery → config → all)
  - `build-images.sh`: builds all 10 Docker images from bootJar output
  - Config pattern: `${ENV_VAR:local-default}` in equitycart-config repo — works in both local dev and Docker
  - Fixed: Kafka volume permissions (user: "0"), single-broker replication factors, Git Bash path mangling (MSYS_NO_PATHCONV), Eureka Hyper-V hostname (prefer-ip-address: true), config-server DNS refresh (refresh-rate: 3600)
  - All 10 services UP and registered in Eureka, inter-service communication verified
- [~] Step 13: End-to-end testing + re-audit — DEFERRED to Phase 8
  - Reason: Full end-to-end flow requires per-service JWT validation (userId extraction from SecurityContext). Without Phase 8's OAuth2 Resource Server, SecurityContextHolder returns null in standalone services → NullPointerException on all userId-dependent endpoints.
  - Infrastructure-level validation done: all 10 services register in Eureka, Feign HTTP calls resolve via lb://, Kafka topics produce/consume, Docker Compose stack starts cleanly.
  - Will execute full business-flow E2E testing after Phase 8 adds per-service auth.

### Phase 7 Architectural Notes (Strangler Fig — Remaining Coupling)

**Fully decoupled (HTTP via Feign):**

- Order → Product (ProductFeignClient: deductStock, restoreStock, getProductById)
- Portfolio → Product (ProductFeignClient: getProductById, getTickerMappingsByBrandId)
- Portfolio → Order (OrderFeignClient: getOrderById, updateOrderStatus)

**Still coupled (same-JVM direct injection — to be resolved in Phase 10):**

- Portfolio → Ledger: injects `LedgerServiceImpl` directly (needs LedgerFeignClient)
- Portfolio → MarketData: injects `MarketDataServiceImpl` directly (needs MarketDataFeignClient)
- Portfolio → Order entities: `@EntityScan`/`@EnableJpaRepositories` covers `com.equitycart.order` for `OutboxEvent` + `SagaOutboxWriter` (portfolio should own its own outbox table)

**Target state (post Phase 10):** Each service's `build.gradle` contains ONLY `implementation project(':commons')` — no other service modules. All cross-service communication via HTTP (Feign) or messaging (Kafka).

## Phase 8: Security Hardening — COMPLETE (started 2026-06-12)

### Approach: Incremental (Custom JWT → OAuth2/Keycloak)

Phase 8 follows a two-track approach: first distribute existing HMAC-SHA256 JWT validation to all services (unblocks E2E testing immediately), then migrate to Keycloak + OAuth2 Resource Server (production-grade, asymmetric keys, JWKS). Dual-mode: custom auth stays alongside Keycloak for flexibility.

### Step Plan

| #   | Step                                                                           | Status   |
| --- | ------------------------------------------------------------------------------ | -------- |
| 1   | Extract JWT validation to commons (shared library)                             | COMPLETE |
| 2   | Wire commons security into all 6 downstream services                           | COMPLETE |
| 3   | Feign interceptor — propagate Authorization header                             | COMPLETE |
| 4   | Gateway-level JWT pre-validation (GlobalFilter)                                | COMPLETE |
| 5   | Keycloak Docker setup + realm/client/role configuration                        | COMPLETE |
| 6   | Migrate to OAuth2 Resource Server (spring-boot-starter-oauth2-resource-server) | COMPLETE |
| 7   | Gateway Token Relay (replace custom filter with Spring Security)               | COMPLETE |
| 8   | Rate limiting at Gateway (Redis-backed, token bucket)                          | COMPLETE |
| 9   | OWASP security headers + secrets management (env vars)                         | COMPLETE |
| 10  | E2E security integration tests (completes deferred Phase 7 Step 13)            | DEFERRED |

### Steps 1-4 Completion Summary (2026-06-18)

**Step 1 — Commons JWT Library:** JwtTokenValidator interface + JwtTokenValidatorImpl (HMAC-SHA256 via JJWT 0.12.6). JwtAuthenticationFilter (OncePerRequestFilter, sets SecurityContext with userId as Long principal). SecurityAutoConfig with @ConditionalOnProperty("equitycart.security.enabled") gate. jwt.secret moved to shared application.yml in Config Server.

**Step 2 — All Services Wired:** SecurityAutoConfig activated via `equitycart.security.enabled: true` in each service's Config Server YAML. @EnableMethodSecurity activates existing @PreAuthorize annotations. Removed explicit spring-security-core from build.gradle (comes transitively via commons).

**Step 3 — Feign Auth Propagation:** FeignAuthorizationInterceptor reads Authorization from RequestContextHolder and copies to outgoing Feign request. ServiceTokenProvider fallback for non-HTTP contexts (Kafka consumers, @Scheduled) — generates short-lived JWT (subject=0, role=SERVICE, 60s expiry).

**Step 4 — Gateway Pre-Validation:** JwtValidationGatewayFilter (GlobalFilter) validates JWT at API Gateway edge before routing. Invalid tokens rejected with 401 (saves network hop). Open paths (/api/auth/**, /actuator/**) skip validation. Ordered after CorrelationIdGatewayFilter.

### Obstacles Encountered & Resolved

| Obstacle                                          | Root Cause                                                  | Fix                                                              |
| ------------------------------------------------- | ----------------------------------------------------------- | ---------------------------------------------------------------- |
| ReadOnlyHttpHeaders UnsupportedOperationException | `.then()` runs after response committed → headers sealed    | ServerHttpResponseDecorator intercepts writeWith() before commit |
| Invalid HTTP method: PATCH                        | HttpURLConnection (JDK default) predates RFC 5789           | Added feign-hc5 (Apache HttpClient 5)                            |
| 403 on Feign from Kafka consumer                  | RequestContextHolder null on non-HTTP threads               | ServiceTokenProvider generates machine-identity JWT              |
| ServiceToken NumberFormatException                | subject="SYSTEM" → Long.parseLong() fails                   | Changed to subject="0" (sentinel userId)                         |
| ServiceToken ClassCastException                   | roles="SERVICE" (String) → List cast fails                  | Changed to List.of("SERVICE") (JSON array)                       |
| SecurityAutoConfig anyRequest() broken            | Multiple anyRequest() calls — only first applies            | Single anyRequest().authenticated() + @PreAuthorize for roles    |
| Sell-to-spend defaulting to transactional         | Config migration gap — property not in Config Server        | Added to equitycart-config/portfolio-service.yml                 |
| PKIX path building failed in Docker               | Zscaler TLS interception → private CA not in JVM truststore | keytool -importcert with ZscalerRootCA.pem in Dockerfile         |
| Docker COPY file not found                        | Build context = equitycart/, not docker/                    | COPY docker/ZscalerRootCA.pem (context-relative path)            |
| .gitignore not working for cert                   | Pattern missing equitycart/ prefix                          | Used equitycart/docker/\*.pem                                    |
| Docker PostgreSQL port conflict                   | Host port 5432 occupied by org setup                        | Mapped to 9432:5432 (host:container)                             |
| JDBC URLs broken in Docker                        | Used host port (9432) inside container network              | Changed to internal port (5432) for container-to-container       |
| StockBackRewardConsumer retry loop                | Portfolio-service missing SPRING_DATA_REDIS_HOST            | Added SPRING_DATA_REDIS_HOST=redis to docker-compose             |

### Step 5 Completion Summary (2026-06-23)

**Step 5 — Keycloak Docker Setup + Realm Configuration:**

- Keycloak 26.0 (quay.io/keycloak/keycloak:26.0) added to docker-pets.yml, sharing PostgreSQL container (keycloak database)
- equitycart-realm.json created: auto-imported via `--import-realm` on first boot
  - 4 realm roles: CUSTOMER (default on registration), SELLER, ADMIN, SERVICE
  - 3 clients: equitycart-gateway (confidential, auth-code + ROPC), equitycart-frontend (public, PKCE S256), equitycart-services (client-credentials)
  - 2 protocol mappers per client: roles-mapper (flattens realm_access.roles → top-level `roles` claim), userId-mapper (user attribute → token claim)
  - 3 test users: customer1/seller1/admin1 with pre-assigned roles and userId attributes mapping to database IDs 1/2/3
  - Service account user: service-account-equitycart-services (gets SERVICE role for machine-to-machine tokens)
- init-db.sh: added `CREATE DATABASE keycloak;`
- start-pets.sh: OIDC discovery endpoint readiness check (replaces /health/ready which is on management port 9000)
- Dual-mode architecture: Custom HS256 auth endpoints remain alongside Keycloak RS256 — services will accept EITHER issuer after Step 6

**Obstacles:**

- /health/ready not accessible on main port (8180) — Keycloak 24+ serves health on management port 9000; fixed by polling OIDC discovery endpoint instead
- KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD deprecated in 26.x — replaced with KC_BOOTSTRAP_ADMIN_USERNAME/KC_BOOTSTRAP_ADMIN_PASSWORD
- --import-realm only imports if realm doesn't exist (common gotcha: editing JSON and restarting does nothing)

### Step 6 Completion Summary (2026-06-26)

**Step 6 — OAuth2 Resource Server Migration (product-service first):**

- Added `spring-boot-starter-oauth2-resource-server` to commons/build.gradle (api scope — transitive to all services)
- Created `KeycloakJwtAuthenticationConverter` in security/impl/ — converts Spring's Jwt object to UsernamePasswordAuthenticationToken(Long userId, null, authorities), maintaining backward compat with `(Long) authentication.getPrincipal()`
- Created `OAuth2ResourceServerConfig` in config/ — @ConditionalOnProperty(mode=oauth2), registers converter via `.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(...)))`
- Modified `SecurityAutoConfig` condition from `enabled=true` to `mode=custom`
- All service configs migrated: `equitycart.security.enabled: true` → `equitycart.security.mode: custom` (or `oauth2` for product-service)
- application.yml: added `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` with env var override for Docker
- docker-compose-services.yml: added `KEYCLOAK_JWKS_URI` env var for product-service pointing to Keycloak JWKS certs endpoint

**Key design decisions:**

- Used `jwk-set-uri` (not `issuer-uri`) to avoid issuer mismatch between Docker DNS (keycloak:8080) and token's iss claim (localhost:8180)
- Dual-mode via @ConditionalOnProperty: services can be individually switched from custom→oauth2
- Converter uses @Component (not defined as @Bean in config) for simplicity of dependency injection

**Obstacles:**

- Wrong Converter interface imported (Jackson's Converter vs Spring's Converter) — Jackson has getInputType/getOutputType; Spring's has single convert() method
- Wrong JWT type imported (Nimbus JWT vs Spring Security Jwt) — Spring's Jwt is the decoded/validated token object
- Tried registering converter as servlet Filter (.addFilterBefore) — converter is not a Filter, it's wired inside .oauth2ResourceServer() config
- Duplicate `security:` key in YAML causing wrong property path (spring.security.security.oauth2... instead of spring.security.oauth2...)
- issuer-uri vs jwk-set-uri confusion — issuer-uri validates iss claim (fails when Docker hostname differs from token's issuer); jwk-set-uri just fetches keys

### Step 7 Completion Summary (2026-06-28)

**Step 7 — Gateway Reactive OAuth2 (replace HS256 filter with Spring Security WebFlux):**

- Created `api-gateway/.../config/SecurityConfig.java` — `@EnableWebFluxSecurity`, `SecurityWebFilterChain` with `oauth2ResourceServer()` reactive DSL; private `keycloakReactiveConverter()` returning `Mono<AbstractAuthenticationToken>` (required by WebFlux `flatMap` composition)
- Gateway now validates RS256 tokens via `NimbusReactiveJwtDecoder` auto-configured from `jwk-set-uri` in api-gateway.yml
- Token forwarded unchanged via `ProxyExchange` to downstream services (defense in depth — services validate independently)

**Bugs resolved**:

- `JwtValidationGatewayFilter @Component`: HS256 filter ran before `SecurityWebFilterChain`, rejected all RS256 tokens → fix: commented out `@Component` (line 65)
- `JwtAuthenticationFilter @Component`: Spring Boot's `FilterRegistrationBean` auto-registered it as standalone servlet filter in all services → double-validation in oauth2 mode → fix: commented out `@Component` (line 57)

**Key concepts**: `SecurityWebFilterChain` (reactive) vs `SecurityFilterChain` (servlet) — completely separate infrastructure; `ReactiveSecurityContextHolder` uses Reactor Context (not ThreadLocal — event loop thread serves many requests)

### Step 8 Completion Summary (2026-06-29)

**Step 8 — Rate Limiting at Gateway (Redis token bucket):**

- Created `api-gateway/.../config/RateLimiterConfig.java` — `@Bean KeyResolver userKeyResolver()`; extracts userId from `ReactiveSecurityContextHolder` for authenticated; `.defaultIfEmpty(remoteAddress)` falls back to IP for anonymous requests (brute-force protection on login endpoint)
- api-gateway.yml: `RequestRateLimiter` default-filter — `replenishRate: 10`, `burstCapacity: 20`, `key-resolver: "#{@userKeyResolver}"` (SpEL bean reference)
- Redis Lua script: atomic check-and-decrement prevents race condition across multiple gateway instances

### Step 9 Completion Summary (2026-06-30)

**Step 9 — OWASP Security Headers:**

- Created `api-gateway/.../filter/SecurityHeadersGlobalFilter.java` — `@Component GlobalFilter` at `LOWEST_PRECEDENCE`; `chain.filter(exchange).then(Mono.fromRunnable(...))` sets 6 OWASP headers after downstream response arrives but before Netty flushes
- Headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security: max-age=31536000`, `Content-Security-Policy: default-src 'self'`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`
- Bug found and fixed: missing `@Component` → `GatewayAutoConfiguration` never collected the bean → headers never sent

### Phase 8 Complete — Architecture

```
Browser / Postman (Authorization: Bearer <Keycloak RS256 token>)
    │
    ▼
API Gateway (port 8080, Netty/WebFlux)
    ├── SecurityWebFilterChain (@EnableWebFluxSecurity)
    │   ├── NimbusReactiveJwtDecoder → JWKS → RS256 validation → userId + roles
    │   └── AuthorizationWebFilter → /api/auth/** permit, rest authenticated
    ├── RequestRateLimiter → Redis token bucket (10 req/sec per userId, per IP anon)
    ├── SecurityHeadersGlobalFilter → 6 OWASP headers on every response
    └── ProxyExchange → downstream (Authorization: Bearer forwarded unchanged)
        │
        ▼
    Service (port 8081-8087, Tomcat/Servlet)
        ├── mode=oauth2: BearerTokenAuthenticationFilter + NimbusJwtDecoder (servlet)
        │   └── KeycloakJwtAuthenticationConverter → SecurityContextHolder
        ├── mode=custom: JwtAuthenticationFilter (JJWT HS256) → SecurityContextHolder
        ├── FeignAuthorizationInterceptor → propagates token to Feign calls
        └── @PreAuthorize → RBAC (requires @EnableMethodSecurity)
```

### Phase 8 Remaining

- [ ] Unit + Integration tests with Testcontainers (deferred to Phase 9+ maintenance period)
- [ ] ServiceTokenProvider → Keycloak Client Credentials migration (latent: HS256 service tokens fail on oauth2-mode services when called from Kafka consumer → Feign fallback path)

### Phase 9 Completion Summary (2026-08-07)

**Phase 9 — Observability (Logging, Metrics, Tracing, Alerting):**

- Structured logging standardized with per-service `log4j2-spring.xml` (console + rolling JSON file) and correlation context propagation.
- Prometheus metrics scraping enabled via actuator endpoints and service-level Micrometer instrumentation.
- Grafana dashboards provisioned and connected to Prometheus data source.
- Distributed tracing enabled with Micrometer Tracing + Zipkin exporter and centralized trace UI.
- Custom business metrics added:
  - order placement success/failure + latency
  - portfolio trade and reward metrics
  - notification dispatch success/failure/channel counts
- Alert rules configured in Grafana for:
  - Service down (`up == 0`)
  - Error-rate threshold breaches
  - High p99 latency
- Infra networking hardened for split-compose startup using a shared external Docker network and startup-time network creation.
- Centralized EFK/Fluentd stack documented as **environment-blocked** by corporate Zscaler image access policy; accepted fallback is structured per-service logs + `core-loglens`.

## Phase 10 — CQRS & Advanced Features (Topic 1 COMPLETE, Topic 2 Concept Complete)

### Topic 1 Completion Summary (2026-01-08)

**Phase 10 Topic 1 — CQRS Portfolio Read Model (Event-Driven Projection):**

- **Deliverables Completed:**
  - CQRS Architecture: Write-model (PostgreSQL) + Read-model (MongoDB)
  - Event-driven projection: PortfolioReadModelOutboxConsumer listens to portfolio-projection Kafka topic
  - Full-rebuild strategy: each Kafka event triggers complete user snapshot rebuild from PostgreSQL
  - Idempotency via MongoDB upsert by userId: Kafka at-least-once + upsert = exactly-once semantics
  - Debezium CDC primary path: PostgreSQL WAL → Kafka topic (via Kafka Connect)
  - OutboxPoller fallback path: application polls outbox_events table → publishes to Kafka
  - Feature flag (@Profile("!cdc")): single Spring app runs in CDC mode or polling mode
  - userId as Kafka partition key: all user events route to same partition → consistent rebuild order
  - Scheduled 24-hour reconciliation job: detects and repairs drift between PostgreSQL and MongoDB
  - Method-level JavaDoc and logging: PortfolioReadModelSynchronizer (rebuildReadModelForUser algorithm), PortfolioOutboxWriter (8 event methods with parameter docs)
  - Explained why synchronizeReadModels scheduled job is commented (event-driven supersedes polling)
  - @Lob gotcha documented: use @Column(columnDefinition = "text") instead (CDC compatibility)

- **Code Quality:**
  - All Topic 1 files compile successfully (gradle clean build -p portfolio)
  - Spotless formatting applied and validated
  - Comprehensive JavaDoc and logging at debug/info/error levels
  - No unrelated code changes — focused on documentation polish only

- **Manual E2E Validation Checklist (Ready for execution):**
  - BUY trade → outbox row → Debezium → Kafka → consumer rebuilds → API returns updated portfolio
  - SELL trade → outbox row → Debezium → Kafka → consumer rebuilds → API returns updated portfolio
  - REWARD_GRANTED → outbox row → Debezium → Kafka → consumer rebuilds → API returns pending reward
  - REWARD_VESTED → outbox row → Debezium → Kafka → consumer rebuilds → API returns vested reward
  - SELL_TO_SPEND_INITIATED → outbox row → Debezium → Kafka → consumer rebuilds → API returns saga state
  - REFUND_RESTORED (compensation) → outbox row → Debezium → Kafka → consumer rebuilds → API returns restored holdings

- **Architectural Decisions Documented:**
  - Correctness-first over premature optimization (full rebuild vs incremental delta)
  - Event-driven eliminates time-based polling
  - Scheduled reconciliation for drift detection and repair (separate from happy path)
  - Separate read and write technologies for CQRS performance tradeoff
  - @Profile for deployment flexibility (CDC vs polling mode)

- **Learning Files Updated:**
  - kafka-learning.md: Section 15 (Phase 10 Topic 1 CQRS, CDC implementation, event-driven rebuild)
  - learning_log.md: Phase 10 Topic 1 section (10 roadblocks, 9 core concepts, 5 interview Q&A)
  - progress.md: Topic 1 marked COMPLETE
  - (Pending: java-reference.md, microservice-patterns.md, springboot-reference.md — see tasks below)

- **Residual Risks & Open Questions:**
  - Full rebuild latency: scaling to millions of users may require incremental delta projection (deferred to when metrics show bottleneck)
  - Manual E2E validation in progress (not automated JUnit suite) — sufficient for MVP confidence
  - CDC requires external Kafka Connect infrastructure — production dependency
  - Consumer group rebalancing on partition count changes (not tested yet)

### Topic 2 Completion Summary (2026-08-14)

**Phase 10 Topic 2 — Stock Gifting Saga (Peer-to-Peer Transfer via Orchestration):**

- **Deliverables Completed:**
  - Gift API contract: `GiftRequest` (receiverId, tickerSymbol, quantity, idempotencyKey) + `GiftResponse` (sagaId, status, giver/receiver/ticker)
  - Saga state model: `GiftSagaStatus` enum with terminal-state helper, `GiftSaga` JPA entity with `@Version` optimistic lock, `GiftSagaRepository` with idempotency key + timeout queries
  - Orchestration layer: `GiftSagaOrchestrator` with three forward steps:
    1. Debit giver holding (capture transfer price/value at saga creation)
    2. Credit receiver holding (use captured transfer price)
    3. Record ledger entry (use captured transfer dollar value)
  - Compensation path: reverse-order undo with explicit `completedSteps` tracking (0/1/2/3)
  - Timeout detector: `@Scheduled` scanner for non-terminal stale sagas beyond 30s threshold
  - Outbox visibility: `GiftSagaOutboxWriter` emits lifecycle events to `gift-saga` Kafka topic
  - REST integration: `PortfolioFacade` + `PortfolioFacadeImpl` + `PortfolioController` endpoint (`POST /api/portfolio/gift`)
  - Dual-layer idempotency: client-supplied `idempotencyKey` for HTTP retry safety + saga status gates for step-level idempotency

- **Code Quality:**
  - All Topic 2 files compile successfully (gradle clean build -p portfolio)
  - All classes have comprehensive JavaDoc documenting business logic + parameter precision
  - Extensive logging at debug/info/error levels for observability
  - Monetary precision: BigDecimal fields with scale=4 for transferPricePerShare and transferDollarValue
  - No unrelated code changes — focused on gifting saga only

- **Critical Correction During Verification:**
  - Initial ledger calls used `BigDecimal.ZERO` instead of actual transfer amounts
  - Fixed: added `transferPricePerShare` and `transferDollarValue` fields to GiftSaga entity
  - Captured at saga creation: ensures all retries use identical monetary values
  - Compensation ledger uses same positive value for reverse entry (not absolute value logic)
  - Giver re-add uses transferred price, not zero

- **Why Topic 2 Matters Architecturally:**
  - Not "regular buy/sell" — dedicated peer-to-peer transfer workflow
  - Both users' holdings must remain balanced across retries (idempotency essential)
  - Partial failures must not leak shares or create double-credits
  - Saga proves compensation pattern works for user-triggered transfers (not just async Kafka-driven workflows)

- **Manual E2E Validation Checklist (Ready for execution):**
  - Happy path: giver has 100 AAPL at $10/share → gift 50 to receiver → ledger records $500 → giver has 50, receiver gains 50
  - Duplicate idempotency key: client retries with same idempotencyKey → saga returns cached result (no double transfer)
  - Step-2 failure with compensation: orchestrator fails during credit receiver → compensation debits giver again, sets status COMPENSATED
  - Timeout recovery: orchestrator crashes mid-saga → timeout detector finds stale saga → compensation runs automatically
  - Ledger audit trail: verify ledger entries record actual price and value, not zeros

- **Dual-Layer Idempotency Pattern (Topic 2 Contribution):**
  - CLIENT LAYER: `findByIdempotencyKey()` prevents retries from creating duplicate sagas
  - SAGA LAYER: status gates + natural idempotency prevent re-execution of individual steps
  - Both layers protect against different failure modes (network timeouts vs in-process crashes)

- **Learning Files Updated:**
  - learning_log.md: Phase 10 section now includes Q103–Q106 (Topic 2 concepts on compensation, idempotency, timeout detection)
  - microservice-patterns.md: Section 2.5 (Idempotency) augmented with Topic 2 Gift Saga deep dive demonstrating dual-layer idempotency vs Topic 8 single-layer
  - (Pending: kafka-learning.md, java-reference.md, phase-10-learning-deep-dive.md topic assignment details)

- **Residual Risks & Open Questions:**
  - Topic 2 ledger semantic fix (ZERO → actual values) increases audit trail correctness but must be validated in E2E
  - Gift transfer at giver's average buy price (cost basis) is deterministic but differs from market price — business decision validated
  - Timeout threshold (30s) adequate for monolith; distributed deployment may require longer threshold

### Topic 3 Completion Summary (2026-08-15)

**Phase 10 Topic 3 — Flash Sale Stock Drops (Distributed Lock + Burst Control):**

- **Deliverables Completed:**
  - Distributed lock manager: `FlashSaleLockManager` using Redis `SET NX EX` + Lua compare-and-delete release script
  - Lock key format: `flash-sale:lock:{productId}` — single product scoped, enables concurrent purchases of different products
  - Active window validation: Config-driven with three properties (enabled, start-time, end-time), ISO-8601 parsing, fail-closed on errors
  - Dual-phase idempotency: pre-lock check (fast path) + post-lock re-check (race-safe path) via `findByIdempotencyKey()`
  - Bounded retries: 3 attempts with exponential backoff (50ms × attempt#), max 300ms total wait on lock contention
  - Stock compensation: Tracks `stockDeducted` flag, restores stock on order save failure to prevent orphaned deductions
  - Cache invalidation: Dual-layer `@Caching` decorator on `deductStock()` and `restoreStock()` invalidates both "products" and product-specific caches
  - REST integration: `OrderController.placeFlashSaleOrder()` bypasses cart, direct to stock check + order placement

- **Code Quality:**
  - All Topic 3 files compile successfully (gradle clean build -p order)
  - All classes have comprehensive JavaDoc explaining Redis SET NX EX semantics, lock TTL, Lua release script ownership validation
  - Extensive logging at debug/info/warn levels: lock acquisition attempts, window validation, compensation actions, cache eviction
  - Method-level documentation with "what, why, how" for `isFlashSaleActive()`, `acquireFlashSaleLock()`, compensation flow
  - Monetary precision: `BigDecimal` with proper scale for stock prices and transfer values
  - No unrelated code changes — focused on flash-sale lock behavior only

- **Lock Semantics & Concurrency Behavior:**
  - Product-scoped lock: Only ONE request per productId holds the Redis lock at a time
  - Different productIds = different Redis keys = concurrent execution possible (e.g., 100 users can buy different products simultaneously)
  - Lock TTL: 10 seconds (prevents lock leaks from service crashes, release via Lua prevents stale release race)
  - Lua release script validates owner matches before delete (prevents accidental release by wrong owner due to clock skew)

- **Active Window Validation Pattern:**
  - Config properties: `equitycart.flash-sale.enabled`, `equitycart.flash-sale.start-time`, `equitycart.flash-sale.end-time`
  - Timestamp format: ISO-8601 Instant (e.g., "2026-08-15T10:00:00Z")
  - Validation timing: checked BEFORE lock acquisition (rejects upfront if window closed)
  - Empty/blank times = open window (sale always active when enabled)
  - Parsing errors logged and treated as inactive (fail-closed security model)

- **Dual-Phase Idempotency Pattern (Topic 3 Contribution):**
  - PHASE 1 (Before Lock): Fast-path check `findByIdempotencyKey()` for duplicate requests in flight
  - PHASE 2 (After Lock): Re-check idempotency key under lock to catch concurrent requests that both passed Phase 1
  - Result: prevents duplicate stock deductions and ensures at-most-once semantics under high concurrency

- **Compensation Pattern (Stock Restoration):**
  - If order save fails AFTER stock deduction, `restoreStock()` called automatically
  - Uses captured quantity and productId to restore original amount
  - Cache eviction ensures next stock check reflects restored amount
  - Prevents orphaned deductions and maintains ledger+portfolio consistency

- **Manual E2E Validation Checklist (Ready for execution):**
  - Happy path: Window active, stock available, lock acquired, order created, response 201
  - Window closed: Request rejected with 423 (or 409 if using FlashSaleBusyException), no order created
  - Lock contention: 100 concurrent requests, 1 succeeds, 99 retry with backoff, no overselling
  - Idempotency: Duplicate idempotencyKey within window → cached result, no double-deduction
  - Compensation: Order save fails (DB error simulated) → stock restored, next request succeeds
  - Cache coherence: After purchase, stock reduced, API reflects new quantity, no stale reads

- **Learning Files Updated:**
  - (Pending: java-reference.md, learning_log.md, microservice-patterns.md, springboot-reference.md, kafka-learning.md)

- **Residual Risks & Open Questions:**
  - Lock TTL (10s) set conservatively; distributed deployment may require longer/shorter threshold
  - Cache invalidation strategy (allEntries=true) safe but impacts concurrent reads; incremental invalidation deferred
  - Window parsing relies on app startup — changing config at runtime requires restart (ConfigServer refresh not implemented)
  - Load testing not yet run — 300ms max retry wait may be insufficient under extreme burst traffic (p99 measurements needed)

### Topic 4 Completion Summary (2026-08-17)

**Phase 10 Topic 4 — Price Alert Watchlist (Scheduled Async Evaluation):**

- **Deliverables Completed:**
  - Alert domain under `portfolio/alerts/`: `PriceAlert` + `AlertAuditLog` entities (both `extends BaseEntity` → `Long` IDs), `AlertCondition` (ABOVE/BELOW/BETWEEN/CROSSING) and `AlertEventType` enums
  - `PriceAlertRepository` / `AlertAuditLogRepository` (Spring Data JPA, `Long` keys, ownership-safe `findByIdAndUserId`)
  - `AlertConditionEvaluator` — pure, side-effect-free condition logic
  - `PriceAlertService` — CRUD + threshold validation + duplicate (409) + per-user active quota (50) + soft-delete + audit writes
  - `AlertEvaluationService` — `@Scheduled(fixedDelay)` loop: reads active alerts, fetches price via existing `MarketDataService`, evaluates, publishes `NotificationEvent` on trigger, stamps cooldown, writes audit
  - Record DTOs: `CreatePriceAlertRequest`, `UpdatePriceAlertRequest`, `PriceAlertResponse`, `AlertAuditLogResponse`
  - REST surface added to existing `PortfolioController` (`/api/portfolio/alerts` POST/GET/PUT/DELETE + `/{id}/history`) via `PortfolioFacade`/`PortfolioFacadeImpl` — no separate controller
  - notification-service: added `PRICE_ALERT_TRIGGERED` to `NotificationType` enum + one dispatcher case
  - Config: `equitycart.alerts.evaluation.fixed-delay-ms` / `initial-delay-ms` in `portfolio-service.yml`; tables auto-create via existing `ddl-auto: update`

- **Architecture Decision — Reuse over Reinvent (key lesson):**
  - First draft over-engineered the feature: `PortfolioPriceService` stub, an in-portfolio `NotificationService` with WebSocket/Email/SMS/InApp handler classes (none existed → compile errors), per-alert `notificationChannels`, unused Spring `ApplicationEvent`s, and a redundant scheduler config
  - Correction: deleted 7 files and reused existing infrastructure — `MarketDataService` (Redis-cached prices) for reads, and `NotificationPublisher` → `portfolio-notification` Kafka topic → `NotificationDispatcherImpl` for delivery (channel chosen by config, not per-alert)
  - Net result: fewer classes, compiles clean, matches the module's controller→facade→service→market-data→Kafka pattern

- **CROSSING Detection — self-contained:**
  - `PriceAlert.lastEvaluatedPrice` stores the price seen last cycle; the evaluator passes it as `previousPrice`, so CROSSING (`prev <= threshold && curr > threshold`) needs no market-data history join
  - Written back every cycle (even on non-match) so the "before" value is always current

- **Cooldown Anti-Spam:**
  - `isCooldownExpired()` = active AND (`lastTriggeredAt == null` OR `now > lastTriggeredAt + cooldownMinutes`)
  - Condition met but cooling down → `COOLDOWN_SKIPPED` audit row, no notification (prevents firing every 5s while price stays past threshold)

- **Code Quality (polish pass 2026-08-17):**
  - All alert files + integration files compile with no errors/warnings
  - Removed dead code (unused `alertsByTicker` grouping, redundant ownership checks) and stale Javadoc referencing the deleted design (channels, `nextAlertEligibleAt`, "send via all channels")
  - Lowered per-branch evaluator logs from INFO → TRACE (they fire per-alert per-cycle); INFO reserved for cycle start/end and actual triggers
  - Method-level JavaDoc with what/why/how on evaluator, service, and scheduled flow

- **Manual E2E Validation Checklist (ready for execution):**
  - Create alert (POST) → 201, `CREATED` audit row
  - Duplicate (same user+ticker+condition+threshold1) → 409
  - Quota: 50 active alerts → 51st → 400
  - Price crosses threshold → within ≤ (delay) a `PRICE_ALERT_TRIGGERED` notification is published + `TRIGGERED` audit; `lastTriggeredAt` stamped
  - Repeat within cooldown → `COOLDOWN_SKIPPED`, no notification; after cooldown → fires again
  - CROSSING fires once on transition, not again while price stays above
  - Deactivate (DELETE) → 204, `active=false`, skipped by evaluator; history (GET) newest-first

- **Learning Files Updated:** progress.md, learning_log.md, phase-10-design-plan.md, phase-10-learning-deep-dive.md, microservice-patterns.md, springboot-reference.md

- **Residual Risks & Open Questions:**
  - Full-table scan of active alerts every cycle — fine at current scale; shard-by-ticker is the future optimization
  - Notification publish is best-effort (fire-and-forget); a broker outage can drop a notification while the trigger is still recorded locally — an outbox pattern would close this gap if guaranteed delivery is required
  - `AlertEventType` has 3 reserved values (`CONDITION_NOT_MET`, `REACTIVATED`, `NOTIFICATION_FAILED`) not yet emitted

## Phase Checklist

- [x] Phase 0: Foundation & Setup (Week 1)
- [~] Phase 1: User Service & Security (Weeks 2-3) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 2: Product Catalog & Batch Import (Weeks 4-5) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 3: Order Service & Cart (Weeks 6-7) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 4: Market Data - Reactive (Weeks 8-9) — FUNCTIONAL COMPLETE (tests deferred)
- [~] Phase 5: Portfolio & Stock-Back Engine (Weeks 10-12) — FUNCTIONAL COMPLETE (reward grant deferred to Phase 6)
- [x] Phase 6: Event-Driven Architecture (Weeks 13-15) — COMPLETE
- [x] Phase 7: Microservices Decomposition (Weeks 16-18) — COMPLETE (E2E testing deferred to Phase 8)
- [x] Phase 8: Security Hardening (Weeks 19-20) — COMPLETE (E2E integration tests deferred)
- [x] Phase 9: Observability (Weeks 21-22) — COMPLETE (EFK/Fluentd blocked by enterprise network policy; fallback accepted)
- [~] Phase 10: Advanced Features & Scale (Weeks 23-26) — Topic 1 COMPLETE

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
- **2026-04-20**: Step 7 complete — SecurityConfig with SecurityFilterChain bean. CSRF disabled, STATELESS sessions, /api/auth/\*\* public, all else authenticated. Fixed: missing final, non-lambda DSL, single-star wildcard, hardcoded URL. Next: AuthController.
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
- **2026-05-20**: Phase 6 COMPLETE — All 8 steps done. Kafka KRaft (Docker), event DTOs, producer (outbox-based), StockBackRewardConsumer, cancellation consumer, Outbox Pattern (atomic dual-write), DLQ (DefaultErrorHandler + DeadLetterPublishingRecoverer). E2E tested: happy path, multi-ticker rewards, return cancellation, idempotency, Kafka CLI. Re-audit passed (14 files). Next: Phase 7 — Microservices Decomposition.
- **2026-06-01**: Phase 7 started — Microservices Decomposition design approved. 13-step plan created. Step 1 COMPLETE — Eureka Discovery Server module created (port 8761). DiscoveryServerApplication with @SpringBootApplication + @EnableEurekaServer. application.yml configured (client.register-with-eureka=false, client.fetch-registry=false, server.enable-self-preservation=false for strict learning mode). Eureka dashboard accessible at http://localhost:8761. Next: Config Server (Step 2).
- **2026-05-24**: Steps 9-10 done. Exponential backoff (ExponentialBackOffWithMaxRetries replaces FixedBackOff). Debezium CDC: WAL=logical, Kafka Connect + Outbox Event Router SMT, dual-listener Docker networking, @Profile("!cdc") toggle, @Lob→text fix, **TypeId** default type fix. Multiple issues debugged and resolved (OID storage, timestamp timezone, snapshot poisoning). E2E tested through full reward lifecycle (order → deliver → CDC → reward → vest → holding).
- **2026-05-24**: Step 11 done. Saga Orchestrator for Sell-to-Spend: orchestration-based saga with state machine entity, compensating transactions (reverse ledger + re-add shares), @Scheduled timeout detector, lifecycle events via outbox to Kafka. @ConditionalOnProperty toggle between transactional and saga strategies. 7 new files (entity, enum, repo, orchestrator, service, outbox writer, event DTO), 3 files modified. BUILD SUCCESSFUL. Next: test end-to-end.
- **2026-05-31**: Step 13 done. Notification Service — Observer Pattern (distributed via Kafka Pub/Sub) + Strategy Pattern (pluggable channels). New module: notification-service (14 Java files). NotificationPublisher in portfolio (fire-and-forget KafkaTemplate). 3 channel strategies (Log, Email via MailHog, Webhook via WebClient). NotificationDispatcher resolves channel from config via Spring Map<String, Bean> injection. NotificationLog audit entity. REST API: GET /api/notifications. Integrated into TradeServiceImpl, VestingHelperImpl, SagaOrchestrator. E2E tested: TRADE_EXECUTED notification logged + persisted + queryable. Re-audit: all 16 files have Javadoc + Log4j logger.
- **2026-06-03**: Phase 7 Step 4 COMPLETE — User-Service extracted as standalone microservice (port 8081). UserServiceApplication created with @EnableDiscoveryClient. Dual Gradle plugin (java-library → org.springframework.boot + jar enabled for monolith compatibility). Local application.yml with spring.application.name + configserver import. user-service.yml expanded with full datasource/JPA/JWT/actuator/eureka config. Strangler Fig pattern implemented: gateway routes /api/auth/** and /api/users/** to lb://user-service while monolith still runs on 8082. Key learnings: ddl-auto hierarchy (validate in base = prod default, update in service = dev override), config duplication during extraction is intentional, defaultZone should always be explicit. Next: Step 5 — Extract Market-Data-Service (port 8085).
- **2026-06-04**: Phase 7 Step 5 COMPLETE — Market-Data-Service extracted as standalone microservice (port 8085). MarketDataServiceApplication created with @EnableDiscoveryClient + full Javadoc. Root cause of transitive JPA issue identified (commons `api` scope → Gradle leaks JPA to all consumers → DataSourceAutoConfiguration fires and crashes). Fix: commented out commons dependency (market-data uses no commons types). Eureka Windows/Hyper-V hostname fix applied globally (prefer-ip-address: true + ip-address: 127.0.0.1 in equitycart-config/application.yml). Security gap documented: spring-security-core without full starter → no filter chain → all endpoints open; Phase 8 will add OAuth2 Resource Server per service. Javadoc + Log4j added to MarketDataServiceApplication.java. Sections 12 (transitive deps) and 13 (standalone security gap) added to springboot-reference.md. Q117–Q120 added to learning_log.md. Next: Step 6 — Extract Order-Service (port 8083).
- **2026-06-05**: Phase 7 Step 6 COMPLETE — Order-Service extracted as standalone microservice (port 8088). Three debugging sessions: (1) ProductRepository bean not found → @EnableJpaRepositories + @EntityScan with explicit basePackages required when OrderServiceImpl has cross-module repository dependency; @ComponentScan alone doesn't fix JPA repository scanning; (2) port conflict with Debezium Kafka Connect (8083) → changed to 8088; (3) Gateway AND vs OR predicate behaviour → comma-separated Path predicate for multi-path routing. @EnableScheduling required for OutboxPoller. CDC profile must NOT be active until Debezium watches equitycart_order DB. Kafka producer config moved to base application.yml. Q121–Q123 added to learning_log.md. Next: Step 7 — Extract Portfolio-Service (port 8084).
- **2026-06-06**: Phase 7 Step 7 COMPLETE — Portfolio-Service extracted as standalone microservice (port 8084). Required `@ComponentScan` (not `@EnableJpaRepositories`) because service layer beans (LedgerServiceImpl, MarketDataServiceImpl) needed, not just repositories. `excludeFilters = @Filter(SpringBootApplication.class)` required to prevent `OrderServiceApplication` from being loaded as a `@Configuration`, causing `BeanDefinitionOverrideException`. Runtime surprises: Spring Batch config required (product-service has spring-batch on classpath; `@ComponentScan` loads `ProductBatchConfig`; `BatchAutoConfiguration` fires) and alphavantage.\* config required (market-data's `WebClientConfig` uses `@Value` injection). Core lesson: `@EnableJpaRepositories` is surgical, `@ComponentScan` is broad — broader scanner = more auto-configuration side effects. Q124–Q126 added to learning_log.md. Next: Step 8 — Extract Ledger-Service (port 8086).
- **2026-06-06**: Phase 7 Step 8 COMPLETE — Ledger-Service extracted as standalone (port 8086). Simplest extraction: only `@EntityScan` needed beyond `@SpringBootApplication + @EnableDiscoveryClient` — `BaseEntity` in commons is the sole out-of-scope class. No `@EnableJpaRepositories`, no `@ComponentScan` needed. No transitive contamination. No REST controllers (gateway route pre-wired for Phase 10). Q127–Q128 added. Next: Step 9 — Notification-Service (port 8087).
- **2026-06-09**: Phase 7 Step 10 COMPLETE — OpenFeign migration. ProductFeignClient (4 methods) + BrandTickerMappingDTO + ProductDTO in commons. OrderFeignClient in portfolio module (cannot go in commons — circular dependency via order-service types). SellToSpendServiceImpl + SagaOrchestrator migrated from OrderService → OrderFeignClient. StockBackRewardConsumer migrated from ProductRepository → ProductFeignClient. Two startup errors debugged: (1) @ComponentScan("com.equitycart.order") loaded OrderServiceImpl which now requires ProductFeignClient — fix: remove order from @ComponentScan; (2) OutboxEventRepository proxy removed too aggressively — fix: keep order in @EnableJpaRepositories + @EntityScan. All 8 services UP on Eureka. Javadoc added to all uncommitted files. openfeign-guide.md created (12 sections). Q131–Q138 added to learning_log.md. Next: Step 11 — Correlation ID propagation. Same clean extraction pattern as ledger-service: `@EntityScan` for BaseEntity, no cross-module `@ComponentScan`. Key insights: product-service extraction deferred to Phase 10 (consumers must migrate to Feign first); saga strategy and timeout properties correctly omitted — `matchIfMissing=true` makes saga default-active, `@Value` inline `:30` default removes need for YAML entry. Q129–Q130 added. Next: Step 10 — OpenFeign clients.
- **2026-06-10**: Phase 7 Step 11 COMPLETE — Correlation ID propagation. Three components: (1) MdcCorrelationFilter (OncePerRequestFilter in commons, ThreadContext put/remove lifecycle); (2) FeignCorrelationInterceptor (Feign RequestInterceptor, propagates ID to downstream Feign calls); (3) CorrelationIdGatewayFilter (GlobalFilter + Ordered in api-gateway, generates UUID at entry point, mutates immutable WebFlux exchange). Replaced org.slf4j.MDC with org.apache.logging.log4j.ThreadContext (Log4j2 native, no SLF4J bridge). Debugged: default-filters YAML approach fails (SpEL evaluated at startup + wrong direction); OrderedGatewayFilter inheritance wrong (wrapper for route-level filter, not GlobalFilter). mdc-correlation-guide.md created (line-by-line filter explanation, GlobalFilter vs default-filters, Netty vs Tomcat filter types, Correlation ID vs TraceId/SpanId). Javadoc on all 3 files + GatewayApplication updated. Next: Step 12 — Docker Compose.
- **2026-06-12**: Phase 7 Step 12 COMPLETE — Docker Compose full stack. Two-file split: docker-pets.yml (infra) + docker-compose-services.yml (10 Spring Boot services). Start scripts with readiness polling. build-images.sh for all 10 images. Config pattern: `${ENV_VAR:local-default}` in equitycart-config works in both environments. Debugging sessions: (1) Kafka AccessDeniedException → `user: "0"`; (2) INVALID_REPLICATION_FACTOR → single-broker env vars; (3) ConfigClientFailFastException → placeholder in spring.config.import; (4) Eureka registration with Hyper-V hostname → `prefer-ip-address: true` (without explicit ip-address); (5) Git Bash MINGW64 path mangling → `sh -c '...'` wrapper; (6) Config-server DNS failure → `refresh-rate: 3600` (local cache still serves). Key learnings: spring.config.import is ADDITIVE (merges, doesn't override), config-server serves placeholders (client resolves), Docker DNS resolves service names on custom bridge networks, port mapping bridges host↔container worlds. All 10 services UP in Eureka, gateway routing verified. Next: Step 13 — End-to-end testing + re-audit.
- **2026-06-23**: Phase 8 Step 5 COMPLETE — Keycloak Docker setup. Added Keycloak 26.0 (quay.io) to docker-pets.yml sharing existing PostgreSQL container. Created equitycart-realm.json: 4 realm roles (CUSTOMER/SELLER/ADMIN/SERVICE), 3 OAuth2 clients (equitycart-gateway confidential, equitycart-frontend public+PKCE, equitycart-services client-credentials), protocol mappers (roles flattener + userId attribute for backward compat), 3 test users with pre-assigned roles. Updated init-db.sh (+keycloak DB), start-pets.sh (OIDC discovery readiness check). Obstacles: (1) /health/ready on separate management port 9000, fixed by checking OIDC discovery endpoint instead; (2) KEYCLOAK_ADMIN deprecated in 26.x, replaced with KC_BOOTSTRAP_ADMIN_USERNAME; (3) --import-realm only runs on first boot (realm doesn't exist yet). Keycloak admin console accessible at http://localhost:8180. Token acquisition verified via ROPC flow. Conceptual foundation written to security-reference.md Section 13 (OAuth2/OIDC/Keycloak history, flows, RS256, JWKS, competitors). Next: Step 6 — OAuth2 Resource Server migration.
- **2026-06-18**: Phase 8 Steps 1-4 COMPLETE — Per-service JWT validation distributed to all services via commons SecurityAutoConfig. 13 obstacles resolved during E2E testing: ReadOnlyHttpHeaders (ServerHttpResponseDecorator), PATCH unsupported (feign-hc5), Kafka consumer 403 (ServiceTokenProvider with subject=0, role=[SERVICE], 60s expiry), anyRequest() terminal matcher bug, Zscaler TLS interception (keytool CA import in Dockerfile), Docker port mapping (9432:5432), config migration gap (sell-to-spend strategy), .gitignore path resolution. Full business flow verified end-to-end: Register → Login → Browse → Cart → Order → Deliver → Stock-Back Reward → Vest → Trade → Sell-to-Spend. All 10 services running in Docker with auth enforced. Documentation updated: security-reference.md (Sections 11-12), microservice-patterns.md (Sections 12-13), springboot-reference.md (Sections 11-13), learning_log.md (Q155-Q163). Javadoc updated on ServiceTokenProvider, ServiceTokenProviderImpl, FeignAuthorizationInterceptor, SecurityAutoConfig, CorrelationIdGatewayFilter, Dockerfile. Next: Step 5 — Keycloak Docker setup.
- **2026-08-07**: Phase 9 COMPLETE — observability rollout validated. Structured logging (Log4j2 JSON + correlation IDs), Prometheus scraping, Grafana dashboards, Zipkin tracing, custom business metrics, and alert rules are in place. Docker split-compose networking was stabilized via shared external network creation in startup scripts. Centralized EFK/Fluentd was blocked by corporate image policy (Zscaler), and fallback (`core-loglens` + JSON logs) was accepted and documented.

- **2026-10-15**: Phase 10 Topic 1 Implementation Summary — CQRS Portfolio Read Model (SQL write + Mongo read projection via Kafka/Debezium):
  - [x] Mongo read model layer (portfolio_read_models collection)
  - [x] CQRS read controller (GetPortfolioReadModel endpoint) + feature-flag-based routing
  - [x] Portfolio outbox entity/repo/writer/poller scaffolding
  - [x] Debezium connector for portfolio outbox (PostgreSQL WAL → Kafka)
  - [x] Kafka consumer projecting to Mongo (upsert-by-userId for idempotency)
  - [x] Manual E2E validation (buy/sell/reward grant/vest/sell-to-spend/refund-restored flows)
  - [x] JavaDoc/logging/comments on all Topic 1 files
  - [x] Topic 1 compilation verification PASSED
  - [x] Learning & Documentation: kafka-learning.md (idempotency, partition keys, reconciliation, lessons learned), microservice-patterns.md (saga section expanded with clawback examples), learning_log.md (Q&A on compensation vs retry, saga idempotency, timeout detection, partition key ordering), java-reference.md (saga pattern section enhanced with ClawbackSaga comparison).

- **2026-10-15**: Phase 10 Topic 8 (Return Clawback Saga) Implementation Summary — Compensation-based saga for VESTED reward clawback on refund approval:
  - [x] ClawbackStatus enum (INITIATED, LEDGER_ADJUSTED, HOLDING_REDUCED, COMPLETED, COMPENSATING, FAILED)
  - [x] ClawbackSaga entity + ClawbackSagaRepository (findByRewardId, findStuck, findExpired)
  - [x] ClawbackSagaOrchestrator (3-step forward + reverse-order compensation on timeout)
  - [x] ClawbackOutboxWriter (publishes saga lifecycle events to clawback-saga-events Kafka topic)
  - [x] ClawbackSagaTimeoutDetector (@Scheduled, 30s interval, retry vs compensate decision via attemptCount)
  - [x] Three-layer idempotency: status gates + natural idempotency (ledger idempotency keys) + unique DB constraints
  - [x] Partition key strategy reinforced: userId as Kafka key ensures per-user event ordering (critical for compensation safety)
  - [x] Learning integrated into existing documentation (NOT appended as new sections):
    - kafka-learning.md: Idempotency section enhanced with saga status-gate pattern, partition key section explains clawback compensation ordering, reconciliation section clarifies timeout detection as separate concern, lessons learned list expanded with compensation/idempotency/partition-key rules
    - microservice-patterns.md: Saga section (2.5-2.9) massively expanded with ClawbackSaga as second implementation example, idempotency table includes clawback details, timeout detection includes full retry/compensate logic, compensating transaction design rules include detailed clawback scenarios (why forward ops not deletes), implementation section now compares SellToSpend vs Clawback sagas
    - java-reference.md: Saga pattern section (2.10) updated with ClawbackSaga, pattern summary table includes ClawbackSagaOrchestrator
    - learning_log.md: 7 new Q&A entries (Q196–Q201) on saga compensation vs retry, idempotency layers, timeout detection logic, partition key propagation, clawback trigger scenario
  - [x] Topic 8 implementation verified as correct during review sessions.

- **2026-08-14**: Phase 10 Topic 2 (Stock Gifting Saga) implementation verification + polish:
  - [x] Gifting saga implementation verified in code review:
    - `GiftSagaStatus`, `GiftSaga`, `GiftSagaRepository`
    - `GiftSagaOrchestrator` (debit giver -> credit receiver -> ledger audit, plus compensation + timeout detector)
    - `GiftSagaOutboxWriter` (lifecycle events to outbox/topic)
    - `GiftRequest` / `GiftResponse`
    - `PortfolioFacade` + `PortfolioFacadeImpl` + `PortfolioController` gift endpoint integration
  - [x] Targeted compile validation PASSED:
    - `cd equitycart && .\\gradlew.bat :portfolio:compileJava -x test`
  - [x] Correctness fix applied during verification:
    - compensation path now uses explicit `completedSteps` mapping (instead of relying on overwritten `COMPENSATING` status)
  - [x] JavaDoc/logging/comments enhanced across all uncommitted gifting-related files
  - [x] Learning updates completed:
    - `phase-10-learning-deep-dive.md` Topic 2 kickoff section
    - `learning_log.md` Q202-Q205 (compensation boundary, separation from buy/sell, idempotency key rationale, timeout recovery)
  - [ ] Pending before Topic 2 closure:
    - manual E2E validation (happy path, duplicate idempotency request, failure compensation, timeout compensation)
