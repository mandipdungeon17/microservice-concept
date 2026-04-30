package com.equitycart.user.repository;

import com.equitycart.user.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Role} entities. Provides CRUD operations and custom queries
 * for role lookup by name.
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

  /**
   * Finds a role by its unique name.
   *
   * @param name the role name (e.g., "CUSTOMER", "ADMIN")
   * @return an {@link Optional} containing the role if found
   */
  Optional<Role> findByName(String name);

  /**
   * Checks whether a role with the given name already exists.
   *
   * @param name the role name to check
   * @return {@code true} if a role with the name exists
   */
  boolean existsByName(String name);
}
