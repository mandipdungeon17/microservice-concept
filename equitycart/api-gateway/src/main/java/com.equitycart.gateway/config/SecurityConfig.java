package com.equitycart.gateway.config;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive Spring Security configuration for the API Gateway (Phase 8 Step 7).
 *
 * <p><b>Why Reactive (not Servlet)?</b> Spring Cloud Gateway runs on Netty (non-blocking event
 * loop), not on Tomcat (thread-per-request). This means:
 *
 * <ul>
 *   <li>No {@code HttpServletRequest} — uses {@code ServerWebExchange} (reactive request/response)
 *   <li>No {@code SecurityFilterChain} — uses {@code SecurityWebFilterChain} (reactive filter
 *       chain)
 *   <li>No {@code HttpSecurity} — uses {@code ServerHttpSecurity} (reactive DSL)
 *   <li>No {@code OncePerRequestFilter} — uses {@code WebFilter} (reactive filter interface)
 *   <li>No {@code SecurityContextHolder} (ThreadLocal) — uses {@code ReactiveSecurityContextHolder}
 *       (Reactor Context, propagated through reactive pipeline)
 * </ul>
 *
 * <p><b>What This Replaces:</b> The manual {@code JwtValidationGatewayFilter} (Phase 8 Step 4)
 * which used JJWT library with HS256 symmetric key. That filter could only validate custom tokens.
 * This configuration uses Spring Security's built-in OAuth2 Resource Server which:
 *
 * <ul>
 *   <li>Validates RS256 (asymmetric) tokens from Keycloak via JWKS endpoint
 *   <li>Automatically caches RSA public keys (no per-request JWKS fetch)
 *   <li>Handles key rotation (re-fetches JWKS when unknown {@code kid} encountered)
 *   <li>Validates token expiry with clock skew tolerance (60s default)
 *   <li>Returns standard RFC 6750 error responses (WWW-Authenticate header)
 * </ul>
 *
 * <p><b>Internal Request Flow — Debug Mode:</b>
 *
 * <pre>
 * 1. HTTP request arrives at Netty event loop (port 8080)
 *
 * 2. Spring WebFlux dispatches to WebFilterChain
 *    └─ WebFilterChainProxy (Spring Security's reactive entry point)
 *       └─ SecurityWebFilterChain (THIS bean's configuration)
 *
 * 3. AuthenticationWebFilter (auto-registered by .oauth2ResourceServer().jwt())
 *    ├─ ServerBearerTokenAuthenticationConverter extracts "Bearer xxx" from header
 *    │   → If no header: continues without authentication (anonymous)
 *    │   → If malformed: returns 401 with WWW-Authenticate: Bearer error="invalid_token"
 *    ├─ ReactiveJwtDecoder (NimbusReactiveJwtDecoder, auto-configured by Spring Boot):
 *    │   a) Base64-decodes JWT header → reads {"alg":"RS256","kid":"abc123"}
 *    │   b) Looks up "abc123" in cached JWKS keys (fetched from jwk-set-uri)
 *    │   c) If kid not found in cache: re-fetches JWKS from Keycloak → retries lookup
 *    │   d) Verifies RS256 signature: SHA256(header.payload) == RSA_decrypt(signature, publicKey)
 *    │   e) Validates exp claim (current time < exp + clockSkew)
 *    │   f) Decodes payload → returns Spring Security's Jwt object with all claims
 *    └─ keycloakReactiveConverter() (THIS class's private method):
 *        a) Extracts "userId" claim → Long (backward compat with custom auth)
 *        b) Extracts "roles" claim → List of GrantedAuthority with ROLE_ prefix
 *        c) Returns Mono of UsernamePasswordAuthenticationToken
 *        d) Token stored in ReactiveSecurityContextHolder (Reactor Context)
 *
 * 4. AuthorizationWebFilter (auto-registered by .authorizeExchange())
 *    ├─ /api/auth/** → permitAll → passes regardless of authentication
 *    ├─ /actuator/** → permitAll → passes regardless of authentication
 *    └─ anyExchange → authenticated → requires non-null Authentication in context
 *        → If missing: AccessDeniedException → 401 Unauthorized
 *        → If present: passes to next filter
 *
 * 5. Spring Cloud Gateway routing phase
 *    ├─ Route predicates match request path to service (e.g., /api/products → product-service)
 *    ├─ GlobalFilters execute (CorrelationId, SecurityHeaders, RateLimiter)
 *    ├─ TokenRelay (from default-filters): reads token from ReactiveSecurityContext
 *    │   → Adds Authorization: Bearer header to proxied request
 *    └─ ProxyExchange forwards request to downstream service via load balancer
 *
 * 6. Downstream service receives request with same Bearer token
 *    → Validates independently via its own SecurityFilterChain (defense in depth)
 * </pre>
 *
 * <p><b>Why @EnableWebFluxSecurity (not @EnableMethodSecurity)?</b>
 *
 * <ul>
 *   <li>{@code @EnableWebFluxSecurity}: Activates Spring Security for WebFlux applications. Creates
 *       the reactive security infrastructure (SecurityWebFilterChain,
 *       ReactiveAuthenticationManager).
 *   <li>{@code @EnableMethodSecurity}: Activates @PreAuthorize/@PostAuthorize AOP annotations.
 *       Works in BOTH servlet and reactive contexts. For the gateway, we don't have @PreAuthorize
 *       on controllers (gateway has no controllers — it only routes). So this annotation isn't
 *       needed here.
 * </ul>
 *
 * <p><b>Thread Safety:</b> Reactive chains don't use ThreadLocal. Authentication is stored in
 * Reactor Context (propagated through the reactive pipeline automatically). No thread-pool
 * concerns.
 *
 * @see RateLimiterConfig (extracts userId from ReactiveSecurityContextHolder for per-user limiting)
 * @see com.equitycart.gateway.filter.CorrelationIdGatewayFilter (runs before this in filter order)
 * @see com.equitycart.gateway.filter.SecurityHeadersGlobalFilter (adds OWASP headers to responses)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  private static final Logger log = LogManager.getLogger(SecurityConfig.class);

  /**
   * Builds the reactive security filter chain for the API Gateway.
   *
   * <p>This bean replaces the manual JwtValidationGatewayFilter. Spring Security handles: token
   * extraction, JWKS fetching, RS256 validation, claim parsing, and error responses.
   *
   * @param http the reactive HTTP security builder (injected by Spring)
   * @return configured SecurityWebFilterChain
   */
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

  /**
   * Creates a reactive JWT converter that extracts userId and roles from Keycloak tokens.
   *
   * <p><b>Why Mono wrapper?</b> Reactive security requires {@code Converter<Jwt,
   * Mono<AbstractAuthenticationToken>>} — the conversion itself is synchronous (no I/O), but must
   * be wrapped in Mono to fit the reactive pipeline. This differs from servlet's converter which
   * returns the token directly (no Mono).
   *
   * <p><b>Claim extraction:</b> Identical logic to {@code
   * com.equitycart.commons.security.impl.KeycloakJwtAuthenticationConverter} (servlet version) —
   * extracts "userId" claim as Long principal, "roles" claim as ROLE_-prefixed authorities.
   * Duplicated here because commons is servlet-only (depends on jakarta.servlet) and cannot be used
   * in the WebFlux gateway.
   *
   * @return reactive converter wrapping UsernamePasswordAuthenticationToken in Mono
   */
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
