package com.equitycart.user.service.impl;

import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.repository.RefreshTokenRepository;
import com.equitycart.user.service.api.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserService} that handles user account operations such as logout by
 * revoking active refresh tokens.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private static final Logger LOGGER = LogManager.getLogger(UserServiceImpl.class);

  private final RefreshTokenRepository refreshTokenRepository;

  /** {@inheritDoc} */
  @Override
  @Transactional
  public void logout(Long userId) {
    LOGGER.info("Logging out user id: {}", userId);
    List<RefreshToken> refreshTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
    LOGGER.debug(
        "Found {} active refresh tokens to revoke for user id: {}", refreshTokens.size(), userId);
    refreshTokens.forEach(
        token -> {
          token.setRevoked(true);
        });
    refreshTokenRepository.saveAll(refreshTokens);
    LOGGER.info(
        "User logged out successfully, {} tokens revoked for user id: {}",
        refreshTokens.size(),
        userId);
  }
}
