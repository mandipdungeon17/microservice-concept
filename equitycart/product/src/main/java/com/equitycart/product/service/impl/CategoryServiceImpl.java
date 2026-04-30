package com.equitycart.product.service.impl;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import com.equitycart.product.entity.Category;
import com.equitycart.product.repository.CategoryRepository;
import com.equitycart.product.service.api.CategoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link CategoryService} that handles category CRUD operations. Supports
 * hierarchical parent-child category relationships.
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

  private static final Logger log = LogManager.getLogger(CategoryServiceImpl.class);

  private final CategoryRepository categoryRepository;

  /** {@inheritDoc} */
  @Override
  public CategoryResponse createCategory(CategoryRequest request) {
    log.info("Creating category with name: {}", request.name());
    if (categoryRepository.existsByNameIgnoreCase(request.name()))
      throw new DuplicateResourceException("Category already exist");

    Category parent = null;

    if (request.parentId() != null) {
      parent =
          categoryRepository
              .findById(request.parentId())
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          "Parent category not found with id: " + request.parentId()));
      log.debug("Resolved parent category id: {}", request.parentId());
    }

    Category category =
        Category.builder()
            .name(request.name())
            .description(request.description())
            .parent(parent)
            .build();

    Category savedCategory = categoryRepository.save(category);
    log.info("Category created successfully with id: {}", savedCategory.getId());

    return toResponse(savedCategory);
  }

  /** {@inheritDoc} */
  @Override
  public CategoryResponse getCategoryById(Long categoryId) {
    log.debug("Fetching category by id: {}", categoryId);
    Category category =
        categoryRepository
            .findById(categoryId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Category not found with id " + categoryId));

    return toResponse(category);
  }

  /** {@inheritDoc} */
  @Override
  public List<CategoryResponse> getTopLevelCategories() {
    log.debug("Fetching top-level categories");
    List<Category> categories = categoryRepository.findByParentIsNull();
    log.info("Retrieved {} top-level categories", categories.size());

    return categories.stream().map(this::toResponse).toList();
  }

  /** {@inheritDoc} */
  @Override
  public List<CategoryResponse> getSubCategories(Long parentCategoryId) {
    log.debug("Fetching subcategories for parent id: {}", parentCategoryId);
    List<Category> categories = categoryRepository.findByParentId(parentCategoryId);
    log.info("Retrieved {} subcategories for parent id: {}", categories.size(), parentCategoryId);

    return categories.stream().map(this::toResponse).toList();
  }

  /**
   * Converts a {@link Category} entity to a {@link CategoryResponse} DTO.
   *
   * @param category the category entity
   * @return the category response DTO
   */
  private CategoryResponse toResponse(Category category) {
    return new CategoryResponse(
        category.getId(),
        category.getName(),
        category.getDescription(),
        category.getParent() == null ? null : category.getParent().getId(),
        category.getParent() == null ? null : category.getParent().getName(),
        category.isActive());
  }
}
