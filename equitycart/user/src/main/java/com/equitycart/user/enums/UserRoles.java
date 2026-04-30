package com.equitycart.user.enums;

import lombok.Getter;

/**
 * Enumeration of the predefined user roles in the EquityCart system. Each constant carries a
 * lowercase description string used during role seeding and display.
 */
@Getter
public enum UserRoles {
  ADMIN("admin"),
  SELLER("seller"),
  CUSTOMER("customer");

  private final String description;

  UserRoles(String description) {
    this.description = description;
  }
}
