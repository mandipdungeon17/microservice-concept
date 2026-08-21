package com.equitycart.product.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.CategoryRequest;
import com.equitycart.product.dto.CategoryResponse;
import com.equitycart.product.entity.Category;
import com.equitycart.product.repository.CategoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

  @Mock private CategoryRepository categoryRepository;
  @InjectMocks private CategoryServiceImpl categoryService;

  @Test
  void createCategoryShouldThrowWhenAlreadyExists() {
    CategoryRequest request = new CategoryRequest("Shoes", "d", null);
    when(categoryRepository.existsByNameIgnoreCase("Shoes")).thenReturn(true);

    assertThrows(DuplicateResourceException.class, () -> categoryService.createCategory(request));
  }

  @Test
  void createCategoryShouldThrowWhenParentNotFound() {
    CategoryRequest request = new CategoryRequest("Shoes", "d", 99L);
    when(categoryRepository.existsByNameIgnoreCase("Shoes")).thenReturn(false);
    when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> categoryService.createCategory(request));
  }

  @Test
  void getTopLevelCategoriesShouldMapResponse() {
    Category category = Category.builder().name("Top").description("d").build();
    category.setId(10L);
    when(categoryRepository.findByParentIsNull()).thenReturn(List.of(category));

    List<CategoryResponse> responses = categoryService.getTopLevelCategories();

    assertEquals(1, responses.size());
    assertEquals("Top", responses.getFirst().name());
  }

  @Test
  void createCategoryShouldCreateWithoutParent() {
    CategoryRequest request = new CategoryRequest("Electronics", "desc", null);
    Category saved = Category.builder().name("Electronics").description("desc").build();
    saved.setId(1L);

    when(categoryRepository.existsByNameIgnoreCase("Electronics")).thenReturn(false);
    when(categoryRepository.save(any(Category.class))).thenReturn(saved);

    CategoryResponse response = categoryService.createCategory(request);

    assertEquals(1L, response.id());
    assertEquals("Electronics", response.name());
    assertEquals(null, response.parentId());
    verify(categoryRepository).save(any(Category.class));
  }

  @Test
  void createCategoryShouldCreateWithParent() {
    Category parent = Category.builder().name("Root").description("root").build();
    parent.setId(99L);
    Category saved = Category.builder().name("Shoes").description("d").parent(parent).build();
    saved.setId(100L);
    CategoryRequest request = new CategoryRequest("Shoes", "d", 99L);

    when(categoryRepository.existsByNameIgnoreCase("Shoes")).thenReturn(false);
    when(categoryRepository.findById(99L)).thenReturn(Optional.of(parent));
    when(categoryRepository.save(any(Category.class))).thenReturn(saved);

    CategoryResponse response = categoryService.createCategory(request);

    assertEquals(99L, response.parentId());
    assertEquals("Root", response.parentName());
  }

  @Test
  void getCategoryByIdShouldThrowWhenMissing() {
    when(categoryRepository.findById(55L)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> categoryService.getCategoryById(55L));
  }

  @Test
  void getSubCategoriesShouldMapChildren() {
    Category parent = Category.builder().name("Root").build();
    parent.setId(2L);
    Category child = Category.builder().name("Child").description("d").parent(parent).build();
    child.setId(3L);
    when(categoryRepository.findByParentId(2L)).thenReturn(List.of(child));

    List<CategoryResponse> responses = categoryService.getSubCategories(2L);

    assertEquals(1, responses.size());
    assertEquals(2L, responses.getFirst().parentId());
    assertEquals("Root", responses.getFirst().parentName());
  }
}
