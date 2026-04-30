package com.equitycart.product.controller;

import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;
import com.equitycart.product.service.api.BrandTickerMappingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for brand-ticker mapping operations. Base path: {@code
 * /api/brand-ticker-mappings}
 */
@RestController
@RequestMapping("/api/brand-ticker-mappings")
@RequiredArgsConstructor
public class BrandTickerMappingController {

  private static final Logger log = LogManager.getLogger(BrandTickerMappingController.class);

  private final BrandTickerMappingService brandTickerMappingService;

  /**
   * Creates a new brand-ticker mapping. Restricted to ADMIN role.
   *
   * @param request validated mapping request body
   * @return the created mapping with HTTP 201 status
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping
  public ResponseEntity<BrandTickerMappingResponse> createBrandTickerMapping(
      @Valid @RequestBody BrandTickerMappingRequest request) {
    log.info(
        "POST /api/brand-ticker-mappings - brandId: {}, ticker: {}",
        request.brandId(),
        request.tickerSymbol());

    BrandTickerMappingResponse brandTickerMappingResponse =
        brandTickerMappingService.createBrandTickerMapping(request);

    return ResponseEntity.status(HttpStatus.CREATED).body(brandTickerMappingResponse);
  }

  /**
   * Retrieves all ticker mappings for a specific brand.
   *
   * @param brandId the brand identifier
   * @return list of ticker mapping responses
   */
  @GetMapping("/brand/{brandId}")
  public ResponseEntity<List<BrandTickerMappingResponse>> getByBrandId(
      @PathVariable("brandId") Long brandId) {
    log.info("GET /api/brand-ticker-mappings/brand/{}", brandId);
    List<BrandTickerMappingResponse> brandTickerMappingResponseList =
        brandTickerMappingService.getByBrandId(brandId);

    return ResponseEntity.ok(brandTickerMappingResponseList);
  }
}
