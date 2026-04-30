package com.equitycart.product.service.impl;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.entity.BrandTickerMapping;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.repository.BrandTickerMappingRepository;
import com.equitycart.product.service.api.BrandTickerMappingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link BrandTickerMappingService} that manages brand-to-ticker mappings.
 * Validates brand existence and ensures no duplicate brand-ticker combinations.
 */
@Service
@RequiredArgsConstructor
public class BrandTickerMappingServiceImpl implements BrandTickerMappingService {

  private static final Logger log = LogManager.getLogger(BrandTickerMappingServiceImpl.class);

  private final BrandTickerMappingRepository brandTickerMappingRepository;
  private final BrandRepository brandRepository;

  /** {@inheritDoc} */
  @Transactional
  @Override
  public BrandTickerMappingResponse createBrandTickerMapping(BrandTickerMappingRequest request) {
    log.info(
        "Creating brand-ticker mapping for brandId: {}, ticker: {}",
        request.brandId(),
        request.tickerSymbol());
    if (brandTickerMappingRepository.existsByBrandIdAndTickerSymbol(
        request.brandId(), request.tickerSymbol()))
      throw new DuplicateResourceException("Brand and Ticker Symbol already exist.");

    Brand brand =
        brandRepository
            .findById(request.brandId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("Brand not found with id " + request.brandId()));
    BrandTickerMapping brandTickerMapping =
        BrandTickerMapping.builder()
            .tickerSymbol(request.tickerSymbol())
            .exchange(request.exchange())
            .stockBackPercentage(request.stockBackPercentage())
            .brand(brand)
            .build();

    BrandTickerMapping savedTickerMapping = brandTickerMappingRepository.save(brandTickerMapping);
    log.info("Brand-ticker mapping created successfully with id: {}", savedTickerMapping.getId());

    return toResponse(savedTickerMapping);
  }

  /** {@inheritDoc} */
  @Override
  public List<BrandTickerMappingResponse> getByBrandId(Long brandId) {
    log.debug("Fetching ticker mappings for brandId: {}", brandId);
    List<BrandTickerMapping> brandTickerMappings =
        brandTickerMappingRepository.findByBrandId(brandId);
    log.info("Retrieved {} ticker mappings for brandId: {}", brandTickerMappings.size(), brandId);

    return brandTickerMappings.stream().map(this::toResponse).toList();
  }

  /**
   * Converts a {@link BrandTickerMapping} entity to a {@link BrandTickerMappingResponse} DTO.
   *
   * @param mapping the brand-ticker mapping entity
   * @return the mapping response DTO
   */
  private BrandTickerMappingResponse toResponse(BrandTickerMapping mapping) {
    return new BrandTickerMappingResponse(
        mapping.getId(),
        mapping.getBrand().getId(),
        mapping.getBrand().getName(),
        mapping.getTickerSymbol(),
        mapping.getExchange(),
        mapping.getStockBackPercentage(),
        mapping.isActive());
  }
}
