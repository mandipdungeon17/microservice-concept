package com.equitycart.commons.security.impl;

import com.equitycart.commons.security.api.JwtTokenValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.List;
import javax.crypto.SecretKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 JWT token validator implementation using JJWT library.
 *
 * <p><b>Algorithm:</b> HS256 (HMAC with SHA-256). Tokens are signed and verified using a shared
 * secret key. This approach is suitable for internal service-to-service communication where all
 * parties trust the secret.
 *
 * <p><b>Token Structure:</b> Expects tokens with claims:
 *
 * <ul>
 *   <li>"sub" (subject) = userId as String (parsed to Long)
 *   <li>"roles" = List of role names
 *   <li>"exp" (expiration) = auto-validated by JJWT parser
 * </ul>
 *
 * <p><b>Limitation:</b> Uses symmetric cryptography (shared secret). If any service is compromised,
 * an attacker can forge valid tokens. For production, consider asymmetric keys (RS256 with Keycloak
 * JWKS) in Phase 8 Step 6.
 *
 * <p><b>Thread Safety:</b> SecretKey objects are immutable; safe for concurrent access.
 *
 * @see com.equitycart.commons.security.api.JwtTokenValidator
 */
@Component
public class JwtTokenValidatorImpl implements JwtTokenValidator {

  private static final Logger log = LogManager.getLogger(JwtTokenValidatorImpl.class);

  @Value("${jwt.secret}")
  private String secretKey;

  /**
   * Parses and verifies the JWT token signature using the configured secret key.
   *
   * @param token the JWT string
   * @return Jws object with verified claims
   * @throws JwtException if signature verification fails or token is malformed
   */
  @Override
  public Jws<Claims> extractAllClaims(String token) {
    return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
  }

  /**
   * Validates a JWT token without throwing exceptions (swallows all JwtException types).
   *
   * <p>This method is defensive: it catches signature failures, expiration, malformed tokens, etc.,
   * and returns false. Calling code can use this for conditional logic without try-catch.
   *
   * @param token the JWT string
   * @return true if valid, false otherwise
   */
  @Override
  public boolean validateToken(String token) {
    try {
      extractAllClaims(token);
      log.debug("JWT token validation successful");
      return true;
    } catch (JwtException e) {
      log.warn("JWT token validation failed: {}", e.getMessage());
      return false;
    } catch (IllegalArgumentException e) {
      log.warn("JWT token is malformed or empty: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Extracts userId from the token's "sub" claim and converts to Long.
   *
   * @param token the JWT string
   * @return the userId as Long
   * @throws NumberFormatException if sub claim is not a valid Long
   * @throws JwtException if token is invalid (validation should happen before calling this)
   */
  @Override
  public Long extractUserId(String token) {
    String subject = extractAllClaims(token).getPayload().getSubject();
    Long userId = Long.valueOf(subject);
    log.debug("Extracted userId {} from token", userId);
    return userId;
  }

  /**
   * Extracts the roles list from the token's "roles" claim.
   *
   * @param token the JWT string
   * @return list of role names (e.g., ["CUSTOMER", "SELLER"]), or empty list if claim absent
   * @throws JwtException if token is invalid
   */
  @Override
  public List<String> extractRoles(String token) {
    @SuppressWarnings("unchecked")
    List<String> roles = extractAllClaims(token).getPayload().get("roles", List.class);
    if (roles == null || roles.isEmpty()) {
      log.debug("No roles found in token; user has no permissions");
      return List.of();
    }
    log.debug("Extracted roles {} from token", roles);
    return roles;
  }

  /**
   * Decodes the Base64-encoded shared secret and creates an HMAC-SHA256 signing key.
   *
   * <p><b>Note:</b> The secret is expected to be Base64-encoded (to safely embed in YAML/env vars).
   * JJWT requires at least 256 bits (32 bytes) for HMAC-SHA256.
   *
   * @return SecretKey suitable for JJWT verification
   */
  private SecretKey getSigningKey() {
    byte[] decodedKey = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(decodedKey);
  }
}
