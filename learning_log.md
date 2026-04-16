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

---
