# Spring Security — Deep Dive Reference

> Complete debug-mode walkthrough of Spring Security in EquityCart. Every filter, every interception,
> every internal delegation explained as if stepping through with a debugger.
> Covers: filter chain architecture, JWT authentication, authorization, RBAC, CSRF, CORS,
> SecurityContext, and best practices.

---

## 1. The Big Picture — What Spring Security Actually Is

Spring Security is a **servlet filter chain** that intercepts every HTTP request BEFORE it reaches your controller. It's not annotations, not configuration — at runtime, it's a chain of `javax.servlet.Filter` (now `jakarta.servlet.Filter`) instances executed in order.

```
HTTP Request from client
       │
       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ Tomcat (Servlet Container)                                                │
│                                                                           │
│   DelegatingFilterProxy ("springSecurityFilterChain")                      │
│     └─→ FilterChainProxy                                                  │
│           └─→ SecurityFilterChain (your @Bean)                            │
│                 ├── DisableEncodeUrlFilter                                 │
│                 ├── WebAsyncManagerIntegrationFilter                       │
│                 ├── SecurityContextHolderFilter                            │
│                 ├── HeaderWriterFilter                                     │
│                 ├── LogoutFilter                                           │
│                 ├── JwtAuthFilter  ← YOUR CUSTOM FILTER (added here)      │
│                 ├── UsernamePasswordAuthenticationFilter (disabled/unused) │
│                 ├── RequestCacheAwareFilter                                │
│                 ├── SecurityContextHolderAwareRequestFilter                │
│                 ├── AnonymousAuthenticationFilter                          │
│                 ├── SessionManagementFilter                                │
│                 ├── ExceptionTranslationFilter                             │
│                 └── AuthorizationFilter  ← AUTHORIZATION CHECK HERE       │
│                                                                           │
│   Only if ALL filters pass:                                               │
│     DispatcherServlet → Controller → Service → Response                   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Key insight:** Spring Security runs BEFORE Spring MVC. Your controller never sees rejected requests.

---

## 2. SecurityConfig — Line-by-Line Debug-Mode Walkthrough

```java
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
    return httpSecurity
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole(UserRoles.ADMIN.name())
            .requestMatchers(HttpMethod.POST, "/api/products/**")
                .hasAnyRole(UserRoles.SELLER.name(), UserRoles.ADMIN.name())
            .anyRequest().authenticated())
        .build();
  }
}
```

### Line-by-line:

#### `@Configuration`

Marks this class as a source of bean definitions. Spring processes it during component scanning and invokes all `@Bean` methods to register beans in the ApplicationContext.

#### `@EnableMethodSecurity`

Activates method-level security annotations (`@PreAuthorize`, `@PostAuthorize`, `@Secured`). Without this, those annotations are silently ignored.

**How it works internally:** Creates a `MethodSecurityInterceptor` (AOP aspect) that wraps every method annotated with `@PreAuthorize`. The interceptor evaluates the SpEL expression BEFORE method execution. If it evaluates to false → AccessDeniedException → 403.

#### `@Bean public PasswordEncoder passwordEncoder()`

BCrypt hash function for password storage. When a user registers, the plain password is hashed: `passwordEncoder.encode("mypassword")` → `$2a$10$xN9KJ3oE...` (60-char hash).

**BCrypt internals:**

```
1. Generate random 16-byte salt
2. Derive encryption key from (password + salt) via Blowfish key schedule (cost factor: 2^10 = 1024 rounds)
3. Encrypt the constant "OrpheanBeholderScryDoubt" with the key (24 rounds)
4. Output: $2a$10$<salt><hash>   ($2a = version, $10 = cost factor)
```

**Why BCrypt:** Deliberately slow (configurable cost). SHA-256 hashes billions/second; BCrypt at cost=10 does ~1000/second. Makes brute-force infeasible.

#### `.csrf(AbstractHttpConfigurer::disable)`

Disables CSRF (Cross-Site Request Forgery) protection.

**Why disable for APIs:** CSRF protection works by embedding a random token in HTML forms — the server verifies it on submit, proving the request came from YOUR page, not a malicious third-party page. JWT-based APIs don't serve HTML forms — clients send the JWT in the Authorization header. CSRF attacks cannot forge custom headers (browser CORS prevents it). Therefore CSRF protection is unnecessary and would break API calls.

**Best Practice:**

- DISABLE CSRF for: stateless REST APIs with token-based auth (JWT, API keys)
- KEEP CSRF for: traditional server-rendered apps with session cookies (MVC + Thymeleaf)

#### `.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`

Tells Spring Security to NEVER create or use an HTTP session.

**What this changes:**

```
Without STATELESS:
  Request 1: authenticate → Spring creates HttpSession → stores SecurityContext in session
  Request 2: session cookie sent → Spring loads SecurityContext from session → authenticated
  Problem: sessions consume server memory, don't scale horizontally (sticky sessions needed)

With STATELESS:
  Request 1: authenticate → SecurityContext lives ONLY in ThreadLocal, dies after response
  Request 2: must re-authenticate (send JWT again) → JWT validated → SecurityContext recreated
  Benefit: no server state, any instance can handle any request (horizontal scaling)
```

**Best Practice:** ALWAYS use STATELESS for JWT-based APIs. Session-based auth is for browser apps with server-side rendering.

#### `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`

Inserts our custom JwtAuthFilter into the filter chain BEFORE the UsernamePasswordAuthenticationFilter position.

**Why "before"?** The filter chain has a defined order. UsernamePasswordAuthenticationFilter normally handles form login (username+password POST to /login). Since we use JWT, we need our filter to run BEFORE that position — it authenticates the request via the Authorization header instead.

**Filter ordering (Spring Security defaults):**

```
Position 100: ChannelProcessingFilter
Position 200: SecurityContextPersistenceFilter
Position 300: ConcurrentSessionFilter
Position 400: LogoutFilter
Position 500: ← JwtAuthFilter inserted HERE (before position 600)
Position 600: UsernamePasswordAuthenticationFilter
Position 700: RequestCacheAwareFilter
Position 900: AnonymousAuthenticationFilter
Position 1000: SessionManagementFilter
Position 1100: ExceptionTranslationFilter
Position 1200: AuthorizationFilter (was FilterSecurityInterceptor in older versions)
```

#### `.authorizeHttpRequests(...)` — The Authorization Rules

```java
.requestMatchers("/api/auth/**").permitAll()
```

Any URL matching `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh` → skip authorization entirely. Even unauthenticated requests pass.

```java
.requestMatchers("/api/admin/**").hasRole(UserRoles.ADMIN.name())
```

URLs under `/api/admin/` require the authenticated user to have `ROLE_ADMIN` authority.

**hasRole("ADMIN") internally checks for:** `ROLE_ADMIN` (Spring auto-prepends "ROLE\_" prefix).

```java
.requestMatchers(HttpMethod.POST, "/api/products/**")
    .hasAnyRole(UserRoles.SELLER.name(), UserRoles.ADMIN.name())
```

POST requests to product endpoints require SELLER or ADMIN. GET requests to same URL → fall through to `anyRequest().authenticated()`.

```java
.anyRequest().authenticated()
```

All other requests: must have a valid Authentication in SecurityContext (i.e., JWT was valid).

**Evaluation order matters:** Rules are checked top-to-bottom. First match wins. If `/api/auth/login` matches `permitAll()`, the later `anyRequest().authenticated()` never applies to it.

---

## 3. JwtAuthFilter — Complete Debug-Mode Walkthrough

### 3.1 Every Request Goes Through This Filter

```
STEP 1: HTTP Request arrives at Tomcat
─────────────────────────────────────────────────────────────────────────────
  POST /api/portfolio/trade HTTP/1.1
  Host: localhost:8080
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiIsInJvbGVzIj...
  Content-Type: application/json

  {"tickerSymbol": "AAPL", "quantity": 10, "tradeType": "BUY"}

STEP 2: Filter chain reaches JwtAuthFilter.doFilterInternal()
─────────────────────────────────────────────────────────────────────────────
  This filter extends OncePerRequestFilter:
  - Guarantees execution exactly ONCE per request (even with servlet forwards/includes)
  - Spring Security may dispatch a request multiple times internally — this prevents double-auth

STEP 3: Extract Bearer token from Authorization header
─────────────────────────────────────────────────────────────────────────────
  String bearerToken = request.getHeader("Authorization");
  // bearerToken = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0Mi..."

  if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      String token = bearerToken.substring("Bearer ".length());
      // token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiIsInJvbGVzIjpbIkNVU1RPTUVSIl0..."
  }

  WHY "Bearer" prefix?
  RFC 6750 defines the Bearer token scheme for OAuth2. The prefix identifies
  the authentication scheme (vs "Basic" for username:password, "Digest" for digest auth).
  Multiple auth schemes can coexist — the prefix disambiguates.

