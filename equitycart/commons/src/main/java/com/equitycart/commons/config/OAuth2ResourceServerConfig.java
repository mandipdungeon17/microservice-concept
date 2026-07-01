package com.equitycart.commons.config;

import com.equitycart.commons.security.impl.KeycloakJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security OAuth2 Resource Server configuration for JWT validation via Keycloak JWKS
 * endpoint.
 *
 * <p><b>Context (Phase 8 Step 6):</b> Replaces the custom JwtAuthenticationFilter (Step 1-2) with
 * Spring's production-grade OAuth2 Resource Server. This provides automatic JWKS fetching, RSA key
 * caching, key rotation, issuer validation, audience checks, and clock skew tolerance — all of
 * which the custom implementation lacked.
 *
 * <p><b>Conditional Activation:</b> Only loaded when {@code equitycart.security.mode=oauth2}. This
 * allows per-service migration: product-service can switch while others stay on custom mode.
 * Services without explicit mode setting don't load ANY security config (e.g., discovery-server,
 * config-server).
 *
 * <p><b>How It Works Internally — Step-by-Step:</b>
 *
 * <ol>
 *   <li><b>Request arrives</b> with Authorization: Bearer &lt;token&gt; header
 *   <li><b>BearerTokenAuthenticationFilter</b> (added automatically by .oauth2ResourceServer())
 *       extracts the token substring
 *   <li><b>NimbusJwtDecoder</b> (autowired and configured by Spring) is invoked with the token:
 *       <ol>
 *         <li>Splits token into 3 parts (header.payload.signature)
 *         <li>Decodes header (Base64 JSON) → reads {@code "alg": "RS256", "kid": "..."}
 *         <li>Uses {@code kid} (key ID) to find matching key in cached JWKS
 *         <li>Validates signature using the RSA public key
 *         <li>Decodes payload (Base64 JSON) → claims
 *         <li>Validates {@code exp} (expiration), {@code iss} (issuer from jwk-set-uri property),
 *             {@code aud} (audience if configured)
 *         <li>Returns a Spring {@code Jwt} object with all claims available
 *       </ol>
 *   <li><b>KeycloakJwtAuthenticationConverter</b> (our custom converter) receives the {@code Jwt}:
 *       <ol>
 *         <li>Extracts {@code userId} claim → principal (Long, for backward compatibility)
 *         <li>Extracts {@code roles} claim → list of authorities with "ROLE_" prefix
 *         <li>Returns {@code UsernamePasswordAuthenticationToken(userId, null, authorities)}
 *       </ol>
 *   <li><b>SecurityContext</b> is populated with the token
 *   <li><b>AuthorizationFilter</b> checks if the request matches a matcher rule:
 *       <ol>
 *         <li>/api/auth/** → permitAll (no principal needed)
 *         <li>/actuator/** → permitAll
 *         <li>anyRequest → authenticated (principal must exist)
 *       </ol>
 *   <li><b>If authorized</b> → passes to controller. <b>If denied</b> → ExceptionTranslationFilter
 *       catches and returns 403 Forbidden
 * </ol>
 *
 * <p><b>JWKS Endpoint — What It Provides:</b> Keycloak's JWKS endpoint ({@code
 * /realms/{realm}/protocol/openid-connect/certs}) returns a JSON Web Key Set with the current RSA
 * public key(s):
 *
 * <pre>
 * {
 *   "keys": [
 *     {
 *       "kty": "RSA",
 *       "kid": "1234567890abcdef",  // Key ID — matches JWT header
 *       "alg": "RS256",
 *       "use": "sig",
 *       "n": "xjlCRBqkQHsK7t8vVK9+8l...",  // RSA public exponent n
 *       "e": "AQAB"                 // RSA public exponent e
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>NimbusJwtDecoder caches these keys (TTL configurable via
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri) and automatically refreshes when Keycloak
 * rotates keys (generates new key pair).
 *
 * <p><b>Why This Is Better Than Custom HS256:</b>
 *
 * <ul>
 *   <li><b>Asymmetric signing:</b> Only Keycloak holds the RSA private key. Services never see it.
 *       If a service is compromised, the attacker cannot forge tokens.
 *   <li><b>Token revocation:</b> JWKS endpoint can be updated to remove revoked keys (though this
 *       is rarely used in practice — revocation is typically handled at the Keycloak admin API
 *       level).
 *   <li><b>Key rotation:</b> Keycloak periodically generates new RSA key pairs. Old keys remain in
 *       JWKS for a grace period (allowing in-flight tokens to still validate). Services
 *       auto-discover via cache refresh.
 *   <li><b>Clock skew:</b> Validated claim times (exp, iat, nbf) are checked with configurable
 *       clock skew tolerance (60s default) — prevents failures due to minor time differences
 *       between Keycloak and services.
 *   <li><b>Issuer validation:</b> {@code iss} claim can be validated if using issuer-uri (not just
 *       jwk-set-uri).
 * </ul>
 *
 * <p><b>Configuration Sources (in order of precedence):</b>
 *
 * <ol>
 *   <li>Environment variable: {@code KEYCLOAK_JWKS_URI} (set in Docker compose for Docker
 *       deployments)
 *   <li>YAML config from Config Server: {@code equitycart-config/application.yml}
 *   <li>Fallback default: {@code
 *       http://localhost:8180/realms/equitycart/protocol/openid-connect/certs} (for local dev)
 * </ol>
 *
 * <p><b>Thread Safety:</b> NimbusJwtDecoder and its internal JWKS cache are thread-safe. The
 * SecurityFilterChain is built once during Spring initialization (synchronized); at runtime, each
 * request thread independently validates its token without blocking others.
 *
 * <p><b>Migration Path:</b> To switch a service from custom (mode=custom) to oauth2:
 *
 * <pre>
 * 1. In equitycart-config/{service}.yml, set: equitycart.security.mode: oauth2
 * 2. SecurityAutoConfig does NOT load (condition fails)
 * 3. This OAuth2ResourceServerConfig DOES load (condition matches)
 * 4. Ensure KEYCLOAK_JWKS_URI is accessible (or use issuer-uri for auto-discovery)
 * 5. Test: send a Keycloak RS256 token (from /realms/{realm}/protocol/openid-connect/token)
 * </pre>
 *
 * @see KeycloakJwtAuthenticationConverter (converts Spring Jwt →
 *     UsernamePasswordAuthenticationToken)
 * @see
 *     org.springframework.security.oauth2.server.resource.authentication.JwtBearerTokenAuthenticationConverter
 *     (Spring's default converter, less flexible)
 */
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@ConditionalOnProperty(name = "equitycart.security.mode", havingValue = "oauth2")
public class OAuth2ResourceServerConfig {

