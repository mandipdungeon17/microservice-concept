package com.equitycart.commons.security.impl;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Spring Security converter that transforms Keycloak-issued JWT tokens into Spring's authentication
 * model, maintaining backward compatibility with the custom JWT implementation.
 *
 * <p><b>Context (Phase 8 Step 6):</b> This converter is the bridge between two worlds:
 *
 * <ul>
 *   <li><b>Keycloak's world:</b> Provides a standard OIDC/OAuth2 JWT with RSA-256 signature,
 *       specific claim names (sub = Keycloak UUID, roles = list in token, userId = custom
 *       attribute)
 *   <li><b>EquityCart's world:</b> Expects authentication principal to be userId as Long, with
 *       roles extracted for @PreAuthorize checks
 * </ul>
 *
 * <p>Without this converter, Spring Security would use its default converter, which returns {@code
 * Jwt} as principal — incompatible with controllers expecting {@code (Long)
 * authentication.getPrincipal()}.
 *
 * <p><b>Claim Structure — What Keycloak Sends:</b>
 *
 * <p>Keycloak token after RS256 validation (by NimbusJwtDecoder) and conversion (by this class):
 *
 * <pre>
 * {
 *   "sub": "550e8400-e29b-41d4-a716-446655440000",  // Keycloak user UUID (we don't use this)
 *   "email": "customer1@test.com",
 *   "name": "Customer One",
 *   "roles": ["CUSTOMER", "SELLER"],        // Protocol mapper: realm-access.roles → roles claim
 *   "userId": "1",                          // Protocol mapper: custom attribute "userId" → "userId" claim
 *   "iss": "http://keycloak:8080/realms/equitycart",  // Issuer (validated by NimbusJwtDecoder)
 *   "aud": "account",
 *   "iat": 1718700000,
 *   "exp": 1718700900,
 *   "jti": "550e8400-e29b-41d4-a716-446655440111"     // JWT ID (unique per token)
 * }
 * </pre>
 *
 * <p><b>Conversion Process — Step-by-Step:</b>
 *
 * <ol>
 *   <li><b>Extract userId</b>: jwt.getClaim("userId") returns Object (could be null, String, Long)
 *       <ol>
 *         <li>If not null: {@code Long.parseLong(userObj.toString())} (handles String "1" → Long 1)
 *         <li>If null: default to 0L (sentinel value, avoids NPE in downstream code)
 *       </ol>
 *   <li><b>Extract roles</b>: jwt.getClaim("roles") returns Object (could be null, List, or wrong
 *       type)
 *       <ol>
 *         <li>Type cast to {@code List<String>} (JwtAuthenticationConverter already validated token
 *             structure, so cast is safe)
 *         <li>If null: default to empty list (user has no roles → no authorities → access denied
 *             by @PreAuthorize on restricted endpoints)
 *       </ol>
 *   <li><b>Convert roles to authorities</b>: Stream each role, prefix with "ROLE_", create
 *       SimpleGrantedAuthority
 *       <ol>
 *         <li>Input: ["CUSTOMER", "SELLER"]
 *         <li>Output: [SimpleGrantedAuthority("ROLE_CUSTOMER"),
 *             SimpleGrantedAuthority("ROLE_SELLER")]
 *         <li>Why "ROLE_" prefix? Spring Security convention: @PreAuthorize("hasRole('CUSTOMER')")
 *             expands to hasAnyAuthority("ROLE_CUSTOMER") internally
 *       </ol>
 *   <li><b>Create authentication token</b>: UsernamePasswordAuthenticationToken(userId, null,
 *       authorities)
 *       <ol>
 *         <li>Principal (1st arg): userId as Long — allows {@code (Long)
 *             authentication.getPrincipal()} in controllers
 *         <li>Credentials (2nd arg): null — we don't store the token itself (NimbusJwtDecoder
 *             already validated it)
 *         <li>Authorities (3rd arg): list of GrantedAuthority — enables @PreAuthorize evaluation
 *       </ol>
 *   <li><b>Return token</b>: Spring Security stores this in SecurityContextHolder for the request
 * </ol>
 *
 * <p><b>Why Not Just Use Spring's Default Converter?</b>
 *
 * <p>Spring Security's default converter (JwtBearerTokenAuthenticationConverter) returns {@code new
 * BearerTokenAuthentication(jwt, authorities, ...)}, where principal IS the Jwt object:
 *
 * <pre>
 * authentication.getPrincipal() → Jwt (has getClaim(), getIssuedAt(), getExpires() methods)
 * (Long) authentication.getPrincipal() → ClassCastException!
 *               </pre>
 *
 * <p>Our custom converter ensures backward compatibility:
 *
 * <pre>
 * authentication.getPrincipal() → 1L (Long)
 * (Long) authentication.getPrincipal() → 1L (works!)
 *               </pre>
 *
 * <p><b>Future Consideration — Sub vs UserId:</b> We extract the {@code userId} custom claim
 * instead of parsing {@code sub}. This works because:
 *
 * <ul>
 *   <li>Keycloak realm includes a protocol mapper: "userId-mapper" → "userId" claim (configured in
 *       equitycart-realm.json)
 *   <li>Alternative (more complex): could parse {@code sub} UUID → look up user in our DB → extract
 *       userId. Not done here to keep the converter stateless.
 * </ul>
 *
 * <p><b>Thread Safety:</b> Stateless — no field state, no shared mutable objects. Spring invokes
 * convert() on each request's thread independently.
 *
 * @author Phase 8 Step 6 implementation
 */
@Component
public class KeycloakJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  /**
   * Converts Spring Security's validated Jwt (from NimbusJwtDecoder) into an
   * UsernamePasswordAuthenticationToken for backward compatibility with custom JWT implementation.
   *
   * @param jwt the validated JWT token (already signature-verified by NimbusJwtDecoder, guaranteed
   *     non-null)
   * @return UsernamePasswordAuthenticationToken with principal=userId, authorities from roles claim
   */
  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    // Extract userId claim (custom attribute set in Keycloak protocol mapper)
    // Type: may be String "1" or null if not set, we handle both
    Object userObj = jwt.getClaim("userId");
    // Extract roles claim (standard, populated by Keycloak protocol mapper from
    // realm_access.roles)
    // Type: List<String> for valid tokens, null if not present
    Object roleObj = jwt.getClaim("roles");

    // Convert userId to Long, default to 0L if not present (sentinel value)
    // Long.parseLong(userObj.toString()) handles String "1" → Long 1L correctly
    Long userId = userObj != null ? Long.parseLong(userObj.toString()) : 0L;
    // Cast roles to List, default to empty list if not present
    // At this point, token structure is guaranteed valid (NimbusJwtDecoder already
    // validated)
    List<String> roles = roleObj != null ? (List<String>) roleObj : List.of();

    // Convert role names to Spring GrantedAuthority with ROLE_ prefix
    // Example: "CUSTOMER" → SimpleGrantedAuthority("ROLE_CUSTOMER")
    // This prefix is required for @PreAuthorize("hasRole('CUSTOMER')") to work
    List<SimpleGrantedAuthority> authorities =
        roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();

    // Return Spring's authentication token
    // Principal (1st arg) = userId: enables (Long) authentication.getPrincipal() in
    // controllers
    // Credentials (2nd arg) = null: we don't store token, it was already validated
    // Authorities (3rd arg) = roles: enables @PreAuthorize evaluation
    return new UsernamePasswordAuthenticationToken(userId, null, authorities);
  }
}
