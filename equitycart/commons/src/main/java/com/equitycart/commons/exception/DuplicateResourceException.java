package com.equitycart.commons.exception;

/**
 * Thrown when attempting to create a resource that already exists (e.g., duplicate email, SKU).
 * Mapped to HTTP 409 Conflict by {@link com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class DuplicateResourceException extends RuntimeException {

  public DuplicateResourceException(String message) {
    super(message);
  }
}
