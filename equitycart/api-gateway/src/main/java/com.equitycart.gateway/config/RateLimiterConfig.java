package com.equitycart.gateway.config;

import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

/**
 * Rate limiter key resolution configuration for Spring Cloud Gateway (Phase 8 Step 8).
 *
 * <p><b>What This Does:</b> Defines HOW the rate limiter identifies each unique client. The {@code
 * RequestRateLimiter} gateway filter (configured in api-gateway.yml) uses this {@code KeyResolver}
 * bean to determine the Redis key for each request's token bucket.
 *
 * <p><b>Rate Limiting Strategy:</b>
 *
 * <ul>
 *   <li><b>Authenticated requests:</b> Rate limit per userId (extracted from JWT → SecurityContext)
 *   <li><b>Anonymous requests:</b> Rate limit per IP address (fallback when no auth)
 * </ul>
 *
 * <p><b>Token Bucket Algorithm (configured in api-gateway.yml):</b>
 *
 * <pre>
 * redis-rate-limiter.replenishRate: 10     → 10 tokens added per second
 * redis-rate-limiter.burstCapacity: 20     → maximum tokens in bucket
 * redis-rate-limiter.requestedTokens: 1    → 1 token consumed per request
 *
 * Scenario:
 *   t=0:    bucket=[20/20], request → bucket=[19/20] → 200 OK
 *   t=0.1s: bucket=[20/20] (refilled 1 token, capped at 20)
 *   ...burst 20 requests in 0.5s:
 *   t=0.5s: bucket=[0/20] → next request → 429 Too Many Requests
 *   t=2s:   bucket=[20/20] (refilled at 10/sec for 2s) → requests allowed again
 * </pre>
 *
 * <p><b>Redis Storage — What Gets Created:</b>
 *
 * <pre>
 * Key: "request_rate_limiter.{userId}.tokens"   → current token count (integer)
 * Key: "request_rate_limiter.{userId}.timestamp" → last request time (epoch seconds)
 * TTL: auto-managed by Spring's Lua script (expires when bucket is full/idle)
 * </pre>
 *
 * <p><b>Why Redis (not in-memory)?</b>
 *
 * <ul>
 *   <li>If gateway runs multiple instances (load balanced), in-memory counters are per-instance.
 *       User hits instance A (count=5), then instance B (count=0) — effectively doubling their
 *       limit.
 *   <li>Redis is shared — all gateway instances read/write the same bucket. Atomic via Lua script.
 *   <li>Redis Lua script: runs entirely server-side, no network round-trip between
 *       check-and-decrement. Prevents race condition where two threads read count=1 and both allow
 *       (should only allow one).
 * </ul>
 *
 * <p><b>Internal Execution Flow — Debug Mode:</b>
 *
 * <pre>
 * 1. Request arrives at gateway with Authorization: Bearer token
 * 2. SecurityWebFilterChain validates token → ReactiveSecurityContextHolder populated
 * 3. Route matched → RequestRateLimiter filter executes:
 *    a) Calls userKeyResolver.resolve(exchange) → returns Mono of key string
 *    b) For authenticated: ReactiveSecurityContextHolder.getContext()
 *       → ctx.getAuthentication().getPrincipal() → "1" (userId as string)
 *    c) For anonymous: exchange.getRequest().getRemoteAddress().getHostAddress() → "192.168.1.100"
 * 4. RedisRateLimiter.isAllowed(routeId, key):
 *    a) Executes Lua script on Redis: request_rate_limiter.lua
 *    b) Lua logic: if tokens_left > 0 then decrement and allow else reject
 *    c) Returns RateLimiterResponse(allowed=true/false, tokensRemaining, headers)
 * 5. If allowed: passes to next filter → eventually proxied to downstream service
 *    If rejected: returns 429 Too Many Requests with headers:
 *       X-RateLimit-Remaining: 0
 *       X-RateLimit-Burst-Capacity: 20
 *       X-RateLimit-Replenish-Rate: 10
 * </pre>
 *
 * <p><b>Why .getPrincipal().toString() (not casting to Long)?</b> In reactive context, the
 * principal is stored as Object in the Authentication. Calling toString() on Long returns the
 * numeric string (e.g., "1"). This is safe because our converter always sets Long as principal. If
 * a default converter were used (principal = Jwt), toString() would return a longer string but
 * still work as a unique key.
 *
 * <p><b>Edge Case — What If Redis Is Down?</b> Spring's RequestRateLimiter has configurable
 * behavior: {@code deny-empty-key: true/false}. Default is to DENY (fail-closed) when the key
 * resolver returns empty. If Redis itself is unreachable, the rate limiter throws an exception
 * which results in 500 Internal Server Error. In production, consider circuit-breaker + fallback to
 * fail-open (allow all requests) when Redis is unavailable.
 *
 * @see SecurityConfig (populates ReactiveSecurityContextHolder with userId from JWT)
 */
@Configuration
public class RateLimiterConfig {

  private static final Logger log = LogManager.getLogger(RateLimiterConfig.class);

  /**
   * Creates a KeyResolver that extracts the rate-limiting key from the request.
   *
   * <p>Priority: userId from SecurityContext → IP address fallback.
   *
   * @return KeyResolver bean referenced by SpEL "#{@userKeyResolver}" in gateway YAML
   */
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
