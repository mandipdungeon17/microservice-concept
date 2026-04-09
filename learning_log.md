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

### Date: 2025-04-09

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

**7. Spring Boot Starters Explained**

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

---
