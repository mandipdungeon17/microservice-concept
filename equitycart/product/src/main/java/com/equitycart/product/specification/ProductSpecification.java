package com.equitycart.product.specification;

import com.equitycart.product.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

public final class ProductSpecification {

    private ProductSpecification () {}

    public static Specification<Product> hasName(String name) {
        if(name == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%");
    }

    public static Specification<Product> hasBrandId(Long brandId) {
        if(brandId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .equal(root.get("brand").get("id"), brandId);
    }

    public static Specification<Product> hasCategoryId(Long categoryId) {
        if(categoryId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Product> hasMinPrice(BigDecimal minPrice) {
        if(minPrice == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .greaterThanOrEqualTo(root.get("price"), minPrice);
    }

    public static Specification<Product> hasMaxPrice(BigDecimal maxPrice) {
        if(maxPrice == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .lessThanOrEqualTo(root.get("price"), maxPrice);
    }

    public static Specification<Product> isActive(Boolean active) {
        if (active == null) return Specification.unrestricted();
        return (root, query, cb) -> cb
                .equal(root.get("active"), active);
    }
}
