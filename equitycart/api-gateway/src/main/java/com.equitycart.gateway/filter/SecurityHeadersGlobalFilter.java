package com.equitycart.gateway.filter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive {@link GlobalFilter} that adds OWASP-recommended security response headers to every HTTP
 * response leaving the API Gateway (Phase 8 Step 9).
 *
 * <p><b>Why at the Gateway?</b> Adding headers at the gateway ensures ALL downstream services
 * benefit from browser security protections without each service implementing its own header logic.
 * One change here protects 7+ services.
 *
 * <p><b>Headers Added and What They Prevent:</b>
 *
 * <pre>
 * ┌─────────────────────────────────┬───────────────────────────────────────────────────────────┐
 * │ Header                          │ Attack Prevented                                          │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ X-Content-Type-Options: nosniff │ MIME-type sniffing: browser guesses application/json as   │
 * │                                 │ text/html, allowing injected script tags to execute as    │
 * │                                 │ HTML. "nosniff" forces browser to trust Content-Type.     │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ X-Frame-Options: DENY           │ Clickjacking: attacker embeds your page in a hidden       │
 * │                                 │ iframe on their site. Victim clicks "Play Video" but      │
 * │                                 │ actually clicks "Transfer $1000" on the hidden page.      │
 * │                                 │ "DENY" prevents any site from framing your pages.         │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ Strict-Transport-Security       │ Man-in-the-middle (MITM): attacker intercepts HTTP        │
 * │ max-age=31536000;               │ request (before redirect to HTTPS) and reads/modifies     │
 * │ includeSubDomains               │ traffic. HSTS tells browser "never use HTTP for this      │
 * │                                 │ domain again" — cached for 1 year (31536000 seconds).     │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ Content-Security-Policy:        │ Cross-Site Scripting (XSS): attacker injects a script     │
 * │ default-src 'self'              │ tag into your page (e.g., via stored XSS in a comment).   │
 * │                                 │ CSP tells browser "only execute scripts loaded from THIS  │
 * │                                 │ origin" — external scripts (attacker's server) blocked.   │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ Referrer-Policy:                │ Information leakage: when user clicks a link to external  │
 * │ strict-origin-when-cross-origin │ site, browser sends Referer header with FULL URL          │
 * │                                 │ (including query params like ?token=xxx). This policy     │
 * │                                 │ sends only the origin (https://myapp.com) for cross-site. │
 * ├─────────────────────────────────┼───────────────────────────────────────────────────────────┤
 * │ Permissions-Policy:             │ Feature abuse: malicious page requests access to camera,  │
 * │ camera=(), microphone=(),       │ microphone, GPS. This header explicitly denies these      │
 * │ geolocation=()                  │ permissions — browser will never prompt the user.         │
 * └─────────────────────────────────┴───────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why .then(Mono.fromRunnable()) Pattern:</b>
 *
 * <pre>
 * chain.filter(exchange)         → executes downstream filters and proxies to service
 *   .then(Mono.fromRunnable())   → runs AFTER downstream response arrives (response committed)
 *
 * PROBLEM: In some cases, response headers are already flushed before .then() runs.
 * MITIGATION: This filter runs at LOWEST_PRECEDENCE (last), so headers are set after all other
 * filters but before the response bytes are written to the wire. For streaming responses (SSE),
 * consider using ServerHttpResponseDecorator (like CorrelationIdGatewayFilter does).
 * </pre>
 *
 * <p><b>Filter Ordering:</b> {@code LOWEST_PRECEDENCE} ensures this runs AFTER all other filters
 * (security, rate limiting, routing). Headers are added just before the response leaves the
 * gateway. This prevents earlier filters from accidentally overwriting these security headers.
 *
 * <p><b>Thread Safety:</b> Stateless filter — no fields mutated at runtime. Header values are
 * constant strings. Safe for concurrent reactive execution on Netty event loop.
 *
 * @see com.equitycart.gateway.filter.CorrelationIdGatewayFilter (runs at HIGHEST_PRECEDENCE)
 * @see com.equitycart.gateway.config.SecurityConfig (OAuth2 authentication before this filter)
 */
@Component
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered {

  private static final Logger log = LogManager.getLogger(SecurityHeadersGlobalFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain
        .filter(exchange)
        .then(
            Mono.fromRunnable(
                () -> {
                  HttpHeaders headers = exchange.getResponse().getHeaders();
                  headers.set("X-Content-Type-Options", "nosniff");
                  headers.set("X-Frame-Options", "DENY");
                  headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                  headers.set("Content-Security-Policy", "default-src 'self'");
                  headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
                  headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                  log.debug("OWASP security headers added to response");
                }));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
