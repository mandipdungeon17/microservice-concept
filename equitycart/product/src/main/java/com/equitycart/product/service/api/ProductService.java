package com.equitycart.product.service.api;

import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import com.equitycart.product.entity.Product;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    ProductResponse createProduct(ProductRequest productRequest);

    ProductResponse getProductById(Long productId);

    ProductResponse updateProduct(Long productId, ProductRequest productRequest);

    void deleteProduct(Long productId);

    PagedResponse<ProductResponse> searchProduct(ProductSearchRequest request, Pageable pageable);
}
