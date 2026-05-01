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

**21. Duplicate Method Names Cause Confusion (2026-04-20)**

- Problem: All three controller methods named `register`. Java allows this (overloading — different parameter types), but it's semantically misleading and error-prone.
- Fix: Named methods after their purpose: `register`, `login`, `refreshToken`.
- Lesson: Method overloading in controllers is legal but a bad idea. Name methods after their action. REST controllers especially benefit from clear naming since they map to API endpoints.

**22. Duplicate @PostMapping Path Causes Startup Failure (2026-04-20)**

- Problem: Two methods mapped to `@PostMapping("/register")`. Spring cannot resolve which method to call and fails at startup with `Ambiguous mapping` error.
- Fix: Changed refresh endpoint to `@PostMapping("/refresh")`.
- Lesson: Each combination of HTTP method + path must map to exactly one handler method. Spring checks this eagerly at startup — not at request time.

**23. Catch-All Exception Handling Hides Real Errors (2026-04-20)**

- Problem: `catch (Exception e)` returned 400 BAD_REQUEST for everything — including server errors like database failures. Also returned `AuthResponse(null, null)` — meaningless to the client.
- Fix: Removed try-catch entirely. Unhandled exceptions return 500 by default. Proper error handling will be added via `@RestControllerAdvice` (global exception handler).
- Lesson: Don't catch exceptions in controllers just to return a status code. Use `@RestControllerAdvice` to centralize error handling. 400 = client's fault, 500 = server's fault — using the wrong one makes debugging impossible.

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

**39. REST Controller Layer — Thin by Design (2026-04-20)**

History: The "thin controller, fat service" pattern comes from MVC architecture (1979, Trygve Reenskaug at Xerox PARC). Controllers should only translate HTTP ↔ service calls. Business logic belongs in the service layer. This keeps controllers reusable (same service can be called from controllers, scheduled jobs, message listeners) and testable (test business logic without HTTP).

- `@RestController` = `@Controller` + `@ResponseBody` (introduced Spring 4.0, 2013)
- `@RequestMapping("/api/auth")` — base path, combined with method-level `@PostMapping("/login")` → `/api/auth/login`
- `@RequestBody` — tells Spring to deserialize JSON body → Java object (via Jackson)
- `ResponseEntity<T>` — wraps response body + HTTP status code. `ResponseEntity.ok(body)` for 200, `new ResponseEntity<>(body, HttpStatus.CREATED)` for 201
- 201 CREATED for register (new resource created), 200 OK for login/refresh (no new resource)

**40. How JSON Serialization Works in Spring (2026-04-20)**

- Spring Boot auto-configures Jackson (JSON library) via `spring-boot-starter-web`
- Incoming request: `HttpMessageConverter` reads JSON body → calls Jackson → creates Java object (deserialization)
- Outgoing response: Jackson converts Java object → JSON string (serialization)
- Java records work automatically because they have public accessor methods (`email()`, `password()`)
- No annotations needed on DTOs for basic serialization — Jackson handles it by convention

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

**Q34: "What is @RestController and how does it differ from @Controller?" (2026-04-20)**
A: `@Controller` returns view names (for server-side rendered HTML — Thymeleaf, JSP). `@RestController` = `@Controller` + `@ResponseBody` — every method's return value is serialized directly to the response body (JSON by default). Introduced in Spring 4.0 (2013) to eliminate the need for `@ResponseBody` on every method in REST APIs.

**Q35: "What does @RequestBody do?" (2026-04-20)**
A: It tells Spring to read the HTTP request body and deserialize it into the annotated parameter. Spring uses `HttpMessageConverter` implementations — for JSON, it uses Jackson's `MappingJackson2HttpMessageConverter`. Without `@RequestBody`, Spring looks for query parameters or form data instead. The `Content-Type: application/json` header tells Spring which converter to use.

**Q36: "Why use ResponseEntity instead of returning the object directly?" (2026-04-20)**
A: Returning an object directly always sends 200 OK. `ResponseEntity` gives you control over the HTTP status code, headers, and body. For REST APIs, correct status codes matter: 201 for resource creation, 204 for no content, 404 for not found. Clients (and API consumers) rely on status codes for flow control — a mobile app checks for 401 to trigger re-login, not by parsing error messages.

**Q37: "Why should controllers be thin?" (2026-04-20)**
A: Business logic in controllers is untestable without spinning up HTTP infrastructure. It's also unreusable — you can't call a controller method from a scheduled job or message listener. The "thin controller, fat service" pattern (from MVC, 1979) keeps controllers as translators between HTTP and business logic. Test the service layer with unit tests, test the controller layer with integration tests.

**24. PostgreSQL Database Names Are Case-Sensitive (2026-04-21)**

- Problem: YAML had `equitycart`, pgAdmin showed `equityCart`. PostgreSQL treats unquoted names as lowercase, but if created with quotes (`"equityCart"`), it preserves case.
- Lesson: Always use lowercase database names in PostgreSQL. If you create `equityCart` via pgAdmin's GUI, check whether it added quotes. Unquoted `CREATE DATABASE equitycart` and `CREATE DATABASE EquityCart` both create `equitycart`. But `CREATE DATABASE "EquityCart"` creates a case-sensitive name.

**25. `data.sql` Doesn't Run for PostgreSQL by Default (2026-04-21)**

- Problem: Created `data.sql` with seed data, but the `roles` table stayed empty.
- Root cause: Since Spring Boot 2.5, `spring.sql.init.mode` defaults to `embedded` — meaning `data.sql` only executes for in-memory databases (H2, HSQLDB). PostgreSQL is not embedded, so the file is silently skipped.
- Fix: Set `spring.sql.init.mode: always` in `application.yml`.
- History: Before Spring Boot 2.5, `data.sql` ran unconditionally. The change was made after production incidents where seed scripts accidentally ran against prod databases, duplicating or corrupting data.

**26. `data.sql` Runs Before Hibernate Creates Tables (2026-04-21)**

- Problem: Even with `mode: always`, `data.sql` can fail because it runs before `ddl-auto: update` creates/updates tables.
- Fix: Set `spring.jpa.defer-datasource-initialization: true` — this flips the order: Hibernate DDL first → `data.sql` second.
- Lesson: Spring Boot has two initialization phases: (1) SQL script init (`data.sql`, `schema.sql`) and (2) Hibernate DDL (`ddl-auto`). By default, SQL scripts run first. The `defer` flag reverses this.

**27. YAML Indentation Changes the Property Path (2026-04-21)**

- Problem: Placed `defer-datasource-initialization: true` under `spring.data` instead of `spring.jpa`. Spring silently ignored the unknown property.
- What happened: `spring.data.defer-datasource-initialization` is not a real property. `spring.jpa.defer-datasource-initialization` is. One indent level difference = completely different property.
- Lesson: YAML is whitespace-sensitive. Every indentation level maps to a dot-separated property path. Always verify the full property path matches the Spring Boot documentation. Use IDE autocomplete when possible.

**28. Java Field Defaults vs Database Rows (2026-04-21)**

- Problem: Set `private String name = UserRoles.CUSTOMER.name()` on Role entity, expected `CUSTOMER` to appear in the `roles` table.
- Reality: Java field defaults only apply when creating a `new Role()` in Java. They don't insert rows into the database. `ddl-auto: update` creates the table structure (columns, constraints), not data.
- Lesson: Entity field defaults = "what value should a new Java object have in memory." Database seed data = "what rows should exist on disk." These are separate concerns. Use `data.sql`, `CommandLineRunner`, or a JSON seeder to populate reference data.

**Q38: "Why does Spring Boot skip data.sql for PostgreSQL?" (2026-04-21)**
A: Safety. Since Spring Boot 2.5, `spring.sql.init.mode` defaults to `embedded` — only in-memory databases get auto-seeded. This prevents `data.sql` from accidentally running against production databases on every restart. For PostgreSQL, you must explicitly set `mode: always`. In production, you'd typically use Flyway or Liquibase instead of `data.sql` — they track which migrations have already run and never re-execute them.

**Q39: "What's the difference between `data.sql` and `schema.sql` in Spring Boot?" (2026-04-21)**
A: `schema.sql` creates/alters table structure (DDL: CREATE TABLE, ALTER TABLE). `data.sql` inserts/updates data (DML: INSERT, UPDATE). Both run at the same phase — before Hibernate DDL by default. If you use `ddl-auto: update`, you typically don't need `schema.sql` because Hibernate handles DDL. You'd only use `schema.sql` with `ddl-auto: none` (fully manual schema management).

**Q40: "How do you handle multiple active refresh tokens per user?" (2026-04-21)**
A: It depends on the security model. Multi-device apps (Gmail, Netflix) allow multiple active refresh tokens — one per session/device. Banking apps revoke all previous tokens on new login (single session). For multi-token systems, you need periodic cleanup of expired/revoked tokens — either a `@Scheduled` job or database TTL. The refresh token table will grow unbounded otherwise.

**29. CommandLineRunner for Seed Data (2026-04-21)**

- `CommandLineRunner` is a functional interface with `run(String... args)`. Spring Boot executes all `CommandLineRunner` beans after the full application context is loaded — meaning all beans are created, all `@PostConstruct` have run, and Hibernate has created/updated tables.
- History: Added in Spring Boot 1.0 (2014). Its sibling `ApplicationRunner` (added 1.3) is identical except it receives parsed `ApplicationArguments` instead of raw `String[]`. Use either — for seed data it makes no difference.
- Advantage over `data.sql`: no ordering tricks (`defer-datasource-initialization`), no `sql.init.mode` needed, type-safe, can use repositories and Spring beans.

**30. Jackson ObjectMapper and TypeReference (2026-04-21)**

- `ObjectMapper` is Jackson's core class for JSON ↔ Java conversion. Spring Boot auto-configures one with sensible defaults — always inject it, never `new ObjectMapper()`.
- `TypeReference<List<RoleSeedData>>` solves Java's **type erasure** problem. At runtime, `List<RoleSeedData>` becomes just `List` — the generic type is erased. `TypeReference` captures the full generic type at compile time using an anonymous subclass trick (the `{}` after `new TypeReference<>()` creates an anonymous class that preserves the type info).
- History: Type erasure was a deliberate Java 5 (2004) design choice for backward compatibility with pre-generics code. Jackson, Gson, and other libraries all need workarounds like `TypeReference` because of it.

**31. Classpath Resource Loading in Spring (2026-04-21)**

- `@Value("classpath:seedData/roles.json") Resource rolesFile` — Spring resolves `classpath:` prefix by searching all JARs and class directories on the classpath.
- In a multi-module Gradle project, `user/src/main/resources/` gets packaged into `user-service.jar`. When `app` depends on `user`, that JAR is on the classpath — so `classpath:seedData/roles.json` finds the file inside the user JAR.
- Alternative: `new ClassPathResource("seedData/roles.json")` does the same thing without Spring injection.
- `resource.getInputStream()` reads the file contents — works for both files on disk and files inside JARs (unlike `resource.getFile()` which fails for JAR-embedded resources).

**Q41: "Why inject ObjectMapper instead of creating a new one?" (2026-04-21)**
A: Spring Boot auto-configures ObjectMapper with project-wide settings (date formats, naming strategies, null handling, module registration like JavaTimeModule). `new ObjectMapper()` creates a bare instance that ignores all that config. If you later add `spring.jackson.date-format` in YAML, the injected one picks it up — the hand-created one doesn't. Consistency matters when multiple parts of the app serialize/deserialize JSON.

