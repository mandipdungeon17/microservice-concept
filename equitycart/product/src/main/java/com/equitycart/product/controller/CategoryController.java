package com.equitycart.product.controller;

import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import com.equitycart.product.service.api.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory (@Valid @RequestBody CategoryRequest categoryRequest) {
        CategoryResponse categoryResponse = categoryService.createCategory(categoryRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(categoryResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable("id")  Long id) {
        CategoryResponse categoryResponse = categoryService.getCategoryById(id);

        return ResponseEntity.ok(categoryResponse);
    }

    @GetMapping("/top-level")
    public ResponseEntity<List<CategoryResponse>> getTopLevelCategories() {
        List<CategoryResponse> categoryResponseList = categoryService.getTopLevelCategories();

        return ResponseEntity.ok(categoryResponseList);
    }

    @GetMapping("/{parentId}/subcategories")
    public ResponseEntity<List<CategoryResponse>> getSubCategories(@PathVariable("parentId")  Long parentId) {
        List<CategoryResponse> categoryResponseList = categoryService.getSubCategories(parentId);

        return ResponseEntity.ok(categoryResponseList);
    }
}
