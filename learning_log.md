# EquityCart — Learning Log

> This file is a comprehensive revision guide. Updated at the end of every phase.
> Covers: Roadblocks faced, Concepts learned, Interview questions discussed.

---

## Phase 0: Foundation & Project Setup ✅

### Date: 2026-04-07

---

### Roadblocks & Issues Faced

**1. settings.gradle module name mismatch**

- Problem: `include 'ledger'` but `project(":ledger-service").projectDir = file("ledger")` — Gradle couldn't resolve the mismatch because `include` name and `project()` reference must match.
- Fix: Changed `include` names to match the `-service` suffix, and kept `projectDir` mappings only for folders that differ from module names.
- Lesson: The `include` name IS the module identity everywhere in Gradle. `projectDir` only remaps the physical folder.

**2. `classpath()` used outside `buildscript {}` block**

- Problem: Used `classpath("org.springframework.boot:spring-boot-starter-web")` inside `dependencies {}` of modules. This caused build errors.
- Fix: `classpath` is ONLY valid inside `buildscript { dependencies { } }`. For module dependencies, use `implementation`, `compileOnly`, `runtimeOnly`, `api`, `testImplementation`.
- Lesson: `classpath` adds plugins/build tools. `implementation` adds libraries your code uses. They serve completely different purposes.

**3. `plugins {}` block — variable interpolation not supported**

- Problem: Tried `id 'org.springframework.boot' version "${springBootVersion}"` inside `plugins {}`.
- Fix: The `plugins {}` block is parsed BEFORE any script runs, so `gradle.properties` variables aren't available. Either hardcode the version or use `pluginManagement` in `settings.gradle`.
- Lesson: `plugins {}` is a "pre-compile" block. Use it for version declarations, not dynamic values.

**4. Package name vs directory path confusion**

- Problem: Wrote `package main.java.com.equitycart;` — including the source root path in the package.
- Fix: Gradle treats `src/main/java/` as the source root. Everything AFTER that is the package. So the correct package is `package com.equitycart;`.
- Root cause: VS Code didn't recognize the Gradle source root, so it calculated the package from the filesystem path. Running `Java: Clean Java Language Server Workspace` fixed the IDE.
- Lesson: In Java, the package name corresponds to the folder structure INSIDE the source root, not the full filesystem path.

**5. `spring-boot-starter-web` dependency missing group ID**

- Problem: Wrote `implementation "spring-boot-starter-web"` instead of `implementation 'org.springframework.boot:spring-boot-starter-web'`.
- Fix: Maven dependencies always require the format `group:artifact` (and optionally `:version`).
- Lesson: Always use the full `group:artifact` format. The version can be omitted ONLY if a BOM manages it.

**6. `bootJar` and `jar` tasks on root project**

- Problem: Added `bootJar {}` and `jar {}` blocks to the root `build.gradle`, but root has `id 'org.springframework.boot' apply false`.
- Fix: These tasks only exist when the Spring Boot plugin is applied. Moved them to `app/build.gradle` which is the only module with the plugin applied.
- Lesson: Tasks are created by plugins. If a plugin is `apply false` on the root, its tasks don't exist there.

**7. application.yml YAML formatting**

- Problem: Wrote `username:postgres` (no space after colon).
- Fix: YAML requires a space after the colon: `username: postgres`.
- Lesson: YAML is whitespace-sensitive. `key: value` (with space) is correct. `key:value` is invalid or parsed as a string.

---

### Core Concepts Learned

**1. Gradle Multi-Module Architecture**

- Root `build.gradle` declares plugins with `apply false` (version catalog)
- `allprojects {}` → config for root + all children (group, version, repositories)
- `subprojects {}` → config for children only (plugins, java version, shared dependencies)
- Each module has its own `build.gradle` with only module-specific config
- `java-library` plugin adds `api` scope; `java` plugin does not

**2. Groovy DSL Basics for Gradle**

- Everything is a method call with a closure: `plugins { ... }` = `plugins({ ... })`
- Single quotes = plain string; double quotes = GString (supports `${var}`)
- Method calls can omit parentheses: `apply plugin: 'java'` = `apply(plugin: 'java')`
- `apply plugin:` (legacy) can go anywhere; `plugins {}` (modern) only at top of file

**3. `apply false` Pattern**

- Declares a plugin's version globally without activating it on the root project
- Submodules can then `apply plugin: 'name'` to activate it, inheriting the version
- Essential for multi-module: root declares versions, modules choose what to activate

**4. Spring Boot Dependency Management (BOM)**

- The Spring Boot BOM (`spring-boot-dependencies`) defines compatible versions for ~300 libraries
- When imported via `dependencyManagement { imports { mavenBom "..." } }`, you can omit version numbers
- This ensures ALL Spring, Jackson, Hibernate, and Lombok versions are compatible with each other
- Without BOM: you manually manage every version and risk incompatibilities

**5. Dependency Scopes**

- `implementation` → compile + runtime, NOT exposed to consumers
- `api` → compile + runtime, EXPOSED to consumers (java-library only)
- `compileOnly` → compile time only, NOT in final JAR (Lombok)
- `runtimeOnly` → NOT at compile, only at runtime (JDBC drivers)
- `annotationProcessor` → code generators at compile time (Lombok)
- `testImplementation` → only for test code

**6. Spring Boot Auto-Configuration**

- `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`
- Auto-config scans `META-INF/spring/...AutoConfiguration.imports` files in every JAR on classpath
- Each auto-config checks `@ConditionalOnClass`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`
- If conditions pass → beans are auto-registered (DataSource, EntityManagerFactory, etc.)
- "Opinionated defaults" — Spring Boot configures everything unless you override

**7. @ComponentScan Package Hierarchy**

- Scans the annotated class's package AND all sub-packages
- Main class at `com.equitycart` → scans `com.equitycart.*`, `com.equitycart.user.*`, `com.equitycart.order.*`
- If main class were at `com.equitycart.app`, it would NOT scan `com.equitycart.user` (not a sub-package)

**8. Gradle Wrapper**

- 4 files: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`
- `gradlew` reads `gradle-wrapper.properties` → downloads exact Gradle version → caches at `~/.gradle/wrapper/dists/`
- Solves "works on my machine" — everyone uses the same Gradle version
- No global Gradle installation needed

**9. Jakarta EE vs javax**

- Oracle donated Java EE to Eclipse Foundation → renamed from `javax.*` to `jakarta.*`
- Spring Boot 3.x requires Jakarta EE 9+ (all imports are `jakarta.persistence`, `jakarta.servlet`, etc.)
- Functionally identical APIs — it's a package rename, not a rewrite
- Old tutorials/StackOverflow answers using `javax.*` won't work with Spring Boot 3.x

**10. `com` vs `io` Package Convention**

- Reverse domain name convention: `com.equitycart` implies `equitycart.com` ownership
- `io.equitycart` → modern/startup convention; `com.equitycart` → enterprise convention
- No functional difference — purely naming convention
- Enterprise standard: use `com.*`

---

### Interview Questions Discussed

**Q1: "What is the Gradle Wrapper and why is it important?"**
A: The Gradle Wrapper ships 4 files with the project that download and cache an exact Gradle version. It ensures every developer and CI system uses the same build tool version, solving "works on my machine" problems. No global Gradle installation is needed.

**Q2: "What changed between javax and jakarta? Why does Spring Boot 3 require Java 17+?"**
A: Oracle donated Java EE to Eclipse Foundation, which legally couldn't use the `javax` trademark. All packages were renamed from `javax.*` to `jakarta.*`. Spring Boot 3 is built on Jakarta EE 9+, which targets Java 17+. The APIs are functionally identical — it's a namespace change.

**Q3: "What is @SpringBootApplication and what does it do internally?"**
A: It's a meta-annotation combining three: (1) `@SpringBootConfiguration` — marks this as the main config class, (2) `@EnableAutoConfiguration` — scans classpath JARs for auto-configuration classes that conditionally register beans, (3) `@ComponentScan` — scans the current package and sub-packages for @Component, @Service, @Repository, @Controller beans.

