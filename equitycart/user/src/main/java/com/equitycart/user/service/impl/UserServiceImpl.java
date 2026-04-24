package com.equitycart.user.service.impl;

import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.repository.RefreshTokenRepository;
import com.equitycart.user.service.api.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger LOGGER = LogManager.getLogger(UserServiceImpl.class);

    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    @Transactional
    public void logout(Long userId) {
          List<RefreshToken> refreshTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
          refreshTokens.forEach(token -> {
              token.setRevoked(true);
          });
          refreshTokenRepository.saveAll(refreshTokens);
    }
}