  private static final Logger log = LogManager.getLogger(OAuth2ResourceServerConfig.class);

  private final KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;

  /**
   * Configures the Spring Security filter chain for OAuth2 Resource Server mode.
   *
   * <p><b>What This Does:</b> Registers Spring's built-in BearerTokenAuthenticationFilter (via
   * .oauth2ResourceServer()) which validates JWT tokens using NimbusJwtDecoder (connects to
   * Keycloak JWKS endpoint). Disables CSRF (JWT is immune), enables stateless session (no
   * server-side session storage), and sets authorization rules.
   *
   * <p><b>Flow for Each Request:</b>
   *
   * <pre>
   * /api/auth/login (no token needed)
   *   ├─ BearerTokenAuthenticationFilter: no header → skips validation → anon principal
   *   ├─ AuthorizationFilter: path matches /api/auth/** → permitAll → success
   *   └─ Controller processes request
   *
   * /api/products (token required)
   *   ├─ BearerTokenAuthenticationFilter: extracts bearer token
   *   ├─ NimbusJwtDecoder: validates RS256 signature via JWKS → returns Jwt
   *   ├─ KeycloakJwtAuthenticationConverter: converts Jwt → UsernamePasswordAuthenticationToken
   *   ├─ SecurityContextHolder: stores authentication (userId, roles)
   *   ├─ AuthorizationFilter: path matches anyRequest → authenticated → principal exists → success
   *   └─ Controller processes request, can access userId via SecurityContext
   * </pre>
   *
   * @param httpSecurity builder provided by Spring; we configure it and call .build()
   * @return configured SecurityFilterChain bean
   * @throws Exception if configuration fails (deferred by Spring to startup time)
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
    log.info("Enabling OAuth2 Resource Server security (Keycloak JWKS validation)");
    return httpSecurity
        // Step 1: Enable OAuth2 Resource Server
        // Automatically registers BearerTokenAuthenticationFilter and NimbusJwtDecoder
        // NimbusJwtDecoder is auto-configured by Spring Boot based on
        // spring.security.oauth2.resourceserver.jwt.jwk-set-uri from application.yml
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwtConfigurer ->
                        // Step 2: Wire our custom converter
                        // Converts Spring's Jwt object → UsernamePasswordAuthenticationToken
                        // This is called AFTER token validation, only if signature is valid
                        jwtConfigurer.jwtAuthenticationConverter(
                            keycloakJwtAuthenticationConverter)))
        // Step 3: Disable CSRF
        // CSRF (Cross-Site Request Forgery) is a browser-cookie vulnerability
        // JWT tokens are sent in Authorization header (not cookie), so CSRF doesn't
        // apply
        .csrf(AbstractHttpConfigurer::disable)
        // Step 4: Stateless session management
        // STATELESS: Spring does not create HttpSession, each request validated
        // independently
        // Reduces memory overhead and makes the service horizontally scalable
        .sessionManagement(se -> se.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Step 5: Authorization rules
        // These rules are evaluated AFTER authentication (after token is validated and
        // SecurityContext is populated)
        .authorizeHttpRequests(
            auth ->
                auth
                    // Open paths: no token needed
                    .requestMatchers("/api/auth/**", "/actuator/**")
                    .permitAll()
                    // All other paths: token must be present and valid
                    .anyRequest()
                    .authenticated())
        .build();
  }
}
