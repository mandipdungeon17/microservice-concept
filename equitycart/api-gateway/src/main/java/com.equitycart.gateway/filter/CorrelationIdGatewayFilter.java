package com.equitycart.gateway.filter;

import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway {@link GlobalFilter} that generates or propagates a Correlation ID on every
 * inbound request before it is forwarded to a downstream microservice.
 *
 * <p><b>Why a GlobalFilter (not default-filters YAML)?</b> Spring Cloud Gateway's YAML {@code
 * default-filters} section supports built-in filter factories (e.g., {@code AddRequestHeader},
 * {@code AddResponseHeader}). These filters set static values from SpEL evaluated once at
 * route-load time — they cannot generate a unique UUID per request or conditionally skip generation
 * when a header already exists. Both of those capabilities require runtime Java logic, which only a
 * {@link GlobalFilter} bean provides.
 *
 * <p><b>Why this filter runs on Netty (not Tomcat):</b> The API Gateway uses Spring Cloud Gateway
 * (WebFlux-based), which runs on Netty's non-blocking event loop — not a Servlet container. The
 * reactive request type is {@link ServerWebExchange} (not {@code HttpServletRequest}), and filters
 * return {@link Mono} (not void). There is no {@code OncePerRequestFilter} here — that is Servlet
 * API only, used by downstream services (portfolio-service, order-service) which run on Tomcat.
 *
 * <p><b>Immutability and {@code exchange.mutate()}:</b> {@link ServerHttpRequest} and {@link
 * ServerWebExchange} are immutable in WebFlux — you cannot call {@code request.addHeader(...)}.
 * Instead, {@code exchange.getRequest().mutate()} returns a builder that copies all existing
 * request data and allows adding/overriding headers. The builder produces a new {@link
 * ServerHttpRequest}, which must then be wrapped in a new exchange via {@code
 * exchange.mutate().request(newRequest).build()} before passing to the filter chain.
 *
 * <p><b>Flow per request:</b>
 *
 * <pre>
 * 1. Read X-Correlation-Id from incoming request headers
 * 2. If absent or blank → generate UUID (this gateway is the request origin)
 * 3. Mutate the request to carry the header → downstream services receive it
 * 4. Forward mutated exchange to the rest of the filter chain + routed service
 * 5. After response returns (.then()), add X-Correlation-Id to response headers
 *    → client receives the ID in the HTTP response (useful for frontend error correlation)
 * </pre>
 *
 * <p><b>Ordering:</b> {@link Ordered#HIGHEST_PRECEDENCE} ensures this filter runs before all other
 * global filters (security, rate limiting, logging) — guaranteeing the Correlation ID is available
 * on the exchange by the time any other filter executes.
 *
 * <p><b>What downstream services do with the header:</b> Each downstream service has a {@code
 * com.equitycart.commons.filter.MdcCorrelationFilter} (Servlet-based {@code OncePerRequestFilter})
 * that reads {@code X-Correlation-Id} from the incoming request, stores it in Log4j2 {@code
 * ThreadContext}, and logs it on every line via {@code %X{correlationId}} in the log pattern.
 * Service-to-service calls propagate it further via {@code
 * com.equitycart.commons.feign.FeignCorrelationInterceptor}.
 *
 * <p><b>GlobalFilter vs GatewayFilter:</b>
 *
 * <ul>
 *   <li>{@code GlobalFilter} — applies to ALL routes automatically; registered as a
 *       {@code @Component}
 *   <li>{@code GatewayFilter} — applies to a specific route only; configured per-route in YAML
 *   <li>{@code OrderedGatewayFilter} — a wrapper that assigns an order to a route-level {@code
 *       GatewayFilter}; not relevant here since we implement {@code GlobalFilter} directly
 * </ul>
 *
 * @see <a href="com.equitycart.commons.filter.MdcCorrelationFilter">MdcCorrelationFilter</a>
 * @see <a
 *     href="com.equitycart.commons.feign.FeignCorrelationInterceptor">FeignCorrelationInterceptor</a>
 */
@Component
public class CorrelationIdGatewayFilter implements GlobalFilter, Ordered {

  private static final Logger log = LogManager.getLogger(CorrelationIdGatewayFilter.class);

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
    boolean wasGenerated = correlationId == null;

    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    log.info(
        "Incoming X-Correlation-Id: {} ({})",
        correlationId,
        wasGenerated ? "generated" : "forwarded");

    ServerHttpRequest mutateRequest =
        exchange.getRequest().mutate().header("X-Correlation-Id", correlationId).build();

    ServerWebExchange mutatedExchange = exchange.mutate().request(mutateRequest).build();

    String finalCorrelationId = correlationId;

    return chain
        .filter(mutatedExchange)
        .then(
            Mono.fromRunnable(
                () -> {
                  log.info(
                      "CorrelationIdGatewayFilter: X-Correlation-Id added to response headers");
                  exchange.getResponse().getHeaders().add("X-Correlation-Id", finalCorrelationId);
                }));
  }
}
