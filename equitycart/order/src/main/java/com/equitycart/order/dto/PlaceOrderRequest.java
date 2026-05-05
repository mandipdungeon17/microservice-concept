package com.equitycart.order.dto;

import jakarta.validation.constraints.NotBlank;

/** Request payload for placing a new order from the user's cart. */
public record PlaceOrderRequest(
    @NotBlank String idempotencyKey, @NotBlank String shippingAddress, String paymentMethod) {}
