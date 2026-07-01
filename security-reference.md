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

---

## Section 13: Identity Providers — Keycloak, OAuth2, and OpenID Connect

### What Problem Does an Identity Provider (IdP) Solve?

In a monolithic application, authentication is straightforward: the app has ONE database of users, ONE login form, and ONE session cookie. The app both **issues** credentials and **validates** them — a single trust boundary.

In microservices, this collapses:
- 10 services each need to authenticate requests independently
- Users shouldn't log in separately to each service
- Services call each other — who authenticates machine-to-machine calls?
- Where do you manage users, roles, password policies, MFA?
- How do you revoke a compromised token across ALL services simultaneously?
- How do you rotate signing keys without restarting every service?

An **Identity Provider** centralizes these concerns into a dedicated service. It becomes the single source of truth for identity: "Who is this user? What are they allowed to do? Here's a cryptographically signed proof."

### Historical Evolution — How We Got Here

**1990s — Session-Based Auth (Monolith Era):**
The server stores session state in memory or a database. On login, the server creates a session, returns a `JSESSIONID` cookie. Every request sends the cookie; the server looks up the session. Problem: doesn't scale horizontally (sticky sessions needed) and doesn't work across domains.

**2000s — SAML (Enterprise SSO):**
Security Assertion Markup Language — XML-based protocol for enterprise Single Sign-On. Heavy, complex, designed for browser-based enterprise apps. Still used in corporate environments (Okta, ADFS). Tokens are XML documents, sometimes kilobytes in size. Overkill for APIs.

**2010 — OAuth 2.0 (RFC 6749):**
Authorization framework (NOT authentication). Designed for delegated access: "Let this app access my Google Drive files." Defines four flows (Authorization Code, Implicit, Client Credentials, Resource Owner Password). Tokens are opaque strings — the resource server must call the authorization server to validate them (token introspection). Problem: OAuth2 alone doesn't tell you WHO the user is, only WHAT they're allowed to access.

**2014 — OpenID Connect (OIDC):**
Authentication layer ON TOP of OAuth2. Adds the `id_token` (a JWT containing user identity claims: sub, name, email). Now you know both WHO (OIDC) and WHAT (OAuth2). Introduces the `.well-known/openid-configuration` discovery endpoint — services auto-discover issuer, token endpoints, JWKS URIs. This is the modern standard for microservices auth.

**2015 — JWT (RFC 7519):**
JSON Web Token — compact, self-contained token format. Contains encoded claims (payload), signed by the issuer. Resource servers can validate LOCALLY (no network call to the IdP) by checking the signature against the issuer's public key. Made OAuth2/OIDC practical for microservices: each service validates independently without calling the IdP on every request.

### What is Keycloak?

**Keycloak** is an open-source Identity and Access Management (IAM) server, originally created by Red Hat (now part of JBoss community). First released in **2014**, it implements OAuth 2.0, OpenID Connect, and SAML 2.0 protocols.

**In plain terms:** Keycloak is a standalone server that handles everything about users — registration, login, logout, password reset, MFA, social login, role management, token issuance, and token revocation. Your application services don't handle any of this; they just validate tokens that Keycloak issued.

**Core capabilities:**
- **User Management:** Admin console for creating/managing users, groups, roles. Self-service registration, password reset, email verification.
- **Token Lifecycle:** Issues access tokens (short-lived, 5-15 min), refresh tokens (long-lived), ID tokens (identity claims). Handles rotation, revocation, introspection.
- **Single Sign-On (SSO):** One login serves all applications in the same realm. User logs into App A → automatically authenticated in App B.
- **Identity Brokering:** Federate with external IdPs (Google, GitHub, LDAP/Active Directory, corporate SAML). "Login with Google" without building the OAuth2 dance yourself.
- **Client Adapters/SDKs:** Pre-built integrations for Spring Boot, Node.js, Angular, etc. (though Spring Security's built-in OAuth2 support has largely replaced Keycloak's own adapters).
- **Fine-Grained Authorization:** Policy-based access control beyond simple roles.
- **Multi-Tenancy:** Multiple "realms" — isolated tenant environments within one Keycloak instance.

### Keycloak's Mental Model — Realm, Client, Role

Think of Keycloak as a **hotel**:

```
Keycloak Server = Hotel
    │
    ├── Realm "master"      = Hotel management office (system admin only)
    │
    ├── Realm "equitycart"  = A floor of the hotel (our app's tenant)
    │       │
    │       ├── Users        = Guests registered on this floor
    │       │   ├── customer1 (email: customer1@test.com, roles: CUSTOMER)
    │       │   ├── seller1   (email: seller1@test.com,   roles: SELLER)
    │       │   └── admin1    (email: admin1@test.com,    roles: ADMIN)
    │       │
    │       ├── Roles         = Key card access levels
    │       │   ├── CUSTOMER  (default — auto-assigned on registration)
    │       │   ├── SELLER
    │       │   ├── ADMIN
    │       │   └── SERVICE   (for machine-to-machine)
    │       │
    │       └── Clients       = Doors/entrances to the hotel
    │           ├── equitycart-gateway   (confidential — staff entrance, has a key)
    │           ├── equitycart-frontend  (public — lobby entrance, no key needed)
    │           └── equitycart-services  (confidential — service elevator, staff-only)
    │
    └── Realm "another-app" = Another floor (completely isolated)
```

**Realm:** An isolated security domain. Users, clients, roles, tokens — everything is scoped to a realm. The `master` realm is reserved for Keycloak server administration. You create a realm per application (or per tenant in multi-tenant setups).

**Client:** An application that uses Keycloak for authentication. Each client has its own authentication rules:
- **Confidential** — has a `client_secret` stored server-side (backend services, API gateways). The secret proves the client's identity.
- **Public** — no secret (browsers, mobile apps). Can't store secrets securely. Uses PKCE (Proof Key for Code Exchange) to prevent auth code interception.

**Realm Role vs Client Role:**
- **Realm role** — global across all clients in the realm (CUSTOMER, SELLER, ADMIN). Simpler, preferred for most apps.
- **Client role** — scoped to a specific client. Use when different applications need different role sets (e.g., "editor" in App A, "viewer" in App B).

### OAuth2 Flows — Which Flow for Which Scenario

```
┌──────────────────────────────────────────────────────────────────┐
│                        OAuth2 Flow Decision Tree                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  Is the client a browser/SPA/mobile app?                          │
│    ├── YES → Authorization Code + PKCE                            │
│    │         User redirected to Keycloak login page                │
│    │         Auth code returned → exchanged for tokens             │
│    │         PKCE prevents auth code interception (no secret)      │
│    │                                                               │
│    └── NO → Is there a human user involved?                       │
│              ├── YES → Authorization Code (confidential client)   │
│              │         Server-side app with client_secret          │
│              │         Same redirect flow, but secret validates    │
│              │                                                     │
│              └── NO → Client Credentials                          │
│                       Machine-to-machine (no user context)        │
│                       Client authenticates with clientId + secret  │
│                       Receives token with service-level roles      │
│                                                                    │
│  ⚠️ Resource Owner Password Credentials (ROPC / Direct Access):  │
│     User sends username+password directly to token endpoint.       │
│     Client sees plaintext credentials → ONLY for testing/dev.     │
│     Deprecated in OAuth 2.1. Never use in production.              │
└──────────────────────────────────────────────────────────────────┘
```

### RS256 vs HS256 — Why Keycloak Changes Everything

This is the single most important security upgrade in the entire project.

**HS256 (what we have now — Steps 1-4):**
```
┌─────────────┐     jwt.secret (shared)     ┌─────────────────┐
│ user-service │ ◄──────────────────────────► │  order-service  │
│ (SIGNS token)│     jwt.secret (shared)     │ (VALIDATES token)│
└─────────────┘ ◄──────────────────────────► └─────────────────┘
                     jwt.secret (shared)
              ◄──────────────────────────►
              │  portfolio-service         │
              │  (VALIDATES token)         │
              │  ...AND CAN FORGE tokens   │  ← THIS IS THE PROBLEM
```

With symmetric signing, every service holding the secret can create tokens claiming to be ANY user with ANY role. If portfolio-service is compromised, the attacker can forge admin tokens for ALL services.

**RS256 (what Keycloak gives us):**
```
┌──────────────┐                           ┌─────────────────┐
│   Keycloak   │     PRIVATE key           │  order-service  │
│ (SIGNS token)│     (only Keycloak has it)│ (VALIDATES token)│
└──────────────┘                           └─────────────────┘
       │                                          ▲
       │ issues token                             │ fetches PUBLIC key
       │ signed with                              │ from JWKS endpoint
       │ private key                              │
       ▼                                          │
  ┌──────────┐    PUBLIC key (via JWKS)    ┌──────────────┐
  │   Token  │ ──────────────────────────► │  All services │
  │  (JWT)   │    Can VERIFY but NOT SIGN  │ (read-only)   │
  └──────────┘                             └──────────────┘
```

With asymmetric signing:
- Only Keycloak holds the private key → only Keycloak can issue tokens
- Services hold only the public key (fetched automatically from JWKS endpoint)
- Compromising a service does NOT let the attacker forge tokens
- Key rotation: Keycloak generates new key pair → old public key stays in JWKS for existing tokens → new tokens signed with new key → services auto-refresh JWKS cache → zero-downtime rotation

### JWKS — The Key Distribution Problem Solved

