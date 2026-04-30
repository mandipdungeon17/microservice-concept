package com.equitycart.user.dto;

/**
 * Immutable data carrier used during application startup to seed default roles into the database.
 *
 * @param name the role name (e.g., "CUSTOMER", "ADMIN")
 * @param description a brief description of the role's purpose
 */
public record RoleSeedData(String name, String description) {}
