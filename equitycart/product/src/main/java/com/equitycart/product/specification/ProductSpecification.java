package com.equitycart.product.specification;

import com.equitycart.product.entity.Product;
import java.math.BigDecimal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.jpa.domain.Specification;

/**
 * Utility class providing JPA {@link Specification} builders for dynamic product queries. Each
 * method returns an unrestricted specification when its parameter is null, allowing specifications
 * to be safely combined with {@code Specification.allOf()}.
 */
public final class ProductSpecification {

  private static final Logger log = LogManager.getLogger(ProductSpecification.class);

  private ProductSpecification() {}

  /**
   * Builds a case-insensitive LIKE specification on the product name.
   *
   * @param name the partial name to match (nullable)
   * @return the specification, or unrestricted if name is null
   */
  public static Specification<Product> hasName(String name) {
    if (name == null) return Specification.unrestricted();
    log.debug("Building specification: name LIKE '%{}%'", name);
    return (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%");
  }

  /**
   * Builds an equality specification on the product's brand ID.
   *
   * @param brandId the brand identifier to match (nullable)
   * @return the specification, or unrestricted if brandId is null
   */
  public static Specification<Product> hasBrandId(Long brandId) {
    if (brandId == null) return Specification.unrestricted();
    log.debug("Building specification: brandId = {}", brandId);
    return (root, query, cb) -> cb.equal(root.get("brand").get("id"), brandId);
  }

  /**
   * Builds an equality specification on the product's category ID.
   *
   * @param categoryId the category identifier to match (nullable)
   * @return the specification, or unrestricted if categoryId is null
   */
  public static Specification<Product> hasCategoryId(Long categoryId) {
    if (categoryId == null) return Specification.unrestricted();
    log.debug("Building specification: categoryId = {}", categoryId);
    return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
  }

  /**
   * Builds a greater-than-or-equal specification on the product price.
   *
   * @param minPrice the minimum price (inclusive, nullable)
   * @return the specification, or unrestricted if minPrice is null
   */
  public static Specification<Product> hasMinPrice(BigDecimal minPrice) {
    if (minPrice == null) return Specification.unrestricted();
    log.debug("Building specification: price >= {}", minPrice);
    return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice);
  }

  /**
   * Builds a less-than-or-equal specification on the product price.
   *
   * @param maxPrice the maximum price (inclusive, nullable)
   * @return the specification, or unrestricted if maxPrice is null
   */
  public static Specification<Product> hasMaxPrice(BigDecimal maxPrice) {
    if (maxPrice == null) return Specification.unrestricted();
    log.debug("Building specification: price <= {}", maxPrice);
    return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice);
  }

  /**
   * Builds an equality specification on the product's active flag.
   *
   * @param active the active status to match (nullable)
   * @return the specification, or unrestricted if active is null
   */
  public static Specification<Product> isActive(Boolean active) {
    if (active == null) return Specification.unrestricted();
    log.debug("Building specification: active = {}", active);
    return (root, query, cb) -> cb.equal(root.get("active"), active);
  }
}
