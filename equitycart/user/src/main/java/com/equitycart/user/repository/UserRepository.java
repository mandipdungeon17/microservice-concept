package com.equitycart.user.repository;

import com.equitycart.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link User} entities. Provides CRUD operations and custom queries
 * for user lookup by email.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * Finds a user by their email address.
   *
   * @param email the email to search for
   * @return an {@link Optional} containing the user if found
   */
  Optional<User> findByEmail(String email);

  /**
   * Checks whether a user with the given email already exists.
   *
   * @param email the email to check
   * @return {@code true} if a user with the email exists
   */
  boolean existsByEmail(String email);
}
