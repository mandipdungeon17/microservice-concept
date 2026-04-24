package com.equitycart.commons.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ValidationErrorResponse(
        int status,
        String error,
        String message,
        LocalDateTime timeStamp,
        List<FieldError> fieldErrors
){
    public record FieldError(
            String field,
            String errorMessage
    ) {}
}
