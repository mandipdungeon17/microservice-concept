package com.equitycart.product.repository;

import com.equitycart.product.entity.BrandTickerMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link BrandTickerMapping} entities. Provides lookups by brand and
 * duplicate-detection by brand-ticker combination.
 */
public interface BrandTickerMappingRepository extends JpaRepository<BrandTickerMapping, Long> {

  /**
   * Retrieves all ticker mappings for a specific brand.
   *
   * @param brandId the brand identifier
   * @return list of ticker mappings for the given brand
   */
  List<BrandTickerMapping> findByBrandId(Long brandId);

  /**
   * Checks whether a mapping already exists for the given brand and ticker symbol.
   *
   * @param brandId the brand identifier
   * @param tickerSymbol the stock ticker symbol
   * @return {@code true} if this brand-ticker combination already exists
   */
  boolean existsByBrandIdAndTickerSymbol(Long brandId, String tickerSymbol);
}
