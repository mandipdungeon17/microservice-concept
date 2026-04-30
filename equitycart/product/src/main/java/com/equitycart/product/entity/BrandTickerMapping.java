package com.equitycart.product.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps a {@link Brand} to a stock ticker symbol on a specific exchange. Each mapping includes a
 * stock-back percentage that determines the cashback rate for purchases from the associated brand.
 * Enforces a unique constraint on (ticker_symbol, brand_id).
 */
@Entity
@Table(
    name = "brand_ticker_mappings",
    uniqueConstraints =
        @UniqueConstraint(
            columnNames = {"ticker_symbol", "brand_id"},
            name = "unique_ticker_brand"))
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

  @Builder.Default private boolean active = true;
}
