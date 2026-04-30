package com.equitycart.commons.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Error response DTO for validation failures (HTTP 400). Contains field-level error details via
 * nested {@link FieldError} records.
 */
public record ValidationErrorResponse(
    int status,
    String error,
    String message,
    LocalDateTime timeStamp,
    List<FieldError> fieldErrors) {
  public record FieldError(String field, String errorMessage) {}
}