**Problem:** In HS256, changing the shared secret requires restarting ALL services simultaneously (or they can't validate new tokens). This makes key rotation practically impossible in production.

**JWKS (JSON Web Key Set)** — Keycloak publishes its public keys at:
```
GET /realms/{realm}/protocol/openid-connect/certs
```

Response:
```json
{
  "keys": [
    {
      "kid": "abc123",          ← Key ID (referenced in JWT header)
      "kty": "RSA",             ← Key type
      "alg": "RS256",           ← Algorithm
      "use": "sig",             ← Purpose: signature verification
      "n": "0vx7agoebG...",    ← RSA modulus (public key component)
      "e": "AQAB"               ← RSA exponent (public key component)
    }
  ]
}
```

**How services use it:**
1. Service receives a JWT with header `"kid": "abc123"`
2. Service fetches JWKS from Keycloak (cached, refreshed every 5 minutes)
3. Finds the key with matching `kid`
4. Validates the JWT signature using that public key
5. If valid → trust the claims. If not → reject.

Spring's `NimbusJwtDecoder` handles steps 2-5 automatically. You configure the issuer URI once; the decoder auto-discovers the JWKS endpoint via `.well-known/openid-configuration`.

### OIDC Discovery — Self-Configuring Services

OpenID Connect defines a discovery endpoint:
```
GET /realms/{realm}/.well-known/openid-configuration
```

Returns a JSON document listing ALL endpoints:
```json
{
  "issuer": "http://localhost:8180/realms/equitycart",
  "authorization_endpoint": "http://localhost:8180/realms/equitycart/protocol/openid-connect/auth",
  "token_endpoint": "http://localhost:8180/realms/equitycart/protocol/openid-connect/token",
  "userinfo_endpoint": "http://localhost:8180/realms/equitycart/protocol/openid-connect/userinfo",
  "jwks_uri": "http://localhost:8180/realms/equitycart/protocol/openid-connect/certs",
  "end_session_endpoint": "...",
  "introspection_endpoint": "...",
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token"],
  "response_types_supported": ["code", "id_token", "token"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

Spring Security's `issuer-uri` configuration uses this endpoint. You set ONE property:
```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: http://keycloak:8080/realms/equitycart
```
And Spring auto-discovers the JWKS URI, issuer, algorithms — zero hardcoded URLs.

### Keycloak vs Competitors — Industry Landscape

| Product | Type | License | Best For | Notable Users |
|---------|------|---------|----------|---------------|
| **Keycloak** | Self-hosted IdP | Apache 2.0 (open source) | Full control, on-prem, learning | Red Hat, government agencies, banks |
| **Auth0** | Cloud IdP (SaaS) | Proprietary (free tier) | SaaS apps, quick setup, dev teams | Atlassian, Mozilla, Mazda |
| **Okta** | Cloud IdP (Enterprise) | Proprietary (paid) | Enterprise SSO, workforce identity | FedEx, T-Mobile, Splunk |
| **AWS Cognito** | Cloud IdP (AWS-native) | AWS pricing model | AWS-centric apps, mobile backends | N/A (AWS customers) |
| **Azure AD / Entra ID** | Cloud IdP (Microsoft) | Microsoft licensing | Microsoft ecosystem, O365 integration | Most Fortune 500 |
| **Firebase Auth** | Cloud IdP (Google) | Free (Google ecosystem) | Mobile apps, quick prototyping | Startups, mobile-first |
| **Spring Authorization Server** | Library (embedded) | Apache 2.0 | Custom IdP in Spring ecosystem | Spring-based orgs |
| **Ory Hydra** | Self-hosted IdP | Apache 2.0 | Headless OAuth2/OIDC (no UI) | Privacy-focused apps |
| **FusionAuth** | Self-hosted + Cloud | Community (free) + paid | Self-hosted with commercial support | GitHub, IBM |

**Why Keycloak for EquityCart?**
1. **Open source** — aligns with project's 100% open-source tech stack requirement
2. **Self-hosted** — runs in Docker alongside existing infrastructure (no cloud vendor dependency)
3. **Feature-complete** — admin console, user federation, social login, MFA — all included
4. **Industry standard** — widely used in enterprise Java/Spring projects. Interviewers at Java shops expect Keycloak knowledge
5. **Spring integration** — `spring-boot-starter-oauth2-resource-server` + Keycloak JWKS works out of the box
6. **Learning value** — exposes all OAuth2/OIDC concepts visually (admin console shows realms, clients, flows, tokens)

**Production considerations:**
- Keycloak itself is a Java application (Quarkus-based since v17, previously WildFly). Needs 512MB-1GB RAM.
- Requires its own database (PostgreSQL recommended for production)
- Should run in HA mode (clustered) for production — Infinispan for cache, shared DB
- In Kubernetes: use the Keycloak Operator or Helm chart
- Starting with v17 (2022): Keycloak moved from WildFly to Quarkus runtime — significantly faster startup (~5s vs ~30s)

### How We'll Use Keycloak in EquityCart

**Dual-mode approach:** Custom auth (Steps 1-4) remains functional. Keycloak runs alongside. Services will accept tokens from EITHER issuer. This mirrors real-world migrations where you don't cut over overnight.

```
                          ┌─────────────────────┐
                          │      Keycloak        │
                          │  (RS256 tokens)      │
                          │  Port 8180           │
                          │                      │
   ┌──────────────┐       │  Realm: equitycart   │
   │   Browser/   │──────►│  3 clients           │
   │   Postman    │ login │  3 roles             │
   └──────────────┘       │  JWKS endpoint       │
          │               └──────────┬────────────┘
          │ Bearer token              │ Bearer token (RS256)
          │ (custom HS256             │
          │  OR Keycloak RS256)       │
          ▼                           ▼
   ┌─────────────────────────────────────────┐
   │              API Gateway (8080)           │
   │  JwtValidationGatewayFilter              │
   │  (Step 6: dual-mode validation)          │
   │  accepts EITHER token type               │
   └────────────────┬────────────────────────┘
                    │
         ┌──────────┼──────────┐
         ▼          ▼          ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │ user-svc │ │ order-svc│ │ port-svc │  ... (all 7 services)
   │ custom   │ │ commons  │ │ commons  │
   │ auth +   │ │ JWT      │ │ JWT      │
   │ Keycloak │ │ filter   │ │ filter   │
   └──────────┘ └──────────┘ └──────────┘
```

**Step 5 scope (this step):** Infrastructure only — Keycloak Docker container + realm configuration. NO Java code changes. We verify Keycloak issues correct tokens before touching any service code.

**Step 6 scope (next step):** Add `spring-boot-starter-oauth2-resource-server` to commons, create dual-mode SecurityAutoConfig that validates both custom HS256 and Keycloak RS256 tokens.

### Keycloak Token Structure — What Changes from Custom JWT

**Current custom token (HS256):**
```json
{
  "sub": "1",                    ← userId (Long as String)
  "roles": ["CUSTOMER"],         ← flat top-level claim
  "iat": 1718700000,
  "exp": 1718700900
}
```

**Keycloak token (RS256) — default structure:**
```json
{
  "sub": "a1b2c3d4-uuid",       ← Keycloak's internal user UUID (NOT our DB userId!)
  "realm_access": {
    "roles": ["CUSTOMER", "default-roles-equitycart"]   ← NESTED, not flat
  },
  "resource_access": {
    "equitycart-gateway": { "roles": ["uma_protection"] }
  },
  "preferred_username": "customer1",
  "email": "customer1@test.com",
  "iss": "http://localhost:8180/realms/equitycart",
  "aud": "equitycart-gateway",
  "exp": 1718700900,
  "iat": 1718700000,
  "typ": "Bearer",
  "azp": "equitycart-gateway"
}
```

**Two backward-compatibility problems:**
1. `roles` is nested under `realm_access.roles`, but our code reads `claims.get("roles")` (flat)
2. `sub` is a UUID, but our code does `Long.parseLong(sub)` to extract userId

**Solution (via Keycloak Protocol Mappers):**
- **Roles mapper:** Flattens `realm_access.roles` into a top-level `roles` claim → existing `extractRoles()` works unchanged
- **userId mapper:** Maps a custom user attribute `userId` (set per user) into a top-level `userId` claim → Step 6's converter reads this

### Interview Questions

**Q: "What is the difference between OAuth2 and OpenID Connect?"**
A: OAuth2 is an AUTHORIZATION framework — it answers "what is this client allowed to ACCESS?" (scopes, delegated permissions). OIDC is an AUTHENTICATION layer built on top of OAuth2 — it answers "WHO is this user?" (identity via `id_token` with claims like `sub`, `name`, `email`). You can have OAuth2 without OIDC (API-only access), but OIDC always requires OAuth2 as its transport. In practice: if you need user login, use OIDC. If you need API access delegation (like "let this app read my files"), use OAuth2.

**Q: "What is the difference between a confidential and public client?"**
A: A confidential client has a `client_secret` stored securely on a backend server — the secret proves the client's identity when exchanging tokens. A public client (browser SPA, mobile app) cannot store secrets securely (JavaScript source is visible, APKs are decompilable). Public clients use PKCE (Proof Key for Code Exchange) instead: the client generates a random `code_verifier`, sends its hash (`code_challenge`) with the auth request, then proves possession of the original verifier when exchanging the auth code. This prevents auth code interception even without a secret.

**Q: "Explain asymmetric vs symmetric JWT signing. When would you use each?"**
A: **Symmetric (HS256):** One shared secret signs AND verifies. Fast (HMAC is a hash function). Fine for single-service systems where the same process that signs also verifies. Dangerous in microservices: every service holding the secret can forge tokens. **Asymmetric (RS256):** Private key signs (kept by IdP only), public key verifies (distributed to all services via JWKS). Slightly slower (~10x, but still microseconds). Essential for microservices: services can verify but never forge. Use HS256 for internal single-service optimization; RS256 for anything multi-service or production.

**Q: "How does key rotation work with JWKS?"**
A: (1) Keycloak admin generates a new RSA key pair. (2) JWKS endpoint now returns BOTH old and new public keys. (3) Keycloak starts signing new tokens with the new private key (`kid` in token header identifies which key). (4) Existing tokens (signed with old key) still validate because the old public key is still in JWKS. (5) After old tokens expire (max 15 min for access tokens), the old key can be removed from JWKS. (6) Services auto-refresh their JWKS cache (default: every 5 min in NimbusJwtDecoder). Zero downtime, zero restarts, zero config changes.

**Q: "Why not build your own IdP instead of using Keycloak?"**
A: The features you need for production — MFA, social login, password policies, brute-force detection, account locking, email verification, token revocation, session management, admin console, key rotation, OIDC compliance, SAML support — represent years of security-critical engineering. Custom JWT auth (our Steps 1-4) covers basic authentication but misses: token revocation (stolen token is valid until expiry), key rotation (requires restart), user management (no admin UI), standards compliance (no OIDC discovery). Keycloak provides all of this out of the box. "Don't roll your own auth" is the security community's equivalent of "don't write your own crypto."

**Q: "When would you choose Auth0/Okta over Keycloak?"**
A: Choose cloud IdP (Auth0/Okta) when: (1) your team doesn't want to operate infrastructure (Keycloak needs its own database, monitoring, HA setup), (2) you need social login with 50+ providers pre-configured, (3) you have a SaaS product and want the IdP vendor to handle compliance (SOC2, HIPAA). Choose Keycloak when: (1) you need full control over data (regulated industries, government, data sovereignty), (2) cost matters at scale (Auth0 charges per user; Keycloak is free), (3) you're already in the Java/Spring ecosystem, (4) you need deep customization (custom authentication flows, custom token mappers).

---

### Section 13.1: Keycloak Docker Infrastructure — Debug-Mode Walkthrough (Phase 8 Step 5)

This section covers EVERYTHING about how Keycloak runs in EquityCart's Docker infrastructure. Every setting, every env var, every field in the realm JSON explained as if stepping through with a debugger.

---

#### 13.1.1: docker-pets.yml — Keycloak Service Definition (Line-by-Line)

```yaml
keycloak:
  container_name: keycloak
  image: quay.io/keycloak/keycloak:26.0
  command: start-dev --import-realm
  depends_on:
    - postgres
  ports:
    - 8180:8080
    - 9000:9000
  environment:
    - KC_BOOTSTRAP_ADMIN_USERNAME=admin
    - KC_BOOTSTRAP_ADMIN_PASSWORD=admin
    - KC_DB=postgres
    - KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak
    - KC_DB_USERNAME=postgres
    - KC_DB_PASSWORD=postgres
    - KC_HEALTH_ENABLED=true
  volumes:
    - ./keycloak/equitycart-realm.json:/opt/keycloak/data/import/equitycart-realm.json:ro
```

**Line: `container_name: keycloak`**
- Sets the Docker container's name to `keycloak` (instead of auto-generated `docker-keycloak-1`)
- Other containers (and docker exec commands) can reference it by this name
- What breaks if wrong: nothing operationally, but `docker exec keycloak ...` commands would fail with wrong name

**Line: `image: quay.io/keycloak/keycloak:26.0`**
- Keycloak's official registry is `quay.io` (Red Hat's container registry), NOT Docker Hub
- Version 26.0 is the latest LTS as of 2025. Based on Quarkus runtime (v17+ moved from WildFly → Quarkus = faster startup, ~5s vs ~30s)
- What breaks if you use `docker.io/keycloak/keycloak`: Docker Hub image is community-maintained and may lag or differ
- What breaks if you use `:latest`: breaking changes between major versions; realm export format can change

**Line: `command: start-dev --import-realm`**
- `start-dev`: Starts Keycloak in **development mode** — HTTP enabled (no TLS certificate required), caching disabled (changes visible immediately), no hostname verification. For production you'd use `start` which requires TLS configuration.
- `--import-realm`: On FIRST boot, reads JSON files from `/opt/keycloak/data/import/` directory and imports them as realms. ON SUBSEQUENT boots, this flag is a NO-OP (the database already has the realm).
- **Critical format detail:** Must be STRING form (`command: start-dev --import-realm`) or SEPARATE list elements. A single-element list `- "start-dev --import-realm"` passes the entire string as ONE argument to kc.sh → Keycloak can't find a subcommand named "start-dev --import-realm".

```yaml
# WRONG — single list element, Docker passes as one string argument to entrypoint:
command:
  - start-dev --import-realm
# Keycloak sees: /opt/keycloak/bin/kc.sh "start-dev --import-realm" (one arg, no such command)

# CORRECT — string form (Docker splits on spaces using /bin/sh -c):
command: start-dev --import-realm
# Keycloak sees: /opt/keycloak/bin/kc.sh start-dev --import-realm (two args)

# ALSO CORRECT — explicit list form:
command:
  - start-dev
  - --import-realm
# Same result as above, two separate arguments
```

**Line: `depends_on: - postgres`**
- Keycloak needs PostgreSQL to be running before it starts (it stores all data in PostgreSQL)
- NOTE: `depends_on` only waits for the container to START, not for PostgreSQL to be READY. That's why `start-pets.sh` has a separate readiness poll.
- What breaks without it: Keycloak boots → tries to connect to PostgreSQL → connection refused → Keycloak crashes → container exits

**Line: `ports: - 8180:8080 - 9000:9000`**
- `8180:8080`: Maps host port 8180 → container port 8080 (Keycloak's application interface). Host port 8180 chosen because api-gateway uses 8080.
- `9000:9000`: Maps host port 9000 → container port 9000 (Keycloak's management interface). Health/metrics live here since Keycloak 24+.
- Why two ports: Keycloak deliberately separates "user-facing" traffic (login pages, token endpoints, admin console) from "infrastructure" traffic (health checks, metrics, readiness probes). In production on Kubernetes, the management port is typically NOT exposed externally — only the liveness/readiness probes use it.
- What URLs does each port serve:
  - Port 8180 (application): `http://localhost:8180/admin` (console), `http://localhost:8180/realms/equitycart/protocol/openid-connect/token` (token endpoint), `http://localhost:8180/realms/equitycart/.well-known/openid-configuration` (OIDC discovery)
  - Port 9000 (management): `http://localhost:9000/health/ready` (readiness), `http://localhost:9000/health/live` (liveness), `http://localhost:9000/metrics` (Prometheus metrics)

**Line: `KC_BOOTSTRAP_ADMIN_USERNAME=admin` / `KC_BOOTSTRAP_ADMIN_PASSWORD=admin`**
- Creates the initial admin user for the `master` realm on FIRST BOOT ONLY
- "Bootstrap" prefix (Keycloak 26.x naming) clarifies this: after first boot, the admin credentials are stored in PostgreSQL and these env vars are ignored on subsequent restarts
- The `master` realm is Keycloak's system administration realm — you log in here to manage all other realms
- **History:** Before Keycloak 26.x, these were `KEYCLOAK_ADMIN` and `KEYCLOAK_ADMIN_PASSWORD`. The KC_ prefix unification happened in 26.x to standardize all configuration.
- What breaks if wrong: you can't log into the admin console → can't manage realms/users/clients

**Line: `KC_DB=postgres`**
- Tells Keycloak to use PostgreSQL as its database backend (instead of embedded H2)
- Other options: `mysql`, `mariadb`, `mssql`, `oracle`. Default (if not set): H2 file database (dev-only, loses data between container restarts if no volume)
- What happens internally: Keycloak uses Hibernate/JPA. This setting loads the PostgreSQL JDBC dialect and driver.

**Line: `KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak`**
- JDBC connection string to the PostgreSQL container
- `postgres` (hostname): Docker Compose DNS resolves this to the postgres container's IP (Docker's internal DNS based on service name)
- `5432` (port): PostgreSQL's INTERNAL container port (NOT the host-mapped 9432). Inside Docker's network, containers communicate on internal ports.
- `keycloak` (database name): A separate database for Keycloak's own tables (created by init-db.sh on first PostgreSQL boot)
- What breaks if you use `localhost:9432`: Inside the keycloak container, localhost = the container itself, not the host machine. And 9432 is only the HOST-side mapping.
- What breaks if database doesn't exist: Keycloak startup fails with "database 'keycloak' does not exist" — that's why init-db.sh creates it.

**Line: `KC_DB_USERNAME=postgres` / `KC_DB_PASSWORD=postgres`**
- PostgreSQL credentials. Same as other services (all share the same PostgreSQL instance but different databases)
- In production: each service would have its own PostgreSQL user with permissions restricted to its database only

**Line: `KC_HEALTH_ENABLED=true`**
- Enables Keycloak's health check endpoints on the management port (9000)
- Without this: `http://localhost:9000/health/ready` returns 404
- With this: returns `{"status": "UP", "checks": [...]}` when Keycloak is fully ready
- Even with this enabled, health is on port 9000 ONLY (not 8080/8180). This is a common gotcha.

**Line: `volumes: - ./keycloak/equitycart-realm.json:/opt/keycloak/data/import/equitycart-realm.json:ro`**
- Mounts the local realm export file into Keycloak's import directory
- Path breakdown:
  - `./keycloak/equitycart-realm.json` = relative to docker-compose file location (`equitycart/docker/keycloak/equitycart-realm.json`)
  - `/opt/keycloak/data/import/equitycart-realm.json` = Keycloak's designated import directory (hardcoded in Keycloak's `--import-realm` logic)
  - `:ro` = read-only mount (Keycloak never writes to this file)
- The `--import-realm` command scans this directory on first boot and imports any `.json` file it finds
- What breaks without this volume: `--import-realm` finds no files → no realm created → you'd have to configure everything manually via admin console

---

#### 13.1.2: init-db.sh — Keycloak Database Creation

```bash
CREATE DATABASE keycloak;
```

This single line (added alongside the other 7 databases) ensures the `keycloak` database exists when Keycloak first connects. PostgreSQL's `docker-entrypoint-initdb.d` scripts run ONLY on first volume creation (empty data directory).

**What Keycloak does with this database:** On first connection, Keycloak auto-creates ~100+ tables using Hibernate's `hbm2ddl.auto=update` strategy. Tables include: `USER_ENTITY`, `CLIENT`, `REALM`, `CLIENT_SESSION`, `CREDENTIAL`, `USER_ROLE_MAPPING`, etc. You never manage these tables manually — Keycloak owns its schema completely.

---

#### 13.1.3: equitycart-realm.json — Complete Line-by-Line Breakdown

This is the realm export file that Keycloak reads on first boot. Think of it as the "seed data" for the entire identity system. After import, the DATABASE becomes the source of truth — editing this file and restarting has NO effect.

**Overall Structure:**
```
{
  realm settings (top-level)     → the building itself (name, rules, timeouts)
  roles                          → the access levels (CUSTOMER, SELLER, ADMIN, SERVICE)
  defaultRole                    → which role new self-registered users get automatically
  clients                        → the applications allowed to use this Keycloak realm
  users                          → pre-seeded test accounts
}
```

##### Realm-Level Settings

```json
{
  "realm": "equitycart",
```
- The realm name. This appears in ALL URLs: `/realms/equitycart/protocol/openid-connect/token`
- Isolated from `master` realm (system admin). You can have many realms in one Keycloak — each is a separate tenant with its own users, clients, roles.
- **Analogy:** If Keycloak is a hotel, this is one FLOOR of the hotel. The master realm is the hotel management office.

```json
  "enabled": true,
```
- Activates the realm immediately on import. `false` would create it in disabled state (login attempts rejected).
- Useful for: creating a realm in advance but not making it live yet (e.g., staging setup).

```json
  "registrationAllowed": true,
```
- Allows users to self-register via Keycloak's built-in registration form (at `/realms/equitycart/protocol/openid-connect/registrations`)
- Matches our current system where `/api/auth/register` creates accounts. With Keycloak, the registration UI is Keycloak-hosted.
- What if `false`: Only admins can create users (via admin console or REST API). Self-registration page shows "Registration not allowed."

```json
  "loginWithEmailAllowed": true,
```
- Users can enter their email address instead of username on the login form
- Our system uses email as the primary identifier (matches `UserEntity.email`), so this is essential
- What if `false`: Login form only accepts the `username` field. Since our users are `customer1`, `seller1`, etc., they'd have to remember the username rather than email.

```json
  "duplicateEmailsAllowed": false,
```
- Enforces email uniqueness across all users in the realm
- Matches our database's `UNIQUE` constraint on `UserEntity.email`
- What if `true`: Two users could register with the same email → confusing identity. Security risk: attacker registers with victim's email.

```json
  "sslRequired": "none",
```
- Disables TLS enforcement for this realm. In `start-dev` mode, this is already the default.
- Production values: `"external"` (require HTTPS for external requests but allow HTTP for internal), `"all"` (require HTTPS everywhere)
- What this means: Keycloak accepts login/token requests over plain HTTP. Fine for Docker dev environment; would be a critical vulnerability in production.

```json
  "accessTokenLifespan": 900,
```
- Access tokens expire after 900 seconds (15 minutes)
- Matches our custom JWT's `jwt.access-token-expiry: 900000` (milliseconds → seconds, same value)
- **Why 15 minutes?** Balance between security (shorter = less window if stolen) and UX (longer = fewer refresh cycles). Industry standard is 5-15 minutes for access tokens.
- What happens on expiry: Client must use refresh token to get a new access token. If refresh token also expired → user must re-authenticate.

```json
  "ssoSessionIdleTimeout": 1800,
```
- SSO session (the session that Keycloak maintains for the user) times out after 30 minutes of inactivity
- This is NOT the access token lifetime — it's how long Keycloak remembers that the user already logged in
- If a user logged into App A and then opens App B within 30 minutes → auto-authenticated (SSO). After 30 minutes idle → must log in again.
- **Analogy:** You badge into the building (authenticate). Your badge remains "active" for 30 minutes of idle time. After 30 minutes without using it, you must badge in again.

```json
  "ssoSessionMaxLifespan": 36000,
```
- Absolute maximum SSO session lifetime: 36000 seconds = 10 hours
- Even if the user is actively using the system (resetting the idle timeout), they must re-authenticate after 10 hours
- **Why?** Prevents stolen SSO cookies from granting indefinite access. Forces periodic re-authentication.
- Typical corporate value: 8-12 hours (workday). Banking: 30 minutes absolute.

##### Roles Section

```json
  "roles": {
    "realm": [
      {
        "name": "CUSTOMER",
        "description": "Default role for registered users",
        "composite": false,
        "clientRole": false
      },
```
- `"name": "CUSTOMER"` — Role identifier. This exact string appears in the JWT's `roles` claim (after our mapper flattens it). Must match what `@PreAuthorize("hasRole('CUSTOMER')")` expects.
- `"composite": false` — A composite role would contain OTHER roles (like ADMIN containing CUSTOMER + SELLER). Our roles are flat/independent.
- `"clientRole": false` — This is a REALM-level role (global across all clients in this realm). A client role would be scoped to one specific client.
- Same structure for SELLER, ADMIN, and SERVICE roles.

**SERVICE role (special):**
```json
      {
        "name": "SERVICE",
        "description": "Machine-to-machine service identity",
        "composite": false,
        "clientRole": false
      }
```
- Used for the `equitycart-services` client (Client Credentials flow = machine-to-machine, no human user)
- Replaces our custom `ServiceTokenProvider` pattern. Instead of generating a JWT with `subject=0, role=SERVICE`, the service authenticates to Keycloak and gets a proper token.
- Controllers that have `@PreAuthorize("hasRole('CUSTOMER')")` naturally exclude SERVICE tokens. `anyRequest().authenticated()` allows them through (defense-in-depth: SERVICE can call other services).

##### Default Role Section

