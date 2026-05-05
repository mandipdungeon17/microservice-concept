package com.equitycart.order.dto;

import jakarta.validation.constraints.NotBlank;

/** Request payload for transitioning an order to a new status (admin operation). */
public record UpdateOrderStatusRequest(@NotBlank String status) {}
