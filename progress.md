# Progress Tracking

## Status: Phase 1 - User Service & Security (IN PROGRESS)

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

## Phase Checklist
- [x] Phase 0: Foundation & Setup (Week 1)
- [~] Phase 1: User Service & Security (Weeks 2-3) — FUNCTIONAL COMPLETE (tests deferred)
- [ ] Phase 2: Product Catalog & Batch Import (Weeks 4-5) ← CURRENT
- [ ] Phase 3: Order Service & Cart (Weeks 6-7)
- [ ] Phase 4: Market Data - Reactive (Weeks 8-9)
- [ ] Phase 5: Portfolio & Stock-Back Engine (Weeks 10-12)
- [ ] Phase 6: Event-Driven Architecture (Weeks 13-15)
- [ ] Phase 7: Microservices Decomposition (Weeks 16-18)
- [ ] Phase 8: Security Hardening (Weeks 19-20)
- [ ] Phase 9: Observability (Weeks 21-22)
- [ ] Phase 10: Advanced Features & Scale (Weeks 23-26)

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
