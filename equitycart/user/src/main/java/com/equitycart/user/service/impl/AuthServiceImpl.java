package com.equitycart.user.service.impl;

import com.equitycart.commons.exception.AccountDisabledException;
import com.equitycart.commons.exception.AuthenticationException;
import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;
import com.equitycart.user.entity.RefreshToken;
import com.equitycart.user.entity.Role;
import com.equitycart.user.entity.User;
import com.equitycart.user.entity.UserRole;
import com.equitycart.user.entity.WalletAccount;
import com.equitycart.user.enums.UserRoles;
import com.equitycart.user.repository.RefreshTokenRepository;
import com.equitycart.user.repository.RoleRepository;
import com.equitycart.user.repository.UserRepository;
import com.equitycart.user.repository.UserRoleRepository;
import com.equitycart.user.repository.WalletAccountRepository;
import com.equitycart.user.service.api.AuthService;
import com.equitycart.user.service.api.JwtService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link AuthService} that handles user registration, login, and refresh-token
 * rotation. Coordinates with {@link JwtService} for token generation and multiple repositories for
 * persistence.
 */
@Service
/* Lombok annotation to generate a constructor with required arguments (final fields)
   This allows for dependency injection of the repositories and password encoder.
   @Autowired is not needed when using constructor injection with Spring,
   and Lombok's @RequiredArgsConstructor simplifies this process.
*/
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private static final Logger log = LogManager.getLogger(AuthServiceImpl.class);

  @Value("${jwt.refresh-token-expiry}")
  private long refreshTokenExpiry;

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final WalletAccountRepository walletAccountRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final RoleRepository roleRepository;
  private final JwtService jwtService;

  /** {@inheritDoc} */
  @Transactional
  @Override
  public AuthResponse register(RegisterRequest request) {
    log.info("Registering new user with email: {}", request.email());
    if (userRepository.existsByEmail(request.email())) {
      log.warn("Registration failed - email already exists: {}", request.email());
      throw new DuplicateResourceException("Email already registered");
    }
    String encodedPassword = passwordEncoder.encode(request.password());
    User user = User.builder().email(request.email()).password(encodedPassword).build();
    User savedUser = userRepository.save(user);
    log.debug("User persisted with id: {}", savedUser.getId());

    Optional<Role> role = roleRepository.findByName(UserRoles.CUSTOMER.name());
    if (role.isEmpty()) throw new ResourceNotFoundException("Default role CUSTOMER not found");

    UserRole userRole = UserRole.builder().user(savedUser).role(role.get()).build();
    userRoleRepository.save(userRole);
    log.debug("Assigned CUSTOMER role to user id: {}", savedUser.getId());

    WalletAccount walletAccount = WalletAccount.builder().user(savedUser).build();
    walletAccountRepository.save(walletAccount);
    log.debug("Wallet account created for user id: {}", savedUser.getId());

    log.info("User registered successfully with id: {}", savedUser.getId());
    return generateAuthAndRefreshTokens(savedUser, List.of(userRole.getRole().getName()));
  }

  /** {@inheritDoc} */
  @Override
  public AuthResponse login(LoginRequest request) {
    log.info("Login attempt for email: {}", request.email());
    Optional<User> optionalUser = userRepository.findByEmail(request.email());
    if (optionalUser.isEmpty()) {
      log.warn("Login failed - user not found for email: {}", request.email());
      throw new AuthenticationException("Invalid email or password");
    }

    User userEntity = optionalUser.get();

    if (!passwordEncoder.matches(request.password(), userEntity.getPassword())) {
      log.warn("Login failed - invalid password for email: {}", request.email());
      throw new AuthenticationException("Invalid email or password");
    }

    if (userEntity.isAccountLocked() || !userEntity.isEnabled()) {
      log.warn("Login failed - account locked or disabled for user id: {}", userEntity.getId());
      throw new AccountDisabledException("Account is locked or disabled");
    }

    List<UserRole> userRoles = userRoleRepository.findByUserId(userEntity.getId());

    if (userRoles.isEmpty()) throw new ResourceNotFoundException("User has no assigned roles");

    List<String> roles = userRoles.stream().map(u -> u.getRole().getName()).toList();

    log.info("User logged in successfully with id: {}", userEntity.getId());
    return generateAuthAndRefreshTokens(userEntity, roles);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public AuthResponse refreshToken(RefreshRequest request) {
    log.info("Refresh token request received");
    Optional<RefreshToken> optionalRefreshToken =
        refreshTokenRepository.findByToken(request.refreshToken());

    if (optionalRefreshToken.isEmpty()) {
      log.warn("Refresh token not found in database");
      throw new AuthenticationException("Invalid refresh token");
    }

    RefreshToken refreshTokenEntity = optionalRefreshToken.get();

    if (refreshTokenEntity.isRevoked()
        || refreshTokenEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
      log.warn(
          "Refresh token revoked or expired for user id: {}", refreshTokenEntity.getUser().getId());
      throw new AuthenticationException("Refresh token has been revoked or expired");
    }

    refreshTokenEntity.setRevoked(true);
    refreshTokenRepository.save(refreshTokenEntity);
    log.debug("Old refresh token revoked for user id: {}", refreshTokenEntity.getUser().getId());

    List<UserRole> userRoles =
        userRoleRepository.findByUserId(refreshTokenEntity.getUser().getId());

    if (userRoles.isEmpty()) throw new ResourceNotFoundException("User has no assigned roles");

    List<String> roles = userRoles.stream().map(u -> u.getRole().getName()).toList();

    log.info("Token refreshed successfully for user id: {}", refreshTokenEntity.getUser().getId());
    return generateAuthAndRefreshTokens(refreshTokenEntity.getUser(), roles);
  }

  /**
   * Generates a new access/refresh token pair and persists the refresh token.
   *
   * @param user the user to generate tokens for
   * @param roles the user's role names to embed in the access token
   * @return authentication response containing both tokens
   */
  private AuthResponse generateAuthAndRefreshTokens(User user, List<String> roles) {
    log.debug("Generating access and refresh tokens for user id: {}", user.getId());
    String accessToken = jwtService.generateAccessToken(user, roles);
    String refreshToken = jwtService.generateRefreshToken();

    RefreshToken refreshTokenEntity =
        RefreshToken.builder()
            .user(user)
            .token(refreshToken)
            .expiresAt(LocalDateTime.now().plusDays(refreshTokenExpiry))
            .build();

    refreshTokenRepository.save(refreshTokenEntity);

    return new AuthResponse(accessToken, refreshToken);
  }
}