```json
  "defaultRole": {
    "name": "default-roles-equitycart",
    "composite": true,
    "composites": {
      "realm": ["CUSTOMER"]
    }
  },
```
- **What it does:** When a NEW user self-registers (via Keycloak's registration form), they automatically get the CUSTOMER role.
- `"name": "default-roles-equitycart"` — This is Keycloak's internal composite role that's auto-assigned. The name follows Keycloak's convention: `default-roles-<realm-name>`.
- `"composite": true` — It's a composite that CONTAINS other roles.
- `"composites": { "realm": ["CUSTOMER"] }` — The composite includes the CUSTOMER realm role. You could add more roles here if ALL new users should get multiple roles.
- **Critical understanding:** This ONLY applies to future self-registrations. Pre-seeded users (customer1, seller1, admin1) have their roles explicitly set in the `realmRoles` array. If you set admin1's `realmRoles` to only `["ADMIN"]` and don't include `"default-roles-equitycart"`, admin1 won't get CUSTOMER role.
- **Analogy:** New guests at the hotel automatically get a basic access card (CUSTOMER). VIP guests (SELLER, ADMIN) get their access configured individually by the hotel manager.

##### Clients Section — equitycart-gateway (Confidential Client)

```json
  "clients": [
    {
      "clientId": "equitycart-gateway",
```
- This is the identifier used in OAuth2 flows. When the gateway sends a token request, it includes `client_id=equitycart-gateway`.
- Not the same as "name" (display name for the admin console).

```json
      "name": "EquityCart API Gateway",
```
- Human-readable name shown in the admin console and consent pages. Has no technical effect.

```json
      "enabled": true,
```
- Client is active. If `false`, all token requests using this clientId would be rejected.

```json
      "clientAuthenticatorType": "client-secret",
```
- How this client proves its identity: using a shared secret (vs. signed JWT, X.509 certificate, etc.)
- "Confidential client" = it HAS a secret. The secret is sent alongside token requests.
- **What this means at runtime:** When the gateway calls the token endpoint, it must include the secret (via POST body `client_secret=gateway-secret` or HTTP Basic Auth header).

```json
      "secret": "gateway-secret",
```
- The client's shared secret. In production, this would be a long random string. We use a readable value for development.
- **Where this gets used:** In the gateway's `application.yml` when configuring Spring Security OAuth2 Client (Step 7):
  ```yaml
  spring.security.oauth2.client.registration.keycloak.client-secret: gateway-secret
  ```
- **Security note:** In production, this should come from environment variables or a secrets manager (Vault, AWS Secrets Manager), not be committed to Git.

```json
      "redirectUris": ["http://localhost:8080/*"],
```
- After successful authentication, Keycloak redirects the user back to one of these allowed URLs
- `http://localhost:8080/*` = any path on the gateway is acceptable for redirects
- **Security purpose:** Prevents "open redirect" attacks. Without this allowlist, an attacker could set `redirect_uri=https://evil.com/steal-token` and Keycloak would send the auth code there.
- Only relevant for Authorization Code flow (user login via browser). Not used for Client Credentials or Direct Access Grants.

```json
      "webOrigins": ["http://localhost:8080"],
```
- Configures CORS (Cross-Origin Resource Sharing) for Keycloak's JavaScript adapter
- Tells Keycloak: "Allow JavaScript running on http://localhost:8080 to call Keycloak's endpoints"
- Without this: browser-based JavaScript calls to Keycloak token endpoint would be blocked by the browser's same-origin policy

```json
      "standardFlowEnabled": true,
```
- Enables the **Authorization Code** flow — the recommended flow for server-side applications
- How it works: User → Keycloak login page → auth code returned → gateway exchanges code for tokens (server-side, secret included)
- This is what the gateway will use in Step 7 (Token Relay pattern)

```json
      "directAccessGrantsEnabled": true,
```
- Enables **Resource Owner Password Credentials (ROPC)** — user sends username+password directly to token endpoint
- **ONLY for testing/development!** The client sees plaintext credentials. Deprecated in OAuth 2.1.
- Why we enable it: Makes curl/Postman testing easy during development:
  ```bash
  curl -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
    -d "grant_type=password&client_id=equitycart-gateway&client_secret=gateway-secret&username=customer1&password=Test@1234"
  ```
- In production: disable this and use Authorization Code + PKCE exclusively.

```json
      "serviceAccountsEnabled": false,
```
- This client does NOT have its own service account (it's for user-facing auth, not machine-to-machine)
- Only `equitycart-services` needs a service account (Client Credentials flow)

```json
      "publicClient": false,
```
- `false` = confidential client (HAS a secret). `true` would make it a public client (no secret).
- Must align with `clientAuthenticatorType: "client-secret"` — if public, the secret field is ignored.

```json
      "protocol": "openid-connect",
```
- This client uses OIDC protocol (not SAML). Determines which endpoints are available, what token format is issued.
- Always `openid-connect` for modern applications. `saml` is for legacy enterprise SSO integration.

##### Protocol Mappers — The Backward-Compatibility Bridge

```json
      "protocolMappers": [
        {
          "name": "roles-mapper",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-realm-role-mapper",
```
- `"protocolMapper": "oidc-usermodel-realm-role-mapper"` — Built-in Keycloak mapper that takes the user's realm roles and writes them into the JWT
- By DEFAULT (without this mapper), Keycloak puts roles in a NESTED structure:
  ```json
  { "realm_access": { "roles": ["CUSTOMER", "default-roles-equitycart"] } }
  ```
- Our existing Java code reads: `claims.get("roles", List.class)` — expects a FLAT top-level claim
- This mapper OVERRIDES the location, placing roles at the path we specify in `claim.name`

```json
          "config": {
            "multivalued": "true",
```
- A user can have MULTIPLE roles → the claim value is a JSON array `["CUSTOMER", "SELLER"]`
- If `false`: only the first role would appear. Multi-role users would lose permissions.

```json
            "claim.name": "roles",
```
- **THE critical setting.** This tells Keycloak: "put the roles array at the TOP LEVEL under the key `roles`"
- Without this mapper or with default settings: roles end up at `realm_access.roles` (nested) → `claims.get("roles")` returns null → every `@PreAuthorize` check fails → all users get 403 Forbidden
- With this mapper: `claims.get("roles")` returns `["CUSTOMER"]` → existing code works unchanged

```json
            "jsonType.label": "String",
```
- Each individual role value is a String (not int, not boolean)
- Serialized in the JWT as: `"roles": ["CUSTOMER", "SELLER"]` (array of strings)

```json
            "id.token.claim": "true",
            "access.token.claim": "true",
            "userinfo.token.claim": "true"
```
- Include this claim in ALL three token types:
  - `id.token.claim`: The ID token (identity information — "who is this user?")
  - `access.token.claim`: The access token (what we validate on every API call)
  - `userinfo.token.claim`: The response from Keycloak's `/userinfo` endpoint
- We primarily use the access token, but including in all three ensures consistent behavior regardless of which token is inspected.

##### userId Mapper — Database ID Bridge

```json
        {
          "name": "userId-mapper",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
```
- `"oidc-usermodel-attribute-mapper"` — Takes a CUSTOM user attribute and maps it to a JWT claim
- Different from the roles mapper: this reads from user attributes (custom key-value pairs stored per user), not from the role system

```json
          "config": {
            "user.attribute": "userId",
```
- Reads the attribute named `userId` from the Keycloak user. Each user has `"attributes": { "userId": ["1"] }` (or "2", "3", etc.)
- This is a CUSTOM attribute we define — not built into Keycloak. It stores OUR database's auto-increment ID.

```json
            "claim.name": "userId",
```
- Writes the value to a top-level claim called `userId` in the JWT
- Result in token: `{ ..., "userId": "1", "sub": "a1b2c3-uuid-...", ... }`
- **Why we need this:** Keycloak's `sub` claim contains a UUID (Keycloak's internal user ID). Our controllers do `(Long) authentication.getPrincipal()` expecting a numeric database ID. The `userId` claim bridges this gap.
- **In Step 6:** The custom `JwtAuthenticationConverter` will read `userId` (from Keycloak tokens) or `sub` (from custom tokens) and set it as the authentication principal.

```json
            "jsonType.label": "long",
```
- The claim type is `long` (numeric). Keycloak stores all user attributes as strings internally, but this tells the JWT serializer to emit it as a number in the JSON.
- Result: `"userId": 1` (number) not `"userId": "1"` (string). Allows `Long.parseLong()` or direct numeric deserialization.

##### equitycart-frontend (Public Client)

```json
    {
      "clientId": "equitycart-frontend",
      ...
      "publicClient": true,
```
- `publicClient: true` — This client has NO secret. Browser/SPA JavaScript cannot securely store secrets (source code is visible via View Source / DevTools).
- No `"secret"` field needed. Keycloak won't require client authentication for token requests from this client.

```json
      "attributes": { "pkce.code.challenge.method": "S256" },
```
- **PKCE (Proof Key for Code Exchange)** — Required for public clients because they can't use a client_secret
- How PKCE works:
  1. Frontend generates a random `code_verifier` (128 chars)
  2. Frontend hashes it: `code_challenge = SHA256(code_verifier)` — this is the `S256` method
  3. Frontend sends `code_challenge` with the auth request to Keycloak
  4. Keycloak stores the challenge, returns auth code to frontend
  5. Frontend exchanges auth code + ORIGINAL `code_verifier` for tokens
  6. Keycloak hashes the verifier, compares with stored challenge → match = legitimate client
- **Attack it prevents:** An attacker intercepts the auth code (e.g., via redirect URI manipulation). Without the `code_verifier`, they CANNOT exchange it for tokens. The verifier never leaves the original client.

##### equitycart-services (Client Credentials — Machine-to-Machine)

```json
    {
      "clientId": "equitycart-services",
      ...
      "publicClient": false,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": true,
```
- `standardFlowEnabled: false` — No Authorization Code flow. No human user logs in through this client.
- `directAccessGrantsEnabled: false` — No username/password. This is machine-to-machine only.
- `serviceAccountsEnabled: true` — **This is the key setting.** Enables the Client Credentials flow.
- How Client Credentials works:
  1. The service sends: `grant_type=client_credentials&client_id=equitycart-services&client_secret=services-secret`
  2. Keycloak verifies the client's secret
  3. Keycloak issues a token with the SERVICE role (from the service account user)
  4. Token has NO human user identity — it represents the SERVICE ITSELF
- **What replaces:** Our current `ServiceTokenProvider` pattern (self-issued JWT with subject=0, role=SERVICE). Keycloak's Client Credentials is the industry standard equivalent.
- **When used:** Kafka consumers making Feign calls (no incoming HTTP request = no user token to propagate). Instead of generating a self-signed token, the service authenticates to Keycloak and gets a proper token.

##### Users Section — Pre-Seeded Test Accounts

```json
  "users": [
    {
      "username": "customer1",
```
- The login username. This is what appears in the `preferred_username` claim in the JWT.
- Keycloak stores usernames as case-insensitive by default.

```json
      "email": "customer1@equitycart.com",
      "enabled": true,
      "emailVerified": true,
```
- `enabled: true` — Account is active. `false` would block all logins for this user.
- `emailVerified: true` — Skips email verification flow. Without this, Keycloak would require the user to click a verification link before login.

```json
      "firstName": "Customer",
      "lastName": "One",
```
- Used in Keycloak's admin console display and can be included in tokens via mappers. Not used by our application code.

```json
      "credentials": [
        {
          "type": "password",
          "value": "Test@1234",
          "temporary": false
        }
      ],
```
- `"type": "password"` — Standard password credential. Other types: OTP, WebAuthn (FIDO2).
- `"value": "Test@1234"` — Plaintext in the JSON file. Keycloak hashes it (bcrypt/PBKDF2) on import. After import, the plaintext is never stored — only the hash lives in the database.
- `"temporary": false` — The user does NOT need to change password on first login. `true` would force a password change on first authentication (used for admin-created accounts in production).

```json
      "realmRoles": [
        "CUSTOMER", "default-roles-equitycart"
      ],
```
- Explicit role assignment for pre-seeded users. The `defaultRole` section only applies to FUTURE self-registrations, not to users defined here.
- `"default-roles-equitycart"` included so this user is "complete" (has the default role composite). If omitted, the user still works — they just won't have whatever the default composite contains.
- seller1 gets `["SELLER", "default-roles-equitycart"]`, admin1 gets `["ADMIN", "default-roles-equitycart"]`
- **Important subtlety:** If you want admin1 to ALSO have CUSTOMER role (because the default composite includes CUSTOMER), you must include `"default-roles-equitycart"`. The composite resolves at login time: Keycloak sees the user has `default-roles-equitycart` → looks up its composites → includes CUSTOMER in the token's roles.

```json
      "attributes": {
        "userId": ["1"]
      }
```
- Custom user attribute storing OUR database's auto-increment primary key
- This is what the `userId-mapper` reads and injects into the JWT
- Array format `["1"]` — Keycloak stores all attributes as arrays (even single-valued ones). The mapper handles extracting the first value.
- Maps to: customer1 → userId=1, seller1 → userId=2, admin1 → userId=3 (matching our DataSeeder output)

##### Service Account User — Machine Identity

```json
    {
      "username": "service-account-equitycart-services",
      "enabled": true,
      "emailVerified": true,
      "serviceAccountClientId": "equitycart-services",
      "realmRoles": ["SERVICE"]
    }
```
- **Why this user exists:** When `equitycart-services` client uses Client Credentials flow, Keycloak needs a "user" entity to attach roles and attributes to. This is the service account user.
- `"username": "service-account-equitycart-services"` — MUST follow the pattern `service-account-<clientId>`. Keycloak enforces this naming convention.
- `"serviceAccountClientId": "equitycart-services"` — Links this user to the client. When the client authenticates via Client Credentials, this user's roles appear in the token.
- `"realmRoles": ["SERVICE"]` — The CLIENT CREDENTIALS token will have `"roles": ["SERVICE"]` (after our mapper flattens it).
- No credentials section — this user never logs in with a password. Authentication happens via the CLIENT's secret (`services-secret`).
- **Why we can't use `serviceAccountRealmRoles` on the client:** That field doesn't exist in Keycloak's schema. The ONLY way to assign roles to a service account is via this user entity. Keycloak silently ignores unknown JSON fields during import — no error, no warning, just missing roles.

---

#### 13.1.4: Keycloak Startup Flow — What Happens on First Boot

```
┌─── docker compose up -d ──────────────────────────────────────────────────────────┐
│                                                                                    │
│  1. PostgreSQL container starts                                                    │
│     └── init-db.sh runs (first boot only, empty data dir)                         │
│         └── Creates 8 databases: equitycart, equitycart_user, ..., keycloak        │
│                                                                                    │
│  2. Keycloak container starts (depends_on: postgres)                               │
│     └── /opt/keycloak/bin/kc.sh start-dev --import-realm                          │
│         │                                                                          │
│         ├── Phase A: Boot Quarkus runtime (~2-3s)                                  │
│         │   └── Load extensions, configure HTTP server (Vert.x/Netty)             │
│         │                                                                          │
│         ├── Phase B: Connect to PostgreSQL                                         │
│         │   └── jdbc:postgresql://postgres:5432/keycloak                           │
│         │   └── If DB empty → Hibernate auto-creates ~100+ tables                 │
│         │                                                                          │
│         ├── Phase C: Bootstrap admin user                                          │
│         │   └── KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD → creates admin in master     │
│         │   └── Only if admin doesn't already exist in DB                         │
│         │                                                                          │
│         ├── Phase D: --import-realm processing                                    │
│         │   └── Scans /opt/keycloak/data/import/                                  │
│         │   └── Finds equitycart-realm.json                                       │
│         │   └── Checks: does realm "equitycart" exist in DB?                      │
│         │       ├── NO (first boot) → Import realm: roles, clients, users         │
│         │       │   └── Log: "Imported realm equitycart"                          │
│         │       └── YES (subsequent boot) → SKIP (no-op, no log message)          │
│         │                                                                          │
│         └── Phase E: Start HTTP server                                            │
│             └── Application port: 8080 (mapped to host 8180)                      │
│             └── Management port: 9000 (mapped to host 9000)                       │
│             └── Log: "Keycloak started in Xs (started ... running ...)"           │
│                                                                                    │
│  3. start-pets.sh readiness poll:                                                  │
│     └── curl http://localhost:8180/realms/equitycart/.well-known/openid-configuration
│         ├── Before Phase E complete → connection refused                           │
│         ├── After Phase E but before Phase D → 404 (realm doesn't exist yet)      │
│         └── After all phases → 200 + JSON response → READY                        │
│                                                                                    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

**Why we poll OIDC discovery instead of /health/ready:**
1. `/health/ready` lives on port 9000 — a different port from the application. It tells you "Keycloak process is running" but NOT "the realm is imported and tokens can be issued."
2. OIDC discovery (`/realms/equitycart/.well-known/openid-configuration`) on port 8180 confirms:
   - Keycloak HTTP server is up (port responsive)
   - The `equitycart` realm exists (successfully imported)
   - Token endpoints are configured (discovery JSON returned)
   - This is the same endpoint Spring Security will call to auto-configure itself
3. It's a SEMANTIC readiness check: "Can services actually authenticate?" vs "Is the process alive?"

---

#### 13.1.5: Subsequent Boots — What Changes (and What Doesn't)

```
After first boot, the keycloak PostgreSQL database contains ALL configuration.
The JSON file is IGNORED on subsequent starts.

┌─── docker compose restart keycloak ────────────────────┐
│                                                         │
│  1. kc.sh start-dev --import-realm                     │
│  2. Connect to PostgreSQL → tables already exist       │
│  3. Bootstrap admin → admin already exists → SKIP      │
│  4. --import-realm → realm "equitycart" exists → SKIP  │
│  5. Start HTTP server → READY                          │
│                                                         │
│  Time: ~3-5 seconds (vs ~10-15s on first boot)         │
└─────────────────────────────────────────────────────────┘
```

**Implication:** Editing `equitycart-realm.json` and restarting has NO EFFECT. The database is the source of truth.

---

#### 13.1.6: How to Update Keycloak Configuration After First Boot

Three approaches, in order of preference:

**Option A: Admin Console (GUI) — Best for quick changes**
```
Browser → http://localhost:8180/admin → login admin/admin
→ Select "equitycart" realm (dropdown, top-left)
→ Navigate: Realm Settings, Clients, Users, Realm Roles, etc.
→ Make changes → they're saved to PostgreSQL immediately
```
Use for: Adding a user, changing a client redirect URI, enabling a mapper, adjusting token lifetimes.

**Option B: Admin REST API — Best for scripting/automation**
```bash
# 1. Get admin token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8180/realms/master/protocol/openid-connect/token \
  -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" \
  | jq -r .access_token)

# 2. Create a new user via API
curl -s -X POST http://localhost:8180/admin/realms/equitycart/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","enabled":true,"credentials":[{"type":"password","value":"pass123","temporary":false}]}'

# 3. List all clients
curl -s http://localhost:8180/admin/realms/equitycart/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.[].clientId'
```
Use for: CI/CD pipelines, automated test setup, bulk operations.

**Option C: Nuclear — Drop database and re-import (fresh start)**
```bash
docker compose -f docker/docker-pets.yml down -v   # -v removes volumes (all data lost!)
# Edit equitycart-realm.json as needed
sh docker/start-pets.sh                             # Fresh start, JSON imported again
```
Use for: Major realm restructuring, testing the import itself, resetting to known state. WARNING: destroys ALL data in ALL databases (postgres volume shared).

**Option C-lite: Drop only Keycloak database**
```bash
docker exec postgres psql -U postgres -c "DROP DATABASE keycloak;"
docker exec postgres psql -U postgres -c "CREATE DATABASE keycloak;"
docker compose -f docker/docker-pets.yml restart keycloak
# Keycloak boots → empty DB → imports realm JSON fresh
```
Use for: Resetting Keycloak without affecting application databases.

---

#### 13.1.7: Token Acquisition Flows — Step-by-Step What Happens

**Flow 1: Resource Owner Password Credentials (ROPC) — for testing**

```
curl -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=customer1" \
  -d "password=Test@1234"
```

Step-by-step what Keycloak does:
```
1. Receive POST to /realms/equitycart/protocol/openid-connect/token
2. Parse grant_type=password → ROPC flow
3. Validate client: client_id=equitycart-gateway
   └── Is directAccessGrantsEnabled=true? YES → proceed
   └── Verify client_secret matches stored hash → YES → client authenticated
4. Validate user credentials:
   └── Find user "customer1" in equitycart realm → found
   └── Is user enabled? YES
   └── Hash provided password, compare with stored hash → MATCH
5. Build token claims:
   └── sub = Keycloak UUID of customer1
   └── Apply protocol mappers:
       ├── roles-mapper: user has CUSTOMER role → "roles": ["CUSTOMER"]
       └── userId-mapper: user.attributes.userId = "1" → "userId": 1
   └── Add standard claims: iss, aud, exp, iat, typ, azp
6. Sign token with realm's PRIVATE RSA key (RS256)
7. Return JSON:
   {
     "access_token": "eyJhbG...",   ← RS256 signed JWT
     "refresh_token": "eyJhbG...",  ← for getting new access tokens
     "token_type": "Bearer",
     "expires_in": 900,             ← seconds until access token expires
     "refresh_expires_in": 1800
   }
```

**Flow 2: Client Credentials — for service-to-service**

```
curl -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=equitycart-services" \
  -d "client_secret=services-secret"
```

Step-by-step:
```
1. Parse grant_type=client_credentials
2. Validate client: equitycart-services
   └── Is serviceAccountsEnabled=true? YES
   └── Verify client_secret → MATCH
3. Look up service account user: "service-account-equitycart-services"
   └── User has realmRoles: ["SERVICE"]
4. Build token:
   └── sub = UUID of service account user
   └── roles-mapper: "roles": ["SERVICE"]
   └── NO userId attribute (service accounts don't have it)
5. Sign with RS256, return token
```

**Key difference:** No human credentials involved. The CLIENT authenticates (not a user). The token represents the service itself, not any human user. Controllers checking `userId` will get null from Keycloak tokens for service accounts — this is expected (service calls don't have a "user" context).

---

#### 13.1.8: Operational Obstacles — Full Debug-Mode Explanations

**Obstacle 1: Health endpoint on management port (not main port)**

The problem:
```bash
# This was in start-pets.sh originally:
until curl -sf http://localhost:8180/health/ready > /dev/null 2>&1; do
  echo "keycloak not ready, retrying in 5s..."; sleep 5
done
# Result: infinite loop. The URL never responds.
```

Why it fails:
- Since Keycloak 24 (June 2024), health/metrics endpoints are served on a SEPARATE management port (default: 9000), not the main application port (8080/mapped to 8180)
- This is a deliberate security separation: infrastructure probes (health, metrics) should not be exposed on the same port as user-facing endpoints (login, token)
- `KC_HEALTH_ENABLED=true` only ENABLES the health subsystem — it doesn't change WHICH PORT serves it
- The correct URL is either:
  - `http://localhost:9000/health/ready` (management port) — requires port 9000 to be mapped in docker-compose
  - `http://localhost:8180/realms/equitycart/.well-known/openid-configuration` (application port) — better semantic check

The fix (what we use):
```bash
until curl -sf http://localhost:8180/realms/equitycart/.well-known/openid-configuration > /dev/null 2>&1; do
  echo "keycloak not ready, retrying in 5s..."; sleep 5
done
```

Why OIDC discovery is better than /health/ready:
- `/health/ready` only confirms the JVM is alive and DB connected. It doesn't confirm the realm was imported.
- OIDC discovery confirms: (a) HTTP server responding, (b) realm exists, (c) token endpoints configured
- It's the exact endpoint Spring Security will call at service startup — if this works, service OAuth2 integration will work

**Obstacle 2: Deprecated KEYCLOAK_ADMIN env vars (Keycloak 26.x)**

The error in Docker logs:
```
WARN  [org.keycloak.quarkus.runtime.configuration.Configuration] 
  The following used environment variables are DEPRECATED and will be removed in a future release:
  - KEYCLOAK_ADMIN replaced by KC_BOOTSTRAP_ADMIN_USERNAME
  - KEYCLOAK_ADMIN_PASSWORD replaced by KC_BOOTSTRAP_ADMIN_PASSWORD
```

Historical context:
- Pre-v17 (WildFly era): Used `KEYCLOAK_` prefix for everything
- v17-v25 (early Quarkus era): Mixed `KEYCLOAK_` and `KC_` prefixes
- v26+ (current): Unified under `KC_` prefix. `KEYCLOAK_` still works but is deprecated (shows warning)
- The "BOOTSTRAP" in the name clarifies semantics: these credentials are used ONCE on first boot to create the initial admin. After that, credentials live in the database and these env vars are irrelevant on restart.

The fix:
```yaml
# Old (deprecated, shows warning):
- KEYCLOAK_ADMIN=admin
- KEYCLOAK_ADMIN_PASSWORD=admin

# New (correct for v26+):
- KC_BOOTSTRAP_ADMIN_USERNAME=admin
- KC_BOOTSTRAP_ADMIN_PASSWORD=admin
```

**Obstacle 3: --import-realm is first-boot-only (edit-restart does nothing)**

The scenario that confuses developers:
```
1. Start Keycloak → realm imports fine
2. Notice a mistake in equitycart-realm.json
3. Edit the JSON file
4. Restart Keycloak container
5. Check admin console → old values still there
6. Confusion: "Why didn't my change apply?"
```

Root cause:
- `--import-realm` checks: "Does a realm named X exist in the database?"
  - If NO → import the JSON
  - If YES → skip entirely (no merge, no update, no partial import)
- This is by design: after first import, the database is the authoritative source. If Keycloak overwrote DB state from the JSON on every restart, any admin console changes would be lost.

When this behavior makes sense:
- Admin adds a user via console → restart shouldn't delete that user
- Admin changes token lifetime via console → restart shouldn't revert it
- CI/CD deploys new Keycloak version → existing configuration preserved

How to make JSON changes take effect:
- Option A: Use admin console/API to make the equivalent change
- Option B: Drop the keycloak database and restart (nuclear — see Section 13.1.6 Option C-lite)
- Option C: Use Keycloak's `--override=true` flag (v26+) — DANGEROUS, overwrites everything

**Obstacle 4: serviceAccountRealmRoles is not a valid field**

What we tried:
```json
{
  "clientId": "equitycart-services",
  "serviceAccountsEnabled": true,
  "serviceAccountRealmRoles": ["SERVICE"]   ← THIS FIELD DOESN'T EXIST
}
```

What happened: Keycloak imported successfully, no error, no warning. But when we got a Client Credentials token, the `roles` claim was empty (no SERVICE role). Silent failure.

Why Keycloak silently ignores it:
- Keycloak's JSON import uses Jackson deserialization with `@JsonIgnoreProperties(ignoreUnknown = true)`
- Unknown fields are simply discarded during deserialization
- This is a design choice: allows forward compatibility (newer export formats work with older Keycloak) but means TYPOS in field names are also silently ignored

The correct approach:
```json
"users": [
  {
    "username": "service-account-equitycart-services",
    "serviceAccountClientId": "equitycart-services",
    "realmRoles": ["SERVICE"]
  }
]
```

Why it must be a user entry:
- Keycloak's data model: a service account IS a user, just one that authenticates via its parent client's credentials instead of a password
- The client defines `serviceAccountsEnabled: true` → Keycloak creates (or expects) a user with the naming pattern `service-account-<clientId>`
- Roles are ALWAYS assigned to users (or groups), never directly to clients
- The realm JSON export format reflects this: clients define capabilities, users define identities + roles

**Obstacle 5: Docker command format for Keycloak**

The subtle bug:
```yaml
command:
  - start-dev --import-realm    # LOOKS correct but isn't
```

What Docker does with this:
```
Docker sees: command is a YAML sequence with ONE element: "start-dev --import-realm"
Docker passes to entrypoint: /opt/keycloak/bin/kc.sh "start-dev --import-realm"
kc.sh receives: $1 = "start-dev --import-realm" (one argument with a space in it)
kc.sh tries: find subcommand named "start-dev --import-realm" → no such command → ERROR
```

Why it's confusing:
```yaml
# In a Dockerfile, this works fine:
CMD ["start-dev", "--import-realm"]   # Two separate array elements

# In docker-compose, string form works:
command: start-dev --import-realm      # Docker splits on spaces → two arguments

# But single-element list form is broken:
command:
  - start-dev --import-realm           # One element = one argument (space is part of the string)
```

The difference: Docker Compose string form uses `/bin/sh -c "start-dev --import-realm"` which applies shell word splitting. List form bypasses the shell entirely — each list element becomes exactly one exec argument, preserving spaces.

---

#### 13.1.9: Verifying the Complete Setup — Checklist

After `start-pets.sh` completes successfully, verify each layer:

```bash
# 1. Admin Console
# Browser → http://localhost:8180/admin → login admin/admin
# Select "equitycart" realm from dropdown (top-left)
# Verify: Realm Settings → General → Realm name = equitycart

# 2. OIDC Discovery (proves realm configured correctly)
curl -s http://localhost:8180/realms/equitycart/.well-known/openid-configuration | jq .
# Look for: issuer, token_endpoint, jwks_uri fields

# 3. JWKS (proves RSA keys generated)
curl -s http://localhost:8180/realms/equitycart/protocol/openid-connect/certs | jq .
# Look for: "kty": "RSA", "alg": "RS256", "use": "sig"

# 4. Get user token (ROPC flow)
TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=gateway-secret" \
  -d "username=customer1" \
  -d "password=Test@1234" | jq -r .access_token)
echo $TOKEN

# 5. Decode token (verify claims structure)
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# Expected: "roles": ["CUSTOMER"], "userId": 1, "preferred_username": "customer1"

# 6. Get service token (Client Credentials)
SERVICE_TOKEN=$(curl -s -X POST http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=equitycart-services" \
  -d "client_secret=services-secret" | jq -r .access_token)
echo $SERVICE_TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# Expected: "roles": ["SERVICE"], no userId claim

# 7. Verify custom auth STILL works (dual-mode)
# Start application services → POST /api/auth/login → custom HS256 JWT issued → use it → should work
# Both token types coexist — services validate HS256 (custom), Keycloak issues RS256
```

---

#### 13.1.10: What Keycloak Has Given Us (vs Custom Auth Comparison)

| Concern | Custom Auth (Steps 1-4) | Keycloak (Step 5+) |
|---------|------------------------|-------------------|
| **Token signing** | HS256 (symmetric, shared secret) | RS256 (asymmetric, private key only at IdP) |
| **Key compromise impact** | ANY service can forge tokens | Only Keycloak can forge tokens |
| **Key rotation** | Change secret → restart ALL services | Generate new key pair → zero downtime |
| **Key distribution** | Config Server YAML (plaintext) | JWKS endpoint (auto-discovery, cached) |
| **Token revocation** | Impossible (JWT valid until expiry) | Session invalidation at IdP level |
| **User management** | Custom code (UserService, BCrypt) | Admin console, REST API, LDAP federation |
| **Password policy** | None (beyond BCrypt storage) | Length, complexity, history, lockout |
| **Brute force protection** | None | Built-in (account lockout after N failures) |
| **MFA** | Not available | TOTP, WebAuthn, SMS (configurable per realm) |
| **Social login** | Not available | Google, GitHub, Facebook, etc. (pre-built) |
| **Standards compliance** | Custom (no standard) | OIDC, OAuth2, SAML 2.0 certified |
| **Discovery endpoint** | None (hardcoded config) | `.well-known/openid-configuration` |
| **Refresh tokens** | Custom table (AuthService) | IdP-managed with rotation |
| **Service-to-service** | Self-signed JWT (ServiceTokenProvider) | Client Credentials flow (proper) |
| **Audit trail** | Application logs only | Keycloak events (login, token, admin actions) |

**The fundamental shift:** With custom auth, the jwt.secret is a "skeleton key" — any entity that has it can impersonate anyone. With Keycloak + RS256, services can only VERIFY tokens (public key), never FORGE them (private key stays at Keycloak). This is the difference between "trust because you could attack me" and "trust because cryptography proves your identity."

---

### Section 13.2: OAuth2 Resource Server Migration — Debug-Mode Walkthrough (Phase 8 Step 6)

This section explains how `spring-boot-starter-oauth2-resource-server` replaces the custom `JwtAuthenticationFilter` for services in `mode: oauth2`. Every class, every Spring auto-configuration, every internal step explained.

---

#### 13.2.1: What Is an "OAuth2 Resource Server"?

In OAuth2 terminology, there are three actors:

```
┌─────────────────────┐     ┌──────────────────────────────┐     ┌──────────────────────┐
│  Authorization      │     │         Client               │     │   Resource Server    │
│  Server (Keycloak)  │     │  (gateway / frontend)        │     │ (order-service, etc) │
│                     │     │                              │     │                      │
│  - Issues tokens    │◄────│  - Requests tokens on        │────►│  - Protects APIs     │
│  - Manages users    │     │    behalf of users           │     │  - Validates tokens  │
│  - Signs with       │     │  - Redirects to login        │     │  - Returns data      │
│    private key      │     │                              │     │                      │
└─────────────────────┘     └──────────────────────────────┘     └──────────────────────┘
```

Your microservices (order, product, portfolio, etc.) are **Resource Servers**. They:
- Do NOT issue tokens (that's Keycloak's job)
- Do NOT redirect users to login pages (that's the client's job)
- ONLY validate incoming Bearer tokens and decide: "Is this legitimate? What can this caller do?"

`spring-boot-starter-oauth2-resource-server` is Spring's production-grade implementation of this role.

---

#### 13.2.2: What the Starter Brings — Dependency Breakdown

Adding `api 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'` to `commons/build.gradle` pulls in:

| Library | What it does |
|---------|-------------|
| `spring-security-oauth2-resource-server` | Core resource server support: `BearerTokenAuthenticationFilter`, `JwtAuthenticationProvider`, exception handling for 401/403 |
| `spring-security-oauth2-jose` | JWT handling: `NimbusJwtDecoder` (validates RS256/RS384/RS512/ES256), JWKS key fetching and caching |
| `nimbus-jose-jwt` (transitive) | Low-level JOSE library that does the actual RSA signature math |
| `spring-security-oauth2-core` | Core OAuth2 concepts: `OAuth2TokenValidator`, `JwtIssuerValidator`, `JwtTimestampValidator` |

**Critical auto-configuration triggered by the starter:**

When Spring Boot detects `spring-security-oauth2-resource-server` on classpath AND finds `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` property, it auto-creates:
1. A `JwtDecoder` bean (NimbusJwtDecoder configured with the JWKS URI)
2. NOTHING else automatically — you must define your own `SecurityFilterChain` to activate it

This is why `OAuth2ResourceServerConfig` exists: it defines the SecurityFilterChain that tells Spring "use oauth2ResourceServer with JWT mode."

---

#### 13.2.3: Configuration in equitycart-config/application.yml

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI:http://localhost:8180/realms/equitycart/protocol/openid-connect/certs}
```

**What this property does at startup:**

1. Spring Boot auto-configuration (`OAuth2ResourceServerJwtConfiguration`) detects this property
2. Creates a `NimbusJwtDecoder` bean configured to fetch RSA public keys from that URL
3. On first token validation, `NimbusJwtDecoder`:
   - Fetches the JWKS JSON from that URL (contains public keys in JWK format)
   - Caches the keys in memory (default cache TTL: 5 minutes)
   - When a JWT arrives, extracts the `kid` (Key ID) from its header
   - Looks up the matching public key in the cache
   - Validates the RS256 signature using that public key
   - If `kid` not found in cache → re-fetches JWKS (handles key rotation automatically)

**`jwk-set-uri` vs `issuer-uri` — when to use which:**

| Property | What Spring does | Issuer validation | Auto-discovery | Use when |
|----------|-----------------|-------------------|----------------|----------|
| `jwk-set-uri` | Fetches keys directly from the URL you provide | NO (doesn't check `iss` claim) | NO (you specify the full URL) | Dev environments, when issuer URL differs between Docker/local |
| `issuer-uri` | Fetches `{value}/.well-known/openid-configuration`, finds `jwks_uri` from that JSON | YES (rejects tokens where `iss` ≠ this value) | YES (auto-discovers all endpoints) | Production, when issuer URL is consistent |

We use `jwk-set-uri` because in Docker the services see Keycloak at `http://keycloak:8080` but Keycloak puts `iss: http://localhost:8180/realms/equitycart` in tokens (because that's how the browser accessed it). This mismatch would cause issuer validation failure with `issuer-uri`.

**Environment variable override for Docker:**
```yaml
# In docker-compose-services.yml for product-service:
KEYCLOAK_JWKS_URI=http://keycloak:8080/realms/equitycart/protocol/openid-connect/certs
```

Inside Docker, services resolve `keycloak` via Docker's internal DNS to the Keycloak container's IP. Port 8080 is Keycloak's internal port (not the host-mapped 8180).

---

#### 13.2.4: The Dual-Mode Security Design — How It Works

```
┌───────────────────────────────────────────────────────────────────────────┐
│            Dual-Mode Security — @ConditionalOnProperty                     │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  equitycart.security.mode = ?                                              │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ mode = "custom"  (SecurityAutoConfig activates)                      │  │
│  │                                                                      │  │
│  │ Uses: JwtAuthenticationFilter (your custom OncePerRequestFilter)     │  │
│  │ Validates: HMAC-SHA256 signature using shared jwt.secret             │  │
│  │ Decoder: JJWT library (Jwts.parser().verifyWith(key))                │  │
│  │ Principal: Long userId (from "sub" claim)                            │  │
│  │ Services: order, portfolio, ledger, notification, market-data        │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ mode = "oauth2"  (OAuth2ResourceServerConfig activates)              │  │
│  │                                                                      │  │
│  │ Uses: BearerTokenAuthenticationFilter (Spring's built-in)            │  │
│  │ Validates: RS256 signature using Keycloak's public key (from JWKS)   │  │
│  │ Decoder: NimbusJwtDecoder (fetches JWKS, caches keys)                │  │
│  │ Converter: KeycloakJwtAuthenticationConverter                        │  │
│  │ Principal: Long userId (from "userId" claim via converter)           │  │
│  │ Services: product-service (first migrated)                           │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ mode = not set  (NEITHER activates)                                  │  │
│  │                                                                      │  │
│  │ No SecurityFilterChain bean → Spring Boot default (permit all)       │  │
│  │ Services: discovery-server, config-server (infrastructure, no auth)  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
└───────────────────────────────────────────────────────────────────────────┘
```

**Why dual-mode instead of switching everything at once:**
- If Keycloak has an issue, services in `mode: custom` still work
- You can migrate one service at a time, test it, then move the next
- This mirrors real-world migrations — never "big bang" cutover

**How to migrate a service:** Change one line in its config:
```yaml
# Before:
equitycart:
  security:
    mode: custom

# After:
equitycart:
  security:
    mode: oauth2
```

---

#### 13.2.5: OAuth2ResourceServerConfig — Line-by-Line

```java
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@ConditionalOnProperty(name = "equitycart.security.mode", havingValue = "oauth2")
public class OAuth2ResourceServerConfig {

    private static final Logger log = LogManager.getLogger(OAuth2ResourceServerConfig.class);
    private final KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        log.info("Enabling OAuth2 Resource Server security (Keycloak JWKS validation)");
        return httpSecurity
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(se -> se.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/actuator/**").permitAll()
                .anyRequest().authenticated())
            .build();
    }
}
```

**`@ConditionalOnProperty(name = "equitycart.security.mode", havingValue = "oauth2")`**
- This bean is ONLY created when the property equals "oauth2"
- If property is "custom" or missing → this entire class is skipped during component scanning
- Spring evaluates this condition during the bean definition phase (before instantiation)

**`.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`**
- Tells Spring Security: "This application is an OAuth2 Resource Server that accepts JWT tokens"
- Internally, this registers `BearerTokenAuthenticationFilter` in the filter chain
- The filter:
  1. Checks every request for `Authorization: Bearer xxx` header
  2. If found → extracts token string → passes to `JwtDecoder` bean (auto-configured from jwk-set-uri)
  3. If JwtDecoder validates → passes `Jwt` to the configured `jwtAuthenticationConverter`
  4. If no Authorization header → skips (no authentication set, handled by authorization rules)

**`.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter)`**
- Overrides Spring's default converter (`JwtAuthenticationConverter`) with our custom one
- Default converter would set `authentication.getPrincipal()` to the entire `Jwt` object (not Long)
- Our converter transforms `Jwt` → `UsernamePasswordAuthenticationToken(Long userId, null, authorities)`
- This is WHERE backward compatibility is maintained — controllers don't change

**What happens WITHOUT `.jwtAuthenticationConverter(...)`:**
- Spring uses `JwtAuthenticationConverter` (default)
- It creates `JwtAuthenticationToken` with `principal = Jwt object`
- Your controllers doing `(Long) authentication.getPrincipal()` → ClassCastException
- Your controllers doing `authentication.getAuthorities()` → returns SCOPE_xxx (not ROLE_xxx)

**`.csrf(AbstractHttpConfigurer::disable)` — Same as SecurityAutoConfig**
- Stateless APIs don't use session cookies → CSRF protection is irrelevant and would block legitimate API calls

**`.sessionManagement(se -> se.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`**
- No HttpSession created. Each request is validated independently via the Bearer token.
- Without this: Spring might create a session after successful auth → subsequent requests would be authenticated via session cookie (bypassing JWT validation). That's wrong for an API.

**`@EnableMethodSecurity`**
- Activates `@PreAuthorize` annotations on controller methods
- Without this: `@PreAuthorize("hasRole('SELLER')")` annotations are silently ignored → any authenticated user can access seller endpoints

---

#### 13.2.6: KeycloakJwtAuthenticationConverter — Line-by-Line

```java
@Component
public class KeycloakJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Object userObj = jwt.getClaim("userId");
    Object roleObj = jwt.getClaim("roles");

    Long userId = userObj != null ? Long.parseLong(userObj.toString()) : 0L;
    List<String> roles = roleObj != null ? (List<String>) roleObj : List.of();

    List<SimpleGrantedAuthority> authorities =
        roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();

    return new UsernamePasswordAuthenticationToken(userId, null, authorities);
  }
}
```

**`@Component`**
- Registers this class as a Spring bean in the application context
- `OAuth2ResourceServerConfig` uses `@RequiredArgsConstructor` with `private final KeycloakJwtAuthenticationConverter` → Spring injects this bean via constructor injection
- Without `@Component`: Spring can't find the bean → `NoSuchBeanDefinitionException` at startup

**`implements Converter<Jwt, AbstractAuthenticationToken>`**
- This is `org.springframework.core.convert.converter.Converter` (NOT Jackson's Converter)
- Generic types: Input = `org.springframework.security.oauth2.jwt.Jwt`, Output = `AbstractAuthenticationToken`
- The interface requires ONE method: `AbstractAuthenticationToken convert(Jwt jwt)`
- Spring's `BearerTokenAuthenticationFilter` calls this after NimbusJwtDecoder validates the token

**`jwt.getClaim("userId")`**
- The `Jwt` object contains ALL decoded claims as a `Map<String, Object>`
- `getClaim(name)` returns `Object` — the type depends on how the claim was serialized in JSON
- Our Keycloak mapper configured `jsonType.label=long` → the value arrives as `Long` (or `Integer` for small numbers)
- Service account tokens don't have this claim → returns `null`

**`Long.parseLong(userObj.toString())`**
- Handles all possible types: Long (1L → "1" → 1L), Integer (1 → "1" → 1L), String ("1" → 1L)
- `.toString()` normalizes any type to String before parsing
- If the claim is somehow non-numeric → NumberFormatException (indicates misconfigured mapper)

**`userObj != null ? ... : 0L`**
- Fallback to 0L for service account tokens (Client Credentials flow)
- Same sentinel value as the custom `ServiceTokenProvider` used (subject=0)
- Controllers checking `userId == 0` can identify service-to-service calls

**`(List<String>) roleObj`**
- Our roles-mapper configured `multivalued=true` → JWT contains `"roles": ["CUSTOMER", "SELLER"]`
- JSON array deserializes to `ArrayList<String>` in Java
- Unchecked cast is safe because we control the Keycloak mapper configuration

**`new SimpleGrantedAuthority("ROLE_" + role)`**
- Spring Security convention: `hasRole("CUSTOMER")` internally checks for authority `"ROLE_CUSTOMER"`
- The ROLE_ prefix is added during conversion, not stored in the token itself
- Alternative: use `hasAuthority("CUSTOMER")` which checks exact string — but existing code uses `hasRole()`

**`new UsernamePasswordAuthenticationToken(userId, null, authorities)`**
- 3-argument constructor → marks as authenticated (`isAuthenticated() = true`)
- `principal` (arg 1) = userId as Long → accessible via `authentication.getPrincipal()`
- `credentials` (arg 2) = null → we don't need to store the token here
- `authorities` (arg 3) = role list → accessible via `authentication.getAuthorities()`
- This is the SAME token type created by the custom `JwtAuthenticationFilter` → zero controller changes

---

#### 13.2.7: Internal Request Flow — Complete Trace (oauth2 mode)

```
Request arrives: GET /api/products, Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI...
    │
    ▼
┌─────────────────────────────────────── PRODUCT SERVICE (mode=oauth2) ─────────────────────┐
│                                                                                            │
│  DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain                           │
│                                                                                            │
│  Filter 1: MdcCorrelationFilter (from commons)                                            │
│    → Reads X-Correlation-Id → stores in MDC ThreadContext                                 │
│                                                                                            │
│  Filter 2: BearerTokenAuthenticationFilter (Spring's built-in, registered by              │
│             .oauth2ResourceServer())                                                       │
│    │                                                                                      │
│    ├── Step A: Extract token from Authorization header                                    │
│    │   └── "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI..." → removes "Bearer " prefix            │
│    │                                                                                      │
│    ├── Step B: Create BearerTokenAuthenticationToken(tokenString)                         │
│    │   └── Unauthenticated authentication request (not yet validated)                     │
│    │                                                                                      │
│    ├── Step C: Delegate to JwtAuthenticationProvider.authenticate()                       │
│    │   │                                                                                  │
│    │   ├── Step C1: NimbusJwtDecoder.decode(tokenString)                                  │
│    │   │   │                                                                              │
│    │   │   ├── Parse JWT header (base64 decode first segment)                             │
│    │   │   │   → {"alg":"RS256","typ":"JWT","kid":"abc123"}                               │
│    │   │   │                                                                              │
│    │   │   ├── Lookup key "abc123" in JWKS cache                                          │
│    │   │   │   ├── Cache HIT → use cached RSA public key                                 │
│    │   │   │   └── Cache MISS → fetch http://keycloak:8080/realms/.../certs               │
│    │   │   │                    → parse JSON → cache all keys → find "abc123"             │
│    │   │   │                                                                              │
│    │   │   ├── Validate RS256 signature                                                   │
│    │   │   │   → RSA: decrypt signature with public key → compare with SHA256(header.payload)
│    │   │   │   → Match = token was signed by Keycloak's private key (not forged)          │
│    │   │   │                                                                              │
│    │   │   ├── Validate expiry: current time < exp claim (with 60s clock skew tolerance)  │
│    │   │   │                                                                              │
│    │   │   └── Return Spring's Jwt object containing all decoded claims                   │
│    │   │                                                                                  │
│    │   ├── Step C2: Call jwtAuthenticationConverter.convert(jwt)                           │
│    │   │   → YOUR KeycloakJwtAuthenticationConverter:                                     │
│    │   │     jwt.getClaim("userId") → 1L                                                  │
│    │   │     jwt.getClaim("roles") → ["CUSTOMER"]                                         │
│    │   │     → authorities = [ROLE_CUSTOMER]                                              │
│    │   │     → return UsernamePasswordAuthenticationToken(1L, null, [ROLE_CUSTOMER])       │
│    │   │                                                                                  │
│    │   └── Return the authenticated token                                                 │
│    │                                                                                      │
│    └── Step D: SecurityContextHolder.getContext().setAuthentication(authenticatedToken)    │
│                                                                                            │
│  Filter 3: AuthorizationFilter                                                            │
│    → /api/products matches anyRequest().authenticated()                                   │
│    → SecurityContext has authentication with isAuthenticated()=true → PASS                │
│                                                                                            │
│  DispatcherServlet → ProductController                                                    │
│    → (Long) authentication.getPrincipal() → 1L                                            │
│    → @PreAuthorize("hasRole('CUSTOMER')") → checks ROLE_CUSTOMER in authorities → PASS    │
│    → Returns product data                                                                 │
│                                                                                            │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Key difference from custom mode:**
- Custom mode: YOUR `JwtAuthenticationFilter` does Steps A-D manually using JJWT library
- OAuth2 mode: Spring's `BearerTokenAuthenticationFilter` does Steps A-D using NimbusJwtDecoder
- In BOTH modes: SecurityContext ends up with same `UsernamePasswordAuthenticationToken(Long, null, authorities)`
- Controllers see NO difference — they call `authentication.getPrincipal()` and get a Long either way

---

#### 13.2.8: NimbusJwtDecoder Internals — How Key Caching Works

```
┌──────────────────────────────────────────────────────────────────────┐
│                  NimbusJwtDecoder Key Cache                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Cache state (in-memory, per JVM):                                    │
│    { "abc123": RSAPublicKey(...), "def456": RSAPublicKey(...) }       │
│                                                                       │
│  On token validation:                                                 │
│    1. Extract "kid" from JWT header → "abc123"                        │
│    2. Look up in cache → FOUND → validate signature                  │
│                                                                       │
│  On key rotation (Keycloak generates new key pair):                   │
│    1. New tokens have "kid": "ghi789" (new key)                       │
│    2. Old tokens still have "kid": "abc123" (old key)                 │
│    3. Service receives new token → looks up "ghi789" → NOT IN CACHE  │
│    4. NimbusJwtDecoder re-fetches JWKS from Keycloak                 │
│    5. New JWKS contains BOTH old + new keys                           │
│    6. Cache updated: {"abc123": ..., "def456": ..., "ghi789": ...}   │
│    7. Validates new token with new key → success                     │
│    8. Old tokens still validate (old key still in JWKS/cache)         │
│                                                                       │
│  Cache refresh triggers:                                              │
│    - Unknown "kid" encountered (immediate fetch)                      │
│    - Default cache TTL expired (implementation-specific, ~5 min)     │
│                                                                       │
│  KEY POINT: You never restart services for key rotation.              │
│  The cache self-heals by re-fetching when it encounters unknown kid. │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

**What happens if Keycloak is down during key fetch:**
- If cache has the key → token validates fine (no network call needed)
- If cache doesn't have the key → `JwtDecoderInitializationException` → HTTP 401 returned
- This means: transient Keycloak outages don't break validation of tokens signed with cached keys
- Only NEW key rotations during Keycloak downtime would cause issues (very unlikely timing)

---

#### 13.2.9: Why `@Component` on the Converter But Not on SecurityAutoConfig's Filter

```
Custom mode:
  SecurityAutoConfig has: private final JwtAuthenticationFilter jwtAuthenticationFilter;
  JwtAuthenticationFilter has @Component → Spring creates the bean

OAuth2 mode:
  OAuth2ResourceServerConfig has: private final KeycloakJwtAuthenticationConverter converter;
  KeycloakJwtAuthenticationConverter has @Component → Spring creates the bean
```

Both configs use `@RequiredArgsConstructor` (Lombok) which generates a constructor with all `final` fields. Spring uses constructor injection to provide the beans.

**Potential conflict:** Both `JwtAuthenticationFilter` (has @Component) and `KeycloakJwtAuthenticationConverter` (has @Component) are always created as beans — regardless of which mode is active. The `@ConditionalOnProperty` only controls which CONFIG CLASS (and its SecurityFilterChain) is active.

This is fine because:
- Creating the bean doesn't mean it's used in the filter chain
- `JwtAuthenticationFilter` exists as a bean in oauth2 mode but nothing adds it to the chain
- `KeycloakJwtAuthenticationConverter` exists as a bean in custom mode but nothing calls it
- They're harmless orphan beans — no side effects from instantiation

---

#### 13.2.10: Interview Questions — Step 6

**Q: "What is the difference between your custom JwtAuthenticationFilter and Spring's OAuth2 Resource Server?"**

A: Both do the same job: extract Bearer token → validate → set SecurityContext. The differences:

| Aspect | Custom Filter | OAuth2 Resource Server |
|--------|--------------|----------------------|
| Signing algorithm | HS256 (symmetric, shared secret) | RS256 (asymmetric, JWKS) |
| Key source | `@Value("${jwt.secret}")` at startup | JWKS endpoint (fetched, cached, auto-refreshed) |
| Key rotation | Requires restart of ALL services | Automatic (cache refresh on unknown kid) |
| Issuer validation | Not checked | Validates `iss` claim (when using issuer-uri) |
| Clock skew | 0 tolerance (exact expiry) | 60s tolerance (configurable) |
| Token type | Only your custom format | Any OIDC-compliant JWT |
| Error responses | Custom JSON (your code) | RFC 6750 standard WWW-Authenticate header |
| Code to maintain | ~80 lines (filter + validator) | 0 lines (framework handles it) |

**Q: "How does NimbusJwtDecoder validate RS256 internally?"**

A: RS256 = RSA signature with SHA-256 hash.
1. Decoder separates the JWT into three parts: header.payload.signature (base64 encoded)
2. Decodes the signature bytes from base64
3. Uses the RSA public key (from JWKS) to "decrypt" the signature → produces a hash
4. Independently computes SHA-256 hash of `base64(header) + "." + base64(payload)`
5. Compares the two hashes: match = token was signed by the holder of the PRIVATE key (Keycloak)
6. Forging would require the private key — having only the public key is mathematically insufficient

**Q: "What happens when the JWKS endpoint is unreachable?"**

A: Two scenarios:
- First ever validation (cold start, empty cache): `JwtDecoderInitializationException` → all tokens rejected → service effectively unusable until JWKS is reachable
- Subsequent validations (warm cache): Tokens with known `kid` still validate from cache. Tokens with unknown `kid` (after key rotation) fail. Cached keys work until Keycloak comes back.

**Q: "Why do you use `jwk-set-uri` instead of `issuer-uri`?"**

A: Issuer mismatch in development. `issuer-uri` validates that the JWT's `iss` claim matches the configured value exactly. In Docker, services see Keycloak at `http://keycloak:8080/realms/equitycart`, but tokens contain `iss: http://localhost:8180/realms/equitycart` (the URL the browser used). This mismatch causes every token to be rejected. `jwk-set-uri` skips issuer validation — it just fetches keys and validates the signature. In production (where the issuer URL is consistent), you'd use `issuer-uri` for the additional security of issuer validation.

**Q: "Your converter uses `Long.parseLong(userObj.toString())` — what if the claim is missing?"**

A: The `userObj != null ? ... : 0L` guard handles null (service account tokens). The `toString()` handles type polymorphism (Long, Integer, String all convert). If the value is non-numeric (e.g., a UUID), `NumberFormatException` propagates as a 500 error — which indicates a Keycloak mapper misconfiguration, not a runtime issue. The fix is in Keycloak config (ensure `jsonType.label=long`), not in Java code.

---

## 14. Phase 8 Step 7 — API Gateway Reactive OAuth2 Resource Server

### 14.1: Why the Gateway Needed a Security Rewrite

**Before Step 7 (custom mode — Steps 1-4):**
`JwtValidationGatewayFilter` manually extracted the Bearer token, decoded it with JJWT, and rejected requests with 401. This worked for HS256 (custom tokens from user-service) but failed completely for RS256 (Keycloak tokens). Keycloak signs with a 2048-bit RSA private key; the custom filter only knew how to verify HMAC-SHA256 signatures using the shared `jwt.secret`. When a Keycloak token arrived, the filter tried `Jwts.parser().verifyWith(hmacKey)` against an RS256 signature — that always fails. The symptom was every authenticated request returning 401 after Keycloak was introduced.

**Root cause discovery — two competing auth mechanisms:**

```
Request arrives at gateway:
    │
    ▼
JwtValidationGatewayFilter (registered via @Component — Step 4)
    → tries HS256 validation on Keycloak's RS256 token
    → JwtException: "JWT signature does not match"
    → returns 401 immediately
    → SecurityWebFilterChain NEVER even reached
```

The fix required two actions:
1. Comment out `@Component` on `JwtValidationGatewayFilter` — stops Spring from auto-registering it as a GlobalFilter
2. Replace with Spring Security's reactive OAuth2 Resource Server — handles RS256/JWKS natively

**After Step 7:**
`SecurityWebFilterChain` (Spring Security reactive) handles token extraction, JWKS fetching, RS256 signature validation, claim conversion, and SecurityContext population — all without a single line of custom validation code.

---

### 14.2: Why the Gateway Uses WebFlux, Not Servlet

Spring Cloud Gateway is built on **Netty** (non-blocking I/O) and **Project Reactor** (reactive streams). It does NOT have a Servlet container (no Tomcat, no Jetty). This has cascading implications for every security class used:

```
┌──────────────────────────────────────────────────────────────────────────┐
│           Servlet (all downstream services)  vs  WebFlux (gateway)       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Incoming request representation:                                        │
│    Servlet  → HttpServletRequest / HttpServletResponse                  │
│    WebFlux  → ServerWebExchange (wraps both request + response)          │
│                                                                          │
│  Security filter chain interface:                                        │
│    Servlet  → SecurityFilterChain (javax.servlet.Filter based)           │
│    WebFlux  → SecurityWebFilterChain (WebFilter based)                   │
│                                                                          │
│  Security DSL builder:                                                   │
│    Servlet  → HttpSecurity (spring-security-web)                         │
│    WebFlux  → ServerHttpSecurity (spring-security-webflux)               │
│                                                                          │
│  Authorization rule method:                                              │
│    Servlet  → .authorizeHttpRequests()                                   │
│    WebFlux  → .authorizeExchange()                                       │
│                                                                          │
│  Security context storage:                                               │
│    Servlet  → SecurityContextHolder (ThreadLocal — one thread per req)   │
│    WebFlux  → ReactiveSecurityContextHolder (Reactor Context —           │
│               propagates through the reactive pipeline, no ThreadLocal)  │
│                                                                          │
│  Thread model:                                                           │
│    Servlet  → One thread blocked per request (pool of ~200 threads)      │
│    WebFlux  → 2 event-loop threads (non-blocking), handles thousands     │
│               of concurrent requests; I/O never blocks the thread        │
│                                                                          │
│  Enabling annotation:                                                    │
│    Servlet  → @EnableWebSecurity (implicit) / @EnableMethodSecurity      │
│    WebFlux  → @EnableWebFluxSecurity (explicitly required)               │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

**Why you can't mix them:** If you accidentally import `HttpSecurity` in a WebFlux app, Spring will not find a `HttpSecurity` bean to inject → `NoSuchBeanDefinitionException` at startup. Similarly, `SecurityFilterChain` (servlet) is never used by WebFlux's `WebFilterChainProxy`. They are completely separate infrastructure trees.

---

### 14.3: SecurityConfig.java — Line-by-Line

**File:** `api-gateway/.../config/SecurityConfig.java`

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  private static final Logger log = LogManager.getLogger(SecurityConfig.class);

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    log.info("Configuring reactive OAuth2 Resource Server security for API Gateway");
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/api/auth/**", "/actuator/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakReactiveConverter())))
        .build();
  }

  private Converter<Jwt, Mono<AbstractAuthenticationToken>> keycloakReactiveConverter() {
    return jwt -> {
      Object userObj = jwt.getClaim("userId");
      Object roleObj = jwt.getClaim("roles");
      Long userId = userObj != null ? Long.parseLong(userObj.toString()) : 0L;
      List<String> roles = roleObj != null ? (List<String>) roleObj : List.of();
      List<SimpleGrantedAuthority> authorities =
          roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
      log.debug("Gateway authenticated userId={} with roles={}", userId, roles);
      return Mono.just(new UsernamePasswordAuthenticationToken(userId, null, authorities));
    };
  }
}
```

**`@EnableWebFluxSecurity`**
- Activates Spring Security's WebFlux infrastructure. Without this, no `SecurityWebFilterChain` is registered, and all requests pass through without authentication.
- Internally registers: `WebFilterChainProxy` (reactive entry point), `ReactiveAuthenticationManager`, `ReactiveSecurityContextHolder` utilities.
- This is NOT `@EnableMethodSecurity` — the gateway has no controllers with `@PreAuthorize`. It only routes. Method security would be meaningless here.

**`SecurityWebFilterChain` bean (return type)**
- The reactive equivalent of servlet's `SecurityFilterChain`
- Spring's `WebFilterChainProxy` holds a list of these chains and delegates incoming `ServerWebExchange` to the first chain whose path matcher matches
- This bean REPLACES the `JwtValidationGatewayFilter` (which is now disabled) as the auth enforcement mechanism

**`.csrf(ServerHttpSecurity.CsrfSpec::disable)`**
- CSRF attacks exploit browser cookie behavior: attacker's site tricks the browser into sending authenticated requests to your site using the session cookie.
- JWT is sent in `Authorization` header (not a cookie), so CSRF cannot happen — the attacker's page can't read or set that header cross-origin.
- Disabling CSRF also removes the CSRF token requirement from `POST/PUT/DELETE` requests.

**`.authorizeExchange(...)`**
- Reactive equivalent of servlet's `.authorizeHttpRequests()`
- `.pathMatchers("/api/auth/**", "/actuator/**").permitAll()` — these paths bypass authentication. `/api/auth/**` routes to user-service for login/register (no token yet). `/actuator/**` for health checks.
- `.anyExchange().authenticated()` — ALL other paths require a valid, non-expired token. If `ReactiveSecurityContextHolder` has no authentication after the OAuth2 filter runs, the request gets 401.

**`.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`**
- Registers `AuthenticationWebFilter` (reactive equivalent of servlet's `BearerTokenAuthenticationFilter`) into the `SecurityWebFilterChain`.
- This filter is responsible for:
  - Extracting `Bearer <token>` from the `Authorization` header
  - Passing the token to `NimbusReactiveJwtDecoder` (auto-configured by Spring Boot from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`)
  - Calling your converter after successful validation
  - Storing the resulting `Authentication` in `ReactiveSecurityContextHolder`

**`keycloakReactiveConverter()` — Why `Mono<AbstractAuthenticationToken>` and not just `AbstractAuthenticationToken`**

Servlet version (commons `KeycloakJwtAuthenticationConverter`):
```
Converter<Jwt, AbstractAuthenticationToken>
convert(Jwt jwt) → returns AbstractAuthenticationToken directly
```

Gateway WebFlux version (this class):
```
Converter<Jwt, Mono<AbstractAuthenticationToken>>
convert(Jwt jwt) → returns Mono<AbstractAuthenticationToken>
```

The reactive pipeline requires every step to return a reactive type (`Mono` or `Flux`). The `AuthenticationWebFilter` internally calls `flatMap` on the converter's result. If you returned a raw `AbstractAuthenticationToken`, there's no way to chain it with `.flatMap()`. The conversion itself is synchronous (no I/O), but it MUST be wrapped in `Mono.just(...)` to fit the reactive contract.

**Why the converter is duplicated (not shared from commons):**
- commons module depends on `jakarta.servlet` transitively (OncePerRequestFilter, HttpServletRequest). If api-gateway imported commons, it would pull servlet classes into a WebFlux application. Spring Boot would then detect both Servlet and WebFlux on the classpath and behave unpredictably (MVC auto-configuration conflict).
- The converter logic is identical (same claim extraction, same UsernamePasswordAuthenticationToken), but it lives in the gateway as a private method returning `Mono<>` — the reactive type makes it gateway-specific and unreusable by servlet services anyway.

---

### 14.4: Why JwtValidationGatewayFilter @Component Was Commented Out

**File:** `api-gateway/.../filter/JwtValidationGatewayFilter.java`, line 65:
```java
// @Component -> Commenting as this No Longer needed after implementation of Keycloak Security
```

**What `@Component` on a `GlobalFilter` does:**
Spring Boot's auto-configuration scans for beans implementing `GlobalFilter`. When it finds one, it registers it in the `GatewayFilterChain`. With `@Component` present:
- Spring creates `JwtValidationGatewayFilter` bean at startup
- Spring Cloud Gateway registers it as a GlobalFilter (runs on EVERY request)
- It runs BEFORE `SecurityWebFilterChain` handles the OAuth2 validation

**The conflict:**
```
Request: Authorization: Bearer <keycloak-RS256-token>
    │
    ▼
JwtValidationGatewayFilter.filter() [HIGHEST_PRECEDENCE]
    → extracts token
    → calls Jwts.parser().verifyWith(hmacSecretKey)  // HS256 key
    → RS256 signature ≠ HMAC signature → JwtException thrown
    → returns 401 with body {"error":"Invalid or expired token"}
    ← SecurityWebFilterChain never runs
    ← NimbusReactiveJwtDecoder never called
```

**After removing @Component:**
```
Request: Authorization: Bearer <keycloak-RS256-token>
    │
    ▼
WebFilterChainProxy → SecurityWebFilterChain
    → AuthenticationWebFilter extracts token
    → NimbusReactiveJwtDecoder validates RS256 via JWKS
    → keycloakReactiveConverter() extracts userId, roles
    → ReactiveSecurityContextHolder populated
    → AuthorizationWebFilter checks authentication → PASS
    → Spring Cloud Gateway routes to downstream service
```

The `JwtValidationGatewayFilter` class still EXISTS — it's just not registered as a bean, so it has no effect. It's kept as code reference for the HS256 custom filter pattern (Steps 1-4 legacy).

---

### 14.5: Token Forwarding to Downstream Services

`SecurityWebFilterChain` combined with Spring Cloud Gateway's HTTP proxying handles token forwarding: when `AuthenticationWebFilter` populates `ReactiveSecurityContextHolder` with the validated token, the gateway's HTTP proxying reads the original `Authorization: Bearer <token>` header from the incoming `ServerWebExchange` and forwards it unchanged to the downstream service.

The downstream service then receives the same Bearer token and validates it independently via its own `BearerTokenAuthenticationFilter` (oauth2 mode) or `JwtAuthenticationFilter` (custom mode). This is **defense in depth** — the gateway pre-validates, but services never trust the gateway blindly.

---

### 14.6: Internal Request Flow — Complete Trace (Step 7)

```
Browser sends: GET /api/products, Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.xxx.yyy
    │
    ▼
Netty Event Loop (port 8080) — single thread, non-blocking
    │
    ▼
WebFilterChainProxy (Spring Security's reactive entry point)
    │
    ▼
SecurityWebFilterChain — THIS bean from SecurityConfig.java
    │
    ├── Step 1: AuthenticationWebFilter
    │   (auto-registered by .oauth2ResourceServer().jwt())
    │   │
    │   ├── A: ServerBearerTokenAuthenticationConverter
    │   │   → reads "Authorization" header → finds "Bearer eyJhbGciOiJSUzI1NiJ9.xxx.yyy"
    │   │   → strips "Bearer " prefix → tokenString = "eyJhbGciOiJSUzI1NiJ9.xxx.yyy"
    │   │   → If no header: returns empty Mono → filter chain continues WITHOUT auth
    │   │   → If malformed: returns Mono.error → 400 Bad Request
    │   │
    │   ├── B: NimbusReactiveJwtDecoder.decode(tokenString)
    │   │   [auto-configured from jwk-set-uri in application.yml]
    │   │   │
    │   │   ├── B1: Base64-decode JWT header → {"alg":"RS256","kid":"abc-key-123"}
    │   │   │
    │   │   ├── B2: Look up "abc-key-123" in JWKS cache
    │   │   │   Cache HIT  → use cached RSAPublicKey
    │   │   │   Cache MISS → fetch http://keycloak:8080/realms/equitycart/.../certs
    │   │   │                → parse JSON array of JWK objects
    │   │   │                → find entry with "kid":"abc-key-123"
    │   │   │                → reconstruct RSAPublicKey from "n" and "e" fields
    │   │   │                → store in cache
    │   │   │
    │   │   ├── B3: Verify RS256 signature (reactive, runs on scheduler thread)
    │   │   │   SHA256(base64(header) + "." + base64(payload))
    │   │   │   == RSA_decrypt(signature_bytes, RSAPublicKey)
    │   │   │   Match → token was signed by Keycloak's PRIVATE key ✓
    │   │   │   Mismatch → Mono.error(JwtValidationException) → 401
    │   │   │
    │   │   ├── B4: Validate exp claim
    │   │   │   currentTime < exp + 60s (clock skew tolerance) → valid ✓
    │   │   │   currentTime ≥ exp + 60s → Mono.error → 401
    │   │   │
    │   │   └── B5: Return Mono<Jwt> (Spring's Jwt object, all claims decoded)
    │   │
    │   ├── C: keycloakReactiveConverter().convert(jwt) [YOUR code]
    │   │   → jwt.getClaim("userId") → "1" (String, from Keycloak mapper)
    │   │   → Long.parseLong("1") → 1L
    │   │   → jwt.getClaim("roles") → ["CUSTOMER"]
    │   │   → authorities = [SimpleGrantedAuthority("ROLE_CUSTOMER")]
    │   │   → return Mono.just(UsernamePasswordAuthenticationToken(1L, null, [ROLE_CUSTOMER]))
    │   │
    │   └── D: ReactiveSecurityContextHolder.withAuthentication(authToken)
    │       → stores in Reactor Context (propagates through all downstream operators)
    │       → NOT ThreadLocal — safe for reactive pipelines
    │
    ├── Step 2: AuthorizationWebFilter
    │   → /api/products → matches anyExchange().authenticated()
    │   → ReactiveSecurityContextHolder.getContext() → SecurityContext present? YES
    │   → authentication.isAuthenticated() = true → PASS
    │
    └── Step 3: Spring Cloud Gateway routing phase
        → Predicate: Path=/api/products/** → matches product-service route
        → GlobalFilters execute:
            CorrelationIdGatewayFilter (HIGHEST_PRECEDENCE) → adds X-Correlation-Id
            SecurityHeadersGlobalFilter (LOWEST_PRECEDENCE) → adds OWASP headers on response
            RequestRateLimiter (from default-filters) → checks Redis bucket
        → ProxyExchange forwards request to lb://product-service
            → Authorization: Bearer <original-token> forwarded unchanged
            → product-service validates independently (defense in depth)
```

---

### 14.7: Interview Questions — Step 7

**Q: "Spring Cloud Gateway is on WebFlux. Your downstream services use Servlet. How do they communicate?"**

A: They communicate via HTTP — the gateway is just an HTTP client from the downstream service's perspective. The gateway uses `WebClient` (reactive HTTP client) to make requests to downstream services. The downstream services receive a plain HTTP request, handle it on their servlet thread pool, and return an HTTP response. The reactive/servlet boundary is bridged by the HTTP protocol — they never share memory or call each other's objects directly.

**Q: "What is `ReactiveSecurityContextHolder` and why can't you use `SecurityContextHolder` in WebFlux?"**

A: `SecurityContextHolder` stores `SecurityContext` in a `ThreadLocal` — a variable bound to the current thread. In a reactive application, a single request can be processed by MANY different threads (one for JWKS fetch, another for the downstream service call, another for response processing). If you stored auth in `ThreadLocal` on thread A, thread B (which handles the downstream response) would see an empty context. `ReactiveSecurityContextHolder` stores the context in Reactor's `Context` — a key-value map that is propagated THROUGH the reactive pipeline automatically, regardless of which thread executes each operator.

**Q: "Why doesn't the gateway need `@EnableMethodSecurity`?"**

A: The gateway has no `@RestController` classes with `@PreAuthorize` annotations. It only routes. Method security AOP weaving is pointless if there are no annotated methods to intercept. `@EnableWebFluxSecurity` is sufficient — it enables path-based auth rules via `.authorizeExchange()`.

**Q: "What happens to a request with no Authorization header after Step 7?"**

A: Two paths:
1. If the path matches `/api/auth/**` or `/actuator/**` → `permitAll()` → passes through regardless.
2. For any other path → `AuthenticationWebFilter` finds no token → `ReactiveSecurityContextHolder` has no authentication → `AuthorizationWebFilter` checks `.anyExchange().authenticated()` → no principal → `AccessDeniedException` → Spring Security returns 401 with header `WWW-Authenticate: Bearer`.

---

## 15. Phase 8 Step 8 — Rate Limiting (Redis Token Bucket)

### 15.1: What Is Rate Limiting and Why at the Gateway?

Rate limiting controls how many requests a client can make in a time window. Without it:
- A bug in a client (infinite retry loop) kills your service for everyone
- An attacker can brute-force passwords, enumerate products, or flood your system (DoS)
- A high-priority user (human browsing) gets the same resources as a runaway bot

**Why at the gateway, not inside each service:**
- One config change → all 7 downstream services protected simultaneously
- Rejected before the request consumes downstream service CPU, DB connections, or heap
- Shared Redis state across all gateway instances (horizontal scaling works correctly)
- Services can have their own secondary limits (defense in depth), but the gateway is the first line

### 15.2: Token Bucket Algorithm — How Redis Rate Limiting Works

Spring Cloud Gateway uses the **token bucket** algorithm, implemented as a Redis Lua script.

**Conceptual model:**
```
Bucket for user "1":
┌──────────────────────────────────────────────────────────────────┐
│  max capacity: 20 tokens (burstCapacity)                         │
│  current tokens: 15                                              │
│  refill rate: 10 tokens/second (replenishRate)                   │
└──────────────────────────────────────────────────────────────────┘

Request arrives → take 1 token (requestedTokens) → 14 remaining → allowed
Request arrives → take 1 token → 13 remaining → allowed
... 13 more requests ...
Bucket empty (0 tokens) → next request → 429 Too Many Requests

1 second passes → 10 tokens refilled → bucket = 10 → requests allowed again
```

**Configured in `equitycart-config/api-gateway.yml`:**
```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 10    # tokens added per second
            redis-rate-limiter.burstCapacity: 20    # max tokens in bucket
            redis-rate-limiter.requestedTokens: 1   # tokens consumed per request
            key-resolver: "#{@userKeyResolver}"     # SpEL reference to bean
```

`default-filters` means this applies to EVERY route, not just specific ones. So all 7 services behind the gateway are rate-limited with the same parameters.

**`key-resolver: "#{@userKeyResolver}"` — SpEL bean reference:**
The `#{}` syntax is Spring Expression Language. `@userKeyResolver` means "get the bean named `userKeyResolver` from the Spring ApplicationContext." This resolves to the `KeyResolver` bean defined in `RateLimiterConfig.java`.

---

### 15.3: What Gets Stored in Redis

For each unique key (userId or IP), the Redis Lua script creates two keys:

```
Key 1: "request_rate_limiter.{key}.tokens"
    → String value: current token count (e.g., "15")
    → TTL: auto-managed by Lua script (reset on each access)

Key 2: "request_rate_limiter.{key}.timestamp"
    → String value: last refill time as epoch seconds (e.g., "1719870000")
    → TTL: same auto-managed TTL
```

**Example for userId=1:**
```
request_rate_limiter.1.tokens    = "15"
request_rate_limiter.1.timestamp = "1719870000"
```

**Example for anonymous IP:**
```
request_rate_limiter.192.168.1.100.tokens    = "18"
request_rate_limiter.192.168.1.100.timestamp = "1719870000"
```

**Why Redis Lua script (not Java code):**
Without atomic check-and-decrement, a race condition occurs:
```
Thread A reads tokens=1 → allows request
Thread B reads tokens=1 → allows request (same read before A decremented!)
Both decrement → tokens=-1 → both were allowed (only one should have been)
```
A Redis Lua script runs atomically — no other command executes between reads and writes. The ENTIRE check-and-decrement is one atomic operation.

---

### 15.4: RateLimiterConfig.java — Line-by-Line

**File:** `api-gateway/.../config/RateLimiterConfig.java`

```java
@Configuration
public class RateLimiterConfig {

  private static final Logger log = LogManager.getLogger(RateLimiterConfig.class);

  @Bean
  public KeyResolver userKeyResolver() {
    log.info("Registering userKeyResolver: per-userId for authenticated, per-IP for anonymous");
    return exchange ->
        ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication().getPrincipal().toString())
            .defaultIfEmpty(
                Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                    .getAddress()
                    .getHostAddress());
  }
}
```

**`@Bean` returning `KeyResolver`**
- `KeyResolver` is a functional interface from Spring Cloud Gateway: `Mono<String> resolve(ServerWebExchange exchange)`
- The returned `Mono<String>` is the rate-limiting key — one bucket per unique string value
- The `@Bean` method name `userKeyResolver` becomes the bean name — this is what the SpEL `@userKeyResolver` in YAML refers to

**`ReactiveSecurityContextHolder.getContext()`**
- Returns `Mono<SecurityContext>`. If the request has been authenticated by `SecurityWebFilterChain`, this `Mono` emits a `SecurityContext`. If not authenticated (anonymous or bypassed path), this `Mono` is EMPTY.
- Note: this works because `KeyResolver` runs AFTER `SecurityWebFilterChain` has populated the Reactor Context. Filter ordering ensures auth runs first.

**`.map(ctx -> ctx.getAuthentication().getPrincipal().toString())`**
- `getAuthentication()` → the `UsernamePasswordAuthenticationToken` stored by `keycloakReactiveConverter()`
- `.getPrincipal()` → the Long userId (e.g., `1L`)
- `.toString()` → "1" (String, used as the Redis key)
- This means each AUTHENTICATED user gets their own bucket, regardless of IP address. User 1 on mobile + User 1 on desktop = same bucket (correct — we're limiting the user, not the device)

**`.defaultIfEmpty(...)`**
- If `ReactiveSecurityContextHolder.getContext()` emits NOTHING (anonymous request to `/api/auth/**`), fall back to IP address as the rate limiting key
- `exchange.getRequest().getRemoteAddress()` → `InetSocketAddress` (could be null behind proxies without proper forwarding)
- `.getAddress().getHostAddress()` → string like "192.168.1.100"
- Anonymous users are bucketed per IP — one IP address = one bucket

**`Objects.requireNonNull(...)`**
- `getRemoteAddress()` returns null when the connection is from an abstract channel (e.g., Unix domain socket in testing). `requireNonNull` throws `NullPointerException` at the `defaultIfEmpty` resolution point — this results in a 500 rather than leaking a null key into Redis. In production (real TCP connections), this is never null.

---

### 15.5: Internal Flow When a Rate-Limited Request Arrives

```
Authenticated request: GET /api/products, Authorization: Bearer <token>
    │
    ▼
SecurityWebFilterChain validates token → userId=1 stored in ReactiveSecurityContextHolder
    │
    ▼
Spring Cloud Gateway route matching: /api/products → product-service
    │
    ▼
RequestRateLimiter GatewayFilter (from default-filters)
    │
    ├── 1. Calls userKeyResolver.resolve(exchange)
    │       → ReactiveSecurityContextHolder.getContext() → SecurityContext
    │       → getPrincipal().toString() → "1"
    │       → key = "1"
    │
    ├── 2. Calls RedisRateLimiter.isAllowed("product-service", "1")
    │       → Executes Lua script on Redis:
    │           READ request_rate_limiter.1.tokens → "15"
    │           READ request_rate_limiter.1.timestamp → "1719870000"
    │           currentTime = 1719870001
    │           elapsed = 1s → tokensToAdd = 1 * 10 = 10
    │           tokens = min(15 + 10, 20) = 20 (capped at burstCapacity)
    │           tokens_after = 20 - 1 = 19
    │           WRITE request_rate_limiter.1.tokens = "19"
    │           WRITE request_rate_limiter.1.timestamp = "1719870001"
    │           RETURN allowed=true, tokensRemaining=19
    │
    ├── 3a. ALLOWED (tokensRemaining ≥ 0):
    │       → Adds response headers:
    │           X-RateLimit-Remaining: 19
    │           X-RateLimit-Burst-Capacity: 20
    │           X-RateLimit-Replenish-Rate: 10
    │       → Continues to ProxyExchange → forwards to product-service
    │
    └── 3b. REJECTED (tokensRemaining < 0):
            → Returns HTTP 429 Too Many Requests
            → Response headers still include X-RateLimit-* for client awareness
            → Downstream service never receives the request
```

---

### 15.6: What Happens When Redis Is Down

Spring's `RequestRateLimiter` has a `deny-empty-key` configuration (default: `true`). When Redis is unreachable:
- The Lua script execution throws a `RedisConnectionException`
- Spring wraps this in a `RateLimiterResponse` with `allowed=false`
- Request returns 500 Internal Server Error (not 429)

**Production recommendation:**
Configure `deny-empty-key: false` (fail-open) or add a circuit breaker around Redis calls. The trade-off: fail-closed (default) protects the downstream service but causes legitimate user requests to fail. Fail-open allows all traffic when Redis is down — better for availability, worse for protection.

---

### 15.7: Interview Questions — Step 8

**Q: "Explain the token bucket algorithm and why it's better than a fixed window counter."**

A: A fixed window counter resets at a sharp boundary (e.g., every 60 seconds). This allows bursting: 1000 requests at t=59s and another 1000 at t=61s — effectively 2000 in a 2-second span near the boundary. Token bucket refills continuously at a steady rate. Burst is controlled by `burstCapacity` — you can never consume more than that many tokens at once, regardless of timing. This smooths out traffic patterns and is fairer for all clients.

**Q: "Why Redis and not in-memory for rate limiting?"**

A: If the gateway runs as multiple instances (horizontal scaling), each instance has an independent in-memory counter. A user hitting instance A (count=8) then instance B (count=0) effectively gets double the rate limit. Redis is a shared external store — all gateway instances read/write the same bucket. The Lua script's atomicity prevents race conditions between instances.

**Q: "You rate-limit per userId for authenticated requests. What about the login endpoint?"**

A: `/api/auth/**` matches `permitAll()` and also matches `default-filters`. So the `RequestRateLimiter` DOES run on login requests. Since the request is unauthenticated (no token yet), `ReactiveSecurityContextHolder` is empty → `defaultIfEmpty` falls back to IP address. Login requests are rate-limited per IP — which is exactly what you want: prevents brute-force password attacks from a single IP address.

**Q: "What does `key-resolver: '#{@userKeyResolver}'` mean? Could you use a different syntax?"**

A: `#{...}` is Spring Expression Language (SpEL). `@userKeyResolver` dereferences the bean named `userKeyResolver` from the `ApplicationContext`. The `@Bean` method name in `RateLimiterConfig` MUST match the bean name used in SpEL. Alternatively you could annotate the method `@Bean("myKeyResolver")` and reference it as `#{@myKeyResolver}`.

---

## 16. Phase 8 Step 9 — OWASP Security Headers + Critical Bug Fix

### 16.1: What Are Security Response Headers?

Security response headers are instructions the server sends to the browser about how to behave when rendering the response. They don't affect the server at all — they are purely browser-enforced policies. Each header tells the browser: "here's a restriction I want you to apply when displaying this page."

**Why add them at the gateway:**
- One filter → all 7 downstream services get browser protections
- Services don't need to know about browser security — they just do their job
- Changing a header value requires modifying ONE file (SecurityHeadersGlobalFilter.java), not 7 service configs

---

### 16.2: SecurityHeadersGlobalFilter.java — Bug Found and Fixed

**File:** `api-gateway/.../filter/SecurityHeadersGlobalFilter.java`

**The bug:** The class was missing `@Component`. Without it:
- Spring Boot's component scan never discovers the class
- No `SecurityHeadersGlobalFilter` bean is created in the `ApplicationContext`
- Spring Cloud Gateway has no `GlobalFilter` bean to register
- OWASP headers are never added to any response

```java
// BEFORE (broken — Spring ignores this class entirely):
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered { ... }

// AFTER (fixed — Spring creates the bean, Gateway registers it as GlobalFilter):
@Component
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered { ... }
```

**How Spring Cloud Gateway discovers GlobalFilters:**
At startup, `GatewayAutoConfiguration` collects ALL beans from the `ApplicationContext` that implement `GlobalFilter`. It sorts them by `Ordered.getOrder()` and adds them to the gateway's global filter chain. Without `@Component`, the bean doesn't exist → not collected → not added.

This is different from servlet `Filter` registration (which uses `FilterRegistrationBean`). WebFlux GlobalFilters are registered purely by bean presence, with no additional registration step.

---

### 16.3: SecurityHeadersGlobalFilter.java — Line-by-Line

```java
@Component
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered {

  private static final Logger log = LogManager.getLogger(SecurityHeadersGlobalFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain
        .filter(exchange)
        .then(
            Mono.fromRunnable(
                () -> {
                  HttpHeaders headers = exchange.getResponse().getHeaders();
                  headers.set("X-Content-Type-Options", "nosniff");
                  headers.set("X-Frame-Options", "DENY");
                  headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                  headers.set("Content-Security-Policy", "default-src 'self'");
                  headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
                  headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                  log.debug("OWASP security headers added to response");
                }));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
```

**`implements GlobalFilter, Ordered`**
- `GlobalFilter`: marker interface — Spring Cloud Gateway auto-registers all beans of this type
- `Ordered`: interface with `getOrder()` — controls filter execution sequence. Lower number = earlier execution.

**`getOrder() → Ordered.LOWEST_PRECEDENCE` (= Integer.MAX_VALUE = 2147483647)**
- This filter runs LAST among all GlobalFilters. Why last for response headers?
- Earlier filters (security, rate limiter) may set their own headers. Running last ensures this filter's OWASP headers are not overwritten by earlier filters.
- More importantly: response headers must be set BEFORE the response bytes are written to the Netty channel. Running at lowest precedence (after all other logic) but BEFORE the actual wire flush is the correct timing.

**`chain.filter(exchange).then(Mono.fromRunnable(...))`**
This is the key reactive pattern for response post-processing:

```
chain.filter(exchange)
    → executes ALL downstream filters (authentication, rate limiting, routing, proxying)
    → waits for downstream service response to arrive
    → THEN runs our Mono.fromRunnable
    → sets response headers on the already-received-but-not-yet-flushed response
    → returns Mono.empty() to signal completion
    → Netty flushes response bytes to client (headers + body together)
```

**Why NOT set headers BEFORE `chain.filter()`:**
```java
// WRONG — headers may be cleared/overwritten by downstream processing:
exchange.getResponse().getHeaders().set("X-Frame-Options", "DENY");
return chain.filter(exchange);  // downstream may reset headers
```

**`Mono.fromRunnable(() -> { ... })`**
- `fromRunnable` wraps a `Runnable` (no return value, may throw) into a `Mono<Void>`
- The lambda runs synchronously on the Reactor thread when subscribed
- Setting HTTP headers is an O(1) operation — no I/O, no blocking — appropriate to run on the event loop thread

---

### 16.4: Each Header — What Attack It Prevents

```
┌─────────────────────────────────┬────────────────────────────────────────────────────────┐
│ Header                          │ Attack + How Header Prevents It                        │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ X-Content-Type-Options: nosniff │ MIME-type sniffing: browser receives Content-Type:     │
│                                 │ application/json but "sniffs" it as text/html (sees    │
│                                 │ <script> tag in JSON value). Script executes as XSS.   │
│                                 │ "nosniff" forces browser to trust the Content-Type     │
│                                 │ header. If it says JSON, treat it as JSON — no script. │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ X-Frame-Options: DENY           │ Clickjacking: attacker embeds your app in a hidden     │
│                                 │ <iframe>. Victim sees "Play Video" but the hidden      │
│                                 │ iframe shows "Transfer Funds" at same coordinates.     │
│                                 │ "DENY" prevents any page from loading your app in a   │
│                                 │ frame — iframe renders blank instead.                  │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ Strict-Transport-Security       │ MITM (man-in-the-middle): user types "myapp.com"       │
│ max-age=31536000;               │ → browser sends HTTP → attacker on same network        │
│ includeSubDomains               │ intercepts BEFORE redirect to HTTPS → reads/modifies  │
│                                 │ traffic. HSTS tells browser: cache "always use HTTPS   │
│                                 │ for this domain" for 1 year (31536000 seconds).        │
│                                 │ Browser never attempts HTTP for this domain again.     │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ Content-Security-Policy:        │ XSS (Cross-Site Scripting): attacker injects a         │
│ default-src 'self'              │ <script src="https://evil.com/steal-tokens.js">        │
│                                 │ into a stored comment field. CSP tells browser:        │
│                                 │ "only load resources from THIS origin". External       │
│                                 │ scripts are blocked — the injected tag does nothing.   │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ Referrer-Policy:                │ Information leakage: user clicks a link to an          │
│ strict-origin-when-cross-origin │ external site. Browser sends:                          │
│                                 │ Referer: https://myapp.com/portfolio?token=abc123     │
│                                 │ External site's server logs now have your token.       │
│                                 │ "strict-origin-when-cross-origin" sends only           │
│                                 │ https://myapp.com (origin only) for cross-site links. │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ Permissions-Policy:             │ Feature abuse: malicious injected script calls         │
│ camera=(),                      │ navigator.getUserMedia() to access camera without      │
│ microphone=(),                  │ obvious permission prompt. This header instructs the   │
│ geolocation=()                  │ browser to deny permission requests for these APIs     │
│                                 │ outright — no prompt is ever shown to the user.        │
└─────────────────────────────────┴────────────────────────────────────────────────────────┘
```

---

### 16.5: Why @Component Behaves Differently in WebFlux vs Servlet

**JwtAuthenticationFilter (commons, servlet) — also had @Component commented out:**

File: `commons/.../filter/JwtAuthenticationFilter.java`, line 57:
```java
// @Component -> Commenting as this No Longer needed after implementation of Keycloak Security
```

**The servlet problem with @Component on a Filter:**
Spring Boot registers any `@Component`-annotated class that extends `OncePerRequestFilter` as a real servlet filter via `FilterRegistrationBean`. This registration happens OUTSIDE the `SecurityFilterChain`. The filter runs on EVERY request, regardless of which security config is active.

In `mode=oauth2`, `SecurityAutoConfig` is disabled (via `@ConditionalOnProperty`). But `JwtAuthenticationFilter` still ran as a standalone servlet filter because `@Component` triggers `FilterRegistrationBean` unconditionally. Result: two independent validation chains — Spring Security's OAuth2 filter (validates RS256 via JWKS) and the loose `JwtAuthenticationFilter` (validates HS256 with old secret) — both running on every request. Services saw confusing WARN logs.

**The WebFlux difference:**
In WebFlux, `@Component` on a `GlobalFilter` does NOT trigger a separate "filter registration" mechanism. Spring Cloud Gateway's `GatewayAutoConfiguration` collects GlobalFilter beans at startup. If the bean exists → it's in the chain. If not → it's absent. There's no separate `FilterRegistrationBean` concept. So `SecurityHeadersGlobalFilter` with `@Component` is safe — it's only discovered by the Gateway's GlobalFilter collection.

---

### 16.6: Interview Questions — Step 9

**Q: "What is clickjacking and how does X-Frame-Options prevent it?"**

A: Clickjacking overlays a hidden iframe of your legitimate site on top of a fake page. The victim thinks they're clicking an innocent button but their click lands on the hidden iframe — executing an action (transfer funds, change email) on your site using their active session. `X-Frame-Options: DENY` instructs the browser to refuse to render your page inside any `<frame>`, `<iframe>`, or `<object>` element. The browser enforces this before rendering — even if the attacker injects the iframe, the browser displays blank instead of your page.

**Q: "You added Strict-Transport-Security. What's the first-visit problem?"**

A: HSTS only works after the FIRST HTTPS visit. The very first time a user types `myapp.com` (HTTP), the browser doesn't know about HSTS yet. An attacker on the local network can intercept this first HTTP request (MITM the initial redirect). The fix is HSTS Preloading — submit your domain to `hstspreload.org`. Browsers ship with a hardcoded list of domains that must always use HTTPS — even on the absolute first visit. Our current config (`max-age=31536000; includeSubDomains`) is ready for preloading but not submitted (out of scope for dev environment).

**Q: "Your CSP is `default-src 'self'`. What would break in a real frontend app?"**

A: Most single-page apps load resources from CDNs (React from `cdn.jsdelivr.net`, Google Fonts, analytics scripts). `default-src 'self'` blocks all of these. A production CSP would be more specific: `default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; font-src 'self' https://fonts.gstatic.com`. The current restrictive policy is appropriate for an API gateway (no frontend assets served), but would need to be extended for a full web app.

**Q: "Why does `chain.filter(exchange).then(Mono.fromRunnable())` work for setting response headers but not for setting response body?"**

A: HTTP headers and body are flushed together when the `Mono<Void>` chain completes. Setting headers in `.then()` works because headers haven't been written to the Netty channel yet — they're buffered in `HttpHeaders`. The body is also buffered in `DataBuffer` chunks. Both are flushed to the wire after the reactive chain fully completes. However, for STREAMING responses (Server-Sent Events), chunks are flushed individually as they arrive — `.then()` runs after the stream ENDS, missing all intermediate chunks. For streaming, you'd use a `ServerHttpResponseDecorator` to intercept each chunk (like `CorrelationIdGatewayFilter` does).

---

## 17. Phase 8 Step 10 — E2E Integration Testing Strategy

### 17.1: Test Categories and What Each Proves

| Category | Test | Expected | What it validates |
|----------|------|----------|-------------------|
| Missing token | `GET /api/products` (no header) | 401 | `anyExchange().authenticated()` enforced |
| Invalid token | Bearer with bad signature | 401 | JWKS RS256 validation rejects forgeries |
| Expired token | Valid token past `exp` | 401 | NimbusJwtDecoder checks `exp` claim |
| Wrong role | CUSTOMER token → `POST /api/products` | 403 | `@PreAuthorize("hasRole('SELLER')")` enforced |
| Rate limit | 25 rapid requests with same token | 200×20, 429×5 | Redis token bucket depleted |
| Security headers | Any valid GET | 200 + headers present | SecurityHeadersGlobalFilter active |
| Token propagation | Gateway → product-service | Request logged at service | Bearer header forwarded unchanged |
| Full flow | Register → Login → Order → Portfolio | 201 / 200 | Full auth chain end-to-end |

---

### 17.2: Manual E2E Verification (Phase 8 Current State)

**Step 1 — Start infrastructure:**
```bash
cd equitycart/docker
sh start-pets.sh
# Waits for: postgres, redis, keycloak, all healthy
```

**Step 2 — Start services (in order):**
Config-server → discovery-server → all 7 services → api-gateway

**Step 3 — Get Keycloak token:**
```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/equitycart/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=equitycart-gateway" \
  -d "client_secret=<secret-from-realm.json>" \
  -d "username=customer1" \
  -d "password=password" \
  | jq -r .access_token)
```

**Step 4 — Test 401 (no token):**
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/products
# Expected: 401
```

**Step 5 — Test 200 (valid token):**
```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/products
# Expected: 200
```

**Step 6 — Verify security headers present on every response:**
```bash
curl -s -I -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/products \
  | grep -iE "x-content-type|x-frame|strict-transport|content-security|referrer-policy|permissions"
# Expected: all 6 headers present
```

**Step 7 — Test rate limiting (burst 25 requests):**
```bash
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/products
done
# Expected: first 20 = 200, last 5 = 429
```

**Step 8 — Test 403 (wrong role):**
```bash
# customer1 has CUSTOMER role only
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/products \
  -d '{"name":"test"}'
# Expected: 403 (requires SELLER role per @PreAuthorize)
```

**Step 9 — Verify token propagation (gateway → service):**
Check product-service logs after Step 5. Should see:
```
[corrId=xxx] INFO  ProductController - Request received, userId=1
```
The userId was extracted from the same Keycloak token forwarded by the gateway — confirming token relay works.

---

### 17.3: Latent Bug — ServiceTokenProvider in oauth2 Mode (Not Yet Hit)

**Identified but not fixed in this phase:**

`ServiceTokenProviderImpl.generateServiceToken()` creates an HS256 JWT signed with `jwt.secret`. In `mode=oauth2`, services validate via `NimbusJwtDecoder` (JWKS, RS256 only). If a Kafka consumer triggers a Feign call to a `mode=oauth2` service, the fallback `ServiceTokenProvider` token will be rejected with 401.

**When this breaks:**
```
Kafka consumer (e.g., NotificationEventConsumer)
    → FeignAuthorizationInterceptor.apply()
    → No incoming HTTP request → RequestContextHolder is null
    → Fallback: ServiceTokenProvider.generateServiceToken()
    → Creates HS256 JWT with jwt.secret
    → Feign calls product-service (mode=oauth2)
    → NimbusJwtDecoder tries to validate via JWKS (RS256)
    → Algorithm mismatch → 401
```

**Fix path (future):** Replace `ServiceTokenProvider` with Client Credentials flow — request a real Keycloak token using `equitycart-services` client (`grant_type=client_credentials`). Keycloak returns a properly-signed RS256 token. `mode=oauth2` services accept it.

---

## 18. Summary — Phase 8 Complete Security Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                        Phase 8 — Complete Security Stack                              │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  Browser / Postman                                                                   │
│      │  Authorization: Bearer <Keycloak RS256 token>                                 │
│      ▼                                                                               │
│  API Gateway  (port 8080, Netty/WebFlux)                                             │
│      │                                                                               │
│      ├── SecurityWebFilterChain (@EnableWebFluxSecurity)                             │
│      │   ├── AuthenticationWebFilter                                                 │
│      │   │   └── NimbusReactiveJwtDecoder → JWKS cache → RS256 validation           │
│      │   │       keycloakReactiveConverter() → userId Long + roles                  │
│      │   │       → ReactiveSecurityContextHolder (Reactor Context)                  │
│      │   └── AuthorizationWebFilter → /api/auth/** permit, rest authenticated       │
│      │                                                                               │
│      ├── RequestRateLimiter (default-filter, all routes)                             │
│      │   └── Redis token bucket: 10 req/sec per userId (or per IP for anon)         │
│      │       Lua atomic check-and-decrement, X-RateLimit-* response headers         │
│      │                                                                               │
│      ├── SecurityHeadersGlobalFilter (LOWEST_PRECEDENCE)                             │
│      │   └── Adds OWASP headers on every response:                                  │
│      │       X-Content-Type-Options, X-Frame-Options, HSTS, CSP,                   │
│      │       Referrer-Policy, Permissions-Policy                                    │
│      │                                                                               │
│      └── ProxyExchange → downstream service (Authorization header forwarded)        │
│                                                                                      │
│  Downstream Services (port 8081-8087, Tomcat/Servlet)                               │
│      │  Authorization: Bearer <same Keycloak token forwarded by gateway>             │
│      │                                                                               │
│      ├── equitycart.security.mode = oauth2  (product-service)                        │
│      │   └── OAuth2ResourceServerConfig active                                      │
│      │       BearerTokenAuthenticationFilter + NimbusJwtDecoder (servlet)            │
│      │       KeycloakJwtAuthenticationConverter → SecurityContextHolder              │
│      │                                                                               │
│      ├── equitycart.security.mode = custom  (order, portfolio, ledger, market-data,  │
│      │                                       notification)                           │
│      │   └── SecurityAutoConfig active                                              │
│      │       JwtAuthenticationFilter (JJWT HS256) → SecurityContextHolder            │
│      │                                                                               │
│      ├── equitycart.security.mode = not set  (discovery-server, config-server)       │
│      │   └── No SecurityFilterChain → no auth                                       │
│      │                                                                               │
│      ├── FeignAuthorizationInterceptor (all services)                                │
│      │   └── Propagates Authorization header to service-to-service calls            │
│      │                                                                               │
│      └── @PreAuthorize on controller methods                                         │
│          └── Checked after SecurityContext populated (requires @EnableMethodSecurity) │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

**Security properties achieved:**

| Property | Mechanism | Covers |
|----------|-----------|--------|
| Authentication | RS256 JWT via JWKS (oauth2) or HS256 (custom) | All 7 services + gateway |
| Authorization | @PreAuthorize + path matchers | Per-endpoint, per-role |
| Edge enforcement | SecurityWebFilterChain at gateway | Fast-fail before network hop |
| Defense in depth | Each service validates independently | Service-to-service, direct access |
| Token propagation | Authorization header forwarded + FeignAuthorizationInterceptor | HTTP + Feign calls |
| Rate limiting | Redis token bucket per userId/IP | DoS, brute force |
| Browser protection | OWASP headers on every response | XSS, clickjacking, MITM |
| Key security | RS256 asymmetric (only Keycloak holds private key) | Token forgery impossible |
| Secrets management | `${ENV_VAR:default}` in YAML, env vars in Docker compose | Credentials not in git |

---

## 19. Interview Questions — Phase 8 Summary

**Q: "Walk me through what happens from the moment a Keycloak token arrives at the gateway to a controller method executing."**

A: Full trace:
1. Netty receives TCP bytes on port 8080 → `WebFilterChainProxy`
2. `SecurityWebFilterChain`: `AuthenticationWebFilter` extracts Bearer token → `NimbusReactiveJwtDecoder` fetches JWKS cache (or re-fetches from Keycloak), validates RS256 signature, checks `exp` claim → `keycloakReactiveConverter()` extracts `userId` and `roles` claims → `ReactiveSecurityContextHolder` stores `UsernamePasswordAuthenticationToken`
3. `AuthorizationWebFilter`: path doesn't match `permitAll` → checks `isAuthenticated()` → true → passes
4. `RequestRateLimiter`: calls `userKeyResolver` → gets userId from security context → checks Redis bucket → decrements token → allowed
5. `SecurityHeadersGlobalFilter`: registered at LOWEST_PRECEDENCE, runs AFTER response from downstream arrives → sets OWASP headers
6. `ProxyExchange`: forwards request to `lb://product-service` with same `Authorization` header
7. Product-service Tomcat receives request → `BearerTokenAuthenticationFilter` → `NimbusJwtDecoder` (servlet, separate instance) validates again → `KeycloakJwtAuthenticationConverter` → `SecurityContextHolder`
8. `AuthorizationFilter` passes → `DispatcherServlet` → `ProductController` → `@PreAuthorize("hasRole('SELLER')")` checked via AOP → `authentication.getPrincipal()` returns the `Long userId`

**Q: "Why do you validate the token at BOTH the gateway and the downstream service?"**

A: Defense in depth (zero-trust). The gateway is a convenience — it provides fast-fail before consuming downstream resources and centralized auth for all routes. But the services themselves cannot trust the gateway blindly. If an attacker bypasses the gateway (direct port access, network misconfiguration, compromised gateway instance), they would reach the service unauthenticated. Each service validates independently so that compromising the gateway doesn't mean compromising every service.

**Q: "How does the token bucket rate limiter handle the case where the same user makes requests from two devices simultaneously?"**

A: Both requests for the same user ID read/write the SAME Redis bucket (`request_rate_limiter.1.tokens`). The Redis Lua script is atomic — request A and request B cannot both read `tokens=5` and both decrement to 4. One runs first (atomicity guarantee): reads 5, decrements to 4, writes 4. The other runs second: reads 4, decrements to 3, writes 3. The combined rate is correctly enforced at the user level, not the device level. This is intentional — the limit is "user X makes 10 requests per second total", not "each of user X's devices gets 10 requests per second".

**Q: "What is the difference between `@EnableWebFluxSecurity` and `@EnableMethodSecurity`? When would you use both?"**

A:
- `@EnableWebFluxSecurity`: Activates Spring Security's WebFlux infrastructure (WebFilterChainProxy, `SecurityWebFilterChain` bean creation). Required once per WebFlux application. Enables path-based access rules via `.authorizeExchange()`.
- `@EnableMethodSecurity`: Activates AOP-based method-level annotations (`@PreAuthorize`, `@PostAuthorize`, `@Secured`). Works in both servlet AND WebFlux contexts. Needed when you want to annotate individual controller methods, not just paths.

In our gateway: Only `@EnableWebFluxSecurity` — the gateway has no controllers, only routes. No method-level annotations to apply. In our downstream services: Both are needed — `SecurityAutoConfig`/`OAuth2ResourceServerConfig` sets up the filter chain, and `@EnableMethodSecurity` activates `@PreAuthorize` on `ProductController`, `OrderController`, etc.

**Q: "Keycloak's token contains `iss: http://localhost:8180/realms/equitycart` but services in Docker see Keycloak at `http://keycloak:8080`. Why doesn't issuer validation fail?"**

A: We use `jwk-set-uri` (not `issuer-uri`) in `application.yml`. `jwk-set-uri` tells `NimbusJwtDecoder` to fetch JWKS keys from that exact URL and validate the RS256 signature — that's it. No issuer claim comparison is performed. If we used `issuer-uri`, Spring would auto-discover the JWKS URL AND validate that the token's `iss` claim exactly matches the configured `issuer-uri` value. In Docker, services configure `KEYCLOAK_JWKS_URI=http://keycloak:8080/realms/.../certs` but the token's `iss` still says `localhost:8180` — this mismatch would reject every token. In production (where the hostname is consistent), you'd switch to `issuer-uri` for the extra security of issuer validation.

**Q: "Your SecurityHeadersGlobalFilter was missing `@Component` and wasn't working. How would you diagnose this in production without having the code in front of you?"**

A: Two diagnostic steps:
1. Make a request and inspect response headers: `curl -I http://localhost:8080/api/products | grep -i x-frame`. If `X-Frame-Options` is absent, the filter is not running.
2. Check Spring Boot actuator beans endpoint: `GET /actuator/beans` → search for `securityHeadersGlobalFilter`. If the bean is not listed, Spring never created it. The fix is always `@Component` (or `@Bean` in a `@Configuration` class). The actuator beans endpoint is the authoritative list of everything in the `ApplicationContext`.

**Q: "What is the latent ServiceTokenProvider bug you identified in Phase 8?"**

A: `ServiceTokenProviderImpl` generates HS256 tokens for Kafka consumer → Feign call scenarios (no incoming HTTP request to extract a user token from). In `mode=custom` services, this works — they validate with the same HS256 secret. But `product-service` is `mode=oauth2` — it validates via `NimbusJwtDecoder` which only handles RS256/JWKS. An HS256 token sent to `product-service` fails with algorithm mismatch → 401. The correct fix is to replace `ServiceTokenProvider` with Keycloak's Client Credentials flow (`grant_type=client_credentials` using `equitycart-services` client). This yields a real RS256 Keycloak token that `mode=oauth2` services accept.
