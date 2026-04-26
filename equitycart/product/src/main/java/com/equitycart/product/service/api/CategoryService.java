package com.equitycart.product.service.api;

import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;

import java.util.List;

public interface CategoryService {

    CategoryResponse createCategory(CategoryRequest categoryRequest);

    CategoryResponse getCategoryById(Long categoryId);

    List<CategoryResponse> getTopLevelCategories();

    List<CategoryResponse> getSubCategories(Long parentCategoryId);
}
