package com.equitycart.user.service.api;

/**
 * Defines the contract for user account management operations such as logout and profile
 * management.
 */
public interface UserService {

  /**
   * Logs out a user by revoking all their active refresh tokens.
   *
   * @param userId the ID of the user to log out
   */
  void logout(Long userId);
}
