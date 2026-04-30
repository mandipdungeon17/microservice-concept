package com.equitycart.product.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data transfer object representing a single row in the product CSV import file. Used by Spring
 * Batch's {@code FlatFileItemReader} to map CSV columns to fields.
 */
@Data
@NoArgsConstructor
public class ProductCsvRow {
  private String name;
  private String description;
  private String sku;
  private BigDecimal price;
  private Integer stockQuantity;
  private String imageUrl;
  private Long brandId;
  private Long categoryId;
}
