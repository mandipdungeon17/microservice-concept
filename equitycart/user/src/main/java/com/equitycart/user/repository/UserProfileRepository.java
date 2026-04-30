package com.equitycart.user.repository;

import com.equitycart.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserProfile} entities. Provides standard CRUD operations
 * for user profile management.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {}
