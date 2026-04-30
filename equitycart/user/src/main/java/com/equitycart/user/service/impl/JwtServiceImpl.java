package com.equitycart.user.service.impl;

import com.equitycart.user.entity.User;
import com.equitycart.user.service.api.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link JwtService} using the JJWT library. Handles signing, parsing, and
 * validation of JWT access tokens and generation of opaque refresh tokens.
 */
@Service
public class JwtServiceImpl implements JwtService {

  private static final Logger log = LogManager.getLogger(JwtServiceImpl.class);

  @Value("${jwt.secret}")
  private String secretKey;

  @Value("${jwt.access-token-expiry}")
  private long accessTokenExpiry;

  @Value("${jwt.refresh-token-expiry}")
  private long refreshTokenExpiry;

  /** {@inheritDoc} */
  @Override
  public String generateAccessToken(User user, List<String> roles) {
    log.debug("Generating access token for user id: {}", user.getId());
    return Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("roles", roles)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(Instant.now().plusMillis(accessTokenExpiry)))
        .signWith(getSigningKey())
        .compact();
  }

  /** {@inheritDoc} */
  @Override
  public String generateRefreshToken() {
    log.debug("Generating opaque refresh token");
    return UUID.randomUUID().toString();
  }

  /** {@inheritDoc} */
  @Override
  public Jws<Claims> extractAllClaims(String token) {
    return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
  }

  /** {@inheritDoc} */
  @Override
  public Long extractUserId(String token) {
    return Long.valueOf(extractAllClaims(token).getPayload().getSubject());
  }

  /** {@inheritDoc} */
  @Override
  public List<String> extractRoles(String token) {
    return extractAllClaims(token).getPayload().get("roles", List.class);
  }

  /** {@inheritDoc} */
  @Override
  public boolean validateToken(String token) {
    try {
      extractAllClaims(token);
      log.debug("JWT token validated successfully");
      return true;
    } catch (Exception e) {
      log.warn("JWT token validation failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Derives the HMAC-SHA signing key from the Base64-encoded secret.
   *
   * @return the signing key used for JWT operations
   */
  private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
  }
}
