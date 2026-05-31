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

## Phase 3: Order Service & Cart (IN PROGRESS)

### Date: 2026-05-03

---

### Roadblocks & Issues Faced

**1. Inconsistent DI Pattern — Explicit Constructor vs @RequiredArgsConstructor (2026-05-03)**

- Problem: Agent-provided Cart implementation guidance used explicit constructor injection, while all Phase 1 & 2 classes use `@RequiredArgsConstructor` + `private final` fields.
- Resolution: Student caught the inconsistency and already used `@RequiredArgsConstructor` in the actual implementation. Agent corrected guidance and established consistency enforcement as a mandatory process step.
- Lesson: Consistency across modules matters — in an interview, inconsistent patterns signal lack of code review discipline.

**2. {@inheritDoc} Without Source Javadoc (2026-05-03)**

- Problem: `CartServiceImpl` methods had `{@inheritDoc}` but `CartService` interface had no Javadoc — so there was nothing to inherit. Generated docs would show empty descriptions.
- Fix: Added Javadoc to `CartService` interface methods (the source for inheritance).
- Lesson: `{@inheritDoc}` works by walking up the inheritance tree (interface → superclass) looking for the nearest method with documentation. If the source has nothing, the tag resolves to empty. Always document the interface (contract), use `{@inheritDoc}` on impls.

**3. URL Path Versioning Inconsistency (2026-05-03)**

- Problem: Cart used `/api/v1/cart` while User used `/api/auth` and Product used `/api/products` — no version prefix.
- Fix: Removed `/v1/` from Cart to match existing pattern. URL versioning will be addressed project-wide in Phase 7 (microservices decomposition).
- Lesson: Don't introduce versioning in one module without a project-wide strategy. Inconsistent API versioning confuses clients.

**4. BCrypt Hash Mismatch — Can't Retrieve Passwords (2026-05-03)**

- Problem: Forgot test account passwords. BCrypt is a one-way hash — no retrieval possible.
- Resolution: Generated a fresh hash via `new BCryptPasswordEncoder().encode("password")` and updated directly in PostgreSQL.
- Lesson: BCrypt (Provos & Mazières, 1999) is designed to be irreversible. Each hash includes a random salt, so even identical passwords produce different hashes. The `$2a$10$` prefix = algorithm version + cost factor (2^10 = 1024 rounds).

---

### Core Concepts Learned

**1. Redis as Data Store vs Cache — Two Different Usage Patterns (2026-05-03)**

- In Phase 2, Redis was used as a **cache** (via `@Cacheable`) — Spring Cache abstraction, automatic get/put, TTL-based eviction, transparent to service logic.
- In Phase 3, Redis is used as a **data store** (via `RedisTemplate`) — manual Hash operations, explicit serialization, direct TTL management, Redis IS the persistence layer for cart data.
- Key difference: Cache = "I have a DB, Redis speeds it up." Data store = "Redis IS where the data lives. No DB backup."
- `StringRedisTemplate` is a type-safe `RedisTemplate<String, String>` — all keys and values are Strings. Simpler than generic `RedisTemplate<K,V>` for Hash-based patterns.

**2. Redis Hash Structure — Why It Fits Shopping Carts (2026-05-03)**

- Redis Hashes map field → value within a key. Structure: `Cart:{userId}` → `{productId: JSON, productId: JSON}`.
- **Why Hash over String**: individual field HSET/HDEL is atomic per field — two concurrent "add to cart" requests for different products don't conflict. A String key with full-cart JSON requires read-modify-write (race condition: last write wins, items lost).
  - Race condition with String approach: Thread A reads cart `{shoe}`, Thread B reads cart `{shoe}`. Thread A adds hat → writes `{shoe, hat}`. Thread B adds belt → writes `{shoe, belt}`. Hat is LOST — Thread B overwrote Thread A's write. With Hash: Thread A does `HSET cart hat '...'`, Thread B does `HSET cart belt '...'` — both survive because they write to different fields.
- **Why Hash over List**: List stores items sequentially. No way to update quantity for a specific product without scanning the entire list (O(n)). Removing an item by productId = O(n) scan. Duplicate items possible (no field uniqueness).
- **Why Hash over Set**: Sets store unique values. Changing quantity means removing the old item and adding a new one (non-atomic two-step operation). No field-level access by productId.
- **Why Hash over Sorted Set**: Sorted Sets rank by score — useful for leaderboards, not for key-value field access. Overkill for a cart.
- Redis Hashes use compact encoding called **listpack** (since Redis 7.0, previously **ziplist**) for small Hashes (<128 fields, each <64 bytes). A typical cart qualifies — uses less memory than equivalent String keys.
- **Redis Hash commands used**: `HSET key field value` (add/update one field), `HGETALL key` (fetch all fields and values), `HDEL key field` (remove one field), `DEL key` (delete entire Hash). All are O(1) except HGETALL which is O(n) where n = number of fields.
- History: Redis Hashes introduced in Redis 2.0 (2010) by Salvatore Sanfilippo specifically for this use case — objects with multiple fields where you need to read/write individual fields without deserializing the whole thing. Before Hashes, people serialized entire objects into String keys, leading to race conditions under concurrency.

**3. ObjectMapper — Java ↔ JSON Bridge (2026-05-03)**

- Jackson's `ObjectMapper` converts between Java objects and JSON strings. Core class since Jackson 1.0 (~2009). Jackson has been the de-facto JSON library in Java since ~2009, predating both Gson and the Jakarta JSON-B spec.
- **Why we need it for Redis**: Redis Hash values are Strings. Our `AddToCartRequest` is a Java record. ObjectMapper bridges the gap: `writeValueAsString(item)` → JSON String for storage, `readValue(json, AddToCartRequest.class)` → Java object for retrieval.
- **Why not `toString()`**: `toString()` is not reversible — there's no standard way to parse a `toString()` output back into an object. JSON is a structured, standardized format with a guaranteed round-trip: serialize → deserialize → identical object.
- **When to use ObjectMapper vs let Spring handle it**:
  - Storing Java objects in Redis/Kafka/RabbitMQ → YES, use ObjectMapper manually
  - REST controller request/response bodies → NO, Spring does it automatically via `@RequestBody`/`@ResponseBody` (MappingJackson2HttpMessageConverter)
  - Reading a JSON config file → YES
  - Converting between DTOs manually → YES (`objectMapper.convertValue(source, TargetClass.class)`)
  - Storing in JPA/SQL database → NO, JPA handles mapping via `@Column`
- **Why inject it (not `new ObjectMapper()`)**: Spring Boot auto-configures an ObjectMapper bean with sensible defaults (Java 8 time module registered, proper date formatting, etc.). By injecting: (1) same configuration everywhere (consistent date formats, naming strategies), (2) singleton — ObjectMapper is thread-safe and expensive to create, (3) any customization (e.g., `spring.jackson.serialization.write-dates-as-timestamps=false`) applies globally.
- Mental model: `Java Object → objectMapper.writeValueAsString() → JSON String → Redis HSET → Redis Hash Field` and `Redis Hash Field → Redis HGET → JSON String → objectMapper.readValue() → Java Object`

**4. Why NOT @Data on JPA Entities (2026-05-03)**

- `@Data` = `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor`.
- **@EqualsAndHashCode danger**: generates equals/hashCode using ALL fields. A new entity (id=null) added to a HashSet → after save (id=7) → hashCode changes → HashSet can't find it. Hibernate uses Sets internally for `@OneToMany` collections — corrupted identity = lost entities.
- **@ToString danger**: bidirectional relationships cause infinite recursion. `Order.toString()` → prints items → `OrderItem.toString()` → prints order → StackOverflowError.
- **@RequiredArgsConstructor conflict**: `@Data` generates constructor for `final` fields only, but `@Builder` needs `@AllArgsConstructor`. They conflict.
- Correct pattern for entities: `@Getter` + `@Setter` + `@NoArgsConstructor` + `@AllArgsConstructor` + `@Builder`. Explicit and safe.
- `@Data` IS appropriate for: DTOs, value objects, anything NOT a JPA entity. (Though Java records are even better for immutable DTOs.)

**5. @OneToMany / @ManyToOne — Owning Side vs Inverse Side (2026-05-03)**

- The FK column (`order_id`) always lives in the child table (`order_items`). The "many" side holds the pointer.
- **Owning side** = `OrderItem.order` (@ManyToOne + @JoinColumn) — controls the FK column. Changes here are persisted to the FK.
- **Inverse side** = `Order.items` (@OneToMany(mappedBy = "order")) — read-only mirror for navigation. `mappedBy` value = field name in child entity.
- Only setting the inverse side (`order.getItems().add(item)`) without setting the owning side (`item.setOrder(order)`) → FK stays NULL in DB.
- `addItem()` helper method sets BOTH sides — ensures in-memory model and database are consistent.
- `CascadeType.ALL`: saving the parent automatically persists all children. `orphanRemoval = true`: removing a child from the parent's list deletes it from DB.

**6. Cart getCart() Data Flow — Redis Hash to API Response Pipeline (2026-05-03)**

- The `getCart()` method demonstrates a complete data transformation pipeline from raw Redis data to a structured API response:
  1. **Redis HGETALL** → `Map<Object, Object>` — returns all fields and values from the Hash. Keys are productIds (as Strings), values are JSON Strings.
  2. **Deserialize each JSON** → `AddToCartRequest` — each Map value (JSON String) is passed through `objectMapper.readValue()` to reconstruct the Java record.
  3. **Transform to CartItemResponse** → compute `subtotal = price × quantity` for each item. This is the DTO transformation layer — the internal storage format (AddToCartRequest) differs from the response format (CartItemResponse with computed subtotal).
  4. **Compute total** → `items.stream().map(CartItemResponse::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add)` — sums all subtotals. Uses `BigDecimal::add` (not double) for financial precision.
  5. **Compute expiresAt** → `redisTemplate.getExpire()` returns remaining TTL in seconds. Add to `Instant.now()` to get an absolute timestamp the frontend can display ("your cart expires at 3:45 PM").
  6. **Wrap in CartResponse** → final DTO with userId, items list, total, and expiresAt.
- Design decisions in the pipeline: (1) Empty cart returns `List.of()` not null — null collections force null-checks everywhere downstream. (2) `expiresAt` is null when cart doesn't exist — distinguishes "no cart" from "cart with items." (3) Service layer owns DTO transformation, not the repository — keeps repository reusable (raw data) and service responsible for business logic (subtotal, total computation).

**8. @Enumerated(EnumType.STRING) — Never Use ORDINAL (2026-05-03)**

