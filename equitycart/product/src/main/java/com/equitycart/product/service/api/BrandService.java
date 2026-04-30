package com.equitycart.product.service.api;

import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import java.util.List;

/** Service interface for brand management operations. */
public interface BrandService {

  /**
   * Creates a new brand after checking for name uniqueness.
   *
   * @param brandRequest the brand details
   * @return the created brand response
   * @throws com.equitycart.commons.exception.DuplicateResourceException if brand name already
   *     exists
   */
  BrandResponse createBrand(BrandRequest brandRequest);

  /**
   * Retrieves a brand by its identifier.
   *
   * @param brandId the brand identifier
   * @return the brand response
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if no brand found
   */
  BrandResponse getBrandById(Long brandId);

  /**
   * Retrieves all brands in the catalog.
   *
   * @return list of all brand responses
   */
  List<BrandResponse> getAllBrands();
}
