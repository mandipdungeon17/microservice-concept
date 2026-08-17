package com.equitycart.portfolio.alerts.repository;

import com.equitycart.portfolio.alerts.entity.AlertAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for AlertAuditLog entries. Handles event history queries (for debugging, compliance,
 * replay).
 */
@Repository
public interface AlertAuditLogRepository extends JpaRepository<AlertAuditLog, Long> {

  /**
   * Returns the full audit history for one alert, newest event first.
   *
   * @param priceAlertId id of the owning alert
   * @return audit rows ordered by creation time, descending
   */
  List<AlertAuditLog> findByPriceAlertIdOrderByCreatedAtDesc(Long priceAlertId);
}
