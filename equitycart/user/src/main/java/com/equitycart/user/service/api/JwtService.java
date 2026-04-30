package com.equitycart.user.service.api;

import com.equitycart.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.List;

/**
 * Defines the contract for JWT token generation, parsing, and validation. Implementations handle
 * the cryptographic details of signing and verifying tokens.
 */
public interface JwtService {

  /**
   * Generates a short-lived JWT access token containing the user's ID and roles.
   *
   * @param user the authenticated user
   * @param roles the user's role names to embed as claims
   * @return a signed JWT access token string
   */
  String generateAccessToken(User user, List<String> roles);

  /**
   * Generates a random, opaque refresh token (UUID-based).
   *
   * @return a unique refresh token string
   */
  String generateRefreshToken();

  /**
   * Parses and verifies a JWT token, returning all embedded claims.
   *
   * @param token the JWT token string
   * @return the parsed and verified claims
   * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
   */
  Jws<Claims> extractAllClaims(String token);

  /**
   * Extracts the user ID (subject) from the given JWT token.
   *
   * @param token the JWT token string
   * @return the user ID embedded in the token's subject claim
   */
  Long extractUserId(String token);

  /**
   * Extracts the list of role names from the given JWT token.
   *
   * @param token the JWT token string
   * @return list of role name strings from the "roles" claim
   */
  List<String> extractRoles(String token);

  /**
   * Validates whether the given JWT token is well-formed and not expired.
   *
   * @param token the JWT token string
   * @return {@code true} if the token is valid, {@code false} otherwise
   */
  boolean validateToken(String token);
}