**Q42: "What is TypeReference and why is it needed?" (2026-04-21)**
A: Java erases generic types at runtime (type erasure, Java 5 design). `objectMapper.readValue(json, List.class)` loses the element type — Jackson doesn't know it's `RoleSeedData`, so it deserializes to `List<LinkedHashMap>`. `TypeReference<List<RoleSeedData>>` preserves the full generic type via an anonymous subclass trick. The `{}` creates a subclass whose `.getGenericSuperclass()` retains the type parameter — Jackson reads that reflectively.

**Q43: "What's the difference between CommandLineRunner and data.sql for seeding?" (2026-04-21)**
A: `data.sql` is raw SQL executed during datasource initialization — before the app context is fully ready. It requires `defer-datasource-initialization: true` with `ddl-auto: update`, and `sql.init.mode: always` for non-embedded databases. `CommandLineRunner` runs after full context load — all beans, Hibernate DDL, everything is ready. It's type-safe (uses repositories, not raw SQL), idempotent by design (check before insert in Java), and doesn't need ordering tricks. Trade-off: `data.sql` is simpler for static one-liners; `CommandLineRunner` is better for anything conditional or multi-entity.

**32. @RestControllerAdvice — Global Exception Handling (2026-04-23)**

- History: Before Spring 3.2 (2012), exception handling was per-controller (`@ExceptionHandler` in each controller) or XML-configured (`SimpleMappingExceptionResolver`). Spring 3.2 introduced `@ControllerAdvice` — one class that intercepts exceptions from all controllers. Spring 4.3 (2016) added `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`.
- Flow: Controller → Service throws exception → `@RestControllerAdvice` intercepts → matches `@ExceptionHandler` by exception type → returns structured JSON error response.
- `@ResponseStatus(HttpStatus.NOT_FOUND)` on the handler method sets the HTTP status code for the response.
- The catch-all `@ExceptionHandler(Exception.class)` prevents stack traces from leaking to clients. In production, return a generic message ("An unexpected error occurred") and log the real exception server-side.

**33. Separation of Concerns: Exceptions vs Handlers (2026-04-23)**

- Problem: Initially put `@RestControllerAdvice` and `@ExceptionHandler` on the exception classes themselves — making each exception handle itself.
- Why it's wrong: (1) Violates separation of concerns — an exception's job is to carry error info, a handler's job is to format responses. (2) Spring instantiates `@RestControllerAdvice` classes as beans, but exceptions are created with `throw new` — two separate instances with different lifecycles. (3) Adding a new exception means modifying the exception class to add handler logic, instead of adding one method to the central handler.
- Analogy: Exceptions are the charges (what went wrong), the handler is the judge (decides the response). The charge doesn't decide its own verdict.

**34. Custom Exceptions for HTTP Status Mapping (2026-04-23)**

- Raw `RuntimeException` can't be distinguished — the handler doesn't know if it's a 400, 404, or 500. Custom exceptions map to specific HTTP statuses:
  - `ResourceNotFoundException` → 404 Not Found
  - `DuplicateResourceException` → 409 Conflict
  - `AuthenticationException` → 401 Unauthorized
  - `AccountDisabledException` → 403 Forbidden
- All extend `RuntimeException` (unchecked) — no need to declare `throws` in method signatures. Spring's `@Transactional` only rolls back on unchecked exceptions by default, which is what we want.

**Q44: "What's the difference between @ControllerAdvice and @RestControllerAdvice?" (2026-04-23)**
A: Same relationship as `@Controller` vs `@RestController`. `@ControllerAdvice` returns view names (for server-side rendered HTML). `@RestControllerAdvice` adds `@ResponseBody` — return values are serialized to JSON. For REST APIs, always use `@RestControllerAdvice`.

**Q45: "Why use custom exceptions instead of Spring's ResponseStatusException?" (2026-04-23)**
A: `ResponseStatusException` (Spring 5, 2017) couples your service layer to HTTP — the service decides the status code. Custom exceptions keep the service layer HTTP-agnostic — the service throws `DuplicateResourceException`, and the handler decides it's 409. If the same service is called from a message listener (no HTTP), the exception still makes sense. `ResponseStatusException` is fine for quick prototypes, but custom exceptions scale better in real applications.

**Q46: "Why should the catch-all handler not expose ex.getMessage() to clients?" (2026-04-23)**
A: Unexpected exceptions can contain internal details: SQL queries, file paths, class names, stack frames. Exposing these is an information disclosure vulnerability (OWASP A01:2021). Return a generic message to the client, log the full exception server-side with `log.error("Unexpected error", ex)`. Only custom exceptions (whose messages you control) are safe to expose.

**35. Java Records — Capabilities and Limitations (2026-04-23)**

History: Records were added in Java 14 (2020, preview) and finalized in Java 16 (2021). Inspired by Kotlin's `data class` and Scala's `case class`. Motivation: a simple DTO used to require 50+ lines (fields, constructor, getters, equals, hashCode, toString). Records generate all of this from a single line.

What the compiler generates from `public record Foo(String a, int b) {}`:
- `private final` fields for each component
- Canonical (all-args) constructor
- Accessor methods: `a()`, `b()` (NOT `getA()` — not JavaBean convention)
- `equals()`, `hashCode()`, `toString()`

Limitations:
1. **Immutable** — fields are `final`, no setters, no modification after creation
2. **Cannot extend a class** — implicitly extends `java.lang.Record` (single inheritance)
3. **Can implement interfaces** — accessor methods can satisfy interface contracts
4. **No additional instance fields** — all fields must be in the component list (parentheses). Static fields/methods are allowed
5. **Not suitable for JPA entities** — JPA requires no-arg constructor + mutable fields + setters
6. **Lombok is mostly redundant** — `@Builder`, `@Getter`, `@Setter`, `@Data`, `@NoArgsConstructor`, `@Value`, `@With` either conflict or duplicate what the record provides. `@Slf4j`/`@Log4j2` work (they just add a static field)
7. **Spring/Jakarta annotations work** — `@Valid`, `@NotBlank`, `@Email`, `@JsonProperty`, `@RequestBody` all work. Annotations go on constructor parameters in the component list

Compact constructor — validation without re-declaring parameters:
```java
public record PriceRange(double min, double max) {
    public PriceRange {  // no parentheses — "compact"
        if (max < min) throw new IllegalArgumentException("Max must be >= min");
        // fields are auto-assigned AFTER this block
    }
}
```

Static factory method — the record alternative to `@Builder`:
```java
public record ErrorResponse(int status, String error, String message, LocalDateTime timestamp) {
    public static ErrorResponse of(HttpStatus httpStatus, String message) {
        return new ErrorResponse(httpStatus.value(), httpStatus.getReasonPhrase(), message, LocalDateTime.now());
    }
}
```

When to use: DTOs (request/response), value objects, config holders, any immutable data carrier.
When NOT to use: JPA entities, mutable state, Spring beans, anything needing inheritance.

**Q47: "Can records be JPA entities?" (2026-04-23)**
A: No. JPA requires: (1) no-arg constructor — records don't have one, (2) mutable fields — record fields are final, (3) setter access — records have no setters. Hibernate 6 has experimental support but it's not production-ready. Records can be used as **JPA projections** (query result DTOs) via Spring Data's interface/class-based projections, but not as managed entities.

**Q48: "Why don't records use JavaBean naming (getEmail) for accessors?" (2026-04-23)**
A: Records deliberately broke from JavaBean convention (1996) because they're a different concept. JavaBeans were designed for mutable, tool-friendly components (visual GUI builders). Records are immutable value types — no setters, so `get` prefix makes less sense. Jackson 2.12+ (2020) added native record support, and Spring handles both styles, so this rarely causes issues with modern libraries. Older frameworks expecting `getX()` may not work.

**Q49: "Why doesn't @Builder work with records?" (2026-04-23)**
A: Lombok's `@Builder` generates a private all-args constructor + a static builder class. Records already have a public canonical constructor that can't be made private. The two conflict. Alternatives: (1) static factory method on the record, (2) compact constructor for validation, (3) just use the canonical constructor directly — for DTOs with 2-4 fields, a builder adds complexity without benefit.

**36. Bean Validation in Spring Boot (2026-04-24)**

History: Bean Validation started as JSR 303 (Java EE 6, 2009), evolved to JSR 380 (Bean Validation 2.0, 2017), now Jakarta Validation 3.0. The reference implementation is Hibernate Validator (same company as Hibernate ORM, but a completely separate project). Spring Boot auto-configures it via `spring-boot-starter-validation`.

Flow: Client sends JSON → `@RequestBody` deserializes → `@Valid` triggers validation → valid: proceed to controller → invalid: `MethodArgumentNotValidException` thrown → `@RestControllerAdvice` catches → 400 response.

Key annotations:
- `@NotNull` — not null (allows empty `""`)
- `@NotBlank` — not null, not empty, not whitespace (use for strings)
- `@Email` — valid email format
- `@Size(min=, max=)` — string length or collection size
- `@Min` / `@Max` — numeric bounds
- `@Pattern(regexp=)` — custom regex

`@Valid` on the controller parameter is the trigger — without it, annotations on the DTO are just metadata, validation never runs.

**37. Separate Response Types for Different Error Shapes (2026-04-24)**

- `ErrorResponse(status, error, message, timestamp)` — for simple errors (404, 409, 401, 403, 500)
- `ValidationErrorResponse(status, error, message, timestamp, List<FieldError>)` — for validation errors (400)
- Why separate: avoids nullable `fieldErrors` field on non-validation errors, produces clean OpenAPI schemas, clients can distinguish error types by response shape
- Industry practice: Google, Stripe, AWS use different response shapes for validation vs other errors
- `FieldError` as nested record inside `ValidationErrorResponse` — scoped to where it's used, not polluting the outer package

**38. Log Levels for Different Error Types (2026-04-24)**

- `logger.warn()` — for client mistakes (validation failures, bad input, auth failures). Expected in normal operation.
- `logger.error()` — for unexpected server-side problems (NPE, database down, unhandled exceptions). Should trigger alerts in production.
- Using `error` for validation failures causes alert fatigue — monitoring tools page on-call engineers every time someone submits a blank form.

**Q50: "What's the difference between @NotNull, @NotBlank, and @NotEmpty?" (2026-04-24)**
A: `@NotNull` only checks for null — `""` passes. `@NotEmpty` checks not null AND not empty — `""` fails but `"  "` passes. `@NotBlank` checks not null, not empty, AND not just whitespace — `"  "` fails. For string fields in APIs, `@NotBlank` is almost always what you want. `@NotEmpty` is useful for collections (`List<String>` must have at least one element).

**Q51: "Why use @Valid instead of validating manually in the service?" (2026-04-24)**
A: `@Valid` is declarative — the framework handles validation before your code runs. Manual validation (`if email == null`) is scattered across service methods, easy to forget, and mixes validation logic with business logic. Bean Validation centralizes constraints on the DTO (single source of truth), produces consistent error responses, and is testable independently. Manual validation is only needed for cross-field rules (e.g., "end date must be after start date") that annotations can't express.

