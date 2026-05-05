package com.equitycart.commons.exception;

/**
 * Thrown when a product's available stock is less than the requested quantity. Mapped to HTTP 400
 * Bad Request by {@link com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class InsufficientStockException extends RuntimeException {

  public InsufficientStockException(String message) {
    super(message);
  }
}
