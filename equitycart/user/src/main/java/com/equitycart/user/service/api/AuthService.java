package com.equitycart.user.service.api;

import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    AuthResponse refreshToken(RefreshRequest request);
}
