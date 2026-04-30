package com.equitycart.user.repository;

import com.equitycart.user.entity.UserRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserRole} join entities. Provides queries to retrieve the
 * roles assigned to a specific user.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

  /**
   * Retrieves all role assignments for the given user.
   *
   * @param userId the ID of the user
   * @return list of {@link UserRole} entries for the user
   */
  List<UserRole> findByUserId(Long userId);
}
