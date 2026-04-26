package com.equitycart.product.controller;

import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;
import com.equitycart.product.service.api.BrandTickerMappingService;
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
@RequestMapping("/api/brand-ticker-mappings")
@RequiredArgsConstructor
public class BrandTickerMappingController {

    private final BrandTickerMappingService brandTickerMappingService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<BrandTickerMappingResponse> createBrandTickerMapping(
            @Valid @RequestBody BrandTickerMappingRequest request) {

        BrandTickerMappingResponse brandTickerMappingResponse =
                brandTickerMappingService.createBrandTickerMapping(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(brandTickerMappingResponse);
    }

    @GetMapping("/brand/{brandId}")
    public ResponseEntity<List<BrandTickerMappingResponse>> getByBrandId(@PathVariable Long brandId) {
        List<BrandTickerMappingResponse> brandTickerMappingResponseList =
                brandTickerMappingService.getByBrandId(brandId);

        return ResponseEntity.ok(brandTickerMappingResponseList);
    }
}
