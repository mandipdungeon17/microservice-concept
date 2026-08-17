package com.equitycart.portfolio.alerts.repository;

import com.equitycart.portfolio.alerts.entity.PriceAlert;
import com.equitycart.portfolio.alerts.enums.AlertCondition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for PriceAlert entity. Handles all database queries for alert rules.
 *
 * <p>Design note: Repositories are thin queries only. Business logic (condition evaluation,
 * cooldown checks) lives in Service layer.
 */
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

  /**
   * Retrieve all active alerts for a specific user. Used when user wants to view their alert rules.
   *
   * <p>Query: SELECT * FROM price_alerts WHERE user_id = ? AND active = true Performance: Uses
   * index idx_user_active
   *
   * @param userId user ID
   * @return list of active alerts (empty list if none)
   */
  List<PriceAlert> findByUserIdAndActiveTrue(Long userId);

  /** Ownership-safe single fetch — avoids returning another user's alert. */
  Optional<PriceAlert> findByIdAndUserId(Long id, Long userId);

  /**
   * Retrieve all active alerts (used for full cycle evaluation). Called every 5 seconds by
   * AlertEvaluationService.
   *
   * <p>Query: SELECT * FROM price_alerts WHERE active = true Performance: Table scan (no useful
   * index); consider pagination in production
   *
   * <p>Warning: Potential hot spot in high-alert-volume scenarios (1M+ users). Future optimization:
   * Partition by ticker, evaluate sharded subset per cycle.
   *
   * @return all active alerts
   */
  List<PriceAlert> findByActiveTrue();

  /**
   * Check if user has alert for this ticker with given condition. Used to prevent duplicate rules
   * (business rule: "one ABOVE-$150 per user per ticker").
   *
   * @param userId user ID
   * @param tickerSymbol ticker
   * @param condition condition type
   * @param threshold1 primary threshold
   * @return true if alert already exists
   */
  boolean existsByUserIdAndTickerSymbolAndConditionAndThreshold1(
      Long userId, String tickerSymbol, AlertCondition condition, BigDecimal threshold1);

  /**
   * Count total active alerts for a user (for quota enforcement). Many brokers limit alerts per
   * user (e.g., max 50 active alerts).
   *
   * @param userId user ID
   * @return count of that user's active alerts
   */
  long countByUserIdAndActiveTrue(Long userId);
}
