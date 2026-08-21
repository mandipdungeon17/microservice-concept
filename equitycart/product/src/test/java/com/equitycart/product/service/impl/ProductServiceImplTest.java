package com.equitycart.product.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.InsufficientStockException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.entity.Category;
import com.equitycart.product.entity.Product;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.repository.CategoryRepository;
import com.equitycart.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

  @Mock private ProductRepository productRepository;
  @Mock private CategoryRepository categoryRepository;
  @Mock private BrandRepository brandRepository;
  @InjectMocks private ProductServiceImpl productService;

  @Test
  void createProductShouldThrowWhenSkuExists() {
    ProductRequest request =
        new ProductRequest("P", "D", "SKU-1", new BigDecimal("10.00"), 5, "img", 1L, 2L);
    when(productRepository.existsBySku("SKU-1")).thenReturn(true);

    assertThrows(DuplicateResourceException.class, () -> productService.createProduct(request));
  }

  @Test
  void createProductShouldPersistAndReturnResponse() {
    ProductRequest request =
        new ProductRequest("P", "D", "SKU-1", new BigDecimal("10.00"), 5, "img", 1L, 2L);
    Category category = Category.builder().name("C").build();
    category.setId(1L);
    Brand brand = Brand.builder().name("B").build();
    brand.setId(2L);
    Product saved =
        Product.builder()
            .name("P")
            .description("D")
            .sku("SKU-1")
            .price(new BigDecimal("10.00"))
            .stockQuantity(5)
            .imageUrl("img")
            .category(category)
            .brand(brand)
            .build();
    saved.setId(99L);

    when(productRepository.existsBySku("SKU-1")).thenReturn(false);
    when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
    when(brandRepository.findById(2L)).thenReturn(Optional.of(brand));
    when(productRepository.save(any(Product.class))).thenReturn(saved);

    ProductResponse response = productService.createProduct(request);

    assertEquals(99L, response.id());
    assertEquals("SKU-1", response.sku());
  }

  @Test
  void deductStockShouldThrowWhenInsufficientStock() {
    Product product = Product.builder().name("P").stockQuantity(1).build();
    product.setId(5L);
    when(productRepository.findByProductId(5L)).thenReturn(Optional.of(product));

    assertThrows(InsufficientStockException.class, () -> productService.deductStock(5L, 2));
  }

  @Test
  void restoreStockShouldIncreaseQuantityAndSave() {
    Product product = Product.builder().name("P").stockQuantity(3).build();
    product.setId(5L);
    when(productRepository.findByProductId(5L)).thenReturn(Optional.of(product));

    productService.restoreStock(5L, 4);

    assertEquals(7, product.getStockQuantity());
    verify(productRepository).save(product);
  }

  @Test
  void createProductShouldThrowWhenCategoryNotFound() {
    ProductRequest request =
        new ProductRequest("P", "D", "SKU-2", new BigDecimal("10.00"), 5, "img", 1L, 2L);
    when(productRepository.existsBySku("SKU-2")).thenReturn(false);
    when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> productService.createProduct(request));
  }

  @Test
  void createProductShouldThrowWhenBrandNotFound() {
    ProductRequest request =
        new ProductRequest("P", "D", "SKU-3", new BigDecimal("10.00"), 5, "img", 1L, 2L);
    Category category = Category.builder().name("Cat").build();
    category.setId(1L);
    when(productRepository.existsBySku("SKU-3")).thenReturn(false);
    when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
    when(brandRepository.findById(2L)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> productService.createProduct(request));
  }

  @Test
  void getProductByIdShouldThrowWhenMissing() {
    when(productRepository.findById(999L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> productService.getProductById(999L));
  }

  @Test
  void updateProductShouldThrowWhenProductNotFound() {
    ProductRequest request =
        new ProductRequest("P", "D", "SKU-1", new BigDecimal("10.00"), 5, "img", 1L, 2L);
    when(productRepository.findById(100L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> productService.updateProduct(100L, request));
  }

  @Test
  void updateProductShouldThrowWhenSkuBelongsToAnotherProduct() {
    Product existing = Product.builder().name("Old").sku("OLD").build();
    existing.setId(10L);
    Category category = Category.builder().name("Cat").build();
    category.setId(1L);
    Brand brand = Brand.builder().name("Brand").build();
    brand.setId(2L);
    Product another = Product.builder().name("Another").sku("SKU-9").build();
    another.setId(99L);
    ProductRequest request =
        new ProductRequest("New", "D", "SKU-9", new BigDecimal("20.00"), 10, "img", 1L, 2L);

    when(productRepository.findById(10L)).thenReturn(Optional.of(existing));
    when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
    when(brandRepository.findById(2L)).thenReturn(Optional.of(brand));
    when(productRepository.findBySku("SKU-9")).thenReturn(Optional.of(another));

    assertThrows(DuplicateResourceException.class, () -> productService.updateProduct(10L, request));
  }

  @Test
  void updateProductShouldUpdateWhenSkuBelongsToSameProduct() {
    Product existing =
        Product.builder().name("Old").sku("SKU-1").stockQuantity(1).price(new BigDecimal("1"))
            .category(Category.builder().name("OldCat").build())
            .brand(Brand.builder().name("OldBrand").build())
            .build();
    existing.setId(10L);
    Category category = Category.builder().name("Cat").build();
    category.setId(1L);
    Brand brand = Brand.builder().name("Brand").build();
    brand.setId(2L);
    ProductRequest request =
        new ProductRequest("New", "D2", "SKU-1", new BigDecimal("20.00"), 10, "img2", 1L, 2L);

    when(productRepository.findById(10L)).thenReturn(Optional.of(existing));
    when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
    when(brandRepository.findById(2L)).thenReturn(Optional.of(brand));
    when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(existing));
    when(productRepository.save(existing)).thenReturn(existing);

    ProductResponse response = productService.updateProduct(10L, request);

    assertEquals("New", response.name());
    assertEquals("SKU-1", response.sku());
    assertEquals(10, response.stockQuantity());
  }

  @Test
  void deleteProductShouldThrowWhenNotFound() {
    when(productRepository.findById(123L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> productService.deleteProduct(123L));
  }

  @Test
  void deleteProductShouldSoftDelete() {
    Product existing = Product.builder().name("P").active(true).build();
    existing.setId(77L);
    when(productRepository.findById(77L)).thenReturn(Optional.of(existing));

    productService.deleteProduct(77L);

    assertEquals(false, existing.isActive());
    verify(productRepository).save(existing);
  }

  @Test
  void searchProductShouldReturnPagedResponse() {
    Category category = Category.builder().name("Cat").build();
    category.setId(1L);
    Brand brand = Brand.builder().name("Brand").build();
    brand.setId(2L);
    Product p =
        Product.builder()
            .name("P")
            .description("D")
            .sku("SKU")
            .price(new BigDecimal("9.99"))
            .stockQuantity(4)
            .imageUrl("img")
            .category(category)
            .brand(brand)
            .active(true)
            .build();
    p.setId(3L);

    when(productRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 10), 1));

    PagedResponse<ProductResponse> response =
        productService.searchProduct(
            new ProductSearchRequest("P", 2L, 1L, new BigDecimal("1"), new BigDecimal("20"), true),
            PageRequest.of(0, 10));

    assertEquals(1, response.content().size());
    assertEquals(1, response.totalElements());
  }

  @Test
  void deductStockShouldThrowWhenProductNotFound() {
    when(productRepository.findByProductId(500L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> productService.deductStock(500L, 1));
  }

  @Test
  void deductStockShouldReduceQuantityAndSave() {
    Product product = Product.builder().name("P").stockQuantity(5).build();
    product.setId(55L);
    when(productRepository.findByProductId(55L)).thenReturn(Optional.of(product));

    productService.deductStock(55L, 2);

    assertEquals(3, product.getStockQuantity());
    verify(productRepository).save(product);
  }

  @Test
  void restoreStockShouldThrowWhenProductNotFound() {
    when(productRepository.findByProductId(999L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> productService.restoreStock(999L, 2));
  }
}
