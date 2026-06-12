package com.equitycart.commons.feign;

import com.equitycart.commons.exception.InsufficientStockException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Custom Feign {@link ErrorDecoder} that translates specific HTTP error responses from downstream
 * services into domain-specific exceptions.
 *
 * <p><b>Why this exists:</b> By default, Feign wraps all non-2xx responses in a generic {@link
 * feign.FeignException} containing the HTTP status code and raw body. Calling code would need to
 * catch {@code FeignException} and inspect the status — coupling business logic to HTTP semantics.
 * This decoder intercepts specific status codes at the Feign layer and throws domain exceptions
 * that controllers and services can handle via {@code @ControllerAdvice} without knowing the
 * response originated from a Feign call.
 *
 * <p><b>Current mappings:</b>
 *
 * <ul>
 *   <li>HTTP 409 (Conflict) → {@link InsufficientStockException} — thrown by product-service when
 *       {@code deductStock} finds available stock < requested quantity
 *   <li>All other non-2xx → delegates to {@link Default} decoder (throws generic {@code
 *       FeignException})
 * </ul>
 *
 * <p><b>Registration:</b> {@code @Component} registers this bean in the application context. Spring
 * Cloud OpenFeign discovers any {@code ErrorDecoder} bean and applies it to all
 * {@code @FeignClient} proxies in the context — no per-client configuration needed.
 *
 * <p><b>Limitation:</b> This decoder only intercepts non-2xx responses. If a downstream service
 * returns HTTP 200 with an error payload in the body, the decoder is never invoked — the response
 * deserializes normally (potentially into an incomplete DTO). For such cases, response validation
 * must happen in the calling service.
 *
 * @see com.equitycart.commons.exception.InsufficientStockException
 * @see com.equitycart.commons.feign.ProductFeignClient#deductStock(Long, int)
 */
@Component
public class FeignErrorDecoder implements ErrorDecoder {

  private static final Logger log = LogManager.getLogger(FeignErrorDecoder.class);

  @Override
  public Exception decode(String methodKey, Response response) {
    if (response.status() == 409) {
      log.warn(
          "Feign call {} returned HTTP 409 (Conflict) — translating to InsufficientStockException",
          methodKey);
      throw new InsufficientStockException(
          "Insufficient stock — request rejected by product-service");
    } else {
      log.warn(
          "Feign call {} returned HTTP {} — delegating to default decoder",
          methodKey,
          response.status());
      return new Default().decode(methodKey, response);
    }
  }
}
