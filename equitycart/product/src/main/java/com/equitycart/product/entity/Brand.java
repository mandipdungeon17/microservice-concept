package com.equitycart.product.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a brand in the EquityCart catalog. Products are associated with a brand via a
 * many-to-one relationship. Supports soft delete via the {@code active} flag.
 */
@Entity
@Table(name = "brands")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Brand extends BaseEntity {

  @Column(nullable = false, unique = true)
  private String name;

  private String logoUrl;

  private String description;

  @Builder.Default private boolean active = true;
}
