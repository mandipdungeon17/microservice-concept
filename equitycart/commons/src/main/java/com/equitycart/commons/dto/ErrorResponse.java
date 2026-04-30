package com.equitycart.commons.dto;

import java.time.LocalDateTime;

/** Standard error response DTO for non-validation errors (404, 401, 403, 409, 500). */
public record ErrorResponse(int status, String error, String message, LocalDateTime timeStamp) {}
