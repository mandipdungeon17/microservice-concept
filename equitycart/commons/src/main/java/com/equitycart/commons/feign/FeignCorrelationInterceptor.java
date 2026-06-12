package com.equitycart.commons.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.stereotype.Component;

/**
 * Feign {@link RequestInterceptor} that propagates the Correlation ID from the current thread's MDC
 * into every outgoing Feign HTTP request as an {@code X-Correlation-Id} header.
 *
 * <p><b>Why this is needed:</b> When PORTFOLIO-SERVICE calls ORDER-SERVICE via {@code
 * OrderFeignClient} (in the portfolio module), the HTTP request crosses a process boundary. The
 * Correlation ID stored in MDC / {@code ThreadContext} is thread-local — it does not travel to
 * other processes automatically. This interceptor reads it from MDC and adds it as a request header
 * so the receiving service's {@link com.equitycart.commons.filter.MdcCorrelationFilter} can
 * re-populate its own MDC with the same ID. The result is that log lines in both services carry the
 * same Correlation ID for a single originating user request.
 *
 * <p><b>Auto-registration mechanism:</b> Any {@code @Component} implementing Feign's {@link
 * RequestInterceptor} is automatically discovered and registered into every {@code @FeignClient} in
 * the application context — no explicit wiring in each client. Spring collects all {@code
 * RequestInterceptor} beans during context initialization and passes them to the Feign client
 * builder.
 *
 * <p><b>Invocation timing:</b> {@link #apply(RequestTemplate)} is called once per outgoing Feign
 * HTTP call, immediately before the request bytes are written. The MDC value is read at call time
 * (not at interceptor construction), so it always reflects the Correlation ID of the currently
 * executing request thread — correct in multi-threaded environments.
 *
 * <p><b>MDC vs ThreadContext:</b> The original implementation used {@code org.slf4j.MDC.get()},
 * which delegates to {@code org.apache.logging.log4j.ThreadContext.get()} via the {@code
 * log4j-slf4j-impl} bridge. Since this project uses Log4j2 directly ({@code LogManager.getLogger}),
 * prefer {@code ThreadContext.get()} to eliminate bridge overhead and keep the logging
 * implementation consistent.
 *
 * <p><b>Null guard:</b> If no Correlation ID is in MDC (e.g., a background {@code @Scheduled} task
 * or a test that bypassed {@code MdcCorrelationFilter}), the header is omitted rather than
 * forwarding a null or empty value. The receiving service will generate a fresh UUID.
 */
@Component
public class FeignCorrelationInterceptor implements RequestInterceptor {

  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  @Override
  public void apply(RequestTemplate requestTemplate) {
    String correlationId = ThreadContext.get("correlationId");
    if (correlationId != null) {
      requestTemplate.header(CORRELATION_HEADER, correlationId);
    }
  }
}
