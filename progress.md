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

### Implementation Pending (RESUME HERE)
- [ ] Step 8: Create AuthController (REST endpoints)
- [ ] Step 7: Create SecurityConfig (public vs protected endpoints)
- [ ] Step 8: Create AuthController (REST endpoints)
- [ ] Step 9: Test with Postman/curl

## Phase Checklist
- [x] Phase 0: Foundation & Setup (Week 1)
- [ ] Phase 1: User Service & Security (Weeks 2-3) ← CURRENT
- [ ] Phase 2: Product Catalog & Batch Import (Weeks 4-5)
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
