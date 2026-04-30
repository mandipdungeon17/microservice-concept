package com.equitycart.user.filter;

import com.equitycart.user.service.api.JwtService;
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
 * Spring Security filter that intercepts every HTTP request to extract and validate a JWT from the
 * {@code Authorization} header. If the token is valid, the filter populates the {@link
 * SecurityContextHolder} with the authenticated user's ID and granted authorities.
 */
@RequiredArgsConstructor
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  private static final Logger log = LogManager.getLogger(JwtAuthFilter.class);

  private final JwtService jwtService;

  /**
   * Extracts the JWT from the Authorization header, validates it, and sets the security context if
   * the token is valid.
   *
   * @param request the incoming HTTP request
   * @param response the HTTP response
   * @param filterChain the filter chain to continue processing
   * @throws ServletException if a servlet error occurs
   * @throws IOException if an I/O error occurs
   */
  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain)
      throws ServletException, IOException {

    log.debug("Processing JWT authentication for request: {}", request.getRequestURI());
    String bearerToken = request.getHeader("Authorization");

    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      String token = bearerToken.substring("Bearer ".length());
      if (jwtService.validateToken(token)) {
        Long userId = jwtService.extractUserId(token);
        List<String> roles = jwtService.extractRoles(token);

        List<SimpleGrantedAuthority> authorities =
            roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();

        UsernamePasswordAuthenticationToken authenticationToken =
            new UsernamePasswordAuthenticationToken(userId, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        log.debug("JWT authenticated user id: {} with roles: {}", userId, roles);
      } else {
        log.warn("Invalid JWT token for request: {}", request.getRequestURI());
      }
    }
    filterChain.doFilter(request, response);
  }
}