**Q4: "How does Spring Boot auto-configuration work?"**
A: Each starter JAR contains a file listing its auto-configuration classes. At startup, Spring loads all these classes but each has conditional annotations (@ConditionalOnClass, @ConditionalOnProperty, @ConditionalOnMissingBean). Only configurations whose conditions are met actually register beans. This is why adding `spring-boot-starter-data-jpa` to the classpath automatically creates a DataSource, EntityManagerFactory, etc.

**Q5: "How would you disable a specific auto-configuration?"**
A: Either via annotation: `@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})` or via application.yml: `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`

**Q6: "What's the difference between `implementation` and `api` in Gradle?"**
A: `implementation` makes the dependency available at compile and runtime but hides it from consumers. `api` exposes it to consumers. Use `api` in library modules when consumers need direct access to the dependency's types. `api` is only available with the `java-library` plugin.

**Q7: "What is a BOM (Bill of Materials) in dependency management?"**
A: A BOM is a special POM that defines compatible versions for a set of libraries. Spring Boot's BOM manages ~300 library versions. When imported, you can declare dependencies without version numbers — the BOM provides them. This ensures all libraries are tested to work together.

**Q8: "Why use `apply false` in a Gradle multi-module root?"**
A: `apply false` declares a plugin's version without activating it on the root project. This is important because the root project usually has no source code. Submodules can then apply the plugin and automatically inherit the declared version, ensuring consistent versions across all modules.

**Q9: "What's the difference between `spring-boot-starter-web` and `spring-boot-starter-webflux`?"**
A: `starter-web` uses Spring MVC with blocking I/O (one-thread-per-request) on embedded Tomcat. `starter-webflux` uses reactive/non-blocking I/O with Project Reactor (Mono/Flux) on embedded Netty. Use web for traditional CRUD APIs, webflux for high-concurrency scenarios like real-time data streaming.

**Q10: "Why does only the `app` module have the Spring Boot plugin applied?"**
A: The Spring Boot plugin creates a `bootJar` task that packages a fat/uber JAR with embedded Tomcat and all dependencies. During the monolith phase, only `app` produces the runnable JAR — service modules are just libraries consumed by it. When decomposing to microservices, each service gets its own Spring Boot plugin and becomes independently runnable.

---

## Phase 1: User Service & Security Foundation (IN PROGRESS)

### Date: 2025-04-09 → 2026-04-13

---

### Roadblocks & Issues Faced

**1. JJWT Version and Dependency Scope**

- Problem: `jjwt-api` listed as `runtimeOnly` with outdated version 0.11.2
- Fix: Changed to `implementation` (code imports JJWT API classes directly like `Jwts.builder()`) and updated to `0.12.6`
- Lesson: If you write `import io.jsonwebtoken.Jwts` in your code, it MUST be `implementation`. `runtimeOnly` = never imported in source code, only loaded at runtime (like JDBC drivers).

**2. OneDrive File Locking During Gradle clean**

- Problem: `./gradlew clean build` fails with "Unable to delete directory" because OneDrive sync locks .class files
- Fix: (A) Just run `./gradlew build` without clean — Gradle is incremental. (B) Stop daemons + close IDE first. (C) Move project out of OneDrive.
- Lesson: Cloud-synced folders are bad for build artifacts. Build dirs generate hundreds of files per build.

**3. Commons Module Needed JPA Dependency for BaseEntity (2026-04-13)**

- Problem: `BaseEntity` uses `@MappedSuperclass`, `@Id`, `@GeneratedValue` — all Jakarta Persistence annotations. But commons had no JPA dependency.
- Fix: Added `api 'org.springframework.boot:spring-boot-starter-data-jpa'` to `commons/build.gradle`.
- Why `api` not `implementation`? Because commons is a `java-library` — any module depending on commons (e.g., user-service) needs transitive access to JPA annotations that BaseEntity exposes (`@Id`, `@MappedSuperclass`). `implementation` would hide them → compile error in consuming modules.
- Lesson: Library modules that export types from a dependency in their public API MUST use `api`, not `implementation`.

**4. Missing `public` modifier on Entity Class (2026-04-13)**

- Problem: Wrote `class User extends BaseEntity` (package-private). Other packages (service, repository) couldn't see User.
- Fix: JPA entities must be `public class`. Spring Data repositories, Hibernate reflection, and cross-package access all require public visibility.
- Lesson: Java's default access is package-private, not public. Always explicitly write `public` on entity classes.

**5. `long` vs `Long` for JPA ID Field (2026-04-13)**

- Problem: Used `private long id` (primitive). Before persisting, `id = 0` — ambiguous (is it unsaved or is 0 a real DB ID?).
- Fix: Use `Long` (wrapper). Before persisting, `id = null` — clearly means "not yet saved."
- Lesson: Hibernate uses `null` to decide INSERT (new) vs UPDATE (existing). Primitives can't be null → confuses Hibernate. Always use wrapper types (`Long`, `Boolean`, `Integer`) for nullable JPA fields.

**6. Phone Number and Document Number as String, Not long (2026-04-13)**

- Problem: Used `private long phoneNumber` and `private long documentNumber`.
- Fix: Both must be `String`. Reasons: leading zeros (`09876543210`), country codes (`+91-...`), letters in documents (Passport: `AB1234567`, PAN: `ABCDE1234F`), formatting characters (dashes, spaces).
- Lesson: If you'd never do arithmetic on a value, it's not a number — it's a `String`. Phone numbers, document IDs, zip codes, credit card numbers = always String.

**7. `@Column(precision, scale)` Wrong Values for Money (2026-04-13)**

- Problem: Used `precision = 4, scale = 2` on WalletAccount → `NUMERIC(4,2)` → max value 99.99. Any balance ≥ 100 would overflow.
- Fix: `precision = 19, scale = 4` → `NUMERIC(19,4)` → max ~999 trillion, 4 decimal places for fractional stock prices.
- Lesson: `precision` = total digits (both sides of decimal), `scale` = digits after decimal. For money: `(19, 4)` is industry standard. `(4, 2)` is almost never correct.

**8. passwordEncoder.matches() Argument Order Swapped (2026-04-20)**

- Problem: Wrote `passwordEncoder.matches(userEntity.getPassword(), request.password())` — hash first, plain text second.
- Fix: Correct signature is `matches(CharSequence rawPassword, String encodedPassword)` — raw password FIRST, hash SECOND.
- Why it matters: BCrypt extracts the salt from the second argument (the hash). If you pass plain text as the second arg, it can't find a salt → always returns false → every login fails silently.
- Lesson: Always check method signatures for argument order. This bug is hard to catch because the code compiles fine and no exception is thrown — it just silently returns false.

**9. @Autowired Redundant With @RequiredArgsConstructor (2026-04-20)**

- Problem: Used both `@Autowired` on fields AND `@RequiredArgsConstructor`. Spring injects via the constructor (generated by Lombok), making field-level `@Autowired` completely ignored.
- Fix: Remove all `@Autowired` — `@RequiredArgsConstructor` + `private final` is sufficient. Spring auto-injects when there's one constructor.
- Lesson: Don't mix injection styles. Pick one approach: either `@RequiredArgsConstructor` + `final` (recommended) OR `@Autowired` on fields — not both.

**10. Hardcoded Token Expiry Instead of Config Value (2026-04-20)**

- Problem: Used `Instant.now().plusSeconds(900000)` — hardcoded value and wrong unit (900000 seconds ≠ 15 minutes).
- Fix: Used `@Value("${jwt.access-token-expiry}")` to inject the config value (900000 ms) and `Instant.now().plusMillis(accessTokenExpiry)`.
- Lesson: Never hardcode values that should be configurable. Use `@Value` + application.yml. Also watch units — `plusSeconds` vs `plusMillis` is a 1000x difference.

**11. extractRoles() Wrapping List in singletonList+toString (2026-04-20)**

