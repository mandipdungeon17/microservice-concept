package com.equitycart.product.service.impl;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.service.api.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {
    
    private final BrandRepository brandRepository;

    @Override
    public BrandResponse createBrand(BrandRequest brandRequest) {
        if (brandRepository.existsByNameIgnoreCase(brandRequest.name()))
            throw new DuplicateResourceException("Brand already exists");

        Brand brand = Brand.builder()
                .name(brandRequest.name())
                .logoUrl(brandRequest.logoUrl())
                .description(brandRequest.description())
                .build();
        
        Brand savedBrand = brandRepository.save(brand);

        return toResponse(savedBrand);
    }

    @Override
    public BrandResponse getBrandById(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + brandId));

        return toResponse(brand);
    }

    @Override
    public List<BrandResponse> getAllBrands() {
        List<Brand> brandList = brandRepository.findAll();

        return brandList.stream().map(this::toResponse).toList();
    }

    private BrandResponse toResponse(Brand brand) {
        return new BrandResponse(
                brand.getId(),
                brand.getName(),
                brand.getDescription(),
                brand.getLogoUrl(),
                brand.isActive());
    }
}
