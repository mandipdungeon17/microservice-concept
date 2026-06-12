package com.equitycart.product;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Standalone entry point for the Product Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Product catalogue management: CRUD for products, brands, and categories
 *   <li>Brand–ticker mapping: maps brand identities to stock ticker symbols for stock-back rewards
 *   <li>Inventory control: pessimistic-lock stock deduction on order placement; stock restoration
 *       on order return — the lock is owned by this service because the data lives here
 *   <li>Spring Batch: scheduled product data import jobs (e.g., price updates)
 *   <li>Redis caching: recently accessed product data cached to reduce DB reads
 * </ul>
 *
 * <p><b>Why this service exists as a standalone (Phase 10 extraction):</b> Prior to Phase 10,
 * product entities were a shared library dependency pulled into the monolith classpath via {@code
 * implementation project(':product-service')}. This violated the microservice principle of
 * database-per-service — every service that depended on this module ran Hibernate DDL against
 * {@code equitycart_product}, creating hidden coupling. Extracting to a standalone service with
 * HTTP endpoints (via {@code ProductFeignClient} in commons) enforces the boundary: only
 * PRODUCT-SERVICE has write access to its own database.
 *
 * <p><b>Why {@code @EntityScan} is explicit here:</b> {@code Product} and related entities extend
 * {@code BaseEntity} ({@code @MappedSuperclass} at {@code com.equitycart.commons.entity}).
 * {@code @SpringBootApplication} only scans {@code com.equitycart.product.*} by default, missing
 * the superclass definition. Explicit {@code @EntityScan} covering both packages ensures Hibernate
 * registers the full class hierarchy correctly.
 *
 * <p><b>No {@code @ComponentScan} or {@code @EnableJpaRepositories} overrides needed:</b> All
 * repository interfaces ({@code ProductRepository}, {@code BrandRepository}, etc.) and service
 * beans ({@code ProductServiceImpl}, etc.) live within {@code com.equitycart.product.*}, already
 * covered by the default {@code @SpringBootApplication} scan.
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code product-service.yml} from Config Server (port
 *       8888)
 *   <li>HikariCP connects to {@code equitycart_product} PostgreSQL database
 *   <li>Hibernate auto-creates tables: product, brand, category, brand_ticker_mapping
 *   <li>Redis auto-configuration wires cache for product lookups
 *   <li>Spring Batch schema initialised ({@code spring.batch.jdbc.initialize-schema: always}); job
 *       auto-launch disabled ({@code spring.batch.job.enabled: false})
 *   <li>Eureka client registers service as {@code PRODUCT-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code equitycart_product}) — product, brand, category,
 *       brand_ticker_mapping
 *   <li><b>Redis</b> — product cache (reduces read load for catalogue queries)
 * </ul>
 *
 * <p><b>Key Endpoints (consumed via {@code ProductFeignClient} in commons):</b>
 *
 * <ul>
 *   <li>{@code GET /api/products/{id}} — fetch product by ID; response deserialized into {@code
 *       ProductDTO} subset by consuming services
 *   <li>{@code PUT /api/products/{id}/deduct-stock} — pessimistic-lock stock deduction (called by
 *       order-service on order placement)
 *   <li>{@code PUT /api/products/{id}/restore-stock} — stock restoration on order return
 *   <li>{@code GET /api/brand-ticker-mappings/brand/{brandId}} — ticker mappings for a brand
 *       (called by portfolio-service during stock-back reward calculation)
 * </ul>
 *
 * <p><b>Security Note (Phase 7 interim state):</b> Stock deduction and restoration endpoints carry
 * no {@code @PreAuthorize} guards — they rely on network-level isolation (only gateway-routed
 * traffic reaches port 8089). Phase 8 will add service-to-service OAuth2 client credentials.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka: {@code http://localhost:8761} → {@code PRODUCT-SERVICE} registered on port 8089
 *   <li>Actuator: {@code GET http://localhost:8089/actuator/health} → 200 OK
 *   <li>Gateway: {@code GET http://localhost:8080/api/products/{id}} → reaches product-service
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → PostgreSQL → Redis →
 * Product-Service (8089)
 *
 * @see com.equitycart.product.service.impl.ProductServiceImpl
 * @see com.equitycart.product.service.impl.BrandTickerMappingServiceImpl
 * @see com.equitycart.commons.feign.ProductFeignClient
 */
@SpringBootApplication
@EnableDiscoveryClient
@EntityScan(basePackages = {"com.equitycart.product", "com.equitycart.commons"})
public class ProductServiceApplication {

  private static final Logger log = LogManager.getLogger(ProductServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ProductServiceApplication.class, args);
    log.info(
        "Product Service started — listening on port 8089, registered with Eureka as PRODUCT-SERVICE");
  }
}
