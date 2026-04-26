package com.equitycart.product.dto;

import jakarta.validation.constraints.NotBlank;

public record BrandRequest (
        @NotBlank String name,
        String description,
        String logoUrl
){
}
