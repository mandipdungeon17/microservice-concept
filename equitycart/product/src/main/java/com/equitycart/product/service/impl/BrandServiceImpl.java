package com.equitycart.product.service.impl;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.service.api.BrandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link BrandService} that handles brand CRUD operations. Validates brand name
 * uniqueness before creation.
 */
@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

  private static final Logger log = LogManager.getLogger(BrandServiceImpl.class);

  private final BrandRepository brandRepository;

  /** {@inheritDoc} */
  @Override
  public BrandResponse createBrand(BrandRequest brandRequest) {
    log.info("Creating brand with name: {}", brandRequest.name());
    if (brandRepository.existsByNameIgnoreCase(brandRequest.name()))
      throw new DuplicateResourceException("Brand already exists");

    Brand brand =
        Brand.builder()
            .name(brandRequest.name())
            .logoUrl(brandRequest.logoUrl())
            .description(brandRequest.description())
            .build();

    Brand savedBrand = brandRepository.save(brand);
    log.info("Brand created successfully with id: {}", savedBrand.getId());

    return toResponse(savedBrand);
  }

  /** {@inheritDoc} */
  @Override
  public BrandResponse getBrandById(Long brandId) {
    log.debug("Fetching brand by id: {}", brandId);
    Brand brand =
        brandRepository
            .findById(brandId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Brand not found with id: " + brandId));

    return toResponse(brand);
  }

  /** {@inheritDoc} */
  @Override
  public List<BrandResponse> getAllBrands() {
    log.debug("Fetching all brands");
    List<Brand> brandList = brandRepository.findAll();
    log.info("Retrieved {} brands", brandList.size());

    return brandList.stream().map(this::toResponse).toList();
  }

  /**
   * Converts a {@link Brand} entity to a {@link BrandResponse} DTO.
   *
   * @param brand the brand entity
   * @return the brand response DTO
   */
  private BrandResponse toResponse(Brand brand) {
    return new BrandResponse(
        brand.getId(),
        brand.getName(),
        brand.getDescription(),
        brand.getLogoUrl(),
        brand.isActive());
  }
}
