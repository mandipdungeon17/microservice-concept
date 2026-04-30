package com.equitycart.product.service.api;

import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;
import java.util.List;

/**
 * Service interface for managing brand-to-stock-ticker mappings. These mappings link brands to
 * their publicly traded stock symbols.
 */
public interface BrandTickerMappingService {

  /**
   * Creates a new brand-ticker mapping after validating brand existence and uniqueness.
   *
   * @param request the mapping details including brandId and tickerSymbol
   * @return the created mapping response
   * @throws com.equitycart.commons.exception.DuplicateResourceException if this brand-ticker
   *     combination already exists
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if brand not found
   */
  BrandTickerMappingResponse createBrandTickerMapping(BrandTickerMappingRequest request);

  /**
   * Retrieves all ticker mappings for a specific brand.
   *
   * @param brandId the brand identifier
   * @return list of ticker mapping responses for the brand
   */
  List<BrandTickerMappingResponse> getByBrandId(Long brandId);
}