- Problem: `Collections.singletonList(claims.get("roles", List.class).toString())` — takes a List like `["CUSTOMER"]`, calls toString → `"[CUSTOMER]"`, wraps in another List → `["[CUSTOMER]"]`. Completely corrupts the data.
- Fix: `claims.get("roles", List.class)` — returns the List directly.
- Lesson: `get("key", List.class)` already returns the correct type. Don't wrap or transform it unnecessarily.

**12. validateToken() Always Returning True (2026-04-20)**

- Problem: `validateToken()` had no actual validation — just returned `true` as a placeholder.
- Fix: Wrapped `extractAllClaims(token)` in try-catch. If parsing succeeds → true. If any exception (expired, tampered, malformed) → false.
- Lesson: JJWT's `parseSignedClaims()` IS the validation — it checks signature, expiry, and structure. A single try-catch around it covers all failure modes.

**13. Redundant isTokenExpired() Method (2026-04-20)**

- Problem: Separate `isTokenExpired()` method that manually compared expiration date to now.
- Fix: Removed entirely. JJWT already throws `ExpiredJwtException` during `parseSignedClaims()` if the token is expired.
- Lesson: Understand what the library already does for you. Adding manual checks on top of automatic library checks is redundant and can introduce inconsistencies.

**14. JWT Config in Wrong YAML File (2026-04-20)**

- Problem: JWT properties (`jwt.secret`, `jwt.access-token-expiry`, `jwt.refresh-token-expiry`) defined in `user/src/main/resources/application.yml` instead of `app/src/main/resources/application.yml`.
- Fix: Moved to `app/src/main/resources/application.yml` and deleted the user module's YAML.
- Lesson: In a monolith multi-module Gradle project, only the `app` module's application.yml is loaded by Spring Boot at startup — it's the runnable module. YAMLs in library modules are ignored unless explicitly imported.

**15. Null Header Causes NullPointerException in Filter (2026-04-20)**

- Problem: `request.getHeader("Authorization")` returns `null` when no header present. Calling `.startsWith("Bearer ")` on null → NPE → every unauthenticated request crashes.
- Fix: Added `bearerToken != null &&` before `.startsWith()`. If no header, filter passes request through untouched.
- Lesson: `getHeader()` returns `null` (not empty string) when the header is absent. Always null-check before calling methods on the result.

**16. @Service vs @Component for Filters (2026-04-20)**

- Problem: Used `@Service` on JwtAuthFilter. `@Service` semantically means "business logic bean."
- Fix: Changed to `@Component` — the generic annotation for infrastructure beans. Functionally identical, but semantically correct.
- Lesson: `@Component` is the base annotation. `@Service`, `@Repository`, `@Controller` are specializations with semantic meaning. Use the one that matches the bean's purpose.

**17. UsernamePasswordAuthenticationToken — 2-arg vs 3-arg Constructor (2026-04-20)**

- Problem: Used 2-arg constructor `(principal, credentials)` — creates an UNAUTHENTICATED token (`isAuthenticated() = false`). Spring Security would reject the request.
- Fix: Used 3-arg constructor `(principal, null, authorities)` — creates an AUTHENTICATED token (`isAuthenticated() = true`).
- Why two constructors? Spring Security separates "authentication requests" (2-arg: user is trying to log in) from "authenticated tokens" (3-arg: user is already verified). This prevents accidentally treating unauthenticated requests as authenticated.

**18. Non-lambda DSL Appears Deprecated (2026-04-20)**

- Problem: Used `.authorizeHttpRequests().requestMatchers(...)` (no-arg version) — IDE showed deprecation warning.
- Fix: Switched to lambda DSL: `.authorizeHttpRequests(auth -> auth.requestMatchers(...))`.
- Lesson: Since Spring Security 5.2, the lambda DSL is the standard. Every `HttpSecurity` method follows the same pattern: `method(customizer_lambda)`. The no-arg versions that return chainable registries are deprecated.

**19. Missing `final` on Injected Field with @RequiredArgsConstructor (2026-04-20)**

- Problem: `private JwtAuthFilter jwtAuthFilter` without `final`. Lombok's `@RequiredArgsConstructor` only generates constructor parameters for `final` fields. Without `final`, the field stays `null` → NPE at runtime.
- Fix: Added `final`.
- Lesson: `@RequiredArgsConstructor` = constructor for `final` fields only. This is the standard pattern for Spring constructor injection with Lombok.

**20. Single Wildcard `*` vs Double Wildcard `**` in URL Patterns (2026-04-20)**

- Problem: `/api/auth/*` only matches one path segment (`/api/auth/login`) but not nested paths (`/api/auth/token/refresh`).
- Fix: Changed to `/api/auth/**` which matches any number of path segments.
- Lesson: In Spring's `AntPathMatcher` (named after Apache Ant build tool's path matching): `*` = one segment, `**` = any number of segments. Always use `/**` when you want to match an entire URL subtree.

---

### Core Concepts Learned

**1. Session-Based vs JWT Authentication**

Session:
- Server stores session in memory, sends session ID cookie to client
- Every request: server looks up session ID in memory to identify user
- Problem for microservices: session lives on ONE server. Multiple instances need sticky sessions or shared store (Redis)

JWT:
- Server creates signed token containing user info, sends to client
- Client sends token in Authorization header on every request
- Server verifies signature only — NO database/memory lookup needed
- Perfect for microservices: any service instance can verify independently using shared secret key

**2. JWT Structure (header.payload.signature)**

- Header: `{"alg":"HS256","typ":"JWT"}` — algorithm and type
- Payload: `{"sub":"1","role":"CUSTOMER","exp":1712586400}` — claims (user data + expiry)
- Signature: HMAC-SHA256(header + payload, SECRET_KEY) — tamper-proof guarantee
- CRITICAL: Payload is base64 ENCODED, NOT encrypted. Anyone can decode and read it. Never put passwords in JWT.
- Signature only proves data wasn't tampered with — it does NOT hide the data.

**3. Access Token + Refresh Token Pattern**

- Access Token: short-lived (15-30 min), sent with every API request
- Refresh Token: long-lived (7-30 days), sent ONLY to /refresh endpoint to get new access token
- Why two tokens? If access token is stolen, attacker has only 15 minutes. Refresh token is sent less frequently and can be revoked (deleted from DB).
- Flow: Login -> {accessToken, refreshToken} -> use accessToken -> expires -> send refreshToken -> get new accessToken -> refreshToken expires -> must re-login

**4. Spring Security Filter Chain**

- Every HTTP request passes through a chain of filters BEFORE reaching your @RestController
- Order: JwtAuthFilter (custom) -> UsernamePasswordAuth -> AuthorizationFilter -> ExceptionTranslation
- JwtAuthFilter: extracts JWT from Authorization header, validates signature, sets user in SecurityContext
- AuthorizationFilter: checks if authenticated user has required role/permission
- If auth fails: 401 Unauthorized. If authz fails: 403 Forbidden.

**5. Six Query Methods in Spring/JPA**

(a) Derived Queries — method naming convention, Spring generates SQL:
    findByEmail(String email) -> SELECT * FROM users WHERE email = ?
    Use for: 60-70% of queries (simple conditions)

(b) JPQL — @Query with entity/field names (not table/column):
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.name = :roleName")
    Use for: joins, aggregations. Database-agnostic.

(c) Native SQL — @Query with nativeQuery=true, real SQL:
    @Query(value = "SELECT * FROM users WHERE ...", nativeQuery = true)
    Use for: DB-specific features (JSONB, window functions), performance-critical queries

(d) Criteria API — programmatic query building:
    CriteriaBuilder + Predicate + Root. Very verbose.
    Use for: dynamic queries (but prefer Specifications)

(e) Specifications — clean, composable Criteria API:
    hasEmail("x").and(isEnabled()).and(hasRole("CUSTOMER"))
    Use for: dynamic search/filter (admin dashboards, search pages)

(f) QueryDSL — third-party, generates Q-classes, compile-time safe:
    Use for: large enterprise projects. Not using in EquityCart.

**6. Entity Design Principles Applied**

