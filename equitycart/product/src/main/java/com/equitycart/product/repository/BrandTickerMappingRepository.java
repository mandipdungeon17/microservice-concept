package com.equitycart.product.repository;

import com.equitycart.product.entity.BrandTickerMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrandTickerMappingRepository extends JpaRepository<BrandTickerMapping, Long> {

    List<BrandTickerMapping> findByBrandId(Long brandId);

    boolean existsByBrandIdAndTickerSymbol(Long brandId, String tickerSymbol);
}
