package com.equitycart.user.dto;

public record AuthResponse (
        String accessToken,
        String refreshToken
) {
}
