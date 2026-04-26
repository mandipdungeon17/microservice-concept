package com.equitycart.product.repository;

import com.equitycart.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Product> findByBrandId(Long brandId);

    List<Product> findByCategoryId(Long categoryId);
}
