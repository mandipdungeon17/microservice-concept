package com.equitycart.product.dto;

public record CategoryResponse (
        Long id,
        String name,
        String description,
        Long parentId,
        String parentName,
        boolean active
){
}
