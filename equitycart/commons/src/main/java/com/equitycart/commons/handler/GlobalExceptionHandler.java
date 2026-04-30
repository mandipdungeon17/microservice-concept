package com.equitycart.commons.handler;

import com.equitycart.commons.dto.ErrorResponse;
import com.equitycart.commons.dto.ValidationErrorResponse;
import com.equitycart.commons.exception.AccountDisabledException;
import com.equitycart.commons.exception.AuthenticationException;
import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import java.time.LocalDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception handler for all REST controllers. Maps custom exceptions to appropriate
 * HTTP status codes and structured error responses. Catch-all handler prevents stack trace leakage
 * to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  public static final Logger logger = LogManager.getLogger(GlobalExceptionHandler.class);

  /** Handles resource not found (e.g., product, user, category not found by ID). */
  @ExceptionHandler(ResourceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleResourceNotFoundException(ResourceNotFoundException ex) {
    logger.warn("Resource not found: {}", ex.getMessage());
    return new ErrorResponse(
        HttpStatus.NOT_FOUND.value(),
        HttpStatus.NOT_FOUND.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /** Handles duplicate resource creation (e.g., duplicate email, SKU). */
  @ExceptionHandler(DuplicateResourceException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleDuplicateResourceException(DuplicateResourceException ex) {
    logger.warn("Duplicate resource: {}", ex.getMessage());
    return new ErrorResponse(
        HttpStatus.CONFLICT.value(),
        HttpStatus.CONFLICT.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /** Handles authentication failures (invalid credentials, expired token). */
  @ExceptionHandler(AuthenticationException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public ErrorResponse handleAuthenticationException(AuthenticationException ex) {
    logger.warn("Authentication failed: {}", ex.getMessage());
    return new ErrorResponse(
        HttpStatus.UNAUTHORIZED.value(),
        HttpStatus.UNAUTHORIZED.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /** Handles disabled/locked account access attempts. */
  @ExceptionHandler(AccountDisabledException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ErrorResponse handleAccountDisabledException(AccountDisabledException ex) {
    logger.warn("Account disabled: {}", ex.getMessage());
    return new ErrorResponse(
        HttpStatus.FORBIDDEN.value(),
        HttpStatus.FORBIDDEN.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * Handles Bean Validation failures ({@code @Valid} on request DTOs). Returns field-level error
   * details.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ValidationErrorResponse handleMethodArgumentNotValidException(
      MethodArgumentNotValidException ex) {
    logger.warn("An unexpected error occurred: ", ex);
    return new ValidationErrorResponse(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Validation failed",
        LocalDateTime.now(),
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe -> new ValidationErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList());
  }

  /**
   * Catch-all for unexpected exceptions. Returns generic message to prevent stack trace leakage.
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ErrorResponse handleGenericException(Exception ex) {
    logger.error("An unexpected error occurred: ", ex);
    return new ErrorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
        "An unexpected error occurred",
        LocalDateTime.now());
  }
}
