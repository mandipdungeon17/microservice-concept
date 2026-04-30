package com.equitycart.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries user login credentials from the client to the server.
 *
 * @param email the user's email address (must be valid and non-blank)
 * @param password the user's password (minimum 8 characters)
 */
public record LoginRequest(
    @NotBlank @Email String email, @NotBlank @Size(min = 8) String password) {}
