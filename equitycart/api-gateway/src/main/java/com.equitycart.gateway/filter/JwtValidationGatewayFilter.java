package com.equitycart.gateway.filter;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.List;
import javax.crypto.SecretKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive {@link GlobalFilter} that performs JWT pre-validation at the API Gateway edge before
 * routing requests to downstream services.
 *
 * <p><b>Purpose (Edge Security / Fail-Fast):</b> Rejects requests with missing, malformed, or
 * invalid JWT tokens BEFORE they consume network bandwidth and downstream service resources. A
 * request with an expired token is stopped here (1 hop) instead of being routed to order-service (2
 * hops) where it would be rejected anyway by JwtAuthenticationFilter.
 *
 * <p><b>Defense-in-Depth:</b> This filter does NOT strip or modify the Authorization header after
 * validation. The full Bearer token passes through to downstream services, which independently
 * validate it using commons' {@code JwtAuthenticationFilter}. This ensures security even for
 * inter-service Feign calls that bypass the gateway entirely.
 *
 * <p><b>Filter Ordering:</b> Ordered at {@code HIGHEST_PRECEDENCE + 1}, running immediately AFTER
 * {@link CorrelationIdGatewayFilter} (which runs at HIGHEST_PRECEDENCE). This guarantees that a
 * correlation ID is already assigned when this filter logs 401 rejections — enabling traceability
 * of failed authentication attempts across distributed logs.
 *
 * <p><b>Open Paths:</b> Configurable via {@code equitycart.gateway.security.open-paths} in
 * api-gateway.yml. Paths matching these Ant patterns (e.g., /api/auth/**, /actuator/**) skip JWT
 * validation entirely. Uses Spring's {@link AntPathMatcher} for glob-style matching.
 *
 * <p><b>Reactive Model (Why Not Servlet):</b> Spring Cloud Gateway runs on Netty's event loop
 * (non-blocking I/O). It uses {@link ServerWebExchange} and {@code Mono<Void>} instead of
 * HttpServletRequest and FilterChain. The gateway CANNOT use commons' SecurityAutoConfig or
 * JwtAuthenticationFilter because those require the Servlet API (javax.servlet.Filter,
 * HttpServletRequest), which is incompatible with the WebFlux reactive stack.
 *
 * <p><b>Thread Safety:</b> JJWT's {@code parseSignedClaims()} is a CPU-bound HMAC-SHA256
 * computation — safe to run on Netty's event loop threads without blocking. No I/O, no waiting.
 * {@link AntPathMatcher} is stateless and thread-safe. {@code @Value} fields are set once during
 * bean initialization and never modified.
 *
 * <p><b>Replacement Path:</b> Phase 8 Step 7 replaces this with Spring Security's built-in {@code
 * BearerTokenAuthenticationWebFilter} via {@code spring-boot-starter-oauth2-resource-server}. The
 * framework handles JWKS caching, key rotation, issuer validation, and clock skew — all things this
 * manual filter does not address.
 *
 * @see CorrelationIdGatewayFilter (runs before this filter, assigns correlationId)
 *     com.equitycart.commons.filter.JwtAuthenticationFilter (servlet equivalent in downstream
 *     services)
 */
// @Component -> Commenting as this No Longer needed after implementation of Keycloak Security
// Config.
// If both co-exist then both are validated and it throws error like :
// 2026-06-27 05:58:34 [http-nio-8089-exec-5] [] WARN  c.e.c.s.impl.JwtTokenValidatorImpl - JWT
// token validation failed:
// The parsed JWT indicates it was signed with the 'RS256' signature algorithm,
// but the provided javax.crypto.spec.SecretKeySpec key may not be used to verify RS256 signatures.
// Because the specified key reflects a specific and expected algorithm, and the JWT does not
// reflect this algorithm,
// it is likely that the JWT was not expected and therefore should not be trusted.  Another
// possibility is that the
// parser was provided the incorrect signature verification key, but this cannot be assumed for
// security reasons.

public class JwtValidationGatewayFilter implements GlobalFilter, Ordered {

  private static final Logger log = LogManager.getLogger(JwtValidationGatewayFilter.class);

  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  @Value("${jwt.secret}")
  private String secretKey;

  @Value("${equitycart.gateway.security.open-paths}")
  private List<String> openPaths;

  /**
   * Validates JWT token presence and signature for non-open paths.
   *
   * <p>Flow: check open-path → extract header → validate token → proceed or reject 401.
   *
   * @param exchange the current server exchange (request + response)
   * @param chain the gateway filter chain to delegate to
   * @return {@code Mono<Void>} — either completes the chain or writes a 401 response
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String requestPath = exchange.getRequest().getPath().value();

    boolean isOpenPath =
        openPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, requestPath));
    if (isOpenPath) {
      log.debug("Open path {} — skipping JWT validation", requestPath);
      return chain.filter(exchange);
    }

    String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("Missing or invalid Authorization header for path: {}", requestPath);
      return onUnauthorized(
          exchange, "Missing or invalid Authorization header for path: " + requestPath);
    }

    String token = authHeader.substring("Bearer ".length());
    try {
      Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
      log.debug("JWT pre-validation passed for path: {}", requestPath);
    } catch (JwtException e) {
      log.warn("JWT validation failed for path {}: {}", requestPath, e.getMessage());
      return onUnauthorized(exchange, e.getMessage());
    }
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  /**
   * Decodes the Base64-encoded shared secret into an HMAC-SHA256 signing key.
   *
   * @return SecretKey for JJWT signature verification
   */
  private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
  }

  /**
   * Writes a 401 JSON error response and short-circuits the filter chain.
   *
   * <p>In reactive gateway, returning {@code Mono<Void>} from {@code writeWith()} completes the
   * response — downstream filters and route handlers are never invoked.
   *
   * @param exchange the server exchange to write the response to
   * @param reason human-readable rejection reason (included in response body)
   * @return Mono that completes after writing the 401 response
   */
  private Mono<Void> onUnauthorized(ServerWebExchange exchange, String reason) {
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] bytes = ("{\"error\":\"Unauthorized\",\"message\":\"" + reason + "\"}").getBytes();
    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
