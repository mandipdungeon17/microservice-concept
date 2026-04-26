package com.equitycart.product.controller;

import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import com.equitycart.product.service.api.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<BrandResponse> createBrand(@Valid @RequestBody BrandRequest brandRequest) {
        BrandResponse brandResponse = brandService.createBrand(brandRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(brandResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BrandResponse> getBrandById(@PathVariable Long id) {
        BrandResponse brandResponse = brandService.getBrandById(id);

        return ResponseEntity.ok(brandResponse);
    }

    @GetMapping
    public ResponseEntity<List<BrandResponse>> getAllBrands() {
        List<BrandResponse> brandResponseList = brandService.getAllBrands();

        return ResponseEntity.ok(brandResponseList);
    }
}
