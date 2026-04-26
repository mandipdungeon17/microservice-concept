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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BrandTickerMappingServiceImpl implements BrandTickerMappingService {

    private final BrandTickerMappingRepository brandTickerMappingRepository;
    private final BrandRepository brandRepository;

    @Transactional
    @Override
    public BrandTickerMappingResponse createBrandTickerMapping(BrandTickerMappingRequest request) {
        if(brandTickerMappingRepository.existsByBrandIdAndTickerSymbol(request.brandId(), request.tickerSymbol()))
            throw new DuplicateResourceException("Brand and Ticker Symbol already exist.");

        Brand brand = brandRepository.findById(request.brandId()).orElseThrow(
                () -> new ResourceNotFoundException("Brand not found with id " + request.brandId()));
        BrandTickerMapping brandTickerMapping = BrandTickerMapping.builder()
                .tickerSymbol(request.tickerSymbol())
                .exchange(request.exchange())
                .stockBackPercentage(request.stockBackPercentage())
                .brand(brand)
                .build();

        BrandTickerMapping savedTickerMapping = brandTickerMappingRepository.save(brandTickerMapping);

        return toResponse(savedTickerMapping);
    }

    @Override
    public List<BrandTickerMappingResponse> getByBrandId(Long brandId) {
        List<BrandTickerMapping> brandTickerMappings = brandTickerMappingRepository.findByBrandId(brandId);

        return brandTickerMappings.stream().map(this::toResponse).toList();
    }

    private BrandTickerMappingResponse toResponse(BrandTickerMapping mapping) {
        return new BrandTickerMappingResponse(
                mapping.getId(),
                mapping.getBrand().getId(),
                mapping.getBrand().getName(),
                mapping.getTickerSymbol(),
                mapping.getExchange(),
                mapping.getStockBackPercentage(),
                mapping.isActive()
        );
    }
}
