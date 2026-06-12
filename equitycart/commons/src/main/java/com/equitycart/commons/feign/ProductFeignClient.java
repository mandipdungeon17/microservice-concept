package com.equitycart.commons.feign;

import com.equitycart.commons.dto.BrandTickerMappingDTO;
import com.equitycart.commons.dto.ProductDTO;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Feign client interface for HTTP communication with {@code PRODUCT-SERVICE}.
 *
 * <p><b>Pattern — Declarative HTTP Client:</b> {@code @FeignClient} generates a JDK Dynamic Proxy
 * at startup that implements this interface. Each method call translates to an HTTP request routed
 * to {@code PRODUCT-SERVICE} via the Eureka load-balancer ({@code lb://PRODUCT-SERVICE}).
 *
 * <p><b>Why not RestTemplate?</b> RestTemplate requires hardcoding URLs and writing boilerplate
 * request/response mapping per call. Feign treats the HTTP contract as a Java interface — no
 * implementation code, just annotations. Netflix invented this pattern in 2012 for internal
 * service-to-service calls; Spring Cloud OpenFeign integrated it with Spring MVC annotations around
 * 2015.
 *
 * <p><b>DTO Projection (Jackson subset):</b> {@code ProductResponse} in product-service has more
 * fields than {@code ProductDTO} here. Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES=false} silently
 * drops the extra fields — consuming services only declare the fields they need.
 *
 * <p><b>Error handling:</b> A {@code FeignErrorDecoder} in the commons module intercepts non-2xx
 * responses and maps HTTP 409 ({@code InsufficientStockException}) to a domain exception before it
 * reaches calling code.
 *
 * @see com.equitycart.commons.dto.ProductDTO
 * @see com.equitycart.commons.dto.BrandTickerMappingDTO
 */
@FeignClient(name = "PRODUCT-SERVICE")
public interface ProductFeignClient {

  /**
   * Fetches a product by its identifier. Response deserialized as a {@link ProductDTO} subset.
   *
   * @param id the product identifier
   * @return product projection containing id, name, price, stockQuantity, brandId, active
   */
  @GetMapping("/api/products/{id}")
  ProductDTO getProductById(@PathVariable("id") Long id);

  /**
   * Acquires a pessimistic write lock and deducts {@code quantity} units from the product's stock.
   * Called by order-service on order placement.
   *
   * <p>{@code @RequestParam} maps {@code quantity} as a query parameter ({@code ?quantity=5}). A
   * single scalar value does not warrant a JSON body.
   *
   * @param id the product identifier
   * @param quantity units to deduct
   */
  @PutMapping("/api/products/{id}/deduct-stock")
  void deductStock(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);

  /**
   * Restores previously deducted stock. The compensating operation for {@link #deductStock}. Called
   * by order-service on order return or cancellation.
   *
   * @param id the product identifier
   * @param quantity units to restore
   */
  @PutMapping("/api/products/{id}/restore-stock")
  void restoreStock(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);

  /**
   * Retrieves all brand-ticker mappings for a given brand. Used by portfolio-service during
   * stock-back reward calculation to determine which ticker symbols to award fractional shares of.
   *
   * @param brandId the brand identifier (obtained from {@link ProductDTO#brandId()})
   * @return list of ticker mappings; empty list if the brand has no stock-back configuration
   */
  @GetMapping("/api/brand-ticker-mappings/brand/{brandId}")
  List<BrandTickerMappingDTO> getTickerMappingsByBrandId(@PathVariable("brandId") Long brandId);
}
