package com.equitycart.commons.security.api;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.List;

/**
 * Contract for JWT token validation and claim extraction.
 *
 * <p>Implementations of this interface handle the mechanics of verifying JWT token signatures and
 * extracting claims (userId, roles) from valid tokens. This interface separates the validation
 * concern from the authentication filter, enabling different implementations (HMAC-based,
 * JWKS-based with Keycloak, etc.) without changing consuming code.
 *
 * <p><b>Pattern</b>: Strategy Pattern — multiple implementations (custom JWT, OAuth2 Resource
 * Server) can be swapped at runtime via @ConditionalOnProperty.
 *
 * @see com.equitycart.commons.security.impl.JwtTokenValidatorImpl
 */
public interface JwtTokenValidator {

  /**
   * Parses and verifies a JWT token, returning the signed claims if valid.
   *
   * @param token the JWT token string (without "Bearer " prefix)
   * @return a Jws object containing verified claims
   * @throws io.jsonwebtoken.JwtException if signature verification fails or token is expired
   */
  Jws<Claims> extractAllClaims(String token);

  /**
   * Validates a JWT token without throwing exceptions.
   *
   * @param token the JWT token string
   * @return true if the token is valid (signature verified, not expired), false otherwise
   */
  boolean validateToken(String token);

  /**
   * Extracts the userId from a valid token's "sub" (subject) claim.
   *
   * @param token the JWT token string
   * @return the userId as Long
   * @throws NumberFormatException if the "sub" claim cannot be parsed as a Long
   */
  Long extractUserId(String token);

  /**
   * Extracts the roles list from a valid token's "roles" claim.
   *
   * @param token the JWT token string
   * @return an immutable list of role names (e.g., ["CUSTOMER", "SELLER"]). May be empty but never
   *     null.
   */
  List<String> extractRoles(String token);
}
