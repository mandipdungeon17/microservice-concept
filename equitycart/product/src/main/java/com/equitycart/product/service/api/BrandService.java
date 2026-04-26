package com.equitycart.product.service.api;

import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;

import java.util.List;

public interface BrandService {

    BrandResponse createBrand(BrandRequest brandRequest);

    BrandResponse getBrandById(Long brandId);

    List<BrandResponse> getAllBrands();

}
