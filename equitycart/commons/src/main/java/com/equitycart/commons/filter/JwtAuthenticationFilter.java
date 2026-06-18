package com.equitycart.commons.filter;

import com.equitycart.commons.security.api.JwtTokenValidator;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that intercepts HTTP requests, extracts JWT tokens from Authorization headers,
 * validates them, and populates Spring Security's SecurityContext with the authenticated user.
 *
 * <p><b>Flow:</b>
 *
 * <ol>
 *   <li>Read "Authorization: Bearer <token>" header
 *   <li>If missing or malformed, continue without authentication (endpoint-level @PreAuthorize
 *       decides if access allowed)
 *   <li>Validate token via JwtTokenValidator
 *   <li>If valid: extract userId and roles, create UsernamePasswordAuthenticationToken, set
 *       SecurityContext
 *   <li>If invalid: clear SecurityContext (no authentication)
 *   <li>Continue to next filter
 * </ol>
 *
 * <p><b>Principal Type:</b> The authentication token's principal is userId as Long (not
 * UserDetails). Consuming code must cast: {@code (Long) authentication.getPrincipal()}.
 *
 * <p><b>Roles Format:</b> Role names from the token are prefixed with "ROLE_" before being
 * converted to SimpleGrantedAuthority (Spring Security convention). E.g., token role "CUSTOMER"
 * becomes authority "ROLE_CUSTOMER".
 *
 * <p><b>Thread Safety:</b> Extends OncePerRequestFilter which guarantees the filter runs exactly
 * once per request, even if the request is forwarded. SecurityContextHolder is thread-local
 * storage, safe for concurrent requests.
 *
 * <p><b>Limitation:</b> If JwtTokenValidator returns false (invalid token), the SecurityContext is
 * cleared but the request is NOT rejected (401 not sent). The responsibility to reject
 * unauthenticated requests falls to @PreAuthorize on the endpoint or the SecurityFilterChain's
 * authorizeHttpRequests() rules. This allows unauthenticated requests to reach endpoints that
 * explicitly permit anonymous access (e.g., /api/auth/login).
 *
 * @see com.equitycart.commons.security.api.JwtTokenValidator
 * @see com.equitycart.commons.config.SecurityAutoConfig (registers this filter in the filter chain)
 */
@RequiredArgsConstructor
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LogManager.getLogger(JwtAuthenticationFilter.class);

  private final JwtTokenValidator jwtTokenValidator;

  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain)
      throws ServletException, IOException {

    String bearerToken = request.getHeader("Authorization");

    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      String token = bearerToken.substring("Bearer ".length());

      if (jwtTokenValidator.validateToken(token)) {
        try {
          Long userId = jwtTokenValidator.extractUserId(token);
          List<String> roles = jwtTokenValidator.extractRoles(token);

          List<SimpleGrantedAuthority> authorities =
              roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();

          UsernamePasswordAuthenticationToken authenticationToken =
              new UsernamePasswordAuthenticationToken(userId, null, authorities);

          SecurityContextHolder.getContext().setAuthentication(authenticationToken);
          log.info("Authenticated userId={} with roles={}", userId, authorities);
        } catch (Exception e) {
          log.warn("Failed to extract userId/roles from valid token: {}", e.getMessage());
          SecurityContextHolder.clearContext();
        }
      } else {
        log.debug("Invalid JWT token in Authorization header; proceeding without authentication");
        SecurityContextHolder.clearContext();
      }
    } else {
      log.debug("No Bearer token found in Authorization header");
    }
    filterChain.doFilter(request, response);
  }
}