STEP 4: Validate the token — jwtService.validateToken(token)
─────────────────────────────────────────────────────────────────────────────
  Inside JwtServiceImpl.validateToken():

  try {
      extractAllClaims(token);  // If this doesn't throw → token is valid
      return true;
  } catch (Exception e) {
      return false;
  }

  Inside extractAllClaims():
      Jwts.parser()
          .verifyWith(getSigningKey())  // Load HMAC-SHA key from Base64 secret
          .build()
          .parseSignedClaims(token);    // Parse + verify + check expiration

  What parseSignedClaims() does internally (JJWT library):
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ 1. SPLIT token into 3 parts (separated by '.')                           │
  │    header.payload.signature                                              │
  │    eyJhbGciOiJIUzI1NiJ9                                                 │
  │    .eyJzdWIiOiI0MiIsInJvbGVzIjpbIkNVU1RPTUVSIl0sImlhdCI6MTcxNj...     │
  │    .kX7jPmN2vW8qYzR3_signature_bytes                                    │
  │                                                                          │
  │ 2. BASE64URL-DECODE header → {"alg":"HS256"}                             │
  │    → Algorithm: HMAC-SHA256                                              │
  │                                                                          │
  │ 3. BASE64URL-DECODE payload → JSON claims:                               │
  │    {                                                                     │
  │      "sub": "42",              ← subject (userId as string)              │
  │      "roles": ["CUSTOMER"],    ← custom claim                           │
  │      "iat": 1716000000,        ← issued at (epoch seconds)              │
  │      "exp": 1716003600         ← expires at (epoch seconds)             │
  │    }                                                                     │
  │                                                                          │
  │ 4. VERIFY SIGNATURE:                                                     │
  │    expected = HMAC-SHA256(base64(header) + "." + base64(payload), secretKey)
  │    actual   = base64url-decode(signature part of token)                   │
  │    if (expected != actual) → throw SignatureException("tampered!")        │
  │                                                                          │
  │ 5. CHECK EXPIRATION:                                                     │
  │    if (claims.exp < now()) → throw ExpiredJwtException                   │
  │                                                                          │
  │ 6. Return Jws<Claims> (parsed header + payload + verified signature)     │
  └──────────────────────────────────────────────────────────────────────────┘

  FAILURE SCENARIOS:
  a) Token is garbled (not 3 dot-separated parts) → MalformedJwtException
  b) Signature doesn't match → SignatureException (token was tampered)
  c) Token expired → ExpiredJwtException
  d) Wrong algorithm → UnsupportedJwtException
  All caught by try-catch → validateToken returns false → no authentication set

STEP 5: Extract userId and roles from validated token
─────────────────────────────────────────────────────────────────────────────
  Long userId = jwtService.extractUserId(token);
  // Parses token again (extractAllClaims), reads "sub" claim → "42" → Long.valueOf → 42L

  List<String> roles = jwtService.extractRoles(token);
  // Reads "roles" claim → ["CUSTOMER"]

STEP 6: Create Spring Security Authentication object
─────────────────────────────────────────────────────────────────────────────
  List<SimpleGrantedAuthority> authorities =
      roles.stream()
           .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
           .toList();
  // authorities = [ROLE_CUSTOMER]

  UsernamePasswordAuthenticationToken authenticationToken =
      new UsernamePasswordAuthenticationToken(userId, null, authorities);
  // Three-arg constructor = AUTHENTICATED token
  //   principal = 42L (userId)
  //   credentials = null (we don't store the password)
  //   authorities = [ROLE_CUSTOMER]
  //   authenticated = true (set by 3-arg constructor)

  WHY UsernamePasswordAuthenticationToken for JWT?
  Despite the name, it's just a convenient Authentication implementation.
  We don't use username/password — we use it as a "carrier" for:
  - principal (userId) — accessible via SecurityContextHolder
  - authorities (roles) — used by @PreAuthorize and hasRole() checks

STEP 7: Store Authentication in SecurityContext
─────────────────────────────────────────────────────────────────────────────
  SecurityContextHolder.getContext().setAuthentication(authenticationToken);

  HOW SecurityContextHolder WORKS:
  ┌──────────────────────────────────────────────────────────────────────┐
  │ SecurityContextHolder (static utility class)                          │
  │   └── uses ThreadLocal<SecurityContext> (default strategy)            │
  │         └── SecurityContext                                           │
  │               └── Authentication (our UsernamePasswordAuthToken)      │
  │                     ├── principal: 42L                                │
  │                     ├── credentials: null                             │
  │                     ├── authorities: [ROLE_CUSTOMER]                  │
  │                     └── authenticated: true                           │
  └──────────────────────────────────────────────────────────────────────┘

  ThreadLocal means: each request-handling thread has its OWN SecurityContext.
  Thread A (user 42) and Thread B (user 99) have independent contexts.
  After the request completes, the ThreadLocal is cleared (stateless — no session storage).

STEP 8: Continue filter chain
─────────────────────────────────────────────────────────────────────────────
  filterChain.doFilter(request, response);
  // Passes control to the NEXT filter in the chain
  // Eventually reaches AuthorizationFilter → checks rules → allows/denies

STEP 8-ALT: No Authorization header (unauthenticated request)
─────────────────────────────────────────────────────────────────────────────
  bearerToken = request.getHeader("Authorization");
  // bearerToken = null (no header sent)

  if (bearerToken != null && ...) → FALSE, skip everything

  filterChain.doFilter(request, response);
  // SecurityContext has NO Authentication → anonymous
  // AuthorizationFilter will check: is this URL permitAll()?
  //   /api/auth/login → YES → allow
  //   /api/portfolio/trade → NO (.authenticated() required) → 403 Forbidden
```

---

## 4. Authorization — How Access Decisions Are Made

### 4.1 URL-Level Authorization (AuthorizationFilter)

After all authentication filters have run, the `AuthorizationFilter` (last in chain) evaluates:

```
STEP 1: AuthorizationFilter.doFilter() invoked
─────────────────────────────────────────────────────────────────────────────
  Gets the current request URL: /api/portfolio/trade (POST)
  Gets the Authentication from SecurityContext: userId=42, roles=[ROLE_CUSTOMER]