- User domain split into 7 entities following SRP: User, UserProfile, Role, UserRole, KycDetail, RefreshToken, WalletAccount
- Role as separate table (not enum) because: users can have MULTIPLE roles (SELLER + CUSTOMER), new roles added without schema changes
- KycDetail separate because not all users trade stocks — only loaded when needed
- RefreshToken stored in DB to enable revocation (logout = delete from DB)

**7. @MappedSuperclass vs @Inheritance vs @Embeddable (2026-04-13)**

- Three ways to share fields across JPA entities:
- `@MappedSuperclass`: NOT a table itself. Children inherit column mappings. No JOINs, no discriminator. Best for: shared audit fields (id, createdAt, updatedAt) across unrelated entities.
- `@Inheritance` (SINGLE_TABLE / JOINED / TABLE_PER_CLASS): Creates real table(s) for parent. For **is-a** hierarchies (Payment → CardPayment, UpiPayment). Wrong for unrelated entities.
- `@Embeddable` / `@Embedded`: Value object embedded as component. Can't contain `@Id`. Must repeat `@Embedded` in every entity. Wrong for ID + audit fields.
- EquityCart choice: `@MappedSuperclass` on BaseEntity — simplest, no overhead, every child gets its own columns.

**8. GenerationType: IDENTITY vs SEQUENCE vs TABLE vs AUTO (2026-04-13)**

- `IDENTITY`: DB auto-increment (PostgreSQL SERIAL). INSERT first → DB generates ID → Hibernate reads back. Cannot batch inserts.
- `SEQUENCE`: Hibernate calls DB sequence BEFORE insert. Gets ID first → can batch multiple INSERTs. Best for bulk operations.
- `TABLE`: Simulates sequence with a table + row locking. Terrible performance. Never use.
- `AUTO`: Hibernate picks strategy based on dialect. Unpredictable across versions.
- EquityCart choice: `IDENTITY` for now (simple, no bulk inserts). Will use `SEQUENCE` with `allocationSize=50` for Product entity in Phase 2 (Spring Batch CSV import).

**9. JPA Lifecycle Callbacks: @PrePersist and @PreUpdate (2026-04-13)**

- `@PrePersist`: JPA calls this method automatically just BEFORE INSERT (entity first saved)
- `@PreUpdate`: JPA calls this method automatically just BEFORE UPDATE (entity modified)
- Like database triggers, but in Java — auto-set `createdAt`/`updatedAt` without manual code
- `@Column(updatable = false)` on `createdAt` ensures it's never overwritten on UPDATE

**10. @Builder.Default — When Needed vs Not (2026-04-13)**

- Lombok `@Builder` sets ALL fields to Java zero-values (false, null, 0) — IGNORING field initializers
- `@Builder.Default` tells Lombok: "use the field initializer as the builder default"
- Needed when desired default ≠ zero-value: `@Builder.Default private boolean enabled = true;`
- NOT needed when desired default = zero-value: `private boolean revoked;` (false is correct)
- NOT needed when desired default = null: `private String name;` (null is correct)
- Without `@Builder.Default`: `User.builder().build()` → `enabled = false` (bug!)
- With `@Builder.Default`: `User.builder().build()` → `enabled = true` (correct)

**11. Gradle Dependency Scopes — Complete Picture (2026-04-13)**

| Scope | Compile? | Runtime? | Visible to consumers? | Example in EquityCart |
|---|---|---|---|---|
| `implementation` | Yes | Yes | No | `starter-web` in user-service |
| `api` | Yes | Yes | Yes | `starter-data-jpa` in commons |
| `compileOnly` | Yes | No | No | Lombok (generates code at compile, not needed at runtime) |
| `runtimeOnly` | No | Yes | No | PostgreSQL driver, jjwt-impl, jjwt-jackson |
| `annotationProcessor` | Compile only | No | No | Lombok processor (actually processes @Getter etc.) |
| `testImplementation` | Yes (tests) | Yes (tests) | No | spring-boot-starter-test |

- `compileOnly` makes Lombok available to import; `annotationProcessor` makes it actually run and generate code. Both are needed.
- `runtimeOnly` for JDBC driver: your code never writes `import org.postgresql.Driver` — Spring auto-config loads it via `Class.forName()`.

**12. JPA Relationship Mappings (2026-04-13)**

Four relationship types:
- `@OneToOne` — 1:1 (User ↔ UserProfile, User ↔ KycDetail, User ↔ WalletAccount)
- `@ManyToOne` — N:1 (RefreshToken → User, UserRole → User, UserRole → Role)
- `@OneToMany` — 1:N (inverse of ManyToOne, use `mappedBy`)
- `@ManyToMany` — N:N (avoided — use explicit join entity like UserRole instead)

Unidirectional vs Bidirectional:
- Unidirectional: only ONE entity has the relationship field. UserProfile → User but User has no profile field.
- Bidirectional: BOTH entities know about each other. Requires `mappedBy` on the inverse side.
- `mappedBy = "user"` means: "I don't own the FK. Go look at the `user` field in the other entity."
- Owning side = has `@JoinColumn` = FK in its table. Inverse side = has `mappedBy` = no FK column.
- Default to unidirectional. Add bidirectional only when you frequently navigate from both sides.
- Bidirectional traps: infinite loops (EAGER loading both sides), StackOverflowError in toString/JSON serialization.

**13. FetchType.LAZY vs EAGER (2026-04-13)**

- LAZY: related entity NOT loaded until explicitly accessed (`.getUser()`)
- EAGER: related entity loaded immediately with the parent query (extra JOIN or SELECT)
- Defaults: `@ManyToOne` → EAGER, `@OneToOne` → EAGER, `@OneToMany` → LAZY, `@ManyToMany` → LAZY
- Best practice: ALWAYS set LAZY explicitly on `@ManyToOne` and `@OneToOne` (override the bad defaults)
- Can always force eager loading when needed via JOIN FETCH or EntityGraph

**14. Explicit Join Entity (UserRole) vs @ManyToMany (2026-04-13)**

- `@ManyToMany` creates a hidden join table you can't control or add columns to
- Explicit join entity (UserRole) gives: (1) full control over table/columns, (2) ability to add audit fields (assignedAt, assignedBy), (3) queryable/pageable, (4) clearer SQL
- Used `@UniqueConstraint(columnNames = {"user_id", "role_id"})` for composite uniqueness — prevents duplicate role assignments

**15. BigDecimal for Financial Amounts (2026-04-13)**

