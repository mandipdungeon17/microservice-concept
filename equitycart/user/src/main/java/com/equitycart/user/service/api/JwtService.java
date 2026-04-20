package com.equitycart.user.service.api;

import com.equitycart.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;

import java.util.List;

public interface JwtService {
    String generateAccessToken(User user, List<String> roles);

    String generateRefreshToken();

    Jws<Claims> extractAllClaims(String token);

    Long extractUserId(String token);

    List<String> extractRoles(String token);

    boolean validateToken(String token);
}
