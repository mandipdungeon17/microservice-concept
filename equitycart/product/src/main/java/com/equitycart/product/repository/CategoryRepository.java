package com.equitycart.product.repository;

import com.equitycart.product.entity.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Category} entities. Supports hierarchical queries for
 * parent and child categories.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

  /**
   * Finds a category by name, ignoring case.
   *
   * @param name the category name
   * @return an {@link Optional} containing the category if found
   */
  Optional<Category> findByNameIgnoreCase(String name);

  /**
   * Checks whether a category with the given name already exists (case-insensitive).
   *
   * @param name the category name
   * @return {@code true} if a category with this name exists
   */
  boolean existsByNameIgnoreCase(String name);

  /**
   * Retrieves all top-level categories (those with no parent).
   *
   * @return list of root categories
   */
  List<Category> findByParentIsNull();

  /**
   * Retrieves all subcategories of a given parent category.
   *
   * @param parentId the parent category identifier
   * @return list of child categories
   */
  List<Category> findByParentId(Long parentId);
}
