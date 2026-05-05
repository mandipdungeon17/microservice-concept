package com.equitycart.commons.exception;

/**
 * Thrown when an order status transition violates the allowed state machine rules. Mapped to HTTP
 * 400 Bad Request by {@link com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class InvalidStatusTransitionException extends RuntimeException {

  public InvalidStatusTransitionException(String message) {
    super(message);
  }
}
