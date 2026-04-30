package com.equitycart.commons.exception;

/**
 * Thrown when a requested resource does not exist in the database. Mapped to HTTP 404 Not Found by
 * {@link com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
