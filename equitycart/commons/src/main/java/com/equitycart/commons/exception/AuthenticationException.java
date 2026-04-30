package com.equitycart.commons.exception;

/**
 * Thrown when authentication fails (invalid credentials, expired token). Mapped to HTTP 401
 * Unauthorized by {@link com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class AuthenticationException extends RuntimeException {

  public AuthenticationException(String message) {
    super(message);
  }
}
