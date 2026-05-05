package com.equitycart.product.repository;

import com.equitycart.product.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link Product} entities. Extends {@link JpaSpecificationExecutor}
 * to support dynamic criteria-based queries.
 */
public interface ProductRepository
    extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

  /**
   * Finds a product by its unique SKU.
   *
   * @param sku the stock-keeping unit identifier
   * @return an {@link Optional} containing the product if found
   */
  Optional<Product> findBySku(String sku);

  /**
   * Checks whether a product with the given SKU already exists.
   *
   * @param sku the stock-keeping unit identifier
   * @return {@code true} if a product with this SKU exists
   */
  boolean existsBySku(String sku);

  /**
   * Retrieves all products belonging to a specific brand.
   *
   * @param brandId the brand identifier
   * @return list of products for the given brand
   */
  List<Product> findByBrandId(Long brandId);

  /**
   * Retrieves all products belonging to a specific category.
   *
   * @param categoryId the category identifier
   * @return list of products for the given category
   */
  List<Product> findByCategoryId(Long categoryId);

  /**
   * Retrieves a product by ID with a pessimistic write lock, blocking concurrent transactions from
   * reading or modifying the same row until this transaction commits.
   *
   * @param productId the product primary key
   * @return an {@link Optional} containing the locked product if found
   */
  @Query("SELECT p FROM Product p WHERE p.id = :productId")
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Product> findByProductId(Long productId);
}
