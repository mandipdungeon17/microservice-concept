package com.equitycart.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.equitycart.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceImplTest {

  private JwtServiceImpl jwtService;

  @BeforeEach
  void setUp() {
    jwtService = new JwtServiceImpl();
    ReflectionTestUtils.setField(
        jwtService, "secretKey", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    ReflectionTestUtils.setField(jwtService, "accessTokenExpiry", 60_000L);
    ReflectionTestUtils.setField(jwtService, "refreshTokenExpiry", 86_400_000L);
  }

  @Test
  void generateAndParseTokenShouldReturnUserIdAndRoles() {
    User user = User.builder().email("test@equitycart.com").password("enc").build();
    user.setId(99L);

    String token = jwtService.generateAccessToken(user, List.of("CUSTOMER", "ADMIN"));

    assertNotNull(token);
    assertTrue(jwtService.validateToken(token));
    assertEquals(99L, jwtService.extractUserId(token));
    assertEquals(List.of("CUSTOMER", "ADMIN"), jwtService.extractRoles(token));
  }

  @Test
  void validateTokenShouldReturnFalseForInvalidToken() {
    assertFalse(jwtService.validateToken("not-a-jwt"));
  }
}