- `double`/`float` use binary floating-point → can't represent 0.1 exactly → rounding errors in money
- `0.1 + 0.2 = 0.30000000000000004` with double
- `BigDecimal` stores as scaled integer → exact decimal arithmetic
- Always use `compareTo()` not `equals()`: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` returns **false**
- `@Column(precision = 19, scale = 4)` → NUMERIC(19,4) → handles any currency amount + fractional stock prices

**16. Reserved Word Trap: "user" in PostgreSQL (2026-04-13)**

- `user` is a reserved keyword in PostgreSQL. `CREATE TABLE user (...)` requires quoting → error-prone
- Convention: use plural table names (`users`, `roles`, `user_roles`) to avoid reserved word conflicts
- `@Table(name = "users")` explicitly sets the table name, overriding Hibernate's default (class name)

**17. nullable = false — Database Enforces Business Rules (2026-04-13)**

- `@Column(nullable = false)` adds a NOT NULL constraint at the DB level
- `@JoinColumn(nullable = false)` ensures FK columns can't be null (every profile MUST belong to a user)
- Rule: if a row can't logically exist without a value → `nullable = false`
- Application code has bugs; database constraints don't. Always enforce at DB level.

**18. Spring Data JPA Repository Pattern (2026-04-15)**

History: Before Spring Data (2011), every DAO (Data Access Object) required 50+ lines of boilerplate — EntityManager injection, manual JPQL, result mapping. Rod Johnson (Spring creator) and Oliver Drottbohm designed Spring Data to eliminate this: write an interface, Spring generates the implementation at runtime using JDK dynamic proxies.

- `JpaRepository<Entity, IdType>` extends `PagingAndSortingRepository` → `CrudRepository` → `Repository` (marker)
- Provides ~20 methods free: save, findById, findAll, delete, count, existsById, pagination, sorting
- No `@Repository` annotation needed on interfaces extending JpaRepository — Spring auto-detects them

**19. Derived Query Methods — Method Name = SQL (2026-04-15)**

- Spring parses repository method names at startup and generates JPA queries from them
- `findByEmail` → `SELECT * FROM users WHERE email = ?`
- `existsByEmail` → `SELECT count(*) > 0 FROM users WHERE email = ?`
- `findByUserIdAndRevokedFalse` → `SELECT * FROM ... WHERE user_id = ? AND revoked = false`
- `deleteByUserId` → `DELETE FROM ... WHERE user_id = ?`
- Field names must MATCH the entity field exactly: `findByName` works if entity has `name`, but `findByRoleName` fails if field is just `name`
- Prefix determines behavior: `findBy` returns entity/list, `existsBy` returns boolean, `countBy` returns long, `deleteBy` deletes
- Return type matters: `Optional<T>` for 0-or-1, `List<T>` for 0-or-many, `boolean` for exists checks

**20. JPA, Jakarta, Hibernate, Spring Data — The Dependency Chain (2026-04-15)**

History: In the early 2000s, EJB 2.x Entity Beans were horrendously complex. Gavin King created Hibernate (2001) as a simpler alternative. In 2006, JPA 1.0 was standardized (heavily inspired by Hibernate) as part of Java EE. When Oracle donated Java EE to Eclipse Foundation (2017), `javax.persistence.*` was renamed to `jakarta.persistence.*` due to trademark restrictions.

The layer stack in EquityCart:
```
Your Code (entities use @Entity, @Id, @Column)
    ↓ annotations from
Jakarta Persistence API (jakarta.persistence.*)  ← SPECIFICATION (interfaces + annotations)
    ↓ implemented by
Hibernate ORM                                     ← IMPLEMENTATION (generates SQL, manages cache)
    ↓ auto-configured by
Spring Data JPA                                   ← CONVENIENCE LAYER (generates repository proxies)
    ↓ bundled in
