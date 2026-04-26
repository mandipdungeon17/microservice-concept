package com.equitycart.product.service.impl;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import com.equitycart.product.entity.Category;
import com.equitycart.product.repository.CategoryRepository;
import com.equitycart.product.service.api.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    public CategoryResponse createCategory(CategoryRequest request) {
        if(categoryRepository.existsByNameIgnoreCase(request.name()))
            throw new DuplicateResourceException("Category already exist");

        Category parent = null;

        if(request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId()).orElseThrow(
                    () -> new ResourceNotFoundException("Parent category not found with id: " + request.parentId()));
        }

        Category category = Category.builder()
                .name(request.name())
                .description(request.description())
                .parent(parent)
                .build();

        Category savedCategory = categoryRepository.save(category);

        return toResponse(savedCategory);
    }

    @Override
    public CategoryResponse getCategoryById(Long categoryId) {
        Category category = categoryRepository.findById(categoryId).orElseThrow(
                () -> new ResourceNotFoundException("Category not found with id " + categoryId));

        return toResponse(category);
    }

    @Override
    public List<CategoryResponse> getTopLevelCategories() {
        List<Category> categories = categoryRepository.findByParentIsNull();

        return categories.stream().map(this::toResponse).toList();
    }

    @Override
    public List<CategoryResponse> getSubCategories(Long parentCategoryId) {
        List<Category> categories = categoryRepository.findByParentId(parentCategoryId);

        return categories.stream().map(this::toResponse).toList();
    }

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
