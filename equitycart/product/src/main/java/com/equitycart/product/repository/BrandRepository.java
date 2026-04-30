package com.equitycart.product.repository;

import com.equitycart.product.entity.Brand;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Brand} entities. Provides case-insensitive lookup by brand
 * name.
 */
public interface BrandRepository extends JpaRepository<Brand, Long> {

  /**
   * Finds a brand by name, ignoring case.
   *
   * @param name the brand name to search for
   * @return an {@link Optional} containing the brand if found
   */
  Optional<Brand> findByNameIgnoreCase(String name);

  /**
   * Checks whether a brand with the given name already exists (case-insensitive).
   *
   * @param name the brand name
   * @return {@code true} if a brand with this name exists
   */
  boolean existsByNameIgnoreCase(String name);
}
