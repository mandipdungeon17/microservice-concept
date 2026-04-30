package com.equitycart.product.controller;

import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import com.equitycart.product.service.api.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for product CRUD and search operations. Base path: {@code /api/products} */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

  private static final Logger log = LogManager.getLogger(ProductController.class);

  private final ProductService productService;

  /**
   * Creates a new product. Restricted to ADMIN and SELLER roles.
   *
   * @param request validated product request body
   * @return the created product with HTTP 201 status
   */
  @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
  @PostMapping
  public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
    log.info("POST /api/products - SKU: {}", request.sku());
    ProductResponse productResponse = productService.createProduct(request);

    return ResponseEntity.status(HttpStatus.CREATED).body(productResponse);
  }

  /**
   * Retrieves a product by its identifier.
   *
   * @param id the product identifier
   * @return the product response
   */
  @GetMapping("/{id}")
  public ResponseEntity<ProductResponse> getProductById(@PathVariable("id") Long id) {
    log.info("GET /api/products/{}", id);
    ProductResponse productResponse = productService.getProductById(id);

    return ResponseEntity.ok(productResponse);
  }

  /**
   * Updates an existing product. Restricted to ADMIN and SELLER roles.
   *
   * @param id the product identifier to update
   * @param request validated product request body
   * @return the updated product response
   */
  @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
  @PutMapping("/{id}")
  public ResponseEntity<ProductResponse> updateProduct(
      @PathVariable("id") Long id, @Valid @RequestBody ProductRequest request) {
    log.info("PUT /api/products/{} - SKU: {}", id, request.sku());

    ProductResponse productResponse = productService.updateProduct(id, request);

    return ResponseEntity.ok(productResponse);
  }

  /**
   * Soft-deletes a product. Restricted to ADMIN role.
   *
   * @param id the product identifier to delete
   * @return HTTP 204 No Content
   */
  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteProduct(@PathVariable("id") Long id) {
    log.info("DELETE /api/products/{}", id);
    productService.deleteProduct(id);

    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  /**
   * Searches products using optional query parameters with pagination.
   *
   * @param request the search filter criteria
   * @param pageable pagination and sorting parameters
   * @return paged list of matching products
   */
  @GetMapping
  public ResponseEntity<PagedResponse<ProductResponse>> searchProducts(
      ProductSearchRequest request, Pageable pageable) {
    log.info("GET /api/products - search with filters");
    PagedResponse<ProductResponse> response = productService.searchProduct(request, pageable);

    return ResponseEntity.ok(response);
  }
}
