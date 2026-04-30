package com.equitycart.product.service.api;

import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import java.util.List;

/**
 * Service interface for category management operations. Supports hierarchical category structures
 * with parent-child relationships.
 */
public interface CategoryService {

  /**
   * Creates a new category, optionally nested under a parent category.
   *
   * @param categoryRequest the category details including optional parentId
   * @return the created category response
   * @throws com.equitycart.commons.exception.DuplicateResourceException if category name already
   *     exists
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if parent category not found
   */
  CategoryResponse createCategory(CategoryRequest categoryRequest);

  /**
   * Retrieves a category by its identifier.
   *
   * @param categoryId the category identifier
   * @return the category response
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if no category found
   */
  CategoryResponse getCategoryById(Long categoryId);

  /**
   * Retrieves all top-level categories (those with no parent).
   *
   * @return list of root category responses
   */
  List<CategoryResponse> getTopLevelCategories();

  /**
   * Retrieves all subcategories of a given parent category.
   *
   * @param parentCategoryId the parent category identifier
   * @return list of child category responses
   */
  List<CategoryResponse> getSubCategories(Long parentCategoryId);
}
