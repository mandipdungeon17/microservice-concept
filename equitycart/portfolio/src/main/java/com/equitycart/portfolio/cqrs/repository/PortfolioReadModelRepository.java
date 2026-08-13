package com.equitycart.portfolio.cqrs.repository;

import com.equitycart.portfolio.cqrs.model.PortfolioReadModel;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for CQRS read model portfolio snapshots. Provides query methods to fetch
 * denormalized portfolio data for fast reads.
 *
 * <p>The read model is updated asynchronously by {@link
 * com.equitycart.portfolio.cqrs.synchronizer.PortfolioReadModelSynchronizer} when projection events
 * arrive from Kafka.
 */
public interface PortfolioReadModelRepository extends MongoRepository<PortfolioReadModel, String> {

  /**
   * Finds the read model snapshot for a specific user.
   *
   * @param userId the user ID to search for
   * @return Optional containing the portfolio read model if found, empty otherwise
   */
  Optional<PortfolioReadModel> findByUserId(Long userId);

  /**
   * Deletes the read model snapshot for a specific user. Used during cleanup or user data removal.
   *
   * @param userId the user ID whose read model should be deleted
   */
  void deleteByUserId(Long userId);
}
