package com.equitycart.product.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "brand_ticker_mappings", uniqueConstraints = @UniqueConstraint(
        columnNames = {"ticker_symbol", "brand_id"}, name = "unique_ticker_brand"))
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BrandTickerMapping extends BaseEntity {

    @Column(nullable = false)
    private String tickerSymbol;

    @Column(nullable = false)
    private String exchange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal stockBackPercentage = BigDecimal.ZERO;

    @Builder.Default
    private boolean active = true;
}
