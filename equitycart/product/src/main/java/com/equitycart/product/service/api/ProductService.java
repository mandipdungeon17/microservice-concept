package com.equitycart.product.service.api;

import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for product lifecycle operations. Defines CRUD and search capabilities for the
 * product catalog.
 */
public interface ProductService {

  /**
   * Creates a new product after validating brand and category existence.
   *
   * @param productRequest product details including brandId and categoryId
   * @return the created product response
   * @throws com.equitycart.commons.exception.DuplicateResourceException if SKU already exists
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if brand or category not
   *     found
   */
  ProductResponse createProduct(ProductRequest productRequest);

  /**
   * Retrieves a product by its identifier.
   *
   * @param productId the product identifier
   * @return the product response
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if no product found
   */
  ProductResponse getProductById(Long productId);

  /**
   * Updates an existing product with new details.
   *
   * @param productId the product identifier to update
   * @param productRequest the updated product details
   * @return the updated product response
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if product, brand, or
   *     category not found
   * @throws com.equitycart.commons.exception.DuplicateResourceException if SKU conflicts with
   *     another product
   */
  ProductResponse updateProduct(Long productId, ProductRequest productRequest);

  /**
   * Soft-deletes a product by setting its active flag to false.
   *
   * @param productId the product identifier to deactivate
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if no product found
   */
  void deleteProduct(Long productId);

  /**
   * Searches products using dynamic filters with pagination support.
   *
   * @param request the search criteria (all fields optional)
   * @param pageable pagination and sorting parameters
   * @return a paged response of matching products
   */
  PagedResponse<ProductResponse> searchProduct(ProductSearchRequest request, Pageable pageable);

  /**
   * Deducts stock from a product using a pessimistic write lock to prevent concurrent over-sells.
   * Called by order-service via HTTP ({@code ProductFeignClient}) on order placement.
   *
   * <p>This method owns the {@code SELECT FOR UPDATE} lock because the data lives here. The lock
   * cannot span the HTTP boundary — only this service can enforce atomic stock checks.
   *
   * @param productId the product whose stock to deduct
   * @param quantity the number of units to deduct
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if the product does not
   *     exist
   * @throws com.equitycart.commons.exception.InsufficientStockException if available stock is less
   *     than the requested quantity
   */
  void deductStock(Long productId, int quantity);

  /**
   * Restores previously deducted stock — the compensating transaction for {@link #deductStock(Long,
   * int)}. Called by order-service via HTTP on order return or cancellation.
   *
   * @param productId the product whose stock to restore
   * @param quantity the number of units to add back
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if the product does not
   *     exist
   */
  void restoreStock(Long productId, int quantity);
}
