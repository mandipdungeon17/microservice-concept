package com.equitycart.product.controller;

import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import com.equitycart.product.service.api.BrandService;
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

/** REST controller for brand management operations. Base path: {@code /api/brands} */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
public class BrandController {

  private static final Logger log = LogManager.getLogger(BrandController.class);

  private final BrandService brandService;

  /**
   * Creates a new brand. Restricted to ADMIN role.
   *
   * @param brandRequest validated brand request body
   * @return the created brand with HTTP 201 status
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping
  public ResponseEntity<BrandResponse> createBrand(@Valid @RequestBody BrandRequest brandRequest) {
    log.info("POST /api/brands - name: {}", brandRequest.name());
    BrandResponse brandResponse = brandService.createBrand(brandRequest);

    return ResponseEntity.status(HttpStatus.CREATED).body(brandResponse);
  }

  /**
   * Retrieves a brand by its identifier.
   *
   * @param id the brand identifier
   * @return the brand response
   */
  @GetMapping("/{id}")
  public ResponseEntity<BrandResponse> getBrandById(@PathVariable("id") Long id) {
    log.info("GET /api/brands/{}", id);
    BrandResponse brandResponse = brandService.getBrandById(id);

    return ResponseEntity.ok(brandResponse);
  }

  /**
   * Retrieves all brands.
   *
   * @return list of all brand responses
   */
  @GetMapping
  public ResponseEntity<List<BrandResponse>> getAllBrands() {
    log.info("GET /api/brands");
    List<BrandResponse> brandResponseList = brandService.getAllBrands();

    return ResponseEntity.ok(brandResponseList);
  }
}
