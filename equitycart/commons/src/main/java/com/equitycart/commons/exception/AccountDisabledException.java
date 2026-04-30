package com.equitycart.commons.exception;

/**
 * Thrown when a user's account is disabled or locked. Mapped to HTTP 403 Forbidden by {@link
 * com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class AccountDisabledException extends RuntimeException {

  public AccountDisabledException(String message) {
    super(message);
  }
}