- `EnumType.STRING` stores `"CONFIRMED"` in the DB. `EnumType.ORDINAL` stores `2` (the enum's position).
- ORDINAL danger: adding a new enum value in the middle shifts all ordinals — existing DB rows now map to wrong values. Production data corruption.
- STRING is slightly larger (varchar vs int) but safe against enum reordering.
- Default is ORDINAL (for backward compatibility with JPA 1.0 / EJB 2.x) — must always explicitly set STRING.
- History: This trap is so well-known that Vlad Mihalcea and Thorben Janssen have both written extensively about it. Top-5 Hibernate mistake since JPA 1.0 (2006).

**9. @Builder.Default — Lombok Builder vs Field Initializers (2026-05-03)**

- `@Builder` ignores Java field initializers (`= new ArrayList<>()`). Without `@Builder.Default`, builder creates object with `items = null`.
- `@Builder.Default private List<OrderItem> items = new ArrayList<>()` tells Lombok: "use this initializer when builder doesn't set the field."
- Important for collections in entities — NullPointerException when calling `order.getItems().add(...)` without it.

**10. Spring Data Derived Query — Property Path Traversal (2026-05-03)**

- Method names map to ENTITY FIELD names, never DB column names. Chain: method name → entity field → @Column → SQL column.
- `findByOrderId(Long orderId)`: Spring splits on camelCase → `order` (field in OrderItem) + `id` (field in Order via BaseEntity) → traverses `orderItem.getOrder().getId()` → SQL: `WHERE oi.order_id = ?`.
- Ambiguity: if entity has both `orderId` (plain Long) AND `order` (relationship), Spring prefers the direct field. Use underscore (`findByOrder_Id`) to force traversal.
- History: Property expression parser introduced in Spring Data Commons 1.4 (2012). Traversal reduced boilerplate significantly but introduced the ambiguity edge case, leading to the underscore-disambiguation rule (Spring Data 1.6).

**11. @Table(name = "orders") — SQL Reserved Keywords (2026-05-03)**

- `ORDER` is a SQL reserved keyword (used in `ORDER BY`). Creating a table named `order` causes syntax errors in PostgreSQL.
- Fix: `@Table(name = "orders")` — pluralize or prefix. Common alternatives: `customer_orders`, `purchase_orders`.
- Other common reserved keyword traps: `user` (use `users`), `group`, `index`, `key`, `value`, `check`, `column`.

---

### Roadblocks & Issues Faced (continued)

**5. JPQL Uses Entity Class Name, Not Table Name (2026-05-04)**

- Problem: Wrote `SELECT p FROM Products p` in `@Query` — "Products" is the table name. JPQL uses the entity class name (`Product`).
- Fix: `SELECT p FROM Product p WHERE p.id = :productId`
- Lesson: JPQL is entity-oriented, SQL is table-oriented. `FROM Product` refers to `@Entity class Product`. If you had `@Entity(name = "Prod")`, you'd write `FROM Prod` — but never the `@Table(name)` value.

**6. Builder Pattern Overwrites Object — Items Lost (2026-05-04)**

- Problem: Added items to an `Order` object via `order.addItem()`, then reassigned `order` to a new builder-created object (`order = Order.builder()...build()`). All items were lost because the new object has an empty list.
- Fix: Build the Order object first, THEN iterate and add items to it.
- Lesson: Builders create entirely new instances. If you need to mutate an existing object, don't rebuild it — mutate directly. This is a common trap with Lombok `@Builder` when building objects incrementally.

**7. Null Check vs isEmpty() — Understanding Return Types (2026-05-04)**

- Problem: Checked `cartResponse == null` but `getCart()` never returns null — it returns a `CartResponse` with an empty list.
- Fix: Check `cartResponse.items().isEmpty()` instead.
- Lesson: Know your API contracts. If a method returns a wrapper object, it may have "empty" state (empty list, zero total) rather than null. Read the interface Javadoc.

**8. Swapped Exception Types — Match Exception to Failure Condition (2026-05-04)**

- Problem: Used `InsufficientStockException` when product wasn't found, and `ResourceNotFoundException` when stock was too low. Both technically "work" but confuse API consumers — HTTP 400 for "product not found" makes no sense.
- Fix: Product not found → `ResourceNotFoundException` (404). Insufficient stock → `InsufficientStockException` (400).
- Lesson: Exception types define HTTP semantics. Choose the exception that matches the *actual failure reason*, not just the first one you import.

**9. @GetMapping Path Variable Without Curly Braces (2026-05-04)**

- Problem: `@GetMapping("/orderId")` — a literal string path `/api/order/orderId`. Without `{}`, Spring doesn't bind the `@PathVariable`.
- Fix: `@GetMapping("/{orderId}")`
- Lesson: Path variable template syntax requires curly braces. Without them, it's treated as a literal URL segment. The `@PathVariable` annotation won't throw an error — it just never receives a value.

**10. Control Flow Bug — Throw After If Block Always Executes (2026-05-04)**

- Problem: `if(canTransition) { save(); } throw new Exception();` — the throw executes regardless of the if condition. After the if-block saves, execution falls through to the throw.
- Fix: Either `return` inside the if, or move the throw to an `else` block.
- Lesson: In Java, code after an if-block executes unconditionally unless the if-block exits (return/throw). This is a classic "missing else" bug — especially dangerous in methods without a return type (void) where the compiler doesn't force you to handle all paths.

**11. Enum.valueOf() Throws IllegalArgumentException on Invalid Input (2026-05-04)**

- Problem: `OrderStatus.valueOf(request.status())` — if client sends "BLAH", this throws `IllegalArgumentException` with an unhelpful message. If called in two places (once inside canTransition, once for setStatus), the first throws before reaching any try-catch.
- Fix: Parse once into a local variable, wrap in try-catch, throw user-friendly exception.
- Lesson: `Enum.valueOf()` is strict — no partial matching, case-sensitive. Always validate user input before passing to `valueOf()`. Parse once, use the variable everywhere.

**12. @Slf4j vs Log4j — Project-Wide Logger Consistency (2026-05-05)**

- Problem: Used `@Slf4j` (Lombok + SLF4J facade) in OrderServiceImpl while the entire project uses Log4j directly (`LogManager.getLogger()`).
- Fix: Replaced with `private static final Logger log = LogManager.getLogger(OrderServiceImpl.class);`
- Lesson: Pick one logging pattern and enforce it everywhere. Mixing approaches confuses developers reading the code — "which logger abstraction are we using?" SLF4J is a facade over logging implementations, Log4j is a direct implementation. Both work, but mixing them signals no established convention.

---

### Core Concepts Learned (continued)

**12. Pessimistic Locking — Blocking Concurrent Access at the Database Level (2026-05-04)**

- **The problem it solves**: The "lost update" race condition. Two threads read the same stock value (1), both check "stock >= 1? yes", both decrement to 0 and save. Result: oversold.
- **How it works**: `SELECT ... FOR UPDATE` acquires an exclusive row-level lock in PostgreSQL. Any other transaction trying to read-for-update or write the same row BLOCKS (waits) until the first transaction commits or rolls back.
- **In Spring/JPA**: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository method + `@Transactional` on the service caller. JPA translates to `SELECT ... FOR UPDATE`.
- **Sequence**: Thread A acquires lock → reads stock=1 → decrements → commits → releases lock. Thread B was blocked → now reads stock=0 → throws InsufficientStockException. No oversell.
- **Trade-offs**: Simple reasoning (impossible to get wrong), guarantees consistency, no retry logic needed. BUT: reduces throughput (blocked threads idle), deadlock risk (circular waits), doesn't scale across distributed systems (DB-specific).
- **Historical context**: Oldest concurrency strategy, inherited from mainframe-era databases (1970s-80s, IBM System R). Name reflects the assumption: conflicts are *likely*, so prevent them upfront.

**13. Optimistic Locking — Detect-and-Retry at Write Time (2026-05-04)**

- **How it works**: No lock acquired during read. Each row has a `@Version` column (integer). On UPDATE, SQL includes: `WHERE id = ? AND version = ?`. If another transaction already changed the row (bumped version), zero rows match → `OptimisticLockException` → application retries.
- **In Spring/JPA**: Add `@Version private Long version;` to the entity. Hibernate automatically includes version check in every UPDATE.
- **Trade-offs**: No blocking (max throughput under low contention), works across distributed systems, no deadlocks. BUT: requires retry logic in application code, cascading retries under high contention degrade performance worse than pessimistic, more complex to implement correctly.
- **Historical context**: Gained popularity in 1990s-2000s with web applications — many users reading, few writing same row simultaneously. Name reflects assumption: conflicts are *unlikely*.

**14. When to Use Pessimistic vs Optimistic (2026-05-04)**

| Scenario | Choice | Reason |
|----------|--------|--------|
| E-commerce checkout (stock) | Pessimistic | High contention on popular items, overselling catastrophic, short transaction |
| User profile edit | Optimistic | Rare concurrent edits to same profile |
| Wiki/document editing | Optimistic | Conflicts detectable, user can merge |
| Bank transfer | Pessimistic | Financial correctness > throughput |
| Cart updates | Neither | Redis is single-threaded, no concurrent mutation |
| Distributed microservices | Optimistic | Can't hold DB locks across service boundaries |

**15. Idempotency Keys — Preventing Duplicate Side Effects (2026-05-05)**

- **The problem**: Network failures between client and server cause retries. Without idempotency: Client sends order → Server creates it → Response lost → Client retries → Server creates ANOTHER order. User double-charged.
- **Why orderId can't solve it**: `orderId` is generated AFTER the row is inserted. By the time you could check, the duplicate is already created.
- **How idempotency key works**: Client generates a unique value (UUID) BEFORE sending. Server checks: "Have I processed this key?" → If yes, return existing result. If no, proceed and store the key with the result.
- **Who generates it**: Always the CLIENT. It's in the request body, not generated server-side. Common approaches: `UUID.randomUUID()`, or deterministic: `userId + cartHash + timestamp`.
- **Implementation pattern**: Unique constraint on `idempotency_key` column. First line of `placeOrder()`: check DB for existing order with this key → return it if found. This is the "check-before-create" pattern.
- **Historical context**: Popularized by Stripe (~2015) via `Idempotency-Key` HTTP header after merchants reported double-charges during timeouts. Now standard in all financial APIs (Stripe, Razorpay, PayPal). Mathematical origin: idempotent operations satisfy f(f(x)) = f(x) — applying them multiple times produces the same result as once.
- **Difference from database unique constraint on orderId**: Unique constraint on `orderId` prevents duplicate IDs (a database concern). Idempotency key prevents duplicate *business operations* (an application concern). They operate at different levels.

**16. Order Status State Machine — EnumSet Transition Rules (2026-05-04)**

- A state machine constrains which transitions are valid. Not every status change makes sense: DELIVERED → CREATED would "un-deliver" an order.
- **Implementation**: `Map<OrderStatus, EnumSet<OrderStatus>>` — each status maps to the set of states it can transition TO. `canTransition(next)` is a simple `contains()` check.
- **Why in the enum itself**: The enum owns the knowledge of its own valid transitions. Putting transition logic in the service layer means the rules are scattered and can diverge between methods.
- **EnumSet**: Specialized `Set` implementation for enums — uses a bit vector internally (one bit per enum constant). O(1) for `contains()`. More memory-efficient than `HashSet<OrderStatus>`.
- **Terminal states**: CANCELLED and REFUNDED map to `EnumSet.noneOf()` — no transitions allowed from these states.

**17. Stock Restoration on Returns — Same Lock Pattern in Reverse (2026-05-05)**

- When an order transitions to RETURNED, stock must be added back. Same pessimistic lock pattern as checkout (prevents race conditions during restock).
- `findByProductId()` with `@Lock(PESSIMISTIC_WRITE)` → add quantity → save. Prevents: two returns for the same product causing incorrect stock (same lost-update problem, just in the add direction).
- **Ownership validation**: Customer can only return their own orders. Server checks `order.getUserId().equals(requestingUserId)` — returns generic "not found" rather than "access denied" to avoid leaking order existence to non-owners (security best practice: don't confirm resource existence to unauthorized users).

**18. HTTP Methods — Semantics, Routing, and Infrastructure Behavior (2026-05-05)**

- **What Spring does with @GetMapping/@PostMapping/etc.**: Registers handler mappings at startup — routing rules of `(HTTP method + URL pattern) → controller method`. At runtime, `DispatcherServlet` asks `RequestMappingHandlerMapping` to match. If URL matches but method doesn't → 405 Method Not Allowed.
- **Spring does NOT validate** that your method body matches the verb. You CAN do "get" behavior inside a POST handler — Spring won't complain. The distinction matters for infrastructure OUTSIDE Spring.
- **GET**: Idempotent, safe, cacheable. Browsers/CDNs/proxies cache GET responses. No request body (proxies may strip it).
- **POST**: Not idempotent, not cached, not retried by infrastructure. Use for creating resources or actions with side effects.
- **PUT**: Idempotent. "Replace the ENTIRE resource." Client must send complete representation. Load balancers may auto-retry.
- **PATCH**: Partial modification. Client sends only changed fields. RFC 5789 (2010) — added because PUT's "full replacement" didn't fit real-world partial updates.
- **DELETE**: Idempotent. Calling it multiple times on same resource produces same result.
- **Why it matters beyond routing**: (1) Caching: only GET is cached by CDN/browser. (2) Retry safety: gateways retry GET/PUT/DELETE but never POST. (3) CORS: PUT/PATCH/DELETE trigger preflight OPTIONS request. (4) Tooling: Swagger/monitoring infers behavior from verb. (5) Contract: API consumers expect semantic consistency.
- **Historical context**: Roy Fielding's REST dissertation (2000) defined the "uniform interface" constraint — leveraging HTTP methods so intermediaries (caches, proxies) make intelligent decisions without inspecting payloads. PATCH added in 2010 (RFC 5789) to fill the gap between "replace everything" (PUT) and "modify a part" (real-world need).

---

### Interview Questions Discussed (continued)

**Q104: "What happens if two users try to buy the last item simultaneously?" (2026-05-04)**
A: Without locking: both read stock=1, both pass the `stock >= quantity` check, both decrement to 0, both save — oversold. With pessimistic locking: the first transaction acquires a `SELECT ... FOR UPDATE` lock on the product row. The second transaction blocks until the first commits. First user succeeds (stock→0). Second user unblocks, reads stock=0, fails with `InsufficientStockException`. No oversell possible.

**Q105: "Why pessimistic over optimistic locking for e-commerce checkout?" (2026-05-04)**
A: Three reasons: (1) High contention on popular products during flash sales — optimistic would cause cascading retries, worse than blocking. (2) Overselling has real-world cost (refunds, customer trust damage) — can't afford even one lost update. (3) The critical section is short (read stock, decrement, save within one transaction) — holding a lock briefly is acceptable. Optimistic is better when conflicts are rare (profile edits, wiki pages) where retry cost is low.

**Q106: "What is an idempotency key and why is it necessary even with unique orderId?" (2026-05-05)**
A: An idempotency key is a client-generated unique value (UUID) sent with the request that allows the server to detect and safely handle retries. `orderId` is server-generated AFTER creation — it can't prevent duplicates because it doesn't exist until the first request succeeds. The key exists BEFORE the request, so on retry, the server checks: "did I already process this key?" → returns cached result without side effects. Critical for payment flows where network failures between "order created" and "response received" would otherwise cause double-charges.

**Q107: "How would you implement an order status state machine in Java?" (2026-05-04)**
A: Store valid transitions as a `Map<OrderStatus, EnumSet<OrderStatus>>` inside the enum itself. Each constant maps to the set of states it can transition to. Add a `canTransition(OrderStatus next)` method that checks `TRANSITIONS.get(this).contains(next)`. Terminal states (CANCELLED, REFUNDED) map to `EnumSet.noneOf()`. This keeps transition rules co-located with the enum, uses EnumSet's O(1) bit-vector lookup, and makes it impossible to accidentally allow invalid transitions — the state machine is self-documenting.

**Q108: "Why return 404 instead of 403 when a user tries to access another user's order?" (2026-05-05)**
A: Returning 403 ("Forbidden") confirms the resource EXISTS but the user lacks access. This leaks information — an attacker can enumerate valid order IDs. Returning 404 ("Not Found") reveals nothing: the order might not exist, or it might belong to someone else — the attacker can't tell. This is called "not confirming resource existence to unauthorized users" and is a standard security practice (OWASP recommendation).

**Q109: "Why use PATCH instead of PUT for updating order status?" (2026-05-05)**
A: PUT semantics mean "replace the ENTIRE resource with this payload" — the client must send all fields (userId, totalAmount, items, shippingAddress, etc.) or they become null. PATCH means "apply a partial modification" — sending only the fields being changed (just `status` in our case). Status transition additionally has business validation (state machine), which PATCH better communicates: this isn't a simple field overwrite but a controlled operation with rules. PUT would be appropriate for full order updates where the client sends a complete replacement.

**Q110: "If you can expose a POST endpoint and do 'get' behavior inside, why does the HTTP method annotation matter?" (2026-05-05)**
A: Spring uses the annotation purely for routing (matching incoming requests to handlers). It doesn't validate your method body matches the verb semantics. But the HTTP method choice matters for infrastructure beyond Spring: (1) Caches (CDN, browser) only cache GET — POST responses are never cached; (2) Load balancers may auto-retry GET/PUT/DELETE (idempotent) but never POST (might create duplicates); (3) CORS preflight is triggered for PUT/PATCH/DELETE, not simple GET/POST; (4) The method communicates a contract — API consumers, Swagger docs, and monitoring tools all interpret semantics from the verb.

---
A: Redis Hash maps field → value within a single key. For a cart: `Cart:user123` → `{prod1: json, prod2: json}`. Each field operation (HSET/HDEL) is atomic at the field level — two concurrent "add to cart" for different products don't conflict. With a String key (entire cart as JSON), every modification requires GET → deserialize → modify → serialize → SET — a read-modify-write cycle with race conditions (last write wins, items lost). Hash also uses compact encoding (listpack) for small objects, saving memory.

**Q99: "What is ObjectMapper and when should you use it vs letting Spring handle serialization?" (2026-05-03)**
A: `ObjectMapper` is Jackson's core class for Java ↔ JSON conversion. Let Spring handle it automatically for: REST request/response bodies (`@RequestBody`/`@ResponseBody`), where `MappingJackson2HttpMessageConverter` does it transparently. Use `ObjectMapper` manually when: storing objects in Redis (Hash values must be Strings), reading JSON config files, or converting between DTOs programmatically. Always inject the Spring-managed singleton — it has auto-configured modules (Java 8 time, etc.) and consistent settings.

**Q100: "Why should you never use @Data on JPA entities?" (2026-05-03)**
A: `@Data` generates `@EqualsAndHashCode` using all fields — dangerous for entities where `id` starts as null (pre-persist). Adding an entity to a HashSet, then saving it (id assigned), changes its hashCode — the Set can't find it anymore. Hibernate uses Sets for `@OneToMany` collections, so this corrupts entity tracking. `@Data` also generates `@ToString` — bidirectional relationships (`Order ↔ OrderItem`) cause infinite recursion → StackOverflowError. Use `@Getter` + `@Setter` + `@NoArgsConstructor` + `@AllArgsConstructor` + `@Builder` instead.

**Q101: "Explain the owning side vs inverse side in JPA bidirectional relationships." (2026-05-03)**
A: The owning side has `@JoinColumn` and controls the FK column in the database — only changes to the owning side are persisted to the FK. The inverse side has `@OneToMany(mappedBy = "fieldName")` — it's a read-only navigation mirror. `mappedBy` tells JPA: "don't manage the FK from this side, look at the named field in the child entity." If you only set the inverse side (`parent.getChildren().add(child)`) without setting the owning side (`child.setParent(parent)`), the FK column stays NULL. That's why bidirectional relationships need a helper method that sets both sides.

**Q102: "Why use EnumType.STRING instead of ORDINAL for JPA enums?" (2026-05-03)**
A: ORDINAL stores the enum's position (0, 1, 2...). If you add a new value in the middle of the enum, all positions after it shift — existing DB rows now map to the wrong enum value. Data corruption in production. STRING stores the enum name as text ("CONFIRMED", "SHIPPED") — immune to reordering. The cost is slightly more storage (varchar vs int), which is negligible. ORDINAL is the JPA default (backward compatibility with EJB 2.x), so you must always explicitly annotate with `@Enumerated(EnumType.STRING)`.

**Q103: "How does Spring Data resolve `findByOrderId` when the entity has no `orderId` field?" (2026-05-03)**
A: Spring Data's property expression parser splits the method name on camelCase boundaries and walks the entity graph. `findByOrderId` → tries `orderId` field (not found) → splits into `order` + `id` → finds `OrderItem.order` (@ManyToOne) → traverses to `Order.id` (from BaseEntity). Generates SQL: `WHERE oi.order_id = ?`. If the entity had BOTH an `orderId` field AND an `order` relationship, there'd be ambiguity — Spring prefers the direct field. Use underscore (`findByOrder_Id`) to force traversal through the relationship.

---

## Phase 4: Market Data Service — Reactive (IN PROGRESS)

### Date: 2026-05-08

---

### Core Concepts Learned

**1. @Configuration + @Bean — Spring's Bean Factory Pattern (2026-05-08)**

- `@Configuration` is a specialized `@Component`. Spring creates a CGLIB proxy of this class to ensure singleton semantics — calling a `@Bean` method from within another `@Bean` method in the same class returns the SAME instance, not a new one.
- Each `@Bean`-annotated method is invoked exactly once (singleton scope). The return value is stored in the ApplicationContext. Bean name defaults to method name.
- Analogy: `@Configuration` = recipe book, `@Bean` method = recipe, Spring = chef. At startup, chef cooks each recipe once, stores dishes in the pantry (ApplicationContext). When any class requests a `WebClient`, Spring serves the pre-made instance.
- Why not `new WebClient.builder()...build()` in the service: (1) No singleton guarantee — each class creates its own instance (wasted connection pools), (2) No centralized configuration, (3) No testability (can't swap with mock), (4) No lifecycle management (Spring can't shut down connection pools on app shutdown).
- History: The `@Configuration` + `@Bean` style was introduced in Spring 3.0 (2009) as a Java-based alternative to XML bean definitions. Before this, every bean was declared in `applicationContext.xml` — verbose and not type-safe.

**2. WebClient — Non-Blocking HTTP Client (RestTemplate Replacement) (2026-05-08)**

- `RestTemplate` (Spring 3.0, 2009) was synchronous — one thread per HTTP call, blocked until response arrived. At scale (50 stock price fetches), 50 threads sit blocked.
- `WebClient` (Spring 5, 2017) is built on Reactor Netty, uses non-blocking I/O. Returns `Mono<T>` (0-or-1 result) or `Flux<T>` (0-to-N stream).
- **Restaurant analogy**: RestTemplate = "dedicated waiter" (stands in kitchen waiting for each dish). WebClient = "buzzer model" (waiter gives you a buzzer/`Mono`, goes to serve other tables, buzzer vibrates when dish is ready).
- **Critical insight**: Adding `spring-boot-starter-webflux` alongside `spring-boot-starter-web` does NOT switch the server to Netty. Spring Boot detects `web` is present and stays on Tomcat. You only get the reactive *client* libraries.
- **`.block()` at service boundary**: MVC controllers expect synchronous return values. `.block()` waits for the Mono to complete and unwraps the value. Even with `.block()`, the underlying HTTP call still uses non-blocking I/O — the Netty event loop thread is freed while waiting.
- `RestTemplate` was officially deprecated in Spring 5.0 docs ("prefer WebClient") though not removed for backward compatibility.

**3. Mono<T> — A Promise of Future Data (2026-05-08)**

- `Mono<T>` = "a container that will eventually hold 0 or 1 value, or an error." Nothing happens until someone subscribes or calls `.block()`.
- Writing `webClient.get().uri(...).retrieve().bodyToMono(...)` builds a *pipeline description*, not an execution. Like writing a recipe without cooking.
- `.map(fn)` = transform the value when it arrives (like Stream.map). Can only produce a value.
- `.flatMap(fn)` = transform the value into ANOTHER Mono (like Stream.flatMap). Can return `Mono.error()` to signal failure, which `.map()` cannot.
- This is why `AlphaVantageClient` uses `.flatMap()` for JSON parsing — it needs to return `Mono.error()` when the response is invalid.

**4. Reactor Netty HttpClient — Timeout Configuration (2026-05-08)**

- `HttpClient.create()` creates a Reactor Netty HTTP client (separate from WebClient — it's the underlying transport).
- `.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)` — TCP connect timeout. Controls how long the SYN → SYN-ACK → ACK handshake can take. This is a Netty channel-level option inherited from NIO socket configuration.
- `.responseTimeout(Duration.ofSeconds(10))` — time waiting for the first response byte after the request is fully sent. Similar to Socket's `setSoTimeout()`.
- **Two timeouts protect different failure modes**: Connect timeout = server unreachable (firewall drop, wrong IP). Response timeout = connection established but server slow (overloaded, deadlocked).
- Without timeouts: if Alpha Vantage hangs, your thread blocks indefinitely. With them, you fail fast and let Resilience4j handle fallback.

**5. Jackson JsonNode — Tree-Based JSON Parsing (2026-05-08)**

- `ObjectMapper.readTree(String)` parses JSON into a navigable tree of `JsonNode` objects in two phases:
  - **Phase 1 — Tokenization**: reads character-by-character, produces tokens (`TOKEN_START_OBJECT`, `TOKEN_FIELD_NAME`, `TOKEN_VALUE_STRING`, etc.)
  - **Phase 2 — Tree Construction**: builds a tree of typed nodes from the tokens
- **JsonNode type hierarchy**: `JsonNode` is the abstract parent. Subtypes created based on JSON value type:
  - `ObjectNode` for `{ }` — stores children in `LinkedHashMap<String, JsonNode>` (preserves insertion order)
  - `ArrayNode` for `[ ]`
  - `TextNode` for `"string"`
  - `IntNode`/`LongNode` for integers
  - `DecimalNode`/`DoubleNode` for decimals
  - `BooleanNode` for `true/false`
  - `NullNode` for `null`
- **`.path("key")` vs `.get("key")`**: Both navigate the tree. `.get()` returns `null` on missing key (NPE risk). `.path()` returns `MissingNode` singleton (never null) — safe for chaining: `root.path("Global Quote").path("05. price").asText()`.
- **Why not `String.split()`**: JSON is a tree structure, not flat text. Split can't handle nested objects, escaped quotes, arrays, or mixed types. Before Jackson/Gson existed (early 2000s), developers parsed JSON with regex/split — fragile, broke on edge cases. Jackson (2009) solved this with proper tree parsing.
- **Why JsonNode over `@JsonProperty` mapping**: Alpha Vantage's keys are `"01. symbol"`, `"05. price"` — numbered with dots and spaces. These can't be Java field names. You'd need `@JsonProperty("05. price")` on every field plus a wrapper class for the outer `"Global Quote"` key. Manual JsonNode navigation is simpler for irregular/messy JSON.

---

### Interview Questions Discussed

**Q111: "What is @Configuration and how does Spring manage @Bean methods?" (2026-05-08)**
A: `@Configuration` marks a class as a bean definition source. Spring creates a CGLIB proxy subclass that intercepts `@Bean` method calls to enforce singleton semantics — calling a `@Bean` method multiple times returns the same instance. Each `@Bean` method is invoked once at startup, and the return value is registered in the ApplicationContext with the method name as the bean ID. This replaced XML-based bean definitions (Spring 3.0, 2009). Without the proxy, inter-bean references (`beanA()` called from `beanB()`) would create separate instances, violating singleton scope.

**Q112: "What is WebClient and why was RestTemplate deprecated?" (2026-05-08)**
A: WebClient (Spring 5, 2017) is a non-blocking HTTP client built on Reactor Netty. RestTemplate (Spring 3.0, 2009) was synchronous — one thread blocked per HTTP call. Under high concurrency (50 parallel API calls), RestTemplate pins 50 threads; WebClient uses an event loop and releases threads while waiting for responses. WebClient returns `Mono<T>`/`Flux<T>` (reactive types), supports streaming, and integrates with Resilience4j. Even within an MVC app (Tomcat), WebClient's I/O is non-blocking — you call `.block()` at the service boundary to bridge back to synchronous controllers. Adding `spring-boot-starter-webflux` alongside `spring-boot-starter-web` only adds the client libraries; the server stays on Tomcat.

**Q113: "What is Mono<T> and how does it differ from Optional<T> and CompletableFuture<T>?" (2026-05-08)**
A: All three represent "a value that might exist," but at different levels. `Optional<T>` is synchronous and already resolved — it HAS or DOESN'T HAVE a value right now. `CompletableFuture<T>` is async — will complete in the future, but once you create it the computation is already running. `Mono<T>` is lazy AND async — nothing happens until someone subscribes. Writing `webClient.get()...bodyToMono(String.class)` builds a pipeline blueprint but makes no HTTP call. The call fires only on `.subscribe()` or `.block()`. This laziness enables composition: you can add `.retry()`, `.timeout()`, `.map()` to the pipeline before anything executes. Mono also supports backpressure (Reactive Streams spec), which CompletableFuture doesn't.

**Q114: "Explain .map() vs .flatMap() in reactive streams." (2026-05-08)**
A: `.map(fn)` transforms the value synchronously — the function returns a plain value T, which gets wrapped back into Mono<T>. `.flatMap(fn)` transforms the value into ANOTHER Mono — the function returns Mono<T>, and flatMap "flattens" the nested Mono<Mono<T>> into Mono<T>. Key difference: flatMap can return `Mono.error()` to signal failure, while map can only throw exceptions (which get wrapped in onError signal anyway, but less idiomatic). Use map for simple transformations (parse string to int), flatMap when the transformation itself can fail or involves another async operation (call another service, validate and potentially reject).

**Q115: "What is Jackson's JsonNode and when would you use it over POJO mapping?" (2026-05-08)**
A: `JsonNode` is Jackson's tree model — `readTree()` parses JSON into a tree of typed nodes (`ObjectNode`, `TextNode`, `ArrayNode`, etc.) without needing a Java class. Use it when: (1) JSON keys can't map to Java field names (Alpha Vantage's `"05. price"`), (2) you only need a few fields from a large response, (3) the schema is dynamic/unknown at compile time, (4) you need to inspect the JSON structure before deciding how to parse it. Use POJO mapping (`readValue()` with `@JsonProperty`) when the JSON structure is stable, maps cleanly to Java fields, and you need type safety. JsonNode is more flexible but loses compile-time type checking.

**Q116: "What's the difference between .path() and .get() on JsonNode?" (2026-05-08)**
A: Both navigate the JSON tree. `.get("key")` returns `null` if the key doesn't exist — calling `.asText()` on null throws NPE. `.path("key")` returns a `MissingNode` singleton (never null) — calling `.asText()` on MissingNode returns empty string `""`. This makes `.path()` safe for chaining: `root.path("A").path("B").path("C").asText()` won't NPE even if intermediate keys are missing. Use `.path()` for defensive navigation, `.get()` when you explicitly want to null-check and handle missing keys differently.

---

### Redis opsForValue vs opsForHash — Data Structure Choice (2026-05-09)

**6. Choosing the Right Redis Data Structure (2026-05-09)**

- `opsForValue()` — stores one atomic value per key. Read/write the entire value. Each key has its own TTL. Used for price cache: `price:AAPL → {full JSON}` because you always read/write the complete price, never a sub-field.
- `opsForHash()` — stores multiple fields inside one key (a mini key-value store within a key). Read/write individual fields. TTL is per-key (all fields expire together). Used for cart: `Cart:user1 → {prod1: json, prod2: json}` because you add/remove individual products without touching others.
- **Why not Hash for prices**: Redis TTL is per-key, not per-field. If you stored all prices in one Hash (`prices → {AAPL: json, MSFT: json}`), all symbols would expire together. With opsForValue, each symbol gets its own key and its own 30-second TTL.
- **Why not String for cart**: Storing the entire cart as one JSON string requires read-modify-write on every operation (GET → deserialize → modify → serialize → SET). Under concurrency, this causes the "last write wins" race condition — Thread A adds hat, Thread B adds belt, B overwrites A's write, hat is lost. Hash field-level operations (`HSET`, `HDEL`) are atomic per field.
- **Decision rule**: one atomic blob per key → opsForValue. Multiple independently-accessible fields per key → opsForHash.

---

### Spring AOP — Native Advisor vs AspectJ @Aspect (2026-05-09)

**7. Why @Transactional/@Cacheable Don't Need AOP Dependency But Resilience4j Does (2026-05-09)**

Spring has **two different AOP mechanisms** that look identical from the outside (both create CGLIB proxies) but work differently internally:

**Spring's Native Advisor System (used by @Transactional, @Cacheable):**
- Spring registers `Advisor` + `MethodInterceptor` classes programmatically via auto-configurations (`TransactionAutoConfiguration`, `CacheAutoConfiguration`)
- These use Spring's built-in `AbstractAutoProxyCreator` which lives in the `spring-aop` module
- `spring-aop` is already on the classpath — pulled in transitively by `spring-context`, which is in every Spring Boot starter
- Chain: `spring-boot-starter-web` → `spring-context` → `spring-aop` → native AOP works

**AspectJ @Aspect System (used by Resilience4j, custom aspects):**
- Third-party libraries define `@Aspect`-annotated classes (e.g., `CircuitBreakerAspect`, `RetryAspect`)
- Spring needs `AnnotationAwareAspectJAutoProxyCreator` to detect and process `@Aspect` classes
- This creator is activated by `@EnableAspectJAutoProxy`, which is auto-configured by `AopAutoConfiguration`
- BUT `AopAutoConfiguration` has `@ConditionalOnClass(Advice.class)` — the `Advice` class comes from the `aspectjweaver` library
- Without `spring-boot-starter-aop`, `aspectjweaver` isn't on classpath → `AopAutoConfiguration` doesn't activate → `@Aspect` classes silently ignored → Resilience4j annotations do nothing
- `spring-boot-starter-aop` provides: `aspectjweaver` + triggers `AopAutoConfiguration`

**Why two systems?** Spring's native Advisors (2004) were designed for internal framework use — tightly coupled to Spring's bean lifecycle. AspectJ's `@Aspect` style (adopted by Spring 2.0, 2006) is the public extension point for third-party libraries and application developers who need custom AOP without accessing Spring internals.

---

### CGLIB Proxy Internals — Real Object + Proxy Coexistence (2026-05-09)

**8. How CGLIB Proxy Wraps the Real Object (2026-05-09)**

The proxy doesn't REPLACE the real object — it WRAPS it. Both exist in memory:

```
Step 1: Spring creates the REAL AlphaVantageClient
  → Constructor runs (@RequiredArgsConstructor)
  → webClient, objectMapper, apiKey all injected
  → Fully functional object in memory

Step 2: BeanPostProcessor inspects the real object
  → Finds @CircuitBreaker, @Retry, @RateLimiter on methods
  → Decides: needs a proxy

Step 3: CGLIB generates a subclass at runtime
  → AlphaVantageClient$$SpringCGLIB extends AlphaVantageClient
  → Proxy stores: target (real object) + interceptors (aspect chain)
  → Proxy does NOT have webClient/objectMapper/apiKey populated
  → It never runs method bodies — only interceptor chain, then delegates to target

Step 4: Spring registers PROXY in ApplicationContext
  → All injection points receive the proxy
  → Real object only reachable via proxy's internal target reference
```

Memory layout:
```
MarketDataServiceImpl
  .alphaVantageClient ──→ CGLIB Proxy
                            .target ──→ Real AlphaVantageClient
                            .interceptors    .webClient = [injected]
                              [RetryAspect]  .objectMapper = [injected]
                              [CBaspect]     .apiKey = "GABC..."
                              [RLaspect]     .getStockQuote() { actual code }
```

At runtime: `serviceImpl.alphaVantageClient.getStockQuote("AAPL")` → hits proxy → runs interceptor chain → proxy calls `target.getStockQuote("AAPL")` → real object's method runs with real dependencies.

**9. Private Fallback Methods Work Via Reflection (2026-05-09)**

When the circuit breaker catches a failure and needs to invoke the fallback:
1. The `CircuitBreakerAspect` uses `targetClass.getDeclaredMethod("getStockQuoteFallback", String.class, Throwable.class)`
2. Calls `fallbackMethod.setAccessible(true)` — bypasses Java's access modifier check
3. Invokes `fallbackMethod.invoke(targetObject, symbol, exception)` on the **real object** (not through proxy)

The fallback should be `private` — it's an internal implementation detail. No external code should call it directly. `setAccessible(true)` is the same mechanism Spring uses for `@Autowired` on private fields and Jackson uses for private field serialization.

---

### Resilience4j — Circuit Breaker, Retry, Rate Limiter (2026-05-09)

**10. Circuit Breaker Pattern — Three States (2026-05-09)**

- **Problem it solves**: When a downstream service (Alpha Vantage) is down, your app keeps sending requests that will fail, wasting resources and slowing responses. Each failed request might also time out (10 seconds of thread blocking for nothing).
- **Three states**:
  - CLOSED (normal) — requests pass through. Failures counted in sliding window.
  - OPEN (tripped) — requests immediately rejected with fallback. No calls to downstream. Stays open for configured duration.
  - HALF-OPEN (testing) — allows limited test requests. If they succeed → CLOSED. If they fail → OPEN again.
- **Electrical circuit breaker analogy**: Too many appliances short-circuit (failures) → breaker trips open (cuts the circuit). After cooldown, cautiously test one appliance (half-open). Works → close breaker. Fails → stay open.
- **Configuration**: sliding-window-size=10 (track last 10 calls), failure-rate-threshold=50 (open if 50%+ fail), wait-duration-in-open-state=30s (stay open 30s before testing).
- **History**: Netflix Hystrix (2012) popularized the pattern for Java microservices. Martin Fowler documented it as a formal pattern in 2014. Hystrix entered maintenance mode 2018; Resilience4j became the successor — lighter, functional-style API, Spring Boot 3 compatible.

**11. Retry Pattern — Handling Transient Failures (2026-05-09)**

- Transient failures (network hiccup, DNS timeout, 503 from overloaded server) often resolve on the next attempt.
- Retry automatically re-executes the call with configurable delay between attempts.
- Configuration: max-attempts=3 (1 original + 2 retries), wait-duration=2s (wait between attempts).
- Important: only retry on transient errors. Retrying a 400 Bad Request or 401 Unauthorized is pointless — it'll fail every time.

**12. Rate Limiter — Respecting External API Limits (2026-05-09)**

- External APIs enforce rate limits (Alpha Vantage: 25 requests/day free tier). Exceeding them gets you blocked or returns errors.
- Rate limiter caps outgoing requests: limit-for-period=5 (max 5 calls per period), limit-refresh-period=60s (period resets every 60s).
- timeout-duration=0s means don't queue excess requests — fail immediately. Alternative: set a timeout to wait for a permit.

**13. Annotation Stacking Order — Outermost to Innermost (2026-05-09)**

- When multiple Resilience4j annotations are stacked, they wrap like onion layers. Order: `@Retry` (outermost) → `@CircuitBreaker` → `@RateLimiter` (innermost) → actual method.
- Why this order matters: Retry wraps everything — if circuit breaker rejects (OPEN state), retry doesn't retry (correct behavior, no point retrying a tripped breaker). Circuit breaker monitors failures from both rate limiter rejections and actual API failures. Rate limiter is the last gate before the HTTP call.

**14. Resilience4j YAML Config — `instances` vs `default` (2026-05-09)**

- `resilience4j.circuitbreaker.instances.alphaVantage` configures a named instance matching `@CircuitBreaker(name = "alphaVantage")`.
- `resilience4j.circuitbreaker.default` configures fallback defaults for unnamed or unconfigured instances.
- If annotation uses `name = "alphaVantage"` but YAML only has `default`, the named instance gets Resilience4j's hardcoded defaults — NOT the YAML `default` section. Name must match exactly (case-sensitive).

---

### Interview Questions Discussed (2026-05-09)

**Q117: "When would you use Redis opsForValue vs opsForHash?" (2026-05-09)**
A: `opsForValue` stores one atomic value per key — use when you always read/write the complete object (price cache, session data, simple counters). Each key has independent TTL. `opsForHash` stores multiple fields per key — use when you need independent field-level access within one logical entity (shopping cart with multiple products, user profile with individual fields). TTL is per-key (all fields expire together). Decision: if you need per-field operations or different fields change independently → Hash. If it's always all-or-nothing → String (opsForValue).

**Q118: "Why does @Transactional work without spring-boot-starter-aop but @CircuitBreaker doesn't?" (2026-05-09)**
A: Different AOP mechanisms. `@Transactional` and `@Cacheable` use Spring's native Advisor/MethodInterceptor system, registered programmatically by auto-configurations (`TransactionAutoConfiguration`). This only needs `spring-aop` module — already on classpath via `spring-context` (in every starter). Resilience4j uses AspectJ-style `@Aspect` classes, which require `aspectjweaver` library + `@EnableAspectJAutoProxy` (activated by `AopAutoConfiguration`, which is conditional on `aspectjweaver` being present). Without `spring-boot-starter-aop`, `aspectjweaver` isn't available → `AopAutoConfiguration` doesn't activate → `@Aspect` classes are silently ignored → annotations do nothing.

**Q119: "How does a CGLIB proxy relate to the real object? Does the proxy replace it?" (2026-05-09)**
A: Both coexist in memory. Spring first creates the real object (constructor injection, all fields populated). Then a `BeanPostProcessor` detects proxy-worthy annotations, generates a CGLIB subclass, and registers the PROXY in the ApplicationContext — not the real object. The proxy holds a `target` reference to the real object plus an interceptor chain. The proxy itself has no business fields populated (no `webClient`, no `objectMapper`) — it never runs method bodies. On method call: proxy runs interceptors → interceptors decide to proceed → proxy calls `target.method()` → real object's code runs with its real dependencies. All injection points receive the proxy, so every external call goes through the interceptor chain.

**Q120: "How does a private fallback method work with Resilience4j's circuit breaker?" (2026-05-09)**
A: The `CircuitBreakerAspect` uses Java reflection to invoke the fallback. When the circuit breaker catches a failure: (1) `targetClass.getDeclaredMethod("fallbackName", ...)` finds the method regardless of access modifier, (2) `method.setAccessible(true)` bypasses Java's private access check, (3) `method.invoke(targetObject, args)` calls it on the real object (not through the proxy). This is the same reflection mechanism Spring uses for `@Autowired` on private fields and Jackson uses for private field serialization. The fallback SHOULD be private — it's an implementation detail that no external code should call directly.

**Q121: "What is the circuit breaker pattern and what are its three states?" (2026-05-09)**
A: The circuit breaker prevents an application from repeatedly calling a failing downstream service. Three states: **CLOSED** (normal) — requests pass through, failures counted in a sliding window. When failure rate exceeds threshold → transitions to OPEN. **OPEN** — all requests immediately rejected with fallback, no calls to downstream. Saves resources and prevents cascade failures. After a wait duration → transitions to HALF-OPEN. **HALF-OPEN** — allows a limited number of test requests. If they succeed → CLOSED (service recovered). If they fail → OPEN (still broken). Named after electrical circuit breakers — same principle of cutting the circuit to prevent damage, then cautiously testing before restoring.

**Q122: "Why should a Resilience4j fallback return an error instead of zeroed/default data?" (2026-05-09)**
A: Returning fake data (price=$0.00, volume=0) is dangerous because: (1) the caller has no way to distinguish real data from fallback data, (2) fake data gets cached (Redis, browser) and served to users as real, (3) downstream decisions based on fake data cause business errors (stock-back rewards calculated on $0 price). Returning `Mono.error()` with a descriptive exception lets `GlobalExceptionHandler` send a proper error response (503 Service Unavailable), which clients can handle appropriately (retry later, show "data unavailable" message). Exception: if you have a last-known-good value in MongoDB, returning stale-but-real data with a "stale" flag IS acceptable.

---

### MongoDB Historical Price Storage (2026-05-09)

**15. Spring Data MongoDB — @Document vs JPA @Entity (2026-05-09)**

- `@Document(collection = "price_history")` maps a Java class to a MongoDB collection (like `@Entity` + `@Table` in JPA). MongoDB stores BSON documents (binary JSON), not rows.
- `@Id` on a `String` field maps to MongoDB's `_id`. If you use `String`, Spring auto-generates an `ObjectId` hex string. If you use `Long`, you'd need manual ID generation (MongoDB has no auto-increment like SQL `SERIAL`).
- `@Indexed(expireAfter = "90d")` creates a TTL index on `fetchedAt`. MongoDB's background thread checks every 60 seconds and deletes documents whose indexed field is older than the TTL. This is automatic garbage collection — no cron job or scheduled task needed.
- `MongoRepository<PriceHistory, String>` gives you `save()`, `findById()`, `findAll()` plus Spring Data derived query methods — same interface style as `JpaRepository` but targeting MongoDB.
- Key difference from JPA: no schema enforcement (MongoDB is schemaless), no `ALTER TABLE` migrations. Adding a new field to the document class just works — old documents without the field get `null` when read.

**16. CompletableFuture.runAsync() — Fire-and-Forget Side Effects (2026-05-09)**

- `CompletableFuture.runAsync(() -> {...})` submits a `Runnable` to the common `ForkJoinPool` and returns immediately. The caller doesn't wait for completion.
- Use case: saving `PriceHistory` to MongoDB on cache miss. The price response goes back to the client immediately; the DB write happens asynchronously. If MongoDB is slow (100ms), the API response isn't delayed by it.
- `.whenComplete((result, ex) -> {...})` attaches a callback for logging success/failure — doesn't block the caller.
- **Caveat**: if the application shuts down while the async task is running, it may not complete. For critical writes, use `@Async` with a `TaskExecutor` that has graceful shutdown, or write synchronously. For analytics/logging snapshots, fire-and-forget is acceptable.
- History: `CompletableFuture` was introduced in Java 8 (2014) as a composable alternative to raw `Thread` + `Runnable` or `ExecutorService.submit()`. Before Java 8, fire-and-forget required manual thread pool management.

---

### Company Health Score — Composite Signal Algorithm (2026-05-10)

**17. BigDecimal.compareTo() — Never Use == for Financial Comparisons (2026-05-10)**

- `BigDecimal` is an object — `==` compares references, not values. `new BigDecimal("1.0") == new BigDecimal("1.0")` is `false`.
- Even `.equals()` has a trap: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false` because `.equals()` compares both value AND scale.
- `.compareTo()` compares only the numeric value: returns -1 (less), 0 (equal), or +1 (greater). `compareTo(BigDecimal.ZERO)` is the standard idiom for checking positive/negative.
- History: `BigDecimal` was in Java since 1.1 (1997), but the `compareTo` vs `equals` gotcha has caused production bugs for decades. The Javadoc explicitly warns about it, but many developers still fall into the trap.

**18. LinkedHashMap — Insertion-Ordered Map (2026-05-10)**

- `HashMap` makes no guarantees about iteration order — it can change between runs or JVM versions. `LinkedHashMap` maintains a doubly-linked list of entries alongside the hash table, guaranteeing iteration in insertion order.
- Used for the `signals` map in health score: signals appear in the order they were computed (priceChange → changePercent → weeklyTrend → volume), making the API response predictable and readable.
- Overhead: ~30% more memory per entry (two extra pointers for the linked list). Negligible for small maps, but matters at scale (millions of entries). For API response DTOs, always prefer readability over micro-optimization.

---

### SSE — Server-Sent Events for Live Streaming (2026-05-10)

**19. SSE vs WebSocket vs Polling (2026-05-10)**

- **Polling** (pre-2009): client sends repeated HTTP requests on a timer. Simple but wasteful — most responses return "no change." Latency equals the polling interval. Each request carries full HTTP overhead (headers, TCP handshake if not keep-alive).
- **SSE** (HTML5, 2009): client opens one long-lived HTTP connection (`Content-Type: text/event-stream`). Server pushes data whenever it wants. Unidirectional (server → client). Built on plain HTTP — works through proxies, load balancers, firewalls with no special configuration. Browsers auto-reconnect on disconnect. Format is simple text: `data: {...}\n\n`.
- **WebSocket** (RFC 6455, 2011): full-duplex (both directions). Starts as HTTP then upgrades to `ws://` protocol via handshake. Needed when the client also sends frequent messages (chat apps, collaborative editing, gaming). More complex infrastructure — some corporate proxies and firewalls block the upgrade.
- **Decision rule**: server pushes data, client just listens → SSE. Both sides send frequently → WebSocket. Simple, low-frequency checks → Polling.

**20. Flux.interval() + concatMap — Ordered Periodic Emission (2026-05-10)**

- `Flux.interval(Duration.ofSeconds(5))` emits `0L, 1L, 2L, ...` every 5 seconds on Reactor's `Schedulers.parallel()` timer thread.
- `.concatMap(tick -> alphaVantageClient.getStockQuote(symbol))` subscribes to each inner `Mono` sequentially — waits for tick N's response before subscribing to tick N+1. This guarantees price events arrive in order.
- `.flatMap()` would subscribe to all inner Monos eagerly (concurrently). If tick 6's API call takes 8 seconds and tick 7's takes 2 seconds, flatMap delivers tick 7 first. For a price stream, out-of-order data is confusing.
- `.onErrorResume(e -> Mono.empty())` skips individual tick failures (API error, rate limit) without killing the entire stream. The next tick will try again.
- `.distinctUntilChanged(StockPriceResponse::price)` suppresses consecutive identical prices. During off-hours (market closed), every tick returns the same price — no point pushing duplicate data. Uses the key selector overload because `StockPriceResponse` has a `cachedAt` field that changes every time, making the default `.equals()` always false.

**21. ServerSentEvent<T> Wrapper — Standard SSE Fields (2026-05-10)**

- Returning raw `Flux<T>` with `text/event-stream` produces: `data: {json}\n\n` for each element. Works but limited.
- `ServerSentEvent<T>` gives control over SSE protocol fields: `id` (for client reconnect — browser sends `Last-Event-ID` header), `event` (event type for `EventSource.addEventListener()`), `retry` (reconnect interval in ms), `comment` (keep-alive ping without triggering event handler).
- Spring auto-serializes `.data()` to JSON. The builder pattern: `ServerSentEvent.<StockPriceResponse>builder().data(price).build()`.
- The `.<StockPriceResponse>builder()` syntax is a "type witness" — tells Java's type inference what `T` is for the generic builder.

**22. Reactive End-to-End — No .block() in SSE (2026-05-10)**

- Synchronous endpoints (getPrice, getHealthScore) use `.block()` at the service boundary to bridge reactive Mono into MVC's synchronous return.
- SSE endpoint returns `Flux<ServerSentEvent<T>>` directly — Spring's reactive support writes each element to the HTTP response as it arrives, using chunked transfer encoding. No thread is blocked waiting.
- This is the key architectural benefit: one SSE connection per client, but no thread pinned per connection. Reactor's event loop handles all connections on a small thread pool (default = CPU cores).

---

### Interview Questions Discussed (2026-05-09 — 2026-05-10)

**Q123: "What is a MongoDB TTL index and when would you use one?" (2026-05-09)**
A: A TTL (Time To Live) index is a special single-field index on a date field. MongoDB's background thread checks every 60 seconds and deletes documents whose indexed field value is older than the specified duration. Use it for: session tokens, temporary logs, analytics data, or price history snapshots that become irrelevant after a period. Example: `@Indexed(expireAfter = "90d")` on a `fetchedAt` field auto-deletes documents after 90 days. Advantage over application-level cleanup: no scheduled job to maintain, no bulk deletes that spike load. Limitation: granularity is ~60 seconds (the background thread's check interval), so it's not suitable for precise expiration.

**Q124: "What is CompletableFuture.runAsync() and when is it appropriate for fire-and-forget?" (2026-05-09)**
A: `CompletableFuture.runAsync(Runnable)` submits a task to the ForkJoinPool common pool and returns immediately — the caller doesn't wait for completion. Appropriate for non-critical side effects where the main operation should not be delayed: logging to analytics DB, saving snapshots, sending non-essential notifications. NOT appropriate for critical writes (payment records, order creation) where data loss on shutdown is unacceptable. For those, use synchronous calls or an `@Async` method with a graceful-shutdown `TaskExecutor`. Always attach `.whenComplete()` for error logging — otherwise failures are silently swallowed.

**Q125: "Why use BigDecimal.compareTo() instead of equals() for financial comparisons?" (2026-05-10)**
A: `BigDecimal.equals()` compares both value AND scale: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false` because scales differ (1 vs 2). `compareTo()` compares only the numeric value, returning -1/0/+1. For financial code (prices, balances, totals), `compareTo` is always correct. The `==` operator is even worse — it compares object references, not values. Standard idiom: `amount.compareTo(BigDecimal.ZERO) > 0` to check positive. This is a notorious Java gotcha — the Javadoc warns about it, but it causes production bugs regularly.

**Q126: "What is the difference between concatMap and flatMap in reactive streams?" (2026-05-10)**
A: Both transform each element into a Publisher and flatten the results. `flatMap` subscribes to all inner publishers eagerly (concurrently) — results can arrive out of order. `concatMap` subscribes to each inner publisher sequentially — waits for the current one to complete before subscribing to the next. Use `flatMap` when order doesn't matter and you want maximum throughput (parallel API calls). Use `concatMap` when ordering matters (price stream, event log) or when the downstream can't handle concurrent requests (rate-limited API). There's also `switchMap` — on each new element, cancels the previous inner publisher and subscribes to the new one. Used for "latest value wins" scenarios (search-as-you-type).

**Q127: "Explain SSE vs WebSocket. When would you choose each?" (2026-05-10)**
A: SSE (Server-Sent Events) is a unidirectional protocol — server pushes to client over a standard HTTP connection (`text/event-stream`). WebSocket is bidirectional — both sides send messages over a single TCP connection after an HTTP upgrade handshake. Choose SSE when: server pushes data, client just listens (stock prices, notifications, live scores). SSE advantages: works through all HTTP proxies/firewalls, auto-reconnects with `Last-Event-ID`, simpler server implementation, uses standard HTTP caching/compression. Choose WebSocket when: both sides send frequently (chat, collaborative editing, multiplayer games). WebSocket advantages: lower per-message overhead (no HTTP headers), true bidirectional communication. SSE limitation: browsers allow ~6 concurrent SSE connections per domain (HTTP/1.1 limit, not present in HTTP/2).

---

## Phase 4: Complete Debug-Mode Walkthrough — What Happens Step by Step

This section traces exactly what happens in the market-data module from application startup through each API call, like stepping through a debugger.

### BEFORE THE APPLICATION STARTS — Build & Wiring

**1. Gradle Resolves Dependencies (build time)**

When you run `./gradlew build`, Gradle reads `market-data/build.gradle` and resolves:
- `spring-boot-starter-webflux` → pulls in Reactor Netty (non-blocking HTTP client), `spring-webflux` (reactive web layer), `reactor-core` (Mono/Flux types)
- `spring-boot-starter-data-mongodb` → pulls in MongoDB Java driver, Spring Data MongoDB (`MongoRepository`, `@Document` support)
- `spring-boot-starter-data-redis` → pulls in Lettuce (Redis client), `StringRedisTemplate`
- `resilience4j-spring-boot3` + `resilience4j-reactor` → pulls in Resilience4j aspects (@CircuitBreaker, @Retry, @RateLimiter)
- `spring-boot-starter-aop` → pulls in `aspectjweaver` which activates `AopAutoConfiguration` → enables `@Aspect` scanning for Resilience4j

The `app` module depends on `market-data`, so the fat JAR includes all these classes.

**2. Spring Boot Starts — Auto-Configuration (startup)**

`EquityCartApplication.main()` calls `SpringApplication.run()`. Spring Boot's auto-configuration detects classes on the classpath and creates beans:

```
Classpath Detection → Auto-Configuration → Bean Creation:

WebClient on classpath        → [no auto-config — we provide our own @Bean]
MongoClient on classpath      → MongoAutoConfiguration creates MongoClient bean → connects to mongodb://localhost:27017/equitycart
StringRedisTemplate on classpath → RedisAutoConfiguration creates StringRedisTemplate bean → connects to localhost:6379
aspectjweaver on classpath    → AopAutoConfiguration activates → enables @Aspect scanning
Resilience4j on classpath     → CircuitBreakerAutoConfiguration reads YAML → creates CircuitBreakerRegistry, RetryRegistry, RateLimiterRegistry
```

**3. Bean Creation Order for Market-Data Module**

Spring creates beans in dependency order (constructor injection forces this):

```
Step 1: WebClientConfig.webClient()
  → @Configuration class processed
  → webClient() @Bean method called
  → HttpClient.create() with connect timeout (5s) + response timeout (10s)
  → WebClient.builder().baseUrl("https://www.alphavantage.co").build()
  → WebClient bean registered in ApplicationContext

Step 2: ObjectMapper (already exists — Spring Boot auto-creates one)

Step 3: AlphaVantageClient (real object)
  → @Component detected by component scan
  → Constructor injection: (WebClient, ObjectMapper) ← both already exist
  → @Value("${alphaVantage.api-key}") → Spring reads YAML → injects API key string into field
  → Real object fully constructed with all dependencies

Step 4: AlphaVantageClient (CGLIB proxy wraps real object)
  → BeanPostProcessor inspects real AlphaVantageClient
  → Finds @Retry, @CircuitBreaker, @RateLimiter on getStockQuote()
  → CGLIB generates subclass: AlphaVantageClient$$SpringCGLIB
  → Proxy stores: target (real object) + interceptor chain [RetryAspect, CircuitBreakerAspect, RateLimiterAspect]
  → PROXY registered in ApplicationContext (replaces real object as the injectable bean)

Step 5: PriceHistoryRepository
  → Spring Data MongoDB detects MongoRepository interface
  → Creates a dynamic proxy implementing findBySymbolAndFetchedAtBetween() etc.
  → Registers proxy as bean

Step 6: StringRedisTemplate (already exists — auto-configured)

Step 7: MarketDataServiceImpl
  → @Service detected
  → Constructor injection: (AlphaVantageClient [PROXY], StringRedisTemplate, ObjectMapper, PriceHistoryRepository [PROXY])
  → All 4 dependencies already exist → bean created

Step 8: MarketDataController
  → @RestController detected
  → Constructor injection: (MarketDataService) ← MarketDataServiceImpl satisfies this
  → Bean created, request mappings registered with DispatcherServlet
```

**4. Resilience4j Instances Created from YAML**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      alphaVantage:                    # ← name must match @CircuitBreaker(name = "alphaVantage")
        sliding-window-size: 10        # track last 10 calls
        failure-rate-threshold: 50     # open if 50%+ fail
        wait-duration-in-open-state: 30s
  retry:
    instances:
      alphaVantage:
        max-attempts: 3
        wait-duration: 2s
  ratelimiter:
    instances:
      alphaVantage:
        limit-for-period: 5           # max 5 calls per 60s window
        limit-refresh-period: 60s
        timeout-duration: 0s          # fail immediately if limit reached
```

Spring reads this YAML, creates named instances in CircuitBreakerRegistry/RetryRegistry/RateLimiterRegistry. The `@CircuitBreaker(name = "alphaVantage")` annotation matches the YAML key exactly.

**5. MongoDB Connection Established**

```
MongoClient created → connects to localhost:27017
→ mode=SINGLE (standalone, not replica set)
→ no authentication (default for local dev)
→ database "equitycart" selected (from URI)
→ collection "price_history" auto-created on first write
→ TTL index on fetchedAt created (if not exists) — MongoDB background thread will auto-delete documents older than 90 days
```

**6. SecurityFilterChain Loaded**

```
SecurityConfig's SecurityFilterChain bean:
→ /api/auth/** → permitAll
→ /api/admin/** → ADMIN only
→ POST /api/products/** → ADMIN or SELLER
→ anyRequest().authenticated() ← this covers /api/market-data/**
→ JwtAuthFilter registered BEFORE UsernamePasswordAuthenticationFilter
```

All market-data endpoints require a valid JWT token.

### AFTER APPLICATION STARTS — Request Flows

#### Flow 1: GET /api/market-data/price/AAPL (Cache MISS)

```
CLIENT → sends HTTP GET with Authorization: Bearer <JWT>

──── Servlet Container (Tomcat) ────
1. Tomcat receives request on port 8080
2. Allocates a thread from the thread pool (nio-8080-exec-N)
3. Passes request to Spring's FilterChain

──── Security Filter Chain ────
4. JwtAuthFilter.doFilterInternal() runs:
   → Extracts "Bearer eyJhbG..." from Authorization header
   → Calls jwtService.validateToken(token) → parses JWT, verifies HMAC signature, checks expiry
   → Extracts userId (subject) and roles (custom claim) from JWT payload
   → Creates UsernamePasswordAuthenticationToken(userId, null, [ROLE_CUSTOMER, ROLE_ADMIN])
   → Sets SecurityContextHolder.getContext().setAuthentication(authToken)
   → Calls filterChain.doFilter() → passes to next filter

5. AuthorizationFilter runs:
   → Checks: does /api/market-data/price/AAPL match any specific rule? No.
   → Falls to anyRequest().authenticated() → is SecurityContext populated? Yes → ALLOWED

──── DispatcherServlet ────
6. DispatcherServlet.doDispatch():
   → HandlerMapping matches GET /api/market-data/price/{symbol} → MarketDataController.getPrice()
   → HandlerAdapter invokes the method

──── Controller ────
7. MarketDataController.getPrice("AAPL"):
   → log.info("GET /price/AAPL — single price lookup")
   → Calls marketDataService.getPrice("AAPL")

──── Service ────
8. MarketDataServiceImpl.getPrice("AAPL"):
   → Builds Redis key: "price:AAPL"
   → Calls redisTemplate.opsForValue().get("price:AAPL")
   → Redis returns null → CACHE MISS
   → log.info("Cache MISS for symbol: AAPL — calling Alpha Vantage API")

──── Alpha Vantage Client (through CGLIB proxy) ────
9. Calls alphaVantageClient.getStockQuote("AAPL")
   → This hits the CGLIB PROXY, not the real object
   → Proxy's interceptor chain executes:

   Layer 1 — @Retry (outermost):
     → RetryAspect checks: attempt 1 of 3
     → Proceeds to next layer

   Layer 2 — @CircuitBreaker:
     → CircuitBreakerAspect checks: state = CLOSED, failure count < threshold
     → Proceeds to next layer

   Layer 3 — @RateLimiter (innermost):
     → RateLimiterAspect checks: 1 of 5 permits used this 60s window
     → Permits request → proceeds to real method

   Layer 4 — Real AlphaVantageClient.getStockQuote("AAPL"):
     → log.info("Fetching stock quote for symbol: AAPL")
     → Builds reactive pipeline (NO HTTP call yet — lazy assembly):
        webClient.get()
          .uri("/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=YOUR_KEY")
          .retrieve()
          .bodyToMono(String.class)
          .flatMap(parse JSON → StockQuote)
     → Returns Mono<StockQuote> (still no HTTP call — just a blueprint)

──── Back in Service (.block()) ────
10. stockQuoteMono.map(this::toResponse).block()
    → .block() subscribes to the Mono → triggers the actual HTTP call
    → Reactor Netty sends GET https://www.alphavantage.co/query?...
    → Netty event loop thread handles the I/O (non-blocking)
    → Response arrives: {"Global Quote": {"01. symbol": "AAPL", "05. price": "293.32", ...}}
    → .flatMap() runs: objectMapper.readTree(responseBody)
    → JsonNode tree navigated: rootNode.path("Global Quote").path("05. price").asText() → "293.32"
    → StockQuote record created: (symbol="AAPL", price=293.32, change=5.88, ...)
    → .map(this::toResponse) converts to StockPriceResponse (adds cachedAt = Instant.now())
    → .block() unwraps the Mono and returns the StockPriceResponse to the calling thread

──── Redis Cache Write ────
11. objectMapper.writeValueAsString(stockPriceResponse) → JSON string
12. redisTemplate.opsForValue().set("price:AAPL", json, Duration.ofSeconds(30))
    → Atomic SET with TTL: key "price:AAPL" expires in 30 seconds
    → log.info("Cached price for symbol: AAPL (TTL: PT30S)")

──── MongoDB Async Write ────
13. CompletableFuture.runAsync(() → { ... })
    → Submits to ForkJoinPool common pool → returns immediately (fire-and-forget)
    → On the pool thread (later):
      → PriceHistory.builder().symbol("AAPL").price(293.32)...build()
      → priceHistoryRepository.save(priceHistory) → MongoDB driver inserts document
      → .whenComplete() → log.info("Saved price history for symbol: AAPL")
    → Main thread does NOT wait for this

──── Response ────
14. StockPriceResponse returned to controller
15. Spring serializes to JSON via Jackson
16. Response: 200 OK + {"symbol":"AAPL","price":293.32,...,"cachedAt":"2026-05-10T12:15:15Z"}
```

#### Flow 2: GET /api/market-data/price/AAPL (Cache HIT — within 30s)

```
Steps 1-8 same as above, then:

8b. redisTemplate.opsForValue().get("price:AAPL")
    → Redis returns JSON string → NOT null → CACHE HIT
    → log.debug("Cache HIT for symbol: AAPL")
    → objectMapper.readValue(cachedData, StockPriceResponse.class) → deserialize
    → Returns StockPriceResponse directly
    → NO call to Alpha Vantage
    → NO MongoDB write
    → Response: 200 OK + same JSON (from cache)
```

#### Flow 3: GET /api/market-data/health/AAPL

```
Steps 1-7 same (JWT validation, authorization, routing)

──── Controller ────
7. MarketDataController.getHealthScore("AAPL"):
   → Calls marketDataService.getHealthScore("AAPL")

──── Service ────
8. MarketDataServiceImpl.getHealthScore("AAPL"):
   → log.info("Computing health score for symbol: AAPL")

   Sub-call 1: this.getPrice("AAPL")
     → Goes through the full getPrice() flow (cache check → API call or cache hit)
     → Returns StockPriceResponse(price=293.32, change=+5.88, changePercent="2.0456%", volume=52692761)

   Signal 1 — priceChange:
     → change.compareTo(BigDecimal.ZERO) → 5.88 > 0 → changeSign = 1
     → score = 50 + 15 = 65
     → signals.put("priceChange", "POSITIVE (+15)")

   Signal 2 — changePercent:
     → "2.0456%".replace("%","") → "2.0456" → Double.parseDouble → 2.0456
     → Math.abs(2.0456) = 2.0456 → > 2.0 → SIGNIFICANT
     → changeSign > 0 → bonus = +10
     → score = 65 + 10 = 75
     → signals.put("changePercent", "SIGNIFICANT (+10)")

   Sub-call 2: this.getHistory("AAPL", 7)
     → priceHistoryRepository.findBySymbolAndFetchedAtBetween("AAPL", 7_days_ago, now)
     → MongoDB returns List<PriceHistory> (documents saved from previous getPrice() calls)

   Signal 3 — weeklyTrend:
     → history.size() > 1 → compare earliest vs latest price
     → If same day/same price → FLAT (+0)
     → If latest > earliest → UPTREND (+15)
     → score stays 75 or becomes 90

   Signal 4 — volume:
     → 52,692,761 > 1,000,000 → HIGH
     → score += 10 → score = 85 (with FLAT trend)
     → signals.put("volume", "HIGH (+10)")

   Clamp: Math.max(0, Math.min(100, 85)) → 85
   → log.info("Health score for AAPL: 85 — signals: {priceChange=POSITIVE (+15), ...}")
   → Returns HealthScoreResponse(symbol="AAPL", score=85, signals={...}, calculatedAt=now)

──── Response ────
9. 200 OK + {"symbol":"AAPL","score":85,"signals":{...},"calculatedAt":"..."}
```

#### Flow 4: GET /api/market-data/stream/AAPL (SSE)

```
Steps 1-6 same (JWT, authorization)

──── Controller ────
7. MarketDataController.getStreamPrice("AAPL"):
   → log.info("GET /stream/AAPL — opening SSE stream")
   → Calls marketDataService.streamPrice("AAPL")
   → Returns Flux<ServerSentEvent<StockPriceResponse>>
   → Spring detects produces = "text/event-stream"
   → Sets response Content-Type: text/event-stream
   → Does NOT close the connection — keeps it open

──── Reactive Stream (no .block() — fully async) ────
8. Flux.interval(Duration.ofSeconds(5))
   → Reactor scheduler thread emits: 0L at t=0s, 1L at t=5s, 2L at t=10s, ...

   Tick 0 (t=0s):
   → .concatMap(tick → alphaVantageClient.getStockQuote("AAPL"))
   → Hits PROXY → Retry → CircuitBreaker → RateLimiter → real getStockQuote()
   → WebClient calls Alpha Vantage API (non-blocking, Netty event loop)
   → Response arrives → parsed to StockQuote → .map(toResponse) → StockPriceResponse
   → .distinctUntilChanged(StockPriceResponse::price) → first emission → always passes
   → .map(price → ServerSentEvent.builder().data(price).build())
   → Spring writes to HTTP response: "data:{\"symbol\":\"AAPL\",\"price\":293.32,...}\n\n"
   → Client receives event

   Tick 1 (t=5s):
   → Same flow → gets price 293.32
   → .distinctUntilChanged(price) → same price as tick 0 → SUPPRESSED (not emitted)
   → Client receives nothing

   Tick 2 (t=10s):
   → Same flow → gets price 294.10 (price changed!)
   → .distinctUntilChanged(price) → different from last emitted → PASSES
   → Client receives: "data:{\"symbol\":\"AAPL\",\"price\":294.10,...}\n\n"

   On API error (tick N):
   → .onErrorResume(e → Mono.empty()) → error swallowed, stream continues
   → Next tick (N+1) tries again

──── Connection stays open until: ────
   → Client disconnects (closes browser tab, Ctrl+C in curl)
   → Server shuts down
   → No timeout (stream runs indefinitely)
```

#### Flow 5: DELETE /api/market-data/price/AAPL/cache (ADMIN only)

```
Steps 1-5 same, then:

5b. AuthorizationFilter:
    → @PreAuthorize("hasRole('ADMIN')") on the method
    → Checks SecurityContext: does user have ROLE_ADMIN?
    → If YES → proceeds
    → If NO → throws AccessDeniedException → 403 Forbidden

──── Controller ────
6. MarketDataController.clearPriceCache("AAPL"):
   → log.info("DELETE /price/AAPL/cache — evicting price cache (ADMIN)")
   → Calls marketDataService.evictPriceCache("AAPL")

──── Service ────
7. MarketDataServiceImpl.evictPriceCache("AAPL"):
   → redisTemplate.delete("price:AAPL") → removes key from Redis
   → log.info("Evicted price cache for symbol: AAPL")

──── Response ────
8. 204 No Content (void return + @ResponseStatus(NO_CONTENT))
   → Next getPrice("AAPL") call will be a cache MISS → fresh API call
```

#### Flow 6: GET /api/market-data/history/AAPL?days=7

```
Steps 1-6 same, then:

──── Controller ────
7. MarketDataController.getHistoricalData("AAPL", 7):
   → @RequestParam(defaultValue = "7") → if client omits ?days=, defaults to 7
   → log.info("GET /history/AAPL — last 7 days")
   → Calls marketDataService.getHistory("AAPL", 7)

──── Service ────
8. MarketDataServiceImpl.getHistory("AAPL", 7):
   → Calculates time range: Instant.now().minus(7, ChronoUnit.DAYS) to Instant.now()
   → priceHistoryRepository.findBySymbolAndFetchedAtBetween("AAPL", startTime, endTime)
   → Spring Data MongoDB converts method name to MongoDB query:
     { "symbol": "AAPL", "fetchedAt": { "$gte": startTime, "$lte": endTime } }
   → MongoDB returns matching documents sorted by fetchedAt
   → Returns List<PriceHistory>

──── Response ────
9. 200 OK + [{"id":"...","symbol":"AAPL","price":293.32,"fetchedAt":"2026-05-10T12:15:15Z"}, ...]
   → Note: returns raw PriceHistory entities (including MongoDB _id field)
```

### RESILIENCE SCENARIOS — What Happens When Things Fail

**Scenario A: Alpha Vantage returns 500 (server error)**
```
Attempt 1 → WebClient gets 500 → Mono signals error
@Retry catches → waits 2 seconds → Attempt 2
Attempt 2 → 500 again → waits 2 seconds → Attempt 3
Attempt 3 → 500 again → all retries exhausted
@CircuitBreaker catches → records failure in sliding window
If failure rate < 50% → error propagates to fallback
getStockQuoteFallback() → returns Mono.error("Unable to fetch stock quote for AAPL")
Service catches error → returns 500 to client
```

**Scenario B: Circuit breaker trips OPEN (too many failures)**
```
After 5+ failures in last 10 calls (>50% failure rate):
@CircuitBreaker state → OPEN
Next request arrives → CircuitBreaker immediately rejects (no API call made)
Fallback invoked → Mono.error()
After 30 seconds → state → HALF_OPEN
Next request → allowed through as test
If succeeds → state → CLOSED (normal)
If fails → state → OPEN again (30s wait restarts)
```

**Scenario C: Rate limit exceeded (>5 calls in 60s)**
```
Call 6 within 60-second window:
@RateLimiter checks → 0 permits remaining
timeout-duration=0s → fail immediately (don't queue)
RequestNotPermitted exception thrown
@CircuitBreaker catches → records as failure
Fallback invoked → Mono.error()
After 60s window resets → 5 new permits available
```

---

## Phase 5: Portfolio & Stock-Back Engine ⏳

### Date: 2026-05-12

---

### Roadblocks & Issues Faced

**1. Spring proxy self-invocation and REQUIRES_NEW**

- Problem: `vestPendingRewards()` needs each reward vested in its own independent transaction (so one failure doesn't roll back the batch). But putting `@Transactional(REQUIRES_NEW)` on a private helper method called via `this.helper()` does nothing — the call bypasses the proxy.
- Fix: Extracted `VestingHelperImpl` as a separate `@Service` bean. When `PortfolioServiceImpl` calls `vestingHelper.vestSingleReward(reward)`, the call goes through the proxy, and REQUIRES_NEW is honoured.
- Lesson: Any `@Transactional` annotation only works when the call passes through the Spring AOP proxy. Self-invocation (`this.method()`) is a direct Java call — the proxy never sees it. Same problem affects `@Cacheable`, `@Async`, `@Retryable`.

**2. Optimistic locking retry — double-increment bug**

- Problem: Wrote `for (int i = 0; i < 3; i++) { try { ... } catch { i++; } }`. The for-loop already increments `i` at end of each iteration, so `i++` inside the catch made it increment twice — only 2 attempts instead of 3.
- Fix: Removed the `i++` from the catch block. The for-loop's own increment handles iteration.
- Lesson: When writing retry loops, the loop construct's own increment is sufficient. Don't manually increment inside the body.

**3. @EnableScheduling placement**

- Problem: Initially placed `@EnableScheduling` on the service class (`PortfolioServiceImpl`). While functional, this mixes infrastructure configuration with business logic.
- Fix: Moved to `EquityCartApplication` (the `@SpringBootApplication` main class). Since this is a monolith with one application context, the main class is the natural home for enablement annotations that affect the whole app.
- Lesson: `@Enable*` annotations are infrastructure configuration — they belong on `@Configuration` or `@SpringBootApplication` classes, not on `@Service` classes.

**4. Proxy commit-time exceptions escaping try-catch**

- Problem: `vestSingleReward()` has an internal try-catch(Exception) to keep rewards in PENDING on failure. Assumed this meant the method could never throw. Wrong.
- Root cause: `@Transactional(REQUIRES_NEW)` is implemented by the AOP proxy wrapping the method. Flow: (1) proxy begins transaction → (2) calls method body → (3) method returns normally (exception was caught) → (4) proxy commits → (5) Hibernate flushes dirty entities → (6) if flush/commit fails → proxy throws `DataAccessException` → propagates to caller.
- The internal try-catch only covers step (2). Steps (4-6) happen in the proxy layer, outside the method body.
- Fix: Added try-catch in `vestPendingRewards()` around the `vestingHelper` call to catch commit-time failures.
- Lesson: A try-catch inside a `@Transactional` method does NOT protect against commit-time failures. Hibernate can defer SQL flush to commit time (AUTO flush mode), so exceptions may surface only when the proxy commits.

**5. BigDecimal.ZERO as price for stock-back rewards**

- Problem: Questioned why `addOrUpdateHolding` is called with `BigDecimal.ZERO` as price for vested rewards.
- Resolution: Stock-back rewards are FREE shares — the user paid nothing. Zero price is correct because it reflects the actual cost basis. The weighted-average formula naturally handles this: `newAvg = (oldQty × oldAvg + newQty × 0) / (oldQty + newQty)` — the average drops (dilution). The `dollarValue` field on `StockBackReward` separately tracks the fair-market value at grant time for tax/reporting, but that's not a purchase price.
- Lesson: Distinguish between "what the user paid" (cost basis → Holding.averageBuyPrice) and "what the shares are worth" (fair market value → StockBackReward.dollarValue). They serve different purposes (brokerage vs IRS).

---

### Concepts Learned

**68. @Transactional — Class-Level vs Method-Level (2026-05-12)**

History: Before annotations (pre-Spring 2.0, ~2006), transaction boundaries were declared in XML (`<tx:advice>` blocks mapping method name patterns to attributes). Spring 2.0 introduced `@Transactional` annotations — same AOP proxy mechanism, more readable metadata.

Class-level `@Transactional` sets a default for ALL public methods in that class. Method-level overrides the class default for that specific method. Common patterns:
- Class-level `@Transactional` (REQUIRED) — most methods do read-write work
- Override with `@Transactional(readOnly = true)` on query-only methods
- Override with `@Transactional(propagation = REQUIRES_NEW)` for independent commits

Key: the annotation only works when the call comes through the Spring AOP proxy. Direct `this.method()` calls bypass it entirely.

**69. Transaction Propagation — All 7 Types (2026-05-12)**

| Propagation | Existing Tx? | No Tx? | Use case |
|---|---|---|---|
| REQUIRED (default) | Join it | Create new | 90% of cases |
| REQUIRES_NEW | Suspend it, create new | Create new | Independent commit (audit logs, per-item processing) |
| SUPPORTS | Join it | Run without | Optional participation (reads that benefit from repeatable-read if tx exists) |
| NOT_SUPPORTED | Suspend it | Run without | Long HTTP calls that shouldn't hold a DB connection |
| MANDATORY | Join it | THROW | Safety guard — "never call me without a transaction" |
| NEVER | THROW | Run without | Anti-transaction guard — "I must never be in a transaction" |
| NESTED | Savepoint | Create new | Partial rollback within outer tx — **JPA does not support this**, JDBC only |

"Suspend" means the outer transaction is paused (not rolled back) while the inner one runs. When the inner completes, the outer resumes.

REQUIRES_NEW use case in EquityCart: each reward in `vestPendingRewards` must vest in its own transaction. If reward #3 fails, rewards #1-2 are already committed and unaffected.

NESTED limitation: only works with `DataSourceTransactionManager` (raw JDBC), not `JpaTransactionManager`. Since we use JPA/Hibernate, NESTED is effectively unavailable.

**70. Spring Proxy Self-Invocation Problem (2026-05-12)**

When Spring creates a bean with `@Transactional` (or `@Cacheable`, `@Async`, etc.), it wraps the bean in a CGLIB proxy. External callers get a reference to the proxy, not the real object. But inside the real object, `this` points to the actual instance — not the proxy.

```
External call:   caller → proxy.method() → advice → real.method()  ✓ (proxy intercepts)
Self-invocation: real.method() → this.helper()                      ✗ (proxy bypassed)
```

Solutions:
1. Extract into separate @Service bean (what we did — cleanest)
2. Inject self: `@Lazy @Autowired private PortfolioService self;` then call `self.method()` (works but hacky)
3. Use `TransactionTemplate` programmatically (no proxy needed)
4. `AopContext.currentProxy()` — exposes the proxy but requires `@EnableAspectJAutoProxy(exposeProxy=true)` (fragile)

**71. @Transactional(readOnly=true) — What It Actually Does (2026-05-12)**

Two effects:
1. **Hibernate level**: Sets FlushMode to MANUAL. Hibernate skips dirty-checking on all managed entities in that session. Fewer CPU cycles, no accidental writes.
2. **JDBC/Driver level**: Passes a hint to the DB driver. PostgreSQL can use this to route queries to read-replicas (if configured), or optimize the transaction for read-only workload (no undo log entries needed).

Does NOT enforce immutability — you can still call `entity.setField()` and Hibernate won't flush it (because MANUAL flush), but if you force a flush, it would write. It's a performance hint, not a constraint.

**72. Optimistic Locking + Manual Retry Pattern (2026-05-12)**

`@Version` on an entity field makes Hibernate include `WHERE version = ?` in UPDATE statements. If another transaction incremented the version between your read and write, 0 rows are updated → Hibernate throws `OptimisticLockingFailureException` (Spring wraps the JPA `OptimisticLockException`).

Manual retry pattern:
```
for (int i = 0; i < maxRetries; i++) {
    try {
        // re-read entity (get fresh version), recalculate, save
        return result;
    } catch (OptimisticLockingFailureException e) {
        // log and retry — the loop naturally iterates
    }
}
throw new RuntimeException("Exhausted retries");
```

Critical: the re-read inside the loop ensures you get the latest version. Without it, you'd retry with stale data and fail every time.

Alternative: `@Retryable(OptimisticLockingFailureException.class, maxAttempts=3)` from spring-retry. We used manual loop because spring-retry isn't in our dependencies.

**73. Stock-Back Reward — Business Model (2026-05-12)**

Real-world analogy: "cash-back" credit cards give back money; "stock-back" gives fractional shares of stock.

Lifecycle:
1. User completes order → order-service publishes event
2. Portfolio-service grants reward: PENDING status, vesting date = now + 30 days
3. 30 days pass → scheduled job finds eligible rewards → credits shares to holding → VESTED
4. User now owns real shares — can sell for cash anytime

Why the delay (vesting period):
- If user returns the product within 30 days, reward is CANCELLED — no shares granted
- Without delay, you'd need to "claw back" already-vested shares (legally complex, operationally messy)

Two distinct dollar values:
- `Holding.averageBuyPrice`: what the user PAID per share (zero for free shares — correct)
- `StockBackReward.dollarValue`: what the shares were WORTH at grant time (for tax reporting — IRS treats stock compensation as income at fair market value)

**74. Proxy Commit-Time Exception Propagation (2026-05-12)**

With `@Transactional`, the AOP proxy wraps the method call:
```
proxy.vestSingleReward(reward):
  1. Begin transaction
  2. Call actual method body ← your try-catch lives here
  3. Method returns normally
  4. Proxy commits transaction
  5. Hibernate flushes (AUTO mode) — executes deferred SQL
  6. If flush fails → DataAccessException thrown BY THE PROXY
  7. This exception propagates to the CALLER — your catch never sees it
```

Implication: `stockBackRewardRepository.save(reward)` in step 2 may NOT immediately execute SQL. Hibernate's AUTO flush mode defers the actual INSERT/UPDATE until commit time (step 5). So `save()` succeeds in the method body, but the DB write fails later at commit.

This is why the caller (`vestPendingRewards`) also needs try-catch — the proxy can throw even when the method body doesn't.

---

### Interview Questions — Phase 5

**Q61: "Explain @Transactional propagation types. When would you use REQUIRES_NEW?" (2026-05-12)**
A: Propagation defines what happens when a transactional method is called while a transaction already exists. REQUIRED (default) joins existing or creates new. REQUIRES_NEW always creates independent — suspends any existing transaction. Use REQUIRES_NEW when you need independent commit/rollback: audit logs that must persist even if the main operation fails, batch processing where one item's failure shouldn't roll back others (vesting rewards), sending notifications. Key: "suspend" means paused, not rolled back.

**Q62: "What is the Spring proxy self-invocation problem?" (2026-05-12)**
A: When Spring wraps a bean with AOP (for @Transactional, @Cacheable, etc.), external callers go through the proxy. But inside the bean, `this.method()` calls the real object directly — the proxy is bypassed, so annotations on the called method have no effect. Solutions: extract to a separate @Service (cleanest), inject self with @Lazy, use programmatic TransactionTemplate, or expose the proxy via AopContext. This applies to all annotation-based AOP: @Transactional, @Cacheable, @Async, @Retryable.

**Q63: "How does optimistic locking work with @Version? How do you handle failures?" (2026-05-12)**
A: @Version makes Hibernate add `WHERE version = N` to UPDATEs. If another transaction incremented the version, 0 rows match → OptimisticLockingFailureException. Handle via retry: catch the exception, re-read the entity (fresh version), recalculate derived values (like weighted-average price), save again. Typically 3 retries with backoff. Optimistic locking is preferred over pessimistic when conflicts are rare — it doesn't hold DB locks, so throughput is higher under low contention.

**Q64: "What's the difference between @Transactional(readOnly=true) and no annotation?" (2026-05-12)**
A: readOnly=true gives two optimizations: (1) Hibernate sets FlushMode.MANUAL — skips dirty-checking on all managed entities (less CPU), (2) JDBC driver gets a hint — PostgreSQL can route to read-replicas or skip undo-log entries. It does NOT enforce immutability — if you force a flush, writes can still happen. Use on query-only methods to signal intent and gain performance. No annotation means no transaction at all (or inherits class-level default).

**Q65: "Your scheduled job processes 1000 items. How do you ensure one failure doesn't kill the batch?" (2026-05-12)**
A: Process each item in its own REQUIRES_NEW transaction. This way, each commit/rollback is independent. Implementation: extract per-item logic into a separate @Service with @Transactional(REQUIRES_NEW) — solves the self-invocation problem since the call goes through the proxy. The outer method (with @Scheduled) iterates and calls the helper, catching exceptions per item so the loop continues. Alternative: use TransactionTemplate with PROPAGATION_REQUIRES_NEW programmatically inside the loop.

**75. Facade Design Pattern — DTO Mapping Layer (2026-05-12)**

History: The Facade pattern comes from the Gang of Four book (1994). The name is borrowed from architecture — a building facade is the front-facing exterior that hides structural complexity behind it. In Java enterprise, it became especially popular with EJB Session Facades (early 2000s), where a session bean facade simplified access to multiple entity beans and reduced network round-trips from remote clients.

What it is: a simplified interface over a complex subsystem. The client (controller) calls one method and gets back a ready-to-use result without knowing the internal structure.

In EquityCart:
```
Without facade:  Controller must know about PortfolioService + entity-to-DTO mapping logic
With facade:     Controller calls one method, gets back a ready-to-use DTO
```

Why not put the mapping in the service? The service works at the entity/primitive level — it's also called by internal callers (VestingHelper, future Kafka consumers) that don't want DTOs. The facade sits between controller and service, handling only DTO ↔ entity translation.

Why not put the mapping in the controller? Controllers should be thin — accepting requests and delegating. If a facade coordinates multiple services or maps complex entity graphs, that logic shouldn't live in the controller.

Where facades really shine: coordinating multiple services in one call. For example, a future `getPortfolioSummary()` could call PortfolioService for holdings, MarketDataService for current prices, and LedgerService for transaction history — returning one combined DTO. The controller still makes one call.

**76. Service Layer Boundary: Entities vs DTOs (2026-05-12)**

Design decision: service methods accept primitives/entities and return entities. DTOs are a controller-boundary concern handled by the facade.

This matters because the same service method can be called from multiple entry points:
- `addOrUpdateHolding(userId, ticker, qty, price)` is called by:
  1. The facade (from a controller POST request)
  2. VestingHelperImpl (internal vesting — no DTO involved)

If the service accepted `HoldingRequest` DTO, VestingHelper would have to construct a DTO just to call an internal method — coupling an internal caller to a REST-layer concern.

Rule of thumb: DTOs belong at system boundaries (REST, Kafka events). Internal service-to-service calls use entities and primitives.

**Q66: "What is the Facade pattern and when would you use it?" (2026-05-12)**
A: The Facade pattern (GoF) provides a simplified interface over a complex subsystem. In Spring, a common use is a DTO-mapping facade between controllers and services — the controller calls one facade method, gets back a response DTO, without knowing about entity mapping or multi-service coordination. It keeps controllers thin, services entity-focused, and centralizes mapping logic. Real value appears when one facade method orchestrates multiple services (e.g., portfolio + market data + ledger) into a single response.

**Q67: "Should your service layer accept and return DTOs or entities?" (2026-05-12)**
A: Services should work with entities and primitives — DTOs are a controller-boundary concern. Reason: the same service method may be called from multiple entry points (REST controller via facade, Kafka consumer, scheduler, other services). If the service requires DTOs, internal callers must construct REST-layer objects for internal calls — that's unnecessary coupling. Use a facade or mapper at the boundary to translate DTOs ↔ entities.

**77. Circular Dependency — @Lazy with Lombok's @RequiredArgsConstructor (2026-05-13)**

History: Circular dependencies have plagued DI containers since early Spring (2004). Spring originally resolved them silently via early reference exposure — bean A is partially created (constructor done, fields not yet set), its reference is exposed, then bean B is created and receives the partial A, then A finishes initialization. Spring Boot 2.6 (Nov 2021) changed the default to **prohibit** circular references (`spring.main.allow-circular-references=false`), forcing developers to design them out or use explicit `@Lazy`.

The problem in EquityCart:
```
PortfolioServiceImpl → injects VestingHelper
VestingHelperImpl    → injects PortfolioService
```
Spring can't construct either bean first — each needs the other to be ready.

The fix attempt that **didn't work**: putting `@Lazy` on the field while using `@RequiredArgsConstructor`:
```java
@RequiredArgsConstructor
public class VestingHelperImpl {
    @Lazy private final PortfolioService portfolioService;  // ← IGNORED
}
```

Why it fails: `@RequiredArgsConstructor` generates a constructor with all `final` fields as parameters. Spring Boot uses **constructor injection** when a constructor exists. `@Lazy` on the **field** is a field-level hint — but Spring never does field injection here because it sees a constructor. The annotation needs to be on the **constructor parameter** for Spring to inject a lazy proxy instead of the real bean. Lombok doesn't copy field annotations to constructor parameters by default.

Three valid fixes:
1. **Manual constructor** — write the constructor yourself, put `@Lazy` on the parameter:
   ```java
   public VestingHelperImpl(@Lazy PortfolioService portfolioService, ...) { }
   ```
2. **`lombok.config`** — add `lombok.copyableAnnotations += org.springframework.context.annotation.Lazy` so Lombok copies `@Lazy` to the generated constructor parameter.
3. **Field injection with `@Autowired`** — remove `@RequiredArgsConstructor`, use `@Lazy @Autowired` on the field. Spring does field injection directly (no constructor involved), so `@Lazy` is respected.

Chosen approach: Option 3. `@Lazy @Autowired` field injection on `portfolioService` in VestingHelperImpl.

Field injection vs constructor injection tradeoff:
- Constructor injection: explicit dependencies, immutable (`final`), fails fast, recommended by Spring team as default.
- Field injection: allows `@Lazy` without manual constructors, but dependencies are hidden, fields can't be `final`, harder to unit test (need reflection or `@InjectMocks`).
- Field injection with `@Lazy` is a legitimate escape hatch for circular dependencies — pragmatic fix when the alternative is restructuring the class graph.

How `@Lazy` proxy works at runtime:
```
1. Spring creates VestingHelperImpl
2. For the @Lazy field, Spring injects a CGLIB proxy (not the real PortfolioServiceImpl)
3. Proxy is a lightweight shell — no dependency resolution yet
4. First time vestSingleReward() calls portfolioService.addOrUpdateHolding()...
5. ...the proxy resolves the real PortfolioServiceImpl bean from the context
6. From that point on, the proxy delegates all calls to the real bean
```
The cycle is broken because VestingHelperImpl can finish construction without PortfolioServiceImpl being ready — it only needs the real bean at method-call time.

**Q68: "Your Spring Boot app fails to start with a circular dependency. How do you fix it?" (2026-05-13)**
A: First choice: redesign to remove the cycle (extract shared logic into a third bean). If the cycle is architecturally justified (like PortfolioService ↔ VestingHelper where each legitimately needs the other), use `@Lazy` to break the initialization deadlock. `@Lazy` injects a CGLIB proxy instead of the real bean — the real bean is resolved on first method call, by which time both beans are fully initialized. Important: with Lombok's `@RequiredArgsConstructor`, `@Lazy` on the field is silently ignored — it must be on the constructor parameter (write manually or configure `lombok.copyableAnnotations`). Alternative: use `@Autowired` field injection where `@Lazy` works directly on the field. Never set `spring.main.allow-circular-references=true` — it re-enables the old silent behavior that masks design problems.

**Q69: "What's the difference between field injection and constructor injection in Spring?" (2026-05-13)**
A: Constructor injection: dependencies are explicit (visible in constructor signature), can be `final` (immutable), fails fast at startup if a dependency is missing, easy to unit test (just pass mocks to constructor). Field injection (`@Autowired` on fields): dependencies are hidden, can't be `final`, requires reflection or `@InjectMocks` for testing. Spring team recommends constructor injection as default. Field injection is acceptable as an escape hatch — particularly with `@Lazy` to break circular dependencies, where writing a manual constructor just for one `@Lazy` parameter adds boilerplate.

**78. Trade Service — Sell-side Design Decisions (2026-05-13)**

Why `reduceHolding` doesn't take a sell price:

The sell price doesn't affect the cost basis of remaining shares. `averageBuyPrice` tracks what you **paid** for the shares you still hold. If you bought 10 AAPL at $150 avg and sell 3 at $200, the remaining 7 shares still cost $150 each. The sell price is irrelevant to the holding.

Where the sell price matters (outside reduceHolding):
1. P&L calculation: `profit = (sellPrice - averageBuyPrice) × qtySold`
2. Ledger entry: wallet credit = `sellPrice × qtySold`

This is clean separation of concerns: `reduceHolding` owns inventory (how many shares you have), the caller owns the financial side (at what price).

Zero-quantity holding handling: when a full sell reduces quantity to exactly zero, the holding is deleted from the database to avoid phantom positions showing up in portfolio views. The in-memory object is set to zero quantity before deletion so it can still be used for the response DTO mapping.

**79. Common Bug Pattern: Logging After Mutation (2026-05-13)**

Bug encountered: logging the "before" value of a field after calling the setter.

```java
holding.setQuantity(newQty);        // ← mutates the object
logger.info("qty {} → {}",
    holding.getQuantity(),           // ← now returns newQty (already mutated)
    newQty);                         // ← same value
```

Fix: capture the old value before mutation, or log before the setter call. This is the same pattern used in `addOrUpdateHolding` where `oldQty` and `oldAvg` are captured before modification.

General rule: any time you log a "before → after" transition, capture "before" on a separate line before performing the mutation.

**Q70: "How do you handle a sell that reduces a holding to zero?" (2026-05-13)**
A: Two approaches: (1) leave a zero-quantity row (simpler, but pollutes portfolio views and queries), or (2) delete the holding row when quantity hits zero. Option 2 is cleaner — avoids phantom holdings, keeps queries accurate, and the holding entity can still be returned (in-memory) for response mapping before deletion. For the delete, Hibernate's `remove()` marks the entity for deletion at flush time — you can still read its fields in the same transaction before the flush.

**Q71: "Why use a separate TradeService instead of putting trade logic in PortfolioService?" (2026-05-13)**
A: Single Responsibility. PortfolioService manages the portfolio domain — creating portfolios, managing holdings, granting rewards, scheduling vesting. TradeService handles the trade orchestration — parsing trade type, routing to the right portfolio operation, and coordinating with the ledger for financial recording. TradeService calls both PortfolioService and LedgerService within a single transaction — this coordination logic doesn't belong in PortfolioService.

**80. Double-Entry Bookkeeping in Trade Execution (2026-05-14)**

History: Double-entry bookkeeping dates to 1494 when Luca Pacioli published its rules — every financial event is recorded as two balanced entries (debit and credit). The system survived 500+ years because it's self-auditing: if debits don't equal credits, something is wrong.

In EquityCart, every trade creates a balanced DEBIT+CREDIT pair via LedgerService.recordTransaction(). The entries share a UUID transactionId for correlation.

Trade ledger entries (from the user's perspective):
```
BUY 10 AAPL @ $150:
  DEBIT  HOLDING_ASSET  $1500  (asset increases — user now owns shares)
  CREDIT CASH           $1500  (cash decreases — user spent money)

SELL 5 AAPL @ $180:
  DEBIT  CASH           $900   (cash increases — user received money)
  CREDIT HOLDING_ASSET  $900   (asset decreases — user gave up shares)
```

"Debit" doesn't mean "bad" and "credit" doesn't mean "good." In accounting: DEBIT means "this account gets bigger." For asset accounts (CASH, HOLDING_ASSET), a debit is an increase. For liability/income accounts, a debit is a decrease. The terminology is counterintuitive because most people encounter it from the bank's perspective (where your deposit is THEIR liability, so they "credit" your account).

Why record ledger entries alongside trades? The holding table tracks current state (how many shares you hold right now). The ledger tracks history (every financial event that happened). If someone asks "show me all the transactions for Order #42," the ledger has the answer. The holding can't — it's been updated many times and only shows the latest snapshot.

**81. Sell to Spend — Cross-Domain Atomic Transaction (2026-05-14)**

What is it: A payment method where the user sells stock from their portfolio to fund a pending order. Real-world examples: Robinhood's "Stock Round-Up," Revolut's auto-sell at checkout.

User journey:
```
1. User browses products → adds to cart
2. User places order → Order status: CREATED (placed, awaiting payment)
3. User calls POST /api/portfolio/sell-to-spend
4. System: sell shares + record ledger + confirm order → all atomic
5. Order status: CONFIRMED (paid)
```

Why CREATED state only: CREATED means "order placed, awaiting payment." CONFIRMED means already paid. SHIPPED/DELIVERED are post-payment. Accepting payment only makes sense before it's been paid.

Why require full payment (proceeds ≥ order total): Partial payment would require tracking `amountRemaining` on the order, supporting multiple payment rounds, multi-source reconciliation — that's a payments-platform domain, not a portfolio feature. For EquityCart: sell enough stock to cover the full order, or the request fails. Excess proceeds stay as CASH in the ledger.

Cross-module coordination:
```
SellToSpendServiceImpl orchestrates:
  1. OrderService.getOrderById()       → validate ownership + status
  2. PortfolioService.reduceHolding()  → sell the shares
  3. LedgerService.recordTransaction() → record the sale
  4. OrderService.updateOrderStatus()  → confirm the order
```

All four calls are wrapped in one `@Transactional`. If step 4 fails (e.g., invalid status transition), steps 2 and 3 roll back — the shares are restored, the ledger entry is not persisted. This is the monolith advantage: one database, one transaction manager, automatic atomicity.

Preview of what breaks in microservices: When portfolio, ledger, and order are separate services with separate databases, `@Transactional` can't span them. You'd need a **Saga pattern** — a sequence of local transactions with compensating actions:
```
Saga: sell shares → if ledger fails, re-add shares
      record ledger → if order fails, reverse ledger + re-add shares
      confirm order → done
```
Each service commits independently and publishes an event. If a downstream step fails, upstream services execute compensating transactions to undo their work. This is Phase 6 territory (Event-Driven Architecture).

Guard clause pattern: SellToSpendServiceImpl uses early-return validation (guard clauses) instead of nested if-else. Each validation failure throws immediately, so the happy path flows straight down without indentation. This is more readable than deeply nested conditionals.

**82. Cross-Module Dependencies in a Monolith (2026-05-14)**

portfolio/build.gradle now depends on both `:ledger-service` and `:order-service`. This creates cross-module coupling:
```
portfolio → ledger   (for recording financial events)
portfolio → order    (for confirming orders via sell-to-spend)
portfolio → commons  (shared exceptions, base entity)
```

In a monolith, this is fine — all modules compile and deploy together, share one database, and run in one JVM. The Gradle dependency graph is just an organizational boundary, not a deployment boundary.

In microservices, these would become API calls over HTTP/gRPC or async messages over Kafka. The portfolio service would call the order service's REST API or publish a "StockSold" event. Each service owns its own database, and consistency becomes eventual rather than immediate.

This is exactly the pain point that motivates microservices decomposition: as the dependency graph grows, coordinating transactions across modules gets harder. The monolith-first approach (build it coupled, then extract) lets you get the business logic right before tackling distributed systems complexity.

**Q72: "What is double-entry bookkeeping and why use it in a software system?" (2026-05-14)**
A: Every financial event creates two balanced entries — a debit and a credit — sharing a transaction ID. The system is self-auditing: sum of all debits must equal sum of all credits. In software, a ledger table with double-entry provides a complete, immutable audit trail of every financial event. The entity tables (holdings, orders) track current state; the ledger tracks the full history of how you got there. If there's a dispute ("did I really sell those shares?"), the ledger has the answer.

**Q73: "How does @Transactional work across multiple service calls in a monolith?" (2026-05-14)**
A: Spring's default transaction propagation is REQUIRED — if a transaction exists, join it; if not, create one. So when SellToSpendServiceImpl (annotated @Transactional) calls PortfolioService, LedgerService, and OrderService, all four participate in the same database transaction. If any service throws a RuntimeException, the entire transaction rolls back — the database never sees partial state. This works because all services share the same DataSource and TransactionManager within the Spring context.

**Q74: "What happens to this atomic transaction when you decompose to microservices?" (2026-05-14)**
A: It breaks. Each microservice has its own database, so @Transactional can't span them. You need the Saga pattern: a sequence of local transactions where each service commits independently and publishes an event. If a downstream step fails, upstream services execute compensating transactions (reverse their changes). Two variants: choreography (services react to events) and orchestration (a central coordinator directs the flow). Saga trades atomicity for availability and partition tolerance — eventual consistency instead of immediate consistency.

**83. Portfolio Analytics — Facade as Compositor (2026-05-14)**

The analytics endpoint demonstrates the facade pattern's most valuable use case: **composing data from multiple sources into a single rich response**.

```
Controller calls → facade.getAnalytics(userId)
Facade internally:
  1. portfolioService.getOrCreatePortfolio(userId) → holdings
  2. portfolioService.getRewards(userId)            → rewards
  3. Compute: cost basis per holding, total cost basis, portfolio weights
  4. Aggregate: reward counts by status, total shares/dollars
  5. Assemble: PortfolioAnalyticsResponse
Controller receives ← one combined DTO
```

Why this belongs in the facade (not a new service):
- No new domain logic — it's computation over existing data (sums, percentages, counts)
- No new repository calls — uses existing service methods
- No side effects — pure read + compute + assemble
- This IS the facade's purpose: "compose and map multiple service results into one response"

If this needed market-data integration (current price × quantity = live portfolio value), that would justify a new AnalyticsService because it introduces a new dependency and potentially network calls.

**84. BigDecimal.divide() Scale Pitfall (2026-05-14)**

Bug pattern: `costBasis.divide(totalCostBasis, RoundingMode.HALF_UP)` — this uses the dividend's scale (which could be 10+ from BigDecimal multiplication), producing results like `33.3333333300`.

The 2-argument `divide(BigDecimal, RoundingMode)` inherits scale from `this`. For clean API responses, use the 3-argument form with explicit scale:
```java
costBasis.multiply(BigDecimal.valueOf(100))
         .divide(totalCostBasis, 2, RoundingMode.HALF_UP)  // → 33.33
```

General rule: whenever you divide BigDecimals for display/response purposes, always specify explicit scale. The 2-arg form is fine for internal calculations where scale doesn't matter to the consumer.

**Q75: "What's the difference between BigDecimal's 2-arg and 3-arg divide?" (2026-05-14)**
A: `divide(divisor, roundingMode)` uses the dividend's scale — fine for internal math, but produces unpredictable decimal places in responses. `divide(divisor, scale, roundingMode)` lets you control output precision — use this whenever the result will be serialized (API responses, logs, reports). Without specifying scale OR roundingMode, divide throws ArithmeticException on non-terminating decimals (e.g., 1/3).

**Q76: "When should analytics logic live in a service vs the facade?" (2026-05-14)**
A: If it's pure computation over data from existing services (sums, percentages, counts) with no new dependencies or side effects — facade is appropriate. If it needs new repositories, external API calls, caching, or complex business rules — create a dedicated service. The litmus test: "does this introduce a new dependency that the facade shouldn't own?" If yes → service. If no → facade.

---

### Phase 5 Conclusion (2026-05-16)

**85. The Missing Link — Reward Granting (2026-05-16)**

Phase 5 built all the **infrastructure** for the stock-back reward loop, but left one critical step unimplemented: the **grant trigger**. Here's what exists vs what's missing:

**Implemented (Phase 5):**
- `StockBackReward` entity with PENDING/VESTED/CANCELLED lifecycle
- `grantReward()` in PortfolioService — creates a PENDING reward (idempotent via orderId check)
- `VestingHelper` + `@Scheduled` job — converts PENDING rewards to VESTED holdings
- `getRewards()` endpoint — reads a user's reward history
- Portfolio holdings, trades, analytics — all functional

**Not implemented (deferred to Phase 6):**
- The **trigger** that calls `grantReward()` when an order reaches DELIVERED status
- This requires a cross-module event chain: Order (DELIVERED event) → Product (look up brand) → BrandTickerMapping (get ticker + stockBackPercentage) → MarketData (get current price) → Portfolio (calculate shares, create PENDING reward)

**Why it was deferred:**
This is fundamentally a cross-module integration touching 4 bounded contexts (order, product, market-data, portfolio). In a monolith, you could wire it synchronously inside the order status transition — but Phase 6 introduces Kafka, and the roadmap explicitly lists "Order-Placed event → triggers stock-back reward calculation" as a Phase 6 deliverable. Building the synchronous version now would create throwaway code that Phase 6 immediately replaces with event-driven architecture.

The stock-back loop will be: `Order DELIVERED → OrderDeliveredEvent (Kafka) → RewardCalculationConsumer → grantReward(PENDING) → VestingJob runs → vestReward(VESTED) → addOrUpdateHolding`

**Q77: "Why can't the vesting job run without the grant step?" (2026-05-16)**
A: The `@Scheduled` vesting job queries `findByStatusAndVestingDateBefore(PENDING, now)`. Since no code ever creates a StockBackReward row with PENDING status, the query always returns empty. The job runs on schedule (every 60 seconds), finds nothing, and exits. It's correct but idle — waiting for the grant step to produce PENDING rewards for it to process.

**Q78: "What does the reward grant calculation look like?" (2026-05-16)**
A: When an order is delivered:
1. Look up each order item's product → brand → `BrandTickerMapping` → `tickerSymbol` + `stockBackPercentage`
2. Calculate reward dollar value: `orderItemTotal × stockBackPercentage / 100`
3. Fetch current stock price from market-data service (or Redis cache)
4. Calculate shares earned: `rewardDollarValue / currentStockPrice`
5. Create `StockBackReward` with status=PENDING, vestingDate = now + 30 days
6. After 30 days, the vesting job picks it up: status → VESTED, creates actual holding via `addOrUpdateHolding`

The idempotency check (`findByOrderId`) ensures one reward per order, even if the event is processed multiple times (at-least-once Kafka delivery).

---

## Phase 6: Event-Driven Architecture — Kafka

### Step 1: Kafka Infrastructure + Dependencies (2026-05-17)

**86. Apache Kafka — Core Concepts (2026-05-17)**

Kafka is a distributed commit log (not a traditional message queue). Built by LinkedIn in 2010 to replace N×N service-to-service spaghetti with a single event backbone. Key differences from RabbitMQ: messages are durable (retained for days, not deleted on consumption), replayable (reset offset to re-read), and pull-based (consumer controls read rate).

Core vocabulary:
- **Topic** — named stream of messages (like a DB table, but append-only)
- **Partition** — unit of parallelism within a topic. Messages with same key go to same partition (ordered). Across partitions, no ordering guarantee.
- **Offset** — sequential ID per message within a partition. Consumer tracks "I've read up to offset N" (committed offset).
- **Broker** — Kafka server. Stores partitions on disk, serves reads/writes.
- **Consumer Group** — instances sharing work: same group-id = queue (work divided); different group-ids = pub-sub (each gets all messages).
- **Serializer/Deserializer** — Kafka stores bytes. JsonSerializer converts Java→JSON bytes (producer); JsonDeserializer converts JSON bytes→Java (consumer). `__TypeId__` header carries class name.

See `kafka-learning.md` for full details with visualizations and failure scenarios.

**87. ZooKeeper → KRaft Mode (2026-05-17)**

ZooKeeper (Yahoo, 2006) was Kafka's external coordination service — handled controller election, topic metadata, broker liveness, partition leadership. Problem: two distributed systems to manage, ~200K partition ceiling (ZK memory limit), minutes-long failover (new controller must reload all state from ZK).

KRaft (Kafka 3.3+, 2022) = Kafka + Raft consensus. Metadata stored in internal `__cluster_metadata` topic. Controller elected via Raft protocol between Kafka nodes themselves. Result: one cluster instead of two, seconds-long failover (metadata already replicated locally), millions of partitions supported.

Our dev setup: single Docker container with `PROCESS_ROLES=broker,controller` (combined mode). Production: separate controller nodes (lightweight, Raft only) and broker nodes (heavy, storage).

**Q79: "What does auto-offset-reset=earliest actually do?" (2026-05-17)**
A: ONLY applies when a consumer has NO committed offset for a partition (first run or offset expired). `earliest` = start from offset 0 (don't miss any messages). `latest` = start from current end (skip all existing). After first commit, this setting is irrelevant — consumer always resumes from committed offset. For reward granting, `earliest` is mandatory — missing a delivered-order event means a permanently lost reward.

**Q80: "Why does JsonDeserializer need trusted.packages?" (2026-05-17)**
A: Security. JsonDeserializer reads `__TypeId__` header to determine which class to instantiate. Without a trust allowlist, an attacker writing to your topic could set `__TypeId__` to a dangerous class, triggering arbitrary code during deserialization (same vulnerability class as the 2015 Apache Commons Collections exploit that hit Jenkins/WebLogic). Setting `trusted.packages=com.equitycart.commons.event` means only classes from that package are deserializable.

**Q81: "Why do same-key messages go to the same partition?" (2026-05-17)**
A: Partition = `hash(key) % numPartitions`. Since hash is deterministic, same key always maps to same partition. Within one partition, messages are strictly ordered by offset. So all events for order #42 (DELIVERED, then RETURNED) land in the same partition and are consumed in that exact order. Without key-based routing, these events could land in different partitions and be consumed out of order (RETURNED before DELIVERED → bug).

### Step 2-3: Event DTOs + Kafka Producer (2026-05-17)

**88. Event DTOs — Data Snapshot, Not Entity Reference (2026-05-17)**

Kafka events carry a snapshot of data at the time the event occurred. `OrderDeliveredEvent` includes `orderId`, `userId`, `items` (with product/price snapshots), `totalAmount`, `deliveredAt`. The consumer doesn't query back to the producer for missing data — the event is self-contained. This decouples producer and consumer lifecycles (producer can change its entities without breaking consumers).

Events were implemented as manual POJOs (not Java records) to learn Jackson's deserialization lifecycle: `no-arg constructor → setters called per field → object ready`. Records would eliminate ~60 lines per class but require Jackson 2.12+ record-aware support (uses canonical constructor directly, no no-arg needed).

**89. KafkaTemplate + CompletableFuture — Async Fire-and-Forget (2026-05-17)**

`KafkaTemplate.send(topic, key, value)` is non-blocking — returns `CompletableFuture<SendResult>` immediately. The actual network send happens asynchronously. `whenComplete((result, exception) -> ...)` lets you handle success/failure without blocking the calling thread. Important: `exception != null` means FAILURE, `exception == null` means SUCCESS (the result contains partition + offset metadata).

This is fire-and-forget: if Kafka is down, the order status still updates but the event is lost. The Outbox pattern (Step 6) fixes this by making event creation atomic with the DB transaction.

**Q82: "Why use Object as KafkaTemplate value type instead of a specific event class?" (2026-05-17)**
A: `KafkaTemplate<String, Object>` allows sending different event types (OrderDeliveredEvent, OrderReturnedEvent) through the same template instance. The `JsonSerializer` handles the actual serialization and adds the `__TypeId__` header with the concrete class name. If you typed it as `KafkaTemplate<String, OrderDeliveredEvent>`, you'd need a separate template for each event type.

**Q83: "Why publish AFTER orderRepository.save() and not before?" (2026-05-17)**
A: If you publish first and the save fails (constraint violation, DB down), you've told consumers "order delivered" but it wasn't actually saved. The consumer grants a reward for a non-existent delivery. Publishing after save means: if save fails → exception propagates → publish never reached → no false event. The remaining gap: save succeeds but publish fails (app crash between the two lines) → event lost. Outbox pattern (Step 6) closes this gap.

### Step 4-5: Stock-Back Reward Consumer + Cancellation Consumer (2026-05-19)

**90. Kafka Consumer — @KafkaListener Deserialization Lifecycle (2026-05-19)**

When a `@KafkaListener` method declares a typed parameter like `handleOrderDelivered(OrderDeliveredEvent event)`, Spring Kafka's `JsonDeserializer` reads the `__TypeId__` header from the Kafka message to determine the target class. It then deserializes the JSON bytes into that class using Jackson. The `trusted.packages` config restricts which classes can be instantiated (security against deserialization attacks). If `__TypeId__` says `java.lang.String` but your method expects `OrderDeliveredEvent`, you get ClassCastException.

**91. Composite Unique Constraint — Multi-Ticker Rewards Per Order (2026-05-19)**

`@UniqueConstraint(columnNames = {"order_id", "ticker_symbol"})` replaces the single-column `@Column(unique=true)` on `orderId`. Business logic: buying Apple + Nike products in one order should grant BOTH AAPL and NKE rewards. The idempotency key becomes (orderId + ticker), allowing multiple rewards per order while still preventing duplicates on Kafka redelivery. Repository method: `findByOrderIdAndTickerSymbol()`.

**92. Consumer Group Isolation (2026-05-19)**

`equitycart-reward-group` (order-delivered) and `equitycart-cancellation-group` (order-returned) have separate group IDs despite being in the same class. Each group maintains its own committed offsets. If a future notification consumer needs order-delivered events too, it uses `equitycart-notification-group` and receives ALL messages independently — pub-sub semantics via group isolation.

### Step 6: Outbox Pattern (2026-05-19)

**93. The Dual-Write Problem and Outbox Solution (2026-05-19)**

The dual-write problem: writing to DB and Kafka in the same method without a shared transaction means either can fail independently, leaving permanent inconsistency. The Outbox Pattern solves this by writing the event payload into an `outbox_events` table within the SAME DB transaction as the business write. A background poller reads PENDING rows and publishes to Kafka. Guarantee: at-least-once delivery (consumer must be idempotent). Delivery: if transaction commits, both order and outbox row exist atomically; if it rolls back, neither exists.

See `microservice-patterns.md` for full details with serialization flow diagrams and variant comparison.

**Q84: "Why does the outbox poller use blocking .get() instead of async whenComplete()?" (2026-05-19)**
A: The `whenComplete()` callback runs on Kafka's producer I/O thread — outside any Spring `@Transactional` context. Calling `outboxEventRepository.save()` there either throws `TransactionRequiredException` or auto-commits without isolation. Using `.get()` blocks within the `@Transactional` method boundary, ensuring the status update participates in the same transaction. Poller latency is irrelevant — it's a background job, not a user-facing request.

**Q85: "Why re-hydrate the JSON string back to a DTO before sending via KafkaTemplate?" (2026-05-19)**
A: Spring's `JsonSerializer` writes the `__TypeId__` header based on the Java object type it receives. If you send a raw `String`, it sets `__TypeId__: java.lang.String`. The consumer's `JsonDeserializer` reads that header and tries to instantiate a String — not your event DTO — causing ClassCastException. Re-hydrating (`objectMapper.readValue(payload, EventClass)`) lets the serializer see the real type and set the correct header. Consumer code stays unchanged.

**94. The Outbox Is Infrastructure, Not Domain Logic (2026-05-19)**

The outbox table doesn't care whether an event represents a delivery or a return. Its sole job: "relay this JSON blob to this Kafka topic." Therefore:
- ONE status lifecycle: `PENDING → SENT` (no `RETURNED`, `CANCELLED`, etc.)
- ONE generic poller method that handles ALL event types using `Class.forName(payloadType)`
- The `payloadType` column (FQCN) enables dynamic deserialization without if-else chains
- The `eventType` column (`ORDER_DELIVERED`, `ORDER_RETURNED`) is metadata for debugging/querying — the poller never reads it

Domain-specific statuses (`RETURNED`, `CANCELLED`) belong in domain enums (`OrderStatus`, `VestingStatus`) — not in infrastructure enums like `OutboxStatus`.

**95. Modular Monolith: Why spring-kafka in the app module? (2026-05-19)**

Sub-modules (order-service, portfolio-service) declare `spring-kafka` so their code compiles (`KafkaTemplate`, `@KafkaListener` annotations resolve). But Spring Boot's auto-configuration (`KafkaAutoConfiguration`) only activates if the dependency is on the classpath of the Boot application — which is the `app` module. Auto-configuration reads `spring.kafka.*` YAML properties and creates: `KafkaTemplate` bean, `ConcurrentKafkaListenerContainerFactory` bean, and consumer/producer configurations. Without `spring-kafka` in `app/build.gradle`, these beans wouldn't exist at runtime → `NoSuchBeanDefinitionException` on startup.

**Q86: "Why does Class.forName() need the FQCN and not just the class name?" (2026-05-19)**
A: `Class.forName("OrderDeliveredEvent")` throws `ClassNotFoundException` because Java's classloader resolves classes by their fully-qualified name (package + class). `"OrderDeliveredEvent"` is ambiguous — there could be multiple classes with that name in different packages. `"com.equitycart.commons.event.OrderDeliveredEvent"` is unambiguous. Using `event.getClass().getName()` is the safest approach — it always returns the FQCN without hardcoding.

### Step 7: Dead Letter Queue (2026-05-20)

**96. Dead Letter Queue — Safety Net for Poison Messages (2026-05-20)**

A poison message fails on every retry (malformed JSON, deleted entity, code bug). Without a DLQ, it blocks the consumer at that offset forever. Spring Kafka's `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` provides the fix: retry N times with backoff, then divert to a `.DLT` topic (e.g., `order-delivered.DLT`). The original offset is committed, consumer moves on. Failed messages accumulate in the DLT for investigation/replay.

Configuration is declarative — one `@Bean` in a `@Configuration` class. The `ConcurrentKafkaListenerContainerFactory` picks up the `DefaultErrorHandler` bean automatically. Zero changes to existing `@KafkaListener` methods.

See `kafka-learning.md` Section 9 for full DLQ details with header descriptions and retry classification.

**Q87: "Why separate retryable from non-retryable exceptions?" (2026-05-20)**
A: Retrying a `DeserializationException` 3 times wastes 3 seconds — the JSON is malformed and will never parse correctly. `addNotRetryableExceptions()` tells the error handler to skip retries for permanent failures and send to DLT immediately. Retryable exceptions (DB timeout, network blip) genuinely benefit from retries because the underlying cause may resolve between attempts.

### Step 9: Exponential Backoff (2026-05-20)

**97. Exponential Backoff — Preventing Thundering Herd (2026-05-20)**

Replaced `FixedBackOff(1000L, 3)` with `ExponentialBackOffWithMaxRetries(3)` — retry delays now grow: 1s → 2s → 4s (multiplier 2.0, capped at 10s). Fixed-interval retries cause synchronized storms when multiple consumers fail simultaneously on the same transient failure (e.g., DB connection pool exhaustion). Exponential backoff spreads retries over increasing windows, giving the recovering resource progressively more breathing room. Adding jitter (random ±20%) further desynchronizes — standard in AWS/Google/Stripe SDKs since ~2015.

See `kafka-learning.md` Section 10 for full details on fixed vs exponential vs jitter strategies.

**Q88: "Why use ExponentialBackOffWithMaxRetries instead of plain ExponentialBackOff?" (2026-05-20)**
A: Plain `ExponentialBackOff` stops retrying based on `maxElapsedTime` (default `Long.MAX_VALUE` = infinite retries). This defeats the purpose of DLQ — messages would retry forever instead of routing to the DLT. `ExponentialBackOffWithMaxRetries` (Spring Kafka subclass) adds an explicit retry count cap, ensuring messages reach DLT after exactly N attempts regardless of elapsed time.

### Step 10: Debezium CDC (2026-05-24)

**98. Change Data Capture (CDC) — Database as Event Source (2026-05-24)**

CDC captures row-level changes (INSERT/UPDATE/DELETE) from a database's Write-Ahead Log (WAL) and streams them as events — no polling, no application code. Debezium is the industry-standard open-source CDC platform (Red Hat, 2016), running as a Kafka Connect source connector. It creates a logical replication slot in PostgreSQL, reads the WAL stream in real-time, applies transformations (SMTs), and publishes to Kafka topics.

Key advantage over polling: near-zero latency (ms vs seconds), zero DB query load (reads WAL, not tables), automatic resume from last WAL position (LSN — Log Sequence Number) on restart. Trade-off: external infrastructure (Kafka Connect container), WAL configuration requirement (`wal_level = logical`), and operational complexity.

**99. PostgreSQL WAL Levels — Why `logical` Is Required (2026-05-24)**

PostgreSQL's WAL has three levels: `minimal` (crash recovery only), `replica` (default, supports physical streaming replicas), `logical` (adds decoded row-level changes). CDC requires `logical` because Debezium needs the actual column values from the WAL — not just physical page diffs. Changing WAL level requires `ALTER SYSTEM SET wal_level = 'logical'` + PostgreSQL service restart (cannot be changed at runtime).

**100. Outbox Event Router SMT — Clean Event Extraction (2026-05-24)**

Without the Outbox Event Router, Debezium publishes a raw change event for the `outbox_events` table (with schema envelope, all columns, source metadata). The Outbox Event Router Single Message Transform (SMT) extracts just the `payload` column as the Kafka value, uses `aggregate_id` as the key, and routes to the topic specified in the `topic` column. This transforms a generic table-change event into a clean domain event — matching what the OutboxPoller would have published.

**101. Docker Dual-Listener Pattern — Container Networking (2026-05-24)**

When Kafka and Debezium run in separate Docker containers, Kafka must advertise different addresses for different clients. `localhost:9092` works for the host app (Spring Boot) but means "myself" inside Debezium's container. Solution: two Kafka listeners — `PLAINTEXT://localhost:9092` (advertised to host) and `DOCKER://host.docker.internal:29092` (advertised to containers). `host.docker.internal` is Docker's built-in DNS that resolves to the host machine from inside any container.

**102. @Lob + CDC = Broken — Large Object vs Inline Storage (2026-05-24)**

In PostgreSQL + Hibernate, `@Lob` on a String field creates an OID (Object Identifier) column. The actual text lives in `pg_largeobject` internal catalog; the column stores only a numeric reference (e.g., 18110). JPA transparently follows this reference, but Debezium reads the WAL which only contains the OID number — it cannot access `pg_largeobject`. Fix: `@Column(columnDefinition = "text")` stores content inline (uses TOAST for >2KB automatically). Same unlimited capacity, but CDC-compatible.

**103. `__TypeId__` Header Gap — CDC Messages vs Spring Messages (2026-05-24)**

Spring Kafka's `JsonSerializer` adds a `__TypeId__` header (FQCN of the Java class) to every message. The `JsonDeserializer` reads this to know which class to instantiate. Debezium doesn't add this header — it's not Spring-aware. Fix: `spring.json.value.default.type` property on each `@KafkaListener` tells the deserializer "if no `__TypeId__` header exists, assume this class." Each listener needs its own default since different topics carry different event types.

**Q89: "Why does the outbox_events status stay PENDING in CDC mode?" (2026-05-24)**
A: In polling mode, the OutboxPoller reads PENDING rows → publishes to Kafka → marks SENT. In CDC mode, Debezium reads the INSERT from the WAL (before the app could update anything) and publishes it. Nothing in the app ever updates the status because the OutboxPoller is disabled (`@Profile("!cdc")`). The status column becomes irrelevant in CDC mode — Debezium captures the event at the database level the moment it's committed. A cleanup job can archive old rows.

**Q90: "Why use snapshot.mode=never for the outbox table?" (2026-05-24)**
A: Default `snapshot.mode=initial` performs a full table scan on first start, publishing ALL existing rows. For the outbox table, these rows were already published by the OutboxPoller before switching to CDC. Re-publishing them creates duplicate events. `snapshot.mode=never` skips the snapshot entirely — only new WAL changes (INSERT events from this point forward) are captured. If you delete and re-register the connector without this setting, it snapshots again.

**Q91: "Why did Kafka reject the message with InvalidTimestampException?" (2026-05-24)**
A: The connector config had `transforms.outbox.table.field.event.timestamp=created_at`, which tells Debezium to use the `created_at` column value as the Kafka message timestamp. The app writes `LocalDateTime.now()` using the host timezone (IST, UTC+5:30), storing e.g., "17:50". Debezium interprets this as UTC and converts to epoch millis. The Kafka broker (Docker, running UTC at 13:22) sees a timestamp 4.5 hours in the future and rejects it. Fix: remove the timestamp field mapping — let Debezium use broker time.

**Q92: "What is a Kafka Connect Single Message Transform (SMT)?" (2026-05-24)**
A: An SMT is a lightweight, stateless transformation applied to each message as it passes through Kafka Connect (before producing or after consuming). No external storage, no aggregation — just per-message field extraction, renaming, routing, or filtering. The Outbox Event Router is a complex SMT that restructures a table-change event into a routed domain event. SMTs are configured declaratively in the connector JSON — no Java code needed.

### Phase 6 — Complete Architecture Diagram (2026-05-20)

```
ORDER-SERVICE                         PORTFOLIO-SERVICE
┌───────────────────┐                 ┌─────────────────────────────┐
│ OrderServiceImpl  │                 │ StockBackRewardConsumer     │
│  @Transactional   │                 │                             │
│  1. order.save()  │                 │ order-delivered listener:   │
│  2. outbox.save() │ ← atomic       │  items→brand→ticker→reward  │
└────────┬──────────┘                 │  grantReward() [idempotent] │
         │                            │                             │
         ▼                            │ order-returned listener:    │
┌───────────────────┐                 │  PENDING → CANCELLED        │
│ OutboxPoller      │                 └──────────────▲──────────────┘
│ @Scheduled(5s)    │                                │
│ re-hydrate + send │   Kafka Topics                 │
│ mark SENT         │──▶ order-delivered ────────────┘
└───────────────────┘──▶ order-returned ─────────────┘
                        order-*.DLT (poison messages)

Error: retry×3 exponential (1s→2s→4s) → DLT. Non-retryable → DLT immediately.
Idempotency: findByOrderIdAndTickerSymbol prevents duplicate rewards.
```

See `microservice-patterns.md` Section 1.11 for the full detailed diagram.

### Step 11: Saga Orchestrator for Sell-to-Spend (2026-05-24)

**104. Saga Pattern — Compensating Transactions for Distributed Consistency (2026-05-24)**

When operations span multiple services with separate databases, a single `@Transactional` is impossible — each service commits independently. The Saga pattern coordinates a sequence of local transactions, each with a "compensating transaction" that semantically undoes it on failure. Unlike a DB rollback (which erases the change), compensation is a forward operation visible to other transactions. This is eventual consistency — intermediate states ARE visible. The term originates from Garcia-Molina & Salem's 1987 Princeton paper on long-lived transactions.

**105. Orchestration vs. Choreography — Saga Coordination Strategies (2026-05-24)**

Two approaches: Orchestration (central coordinator drives all steps and decides compensation) vs Choreography (each service reacts to events from the previous service). Orchestration is simpler for complex multi-step flows — the entire saga is readable in one class. Choreography scales better and reduces coupling but scatters logic across multiple consumers. EquityCart uses Orchestration: `SellToSpendSagaOrchestrator` knows the full 3-step sequence and handles all compensation in one place.

**106. State Machine Persistence — Saga Recovery Log (2026-05-24)**

The saga entity persists status at every step boundary (`STARTED → REDUCING_HOLDING → HOLDING_REDUCED → ...`). If the app crashes between steps, the row shows the last committed status. A timeout detector (@Scheduled) polls for sagas stuck in non-terminal states past a threshold and triggers compensation from the last known-good state. Without persistence, crashed sagas leave the system in an inconsistent state (shares removed but order never confirmed).

**107. @ConditionalOnProperty — Strategy Pattern via Spring Configuration (2026-05-24)**

When two implementations of the same interface exist, `@ConditionalOnProperty` activates exactly one bean based on application.yml. `matchIfMissing=true` on the transactional impl makes it the default — the saga impl only activates when explicitly requested (`strategy=saga`). The controller/facade layer calls the interface method unchanged — Spring DI wires the selected implementation at startup. This is the Strategy pattern without explicit factory classes.

**108. Compensating Transaction Design — Forward Undo Operations (2026-05-24)**

Compensations are NOT rollbacks — they create new records that reverse the business effect. Example: undoing a ledger debit requires a new CREDIT entry (with ReferenceType `SELL_TO_SPEND_REVERSAL`), not deleting the original row. This preserves full audit trail and is idempotent (check if reversal already exists before creating). Compensations run in reverse step order — last completed step compensated first. The last step in a saga never needs compensation (nothing runs after it to fail).

**Q93: "Why is executeSaga() NOT @Transactional?" (2026-05-24)**
A: The entire point of the Saga pattern is that each step commits independently — no umbrella transaction. If you wrap it in `@Transactional`, all saves and service calls share one DB transaction that either all-commits or all-rolls-back — which is just the `@Transactional` approach with extra steps. The saga entity saves BETWEEN steps precisely so that crashes don't lose state. Each save auto-commits via Spring Data's default transactional behavior on `repository.save()`.

**Q94: "What if the compensation itself fails?" (2026-05-24)**
A: The saga enters FAILED state — a terminal state requiring manual intervention. In production this triggers alerts (PagerDuty, Slack). An admin reviews the saga entity (which records failureReason, completedSteps, compensation start time) and manually resolves the inconsistency. Automatic retry of failed compensations is possible but risky — if the root cause is persistent (e.g., ledger service permanently down), retrying just generates noise.

**Q95: "Why store all input parameters (ticker, qty, price) in the saga entity?" (2026-05-24)**
A: The orchestrator must be able to resume or compensate without re-fetching the original request. If the app crashes and the timeout detector picks up a stuck saga, it needs all inputs to call `addOrUpdateHolding(userId, ticker, qty, price)` for compensation. Storing inputs in the entity makes the saga self-contained — it can be processed by any instance, not just the one that started it.

**Q96: "How does the saga compare to the @Transactional approach in terms of code complexity?" (2026-05-24)**
A: Transactional: ~50 lines (validate + 3 service calls). Saga: ~300+ lines across 7 files (entity, enum, repository, orchestrator, service impl, outbox writer, event DTO). This 6x complexity is the cost of distribution. You pay it only when you MUST — when services have separate databases and cannot share a transaction. In a monolith, always prefer `@Transactional`.

### Step 11 Extension: Refund Flow — Sell-to-Spend Reversal via Kafka (2026-05-24)

**109. Event-Driven Refund Compensation — Restoring Sold Shares (2026-05-24)**

When an order paid via sell-to-spend is refunded, the shares must be returned to the user's portfolio. This is the "reverse compensation" of the sell-to-spend flow. Unlike saga compensation (which happens synchronously during the saga execution), refund compensation happens asynchronously days/weeks later via Kafka event. The `OrderRefundedEvent` carries `paymentMethod` so the consumer can discriminate between STOCK refunds (requiring share restoration) and CARD refunds (handled by payment gateway). The completed saga entity serves as the source of truth for what was sold (ticker, quantity, price) — no need to re-query the order or market data.

**Q97: "Why use the saga entity for refund data instead of carrying it in the event?" (2026-05-24)**
A: The event only needs `orderId` + `paymentMethod` to route correctly. The saga entity already stores all sale parameters (ticker, quantity, pricePerShare, saleProceeds). Duplicating this data in the event creates a consistency risk — if the saga was compensated (shares already returned), the event wouldn't know. By looking up the saga, the consumer gets both the data AND the idempotency check (`isRefunded` flag) in one query.

**Q98: "Why is `isRefunded` on the saga entity instead of a separate refund table?" (2026-05-24)**
A: The refund is a 1:1 extension of the saga lifecycle, not an independent entity. Adding a boolean to the saga keeps the refund check atomic with the saga lookup (single query + single save). A separate table would require coordinating writes across two tables for idempotency, introducing the same distributed consistency problem the saga was designed to solve.

### Step 12: Event Sourcing for Portfolio Changes — MongoDB Append-Only Event Log (2026-05-27)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     FUNCTIONAL FLOW: Event Sourcing                          │
└─────────────────────────────────────────────────────────────────────────────┘

  User Action (BUY/SELL/SELL-TO-SPEND/VEST/REFUND)
         │
         ▼
  ┌─────────────────────────────────────────────────┐
  │           Service Layer (existing)               │
  │  TradeServiceImpl / PortfolioServiceImpl /       │
  │  VestingHelper / SagaOrchestrator / Consumer     │
  └────────────┬────────────────────┬───────────────┘
               │                    │
        ① State Update       ② Event Append
               │                    │
               ▼                    ▼
  ┌──────────────────┐   ┌──────────────────────────┐
  │   PostgreSQL     │   │   PortfolioEventStoreImpl │
  │   holdings       │   │   (try-catch, best-effort)│
  │   [AUTHORITY]    │   └────────────┬─────────────┘
  └──────────────────┘                │
                                      │ save()
                                      ▼
                            ┌──────────────────────┐
                            │      MongoDB         │
                            │  portfolio_events    │
                            │  [APPEND-ONLY LOG]   │
                            │                      │
                            │  { eventId, userId,  │
                            │    eventType, ticker,│
                            │    qty, price,       │
                            │    metadata,         │
                            │    timestamp,        │
                            │    sequenceNumber }  │
                            └──────────┬───────────┘
                                       │
              ┌────────────────────────┼────────────────────────┐
              │                        │                        │
              ▼                        ▼                        ▼
  GET /events              GET /events/projection    GET /events/projection/validate
  (timeline query)         (replay all events →      (compare projected vs
   optional filters:        rebuild holdings map)     PostgreSQL holdings →
   ?ticker=AAPL                                       MATCH/MISMATCH per ticker)
   ?from=...&to=...)

─────────────────────────────────────────────────────────────────────────────

  Integration Points (9 append calls across 6 files):

  TradeServiceImpl ─────────── BUY  → SHARES_PURCHASED
                               SELL → SHARES_SOLD

  PortfolioServiceImpl ─────── grantReward() → REWARD_GRANTED

  VestingHelperImpl ────────── vestSingleReward() → REWARD_VESTED

  StockBackRewardConsumer ──── order-returned → REWARD_CANCELLED
                               order-refunded → REFUND_RESTORED

  SellToSpendSagaOrchestrator ─ step1() → SELL_TO_SPEND
                                compensate() → SELL_TO_SPEND_COMPENSATED

  SellToSpendServiceImpl ───── sellToSpend() → SELL_TO_SPEND (transactional mode)

─────────────────────────────────────────────────────────────────────────────

  Projection Replay (left fold):

  events = [PURCHASED(5@150), PURCHASED(3@200), SOLD(2@180), VESTED(1@0)]
                │                    │                │              │
  state:   {qty:5, avg:150}   {qty:8, avg:168.75}  {qty:6, avg:168.75}  {qty:7, avg:144.64}
                                                    (avg unchanged       (0 price dilutes avg)
                                                     on sells)
```

**110. Event Sourcing — Immutable Facts as the Source of History (2026-05-27)**

Traditional CRUD overwrites state in place — when you update a holding's quantity, the old value is lost. Event Sourcing inverts this: every state change is recorded as an immutable event (fact) in an append-only store. The current state is always derivable by replaying all events from the beginning. The term comes from Martin Fowler (2005), building on the Domain Events pattern. Benefits: full audit trail, temporal queries ("portfolio at time T"), debugging (replay to reproduce bugs), and analytics (aggregate events without querying live state). Tradeoff: more storage, eventually-consistent read models, and increased complexity for simple CRUD operations.

**111. Projections — Rebuilding State from Events (The "Left Fold") (2026-05-27)**

A projection is a function that processes an event stream and produces a read-optimized view. Conceptually: `currentState = events.reduce(emptyState, applyEvent)` — a left fold in functional programming terms. Each event type has application rules: SHARES_PURCHASED adds to quantity and recalculates weighted average, SHARES_SOLD subtracts quantity (avg unchanged). The projection is deterministic — replaying the same events always produces the same state. This enables: rebuilding from scratch after bugs, creating new read models retroactively, and validating consistency between stores.

**112. Dual-Write Pattern — Pragmatic Event Sourcing Without Full Migration (2026-05-27)**

In pure Event Sourcing, the event store IS the only source of truth and all state is derived via projections. The Dual-Write approach is a pragmatic middle ground: write to both the state store (PostgreSQL holdings) and the event store (MongoDB) simultaneously. PostgreSQL remains authoritative for current state; MongoDB provides history and audit. Risk: if one write succeeds and the other fails, the stores drift. Mitigation: make the event store best-effort (catch exceptions, log warnings, don't break primary flow). Acceptable for audit/analytics use cases where occasional missed events are tolerable.

**113. Sequence Numbers — Ordering Guarantees in Event Stores (2026-05-27)**

Events need a total order per aggregate (user) for deterministic replay. Options: timestamps (risk: clock skew, same-millisecond events), auto-increment (requires centralized counter), or application-level sequence (query last + increment). EquityCart uses per-user sequence numbers: query `findTopByUserIdOrderBySequenceNumberDesc`, then `lastSeq + 1`. Gaps are acceptable (indicate a missed event) but duplicates are not (unique index on eventId prevents them). In distributed systems, vector clocks or Kafka offsets often replace sequence numbers.

**Q99: "Why store eventType as String instead of @Enumerated in MongoDB?" (2026-05-27)**
A: Schema flexibility. MongoDB documents don't enforce column types like PostgreSQL CHECK constraints. Storing as String means: new event types can be added without migration, old documents remain valid, and the projection's `default` case gracefully skips unknown types. If you used an enum directly and added a new value, deserializing old documents with Jackson would fail if the enum was later restructured.

**Q100: "Why is the event store best-effort (try-catch) instead of transactional with PostgreSQL?" (2026-05-27)**
A: PostgreSQL and MongoDB are separate databases — no single transaction can span both (without distributed transactions like XA/2PC, which add massive complexity and latency). Making the event store best-effort means a MongoDB outage doesn't break core portfolio operations. The tradeoff is potential drift (missed events), which is acceptable for an audit/analytics store. If you needed guaranteed consistency, you'd use the Outbox Pattern: write events to a PostgreSQL outbox table (same transaction as the state change), then relay to MongoDB asynchronously.

**Q101: "What happens to the projection for trades done BEFORE event sourcing was enabled?" (2026-05-27)**
A: The projection only replays events that exist in the event store. Pre-Step-12 trades have no events, so the projection shows fewer holdings than PostgreSQL. The `/validate` endpoint explicitly surfaces this as "MISMATCH: projected=NOT_FOUND, actual=qty:X". To backfill, you'd write a migration script that reads the PostgreSQL state and creates synthetic "initial state" events — but this wasn't needed for learning purposes.

**Q102: "Is Event Sourcing the same as CQRS?" (2026-05-27)**
A: No — they are separate patterns that complement each other well but are independent.

- **Event Sourcing** = how you STORE state (as a sequence of immutable events, not current-state rows)
- **CQRS (Command Query Responsibility Segregation)** = how you SEPARATE reads from writes (different models/paths for mutations vs queries)

They are often used together because Event Sourcing naturally produces a write model (event store) that is optimized for appends but awkward for reads — so you build separate read-optimized projections (CQRS). But you can do CQRS without Event Sourcing (e.g., separate read replicas + write master in PostgreSQL), and you can do Event Sourcing without CQRS (single model that both writes events and queries the same store).

**What EquityCart implements:**
- Event Sourcing: Yes — MongoDB append-only event log captures all portfolio mutations as immutable facts
- CQRS: Partially ("CQRS Lite") — the write path goes to PostgreSQL (current state), while the read/audit path queries MongoDB (event timeline + projections). Two different stores optimized for different access patterns, but not a full CQRS architecture (which would have completely separate command and query services with eventual consistency between them)

**Full CQRS would look like:**
```
Command Side                              Query Side
┌─────────────┐    publish    ┌─────────────────────────┐
│ Write API   │──── events ──▶│ Event Handler           │
│ (validates  │               │ (updates read model)    │
│  + appends) │               └───────────┬─────────────┘
└──────┬──────┘                           │
       │                                  ▼
       ▼                        ┌─────────────────────┐
┌─────────────┐                 │   Read Database      │
│ Event Store │                 │   (denormalized,     │
│ (only truth)│                 │    query-optimized)  │
└─────────────┘                 └─────────────────────┘
                                          │
                                          ▼
                                ┌─────────────────────┐
                                │   Read API           │
                                │   (fast queries)     │
                                └─────────────────────┘
```

---

#### Advantages & Real-World Usage of Event Sourcing

**Why Event Sourcing matters (what it solves):**

| Problem with CRUD | How Event Sourcing Solves It |
|-------------------|-----------------------------|
| Overwritten state — no history of HOW you got here | Every mutation recorded as immutable fact; complete chronology |
| "What was the portfolio on May 1st?" — impossible | Replay events up to that timestamp (temporal query) |
| Debugging "why is my holding wrong?" — log archaeology | `/projection/validate` pinpoints exactly which event caused drift |
| Bug in calculation logic — requires data fix | Fix projection code + replay from scratch — events are immutable facts |
| Analytics require querying live DB | Query event store directly without touching production state |
| New requirement needs historical data that wasn't tracked | Write new projection over existing events — data was always there |

**Real-world domains that use Event Sourcing:**

| Domain | Why | Example |
|--------|-----|---------|
| Banking/Finance | Regulatory: must explain every balance change | Each debit/credit is an event; account balance = sum of events |
| E-commerce | Complex order lifecycle needs full audit | Order placed → paid → picked → shipped → delivered → returned |
| Healthcare | Legally mandated audit trail | Every patient record change must be traceable to who/when/why |
| Gaming | Replay & undo capability | Chess move history, real-time game state sync, replays |
| IoT/Telemetry | High-volume append-only sensor data | Temperature readings, GPS pings — never overwritten |
| Version Control | Git IS event sourcing | Commits are immutable events; working directory is the projection |

**When NOT to use Event Sourcing:**
- Simple CRUD with no audit requirements (blog posts, user settings)
- When storage cost of retaining all events outweighs the audit value
- When your team cannot maintain the added complexity (projections, eventual consistency, event versioning/upcasting)
- When strict real-time read consistency is required and eventual consistency is not acceptable

**How EquityCart's implementation demonstrates each principle:**

| Principle | Implementation |
|-----------|---------------|
| Immutable facts | `PortfolioEvent` document — no update/delete methods exist anywhere |
| Deterministic replay | `rebuildHoldings()` — same events always produce identical state |
| Ordering guarantee | `sequenceNumber` — monotonically increasing per user |
| Graceful degradation | `try-catch` in `PortfolioEventStoreImpl` — MongoDB down ≠ broken trades |
| Consistency validation | `/projection/validate` — proves events and state store agree |
| Temporal queries | `?from=...&to=...` filter — query events within any time range |
| Metadata flexibility | `Map<String, Object>` — each event type carries its own context (orderId, sagaId, reason) |

### Step 13: Notification Service — Observer Pattern via Kafka Pub/Sub (2026-05-31)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  FUNCTIONAL FLOW: Notification Service                        │
└─────────────────────────────────────────────────────────────────────────────┘

  Portfolio Services (Subjects/Publishers)
  ┌─────────────────────────────────────────────────────────────────┐
  │ TradeServiceImpl ─────────── publishes TRADE_EXECUTED            │
  │ VestingHelperImpl ────────── publishes REWARD_VESTED             │
  │ SellToSpendSagaOrchestrator ─ publishes SELL_TO_SPEND_COMPLETED  │
  │                               publishes SELL_TO_SPEND_FAILED     │
  └────────────────────────────────────┬────────────────────────────┘
                                       │
                              NotificationPublisher
                              (fire-and-forget, try-catch)
                              kafkaTemplate.send("portfolio-notification", ...)
                                       │
                                       ▼
                          ┌──────────────────────────┐
                          │  Kafka Topic:             │
                          │  portfolio-notification   │
                          └──────────────┬───────────┘
                                         │
                                @KafkaListener(groupId="equitycart-notification-group")
                                         │
                                         ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │              NotificationConsumer → NotificationDispatcher                 │
  │                                                                           │
  │  1. Resolve channel: activeChannel.toLowerCase() + "Channel"              │
  │  2. Look up from Map<String, NotificationChannelStrategy>                 │
  │  3. Build subject + body from event type (switch)                         │
  │  4. strategy.send(userId, subject, body)                                  │
  │  5. Save NotificationLog (SENT or FAILED)                                 │
  │                                                                           │
  │              Strategy Pattern (runtime channel selection):                 │
  │              ┌───────────────┬───────────────┬───────────────┐            │
  │              ▼               ▼               ▼               │            │
  │       logChannel      emailChannel    webhookChannel          │            │
  │       (Log4j INFO)    (JavaMailSender) (WebClient POST)       │            │
  │                        ↓                ↓                     │            │
  │                   MailHog SMTP      HTTP endpoint              │            │
  │                   localhost:1025    configurable URL            │            │
  └──────────────────────────────────────────────────────────────────────────┘

  REST API:
  GET /api/notifications            → all notifications for user (most recent first)
  GET /api/notifications?type=X     → filtered by NotificationType

─────────────────────────────────────────────────────────────────────────────

  Pattern Evolution: Observer → Pub/Sub

  GoF Observer (in-memory)              Kafka Pub/Sub (distributed)
  ┌──────────────────────────┐          ┌──────────────────────────────┐
  │ Subject.observers = [A,B]│          │ Producer knows NO consumers  │
  │ Subject.notify() → A,B   │    →     │ Kafka topic retains events   │
  │ Same JVM, synchronous    │          │ Consumer subscribes, async   │
  │ Observer failure blocks   │          │ Failure doesn't affect sender│
  └──────────────────────────┘          └──────────────────────────────┘
```

**114. Observer Pattern (Distributed) — Decoupled Event-Driven Notifications (2026-05-31)**

The Observer Pattern (GoF, 1994) defines a one-to-many dependency: when one object (subject) changes state, all dependents (observers) are notified. In its original form, the subject maintains a `List<Observer>` and calls `observer.update()` directly — same JVM, synchronous, tightly coupled. Kafka Pub/Sub is this pattern evolved for distributed systems: the message broker decouples publishers from subscribers, adds persistence (events survive consumer downtime), enables replay (offset reset), and supports multiple independent consumer groups. The publisher (TradeServiceImpl) has zero knowledge of who or what consumes its events — adding a new observer (SMS service, analytics pipeline) requires zero changes to the publisher.

**115. Strategy Pattern — Runtime Channel Selection via Spring Bean Map (2026-05-31)**

The Strategy Pattern (GoF) encapsulates a family of algorithms behind a common interface, making them interchangeable at runtime. Here: `NotificationChannelStrategy` defines `send(userId, subject, body)`, with three implementations (LogChannel, EmailChannel, WebhookChannel). Spring's `Map<String, BeanType>` auto-injection collects all beans implementing the interface, keyed by `@Component("name")`. The dispatcher resolves the active strategy from config (`equitycart.notification.channel=LOG` → bean name `logChannel`) — no if-else chain, no factory class. Adding a new channel (e.g., SMS) means: write one new `@Component("smsChannel")` class, update config. Zero changes to dispatcher.

**116. Fire-and-Forget vs Outbox — When Guaranteed Delivery Isn't Required (2026-05-31)**

The Outbox Pattern (Step 6) guarantees event delivery via same-transaction persistence + async relay. Notifications use simpler fire-and-forget KafkaTemplate.send(): if Kafka is down, the notification is silently lost. Why this is acceptable: (1) notifications are not business-critical — no data is corrupted if missed; (2) user can check the API for status; (3) the NotificationLog provides audit even if delivery fails at the channel level. Reserve Outbox for events where loss means data inconsistency (order→reward flow). Use fire-and-forget for low-severity side-effects.

**Q103: "How does Spring auto-inject all beans of an interface into a Map?" (2026-05-31)**
A: When you declare `Map<String, SomeInterface>` as a constructor parameter, Spring scans for all beans implementing that interface and injects them keyed by their bean name. With `@Component("logChannel")`, the key is `"logChannel"`. This is the same mechanism used for `List<SomeInterface>` (injects all implementations as a list). It's the canonical Spring way to implement the Strategy Pattern without manual factory classes.

**Q104: "Why is NotificationPublisher in the portfolio module, not the notification module?" (2026-05-31)**
A: The publisher is the "subject" in the Observer Pattern — it lives where the events originate. Portfolio services (TradeServiceImpl, VestingHelper) need to call `publisher.publish()` directly. If the publisher were in the notification module, portfolio would need a dependency on notification-service, creating a circular dependency (notification already depends on commons, and portfolio is the event source). Keeping the publisher in portfolio maintains clean dependency direction: portfolio → Kafka → notification.

**Q105: "What happens if the active channel bean name doesn't match any registered strategy?" (2026-05-31)**
A: `notificationChannelStrategies.get(beanName)` returns `null`, and the subsequent `strategy.send()` throws `NullPointerException`. This is caught by the outer try-catch in the dispatcher, which logs the error and persists a FAILED NotificationLog entry. To guard against misconfiguration, you could add a null-check with a fallback to LogChannelStrategy — but in practice, the config value is validated at startup time.

---

### Debug Mode: WebClient (Webhook) and Email (JavaMailSender) — Full Request Lifecycle (2026-05-31)

These walkthroughs trace the complete path a notification takes from the moment a portfolio service publishes an event through to the final HTTP POST (webhook) or SMTP delivery (email). Every class and method boundary is annotated with what happens internally at the framework level.

#### Scenario: User executes a BUY trade for 10 shares of AAPL at $150

---

#### Part A: Common Path (Both Channels Share This)

```
STEP 1: TradeServiceImpl.executeTrade() completes successfully
─────────────────────────────────────────────────────────────────────────────
  What just happened: holding updated, ledger entries written, event store appended.
  Now the last line of executeTrade() calls:

    notificationPublisher.publish(new NotificationEvent(
        userId,                          // 42L
        "TRADE_EXECUTED",                // String (not enum — avoids circular dep)
        "AAPL",                          // tickerSymbol
        BigDecimal.valueOf(10),          // quantity
        BigDecimal.valueOf(150),         // pricePerShare
        BigDecimal.valueOf(1500),        // totalValue (qty × price)
        Map.of("tradeType", "BUY"),     // metadata
        LocalDateTime.now()             // timestamp
    ));

  IMPORTANT: This call is INSIDE the @Transactional method but AFTER all DB work.
  If the publish fails (catch block), the DB transaction still commits — by design.
  Notifications are fire-and-forget side effects.

STEP 2: NotificationPublisher.publish(event)
─────────────────────────────────────────────────────────────────────────────
  Location: portfolio module → com.equitycart.portfolio.event.NotificationPublisher

  kafkaTemplate.send("portfolio-notification", event.userId().toString(), event)
              │              │                        │                    │
              │              │                        │                    └─ Value: the record (serialized to JSON)
              │              │                        └─ Key: "42" (String)
              │              └─ Topic name
              └─ Spring's KafkaTemplate<String, Object>

  What happens inside KafkaTemplate.send():
  1. JsonSerializer converts the NotificationEvent record to JSON bytes:
     {"userId":42,"notificationType":"TRADE_EXECUTED","tickerSymbol":"AAPL",
      "quantity":10,"pricePerShare":150,"totalValue":1500,
      "metadata":{"tradeType":"BUY"},"timestamp":"2026-05-31T14:30:00"}
  2. StringSerializer converts key "42" to UTF-8 bytes
  3. Partitioner uses murmur2(keyBytes) % numPartitions → consistent partition for userId 42
     (all notifications for user 42 land on the same partition → ordered processing)
  4. The message is buffered in the producer's accumulator (batch.size=16384 bytes by default)
  5. Kafka producer's sender thread flushes the batch to the broker
  6. Broker writes to partition log, sends ACK back (acks=1 by default in Spring Kafka)
  7. KafkaTemplate returns a CompletableFuture<SendResult> — but we don't await it (fire-and-forget)

  If ANY of steps 4-6 fail → catch block logs WARN, returns. Trade already succeeded.

STEP 3: Kafka Broker — Message Persisted in Topic
─────────────────────────────────────────────────────────────────────────────
  The message now sits in the "portfolio-notification" topic's partition log.
  It has an offset (incrementing integer), a timestamp, the key, and the JSON value.
  The message stays until retention period expires (default 7 days).

STEP 4: NotificationConsumer.handleNotificationEvent(event)
─────────────────────────────────────────────────────────────────────────────
  Location: notification module → com.equitycart.notification.consumer.NotificationConsumer

  Spring Kafka's listener container (KafkaMessageListenerContainer) runs in a
  background thread. On each poll cycle (max.poll.interval.ms=300s by default):
  1. ConsumerNetworkClient sends FETCH request to broker for partition(s) assigned
  2. Broker returns batch of ConsumerRecords
  3. For each record, the container invokes our @KafkaListener method

  Deserialization:
  - The consumer is configured with JsonDeserializer<NotificationEvent>
  - The @KafkaListener property: spring.json.value.default.type=com.equitycart.commons.event.NotificationEvent
    tells the deserializer "assume this class" even without a __TypeId__ header
  - Jackson ObjectMapper reads the JSON bytes → constructs NotificationEvent record
  - If deserialization fails → record goes to DLT (Dead Letter Topic) after retries

  After successful deserialization:
    log.info("Received notification event: type=TRADE_EXECUTED, userId=42, ticker=AAPL")
    notificationDispatcher.dispatch(event);

STEP 5: NotificationDispatcherImpl.dispatch(event)
─────────────────────────────────────────────────────────────────────────────
  Location: notification module → com.equitycart.notification.service.impl.NotificationDispatcherImpl

  5a. Resolve channel strategy:
      activeChannel = "WEBHOOK"  (from application.yml: equitycart.notification.channel)
      beanName = "WEBHOOK".toLowerCase() + "Channel" = "webhookChannel"
      strategy = notificationChannelStrategies.get("webhookChannel")
                 ↑ This Map was injected by Spring at startup — contains:
                   {"logChannel" → LogChannelStrategy,
                    "emailChannel" → EmailChannelStrategy,
                    "webhookChannel" → WebhookChannelStrategy}

  5b. Build subject and body (switch on event.notificationType()):
      subject = "Trade Executed: Executed 10 shares of AAPL"
      body    = "Your trade for 10 shares of AAPL at $150 has been executed."

  5c. Invoke the strategy:
      strategy.send(42L, subject, body);
      ↓ ↓ ↓  (this is where the paths diverge — see Part B or Part C below)
```

---

#### Part B: Webhook Channel — WebClient HTTP POST

```
STEP 6-WEBHOOK: WebhookChannelStrategy.send(42L, subject, body)
─────────────────────────────────────────────────────────────────────────────
  Location: com.equitycart.notification.service.channel.impl.WebhookChannelStrategy

  Field values (injected at startup):
    webClientBuilder → Spring's auto-configured WebClient.Builder bean
    webhookUrl → "http://localhost:9999/webhook"  (from application.yml)

  6a. Build the JSON payload:
      payload = {
        "userId": 42,
        "subject": "Trade Executed: Executed 10 shares of AAPL",
        "body": "Your trade for 10 shares of AAPL at $150 has been executed.",
        "timestamp": 1748700600000   // System.currentTimeMillis()
      }

  6b. Create and execute the HTTP request:
      webClientBuilder            // Spring WebClient.Builder (pre-configured with defaults)
        .build()                  // Creates a WebClient instance (lightweight, can build many)
        .post()                   // HTTP method = POST → returns WebClient.RequestBodyUriSpec
        .uri(webhookUrl)          // "http://localhost:9999/webhook" → resolves to URI
        .bodyValue(payload)       // Serializes Map<String,Object> → JSON via Jackson
                                  // Sets Content-Type: application/json automatically
        .retrieve()               // Initiates the exchange, returns ResponseSpec
        .toBodilessEntity()       // We don't care about response body → Mono<ResponseEntity<Void>>
        .block();                 // BLOCKS the calling thread until HTTP response arrives
                                  // (synchronous from dispatcher's perspective)

  WHAT HAPPENS INSIDE (Reactor Netty under the hood):
  ┌──────────────────────────────────────────────────────────────────────┐
  │ 1. Netty Bootstrap creates an NioSocketChannel                       │
  │ 2. TCP connect to localhost:9999 (timeout from HttpClient config)    │
  │ 3. HTTP/1.1 request written to channel:                              │
  │    POST /webhook HTTP/1.1                                            │
  │    Host: localhost:9999                                               │
  │    Content-Type: application/json                                     │
  │    Content-Length: 187                                                │
  │                                                                      │
  │    {"userId":42,"subject":"Trade Executed: ...","body":"...","timestamp":...}
  │                                                                      │
  │ 4. Netty event loop reads response from socket buffer                │
  │ 5. Response decoded → 200 OK (or 4xx/5xx)                           │
  │ 6. .block() releases the waiting thread with the ResponseEntity      │
  └──────────────────────────────────────────────────────────────────────┘

  WHY WebClient INSTEAD OF RestTemplate?
  ─────────────────────────────────────
  RestTemplate (synchronous) → 1 thread per request, blocks on I/O
  WebClient (reactive)       → event-loop model, non-blocking under the hood
  Even with .block() at the boundary, WebClient uses Netty's efficient I/O model.
  If we later need async (fire 10 webhooks in parallel), we drop .block() and
  compose with Mono.zip() — no code rewrite needed.

  WHY .block() HERE?
  ──────────────────
  The dispatcher needs to know whether the send succeeded BEFORE saving the
  NotificationLog status (SENT vs FAILED). If we went fully reactive
  (subscribe-and-forget), we'd lose the ability to catch exceptions and
  mark the log entry as FAILED. The .block() gives us synchronous
  error-handling semantics while keeping the non-blocking I/O underneath.

  FAILURE SCENARIOS:
  ─────────────────
  a) Connection refused (nothing listening on port 9999):
     → Netty throws io.netty.channel.ConnectTimeoutException or
       java.net.ConnectException: Connection refused
     → Caught by outer catch(Exception e)
     → log.warn("Failed to send webhook notification for userId: 42, subject: ...")
     → Control returns to dispatcher, which saves FAILED NotificationLog

  b) Timeout (server accepts connection but never responds):
     → Reactor signals TimeoutException after response-timeout (from HttpClient config)
     → Same catch path → FAILED log

  c) Server returns 4xx/5xx:
     → WebClient.retrieve() throws WebClientResponseException
     → Same catch path → FAILED log

  d) JSON serialization fails (impossible for Map<String,Object> with primitives):
     → Would be caught at .bodyValue() → same path
```

---

#### Part C: Email Channel — JavaMailSender + SMTP (MailHog)

```
STEP 6-EMAIL: EmailChannelStrategy.send(42L, subject, body)
─────────────────────────────────────────────────────────────────────────────
  Location: com.equitycart.notification.service.channel.impl.EmailChannelStrategy

  Field values (injected at startup):
    javaMailSender → auto-configured by Spring Boot's spring-boot-starter-mail
                     (backed by JavaMailSenderImpl which wraps javax.mail.Session)
    recipientEmail → "demo@equitycart.local"  (from application.yml)
    senderEmail → "noreply@equitycart.local"  (default value in @Value)

  6a. Create the message object:
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom("noreply@equitycart.local");
      message.setTo("demo@equitycart.local");
      message.setSubject("Trade Executed: Executed 10 shares of AAPL");
      message.setText("Your trade for 10 shares of AAPL at $150 has been executed.");

  6b. Send via JavaMailSender:
      javaMailSender.send(message);

  WHAT HAPPENS INSIDE JavaMailSender.send():
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ 1. JavaMailSenderImpl creates a MimeMessage from the SimpleMailMessage    │
  │    (internally: MimeMessage wraps JavaMail's javax.mail.internet.*)      │
  │                                                                          │
  │ 2. Opens SMTP connection (Transport.connect()):                          │
  │    - Creates TCP socket to localhost:1025 (spring.mail.host + port)      │
  │    - SMTP handshake:                                                     │
  │      Client: EHLO localhost                                              │
  │      Server: 250-mailhog Hello localhost                                 │
  │      Server: 250 PIPELINING                                              │
  │                                                                          │
  │ 3. SMTP MAIL FROM command:                                               │
  │      Client: MAIL FROM:<noreply@equitycart.local>                        │
  │      Server: 250 Ok                                                      │
  │                                                                          │
  │ 4. SMTP RCPT TO command:                                                 │
  │      Client: RCPT TO:<demo@equitycart.local>                             │
  │      Server: 250 Ok                                                      │
  │                                                                          │
  │ 5. SMTP DATA command:                                                    │
  │      Client: DATA                                                        │
  │      Server: 354 End data with <CR><LF>.<CR><LF>                         │
  │      Client: From: noreply@equitycart.local                              │
  │              To: demo@equitycart.local                                    │
  │              Subject: Trade Executed: Executed 10 shares of AAPL          │
  │              Date: Sat, 31 May 2026 14:30:00 +0000                       │
  │              Content-Type: text/plain; charset=UTF-8                      │
  │                                                                          │
  │              Your trade for 10 shares of AAPL at $150 has been executed.  │
  │              .                                                            │
  │      Server: 250 Ok: queued                                              │
  │                                                                          │
  │ 6. SMTP QUIT:                                                            │
  │      Client: QUIT                                                        │
  │      Server: 221 Bye                                                     │
  │                                                                          │
  │ 7. TCP connection closed                                                 │
  └──────────────────────────────────────────────────────────────────────────┘

  MailHog (dev SMTP trap):
  ───────────────────────
  In production: SMTP server would relay the email to the recipient's mail server.
  In dev: MailHog accepts ALL emails on port 1025 but never delivers them.
  Instead, it stores them in memory and displays them in a web UI at http://localhost:8025.
  This lets you verify email content without real delivery infrastructure.

  AUTOCONFIGURATION TRACE:
  ────────────────────────
  Spring Boot's MailSenderAutoConfiguration triggers when spring-boot-starter-mail is
  on the classpath. It reads spring.mail.* properties and creates:
    1. JavaMailSenderImpl bean with host=localhost, port=1025
    2. Internally creates javax.mail.Session with mail.smtp.host=localhost, mail.smtp.port=1025
    3. No auth configured (MailHog doesn't require it)

  WHY SimpleMailMessage (NOT MimeMessage)?
  ────────────────────────────────────────
  SimpleMailMessage = plain-text only, no attachments, no HTML.
  MimeMessage = full MIME support (HTML body, inline images, attachments, multipart).
  For notifications (short text alerts), SimpleMailMessage is sufficient and simpler.
  If we needed rich HTML emails (templates, logos), we'd use MimeMessageHelper + Thymeleaf.

  FAILURE SCENARIOS:
  ─────────────────
  a) MailHog not running (connection refused on port 1025):
     → javax.mail.MessagingException: Could not connect to SMTP host: localhost, port: 1025
     → Caught by catch(Exception e)
     → log.warn("Failed to send email notification to userId 42: Could not connect...")
     → Control returns to dispatcher → FAILED NotificationLog saved

  b) MailHog rejects the message (unlikely in dev, possible in prod SMTP):
     → javax.mail.SendFailedException: Invalid Addresses
     → Same catch path → FAILED log

  c) Network timeout (SMTP server hangs):
     → Blocks until javax.mail default socket timeout (infinite by default!)
     → In production, configure: spring.mail.properties.mail.smtp.timeout=5000
     → For MailHog (local), not a practical concern
```

---

#### Part D: Back in Dispatcher (After Channel Strategy Returns)

```
STEP 7: NotificationDispatcherImpl — Persist Audit Log
─────────────────────────────────────────────────────────────────────────────
  If strategy.send() returned normally (no exception):

    NotificationLog entry = NotificationLog.builder()
        .userId(42L)
        .notificationType(NotificationType.TRADE_EXECUTED)    // enum for DB
        .notificationChannel(NotificationChannel.WEBHOOK)     // or EMAIL
        .notificationStatus(NotificationStatus.SENT)
        .subject("Trade Executed: Executed 10 shares of AAPL")
        .body("Your trade for 10 shares of AAPL at $150 has been executed.")
        .metadata("{\"tradeType\":\"BUY\"}")                  // JSON string
        .build();

    notificationLogRepository.save(entry);
    → Hibernate: INSERT INTO notification_log (user_id, notification_type, channel, status,
                 subject, body, metadata, created_at, updated_at) VALUES (42, 'TRADE_EXECUTED',
                 'WEBHOOK', 'SENT', '...', '...', '{"tradeType":"BUY"}', now(), now())

  If strategy.send() threw an exception (caught in outer try-catch):
    → Builds a FAILED NotificationLog with errorMessage = e.getMessage()
    → notificationLogRepository.save(failedEntry);

  RESULT: Every notification attempt (success or failure) is persisted.
  Queryable via: GET /api/notifications → returns full history for the user.

STEP 8: Consumer Offset Commit
─────────────────────────────────────────────────────────────────────────────
  After handleNotificationEvent() returns without throwing:
  Spring Kafka's listener container commits the consumer offset for this record.
  (AckMode.BATCH by default — offset committed after poll batch completes)
  This means: if the app crashes BEFORE offset commit, the message is re-delivered
  on restart (at-least-once semantics). The NotificationLog provides dedup capability
  if needed (check for existing log with same userId + type + timestamp).
```

---

#### Summary: Complete Lifecycle for WEBHOOK Channel

```
TradeServiceImpl.executeTrade()
  └─→ NotificationPublisher.publish(event)
       └─→ KafkaTemplate.send("portfolio-notification", "42", event)
            └─→ [Kafka Broker: partition log]
                 └─→ NotificationConsumer.handleNotificationEvent(event)
                      └─→ NotificationDispatcherImpl.dispatch(event)
                           ├─ resolve "webhookChannel" from Map
                           ├─ build subject + body (switch)
                           └─→ WebhookChannelStrategy.send(42, subject, body)
                                └─→ WebClient.post().uri("http://localhost:9999/webhook")
                                     .bodyValue({userId, subject, body, timestamp})
                                     .retrieve().toBodilessEntity().block()
                                     └─→ [Netty: TCP → HTTP POST → response]
                           └─ notificationLogRepository.save(SENT)
```

#### Summary: Complete Lifecycle for EMAIL Channel

```
TradeServiceImpl.executeTrade()
  └─→ NotificationPublisher.publish(event)
       └─→ KafkaTemplate.send("portfolio-notification", "42", event)
            └─→ [Kafka Broker: partition log]
                 └─→ NotificationConsumer.handleNotificationEvent(event)
                      └─→ NotificationDispatcherImpl.dispatch(event)
                           ├─ resolve "emailChannel" from Map
                           ├─ build subject + body (switch)
                           └─→ EmailChannelStrategy.send(42, subject, body)
                                └─→ SimpleMailMessage(from, to, subject, text)
                                     └─→ javaMailSender.send(message)
                                          └─→ [SMTP: EHLO → MAIL FROM → RCPT TO → DATA → QUIT]
                                               └─→ [MailHog: stores email, visible at :8025]
                           └─ notificationLogRepository.save(SENT)
```

**117. WebClient vs RestTemplate — When to Choose Which (2026-05-31)**

WebClient (Spring WebFlux, since Spring 5.0) is the modern replacement for RestTemplate (Spring 3.0, now in maintenance mode). Key differences: (1) RestTemplate creates a new thread per request (thread-per-connection model) — under 100 concurrent HTTP calls, threads exhaust; (2) WebClient uses Netty's event loop — a small pool of threads handles thousands of connections via non-blocking I/O; (3) WebClient returns Mono/Flux (reactive types) — you can compose parallel calls with Mono.zip(), add timeouts with .timeout(), retry with .retryWhen(), all declaratively; (4) RestTemplate blocks the calling thread on every call — no composition possible without manual threading. Even when you call .block() on WebClient (as we do in WebhookChannelStrategy), the underlying I/O is non-blocking — the block happens at your boundary, not at the network layer. In EquityCart, WebClient is used for both the Alpha Vantage client (fully reactive with Mono) and webhooks (reactive I/O with .block() at boundary for synchronous dispatch semantics).

**118. JavaMailSender and the SMTP Protocol — What Happens on the Wire (2026-05-31)**

JavaMailSender is Spring's abstraction over the JavaMail API (javax.mail / jakarta.mail). When you call `javaMailSender.send(message)`, Spring converts the SimpleMailMessage to a MimeMessage, opens a TCP socket to the SMTP server (host:port from config), and performs the SMTP protocol handshake: EHLO → MAIL FROM → RCPT TO → DATA → content → QUIT. Each command gets a numeric response code (250=OK, 354=ready for data, 550=rejected). The entire exchange is synchronous and blocking — the thread waits until the SMTP server accepts or rejects. In production, you'd configure TLS (STARTTLS), authentication (spring.mail.username/password), and socket timeouts. In dev, MailHog skips all of that — it accepts everything, stores in memory, and shows a web UI. MailHog was created in 2014 as a Go-based SMTP trap specifically for development testing — the idea came from Ruby's MailCatcher gem (2010) but MailHog adds a REST API for programmatic assertion in integration tests.

**Q106: "Why does WebhookChannelStrategy use WebClient.Builder instead of a pre-built WebClient?" (2026-05-31)**
A: Spring auto-configures a `WebClient.Builder` bean (not a `WebClient` bean) because the builder carries default configuration (codecs, filters, base URL) that each user might customize differently. Calling `.build()` on each request is cheap — it just copies the builder's settings into a new immutable WebClient instance. If we injected a pre-built WebClient, we'd share mutable state (e.g., if another bean modifies the same instance). The builder pattern guarantees each call site gets an independent, correctly-configured client.

**Q107: "Why not use @Async on the webhook call instead of .block()?" (2026-05-31)**
A: @Async would move the HTTP call to a separate thread pool, but then exceptions are lost (CompletableFuture never checked). The dispatcher needs the exception to decide SENT vs FAILED in the NotificationLog. With .block(), we get synchronous exception propagation: if the webhook 500s, the catch block fires and we persist FAILED. @Async would give us "fire-and-forget" with no status tracking — defeating the purpose of the audit log.