**Q52: "Why use a separate ValidationErrorResponse instead of adding fields to ErrorResponse?" (2026-04-24)**
A: Single Responsibility. `ErrorResponse` represents a simple error — one message, one status. `ValidationErrorResponse` represents multiple field-level problems. Combining them means every non-validation error has a null `fieldErrors` field — wasted bytes, confusing to clients, messy OpenAPI schema. Separate types let clients distinguish error shapes by type, and each record stays focused on its purpose. Records are cheap to create — don't hesitate to use multiple.

**39. SecurityContextHolder and ThreadLocal — How Per-Request Auth Works (2026-04-24)**

- `SecurityContextHolder` stores `SecurityContext` in a `ThreadLocal` by default (`MODE_THREADLOCAL`).
- `ThreadLocal` (Java 1.2, 1998): each thread has its own independent copy of the variable. Thread-42's SecurityContext with userId=5 is completely invisible to Thread-43's SecurityContext with userId=8.
- Flow: JwtAuthFilter extracts userId from JWT → stores in SecurityContext on current thread → controller reads from same thread's SecurityContext → same userId, guaranteed.
- After request: `SecurityContextHolderFilter` clears the context, so the thread is clean for the next request (thread pool reuse).
- Getting the principal: `Authentication authentication` (method parameter, Spring-injected) or `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` (manual static call). Method parameter is preferred — cleaner, testable, no static coupling.

**40. Spring Security Filter Chain Architecture (2026-04-24)**

History: Filter chain architecture introduced in Spring Security 3.0 (2009), replacing interceptor-based approach.

Request flow:
1. Tomcat receives request, assigns thread
2. `DelegatingFilterProxy` (Spring 1.0, 2004) — bridges Servlet container ↔ Spring beans. Servlet containers don't know about Spring beans, so this proxy delegates to Spring's `FilterChainProxy`.
3. `FilterChainProxy` — Spring Security's entry point. Looks up matching `SecurityFilterChain` bean and runs its filters in order.
4. Filter chain runs: SecurityContextHolderFilter → HeaderWriterFilter → LogoutFilter → **JwtAuthFilter (ours)** → UsernamePasswordAuthenticationFilter → AnonymousAuthenticationFilter → ExceptionTranslationFilter → **AuthorizationFilter** (checks permitAll/authenticated)
5. If authorized → `DispatcherServlet` → controller

`addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` — inserts our filter at the right position. We place it before the default form-login filter because JWT auth must set the SecurityContext before any authorization checks run.

**41. Stateless Logout with JWT (2026-04-24)**

- Access tokens are self-contained and stateless — no server-side revocation without a blacklist.
- Standard approach: revoke all refresh tokens → current access token expires naturally (15 min) → client deletes token from storage.
- This is what Google, GitHub, and OAuth2 implementations do.
- For instant access token revocation: need a server-side blacklist (Redis with TTL = remaining token lifetime). This adds statefulness but covers edge cases like compromised tokens.

