package com.equitycart.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.repository.RefreshTokenRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

  @Mock private RefreshTokenRepository refreshTokenRepository;
  @InjectMocks private UserServiceImpl userService;

  @Test
  void logoutShouldRevokeAllActiveTokens() {
    RefreshToken token1 = RefreshToken.builder().revoked(false).build();
    RefreshToken token2 = RefreshToken.builder().revoked(false).build();
    when(refreshTokenRepository.findByUserIdAndRevokedFalse(10L)).thenReturn(List.of(token1, token2));

    userService.logout(10L);

    assertTrue(token1.isRevoked());
    assertTrue(token2.isRevoked());
    verify(refreshTokenRepository).saveAll(anyList());
  }
}

