package com.equitycart.portfolio.repository;

import com.equitycart.portfolio.entity.Portfolio;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link Portfolio} entities. */
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

  /**
   * Finds the portfolio belonging to a specific user.
   *
   * @param userId the owning user's ID
   * @return the portfolio, or empty if the user has no portfolio yet
   */
  Optional<Portfolio> findByUserId(Long userId);
}
