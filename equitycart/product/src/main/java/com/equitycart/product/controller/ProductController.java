package com.equitycart.product.controller;

import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import com.equitycart.product.service.api.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse productResponse = productService.createProduct(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(productResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable("id") Long id) {
        ProductResponse productResponse = productService.getProductById(id);

        return ResponseEntity.ok(productResponse);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable("id") Long id,
                                                         @Valid @RequestBody ProductRequest request) {

        ProductResponse productResponse = productService.updateProduct(id, request);

        return ResponseEntity.ok(productResponse);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") Long id) {
        productService.deleteProduct(id);

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> searchProducts(
            ProductSearchRequest request, Pageable pageable) {
        PagedResponse<ProductResponse> response = productService.searchProduct(request, pageable);

        return ResponseEntity.ok(response);
    }
}
