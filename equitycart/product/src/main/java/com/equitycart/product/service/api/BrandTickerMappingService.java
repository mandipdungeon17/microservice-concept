package com.equitycart.product.service.api;

import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;

import java.util.List;

public interface BrandTickerMappingService {

    BrandTickerMappingResponse createBrandTickerMapping(BrandTickerMappingRequest request);

    List<BrandTickerMappingResponse> getByBrandId(Long brandId);
}
