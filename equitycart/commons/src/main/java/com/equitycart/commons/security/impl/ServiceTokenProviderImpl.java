package com.equitycart.commons.security.impl;

import com.equitycart.commons.security.api.ServiceTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 implementation of {@link ServiceTokenProvider} that generates short-lived JWTs for
 * service-to-service authentication in non-HTTP contexts (Kafka consumers, scheduled tasks).
 *
 * <p><b>Token Structure:</b>
 *
 * <pre>
 * {
 *   "sub": "0",                    // sentinel userId — avoids NumberFormatException in extractUserId()
 *   "roles": ["SERVICE"],          // List&lt;String&gt; — must match extractRoles() casting expectation
 *   "iat": 1718700000,             // issued-at (epoch seconds)
 *   "exp": 1718700060              // expires 60s after issuance
 * }
 * </pre>
 *
 * <p><b>Why subject is "0" (not "SYSTEM"):</b> {@code JwtTokenValidatorImpl.extractUserId()} parses
 * the subject claim as {@code Long.parseLong(subject)}. A non-numeric subject like "SYSTEM" throws
 * {@code NumberFormatException}, breaking authentication. Using "0" — a userId that cannot exist in
 * the database (auto-increment starts at 1) — satisfies the Long parsing contract while remaining
 * distinguishable from real users.
 *
 * <p><b>Why roles is List.of("SERVICE") (not just "SERVICE"):</b> {@code
 * JwtTokenValidatorImpl.extractRoles()} casts the "roles" claim to {@code List<String>}. If the
 * claim is stored as a plain String, the cast throws {@code ClassCastException} at runtime. JJWT
 * serializes {@code List.of("SERVICE")} as a JSON array {@code ["SERVICE"]}, which deserializes
 * back to a List — matching the expected type.
 *
 * <p><b>Why 60-second expiry:</b> Minimizes the window of misuse if a token is intercepted in
 * transit. Each Feign call generates a fresh token (HMAC signing is ~0.1ms), so short expiry has
 * zero performance impact. The token is never cached or reused.
 *
 * <p><b>Secret key source:</b> Read from {@code jwt.secret} in Config Server's application.yml —
 * the same key used by all services for validation. This ensures tokens generated here pass
 * validation in any downstream service's {@code JwtAuthenticationFilter}.
 *
 * @see ServiceTokenProvider
 * @see com.equitycart.commons.feign.FeignAuthorizationInterceptor
 * @see com.equitycart.commons.security.impl.JwtTokenValidatorImpl
 */
@Component
public class ServiceTokenProviderImpl implements ServiceTokenProvider {

  private static final Logger log = LogManager.getLogger(ServiceTokenProviderImpl.class);

  @Value("${jwt.secret}")
  private String secretKey;

  @Override
  public String getServiceToken() {
    log.debug("Generating service-to-service JWT (subject=0, role=SERVICE, ttl=60s)");
    return Jwts.builder()
        .subject("0")
        .claim("roles", List.of("SERVICE"))
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(Instant.now().plusSeconds(60)))
        .signWith(getSigningKey())
        .compact();
  }

  private SecretKey getSigningKey() {
    byte[] decodedKey = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(decodedKey);
  }
}
