package com.equitycart.user.dto;

/**
 * Authentication response returned after successful login or token refresh.
 *
 * @param accessToken short-lived JWT used for API authorization
 * @param refreshToken long-lived token used to obtain new access tokens
 */
public record AuthResponse(String accessToken, String refreshToken) {}
