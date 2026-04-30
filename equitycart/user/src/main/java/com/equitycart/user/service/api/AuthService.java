package com.equitycart.user.service.api;

import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;

/**
 * Defines the authentication contract for user registration, login, and token refresh operations.
 */
public interface AuthService {

  /**
   * Registers a new user with the CUSTOMER role and creates a wallet account.
   *
   * @param request registration details (email, password)
   * @return authentication response with access and refresh tokens
   * @throws com.equitycart.commons.exception.DuplicateResourceException if email already exists
   */
  AuthResponse register(RegisterRequest request);

  /**
   * Authenticates a user with email and password credentials.
   *
   * @param request login credentials (email, password)
   * @return authentication response with access and refresh tokens
   * @throws com.equitycart.commons.exception.AuthenticationException if credentials are invalid
   * @throws com.equitycart.commons.exception.AccountDisabledException if the account is locked or
   *     disabled
   */
  AuthResponse login(LoginRequest request);

  /**
   * Rotates a refresh token -- revokes the old one and issues a new token pair.
   *
   * @param request the refresh token to rotate
   * @return authentication response with new access and refresh tokens
   * @throws com.equitycart.commons.exception.AuthenticationException if the token is invalid,
   *     revoked, or expired
   */
  AuthResponse refreshToken(RefreshRequest request);
}
