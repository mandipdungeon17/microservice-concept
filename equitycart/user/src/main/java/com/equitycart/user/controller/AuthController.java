package com.equitycart.user.controller;

import com.equitycart.user.dto.AuthResponse;
import com.equitycart.user.dto.LoginRequest;
import com.equitycart.user.dto.RefreshRequest;
import com.equitycart.user.dto.RegisterRequest;
import com.equitycart.user.service.api.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing public authentication endpoints for user registration, login, and token
 * refresh. All endpoints are under {@code /api/auth} and are permitted without authentication in
 * {@link com.equitycart.user.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final Logger log = LogManager.getLogger(AuthController.class);

  private final AuthService authService;

  /**
   * Registers a new user and returns access/refresh tokens.
   *
   * @param registerRequest the registration payload
   * @return {@code 201 Created} with the authentication tokens
   */
  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(
      @Valid @RequestBody RegisterRequest registerRequest) {
    log.info("POST /api/auth/register - email: {}", registerRequest.email());
    return new ResponseEntity<>(authService.register(registerRequest), HttpStatus.CREATED);
  }

  /**
   * Authenticates a user and returns access/refresh tokens.
   *
   * @param loginRequest the login credentials
   * @return {@code 200 OK} with the authentication tokens
   */
  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
    log.info("POST /api/auth/login - email: {}", loginRequest.email());
    return new ResponseEntity<>(authService.login(loginRequest), HttpStatus.OK);
  }

  /**
   * Refreshes an expired access token using a valid refresh token.
   *
   * @param refreshRequest the refresh token payload
   * @return {@code 200 OK} with new authentication tokens
   */
  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refreshToken(
      @Valid @RequestBody RefreshRequest refreshRequest) {
    log.info("POST /api/auth/refresh - token refresh requested");
    return new ResponseEntity<>(authService.refreshToken(refreshRequest), HttpStatus.OK);
  }
}
