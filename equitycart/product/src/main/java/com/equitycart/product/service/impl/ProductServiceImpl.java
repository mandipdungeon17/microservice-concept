package com.equitycart.product.service.impl;

import com.equitycart.commons.dto.PagedResponse;
import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.ProductRequest;
import com.equitycart.product.dto.ProductResponse;
import com.equitycart.product.dto.ProductSearchRequest;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.entity.Category;
import com.equitycart.product.entity.Product;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.repository.CategoryRepository;
import com.equitycart.product.repository.ProductRepository;
import com.equitycart.product.service.api.ProductService;
import com.equitycart.product.specification.ProductSpecification;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ProductService} that handles product CRUD and search operations.
 * Validates brand/category existence and SKU uniqueness before persisting changes.
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

  private static final Logger log = LogManager.getLogger(ProductServiceImpl.class);

  private final ProductRepository productRepository;
  private final CategoryRepository categoryRepository;
  private final BrandRepository brandRepository;

  /** {@inheritDoc} */
  @Transactional
  @Override
  public ProductResponse createProduct(ProductRequest request) {
    log.info("Creating product with SKU: {}", request.sku());
    if (productRepository.existsBySku(request.sku()))
      throw new DuplicateResourceException("SKU already exist");

    Category category =
        categoryRepository
            .findById(request.categoryId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Category not found with category id " + request.categoryId()));

    Brand brand =
        brandRepository
            .findById(request.brandId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Brand not found with brand id " + request.brandId()));

    Product product =
        Product.builder()
            .name(request.name())
            .description(request.description())
            .sku(request.sku())
            .price(request.price())
            .stockQuantity(request.stockQuantity())
            .imageUrl(request.imageUrl())
            .category(category)
            .brand(brand)
            .build();

    Product savedProduct = productRepository.save(product);
    log.info("Product created successfully with id: {}", savedProduct.getId());

    return toResponse(savedProduct);
  }

  /** {@inheritDoc} */
  @Override
  public ProductResponse getProductById(Long productId) {
    log.debug("Fetching product by id: {}", productId);
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Product not found with id: " + productId));

    return toResponse(product);
  }

  /** {@inheritDoc} */
  @Transactional
  @Override
  public ProductResponse updateProduct(Long productId, ProductRequest request) {
    log.info("Updating product with id: {}", productId);
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Product not found with id: " + productId));

    Category category =
        categoryRepository
            .findById(request.categoryId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Category not found with category id " + request.categoryId()));

    Brand brand =
        brandRepository
            .findById(request.brandId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Brand not found with brand id " + request.brandId()));

    Optional<Product> optionalProduct = productRepository.findBySku(request.sku());

    if (optionalProduct.isPresent() && !optionalProduct.get().getId().equals(productId))
      throw new DuplicateResourceException("SKU already exist with sku: " + request.sku());

    product.setName(request.name());
    product.setSku(request.sku());
    product.setPrice(request.price());
    product.setCategory(category);
    product.setBrand(brand);
    product.setDescription(request.description());
    product.setStockQuantity(request.stockQuantity());
    product.setImageUrl(request.imageUrl());

    Product updatedProduct = productRepository.save(product);
    log.info("Product updated successfully with id: {}", updatedProduct.getId());

    return toResponse(updatedProduct);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteProduct(Long productId) {
    log.info("Soft-deleting product with id: {}", productId);
    Product product =
        productRepository
            .findById(productId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Product not found with id: " + productId));

    product.setActive(false);

    productRepository.save(product);
    log.info("Product soft-deleted successfully with id: {}", productId);
  }

  /** {@inheritDoc} */
  @Override
  public PagedResponse<ProductResponse> searchProduct(
      ProductSearchRequest request, Pageable pageable) {
    log.debug(
        "Searching products with filters - name: {}, brandId: {}, categoryId: {}",
        request.name(),
        request.brandId(),
        request.categoryId());
    Specification<Product> spec =
        Specification.allOf(
            ProductSpecification.hasName(request.name()),
            ProductSpecification.hasBrandId(request.brandId()),
            ProductSpecification.hasCategoryId(request.categoryId()),
            ProductSpecification.hasMinPrice(request.minPrice()),
            ProductSpecification.hasMaxPrice(request.maxPrice()),
            ProductSpecification.isActive(request.active()));

    Page<Product> productPage = productRepository.findAll(spec, pageable);
    Page<ProductResponse> responsePage = productPage.map(this::toResponse);
    log.info("Product search returned {} results", productPage.getTotalElements());

    return PagedResponse.from(responsePage);
  }

  /**
   * Converts a {@link Product} entity to a {@link ProductResponse} DTO.
   *
   * @param product the product entity
   * @return the product response DTO
   */
  private ProductResponse toResponse(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getName(),
        product.getDescription(),
        product.getSku(),
        product.getPrice(),
        product.getStockQuantity(),
        product.getImageUrl(),
        product.getCategory().getId(),
        product.getCategory().getName(),
        product.getBrand().getId(),
        product.getBrand().getName(),
        product.isActive());
  }
}
