package com.equitycart.user.repository;

import com.equitycart.user.entity.RefreshToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link RefreshToken} entities. Supports token lookup, active-token
 * queries, and bulk deletion for logout.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  /**
   * Finds a refresh token by its token string value.
   *
   * @param token the token string
   * @return an {@link Optional} containing the refresh token if found
   */
  Optional<RefreshToken> findByToken(String token);

  /**
   * Retrieves all non-revoked refresh tokens for the given user.
   *
   * @param userId the ID of the user
   * @return list of active (non-revoked) refresh tokens
   */
  List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);

  /**
   * Deletes all refresh tokens belonging to the given user.
   *
   * @param userId the ID of the user whose tokens should be deleted
   */
  void deleteByUserId(Long userId);
}
