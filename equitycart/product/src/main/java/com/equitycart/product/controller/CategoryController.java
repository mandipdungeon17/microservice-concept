package com.equitycart.product.controller;

import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import com.equitycart.product.service.api.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for category management operations. Supports hierarchical category structures.
 * Base path: {@code /api/categories}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/categories")
public class CategoryController {

  private static final Logger log = LogManager.getLogger(CategoryController.class);

  private final CategoryService categoryService;

  /**
   * Creates a new category. Restricted to ADMIN and SELLER roles.
   *
   * @param categoryRequest validated category request body
   * @return the created category with HTTP 201 status
   */
  @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
  @PostMapping
  public ResponseEntity<CategoryResponse> createCategory(
      @Valid @RequestBody CategoryRequest categoryRequest) {
    log.info("POST /api/categories - name: {}", categoryRequest.name());
    CategoryResponse categoryResponse = categoryService.createCategory(categoryRequest);

    return ResponseEntity.status(HttpStatus.CREATED).body(categoryResponse);
  }

  /**
   * Retrieves a category by its identifier.
   *
   * @param id the category identifier
   * @return the category response
   */
  @GetMapping("/{id}")
  public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable("id") Long id) {
    log.info("GET /api/categories/{}", id);
    CategoryResponse categoryResponse = categoryService.getCategoryById(id);

    return ResponseEntity.ok(categoryResponse);
  }

  /**
   * Retrieves all top-level categories (those without a parent).
   *
   * @return list of root category responses
   */
  @GetMapping("/top-level")
  public ResponseEntity<List<CategoryResponse>> getTopLevelCategories() {
    log.info("GET /api/categories/top-level");
    List<CategoryResponse> categoryResponseList = categoryService.getTopLevelCategories();

    return ResponseEntity.ok(categoryResponseList);
  }

  /**
   * Retrieves all subcategories of a given parent category.
   *
   * @param parentId the parent category identifier
   * @return list of child category responses
   */
  @GetMapping("/{parentId}/subcategories")
  public ResponseEntity<List<CategoryResponse>> getSubCategories(
      @PathVariable("parentId") Long parentId) {
    log.info("GET /api/categories/{}/subcategories", parentId);
    List<CategoryResponse> categoryResponseList = categoryService.getSubCategories(parentId);

    return ResponseEntity.ok(categoryResponseList);
  }
}
