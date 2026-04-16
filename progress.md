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

### Implementation Pending (RESUME HERE)
- [ ] Step 4: Create AuthService (register, login, refresh)
- [ ] Step 4: Create AuthService (register, login, refresh)
- [ ] Step 5: Create JwtService (generate, validate, extract claims)
- [ ] Step 6: Create JwtAuthFilter (Spring Security filter)
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
