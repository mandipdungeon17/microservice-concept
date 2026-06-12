package com.equitycart.commons.dto;

import java.math.BigDecimal;

/**
 * DTO projection used by services that consume PRODUCT-SERVICE via {@code ProductFeignClient}.
 *
 * <p>This is a subset of the full {@code ProductResponse} returned by product-service (which has
 * more fields). Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES=false} silently drops unmapped fields,
 * so each consumer only declares the fields it actually needs — no coupling to the full schema.
 *
 * <p>Fields: {@code id}, {@code name}, {@code price}, {@code stockQuantity}, {@code brandId},
 * {@code active}.
 *
 * @param id internal product identifier
 * @param name display name of the product
 * @param price current unit price
 * @param stockQuantity available inventory count at the time of the Feign call
 * @param brandId foreign key to the brand — used by portfolio-service to look up brand-ticker
 *     mappings for stock-back reward calculation
 * @param active whether the product is available for purchase
 */
public record ProductDTO(
    Long id, String name, BigDecimal price, Integer stockQuantity, Long brandId, boolean active) {}