spring-boot-starter-data-jpa                      ← STARTER (pulls all above transitively)
```

- Jakarta annotations come from `jakarta.persistence-api` JAR, pulled transitively by the starter
- You never add `jakarta.persistence-api` as a direct dependency

**21. Marker Interface Pattern — Deep Dive With Compile Time & Runtime (2026-04-15)**

History: In Java 1.0 (1996), annotations didn't exist (added in Java 5, 2004). Designers needed a way to "tag" classes with metadata. Solution: an empty interface with zero methods. Implementing it signals a capability — the interface "marks" the class.

What it physically looks like — the actual JDK source:
```java
public interface Serializable {
    // literally nothing — zero methods
}
```

Three classic Java marker interfaces:
- `Serializable` — "I can be converted to bytes." Checked at runtime by ObjectOutputStream via `instanceof`.
- `Cloneable` — "I allow .clone() to work." Checked at runtime by Object.clone().
- `RandomAccess` — "I support fast index-based access (ArrayList, not LinkedList)." Checked at runtime by Collections utilities.

**At COMPILE TIME — Type Safety:**
- Compiler uses it to enforce type constraints
- A method `sendOverNetwork(Serializable data)` rejects non-Serializable objects at compile time
- Generics: `<T extends Serializable>` constrains type parameters — annotations CANNOT do this
- Example: `sendOverNetwork(new User())` → compiles if User implements Serializable; compile error if not

**At RUNTIME — Behavior Gating:**
- JVM/framework checks `instanceof` to decide behavior
- ObjectOutputStream internally does: `if (!(obj instanceof Serializable)) throw new NotSerializableException()`
- This is fast — `instanceof` is a JVM-native operation, much faster than reflection-based annotation checks

**At RUNTIME — Spring Data Proxy Generation:**
- `Repository<T, ID>` is a marker interface — completely empty, zero methods
- At startup, Spring scans classpath for all interfaces extending `Repository`
- Uses `Repository.class.isAssignableFrom(clazz)` to detect them
- For each match, generates a JDK dynamic proxy implementation
- `CrudRepository` adds actual CRUD methods on top; `Repository` itself just says "generate an implementation for me"

**Marker Interface vs Annotation — When to Use Which:**

| Feature | Marker Interface | Annotation |
|---|---|---|
| Compile-time type checking | Yes (`instanceof`, generics) | No |
| Runtime detection | `instanceof` (fast, JVM-native) | `isAnnotationPresent()` (reflection, slower) |
| Can constrain generics | Yes: `<T extends Serializable>` | No |
| Can carry metadata/values | No (it's empty) | Yes: `@Column(nullable = false)` |
| Introduced in | Java 1.0 (1996) | Java 5 (2004) |

Rule of thumb: Use marker interface when you need compile-time type constraints. Use annotations when you need to attach configuration data to a class.

**22. Java Records for DTOs (2026-04-20)**

History: Before Java 14 (2020), creating a simple data carrier class required ~30 lines of boilerplate — constructor, getters, equals(), hashCode(), toString(). Developers used Lombok @Data to reduce this. Brian Goetz (Java language architect) introduced `record` in Java 14 (preview), stable in Java 16 (2021).

- `record RegisterRequest(String email, String password) {}` — auto-generates constructor, getters (email(), password()), equals, hashCode, toString
- Getters are named `email()` not `getEmail()` (record convention)
- All fields are `final` — records are immutable by design → perfect for DTOs
- Validation annotations (`@NotBlank`, `@Email`, `@Size`) go directly on record parameters
- When to use records vs Lombok classes: records for immutable data carriers (DTOs, value objects); Lombok @Builder classes for mutable entities with complex construction

**23. BCrypt Password Hashing (2026-04-20)**

History: Storing passwords as plain text was the norm until major breaches (LinkedIn 2012 — 117M passwords leaked as unsalted SHA-1). MD5/SHA are designed for SPEED → GPUs crack billions/sec. Niels Provos and David Mazières designed BCrypt (1999) based on Blowfish cipher — intentionally SLOW (~100ms per hash), making brute-force impractical.

- `BCryptPasswordEncoder.encode("password123")` → `$2a$10$N9qo8uLO...` (60 chars)
- Output format: `$2a$` (algorithm) + `10$` (cost factor = 2^10 rounds) + `22char_salt` + `31char_hash`
- Salt is EMBEDDED in the hash string — no separate salt column needed
- Each call produces DIFFERENT output (different random salt), but `matches()` still works because it extracts the salt from the stored hash
- `matches(rawPassword, encodedPassword)` — first arg = plain text, second = hash. ORDER MATTERS.
- Cost factor 10 = default. Increase to 12+ as hardware gets faster (doubles computation per increment)

**24. @Bean and @Configuration — History and Purpose (2026-04-20)**

History: In Spring 1.x (2004), every bean was declared in XML: `<bean id="passwordEncoder" class="...BCryptPasswordEncoder"/>`. Spring 3.0 (2009) introduced JavaConfig — @Configuration classes with @Bean methods replaced XML with type-safe Java code.

- `@Configuration` marks a class as a bean definition source — Spring processes it at startup
- `@Bean` on a method means: "call this method once, store the return value in the application context"
- Spring then injects this object wherever the return type is needed (by type matching)
- @Bean methods take no arbitrary parameters — only other beans as parameters (auto-injected by Spring)
- Return the interface type (`PasswordEncoder`), not the implementation (`BCryptPasswordEncoder`) — follows dependency inversion principle

**25. Constructor Injection vs Field Injection (2026-04-20)**

History: `@Autowired` field injection (Spring 2.5, 2007) was the norm for years. The Spring team now recommends constructor injection (since ~2016) for better immutability and explicit dependencies.

Three injection methods in Spring:
- Constructor injection (`@RequiredArgsConstructor` + `private final`): dependencies explicit, immutable, compile-time safe if manually constructed
- Field injection (`@Autowired` on fields): less boilerplate, but dependencies hidden, fields mutable, missing mocks cause runtime NPE not compile error
- Setter injection (`@Autowired` on setters): rarely used, for optional dependencies only

Both field and constructor injection are testable with Mockito (@Mock + @InjectMocks works with both). The real difference:
- Constructor: missing dependency = compile error when constructing manually (`new AuthService(missingArg)`)
- Field: missing mock = silent null → runtime NullPointerException during test

`@RequiredArgsConstructor` (Lombok) generates a constructor for all `final` fields. If a class has one constructor, Spring auto-injects all parameters — no `@Autowired` needed. Don't mix `@Autowired` with `@RequiredArgsConstructor` — redundant.

**26. @Service and Service Layer Pattern (2026-04-20)**

History: In the late 1990s, developers put business logic in Servlets (controllers) creating untestable "fat controllers." Martin Fowler's Patterns of Enterprise Application Architecture (2002) formalized the Service Layer pattern: dedicated layer between controller and data access for business logic.

- `@Service` = `@Component` with semantic meaning ("this is business logic")
- Spring registers it as a bean and makes it injectable
- Interface + Implementation split (api/AuthService + impl/AuthServiceImpl): not required by Spring (CGLIB can proxy classes directly since Spring 4+), but useful for readability and future flexibility

**27. @Transactional — Atomic Database Operations (2026-04-20)**

- Wraps a method in a single database transaction — all DB operations succeed or all roll back
- register() does 3 writes: save User, save UserRole, save WalletAccount. If WalletAccount fails without @Transactional → orphaned User + UserRole in DB (inconsistent data)
- With @Transactional: any exception → all 3 writes roll back atomically
- Place on methods that perform multiple related writes
- Read-only operations (login, refresh) don't strictly need it

**28. Security: Constant Error Messages (2026-04-20)**

- Login should return "Invalid email or password" for BOTH wrong email and wrong password
- Separate messages ("User doesn't exist" vs "Wrong password") let attackers enumerate valid emails by testing different addresses
- Called constant-time error messaging — attacker gets the same response regardless of which part failed

**29. JJWT 0.12.x API — Building and Parsing JWTs (2026-04-20)**

History: JJWT (Java JWT) was created by Les Hazlewood (co-founder of Apache Shiro) in 2014 as a fluent, developer-friendly JWT library for Java. The 0.12.x series (2023) modernized the API — replacing deprecated methods like `signWith(key, algorithm)` with `signWith(key)` (algorithm auto-detected from key type).

Key methods:
- `Jwts.builder()` — creates a JWT builder. Chain: `.subject(userId)`, `.claim("roles", list)`, `.issuedAt(date)`, `.expiration(date)`, `.signWith(key)`, `.compact()` → returns the token string
- `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` — validates signature + expiry, returns `Jws<Claims>`
- `getSigningKey()`: decodes base64 secret → `Keys.hmacShaKeyFor(bytes)` → `SecretKey` for HMAC-SHA256
- If token is expired: `parseSignedClaims()` throws `ExpiredJwtException` automatically
- If signature invalid: throws `SignatureException`
- If malformed: throws `MalformedJwtException`

**30. Opaque Refresh Token vs JWT Refresh Token (2026-04-20)**

- Access token = JWT (self-contained, verified by signature, no DB lookup)
- Refresh token = UUID string (opaque, meaningless without DB lookup)
- Why opaque? Refresh tokens are verified by DB lookup anyway (to check revocation). No need for JWT structure, claims, or signature. `UUID.randomUUID()` is cryptographically random and unguessable.
- Refresh token rotation: on each use, revoke old → issue new. Detects token theft (one party gets "revoked" error).

**31. @Value for Configuration Injection (2026-04-20)**

History: Before Spring 3.0 (2009), externalized config required manual `Properties` file loading or XML `<property>` elements. `@Value` was introduced alongside JavaConfig, using SpEL (Spring Expression Language) property placeholders: `${property.key}`.

- `@Value("${jwt.secret}")` injects the value of `jwt.secret` from application.yml
- Default values: `@Value("${jwt.secret:defaultValue}")` — uses "defaultValue" if key missing
- Works with primitives: `@Value("${jwt.access-token-expiry}")` on `long` field — Spring auto-converts String to long
- Resolved at bean creation time — if key missing and no default, application fails to start
- YAML nested keys map to dot notation: `jwt.access-token-expiry` in code = `jwt: access-token-expiry:` in YAML

**33. Servlet Filters and OncePerRequestFilter (2026-04-20)**

History: Servlet Filters were introduced in Servlet 2.3 (2001) — they intercept HTTP requests before they reach Servlets (or Spring Controllers). A filter chain is an ordered list of filters; each filter processes the request and explicitly calls `filterChain.doFilter()` to pass it to the next one. Spring Security is built entirely on this concept — its security is a chain of ~15 filters.

- `OncePerRequestFilter` (Spring framework) guarantees the filter executes exactly once per request, even if the request is internally forwarded or dispatched multiple times (Servlet spec allows filters to re-execute on forwards/includes)
- Override `doFilterInternal()` — the "internal" suffix signals Spring handles the once-per-request guarantee
- MUST call `filterChain.doFilter(request, response)` to pass the request to the next filter — forgetting this silently drops the request (no response sent)

**34. Spring Security Authentication Model (2026-04-20)**

- `SecurityContextHolder` holds a `SecurityContext` which holds an `Authentication` object
- `Authentication` represents the currently authenticated user — stored per-thread (ThreadLocal)
- `UsernamePasswordAuthenticationToken` is the most common `Authentication` implementation
- 2-arg constructor `(principal, credentials)` → unauthenticated (login attempt)
- 3-arg constructor `(principal, credentials, authorities)` → authenticated (verified user)
- For JWT: principal = userId, credentials = null (JWT already proved identity), authorities = roles
- `SimpleGrantedAuthority("ROLE_CUSTOMER")` — Spring Security's standard authority implementation
- "ROLE_" prefix convention: `hasRole("CUSTOMER")` internally checks for `"ROLE_CUSTOMER"`. Dates back to Spring Security 1.0 (2004) when roles and fine-grained authorities were separated.

**35. @Component vs @Service vs @Repository vs @Controller (2026-04-20)**

All four are stereotype annotations that register a class as a Spring bean. The hierarchy:
- `@Component` — generic bean (infrastructure, utilities, filters)
- `@Service` — specialization for business logic (service layer)
- `@Repository` — specialization for data access (adds automatic exception translation for persistence exceptions)
- `@Controller` / `@RestController` — specialization for web endpoints

Functionally, `@Component` and `@Service` are identical. The difference is semantic — it tells developers what role the bean plays. Use the annotation that matches the bean's architectural purpose.

**36. SecurityFilterChain Configuration — The Modern Way (2026-04-20)**

History: Before Spring Security 5.7 (2022), you extended `WebSecurityConfigurerAdapter` and overrode `configure(HttpSecurity http)`. That was deprecated in favor of a `@Bean` method returning `SecurityFilterChain` — part of Spring's broader move from inheritance to composition.

- `HttpSecurity` is a builder — you declare configuration, not execution order
- Lambda DSL: every method takes a `Customizer<>` e.g. `csrf(csrf -> csrf.disable())`
- `AbstractHttpConfigurer::disable` — method reference shorthand for `csrf -> csrf.disable()`
- `addFilterBefore(filter, ReferenceFilter.class)` — positions your filter relative to a known filter in the chain; builder order doesn't matter, Spring Security's internal `FilterOrderRegistration` determines runtime order

**37. CSRF — When It Matters and When It Doesn't (2026-04-20)**

History: CSRF attacks exploit the browser's automatic cookie-sending behavior. If you're logged into `bank.com` (session cookie stored), a malicious site can trick your browser into POSTing to `bank.com/transfer` — the cookie goes with it automatically. CSRF tokens prevent this by requiring a server-generated token the attacker can't guess.

- JWT in `Authorization` header → browsers don't auto-attach headers → CSRF impossible
- Cookie-based auth → CSRF protection essential
- Rule: disable CSRF for stateless token-based APIs, keep it for session-based web apps

**38. Session Policies in Spring Security (2026-04-20)**

- `ALWAYS` — always create a session
- `IF_REQUIRED` (default) — create if needed
- `NEVER` — don't create, but use one if it exists
- `STATELESS` — never create, never use. Each request is independent. This is what JWT APIs need — the token is the proof, no server memory required. This is also what makes horizontal scaling work: any server instance can handle any request.

**32. Refresh Token Rotation — Security Pattern (2026-04-20)**

- Every time a refresh token is used, the old one is revoked (`setRevoked(true)`) and a new one is issued
- Without rotation: stolen token works until natural expiry (7+ days of unauthorized access)
- With rotation: if attacker and real user both use the same token, one hits "revoked" — immediate detection signal
- Implementation: in `refreshToken()` method, call `refreshTokenEntity.setRevoked(true)` + save BEFORE generating new tokens

**8. Spring Boot Starters Explained**

- starter-web: Spring MVC + embedded Tomcat + Jackson JSON + Bean Validation
- starter-data-jpa: Hibernate + Spring Data JPA repositories + EntityManager + TransactionManager
- starter-data-mongodb: Spring Data MongoDB + MongoDB driver + MongoRepository
- starter-security: Spring Security filter chain + authentication + authorization + BCrypt
- starter-validation: @NotNull, @Email, @Size, @Valid bean validation annotations
- starter-actuator: /health, /info, /metrics, /env monitoring endpoints
- starter-test: JUnit 5 + Mockito + AssertJ + Spring Test (@SpringBootTest, @MockBean)

---

### Interview Questions Discussed

**Q1: "Session vs JWT — which to choose for microservices and why?"**
A: JWT. Sessions require server-side storage and don't scale across service instances without shared session store. JWT is stateless — the token itself contains all auth info. Any service can verify it independently using the shared secret. JWT enables cross-service authentication without calling an auth service per request.

**Q2: "What's inside a JWT? Is it encrypted?"**
A: Three parts: Header (algorithm), Payload (claims like userId, role, expiry), Signature (HMAC of header+payload using secret key). The payload is base64 ENCODED, not encrypted — anyone can decode it. The signature only guarantees tampering hasn't occurred. Never store passwords or sensitive data in JWT payload.

**Q3: "Why use Access Token + Refresh Token instead of a single long-lived token?"**
A: Security. A single long-lived token means if stolen, the attacker has access for days/weeks. With the two-token pattern, the access token expires in 15-30 minutes (limiting damage), while the refresh token (stored more securely, sent less frequently) can be revoked by deleting it from the database.

**Q4: "What are the 6 ways to query data in Spring/JPA and when to use each?"**
A: (1) Derived queries for simple findBy (60-70% of queries). (2) JPQL for joins/aggregations that are DB-agnostic. (3) Native SQL for DB-specific features or performance-critical queries. (4) Criteria API for programmatic dynamic queries (verbose). (5) Specifications for clean, composable dynamic search. (6) QueryDSL for maximum compile-time safety. Start simple, escalate when needed.

**Q5: "When would you use JPQL vs Native SQL?"**
A: JPQL when the query involves entity relationships and you want database-agnostic code (survives DB migration). Native SQL when you need PostgreSQL-specific features (JSONB, window functions, CTEs), complex reports, or exact SQL control for performance.

**Q6: "What is @MappedSuperclass and when would you use it?"**
A: `@MappedSuperclass` creates a shared base class whose fields are inherited by child entities but is NOT a table itself. Use it for audit fields (createdAt, updatedAt, id) that every entity needs. Unlike `@Inheritance`, it doesn't create a parent table or require joins — each child gets its own copy of the columns. Best for unrelated entities sharing common fields.

**Q7: "Why use FetchType.LAZY and what's the default?"**
A: LAZY means the related entity is NOT loaded until explicitly accessed. This prevents loading the entire object graph on every query. Defaults: `@ManyToOne` and `@OneToOne` default to EAGER; `@OneToMany` and `@ManyToMany` default to LAZY. Best practice: always set LAZY explicitly on `@ManyToOne` and `@OneToOne`. Eager load when needed via JOIN FETCH or EntityGraph.

**Q8: "Why use a join entity (UserRole) instead of @ManyToMany?"**
A: `@ManyToMany` creates a hidden join table you can't add columns to. An explicit join entity gives: (1) full control over the table, (2) ability to add audit/metadata columns (assignedAt, assignedBy), (3) queryable and pageable, (4) clearer SQL. For simple cases `@ManyToMany` is fine, but production systems benefit from the explicit entity.

**Q9: "Why BigDecimal instead of double for money?"**
A: Floating-point types use binary representation — `0.1 + 0.2 = 0.30000000000000004`. BigDecimal stores as scaled integers giving exact decimal arithmetic. For financial systems, even 0.01 cent error across millions of transactions is unacceptable. Also: use `compareTo()` not `equals()` because `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` returns false.

**Q10: "GenerationType.IDENTITY vs SEQUENCE — when to use each?"**
A: IDENTITY relies on DB auto-increment — INSERT first, then read back the generated ID. Simple but can't batch inserts. SEQUENCE calls a DB sequence before INSERT to get the ID first — enables batching multiple INSERTs (Hibernate gets 50 IDs at once, queues 50 INSERTs). Use IDENTITY for simple CRUD, SEQUENCE for bulk operations (Spring Batch imports).

**Q11: "What are Gradle dependency scopes and when to use each?"**
A: Six scopes: `implementation` (default — your code imports it, hidden from consumers), `api` (exposed to consumers — library modules only), `compileOnly` (compile time only, not in JAR — Lombok), `runtimeOnly` (only at runtime — JDBC drivers, JJWT impl), `annotationProcessor` (compile-time code generators), `testImplementation` (tests only). Key rule: if a dependency's types appear in your module's public API, use `api`; otherwise `implementation`.

**Q12: "Unidirectional vs Bidirectional mappings — when to use each?"**
A: Unidirectional: only one entity knows about the relationship. Simpler, less coupling. Bidirectional: both entities can navigate to each other via `mappedBy`. Adds complexity — risk of infinite loops, StackOverflow in serialization, and both sides must be kept in sync. Default to unidirectional. Add bidirectional only when you frequently need to navigate from both sides (e.g., `user.getRefreshTokens()`).

**Q13: "@Builder.Default — what problem does it solve?"**
A: Lombok's `@Builder` ignores field initializers and sets everything to Java's zero-value (false, null, 0). So `private boolean enabled = true` becomes false when using the builder. `@Builder.Default` tells Lombok to use the field initializer as the builder's default. Only needed when your desired default differs from the zero-value.

**Q14: "How does Spring Data JPA generate repository implementations?" (2026-04-15)**
A: You write an interface extending JpaRepository. At startup, Spring scans for all interfaces extending the Repository marker interface, then creates JDK dynamic proxies that implement them. For inherited methods (save, findById), it delegates to SimpleJpaRepository. For derived query methods (findByEmail), it parses the method name, generates a JPQL query, and creates an interceptor that executes it. No implementation class needed.

**Q15: "What is a marker interface? Give examples." (2026-04-15)**
A: An interface with zero methods, used to "tag" a class with metadata. Classic examples: `Serializable` (signals the JVM this object can be serialized), `Cloneable` (enables Object.clone()), `RandomAccess` (signals efficient index-based access). Checked at runtime via `instanceof`. In Spring Data, `Repository<T, ID>` is a marker interface — Spring finds all interfaces extending it and generates implementations. Modern alternative: annotations (`@Entity`, `@Repository`) do the same via reflection.

**Q16: "What is the relationship between JPA, Hibernate, and Spring Data JPA?" (2026-04-15)**
A: JPA is a specification (set of interfaces and annotations under `jakarta.persistence.*`) — it defines WHAT an ORM should do. Hibernate is an implementation — it provides the actual code that reads JPA annotations and generates SQL. Spring Data JPA is a convenience layer on top — it auto-configures Hibernate and generates repository implementations from interfaces. `spring-boot-starter-data-jpa` bundles all three transitively.

**Q17: "Why use BCrypt instead of SHA-256 for password hashing?" (2026-04-20)**
A: SHA-256 is designed for speed — GPUs can compute billions of hashes per second, making brute-force trivial. BCrypt is intentionally slow (~100ms per hash) with a configurable cost factor that doubles computation per increment. It also auto-generates and embeds a random salt in each hash, so identical passwords produce different hashes. For passwords, slow is a feature, not a bug.

**Q18: "What is @Transactional and when would you use it?" (2026-04-20)**
A: @Transactional wraps a method in a database transaction — all DB operations succeed or all roll back. Use it when a method performs multiple related writes that must be atomic. Example: registration creates a User, UserRole, and WalletAccount — if any fail, all should roll back to prevent inconsistent data. Spring creates a proxy that opens a transaction before the method and commits/rolls back after.

**Q19: "Constructor injection vs field injection — what are the trade-offs?" (2026-04-20)**
A: Constructor injection (`@RequiredArgsConstructor` + `private final`): dependencies are explicit, immutable, and missing dependencies cause compile errors when constructing manually. Field injection (`@Autowired`): less boilerplate, but dependencies hidden across fields and mutable. Both are testable with Mockito's `@InjectMocks`. Spring team recommends constructor injection for the combination of immutability, transparency, and compile-time safety. Don't mix both — using `@Autowired` with `@RequiredArgsConstructor` is redundant.

**Q20: "Why use Java records for DTOs?" (2026-04-20)**
A: Records (Java 16+) auto-generate constructor, getters, equals, hashCode, toString — eliminating boilerplate for data carriers. They're immutable by design (all fields are final, no setters), which is ideal for DTOs that just carry data between layers and shouldn't be modified. Validation annotations go directly on record parameters. For entities that need mutability and builders, use regular classes with Lombok.

**Q21: "Why should login error messages not distinguish between wrong email and wrong password?" (2026-04-20)**
A: Separate messages ("User not found" vs "Wrong password") allow attackers to enumerate valid email addresses by testing different addresses and observing the response. Use the same message "Invalid email or password" for both cases. This is called constant-time error messaging — the attacker learns nothing about which part failed.

**Q22: "What is JJWT and how does it relate to JWT?" (2026-04-20)**
A: JWT (JSON Web Token) is a specification (RFC 7519) for creating signed tokens. JJWT is a Java library that implements this specification — it provides `Jwts.builder()` for creating tokens and `Jwts.parser()` for validating/parsing them. The JJWT 0.12.x API uses a fluent builder pattern: `Jwts.builder().subject(userId).claim("roles", list).signWith(key).compact()`.

**Q23: "Why is the refresh token a UUID instead of a JWT?" (2026-04-20)**
A: The refresh token's only purpose is to exchange for a new access token — it's looked up in the database, not parsed for claims. A UUID (`UUID.randomUUID()`) is opaque, unguessable, and has no extractable data. Since it's verified by DB lookup (not signature verification), there's no need for JWT structure. This also means revocation is simple: just mark the DB row as revoked.

**Q24: "What is refresh token rotation and why does it matter?" (2026-04-20)**
A: Refresh token rotation means every time a refresh token is used, the old one is revoked and a new one is issued. If an attacker steals a refresh token and the real user also uses it, one of them will hit a revoked token — that's your detection signal. Without rotation, a stolen refresh token works until expiry (potentially 7+ days).

**Q25: "How does JJWT handle expired tokens internally?" (2026-04-20)**
A: When `parseSignedClaims()` is called, JJWT automatically checks the `exp` (expiration) claim against the current time. If the token is expired, it throws `ExpiredJwtException` — you don't need a separate `isTokenExpired()` method. A general `validateToken()` that wraps parsing in try-catch covers expiry, invalid signatures, and malformed tokens all at once.

**Q26: "What does @Value do and how does it connect to application.yml?" (2026-04-20)**
A: `@Value("${jwt.secret}")` tells Spring to inject the value of `jwt.secret` from application.yml (or application.properties) into the annotated field. Spring resolves these at bean creation time. The `${}` syntax is a SpEL (Spring Expression Language) property placeholder. If the key doesn't exist and no default is provided (`${key:default}`), the application fails to start with a clear error.

**Q27: "What is OncePerRequestFilter and why use it instead of a regular Filter?" (2026-04-20)**
A: `OncePerRequestFilter` is a Spring base class that guarantees the filter executes exactly once per HTTP request. The Servlet spec allows filters to re-execute when a request is internally forwarded or included. Without this guarantee, your JWT validation could run multiple times on a single request — wasting resources and potentially causing issues. Override `doFilterInternal()` instead of `doFilter()`.

**Q28: "How does Spring Security store the authenticated user?" (2026-04-20)**
A: `SecurityContextHolder` → `SecurityContext` → `Authentication`. The context is stored in a ThreadLocal, so each request thread has its own authenticated user. In the JWT filter, after validating the token, you create a `UsernamePasswordAuthenticationToken` (3-arg constructor for authenticated) and set it via `SecurityContextHolder.getContext().setAuthentication()`. Downstream code — controllers, `@PreAuthorize`, etc. — reads from this context.

**Q29: "Why does Spring Security use the 'ROLE_' prefix?" (2026-04-20)**
A: Spring Security distinguishes between "roles" (coarse-grained: CUSTOMER, ADMIN) and "authorities" (fine-grained: READ_PRODUCTS, DELETE_USERS). The `ROLE_` prefix is the convention (since Spring Security 1.0, 2004) to identify role-type authorities. `hasRole("CUSTOMER")` internally checks for `ROLE_CUSTOMER`. `hasAuthority("ROLE_CUSTOMER")` checks the exact string. When creating `SimpleGrantedAuthority`, you add the prefix yourself: `"ROLE_" + roleName`.

**Q30: "Why disable CSRF for JWT-based APIs?" (2026-04-20)**
A: CSRF (Cross-Site Request Forgery) attacks trick a browser into sending cookies automatically to your server. CSRF protection works by requiring a secret token in the request that the attacker can't forge. But JWT is sent in the `Authorization` header — browsers don't automatically attach headers. So CSRF attacks can't work against JWT APIs, and the protection is unnecessary. Leaving it enabled would block all POST/PUT/DELETE requests that don't carry a CSRF token.

**Q31: "What is STATELESS session policy and why use it with JWT?" (2026-04-20)**
A: By default, Spring Security creates an HTTP session (JSESSIONID cookie) to remember the authenticated user between requests. With JWT, the token itself carries all the identity information — no server-side session needed. `SessionCreationPolicy.STATELESS` tells Spring Security to never create or use sessions. This makes your server truly stateless — any server instance can handle any request, which is critical for horizontal scaling in microservices.

**Q32: "Why does the builder order not matter in SecurityFilterChain config?" (2026-04-20)**
A: The `HttpSecurity` methods (`.csrf()`, `.authorizeHttpRequests()`, `.addFilterBefore()`) are declarative configuration — you're filling out a form, not writing execution steps. Spring Security has a hardcoded internal filter order in `FilterOrderRegistration`. When you call `.addFilterBefore(filter, Reference.class)`, it registers your filter at a position relative to the reference — regardless of where that line appears in the builder chain. The runtime filter chain order is determined by Spring Security's internal ordering, not your code order.

**Q33: "What's the difference between `authorizeHttpRequests()` and `authorizeHttpRequests(Customizer)`?" (2026-04-20)**
A: The no-arg version `authorizeHttpRequests()` returns a registry object you chain on directly — this is the old style, now deprecated. The lambda version `authorizeHttpRequests(auth -> auth.requestMatchers(...))` takes a `Customizer<>` lambda. Spring Security 5.2+ moved to the lambda DSL for consistency — every configuration method (`csrf`, `sessionManagement`, `authorizeHttpRequests`) follows the same `method(lambda)` pattern. The lambda style also enables better IDE support and avoids the `.and()` chaining that was needed with the old style.

---
