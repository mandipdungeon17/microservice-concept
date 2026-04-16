package com.equitycart.user.service.api;

import com.equitycart.user.dto.AuthResponse;

public interface AuthService {
    AuthResponse register(String email, String password);
    AuthResponse login(String email, String password);
    AuthResponse refreshToken(String refreshToken);
}
