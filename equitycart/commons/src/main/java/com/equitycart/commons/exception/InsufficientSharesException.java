package com.equitycart.commons.exception;

/**
 * Thrown when a sell or reduction operation requests more shares than the user holds for a given
 * ticker. Mapped to 400 Bad Request by {@link
 * com.equitycart.commons.handler.GlobalExceptionHandler}.
 */
public class InsufficientSharesException extends RuntimeException {

  public InsufficientSharesException(String message) {
    super(message);
  }
}
