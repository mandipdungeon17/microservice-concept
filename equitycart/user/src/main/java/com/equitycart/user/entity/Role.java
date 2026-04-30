package com.equitycart.user.entity;

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
 * Represents an authorization role in the EquityCart system (e.g., CUSTOMER, ADMIN). Roles are
 * assigned to users through the {@link UserRole} join entity to support a many-to-many
 * relationship.
 */
@Entity
@Table(name = "roles")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Role extends BaseEntity {

  @Column(nullable = false, unique = true)
  private String name;

  private String description;
}