STEP 2: Match against authorization rules (in order)
─────────────────────────────────────────────────────────────────────────────
  Rule 1: "/api/auth/**".permitAll()
    → Does /api/portfolio/trade match /api/auth/**? NO → next rule

  Rule 2: "/api/admin/**".hasRole("ADMIN")
    → Does /api/portfolio/trade match /api/admin/**? NO → next rule

  Rule 3: POST "/api/products/**".hasAnyRole("SELLER", "ADMIN")
    → Is this a POST to /api/products/**? NO (it's /api/portfolio/trade) → next rule

  Rule 4: anyRequest().authenticated()
    → Is there a valid Authentication in SecurityContext? YES (userId=42)
    → AUTHORIZED ✓

STEP 3: Request proceeds to DispatcherServlet → Controller
─────────────────────────────────────────────────────────────────────────────
  If authorization FAILS:
  → AuthorizationFilter throws AccessDeniedException
  → ExceptionTranslationFilter catches it
  → If user IS authenticated → 403 Forbidden
  → If user is NOT authenticated → 401 Unauthorized (should be — but EquityCart has a known issue
    where it returns 403 instead of 401 due to missing custom AuthenticationEntryPoint)
```

### 4.2 Method-Level Authorization (@PreAuthorize)

```java
@PreAuthorize("hasRole('ADMIN')")
public void updateOrderStatus(Long orderId, OrderStatus newStatus) { ... }
```

**How it works (AOP proxy):**

```
STEP 1: Controller calls orderService.updateOrderStatus(orderId, newStatus)
─────────────────────────────────────────────────────────────────────────────
  orderService is OrderServiceImpl$$SpringCGLIB$$0 (proxy — because @EnableMethodSecurity)

STEP 2: Proxy intercepts → MethodSecurityInterceptor (AuthorizationManagerBeforeMethodInterceptor)
─────────────────────────────────────────────────────────────────────────────
  Evaluates SpEL expression: hasRole('ADMIN')

  Gets Authentication from SecurityContextHolder:
    authorities = [ROLE_CUSTOMER]

  hasRole('ADMIN') checks: does authorities contain "ROLE_ADMIN"?
    → [ROLE_CUSTOMER] contains ROLE_ADMIN? NO
    → Throws AccessDeniedException("Access Denied")

STEP 3: Exception propagates to ExceptionTranslationFilter
─────────────────────────────────────────────────────────────────────────────
  → Returns HTTP 403 Forbidden
  → Controller method NEVER executes (proxy blocked it)
```

**SpEL expressions available in @PreAuthorize:**

| Expression                            | Checks                                       |
| ------------------------------------- | -------------------------------------------- |
| `hasRole('ADMIN')`                    | User has ROLE_ADMIN authority                |
| `hasAnyRole('ADMIN', 'SELLER')`       | User has ROLE_ADMIN or ROLE_SELLER           |
| `isAuthenticated()`                   | User is authenticated (not anonymous)        |
| `#userId == authentication.principal` | Method parameter matches logged-in user's ID |
| `@myBean.check(#arg)`                 | Calls a bean method for custom logic         |

### 4.3 Accessing the Authenticated User in Controllers

```java
@GetMapping("/api/portfolio")
public ResponseEntity<PortfolioResponse> getPortfolio(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    // userId = 42L (stored by JwtAuthFilter in step 6)
    return ResponseEntity.ok(portfolioFacade.getPortfolio(userId));
}
```

**Alternative (more common in EquityCart):**

```java
Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
```

Both access the same ThreadLocal SecurityContext.

---

## 5. JWT (JSON Web Token) — Structure & Security Properties

### 5.1 JWT Anatomy

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiIsInJvbGVzIjpbIkNVU1RPTUVSIl0sImlhdCI6MTcxNjAwMDAwMCwiZXhwIjoxNzE2MDAzNjAwfQ.kX7jPmN2vW8_signature
│                     │                                                                                                               │
│  HEADER (Base64)    │  PAYLOAD (Base64)                                                                                             │ SIGNATURE
│                     │                                                                                                               │
│ {"alg":"HS256"}     │ {"sub":"42","roles":["CUSTOMER"],"iat":1716000000,"exp":1716003600}                                           │ HMAC(header.payload, secret)
```

**Three parts separated by dots:**

1. **Header** — algorithm + token type (always visible, not encrypted)
2. **Payload** — claims (data). Base64 encoded, NOT encrypted. Anyone can decode and read.
3. **Signature** — cryptographic proof that header+payload haven't been tampered with

### 5.2 HMAC-SHA256 Signing — How It Prevents Tampering

```
Signing (at token creation time):
  input  = base64url(header) + "." + base64url(payload)
  key    = HMAC secret (from application.yml: jwt.secret)
  output = HMAC-SHA256(input, key) → 32 bytes → base64url-encoded → signature

Verification (at every request):
  1. Split token into header.payload.signature
  2. Recalculate: expected = HMAC-SHA256(header + "." + payload, key)
  3. Compare: expected == provided signature?
     YES → token is authentic (not tampered)
     NO  → someone modified the payload → REJECT

WHY this prevents tampering:
  Attacker changes payload: {"sub":"42"} → {"sub":"1"} (escalate to admin user)
  But cannot recalculate signature without the secret key
  → Server recalculates with real key → signature mismatch → REJECTED
```

### 5.3 Token Lifecycle in EquityCart

```
┌─────────────────────────────────────────────────────────────────────┐
│ TOKEN LIFECYCLE                                                       │
│                                                                      │
│ 1. User sends POST /api/auth/login {email, password}                 │
│    → AuthService validates credentials (BCrypt compare)              │
│    → JwtService.generateAccessToken(user, roles)                     │
│      → Creates JWT: sub=userId, roles=["CUSTOMER"], exp=now+1h       │
│      → Signs with HMAC-SHA256                                        │
│    → JwtService.generateRefreshToken()                               │
│      → UUID.randomUUID() (opaque string, stored in DB)               │
│    → Returns: { accessToken: "eyJ...", refreshToken: "uuid-..." }    │
│                                                                      │
│ 2. Client stores tokens (localStorage, httpOnly cookie, etc.)        │
│                                                                      │
│ 3. Every subsequent request:                                         │
│    Authorization: Bearer eyJ...                                      │
│    → JwtAuthFilter validates + extracts userId + sets SecurityContext │
│                                                                      │
│ 4. Access token expires (after 1 hour):                              │
│    → Client sends POST /api/auth/refresh { refreshToken: "uuid-..." }│
│    → AuthService validates refresh token against DB                   │
│    → If valid + not revoked + not expired: generate new access token  │
│    → Old refresh token revoked, new one issued (ROTATION)            │
│                                                                      │
│ 5. Logout:                                                           │
│    → POST /api/auth/logout { refreshToken: "uuid-..." }              │
│    → Mark refresh token as revoked in DB                              │
│    → Access token continues to work until expiration (stateless!)    │
│      (This is a known limitation of stateless JWTs)                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.4 Access Token vs Refresh Token

|                  | Access Token                    | Refresh Token                     |
| ---------------- | ------------------------------- | --------------------------------- |
| Format           | JWT (self-contained)            | Opaque UUID (meaningless string)  |
| Storage          | Client-side                     | Client-side + DB (for revocation) |
| Lifetime         | Short (1 hour)                  | Long (7 days)                     |
| Sent with        | Every API request               | Only to /api/auth/refresh         |
| Revocable?       | NO (stateless, no server check) | YES (DB lookup: revoked=true)     |
| Contains claims? | Yes (userId, roles)             | No (just a lookup key)            |

**Why two tokens?**

- Access tokens are FAST to validate (no DB call — just signature check)
- But can't be revoked (fire-and-forget after signing)
- Refresh tokens CAN be revoked (checked against DB)
- Short access lifetime + revocable refresh = balance between performance and control

### 5.5 Best Practices for JWT

- DO: Keep access token lifetime short (15min–1hour). Shorter = smaller window if token stolen.
- DO: Use refresh token rotation (new refresh token on every refresh call). Detects token theft.
- DO: Store secret key in environment variable, NEVER in source code
- DO: Use at least 256-bit keys for HMAC-SHA256 (32 bytes minimum)
- DON'T: Store sensitive data in JWT payload (it's Base64, not encrypted — anyone can read it)
- DON'T: Send JWT in URL parameters (logged in server access logs, browser history)
- DON'T: Use JWT for server-side sessions (defeats stateless purpose if you check DB every request)
- DON'T: Rely on client-side token expiration check only — always verify server-side

---

## 6. Password Security — BCrypt Deep Dive

### 6.1 Registration Flow

```
STEP 1: User sends: POST /api/auth/register { "email": "user@x.com", "password": "MyPass123" }

STEP 2: AuthService.register():
  String hashedPassword = passwordEncoder.encode("MyPass123");
  // BCrypt: generates random salt, hashes password + salt
  // Result: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
  //          │  │ │ │                          │                            │
  //          │  │ │ └─ 22-char salt (Base64)   └─ 31-char hash (Base64)     │
  //          │  │ └─ cost factor (2^10 = 1024 Blowfish rounds)              │
  //          │  └─ BCrypt version                                            │
  //          └─ BCrypt marker                                                │

STEP 3: Store hashed password in User entity → PostgreSQL
  user.setPassword("$2a$10$N9qo8uLOickgx2ZMRZoMye...");
  userRepository.save(user);
  // Plain password NEVER stored anywhere
```

### 6.2 Login Flow

```
STEP 1: User sends: POST /api/auth/login { "email": "user@x.com", "password": "MyPass123" }

STEP 2: AuthService.login():
  User user = userRepository.findByEmail("user@x.com").orElseThrow(...);

  boolean matches = passwordEncoder.matches("MyPass123", user.getPassword());
  // BCrypt.matches():
  // 1. Extract salt from stored hash: "$2a$10$N9qo8uLOickgx2ZMRZoMye"
  // 2. Hash the input password WITH THAT SAME SALT
  // 3. Compare resulting hash with stored hash
  // 4. If equal → password correct, if not → wrong password

  // WHY re-hash instead of decrypt?
  // BCrypt is a ONE-WAY hash. You CANNOT reverse it.
  // You can only check: "does this input produce the same hash?"
```

### 6.3 Best Practices

- DO: Use BCrypt (or Argon2) for password storage — never SHA-256, MD5, or plain text
- DO: Use cost factor 10–12 (balance between security and login latency)
- DO: Let BCrypt generate the salt (built into the function — don't manage salts manually)
- DON'T: Log passwords at any level (even DEBUG)
- DON'T: Compare passwords with `equals()` — use `passwordEncoder.matches()` (constant-time comparison prevents timing attacks)
- DON'T: Store password hints or reversible encryption

---

## 7. CORS (Cross-Origin Resource Sharing)

### 7.1 What CORS Prevents

When a web app at `http://localhost:3000` (React frontend) makes a request to `http://localhost:8080` (Spring API), the browser blocks it by default — **Same-Origin Policy**. CORS is the mechanism to ALLOW specific cross-origin requests.

### 7.2 How CORS Works (Browser → Server Negotiation)

```
STEP 1: Browser detects cross-origin request
─────────────────────────────────────────────────────────────────────────────
  JavaScript on http://localhost:3000 calls fetch("http://localhost:8080/api/portfolio")
  Origins differ (port 3000 ≠ 8080) → browser triggers CORS

STEP 2: Preflight request (for non-simple requests)
─────────────────────────────────────────────────────────────────────────────
  Browser sends OPTIONS request BEFORE the actual request:

  OPTIONS /api/portfolio HTTP/1.1
  Origin: http://localhost:3000
  Access-Control-Request-Method: GET
  Access-Control-Request-Headers: Authorization

  Server must respond with CORS headers:
  Access-Control-Allow-Origin: http://localhost:3000
  Access-Control-Allow-Methods: GET, POST, PUT, DELETE
  Access-Control-Allow-Headers: Authorization, Content-Type
  Access-Control-Max-Age: 3600

STEP 3: If preflight passes → browser sends actual request
─────────────────────────────────────────────────────────────────────────────
  GET /api/portfolio HTTP/1.1
  Origin: http://localhost:3000
  Authorization: Bearer eyJ...

  Server response includes:
  Access-Control-Allow-Origin: http://localhost:3000

  Browser allows JavaScript to read the response.
```

### 7.3 Spring Configuration (when needed)

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:3000"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

### 7.4 Best Practices

- DO: Configure CORS at the Gateway level (not each microservice) when using API Gateway
- DO: Specify exact allowed origins in production (never `*` with credentials)
- DO: Set `Access-Control-Max-Age` to reduce preflight requests (3600s = cache for 1 hour)
- DON'T: Use `allowedOrigins("*")` with `allowCredentials(true)` — browsers reject this combination
- DON'T: Disable CORS entirely — it exists to protect users

---

## 8. SecurityContext Threading Model

### 8.1 ThreadLocal Strategy (Default)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Thread Pool (Tomcat worker threads)                                           │
│                                                                              │
│ Thread-1 (handling request from User 42):                                    │
│   ThreadLocal<SecurityContext> = { auth: {principal: 42, roles: [CUSTOMER]} }│
│                                                                              │
│ Thread-2 (handling request from User 99):                                    │
│   ThreadLocal<SecurityContext> = { auth: {principal: 99, roles: [ADMIN]} }   │
│                                                                              │
│ Thread-3 (idle):                                                             │
│   ThreadLocal<SecurityContext> = null (no active request)                    │
│                                                                              │
│ ISOLATION: Thread-1 cannot see Thread-2's SecurityContext. Each request      │
│ is completely independent. After request completes, ThreadLocal is cleared.  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Problem: Async Operations Lose SecurityContext

```java
CompletableFuture.runAsync(() -> {
    // This runs on ForkJoinPool.commonPool thread — NOT the request thread
    Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    // NPE! Authentication is null here — different thread's ThreadLocal
});
```

**Solutions:**

1. Pass userId as a parameter (simplest — EquityCart does this)
2. Configure `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` (propagates to child threads)
3. Use `DelegatingSecurityContextRunnable` wrapper

### 8.3 Best Practices

- DO: Extract userId early (in controller/facade) and pass it as a method parameter
- DON'T: Access SecurityContextHolder deep in service/repository layers (coupling to servlet context)
- DON'T: Assume SecurityContext is available in @Async methods or CompletableFuture lambdas

---

## 9. Common Security Vulnerabilities & Defenses

### 9.1 OWASP Top 10 (2021) — EquityCart Relevance

| #   | Vulnerability             | EquityCart Defense                         | Status                 |
| --- | ------------------------- | ------------------------------------------ | ---------------------- |
| A01 | Broken Access Control     | @PreAuthorize + URL rules + userId scoping | Protected              |
| A02 | Cryptographic Failures    | BCrypt passwords, HMAC-SHA256 JWT          | Protected              |
| A03 | Injection (SQL, NoSQL)    | Spring Data JPA parameterized queries      | Protected              |
| A04 | Insecure Design           | —                                          | N/A (learning project) |
| A05 | Security Misconfiguration | CSRF disabled (correct for JWT API)        | Protected              |
| A06 | Vulnerable Components     | Dependencies managed via BOM               | Partially              |
| A07 | Auth Failures             | Refresh token rotation, expiry             | Protected              |
| A08 | Data Integrity Failures   | JWT signature verification                 | Protected              |
| A09 | Logging Failures          | Log4j on all operations                    | Partially              |
| A10 | SSRF                      | No user-controlled URLs in WebClient calls | Protected              |

### 9.2 SQL Injection (Prevented by Design)

```
VULNERABLE (raw string concatenation):
  String query = "SELECT * FROM user WHERE email = '" + email + "'";
  // Attacker sends: email = "admin'--"
  // Query becomes: SELECT * FROM user WHERE email = 'admin'--'
  // The -- comments out the rest → returns admin user without password check

PROTECTED (Spring Data JPA — parameterized):
  @Query("SELECT u FROM User u WHERE u.email = :email")
  Optional<User> findByEmail(@Param("email") String email);
  // Parameter is bound separately — never concatenated into SQL
  // Attacker's input treated as literal string value, not SQL code
```

### 9.3 Best Practices

- DO: Always use parameterized queries (JPA does this automatically)
- DO: Validate all input at the boundary (Bean Validation on DTOs)
- DO: Scope data access by userId (never return another user's data)
- DO: Use principle of least privilege (default deny, explicitly allow)
- DON'T: Trust client-side validation alone (always re-validate server-side)
- DON'T: Expose internal IDs in error messages or stack traces
- DON'T: Log sensitive data (passwords, tokens, credit cards)
- DON'T: Return detailed error messages in production (attackers use them for reconnaissance)

---

## 10. Security Architecture for Microservices (Phase 7+ Preview)

### 10.1 Current (Monolith) — Simple

```
Client → [JWT in header] → JwtAuthFilter → SecurityContext → Controller
           Single point of auth. One filter chain. One SecurityContext.
```

### 10.2 Future (Microservices) — Gateway + Token Propagation

```
Client → [JWT] → API Gateway
                    ├── Validates JWT (signature + expiry)
                    ├── Extracts userId, roles
                    ├── Routes to correct service
                    └── Forwards JWT (or internal header) to downstream
                              │
                              ▼
                    Downstream Service (e.g., Portfolio)
                    ├── Re-validates JWT OR trusts gateway header
                    ├── Sets SecurityContext from propagated auth
                    └── Processes request with userId context

Inter-service calls (Feign):
  Portfolio → Ledger:
    FeignRequestInterceptor adds Authorization header from SecurityContext
    Ledger validates JWT same as external request
```

### 10.3 Gateway vs Per-Service Auth

| Strategy                             | Pros                                    | Cons                                         |
| ------------------------------------ | --------------------------------------- | -------------------------------------------- |
| Gateway-only auth                    | Fast (validate once), simple downstream | Compromised internal network = no protection |
| Per-service auth (defense in depth)  | Each service independently secure       | Repeated validation work                     |
| Gateway + lightweight internal check | Best balance                            | Slight complexity                            |

**EquityCart will use:** Gateway validates fully + propagates JWT. Downstream services verify signature (lightweight, no DB call) for defense in depth.

---

## 11. Token Storage on Client Side — Security Trade-offs

| Storage           | XSS Safe?             | CSRF Safe?                   | When to Use                            |
| ----------------- | --------------------- | ---------------------------- | -------------------------------------- |
| localStorage      | NO (JS can read)      | YES (not sent automatically) | SPAs with XSS protection (CSP headers) |
| httpOnly cookie   | YES (JS can't read)   | NO (sent with every request) | Traditional web apps with CSRF tokens  |
| Memory (variable) | YES (dies on refresh) | YES                          | Short sessions, high-security apps     |
| sessionStorage    | NO (JS can read)      | YES                          | Single-tab sessions                    |

**EquityCart (API-only):** Client manages token storage. For a React frontend, recommended: httpOnly cookie with SameSite=Strict (prevents both XSS and CSRF for same-site requests).

---

## 12. Known Issues & Future Fixes

### 12.1 403 Instead of 401 (EquityCart Known Issue)

**Problem:** Unauthenticated requests (no JWT) get 403 Forbidden instead of 401 Unauthorized.

**Root Cause:** Spring Security's default `AuthenticationEntryPoint` returns 403 when it should return 401. Without a custom entry point, the `ExceptionTranslationFilter` defaults to `Http403ForbiddenEntryPoint`.

**Fix (planned for Phase 8):**

```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint((request, response, authException) -> {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
    })
)
```

### 12.2 JWT Cannot Be Revoked (Stateless Limitation)

**Problem:** After logout, the access token is still valid until expiry.

**Acceptable because:** Access token lifetime is short (1 hour). For sensitive operations (password change, fund transfer), you'd add a token blacklist (Redis set of revoked jti claims, checked on each request). Trade-off: adds a DB/cache call per request (partially defeats stateless benefits).

---

## 13. Security Best Practices Summary

### Authentication

- Use short-lived access tokens (15min–1h) + long-lived refresh tokens
- Implement refresh token rotation (new token on each refresh)
- Store passwords with BCrypt (cost ≥ 10)
- Never log tokens or passwords at any level

### Authorization

- Default deny: `anyRequest().authenticated()` as the LAST rule
- Scope ALL data access by userId (never return other users' data)
- Use @PreAuthorize for fine-grained method-level checks
- Avoid role-based logic in service code — keep it in annotations

### Headers & Transport

- Use HTTPS in production (TLS terminates at load balancer or gateway)
- Set security headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`
- Disable HSTS in dev, enable in prod
- Set `SameSite=Strict` on cookies

### Input Validation

- Validate ALL input at controller layer (Bean Validation)
- Use parameterized queries exclusively (JPA/Hibernate)
- Sanitize output (escape HTML if rendering user content)
- Limit request body size (spring.servlet.multipart.max-file-size)

### Error Handling

- Never expose stack traces in production responses
- Use consistent error response format (ErrorResponse DTO)
- Log security events (failed logins, authorization failures) at WARN level
- Don't reveal whether an email exists in registration/login errors (prevents enumeration)

---

## Spring Security + Actuator Endpoints (Phase 7 — 2026-06-02)

### Problem: Actuator returns 403 Despite exposure.include Configuration

Spring Security's FilterChain runs before actuator endpoint resolution. Even if actuator is configured to expose endpoints, Spring Security can block them entirely.

**Diagnostic steps for HTTP 403 on /actuator:**
1. Is `/actuator/**` in `requestMatchers(...).permitAll()`? → If no, security is blocking it
2. Is `management.endpoints.web.exposure.include` configured? → Necessary but not sufficient
3. Is there a `SecurityFilterChain` bean in the app? → If yes, explicit actuator rules are needed

**Fix: Explicitly permit /actuator in SecurityConfig:**
```java
http.authorizeHttpRequests(authz -> authz
    .requestMatchers("/actuator/health").permitAll()         // Public health check
    .requestMatchers("/actuator/**").hasRole("ADMIN")        // Sensitive endpoints: admin only
    .requestMatchers("/api/auth/**").permitAll()
    .anyRequest().authenticated()
);
```

**Why this happens:**
- api-gateway (8080): No `SecurityFilterChain` defined → Spring Boot default allows actuator
- equitycart (8082): Has custom `SecurityFilterChain` → `.anyRequest().authenticated()` blocks everything including actuator

**Principle: Two independent access control layers:**
1. **Management layer** (`management.endpoints.web.exposure.include`) — controls WHICH endpoints exist
2. **Security layer** (`SecurityFilterChain`) — controls WHO can call them

Both must allow access. Configuring management alone is insufficient when Spring Security is active.

**Actuator endpoint exposure by role:**
| Endpoint | Recommended access | Reason |
|----------|-------------------|---------|
| `/actuator/health` | Public | Load balancers, monitoring need this without auth |
| `/actuator/info` | Public | CI/CD pipelines display version info |
| `/actuator/metrics` | ADMIN | Exposes memory, GC, request rates |
| `/actuator/env` | ADMIN (or disabled) | May expose environment variables with secrets |
| `/actuator/configprops` | ADMIN (or disabled) | Shows all properties including passwords |
| `/actuator/beans` | ADMIN (or disabled) | Internal Spring bean wiring — not for clients |

**EquityCart rule (applied in Phase 7):** Expose `health,metrics,info` in equitycart-config, allow `/actuator/**` in SecurityConfig for admin-only services. Public health check permitted for gateway.

---

## 8. Inter-Service Authentication Propagation (Phase 8)

### Defense-in-Depth: Why Both Gateway AND Service Validate

```
Option A (Gateway-only — INSECURE):
  User → Gateway (validates JWT) → strips token, passes userId header → order-service
  
  Attack vector: order-service calls product-service via Feign (bypasses gateway)
  → Attacker compromises order-service → sends fake X-User-Id header to product-service
  → product-service trusts it → privilege escalation

Option B (Defense-in-depth — CORRECT):
  User → Gateway (validates JWT, fast-fail on bad tokens) → forwards full token
  → order-service (validates JWT independently) → Feign call with same token
  → product-service (validates JWT independently) → no trust without proof
  
  Even if order-service is compromised, attacker cannot forge a valid JWT
  (requires the secret key, which is not in memory of a compromised service)
```

### Token Propagation Pattern

**Problem:** After all services enforce JWT auth, inter-service Feign calls break because Feign creates new HTTP requests without the original Authorization header.

**Solution:** `FeignAuthorizationInterceptor` reads the original request's token from `RequestContextHolder` (ThreadLocal) and copies it to the outgoing Feign request.

```
User's request (with token)
    │
    ▼
order-service servlet thread
    │
    ├── FrameworkServlet stores request in ThreadLocal
    ├── JwtAuthenticationFilter validates token → sets SecurityContext
    ├── OrderController.createOrder() processes business logic
    │
    ├── productFeignClient.getProduct(id)
    │       │
    │       ▼
    │   FeignAuthorizationInterceptor.apply(template)
    │       1. RequestContextHolder.getRequestAttributes() → reads ThreadLocal
    │       2. getRequest().getHeader("Authorization") → "Bearer eyJ..."
    │       3. template.header("Authorization", token) → copies to outgoing request
    │       │
    │       ▼
    │   product-service receives request WITH token
    │       1. JwtAuthenticationFilter validates → sets SecurityContext
    │       2. @PreAuthorize checks → authorized
    │       3. Returns product data
    │
    └── order-service continues with product data
```

### Token Propagation vs Token Exchange

| Aspect | Propagation (Phase 8 Steps 1-4) | Exchange (Phase 8 Step 6+) |
|--------|----------------------------------|---------------------------|
| **How** | Copy original JWT to outgoing request | Call IdP to get new scoped-down token |
| **Extra latency** | 0 (just header copy) | 1 HTTP call to IdP per hop |
| **Downstream sees** | Full user identity + ALL roles | Only the permissions needed for that call |
| **If service compromised** | Attacker gets full user token | Attacker gets limited-scope token |
| **Implementation** | 3 lines in RequestInterceptor | OAuth2 Token Exchange grant (RFC 8693) |
| **When to use** | Same team, same trust boundary | Cross-org, different security zones |

### Thread Safety: Where Propagation Breaks

| Context | RequestContextHolder | Why | Solution |
|---------|---------------------|-----|----------|
| Servlet request thread | Available ✓ | FrameworkServlet stores it | FeignAuthorizationInterceptor works |
| Kafka consumer thread | NULL ✗ | No HTTP request originated this thread | Use service-account token (client-credentials) |
| @Async child thread | NULL ✗ | Plain ThreadLocal doesn't propagate to child threads | Extract token before spawning async, or use DelegatingSecurityContextExecutor |
| @Scheduled thread | NULL ✗ | Scheduler threads have no request | Use service-account token |
| WebSocket handler | NULL ✗ | After initial handshake, no per-message HTTP request | Store token during handshake, inject manually |

### SecurityContext vs RequestContextHolder: Two Separate Thread-Local Stores

```
┌─── Thread-42 (handling user request) ───────────────────────────┐
│                                                                   │
│  SecurityContextHolder (ThreadLocal):                             │
│    Authentication = UsernamePasswordAuthenticationToken            │
│      principal = 42L (userId)                                     │
│      credentials = null                                           │
│      authorities = [ROLE_CUSTOMER, ROLE_SELLER]                   │
│                                                                   │
│  RequestContextHolder (ThreadLocal):                              │
│    ServletRequestAttributes                                       │
│      request = HttpServletRequest (full original request object)  │
│        .getHeader("Authorization") = "Bearer eyJhbG..."           │
│        .getHeader("X-Correlation-Id") = "7f3a9c2b-..."            │
│        .getMethod() = "POST"                                      │
│        .getRequestURI() = "/api/orders"                           │
│                                                                   │
│  MDC ThreadContext (InheritableThreadLocal):                      │
│    correlationId = "7f3a9c2b-..."                                 │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

SecurityContextHolder → set by JwtAuthenticationFilter (extracted claims)
RequestContextHolder → set by FrameworkServlet (raw HTTP request object)
MDC ThreadContext    → set by MdcCorrelationFilter (correlation ID string)

All three exist simultaneously on the same thread, serving different purposes:
- SecurityContextHolder: "WHO is this user?" (for @PreAuthorize, role checks)
- RequestContextHolder: "WHAT did they send?" (for interceptors that need raw headers)
- MDC ThreadContext: "HOW do we trace this?" (for log correlation across services)
```

### Interview Questions

**Q: "How do you propagate authentication between microservices?"**
A: For synchronous Feign calls, a RequestInterceptor reads the Authorization header from RequestContextHolder (ThreadLocal containing the original HTTP request) and copies it to the outgoing Feign RequestTemplate. For async/event-driven flows (Kafka), use service-account tokens via OAuth2 client-credentials grant.

**Q: "Should microservices trust the gateway or validate tokens independently?"**
A: Both. Gateway validates for fast-fail (reject bad tokens early, save network hops). Each service validates independently for defense-in-depth (Feign inter-service calls bypass gateway; compromised service can't forge tokens without the signing key).

**Q: "What happens to the SecurityContext when you spawn an async thread?"**
A: By default, nothing — SecurityContextHolder uses plain ThreadLocal, which doesn't propagate. Solutions: (1) use `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` globally (risky: thread pool reuse can leak context), (2) use `DelegatingSecurityContextExecutor` which wraps the executor to copy SecurityContext, (3) extract the needed info (userId, roles) before spawning async work and pass it explicitly.

**Q: "Why is token propagation acceptable within a single trust boundary but not across organizations?"**
A: Within one team's services, all services are equally trusted — if one is compromised, the attacker likely has access to the signing key anyway. Across organizations, services have different trust levels — downstream should only see what it needs (least privilege). Token exchange (RFC 8693) creates a scoped-down token per hop, limiting blast radius of a compromise.

---

## 9. Gateway Edge Security — Reactive JWT Pre-Validation (Phase 8 Step 4)

### Why Validate at the Gateway?

```
WITHOUT gateway validation:
  Bad token → gateway routes → order-service processes → validates → 401
  Cost: 2 network hops + downstream thread consumed + response travel back

WITH gateway validation:
  Bad token → gateway validates → 401 immediately
  Cost: 0 network hops beyond the gateway, instant rejection
```

The gateway acts as a **security perimeter** (edge firewall). It rejects obviously bad requests before they consume internal resources. But it does NOT replace per-service validation — it's an optimization, not a replacement.

### Reactive Gateway vs Servlet Services — Two Different Worlds

| Aspect | api-gateway (Reactive/WebFlux) | order-service (Servlet/MVC) |
|--------|-------------------------------|----------------------------|
| **Runtime** | Netty event loop | Tomcat thread pool |
| **Threading** | ~4 event loop threads handle ALL requests | 1 thread per request (200 default) |
| **Request object** | ServerHttpRequest (immutable) | HttpServletRequest (mutable) |
| **Filter interface** | GlobalFilter → Mono<Void> | OncePerRequestFilter → void |
| **Blocking allowed?** | NO — blocks event loop → all requests stall | YES — each thread is independent |
| **Error response** | DataBuffer + writeWith(Mono.just(buffer)) | response.setStatus() + writer.write() |
| **Security framework** | Cannot use servlet SecurityFilterChain | Uses commons SecurityAutoConfig |

### Filter Execution Order at Gateway

```
Request arrives at gateway (port 8080)
    │
    ▼
CorrelationIdGatewayFilter (HIGHEST_PRECEDENCE)
    │── Assigns X-Correlation-Id (UUID) to request + response headers
    │
    ▼
JwtValidationGatewayFilter (HIGHEST_PRECEDENCE + 1)
    │── Checks: is path in open-paths list?
    │   ├── YES → skip validation, proceed to routing
    │   └── NO → extract Authorization header
    │           ├── Missing/malformed → return 401 JSON (short-circuit)
    │           └── Present → JJWT parseSignedClaims()
    │                   ├── JwtException → return 401 JSON (short-circuit)
    │                   └── Valid → proceed to routing
    │
    ▼
Spring Cloud Gateway Route Predicates
    │── Match request path to configured route (/api/order/** → lb://order-service)
    │
    ▼
LoadBalancerClientFilter
    │── Resolve lb://order-service → actual host:port via Eureka
    │
    ▼
NettyRoutingFilter
    │── Forward request to downstream service (Authorization header intact)
    │
    ▼
Downstream service receives request (with original Bearer token)
```

### Reactive Response Writing: onUnauthorized() Explained

In servlet: `response.setStatus(401); response.getWriter().write(json); return;`

In reactive gateway:
```java
private Mono<Void> onUnauthorized(ServerWebExchange exchange, String reason) {
    // 1. Set HTTP status on the response
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    
    // 2. Set Content-Type header
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    
    // 3. Convert JSON string to bytes → wrap in DataBuffer
    //    DataBuffer = Netty's zero-copy byte container (avoids byte[] copying)
    byte[] bytes = ("{\"error\":\"Unauthorized\",\"message\":\"" + reason + "\"}").getBytes();
    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
    
    // 4. writeWith(Mono.just(buffer)) → writes body and COMPLETES the response
    //    Returning this Mono short-circuits the filter chain
    //    No downstream filters or route handlers execute
    return exchange.getResponse().writeWith(Mono.just(buffer));
}
```

Key insight: returning `writeWith()` from the filter method means "I'm done, don't call anything else." The filter chain is abandoned — the response is flushed to the client.

### What This Manual Filter Does NOT Handle (Fixed by OAuth2 in Step 7)

| Concern | Manual filter | OAuth2 Resource Server |
|---------|---------------|----------------------|
| Key rotation | Must restart gateway | JWKS endpoint auto-refreshes keys |
| Issuer validation | Not checked | Validates `iss` claim matches configured issuer |
| Audience validation | Not checked | Validates `aud` claim matches this service |
| Clock skew tolerance | 0 tolerance | Configurable (default 60s) |
| Key caching | Parses key from config every request | NimbusJwtDecoder caches decoded key |
| Multiple issuers | Not supported | Configurable multi-tenant issuers |

### Interview Questions

**Q: "Where do you validate tokens — gateway, service, or both?"**
A: Both. Gateway validates for fast-fail (rejects expired/malformed before routing). Each service validates independently because Feign inter-service calls bypass the gateway. The gateway is an optimization; per-service validation is the security guarantee.

**Q: "Why can't the gateway use the same SecurityFilterChain as your services?"**
A: Spring Cloud Gateway runs on Netty (reactive/WebFlux). SecurityFilterChain is a servlet-API construct (javax.servlet.Filter). These are incompatible stacks — you cannot import servlet dependencies into a WebFlux application without class conflicts. The gateway needs its own reactive security via GlobalFilter or Spring Security's WebFlux SecurityWebFilterChain.

**Q: "Is JJWT's parseSignedClaims() safe on the Netty event loop?"**
A: Yes. HMAC-SHA256 signature verification is pure CPU computation (no I/O, no network calls, no disk access). It completes in microseconds. The Netty event loop only forbids BLOCKING operations (Thread.sleep, synchronous HTTP calls, JDBC queries). CPU-bound work under ~1ms is fine.

---

## 10. Security Activation Chain — From Classpath to Enforcement (Phase 8 Steps 1-4)

### Complete Activation Flow Diagram

This section traces the ENTIRE path from "security code exists" to "requests are actually rejected." Each step must succeed for security to be enforced. A failure at any step results in **silent degradation** — no error, just no security.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Security Activation Chain — Order Service Example                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LAYER 1: DEPENDENCY (Gradle)                                                │
│  ─────────────────────────────                                               │
│  order/build.gradle:                                                         │
│    implementation project(':commons')                                         │
│         │                                                                    │
│         │  What this does: puts commons .class files on order's classpath     │
│         │  What this does NOT: register any class as a Spring bean            │
│         ▼                                                                    │
│  LAYER 2: SCANNING (@ComponentScan)                                          │
│  ──────────────────────────────────                                          │
│  OrderServiceApplication.java:                                               │
│    @ComponentScan(basePackages = {"com.equitycart.order", "com.equitycart.commons"})
│         │                                                                    │
│         │  Spring scans com.equitycart.commons.** → finds SecurityAutoConfig │
│         ▼                                                                    │
│  LAYER 3: CONDITIONAL EVALUATION (@ConditionalOnProperty)                    │
│  ─────────────────────────────────────────────────────────                   │
│  SecurityAutoConfig.class:                                                   │
│    @ConditionalOnProperty(name="equitycart.security.enabled", havingValue="true")
│         │                                                                    │
│         │  Spring checks Config Server → order-service.yml has the property  │
│         ▼                                                                    │
│  LAYER 4: BEAN CREATION (DI)                                                 │
│  ────────────────────────────                                                │
│  SecurityAutoConfig instantiated → injects JwtAuthenticationFilter           │
│  JwtAuthenticationFilter instantiated → injects JwtTokenValidatorImpl        │
│  JwtTokenValidatorImpl instantiated → @Value("${jwt.secret}") resolved       │
│         │                                                                    │
│         │  All dependencies satisfied → bean graph complete                  │
│         ▼                                                                    │
│  LAYER 5: FILTER CHAIN REGISTRATION                                          │
│  ──────────────────────────────────                                          │
│  SecurityAutoConfig.securityFilterChain(HttpSecurity):                        │
│    .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)│
│    .authorizeHttpRequests(auth -> auth                                        │
│        .requestMatchers("/api/auth/**", "/actuator/**").permitAll()           │
│        .anyRequest().authenticated())                                        │
│         │                                                                    │
│         │  Filter chain registered in FilterChainProxy                       │
│         ▼                                                                    │
│  LAYER 6: REQUEST-TIME ENFORCEMENT                                           │
│  ──────────────────────────────────                                          │
│  Incoming: GET /api/order/1 (no token)                                       │
│    → DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain          │
│    → JwtAuthenticationFilter: no Bearer header → SecurityContext empty       │
│    → AuthorizationFilter: anyRequest().authenticated() → 403 Forbidden       │
│                                                                              │
│  Incoming: GET /api/order/1 (with valid Bearer token)                        │
│    → JwtAuthenticationFilter: validates → sets SecurityContext (userId=42)   │
│    → AuthorizationFilter: authenticated ✓ → proceeds to controller           │
│    → OrderController: (Long) auth.getPrincipal() → 42                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Failure Points — "Security Not Working" Debugging Checklist

| Symptom | Root Cause | Layer Failed |
|---------|-----------|--------------|
| All requests return 200 (no auth) | @ComponentScan doesn't include `com.equitycart.commons` | Layer 2 |
| All requests return 200 (no auth) | `equitycart.security.enabled` not set to `true` | Layer 3 |
| Startup fails: `NoSuchBeanDefinition: JwtAuthenticationFilter` | Filter class not scanned (wrong package) | Layer 2 |
| Startup fails: `Could not resolve placeholder 'jwt.secret'` | Config Server unreachable or property missing | Layer 4 |
| Requests return 403 (not 401) | Token valid but missing required role for @PreAuthorize | Layer 6 |
| Feign inter-service calls return 401 | FeignAuthorizationInterceptor not propagating token | Layer 2 (interceptor not scanned) |
| Gateway passes, service rejects | Different jwt.secret values (split-brain config) | Layer 4 |

### Two-Layer Defense-in-Depth — Complete Request Flow

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                     │
│  CLIENT: GET /api/order/1, Authorization: Bearer eyJhbG...                          │
│       │                                                                             │
│       ▼                                                                             │
│  ┌─────────────────────── API GATEWAY (port 8080) ──────────────────────┐           │
│  │                                                                       │           │
│  │  Filter 1: CorrelationIdGatewayFilter (HIGHEST_PRECEDENCE)            │           │
│  │    → Assigns X-Correlation-Id: 7f3a-9c2b-...                          │           │
│  │                                                                       │           │
│  │  Filter 2: JwtValidationGatewayFilter (HIGHEST_PRECEDENCE + 1)        │           │
│  │    → Checks: is /api/order/1 an open path? NO                         │           │
│  │    → Extracts Bearer token from Authorization header                  │           │
│  │    → JJWT: Jwts.parser().verifyWith(key).parseSignedClaims(token)     │           │
│  │    → Valid? YES → proceed (token NOT stripped, stays in header)        │           │
│  │    │                                                                  │           │
│  │    │  If INVALID here → return 401 immediately (never reaches service)│           │
│  │    │  This saves 1 network hop + service processing time              │           │
│  │    ▼                                                                  │           │
│  │  Route: lb://order-service → Eureka → 192.168.x.x:8088               │           │
│  │                                                                       │           │
│  └───────────────────────────────────────────────────────────────────────┘           │
│       │                                                                             │
│       │  HTTP request forwarded WITH original Authorization header                  │
│       ▼                                                                             │
│  ┌─────────────────── ORDER SERVICE (port 8088) ─────────────────────────┐          │
│  │                                                                        │          │
│  │  Servlet Filter Stack (DelegatingFilterProxy → FilterChainProxy):      │          │
│  │                                                                        │          │
│  │  Filter A: MdcCorrelationFilter                                        │          │
│  │    → Reads X-Correlation-Id header → puts in MDC ThreadContext         │          │
│  │                                                                        │          │
│  │  Filter B: JwtAuthenticationFilter (before UsernamePasswordAuth)       │          │
│  │    → Reads Authorization: Bearer eyJhbG...                             │          │
│  │    → JwtTokenValidatorImpl.validateToken(token) → true                 │          │
│  │    → extractUserId(token) → 42L                                        │          │
│  │    → extractRoles(token) → ["CUSTOMER"]                                │          │
│  │    → SecurityContextHolder.setAuthentication(                           │          │
│  │        new UsernamePasswordAuthenticationToken(42L, null,              │          │
│  │            [ROLE_CUSTOMER]))                                           │          │
│  │                                                                        │          │
│  │  Filter C: AuthorizationFilter                                         │          │
│  │    → /api/order/1 requires authenticated() → SecurityContext has       │          │
│  │      principal → PASS                                                  │          │
│  │                                                                        │          │
│  │  Controller: OrderController.getOrder(1)                               │          │
│  │    → (Long) authentication.getPrincipal() → 42                         │          │
│  │    → Returns order belonging to userId 42                              │          │
│  │                                                                        │          │
│  └────────────────────────────────────────────────────────────────────────┘          │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Why Both Layers Are Needed

| Scenario | Gateway only | Service only | Both (our design) |
|----------|-------------|-------------|-------------------|
| Bad token via browser/curl | Rejected at edge ✓ | Reaches service, rejected there | Rejected at edge (fast-fail) |
| Feign call between services (bypasses gateway) | Not checked! | Rejected at service ✓ | Rejected at service |
| Gateway misconfigured/bypassed | No protection! | Still protected ✓ | Still protected |
| Valid token, expired mid-flight | Gateway may pass (clock skew) | Service rejects ✓ | Belt and suspenders |
| DDoS with invalid tokens | Rejected at edge (saves resources) ✓ | All services waste CPU | Rejected at edge |

**Key principle:** The gateway is an **optimization** (save network hops); per-service validation is the **security guarantee** (zero trust within service mesh).

### Config Server Resolution in Docker Context

```
┌────────────────────────────────────────────────────────────────────────────┐
│               How Services Get Security Config in Docker                     │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  docker-compose-services.yml:                                               │
│    order-service:                                                            │
│      environment:                                                            │
│        - CONFIG_SERVER_URL=http://config-server:8888                          │
│                                                                             │
│  At startup, order-service's Spring Cloud Config client:                     │
│    1. Connects to http://config-server:8888                                  │
│    2. Requests: GET /order-service/default                                   │
│    3. Config Server:                                                         │
│       a. Clones https://github.com/mandipdungeon17/equitycart-config.git    │
│       b. Returns MERGED config:                                              │
│          - application.yml (shared) → jwt.secret, eureka, kafka, logging    │
│          - order-service.yml (specific) → equitycart.security.enabled=true  │
│    4. Spring PropertySource now contains jwt.secret + security flag          │
│                                                                             │
│  ⚠️  CRITICAL: Config Server pulls from REMOTE Git, not local filesystem.   │
│     If you change equitycart-config/ locally but don't push → Docker gets   │
│     the OLD config → security flags missing → SecurityAutoConfig skipped    │
│     silently → all requests pass through without authentication.            │
│                                                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

### Interview Questions

**Q: "You say defense-in-depth — isn't validating twice wasteful?"**
A: The gateway validation costs ~50μs (HMAC-SHA256 is CPU-only). That's negligible compared to the network hop saved when rejecting a bad token. The service-level validation is mandatory because not all traffic enters through the gateway — Feign inter-service calls bypass it entirely. The cost of double-validation on the happy path (valid token) is negligible; the cost of NOT validating at the service (compromised gateway = total breach) is catastrophic.

**Q: "How would you debug 'security is not working' in a multi-module Spring Boot app?"**
A: Systematic layer check: (1) Verify @ComponentScan includes commons package — check startup log for "Enabling JWT-based security" message. (2) Verify property: `actuator/env` endpoint → search for `equitycart.security.enabled`. (3) Verify filter chain: `actuator/beans` → search for `securityFilterChain`. (4) Enable condition report: add `--debug` flag → Spring prints all @Conditional match/no-match decisions. Most common root cause: @EntityScan was added (for BaseEntity) but @ComponentScan was not — they are independent mechanisms.

**Q: "Should the gateway strip the token after validation?"**
A: No. In defense-in-depth, downstream services must validate independently. Stripping the token means services trust the gateway blindly — if the gateway is compromised or misconfigured, there's no second line of defense. The token flows through unchanged; each service performs its own validation. This is zero-trust networking applied to JWT authentication.

---

## Section 11: Phase 8 Obstacles — Service-to-Service Authentication in Async Contexts

### The Problem: 403 on Feign Calls from Kafka Consumers

After all services enforced JWT authentication (Phase 8 Step 2), inter-service Feign calls from Kafka consumer threads failed with HTTP 403. Root cause: `RequestContextHolder.getRequestAttributes()` returns null on non-HTTP threads.

**Why null?** `RequestContextHolder` uses `ThreadLocal` storage. When Tomcat's `DispatcherServlet` receives an HTTP request, it stores the `HttpServletRequest` in the current thread's ThreadLocal. Kafka consumer threads are managed by Spring Kafka's `ConcurrentMessageListenerContainer` — they never pass through `DispatcherServlet`, so no request attributes exist.

**Affected flows:**
- `StockBackRewardConsumer` (order-delivered event) → calls `ProductFeignClient.getProductById()` 
- Any `@Scheduled` or `@Async` task that uses Feign clients

### The Solution: ServiceTokenProvider Pattern

```
Non-HTTP Thread → FeignAuthorizationInterceptor → no RequestContext?
    ├── YES (Kafka/Scheduled) → ServiceTokenProvider.getServiceToken() → "Bearer <service-jwt>"
    └── NO  (normal HTTP)     → propagate original Authorization header
```

**Key Design Constraints (discovered through trial and error):**

| Constraint | Why | What Breaks Otherwise |
|-----------|-----|----------------------|
| Subject must be numeric ("0") | `JwtTokenValidatorImpl.extractUserId()` does `Long.parseLong(subject)` | NumberFormatException on "SYSTEM" |
| Roles must be `List<String>` | `extractRoles()` casts claim to `List<String>` | ClassCastException on plain String "SERVICE" |
| Expiry is mandatory | `JwtAuthenticationFilter` calls `isTokenExpired()` — returns true for no-expiry tokens in JJWT 0.12.6 | Token rejected as expired |
| Same signing key | Downstream validates with shared `jwt.secret` | SignatureException |

**Why subject="0"?** Auto-increment IDs start at 1. Using 0 as a sentinel means it can never collide with a real user, and the `Long.parseLong()` contract is satisfied. Any controller that does `@PreAuthorize("hasRole('CUSTOMER')")` naturally excludes service tokens (role=SERVICE), while `anyRequest().authenticated()` permits them.

### SecurityAutoConfig: `anyRequest()` is Terminal

**Bug:** An earlier implementation attempted multiple authorization chains:
```java
auth.requestMatchers("/api/auth/**").permitAll()
    .anyRequest().hasRole("SERVICE")  // First anyRequest() — catches ALL remaining
    .anyRequest().authenticated();    // Second anyRequest() — NEVER reached
```

**Root cause:** In Spring Security's `AuthorizeHttpRequestsConfigurer`, `anyRequest()` registers a universal matcher (matches every URL). Once registered, the framework throws `IllegalStateException` if you try to add more matchers after it. The second `anyRequest()` above is silently ignored (pre-6.x) or throws an error (6.x+).

**Fix:** Single `anyRequest().authenticated()` — accepts ANY valid JWT. Role-based access control is delegated to method-level `@PreAuthorize` annotations.

### JWT Claims Type Compatibility

JJWT's serialization/deserialization preserves Java types through JSON:
- `List.of("SERVICE")` → serialized as JSON `["SERVICE"]` → deserialized as `ArrayList<String>` ✓
- `"SERVICE"` (plain String) → serialized as JSON `"SERVICE"` → deserialized as `String` ✗ (cast to List fails)

This matters because `JwtTokenValidatorImpl.extractRoles()` does:
```java
return claims.get("roles", List.class);  // ClassCastException if stored as String
```

### Interview Questions

**Q: "How do you handle authentication for service-to-service calls in non-HTTP contexts?"**
A: Three options in increasing maturity: (1) ServiceTokenProvider — generate a short-lived JWT with a sentinel identity (subject=0, role=SERVICE), signed with the shared secret. Simple, no external deps. (2) Client-credentials OAuth2 flow — the calling service authenticates to the IdP with its own clientId/secret and receives a token scoped to its needs. Requires Keycloak/IdP. (3) mTLS — mutual TLS at the network level; no application-layer token needed. Most secure but complex to manage certificates.

**Q: "What is the risk of using a shared HMAC secret for service tokens?"**
A: Any service that holds the jwt.secret can FORGE tokens for ANY identity — including admin users. If one service is compromised, the attacker can impersonate any user across all services. This is the fundamental weakness of symmetric (HS256) signing in microservices. The mitigation: migrate to RS256 (asymmetric) where services hold only the PUBLIC key for validation — they cannot sign new tokens. Only the IdP (Keycloak) holds the private key.

**Q: "Why not cache the service token?"**
A: HMAC signing is ~0.1ms (CPU-only, no I/O). The 60-second TTL means any cached token is valid for at most 60s — caching saves negligible time while introducing expiry-management complexity (what if the cached token has 1s remaining?). Fresh tokens per call are simple and safe.

---

## Section 12: Corporate Proxy TLS Interception in Docker Containers

### The Problem: PKIX Path Building Failed

All HTTPS calls from Docker containers (Alpha Vantage API, Spring Cloud Config pulling from GitHub) failed with:
```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target
```

### Root Cause: Zscaler TLS Interception

Corporate networks often use a forward proxy (Zscaler, Symantec BlueCoat, Palo Alto) that performs **TLS interception** (also called "SSL inspection" or "break-and-inspect"):

1. Container initiates TLS to `api.alphavantage.co`
2. Zscaler proxy intercepts, terminates the original TLS connection
3. Proxy inspects the plaintext HTTP request/response (for DLP, malware scanning)
4. Proxy creates a NEW TLS connection to the container, signed by **Zscaler's private root CA**
5. Container receives a certificate chain ending in Zscaler Root CA — NOT DigiCert/Let's Encrypt

The JVM's default `cacerts` truststore contains only publicly-trusted CAs. Zscaler's CA is private (only your corporation trusts it). Result: PKIX path building fails because the chain cannot be validated.

### The Fix: Import CA into JVM Truststore

```dockerfile
COPY docker/ZscalerRootCA.pem /tmp/ZscalerRootCA.pem
RUN keytool -importcert -cacerts -storepass changeit -noprompt \
      -alias zscaler-root-ca -file /tmp/ZscalerRootCA.pem \
    && rm /tmp/ZscalerRootCA.pem
```

**Why this works:**
- `keytool -importcert -cacerts` adds the certificate to the JVM's trusted CA store
- `-storepass changeit` is the default password for Java's cacerts keystore (unchanged since JDK 1.2)
- After import, the JVM trusts certificate chains signed by Zscaler — HTTPS connections succeed

**Alpine-specific detail:** On `eclipse-temurin:21-jre-alpine`, `cacerts` is a regular file at `/opt/java/openjdk/lib/security/cacerts` (NOT symlinked to system `/etc/ssl/certs/java/cacerts` as on Debian). The `-cacerts` flag handles this automatically by reading `java.home`.

### Docker Build Context Paths

The build context determines COPY source resolution. In this project:
- `docker build -f docker/Dockerfile .` runs from `equitycart/` (the `-f` path is relative to CWD)
- Build context = `equitycart/` → COPY paths are relative to `equitycart/`
- `COPY docker/ZscalerRootCA.pem` resolves to `equitycart/docker/ZscalerRootCA.pem` ✓
- `COPY ZscalerRootCA.pem` resolves to `equitycart/ZscalerRootCA.pem` ✗ (file doesn't exist there)

This is NOT relative to the Dockerfile's location — it's relative to the build context root.

### .gitignore Path Resolution

`.gitignore` patterns are relative to the file's location in the repository:
- `.gitignore` at repo root: patterns match from repo root
- File at `equitycart/docker/ZscalerRootCA.pem` → pattern must be `equitycart/docker/ZscalerRootCA.pem` or `*.pem`
- Pattern `docker/ZscalerRootCA.pem` (without `equitycart/` prefix) → NO MATCH

### Interview Questions

**Q: "Your Docker containers can't reach external HTTPS APIs — what's your debugging approach?"**
A: (1) Check if the same URL works from the host machine. If yes → container-specific TLS issue. (2) Inspect the certificate chain: `openssl s_client -connect api.example.com:443 -proxy proxy.corp.com:9480` — look at the issuer of the leaf certificate. If it's a corporate CA (Zscaler, Symantec), that's TLS interception. (3) Import the corporate root CA into the JVM truststore via `keytool -importcert -cacerts`. (4) For non-JVM containers (Python, Node), update the system CA bundle (`update-ca-certificates` on Debian/Alpine).

**Q: "Is it safe to commit the corporate root CA certificate to the repository?"**
A: The root CA certificate is a PUBLIC key — it can only VERIFY signatures, not create them. Committing it is no more dangerous than committing DigiCert's root certificate. However, it's organization-specific and unnecessary for external contributors, so it's common practice to .gitignore it and add it during CI/CD pipeline builds from a secrets vault.