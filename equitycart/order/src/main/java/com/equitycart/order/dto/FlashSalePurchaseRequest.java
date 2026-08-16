package com.equitycart.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for the direct flash-sale purchase endpoint.
 *
 * @param productId target product to buy under flash-sale flow
 * @param quantity unit count to purchase (must be at least 1)
 * @param idempotencyKey client-supplied deduplication key for retries
 * @param shippingAddress delivery address persisted on the order
 * @param paymentMethod optional payment method label
 */
public record FlashSalePurchaseRequest(
    @NotNull Long productId,
    @NotNull @Min(1) Integer quantity,
    @NotBlank String idempotencyKey,
    @NotBlank String shippingAddress,
    String paymentMethod) {}
