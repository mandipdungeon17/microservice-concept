package com.equitycart.commons.filter;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that assigns or propagates a Correlation ID for the lifetime of an HTTP request.
 *
 * <p><b>Why this filter exists:</b> In a microservice system, a single user action (e.g., "place
 * order") fans out into multiple HTTP calls across PORTFOLIO-SERVICE, ORDER-SERVICE, and
 * PRODUCT-SERVICE. Each service logs independently. Without a shared identifier, correlating those
 * log lines requires manual inspection of timestamps — error-prone at scale. A Correlation ID is a
 * single UUID that travels with the request through every hop and appears on every log line.
 *
 * <p><b>Flow per request:</b>
 *
 * <pre>
 * 1. Incoming HTTP request arrives at any service (or the API Gateway)
 * 2. Filter reads the {@code
 * X - Correlation - Id
 * } header
 * 3. If absent or blank, generates a new UUID — this service is the request origin
 * 4. Puts the value into MDC / ThreadContext under key {@code
 * "correlationId"
 * }
 *    → Log4j2 pattern {@code %X{correlationId}} reads this key on every subsequent log call
 * 5. Echoes the value back in the response header — caller learns what ID was assigned
 * 6. Calls {@code
 * filterChain.doFilter()
 * } — request proceeds with MDC populated
 * 7. {@code finally}: removes the key — mandatory, servlet containers reuse threads
 *    (a leaked key from request N would corrupt request N+1 on the same pooled thread)
 * </pre>
 *
 * <p><b>MDC vs ThreadContext:</b> The original implementation used {@code org.slf4j.MDC}, which
 * works because {@code log4j-slf4j-impl} bridges SLF4J calls to Log4j2's {@code
 * org.apache.logging.log4j.ThreadContext}. However, since this project uses Log4j2 directly
 * throughout ({@code LogManager.getLogger}), prefer {@code ThreadContext} directly to avoid the
 * bridge indirection. Both write to the same underlying thread-local map — the logging pattern
 * {@code %X{correlationId}} reads correctly either way.
 *
 * <p><b>Registration:</b> {@code @Component} registers this bean in the Spring filter chain. {@code
 * OncePerRequestFilter} guarantees exactly-once execution per request even when the filter is
 * referenced by multiple filter chain configurations (e.g., Security + Application chains).
 *
 * <p><b>Correlation ID vs distributed tracing:</b> This is manual, zero-infrastructure correlation
 * — one UUID per request, propagated via HTTP headers and thread-local MDC. It works with plain log
 * aggregation: {@code grep} by UUID across service logs to reconstruct a request trace. By
 * contrast, TraceId + SpanId (OpenTelemetry / Micrometer Tracing) provide richer parent-child span
 * trees with timing and Zipkin/Jaeger UI integration — planned for Phase 9. Correlation ID is the
 * appropriate level for this phase: zero extra dependencies, trivially debuggable, and teaches the
 * propagation mechanic before adding instrumentation complexity.
 */
@Component
public class MdcCorrelationFilter extends OncePerRequestFilter {

  private static final String CORRELATION_HEADER = "X-Correlation-Id";
  private static final Logger log = LogManager.getLogger(MdcCorrelationFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain)
      throws ServletException, IOException {

    String correlationId = request.getHeader(CORRELATION_HEADER);

    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }

    ThreadContext.put("correlationId", correlationId);
    response.setHeader(CORRELATION_HEADER, correlationId); // echo back in response

    try {
      filterChain.doFilter(request, response);
    } finally {
      ThreadContext.remove("correlationId"); // mandatory cleanup — threads are pooled
    }
  }
}
