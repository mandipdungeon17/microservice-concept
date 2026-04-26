package com.equitycart.product.service.api;

import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;

public interface ProductService {

    ProductResponse createProduct(ProductRequest productRequest);

    ProductResponse getProductById(Long productId);

    ProductResponse updateProduct(Long productId, ProductRequest productRequest);

    void deleteProduct(Long productId);
}