**Q53: "How does SecurityContextHolder prevent one user from seeing another's data?" (2026-04-24)**
A: `ThreadLocal`. Each HTTP request runs on its own thread from Tomcat's pool. `SecurityContextHolder` (default `MODE_THREADLOCAL`) stores the `SecurityContext` in a thread-local variable — Thread-42 with userId=5 can never access Thread-43's context with userId=8. The context is cleared after each request (`SecurityContextHolderFilter`), preventing stale data when threads are reused from the pool. The only way to cross-contaminate is manually sharing SecurityContext across threads (don't) or a compromised JWT secret key.

**Q54: "What is DelegatingFilterProxy and why does Spring need it?" (2026-04-24)**
A: Servlet containers (Tomcat, Jetty) manage their own filter lifecycle — they instantiate filters via `web.xml` or `@WebFilter`, not via Spring. But Spring Security's filters are Spring beans (they need dependency injection). `DelegatingFilterProxy` solves this: it's a plain Servlet filter that Tomcat creates, but it delegates every request to a Spring bean (`FilterChainProxy`). It's the bridge between Tomcat's world and Spring's world. Without it, Spring Security filters couldn't participate in the Servlet filter chain.

**Q55: "Why does addFilterBefore use UsernamePasswordAuthenticationFilter as the reference?" (2026-04-24)**
A: `UsernamePasswordAuthenticationFilter` is Spring's default form-login authentication filter — it handles `POST /login` with username/password in form data. In a JWT API, you don't use form login, but you need your authentication to happen at roughly the same point in the chain — after CSRF and headers, but before authorization checks. Placing your JWT filter "before UsernamePasswordAuthenticationFilter" positions it correctly. The reference filter doesn't need to be active — it's just a position marker in the chain.

**42. Form Login — The Traditional Authentication Pattern (2026-04-24)**

History: Form login was THE authentication pattern from the late 1990s through mid-2010s. Every web framework had it: PHP sessions, Java's `j_security_check` (Servlet spec), Rails `devise`, Django `auth`. JWT/token-based auth only became mainstream around 2014-2015 with the rise of SPAs (React, Angular) and mobile apps that needed stateless APIs.

What it is: an HTML form submits username + password as `application/x-www-form-urlencoded` (not JSON). The server creates a session, sends back a cookie. Used by WordPress admin, Jira, Jenkins, banking portals — anywhere the browser manages sessions via cookies.

When to use: server-side rendered apps (Thymeleaf, JSP), admin panels.
When NOT to use: REST APIs consumed by SPAs/mobile (use JWT), microservice-to-microservice (use OAuth2 client credentials).

`UsernamePasswordAuthenticationFilter` handles form login:
1. Checks: is this a POST to `/login`? If no → does nothing, passes through.
2. Extracts username + password from form parameters (not JSON body).
3. Creates unauthenticated `UsernamePasswordAuthenticationToken` (2-arg).
4. Delegates to `AuthenticationManager.authenticate()`.
5. `AuthenticationManager` → `DaoAuthenticationProvider` → calls `UserDetailsService.loadUserByUsername()` (your code) → calls `PasswordEncoder.matches()`.
6. Success → creates authenticated token (3-arg) → `SecurityContextHolder.setAuthentication()` → session created → redirect.
7. Failure → `AuthenticationException` → redirect to `/login?error`.

In EquityCart's JWT API, `UsernamePasswordAuthenticationFilter` never activates because no request matches its criteria (form POST to `/login`). It just passes every request through.

**43. FilterChainProxy and SecurityFilterChain Registration (2026-04-24)**

How `FilterChainProxy` finds your `SecurityFilterChain`:
1. Spring Boot auto-configures `DelegatingFilterProxy` as a Servlet filter named `"springSecurityFilterChain"`.
2. `DelegatingFilterProxy` looks up a Spring bean with that name → finds `FilterChainProxy` (auto-configured by `@EnableWebSecurity`).
3. `FilterChainProxy` holds a `List<SecurityFilterChain>` — your `@Bean SecurityFilterChain` is added during context startup.
4. At request time, `FilterChainProxy` iterates its list, calls `chain.matches(request)`, first match wins.
5. `httpSecurity.build()` returns `DefaultSecurityFilterChain` (implements `SecurityFilterChain`).

Multiple `SecurityFilterChain` beans: if you have separate chains (one for API, one for admin), each has a `requestMatcher` — `FilterChainProxy` picks the first match. Use `@Order` to control priority.

**44. DelegatingFilterProxy and DispatcherServlet Are Siblings, Not Parent-Child (2026-04-24)**

Common misconception: `DelegatingFilterProxy` does NOT come under `DispatcherServlet`. They are siblings in the Servlet container:

```
Tomcat (Servlet Container)
├── Filter chain (Servlet Filters — run BEFORE any servlet)
│   ├── DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain
│   └── other filters...
└── DispatcherServlet (runs AFTER all filters pass)
    ├── HandlerMapping → finds which controller method to call
    ├── HandlerAdapter → invokes the controller method
    ├── ViewResolver → resolves view (if server-side rendering)
    └── HandlerExceptionResolver → finds @ExceptionHandler methods
```

Filters and Servlets are separate concepts in the Java Servlet spec (1997). Filters intercept requests BEFORE they reach any servlet. Security must run before business logic — that's why Spring Security is a filter, not part of DispatcherServlet.

**45. How Unhandled Exceptions Reach GlobalExceptionHandler (2026-04-24)**

When a service method (e.g., `UserServiceImpl.logout()`) doesn't throw custom exceptions, infrastructure exceptions can still occur:
- Database down → `DataAccessException` (Spring's wrapper around JDBC/Hibernate exceptions)
- Connection timeout → `DataAccessException`
- Constraint violation → `DataIntegrityViolationException`

Flow: Service throws `DataAccessException` → Controller doesn't catch → `DispatcherServlet` catches → looks for matching `@ExceptionHandler` → no specific handler for `DataAccessException` → falls through to `@ExceptionHandler(Exception.class)` (catch-all) → returns 500: "An unexpected error occurred".

This is correct: database failures ARE unexpected server errors — 500 is the right status. Custom exceptions are for business rule violations you can predict and name (duplicate email → 409, invalid password → 401). Infrastructure failures are handled by the catch-all.

To handle specific infrastructure errors differently (e.g., 503 for database down), add:
`@ExceptionHandler(DataAccessException.class)` returning `HttpStatus.SERVICE_UNAVAILABLE`.

**Q56: "What is form login and when is it used?" (2026-04-24)**
A: Form login is the traditional authentication pattern where an HTML form submits username + password as `application/x-www-form-urlencoded` to a server endpoint. The server validates credentials, creates a session, and sends a session cookie. Used for server-side rendered apps (Thymeleaf, JSP, WordPress, Jenkins). NOT used for REST APIs — those use JWT. Spring Security's `UsernamePasswordAuthenticationFilter` handles this pattern.

**Q57: "What does UsernamePasswordAuthenticationFilter do and when does it activate?" (2026-04-24)**
A: It intercepts POST requests to `/login` (configurable), extracts username/password from form parameters, creates an unauthenticated token, and delegates to `AuthenticationManager` → `DaoAuthenticationProvider` → `UserDetailsService.loadUserByUsername()` → `PasswordEncoder.matches()`. On success: authenticated token stored in SecurityContext + session created. On failure: `AuthenticationException` thrown. In a JWT API, it never activates because no request matches its criteria — it's just a position marker in the filter chain.

**Q58: "How does FilterChainProxy find your SecurityFilterChain bean?" (2026-04-24)**
A: Spring Boot auto-configures `DelegatingFilterProxy` (a Servlet filter) that delegates to a Spring bean named `"springSecurityFilterChain"` — which is `FilterChainProxy`. During context startup, Spring collects all `SecurityFilterChain` beans (returned by `httpSecurity.build()`) and hands them to `FilterChainProxy`. At request time, `FilterChainProxy` iterates the list, calls `chain.matches(request)` on each, and the first match wins. If you have multiple chains, `@Order` controls priority.

**Q59: "What is the relationship between DelegatingFilterProxy and DispatcherServlet?" (2026-04-24)**
A: They are siblings, not parent-child. Both live in the Servlet container (Tomcat). `DelegatingFilterProxy` is a Servlet filter — it runs BEFORE any servlet. `DispatcherServlet` is a Servlet — it runs AFTER all filters pass. Filters and Servlets are separate concepts from the Java Servlet spec (1997). Security (filters) runs before business logic (servlet) by design. `DelegatingFilterProxy` bridges Tomcat's filter world to Spring's bean world; `DispatcherServlet` bridges Tomcat's servlet world to Spring's MVC world.

**Q60: "How do exceptions from a service without custom exception handling reach the GlobalExceptionHandler?" (2026-04-24)**
A: Infrastructure exceptions (database down → `DataAccessException`, constraint violation → `DataIntegrityViolationException`) are unchecked `RuntimeException` subclasses thrown by Spring Data. They propagate from service → controller → `DispatcherServlet`. The servlet looks for matching `@ExceptionHandler` in `@RestControllerAdvice`. No specific handler match → falls through to `@ExceptionHandler(Exception.class)` (catch-all) → returns 500. This is correct — database failures are unexpected server errors. Custom exceptions are for predictable business rule violations (duplicate email, invalid password), not infrastructure failures.

**46. Role-Based Access Control (RBAC) in Spring Security (2026-04-24)**

History: RBAC as a formal model was defined by NIST in 1992 (Ferraiolo & Kuhn). In Spring Security 1.x (2004), all authorization was URL-based in XML. Method-level `@Secured` came in 2.0 (2008). `@PreAuthorize` with SpEL came in 3.0 (2009). `@EnableMethodSecurity` replaced `@EnableGlobalMethodSecurity` in 5.6 (2022).

Two complementary approaches:
- **URL-based** (in SecurityFilterChain): coarse-grained, path-prefix-level rules. `requestMatchers("/api/admin/**").hasRole("ADMIN")`. Evaluated by `AuthorizationFilter` in the filter chain.
- **Method-level** (`@PreAuthorize`): fine-grained, per-method rules. `@PreAuthorize("hasRole('ADMIN')")`. Evaluated by Spring AOP proxy wrapping the bean. Requires `@EnableMethodSecurity` on a `@Configuration` class.

Both work together — URL rules filter first, method rules filter second. Use URL-based for entire path prefixes, method-level for specific operations and ownership checks.

**Matcher ordering matters**: Spring evaluates top-to-bottom, first match wins. Most specific rules first (`/api/admin/**`), most general last (`anyRequest()`). A misplaced `anyRequest().authenticated()` before specific rules would shadow them.

**47. @EnableMethodSecurity vs @EnableGlobalMethodSecurity (2026-04-24)**

- `@EnableGlobalMethodSecurity(prePostEnabled = true)` — deprecated since Spring Security 5.6 (2022)
- `@EnableMethodSecurity` — the replacement. Enables `@PreAuthorize` and `@PostAuthorize` by default (no `prePostEnabled = true` needed)
- Also supports `@Secured` (disabled by default, enable with `@EnableMethodSecurity(securedEnabled = true)`)
- Must be on a `@Configuration` class — typically `SecurityConfig`

**48. hasRole() vs hasAuthority() (2026-04-24)**

- `hasRole("ADMIN")` automatically prepends `"ROLE_"` → checks for authority `"ROLE_ADMIN"`
- `hasAuthority("ROLE_ADMIN")` checks the exact string — no auto-prefix
- Both work. `hasRole()` is more readable and the convention
- `hasAnyRole("SELLER", "ADMIN")` — matches if user has ANY of the listed roles
- In the JWT filter, authorities are stored as `"ROLE_CUSTOMER"` → `hasRole("CUSTOMER")` matches

**49. @PreAuthorize SpEL Expressions (2026-04-24)**

- `hasRole('ADMIN')` — single role check
- `hasAnyRole('SELLER', 'ADMIN')` — any of these roles
- `isAuthenticated()` — any logged-in user
- `#userId == authentication.principal` — ownership check (method parameter via `#`)
- `@PreAuthorize` is evaluated BEFORE the method runs; `@PostAuthorize` evaluates AFTER (can check return value)
- SpEL annotations are compiled strings — can't call Java enums/methods inside them without custom evaluation context

**50. Authorization Failure Responses (2026-04-24)**

- No token / invalid token → `AuthenticationEntryPoint` → 401 Unauthorized (or 403 if not customized)
- Valid token but wrong role → `AccessDeniedHandler` → 403 Forbidden
- `@PreAuthorize` failure → throws `AccessDeniedException` → `AccessDeniedHandler` → 403
- URL-based rule failure → same `AccessDeniedException` → 403

**Q61: "What are the two approaches to RBAC in Spring Security and when to use each?" (2026-04-24)**
A: URL-based rules in `SecurityFilterChain` (`requestMatchers().hasRole()`) for coarse-grained path-prefix-level restrictions. Method-level `@PreAuthorize` for fine-grained per-method restrictions, especially ownership checks (`#userId == authentication.principal`). Use both together: URL rules for broad patterns (`/api/admin/**` → ADMIN only), method annotations for specific operations. URL rules are evaluated by `AuthorizationFilter` in the filter chain; `@PreAuthorize` is evaluated by Spring AOP proxies.

**Q62: "What is @EnableMethodSecurity and why was @EnableGlobalMethodSecurity deprecated?" (2026-04-24)**
A: `@EnableMethodSecurity` (Spring Security 5.6+) enables `@PreAuthorize` and `@PostAuthorize` by default. It replaced `@EnableGlobalMethodSecurity(prePostEnabled = true)` which required explicit flag. The new annotation uses `AuthorizationManager`-based infrastructure (consistent with the rest of Spring Security 6.x) instead of the older `AccessDecisionManager` voting system. Simpler API, better defaults.

**Q63: "What's the difference between hasRole() and hasAuthority()?" (2026-04-24)**
A: `hasRole("ADMIN")` auto-prepends `"ROLE_"` and checks for `"ROLE_ADMIN"`. `hasAuthority("ROLE_ADMIN")` checks the exact string. Both access the same `GrantedAuthority` list in the `Authentication` object. `hasRole()` is more readable and the convention. The `"ROLE_"` prefix exists because Spring Security (since 1.0, 2004) distinguishes roles (coarse-grained) from authorities (fine-grained) — the prefix identifies which is which.

**Q64: "Why does requestMatcher ordering matter in SecurityFilterChain?" (2026-04-24)**
A: Spring evaluates matchers top-to-bottom; first match wins. If `anyRequest().authenticated()` appears before `.requestMatchers("/api/admin/**").hasRole("ADMIN")`, the admin rule is never evaluated — every request matches `anyRequest()` first. Rule of thumb: most specific matchers first (exact paths, path+method), then broader patterns, then `anyRequest()` last as the catch-all.

**Q65: "How does @PreAuthorize work internally?" (2026-04-24)**
A: `@EnableMethodSecurity` registers a Spring AOP `MethodInterceptor`. When a bean with `@PreAuthorize` is created, Spring wraps it in a proxy (CGLIB or JDK dynamic proxy). Before each method call, the interceptor evaluates the SpEL expression against the current `SecurityContext`. If it returns false, `AccessDeniedException` is thrown → `AccessDeniedHandler` → 403. The proxy pattern is the same mechanism Spring uses for `@Transactional` and `@Cacheable`.

**51. Spring's @Transactional vs Jakarta's @Transactional (2026-04-24)**

History: Java EE had `@Transactional` since JTA (Java Transaction API, 1999) — originally `javax.transaction.Transactional`, now `jakarta.transaction.Transactional`. Spring created its own in Spring Framework 1.0 (2004) because the JTA version was designed for Java EE application servers (JBoss, WebLogic) and too limited for standalone apps.

| Feature | Spring (`org.springframework.transaction.annotation`) | Jakarta (`jakarta.transaction`) |
|---|---|---|
| `readOnly` | Yes — hints to DB driver | No |
| `propagation` | 7 options (REQUIRED, REQUIRES_NEW, NESTED, etc.) | 3 options |
| `isolation` | Yes (READ_COMMITTED, REPEATABLE_READ, etc.) | No |
| `rollbackFor` | Yes — specify exception classes | Limited |
| `timeout` | Yes | No |
| Works without app server | Yes | Originally needed Java EE container |

Rule: In Spring Boot, always use `org.springframework.transaction.annotation.Transactional`. Jakarta's version works (Spring bridges it) but with fewer features. Watch for IDE auto-import picking the wrong one.

**52. When @Transactional Is Needed — Multi-Write Consistency (2026-04-24)**

`refreshToken()` needs `@Transactional` because:
1. Revoke old token (write)
2. Generate + save new token (write)

If step 2 fails without `@Transactional`, old token is revoked but new token doesn't exist — user is locked out. With `@Transactional`, step 1 rolls back and old token remains valid.

General rule: if a method does multiple related writes where partial completion leaves inconsistent state, it needs `@Transactional`.

**Q66: "Spring's @Transactional vs Jakarta's @Transactional — which to use?" (2026-04-24)**
A: Spring's (`org.springframework.transaction.annotation.Transactional`). It has more features: `readOnly`, `isolation`, 7 propagation options, `timeout`, `rollbackFor`. Jakarta's (`jakarta.transaction.Transactional`) was designed for Java EE app servers and has fewer options. Spring bridges Jakarta's annotation internally, so it works, but with reduced control. The most common mistake is IDE auto-importing the wrong one.

---

## Phase 2: Product Catalog (IN PROGRESS)

### Date: 2026-04-27

---

### Roadblocks & Issues Faced

**1. @PathVariable Name Not Resolved — Missing `-parameters` Compiler Flag (2026-04-27)**

- Problem: All APIs with path variables (e.g., `GET /api/categories/{id}`) threw `IllegalArgumentException: Name for argument of type [long] not specified, and parameter name information not available via reflection`.
- Root cause: Java's `javac` does not preserve method parameter names in bytecode by default — they get erased to `arg0`, `arg1`, etc. So when you write `@PathVariable long id`, Spring can't match `id` to `{id}` in the URL path because the bytecode only has `arg0`.
- Fix: Added `-parameters` compiler flag to `build.gradle`:
  ```groovy
  tasks.withType(JavaCompile).configureEach {
      options.compilerArgs.add('-parameters')
  }
  ```
- Alternative: Explicitly name each `@PathVariable("id") long id` — works without the compiler flag.
- History: Before Java 8 (2014), parameter names were ALWAYS erased. Java 8 introduced `-parameters` as an opt-in flag. Spring Boot 2.x auto-configured this via the Gradle plugin. Spring Boot 3.x stopped auto-configuring it — you must set it yourself. This caught many people upgrading from Boot 2 to Boot 3.
- Why off by default: (1) Increases `.class` file size, (2) Exposes parameter names in decompiled code (security/obfuscation concern), (3) Backward compatibility — changing `javac` default output would break tooling. It's a Java platform decision, not a Spring one.

**2. isActive Specification Missing Null Check — Empty Results (2026-04-28)**

- Problem: `GET /api/products?brandId=1` returned empty results even though products existed for that brand.
- Root cause: The `isActive(Boolean active)` specification did NOT check for null — unlike all other spec methods. When `active` was not passed as a query param, `request.active()` was `null`, producing `cb.equal(root.get("active"), null)` which in SQL becomes `WHERE active = NULL` — always false (SQL uses `IS NULL`, not `= NULL`).
- Fix: Added null check to return `Specification.unrestricted()` when active is null, consistent with all other specification methods.
- Lesson: Every optional filter spec method must handle null. Missing one causes silent empty results — no error, just no data. Particularly tricky with `Boolean` (wrapper) vs `boolean` (primitive) — wrapper defaults to null, primitive defaults to false.

**3. `-parameters` Flag Must Be in Root build.gradle for Multi-Module Projects (2026-04-28)**

- Problem: Added `-parameters` to `app/build.gradle` but `ProductSearchRequest` (in product module) still couldn't bind query params.
- Root cause: Each Gradle module has its own `compileJava` task. The flag in `app/build.gradle` only affects the app module's compilation. The product module compiles with its own `product/build.gradle`.
- Fix: Moved the flag to root `build.gradle` inside the `subprojects` block — applies to all modules.
- Lesson: In multi-module Gradle projects, compiler options in one module don't propagate to others. Project-wide settings go in `subprojects {}` in the root.

---

### Core Concepts Learned

**1. Self-Referential Entity — Category Tree (2026-04-27)**

- A category can have a parent category and many child categories — infinite nesting using a single table.
- Implementation: `@ManyToOne` to itself for `parent` + `@OneToMany(mappedBy = "parent")` for `children`.
- `findByParentIsNull()` returns top-level categories (roots of the tree).
- `findByParentId(Long parentId)` returns immediate children of a category.
- History: The "adjacency list" pattern (storing parentId) is the simplest hierarchical data model, used since the earliest relational databases (1970s). Alternatives for deeper queries: nested sets (Joe Celko, 1996), materialized paths, closure tables. Adjacency list is sufficient when you only need parent/children, not "all descendants."

**2. Soft Delete vs Hard Delete (2026-04-27)**

- Hard delete: `DELETE FROM products WHERE id = 1` — row is gone permanently.
- Soft delete: `UPDATE products SET active = false WHERE id = 1` — row stays, marked inactive.
- Why soft delete for products: (1) Orders reference products — hard delete breaks foreign key integrity, (2) Analytics/reporting needs historical data, (3) Undo/restore is trivial, (4) Audit trail preserved.
- Implementation: `private boolean active = true` on Product entity + `@Builder.Default`. Delete endpoint calls `product.setActive(false)` + save.
- Trade-off: soft-deleted rows accumulate — need periodic archival or filtered queries (`WHERE active = true`).
- History: Soft delete became standard practice in the 2000s as data warehousing and compliance requirements grew. GDPR (2018) complicated this — "right to be deleted" may require actual deletion or anonymization, not just a flag.

**3. Composite Unique Constraints (2026-04-27)**

- `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"brand_id", "ticker_symbol"}))` — the combination must be unique, not each column individually.
- Use case: one brand can have multiple ticker mappings (Apple → AAPL on NASDAQ, Apple → AAPL on LSE), but the same brand+ticker pair shouldn't be duplicated.
- `existsByBrandIdAndTickerSymbol()` in the repository checks for duplicates before insert.

**4. Entity-to-DTO Mapping in Service Layer (2026-04-27)**

- Service methods accept request DTOs and return response DTOs — never expose entities to controllers.
- Why: (1) Entities have JPA proxies, lazy-loading traps, circular references that break serialization, (2) Response shape can differ from entity shape (e.g., `brandName` in ProductResponse instead of entire Brand object), (3) API contract is decoupled from database schema.
- Mapping done manually with static factory methods or constructor calls in the service — no need for MapStruct or ModelMapper at this scale.

**5. @ManyToOne with FetchType.LAZY on Product (2026-04-27)**

- Product has `@ManyToOne(fetch = FetchType.LAZY)` to both Brand and Category.
- Without LAZY: every time you load a Product, Hibernate also loads the full Brand and Category objects (even if you don't need them).
- With LAZY: Brand and Category are loaded as Hibernate proxies — only fetched from DB when you actually call `product.getBrand().getName()`.
- In the service layer, we access `brand.getName()` within the `@Transactional` boundary, so the proxy resolves correctly. Outside the transaction, accessing an unloaded lazy proxy throws `LazyInitializationException`.

**6. BigDecimal for Price Fields (2026-04-27)**

- `private BigDecimal price` with `@Column(precision = 10, scale = 2)` — stores up to 99,999,999.99.
- `@DecimalMin("0.01")` on the DTO ensures positive prices.
- Why not `double`: `0.1 + 0.2 = 0.30000000000000004` in IEEE 754 floating point. Financial systems need exact decimal arithmetic. BigDecimal stores as scaled integers internally.
- Already covered in Phase 1 for WalletAccount — same principle applied to product pricing.

**7. @Builder.Default for Active Flag (2026-04-27)**

- `@Builder.Default private boolean active = true` — ensures products are active by default when created via Lombok builder.
- Without `@Builder.Default`: Lombok's `@Builder` ignores field initializers and sets `active = false` (Java's zero-value for boolean).
- Already learned in Phase 1 — reapplied here for Product entity.

**8. JPA Specification Pattern — Composable Dynamic Queries (2026-04-28)**

- `Specification<T>` is a functional interface (Spring Data JPA) with one method: `Predicate toPredicate(Root<T>, CriteriaQuery<?>, CriteriaBuilder)`.
- Each specification encapsulates ONE filter condition. You compose them: `Specification.allOf(spec1, spec2, spec3)` → AND combination.
- `Root<T>` represents the entity — access fields via `root.get("name")`, navigate relationships via `root.get("brand").get("id")`.
- `CriteriaBuilder` is the factory for predicates: `cb.equal()`, `cb.like()`, `cb.greaterThanOrEqualTo()`, `cb.lower()`.
- History: The Specification pattern comes from Eric Evans' "Domain-Driven Design" (2003, Chapter 9) — business rules as composable, reusable predicate objects. Spring Data JPA married this concept to JPA's Criteria API (JPA 2.0, 2009), which was powerful but verbose. Specifications give DDD composability with Criteria type-safety.
- The lambda `(root, query, cb) -> cb.like(...)` IS the implementation of `toPredicate()` — Java wraps the lambda into a `Specification` object (functional interface behavior).

**9. Specification.allOf() and Specification.unrestricted() (2026-04-28)**

- `Specification.where()` was the original way to start a spec chain — deprecated in Spring Data JPA 3.4+ (marked for removal).
- Replacement: `Specification.allOf(spec1, spec2, ...)` — ANDs all specs together. But unlike `where()`, it does NOT handle null specs.
- `Specification.unrestricted()` (added in same version) — returns a spec whose `toPredicate()` returns null, meaning "no condition." Use it instead of returning `null` from spec methods.
- Pattern: each spec method returns `Specification.unrestricted()` when the filter param is null, otherwise returns the actual predicate.

**10. JpaSpecificationExecutor — Interface Multiple Inheritance (2026-04-28)**

- `ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product>` — an interface extending two interfaces.
- Java allows multiple interface inheritance (no state conflicts). Class extending multiple classes is forbidden (diamond problem, avoided since Java 1.0).
- Java 8 default methods reintroduced a mild diamond problem — if two interfaces have the same default method, the implementing class must override to resolve. Compile-time enforced.
- `JpaSpecificationExecutor` adds `findAll(Specification<T>, Pageable)` — combine dynamic filters with pagination in a single query.

**11. Spring Data Pagination — Page<T>, Pageable, PagedResponse (2026-04-28)**

- `Pageable` — Spring Data abstraction for pagination params (page number, size, sort). Auto-resolved from query params (`?page=0&size=10&sort=name,asc`) by `PageableHandlerMethodArgumentResolver` (auto-configured since Spring Data 1.6, 2013).
- `Page<T>` — result container with content + metadata (totalElements, totalPages, isLast). Runs an extra COUNT query.
- `Slice<T>` — lighter alternative, only knows if there's a next page (fetches size+1 rows). Use for infinite scroll.
- `PagedResponse<T>` — custom generic DTO wrapping Page metadata for clean API responses. Avoids leaking Spring Data internals to clients.

**12. `-parameters` Flag Scope in Multi-Module Gradle (2026-04-28)**

- Each Gradle module has its own `compileJava` task. Adding `-parameters` to `app/build.gradle` only affects the app module.
- For project-wide effect, add to root `build.gradle` in the `subprojects` block — applies to all modules.
- Needed for: `@PathVariable` name resolution, `@ModelAttribute` record binding, `Pageable` resolution from query params.

**13. Lambdas and Functional Interfaces — Deferred Execution (2026-04-28)**

- A functional interface has one abstract method. A lambda IS the implementation of that method — Java wraps it into an object of the interface type.
- `hasName("iphone")` returns a `Specification<Product>` object (now). `toPredicate()` runs later (when Spring builds the SQL).
- Two returns: the method returns the Specification object, the lambda (inside) returns the Predicate. Different levels, different times.
- Same pattern everywhere: `Runnable` (lambda returns void, method returns Runnable), `Comparator` (lambda returns int, method returns Comparator).

---

### Interview Questions Discussed

**Q67: "What is a self-referential entity and when would you use it?" (2026-04-27)**
A: A self-referential entity has a foreign key pointing to its own table — `@ManyToOne` to itself for the parent, `@OneToMany(mappedBy)` for children. Used for hierarchical data: categories, organizational charts, file systems, comment threads. The "adjacency list" pattern (storing parentId) is the simplest model — works well for parent/children queries. For deep tree queries ("all descendants"), consider alternatives: materialized paths (store full path as string), nested sets (store left/right boundaries), or closure tables (store all ancestor-descendant pairs).

**Q68: "Soft delete vs hard delete — when to use each?" (2026-04-27)**
A: Soft delete (flag column like `active = false`) when: data is referenced by other tables (orders → products), audit/compliance requires history, business needs undo capability. Hard delete when: data has no references, storage is a concern, or regulations require actual deletion (GDPR "right to erasure"). Soft delete trade-offs: queries must filter by active flag (easy to forget), data accumulates (needs archival strategy), indexes should include the flag for performance.

**Q69: "Why should services return DTOs instead of entities?" (2026-04-27)**
A: (1) JPA entities have Hibernate proxies that can throw `LazyInitializationException` outside transactions, (2) Circular references (`product → brand → products`) cause infinite JSON serialization, (3) Internal fields (passwords, audit columns) leak to clients, (4) API shape can differ from DB schema — `ProductResponse` includes `brandName` string instead of the whole Brand object. DTOs create a clean contract boundary between your API and your database model.

**Q70: "Why does Spring Boot 3 require the `-parameters` compiler flag for @PathVariable?" (2026-04-27)**
A: Java erases method parameter names during compilation by default — `long id` becomes `arg0` in bytecode. The `-parameters` flag (Java 8+) preserves them. Spring Boot 2 auto-configured this via the Gradle/Maven plugin. Spring Boot 3 removed that auto-configuration for build transparency. Without the flag, `@PathVariable long id` can't match to `{id}` in the URL — Spring doesn't know the parameter is called `id`. Fix: add `-parameters` to compiler args, or explicitly name it: `@PathVariable("id") long id`.

**Q71: "What is a composite unique constraint and how does it differ from individual unique columns?" (2026-04-27)**
A: `@UniqueConstraint(columnNames = {"brand_id", "ticker_symbol"})` means the COMBINATION must be unique — brand_id=1 + ticker_symbol=AAPL can only appear once. Individual unique columns (`@Column(unique = true)` on each) means each column must be unique independently — ticker_symbol=AAPL could only appear once across ALL brands. Composite constraints are for "this pair/tuple must be unique" scenarios: user+role, brand+ticker, student+course.

**Q72: "What happens if you access a LAZY-loaded field outside a transaction?" (2026-04-27)**
A: `LazyInitializationException`. Hibernate creates a proxy object for LAZY fields. When you access the proxy (e.g., `product.getBrand().getName()`), Hibernate issues a SQL query — but it needs an active database session (tied to the `@Transactional` boundary). Outside the transaction, the session is closed. Solutions: (1) Access within the transaction and map to a DTO (best), (2) `JOIN FETCH` in JPQL to eagerly load specific associations, (3) `@EntityGraph` to override fetch strategy per query. Never use `FetchType.EAGER` as a blanket fix — it loads data you don't need on every query.

**Q73: "What is the JPA Specification pattern and how does it differ from derived queries?" (2026-04-28)**
A: Derived queries (`findByBrandId`) are static — one method per filter combination. If you have 6 optional filters, you'd need 64 method combinations (2^6). Specifications encapsulate each filter as a composable object: `hasName("iphone").and(hasBrandId(1))`. You compose at runtime based on which params the user provides. Specifications use JPA's Criteria API under the hood but wrap it in a clean, reusable API. Use derived queries for simple, fixed queries (80% of cases). Use Specifications when filters are dynamic and combinable.

**Q74: "What are Root, CriteriaBuilder, and CriteriaQuery in JPA Criteria?" (2026-04-28)**
A: `Root<T>` represents the FROM entity — you access fields via `root.get("name")` and navigate relationships via `root.get("brand").get("id")` (Hibernate is smart enough to use the FK directly without a JOIN). `CriteriaBuilder` is the factory for building WHERE conditions: `cb.equal()`, `cb.like()`, `cb.greaterThanOrEqualTo()`. `CriteriaQuery` represents the overall query (SELECT, GROUP BY, ORDER BY). Together they form JPA 2.0's type-safe query API — an alternative to string-based JPQL.

**Q75: "Page vs Slice — when to use each?" (2026-04-28)**
A: `Page<T>` runs two queries: one for data (with LIMIT/OFFSET) and one for COUNT (total rows). Use it when you need total page count — pagination UI with numbered pages. `Slice<T>` fetches `size + 1` rows — if it gets the extra row, there's a next page. No COUNT query, so it's faster. Use for infinite scroll or "Load More" buttons where total count doesn't matter. For large tables (millions of rows), COUNT can be expensive — `Slice` avoids that cost.

**Q76: "How does Spring auto-resolve Pageable from query parameters?" (2026-04-28)**
A: Spring Boot auto-configures `PageableHandlerMethodArgumentResolver` (via `SpringDataWebAutoConfiguration`). When a controller method has a `Pageable` parameter, the resolver reads `page` (0-based, default 0), `size` (default 20), and `sort` (e.g., `sort=price,desc`) from query params and constructs a `PageRequest`. Multiple sort params are supported: `sort=name,asc&sort=price,desc`. No manual parsing needed — just declare `Pageable` as a parameter.

**Q77: "Why use a generic PagedResponse<T> instead of returning Page<T> directly?" (2026-04-28)**
A: `Page<T>` is a Spring Data interface with internal implementation details — `Pageable` references, `Sort` objects, serialization noise. Returning it directly couples your API to Spring Data's structure. A custom `PagedResponse<T>` gives you a clean, stable contract: just `content`, `page`, `size`, `totalElements`, `totalPages`, `last`. If you switch from Spring Data to another library, the API doesn't change. It's the same DTO-over-entity principle applied to pagination metadata.

**Q78: "What is Specification.unrestricted() and why was Specification.where() deprecated?" (2026-04-28)**
A: `where(spec)` was the traditional starting point for spec chains — it handled null by treating it as "no condition." Deprecated in Spring Data JPA 3.4+ in favor of `allOf(spec1, spec2, ...)` which ANDs multiple specs at once. But `allOf` doesn't handle null specs — so `Specification.unrestricted()` was added as the explicit "no condition" replacement. It returns a spec whose `toPredicate()` returns null, which JPA Criteria interprets as "match everything." Pattern: return `unrestricted()` when filter param is null, return the actual predicate otherwise.

---

### Spring Batch — Concepts Learned (2026-04-29)

**14. Spring Batch Architecture — Job/Step/Reader/Processor/Writer (2026-04-29)**

- History: Spring Batch first released 2007 (SpringSource + Accenture). Before this, Java batch processing was ad-hoc — custom loops, file parsers, manual error handling. IBM had batch frameworks in mainframe COBOL (since 1960s), but Java had no standard. JSR 352 (Java Batch, 2013) standardized it for Java EE, but Spring Batch predated it by 6 years and became the de-facto standard. Spring Batch actually influenced JSR 352's design.
- Architecture: `Job` → `Step` → (`ItemReader` + `ItemProcessor` + `ItemWriter`)
- **Job**: the project manager — decides which steps to run, manages overall state (STARTED/COMPLETED/FAILED), handles restartability, provides identity (job name + params = unique key).
- **Step**: the team lead — opens transactions per chunk, runs the read-process-write loop, tracks metrics (readCount, writeCount, filterCount), handles rollback on failure.
- **Reader**: reads one item at a time from a source (CSV, DB, API).
- **Processor**: transforms/validates one item at a time. Returning null skips the item.
- **Writer**: writes a chunk of items at once (batch insert for performance).

**15. Chunk-Oriented Processing (2026-04-29)**

- The execution order per chunk: read 1 + process 1, read 1 + process 1, ... N times, then write all N at once.
- It is NOT "read N, then process N" — reader and processor alternate per item. The writer receives the whole chunk.
- Why: memory efficiency — the raw DTO can be garbage collected as soon as the entity is created. Database writes benefit from batching (one transaction for N inserts).
- Each chunk = one transaction. If item 47 fails in the writer, all 50 roll back. Previously committed chunks are safe.
- Chunk size is configurable: `chunk(50, transactionManager)`. Trade-off: larger chunks = fewer transactions = faster, but more memory and bigger rollback scope.

**16. @StepScope — Deferred Bean Creation (2026-04-29)**

- Normal `@Bean` methods run at Spring context startup. But `FlatFileItemReader` needs `filePath` from `JobParameters` — which don't exist until the job is actually launched.
- `@StepScope` tells Spring: "Don't create this bean now. Create a proxy. Create the real bean when the step actually executes."
- At that point, `@Value("#{jobParameters['filePath']}")` resolves to the actual file path passed by the controller.
- Without `@StepScope`: Spring tries to resolve `jobParameters` at startup → null → fails.
- Similar concept to `@RequestScope` in web apps (create per HTTP request) — `@StepScope` creates per step execution.

**17. FlatFileItemReader — CSV Reading (2026-04-29)**

- Reads a file line-by-line. Each line becomes one DTO object.
- `FlatFileItemReaderBuilder` (Spring Batch 4.0, 2017) wraps verbose setup: `DelimitedLineTokenizer` (splits by comma) + `BeanWrapperFieldSetMapper` (creates object via setters).
- `linesToSkip(1)` — skips the CSV header row.
- `.names(...)` — maps CSV column positions to DTO field names. Position 0 → field "name", position 1 → field "description", etc.
- `.targetType(ProductCsvRow.class)` — the mapper creates `new ProductCsvRow()` and calls `setName()`, `setPrice()`, etc.
- Why the DTO must be a mutable class (not record): `BeanWrapperFieldSetMapper` uses no-arg constructor + setters. Records have neither.

**18. RepositoryItemWriter — JPA-Based Writing (2026-04-29)**

- Calls `repository.save()` for each item in the chunk.
- Configured with the repository bean and method name (`"save"`).
- The entire chunk is written in one transaction — managed by the Step.
- Alternative: `JdbcBatchItemWriter` for raw JDBC batch inserts (faster, bypasses JPA).

**19. Spring Batch Metadata Tables (2026-04-29)**

- Spring Batch tracks every job execution in DB tables: `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, etc.
- Provides: restartability (resume failed jobs), idempotency (prevent re-running completed jobs with same params), audit trail (when, how many rows, what failed).
- `spring.batch.jdbc.initialize-schema: always` — auto-creates these tables on startup.
- `spring.batch.job.enabled: false` — prevents auto-running all Job beans on startup. Jobs only run when explicitly launched via `JobLauncher`.

**20. MultipartFile — HTTP File Upload (2026-04-29)**

- `@RequestParam("file") MultipartFile file` — Spring handles multipart form data parsing automatically.
- `MultipartFile` is Spring's abstraction over the uploaded file — provides bytes, original filename, content type.
- `file.transferTo(tempFile)` — writes uploaded bytes to disk. Needed because `FlatFileItemReader` requires a file path, not a byte stream.
- `File.createTempFile("products-", ".csv")` — creates a uniquely named temp file to avoid collisions.
- History: `MultipartFile` since Spring 1.0 (2004). Servlet 3.0 (2009) added native `Part` support. Spring wraps it for a cleaner API.

**21. JobParameters — Making Each Job Execution Unique (2026-04-29)**

- Spring Batch uses job name + parameters hash as a unique key. Same job + same params = "already executed" → refused.
- Adding `addLong("startTime", System.currentTimeMillis())` makes each execution unique even with the same CSV.
- Parameters are stored in `BATCH_JOB_EXECUTION_PARAMS` table — available for audit and restart.
- `@Value("#{jobParameters['filePath']}")` in `@StepScope` beans resolves parameters at step execution time (SpEL expression).

---

### Interview Questions Discussed (2026-04-29)

**Q79: "What is Spring Batch and when would you use it instead of a REST API?" (2026-04-29)**
A: Spring Batch is a framework for processing large volumes of data without user interaction — nightly reports, data migrations, bulk imports, ETL pipelines. Use a REST API for real-time, single-item operations (create one product). Use Spring Batch when you need to process thousands/millions of items with transaction management, error handling, skip/retry logic, and execution tracking. Spring Batch provides all this out of the box; building it manually is error-prone.

**Q80: "Explain the chunk-oriented processing model in Spring Batch." (2026-04-29)**
A: Items are processed in chunks. Within a chunk: read one + process one (alternating, one at a time), then write the entire chunk at once. Each chunk is one transaction — if the writer fails, the chunk rolls back but previously committed chunks are safe. Chunk size is configurable: larger = fewer transactions (faster) but more memory and bigger rollback scope. This model is more efficient than item-by-item (too many transactions) or all-at-once (too much memory, one failure loses everything).

**Q81: "What is @StepScope and why is it needed?" (2026-04-29)**
A: `@StepScope` defers bean creation from application startup to step execution time. Needed when a bean depends on runtime values like `JobParameters` — which don't exist at startup. Spring creates a proxy at startup and the real bean when the step runs. Without it, `@Value("#{jobParameters['filePath']}")` would resolve to null at startup and fail. Similar to `@RequestScope` (per HTTP request) — `@StepScope` creates a new instance per step execution.

**Q82: "Why does FlatFileItemReader need a mutable class (not a record) for mapping?" (2026-04-29)**
A: `BeanWrapperFieldSetMapper` (the default mapper) works by: (1) creating an empty object via no-arg constructor, (2) calling setters for each CSV column. Records have neither — no no-arg constructor, no setters, all fields are final. You'd need a custom `FieldSetMapper` implementation to use records. For Spring Batch DTOs, use a regular class with `@Data` + `@NoArgsConstructor`.

**Q83: "How does Spring Batch prevent duplicate job executions?" (2026-04-29)**
A: Spring Batch uses job name + parameters hash as a unique key stored in `BATCH_JOB_INSTANCE`. If you try to run the same job with identical parameters, it checks the table and refuses ("JobInstanceAlreadyCompleteException"). To allow re-runs, add a varying parameter like `addLong("startTime", System.currentTimeMillis())`. This is also how restartability works — a FAILED job with the same params can be restarted to resume from where it left off.

**Q84: "What are Spring Batch metadata tables and why are they required?" (2026-04-29)**
A: Tables like `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` track every job run — status, start/end time, read/write/skip counts, parameters, exit messages. They enable: restartability (resume from last committed chunk), idempotency (prevent re-running completed jobs), and audit trail (compliance, debugging). Spring Batch won't start without them — configure `spring.batch.jdbc.initialize-schema: always` to auto-create, or manage via Flyway/Liquibase in production.

---

### Redis Caching — Concepts Learned (2026-04-30)

**22. Cache-Aside Pattern (2026-04-30)**

- The most common caching pattern: application checks cache first, on miss queries DB, then stores result in cache for future reads.
- Flow: Client → Service → Check Redis → HIT? return cached → MISS? query DB → store in Redis → return.
- Also called "lazy-loading" — cache is populated on demand, not pre-warmed.
- History: The cache-aside pattern predates Redis — used with Memcached (Brad Fitzpatrick, 2003, LiveJournal), local HashMaps, and even CPU L1/L2 caches (same principle since 1960s). Redis (Salvatore Sanfilippo, 2009) became the dominant choice because it's in-memory (microsecond reads), supports TTL natively, works across multiple app instances (unlike per-JVM HashMaps), and has rich data structures.

**23. Spring Cache Abstraction — @Cacheable, @CacheEvict, @CachePut (2026-04-30)**

- Spring 3.1 (2011) introduced declarative caching via annotations — no need to write cache-get/cache-put logic manually.
- `@Cacheable(value, key)`: before method executes, check cache. HIT → return cached, skip method. MISS → run method, store result, return.
- `@CacheEvict(value, key/allEntries)`: after method executes, remove entries from cache. Used on write operations to invalidate stale data.
- `@CachePut(value, key)`: always runs the method AND stores result in cache. Used when you want to update the cache with fresh data (e.g., after an update).
- `@Caching(evict={...}, put={...})`: combine multiple cache operations on one method.
- Spring creates a proxy around the service class — cache logic lives in the proxy, not your code (AOP-based). Important: internal method calls (this.method()) bypass the proxy and skip caching.

**24. Redis Serialization — GenericJackson2JsonRedisSerializer (2026-04-30)**

- Redis stores bytes — Java objects must be serialized before storing and deserialized when reading.
- Three options: (1) `JdkSerializationRedisSerializer` (default) — uses `java.io.Serializable`, produces unreadable bytes, breaks on class changes. (2) `Jackson2JsonRedisSerializer` — needs explicit type per cache, poor generics support. (3) `GenericJackson2JsonRedisSerializer` — stores `@class` type info inside JSON, handles generics like `PagedResponse<ProductResponse>`, human-readable in redis-cli.
- JSON serialization is preferred: debuggable, language-agnostic, no Java class version coupling.
- Trade-off: slightly larger storage than binary, but worth it for observability.

**25. Cache Invalidation Strategy (2026-04-30)**

- "There are only two hard things in Computer Science: cache invalidation and naming things." — Phil Karlton (Netscape, 1996).
- For list/search caches (`products`): must evict `allEntries = true` on any write — impossible to know which search results a new/updated product affects.
- For single-item caches (`product`): can evict by key on delete, or update via `@CachePut` on update.
- TTL as safety net: even if eviction logic has a bug, entries expire after 10 minutes — bounded staleness.
- Never cache data that must be real-time (e.g., stock prices, inventory during flash sales).

**26. TTL (Time-To-Live) — Bounded Staleness (2026-04-30)**

- `entryTtl(Duration.ofMinutes(10))` — Redis auto-deletes the key after 10 minutes.
- Why 10 minutes for product catalog: products change infrequently (admin adds/updates maybe a few times per day). 10 minutes of staleness is acceptable — users won't notice price changing 10 minutes late.
- Without TTL: stale data lives forever if eviction logic misses an edge case. TTL guarantees eventual freshness.
- Too short TTL: defeats the purpose of caching (too many cache misses). Too long: stale data shown to users.
- Redis uses a combination of lazy expiry (check on access) and periodic sampling (background thread deletes 20 random expired keys every 100ms) — efficient even with millions of keys.

---

### Interview Questions Discussed (2026-04-30)

**Q85: "What is the cache-aside pattern and how does it work?" (2026-04-30)**
A: In cache-aside, the application (not the cache) manages data flow. On read: check cache first → HIT means return cached data, MISS means query DB → store in cache → return. On write: update DB → evict/invalidate cache. The cache is populated lazily (on demand). Advantages: simple, application controls freshness, cache failure doesn't break the app (fallback to DB). Alternatives: write-through (write to cache and DB simultaneously), write-behind (write to cache, async flush to DB), read-through (cache itself fetches from DB on miss — used in Hibernate L2 cache).

**Q86: "Explain @Cacheable, @CacheEvict, and @CachePut. When would you use each?" (2026-04-30)**
A: `@Cacheable` — on read methods. Checks cache before running method; on HIT returns cached value without executing the method body. `@CacheEvict` — on write methods. Removes stale cache entries after the method runs (or before, with `beforeInvocation=true`). Use `allEntries=true` for list caches where you can't predict which keys are affected. `@CachePut` — always runs the method AND updates the cache with the new return value. Use on update methods where you know the exact key and want to refresh it without a separate read. Important: `@CachePut` requires the method to return the cacheable value (can't use on void methods).

**Q87: "Why use GenericJackson2JsonRedisSerializer instead of the default JDK serializer?" (2026-04-30)**
A: JDK serialization: (1) requires `Serializable` interface on all cached objects (including nested), (2) produces opaque binary — unreadable in redis-cli for debugging, (3) breaks when you rename/move classes (class name is baked into bytes), (4) vulnerable to deserialization attacks. JSON serializer: (1) no interface needed — uses Jackson reflection, (2) human-readable in Redis, (3) tolerant of class changes (field additions don't break existing cache), (4) `GenericJackson2JsonRedisSerializer` stores `@class` metadata so it knows which type to deserialize to — handles generics and polymorphism.

**Q88: "How do you handle cache invalidation for paginated/filtered list queries?" (2026-04-30)**
A: You can't selectively invalidate individual list entries because you don't know which pages/filters a new item affects. Solution: `@CacheEvict(value = "products", allEntries = true)` on any write operation — nuke the entire list cache. This is acceptable because: (1) writes are infrequent compared to reads, (2) TTL bounds staleness anyway, (3) the alternative (tracking which queries an item appears in) is prohibitively complex. For single-item caches (by ID), you CAN evict/update the specific key.

**Q89: "What happens if Redis goes down? Does the application crash?" (2026-04-30)**
A: With Spring's default configuration, if Redis is unreachable, `@Cacheable` throws a connection exception — which bubbles up as a 500 error. To make it resilient: configure `CacheErrorHandler` (Spring interface) to log and swallow cache exceptions, falling back to the DB. The application degrades gracefully — slower (every request hits DB) but functional. In production: Redis Sentinel or Redis Cluster for high availability, plus a custom `CacheErrorHandler` as a safety net.

---

### How Spring "Manages" Cross-Cutting Concerns — Internals (2026-04-30)

**27. Spring's Three Interception Mechanisms — Filters, Proxies, Framework Loops (2026-04-30)**

- Spring adds behavior around your code without you manually writing plumbing. But it uses three different mechanisms depending on the layer:
- **Servlet Filter Chain** (Spring Security): Tomcat receives HTTP request → passes through an ordered list of Filter objects → only if all filters pass does the request reach your Controller. Your `JwtAuthFilter` is one filter in this chain. Filters are a Servlet API concept (since Servlet 2.3, year 2001) — Spring Security just builds a sophisticated chain on top.
- **CGLIB Proxy** (Cache, @Transactional, @Async): Spring generates a subclass of your service at runtime. When someone calls `productService.searchProduct()`, they're calling the proxy (subclass), not the real object. The proxy's overridden method contains interceptor logic (check cache, open transaction, etc.) and only calls `super.searchProduct()` (the real method) when needed.
- **Framework Loop** (Spring Batch): No proxy, no filter. Instead, YOUR code (reader, processor, writer) is plugged into Spring Batch's internal loop. The framework calls your components at the right time — you don't control the flow. This is the Hollywood Principle: "Don't call us, we'll call you."
- History: Filters = chain of responsibility pattern (GoF, 1994; Servlet 2.3, 2001). Proxies = AOP (Aspect-Oriented Programming — Gregor Kiczales at Xerox PARC, 1997; AspectJ 2001; Spring AOP since Spring 1.0, 2004). Framework loops = Template Method pattern (GoF, 1994; Spring Batch since 2007).

**28. CGLIB Proxy — How Spring Creates It (2026-04-30)**

- During component scan, Spring finds `ProductServiceImpl` with `@Service`
- Spring creates the real object via constructor injection
- A `BeanPostProcessor` (specifically `AbstractAutoProxyCreator`) inspects the class: "Does any method have @Cacheable, @Transactional, or @Async?"
- If YES → Spring generates a CGLIB subclass dynamically at runtime (using bytecode manipulation). This subclass overrides your methods with interceptor logic.
- Spring registers this PROXY in the ApplicationContext, not the real object. When Controller gets injected with `ProductService`, it receives the proxy.
- The proxy holds a chain of `MethodInterceptor` objects (e.g., `CacheInterceptor`, `TransactionInterceptor`). Each decides whether to proceed or short-circuit.
- CGLIB = Code Generation Library (originally by Eric Bruneton, 2002). Creates subclasses without needing interfaces. Alternative: JDK Dynamic Proxy (Java 1.3, 2000) — only works with interfaces, not concrete classes.

**29. Why `this.method()` Bypasses Cache/@Transactional (2026-04-30)**

- When you call `this.searchProduct()` from within `createProduct()`, `this` refers to the real object (you're already inside the proxy at that point).
- The proxy can only intercept calls that come from OUTSIDE — through the injected reference.
- Workaround (if needed): inject `self` reference (`@Lazy private ProductService self;`) and call `self.searchProduct()` — this goes through the proxy. But it's generally a code smell — prefer restructuring.
- This is NOT a bug — it's a fundamental limitation of proxy-based AOP. AspectJ (compile-time weaving) doesn't have this limitation, but it's much more complex to set up.

**30. Filter Chain vs Proxy vs Framework Loop — Comparison (2026-04-30)**

| Mechanism | Spring's Trick | Your Code's Role | When it fires | Self-call problem? |
|-----------|---------------|-----------------|---------------|-------------------|
| Servlet Filter Chain | Ordered filter list in servlet context | Filter in the chain (JwtAuthFilter) | Every HTTP request, before Controller | NO — filters run before any method |
| CGLIB Proxy | Runtime subclass with interceptors | The method being proxied (@Cacheable/@Transactional) | Every external method call on the bean | YES — `this` bypasses proxy |
| Framework Loop | Batch framework calls your beans | Components plugged into the loop (Reader/Processor/Writer) | When JobLauncher.run() is called | NO — framework calls you, not the reverse |

**31. @EnableCaching Internal Flow (2026-04-30)**

- `@EnableCaching` imports `CachingConfigurationSelector` → registers `ProxyCachingConfiguration`
- `ProxyCachingConfiguration` creates a `CacheInterceptor` bean + `BeanFactoryCacheOperationSourceAdvisor`
- The advisor tells Spring's `AbstractAutoProxyCreator`: "Any bean with @Cacheable methods needs a proxy"
- At runtime, `CacheInterceptor.invoke()` is called before/after the target method:
  1. Evaluate SpEL key expression
  2. Look up `CacheManager` → get/create `Cache` by name
  3. Call `cache.get(key)` — HIT or MISS
  4. On MISS: invoke target method, then `cache.put(key, result)`
  5. On HIT: return cached value, skip target method entirely
- Same pattern for `@EnableTransactionManagement` (TransactionInterceptor) and `@EnableAsync` (AsyncExecutionInterceptor) — different interceptors, same proxy mechanism.

---

### Interview Questions — Spring Internals (2026-04-30)

**Q90: "How does Spring implement @Cacheable/@Transactional behind the scenes?" (2026-04-30)**
A: Spring uses AOP proxies. At bean creation time, a `BeanPostProcessor` inspects each class for cache/transaction annotations. If found, it wraps the real bean in a CGLIB-generated subclass (proxy). The proxy overrides annotated methods with interceptor logic: `CacheInterceptor` for caching, `TransactionInterceptor` for transactions. External callers get the proxy (injected by Spring), so every call goes through the interceptor. The interceptor decides whether to proceed to the real method or short-circuit (e.g., return cached value). This is why self-calls (`this.method()`) bypass these annotations — `this` is the real object inside the proxy.

**Q91: "Why does calling `this.method()` bypass @Cacheable and @Transactional?" (2026-04-30)**
A: Spring's AOP works via proxies — a dynamically generated subclass that overrides your methods. When an external bean calls `productService.searchProduct()`, it's calling the proxy. But inside `ProductServiceImpl`, `this` refers to the real object (not the proxy), so `this.searchProduct()` calls the method directly without going through the interceptor chain. Solutions: (1) restructure code so cached methods are always called externally, (2) inject self-reference (`@Lazy private ProductService self; self.method()`), (3) use AspectJ compile-time weaving (no proxy limitation, but complex setup). Most projects choose option 1.

**Q92: "Compare the three ways Spring adds behavior to your code: Filters, Proxies, and Framework Loops." (2026-04-30)**
A: **Filters** (Spring Security): Servlet API mechanism — an ordered chain where each filter inspects/modifies the HTTP request/response. Your code IS a filter in the chain. Runs on every request before the Controller. **Proxies** (Cache, Transactions): AOP mechanism — Spring generates a subclass at runtime that intercepts method calls. Your code is the target being proxied. Only works on external calls (self-calls bypass). **Framework Loops** (Spring Batch): Template Method pattern — your code (reader/processor/writer) is plugged into the framework's execution loop. The framework calls you at the right time. No interception needed — it's plain method invocation controlled by the framework. Each mechanism fits a different layer: HTTP layer (filters), bean layer (proxies), batch processing layer (framework loops).

**Q93: "What is CGLIB and how does it differ from JDK Dynamic Proxy?" (2026-04-30)**
A: Both create proxy objects at runtime. **JDK Dynamic Proxy** (Java 1.3, 2000): requires the target to implement an interface. Creates a `Proxy` class implementing the same interfaces. Uses `InvocationHandler` for interception. Cannot proxy concrete classes without interfaces. **CGLIB** (2002): uses bytecode generation (ASM library) to create a subclass of the concrete class. Doesn't need interfaces. Overrides methods to add interceptor logic. Spring uses CGLIB by default since Spring Boot 2.0 (even if interfaces exist) because it's simpler — no need to inject by interface type. Limitation: can't proxy `final` classes or `final` methods (can't subclass/override them).

---

### @Transactional and @Async — Proxy Interceptor Deep Dive (2026-05-01)

**32. @Transactional — What the Proxy Actually Does (2026-05-01)**

- The method ALWAYS executes (unlike @Cacheable which can skip it). The proxy's job is to wrap the method in a database transaction.
- Without @Transactional, each `repository.save()` gets its own connection from the pool, auto-commits immediately, and returns the connection. Three saves = three independent transactions. If the third fails, the first two are already committed — no atomicity.
- With @Transactional, the proxy does:
  1. **BEFORE method**: Get `PlatformTransactionManager` → call `txManager.getTransaction(definition)` → opens a DB connection from HikariCP pool → calls `connection.setAutoCommit(false)` → binds the connection to the current thread via `TransactionSynchronizationManager` (ThreadLocal).
  2. **Method runs**: all repository calls within the method share the SAME connection (because they ask `DataSourceUtils.getConnection()` which checks the ThreadLocal first).
  3. **AFTER method (success)**: `txManager.commit()` → `connection.commit()` → return connection to pool.
  4. **AFTER method (exception)**: `txManager.rollback()` → `connection.rollback()` → return connection to pool → re-throw exception.
- The invisible glue: `TransactionSynchronizationManager` stores the active connection in a `ThreadLocal<Map<DataSource, ConnectionHolder>>`. When JPA/Hibernate needs a connection, `DataSourceUtils.getConnection(dataSource)` checks this ThreadLocal: bound connection exists → use it. Not bound → get new connection, auto-commit = true.
- History: Manual transaction management (try/begin/commit/catch/rollback/finally/close) was the standard in J2EE (1999). EJB had Container-Managed Transactions (CMT) but was heavyweight. Spring 1.0 (2004) introduced `@Transactional` — same declarative simplicity as EJB CMT but without the EJB container. It was one of Spring's killer features that drove adoption over J2EE.

**33. @Async — What the Proxy Actually Does (2026-05-01)**

- The method ALWAYS executes, but on a DIFFERENT thread. The proxy's job is to offload execution to a thread pool.
- The proxy does:
  1. Get the `TaskExecutor` (thread pool) bean.
  2. Wrap the real method call in a `Runnable` (void return) or `Callable` (returns `Future<T>`).
  3. Submit to thread pool → **return immediately** to the caller. The caller's thread is freed.
  4. The real method executes later on a worker thread from the pool.
- The caller gets back `null` (void), `Future<T>`, or `CompletableFuture<T>` depending on the return type.
- Important: @Async runs on a different thread, which means `SecurityContextHolder` (ThreadLocal) and `TransactionSynchronizationManager` (ThreadLocal) are NOT carried over. The async method has no SecurityContext and no active transaction from the caller.
- History: Before @Async (Spring 3.0, 2009), offloading work to another thread required manual `ExecutorService` management. @Async made it declarative — just annotate and Spring handles thread pool submission. Java's `CompletableFuture` (Java 8, 2014) later improved the return type story.

**34. Same Proxy, Different Interceptor Behavior — Comparison (2026-05-01)**

| Annotation | Does method run? | Proxy BEFORE | Proxy AFTER | ThreadLocal concerns |
|-----------|-----------------|-------------|------------|---------------------|
| `@Cacheable` | Only on MISS | Check Redis for key | Store result in Redis | N/A — same thread |
| `@Transactional` | ALWAYS | Open transaction, bind connection to ThreadLocal | Commit or rollback | Connection shared via ThreadLocal |
| `@Async` | ALWAYS (different thread) | Submit to thread pool, return immediately | Nothing — already returned | SecurityContext and Transaction NOT carried over |

**35. ThreadLocal Connection Binding — Why Repository Calls "Just Work" (2026-05-01)**

- When `@Transactional` opens a connection, it stores it in `TransactionSynchronizationManager` (a ThreadLocal).
- When `productRepository.save(product)` is called, it internally calls `EntityManager.persist()` → which needs a DB connection → asks `DataSourceUtils.getConnection(dataSource)` → checks ThreadLocal: "Is there a connection bound to this thread?" → YES (the proxy put it there!) → uses it.
- This is why multiple repository calls in one `@Transactional` method share ONE connection and ONE transaction without you passing a connection object around.
- Without `@Transactional`: each `DataSourceUtils.getConnection()` call gets a new connection from the pool with `autoCommit = true`. Each save commits independently.

---

### Interview Questions — @Transactional and @Async Internals (2026-05-01)

**Q94: "What exactly does @Transactional do behind the scenes? Why can't you just call repository.save() without it?" (2026-05-01)**
A: `@Transactional` wraps your method in a database transaction via a CGLIB proxy. The `TransactionInterceptor` opens a connection, sets `autoCommit(false)`, binds it to the current thread via `TransactionSynchronizationManager` (ThreadLocal), runs your method, then commits or rolls back. Without it, each `repository.save()` auto-commits independently — if you have 3 writes and the 3rd fails, the first 2 are already committed (no atomicity). `@Transactional` ensures all-or-nothing: either all writes commit, or all roll back.

**Q95: "How do multiple repository calls share the same transaction within a @Transactional method?" (2026-05-01)**
A: Via ThreadLocal connection binding. The `TransactionInterceptor` stores the active DB connection in `TransactionSynchronizationManager` (a ThreadLocal). When any repository call needs a connection, `DataSourceUtils.getConnection()` checks this ThreadLocal first. If a connection is bound to the current thread, it reuses it. All repo calls on the same thread share the same connection and transaction. This is why you don't need to pass a `Connection` object around — the ThreadLocal acts as an invisible parameter.

**Q96: "What happens to @Transactional and SecurityContext when you use @Async?" (2026-05-01)**
A: Both are lost. `@Transactional` binds the DB connection to the caller's thread via ThreadLocal. `SecurityContextHolder` stores the authenticated user on the caller's thread. `@Async` runs the method on a DIFFERENT thread from the pool — that thread has empty ThreadLocals. So the async method has no active transaction (any DB calls will auto-commit independently) and no security context (attempting to read the authenticated user returns null). Solutions: (1) start a new `@Transactional` inside the async method, (2) pass security info as method parameters, (3) configure `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` — but this is fragile with thread pools.

**Q97: "Compare @Transactional, @Cacheable, and @Async — same proxy mechanism, different behavior." (2026-05-01)**
A: All three use CGLIB proxy interception — Spring generates a subclass that overrides the annotated method. The difference is what the interceptor does. **@Cacheable**: BEFORE checks Redis → on HIT returns cached value (method SKIPPED), on MISS runs method + stores result. **@Transactional**: BEFORE opens transaction (connection + autoCommit off), ALWAYS runs method, AFTER commits or rolls back. **@Async**: BEFORE submits method to thread pool and returns immediately, method runs later on a worker thread. Same proxy plumbing, different interceptor strategy. All three share the self-call limitation (`this.method()` bypasses the proxy).

---
