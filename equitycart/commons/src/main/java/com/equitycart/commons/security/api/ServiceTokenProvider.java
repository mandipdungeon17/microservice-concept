package com.equitycart.commons.security.api;

/**
 * Generates short-lived JWT tokens for service-to-service authentication in non-HTTP contexts.
 *
 * <p><b>Problem Solved:</b> After Phase 8 Step 2 enabled JWT enforcement on all services,
 * inter-service Feign calls originating from non-HTTP threads (Kafka consumers, {@code @Scheduled}
 * methods, {@code @Async} tasks) fail with 401 because {@link
 * org.springframework.web.context.request.RequestContextHolder} returns null — there is no incoming
 * HTTP request to propagate an Authorization header from.
 *
 * <p><b>Solution:</b> This provider generates a machine-identity JWT that downstream services
 * accept as a valid authenticated principal. The token identifies the caller as a service (not a
 * human user), enabling downstream {@code .anyRequest().authenticated()} rules to pass without
 * requiring a user-initiated request context.
 *
 * <p><b>Token Characteristics:</b>
 *
 * <ul>
 *   <li><b>Subject:</b> "0" — a sentinel userId that cannot collide with real user IDs (which start
 *       at 1). Chosen to maintain backward compatibility with {@code extractUserId()} returning
 *       Long.
 *   <li><b>Roles:</b> {@code List.of("SERVICE")} — a dedicated role for service-to-service calls.
 *       Must be a List (not a plain String) because {@code JwtTokenValidatorImpl.extractRoles()}
 *       casts the claim to {@code List<String>}.
 *   <li><b>Expiry:</b> 60 seconds — short-lived to limit blast radius if intercepted; each Feign
 *       call generates a fresh token.
 *   <li><b>Signing:</b> Same HMAC-SHA256 secret as user tokens (shared via Config Server's {@code
 *       jwt.secret}).
 * </ul>
 *
 * <p><b>Design Decisions:</b>
 *
 * <ul>
 *   <li><b>Validation-only downstream:</b> The generated token passes through the same {@code
 *       JwtAuthenticationFilter} as user tokens. No special-case handling in the filter — it
 *       extracts subject=0, roles=[SERVICE], sets SecurityContext normally.
 *   <li><b>No token caching:</b> Tokens are cheap to generate (in-memory HMAC signing, ~0.1ms).
 *       Caching would complicate expiry management for negligible benefit.
 *   <li><b>Interface + Impl separation:</b> When Keycloak client-credentials flow replaces this
 *       (Phase 8 Step 6), only the implementation swaps — consumers are unaffected.
 * </ul>
 *
 * <p><b>Consumer:</b> {@link com.equitycart.commons.feign.FeignAuthorizationInterceptor} — when
 * {@code RequestContextHolder.getRequestAttributes()} returns null, it calls {@code
 * getServiceToken()} and attaches the result as a Bearer token on the outgoing Feign request.
 *
 * @see com.equitycart.commons.feign.FeignAuthorizationInterceptor
 * @see com.equitycart.commons.security.impl.ServiceTokenProviderImpl
 */
public interface ServiceTokenProvider {

  /**
   * Generates a fresh short-lived JWT for service-to-service authentication.
   *
   * @return compact JWT string (without "Bearer " prefix — caller adds it)
   */
  String getServiceToken();
}
