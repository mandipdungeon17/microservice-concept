package com.equitycart.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for the token refresh endpoint.
 *
 * @param refreshToken the refresh token string previously issued to the client
 */
public record RefreshRequest(@NotBlank String refreshToken) {}
