package com.equitycart.commons.feign;

import com.equitycart.commons.security.api.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign {@link RequestInterceptor} that propagates the JWT Authorization header from the current
 * incoming HTTP request to every outgoing Feign client call.
 *
 * <p><b>Problem Solved:</b> After Phase 8 Step 2, all downstream services enforce JWT
 * authentication. When order-service calls product-service via {@code ProductFeignClient}, the
 * outgoing HTTP request is a NEW request — it does not automatically carry the original user's
 * Authorization header. Without this interceptor, inter-service Feign calls would fail with 401
 * Unauthorized because the downstream service receives no token.
 *
 * <p><b>Mechanism — RequestContextHolder (ThreadLocal):</b>
 *
 * <ol>
 *   <li>When a user request arrives at a servlet-based Spring service, the {@code
 *       DispatcherServlet} stores the {@code HttpServletRequest} in a {@link ThreadLocal} variable
 *       managed by {@link RequestContextHolder}
 *   <li>This interceptor reads that stored request via {@code
 *       RequestContextHolder.getRequestAttributes()}
 *   <li>It extracts the "Authorization" header from the original incoming request
 *   <li>It copies that header onto the outgoing Feign {@link RequestTemplate}
 *   <li>The downstream service receives the same JWT token the user originally sent
 * </ol>
 *
 * <p><b>Fallback for non-HTTP threads (ServiceTokenProvider):</b> Not all code paths have an
 * originating HTTP request. Kafka consumers, {@code @Scheduled} methods, and {@code @Async} threads
 * execute without a servlet request — {@code RequestContextHolder.getRequestAttributes()} returns
 * null in these contexts. Rather than silently skipping (which causes 401 on the downstream call),
 * this interceptor falls back to {@link ServiceTokenProvider#getServiceToken()} to generate a
 * short-lived machine-identity JWT (subject=0, role=SERVICE, 60s expiry). The downstream service's
 * {@code JwtAuthenticationFilter} validates this token normally — it sees an authenticated
 * principal with userId=0 and ROLE_SERVICE, satisfying {@code .anyRequest().authenticated()} rules.
 *
 * <p><b>Relationship to FeignCorrelationInterceptor:</b> Both interceptors are registered on every
 * Feign client (Spring auto-discovers all {@code @Component RequestInterceptor} beans). They run
 * sequentially in bean-registration order. FeignCorrelationInterceptor propagates tracing context
 * (X-Correlation-Id from MDC), while this interceptor propagates security context (Authorization
 * from HTTP request). Separate interceptors = Single Responsibility.
 *
 * <p><b>Token Propagation vs Token Exchange:</b>
 *
 * <ul>
 *   <li><b>Propagation</b> (what we do here): same token forwarded unchanged. Simple, but the
 *       downstream service sees the full user identity + all roles. Acceptable for internal trusted
 *       services.
 *   <li><b>Exchange</b> (OAuth2 pattern): the intermediary service exchanges the user token for a
 *       new, scoped-down token at the IdP. More secure (least-privilege), but requires IdP support
 *       (Keycloak token exchange endpoint). Phase 8 Step 6 will enable this option.
 * </ul>
 *
 * <p><b>Thread Safety:</b> {@link RequestContextHolder} uses {@link ThreadLocal} storage. {@code
 * getRequestAttributes()} always returns the request bound to the CURRENT thread. In a standard
 * servlet container (Tomcat), each request is handled by one thread from the pool, so concurrent
 * requests never interfere. However, if you spawn a child thread (e.g., {@code
 * CompletableFuture.supplyAsync()}), the child thread has NO request attributes — the ThreadLocal
 * does not propagate. Spring offers {@code RequestContextHolder.setRequestAttributes(attrs, true)}
 * for inheritable mode, but the safer solution is to extract the token before going async.
 *
 * @see FeignCorrelationInterceptor (propagates X-Correlation-Id via MDC ThreadContext)
 * @see org.springframework.web.context.request.RequestContextHolder
 */
@Component
@RequiredArgsConstructor
public class FeignAuthorizationInterceptor implements RequestInterceptor {

  private static final Logger log = LogManager.getLogger(FeignAuthorizationInterceptor.class);

  private final ServiceTokenProvider serviceTokenProvider;

  @Override
  public void apply(RequestTemplate template) {
    ServletRequestAttributes requestAttributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

    if (requestAttributes != null) {
      String header = requestAttributes.getRequest().getHeader("Authorization");
      if (header != null) {
        template.header("Authorization", header);
        log.debug("Propagated Authorization header to outgoing Feign request: {}", template.url());
      } else {
        log.debug("No Authorization header in current request; Feign call will be unauthenticated");
      }
    } else {
      log.debug(
          "No RequestContext available (non-HTTP thread, e.g. Kafka consumer); skipping auth propagation");
      String serviceToken = serviceTokenProvider.getServiceToken();
      template.header("Authorization", "Bearer " + serviceToken);
    }
  }
}
