package com.equitycart.portfolio.alerts.service;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.portfolio.alerts.dtos.AlertAuditLogResponse;
import com.equitycart.portfolio.alerts.dtos.CreatePriceAlertRequest;
import com.equitycart.portfolio.alerts.dtos.PriceAlertResponse;
import com.equitycart.portfolio.alerts.dtos.UpdatePriceAlertRequest;
import com.equitycart.portfolio.alerts.entity.AlertAuditLog;
import com.equitycart.portfolio.alerts.entity.PriceAlert;
import com.equitycart.portfolio.alerts.enums.AlertEventType;
import com.equitycart.portfolio.alerts.repository.AlertAuditLogRepository;
import com.equitycart.portfolio.alerts.repository.PriceAlertRepository;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Business logic for managing price-alert rules: create, list, update, deactivate, and history.
 *
 * <p>Responsibilities: threshold validation (via {@link AlertConditionEvaluator}), duplicate
 * prevention (409 on an identical active rule), per-user active quota enforcement, ownership-scoped
 * reads/writes, soft-delete (deactivate rather than hard delete), and audit-trail writes. The
 * scheduled evaluation/notification path lives in {@link AlertEvaluationService}.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PriceAlertService {

  private static final Logger log = LogManager.getLogger(PriceAlertService.class);

  // Configuration for business rules
  private static final int MAX_ACTIVE_ALERTS_PER_USER = 50;
  private static final int DEFAULT_COOLDOWN_MINUTES = 60;

  private final PriceAlertRepository alertRepository;
  private final AlertAuditLogRepository auditLogRepository;
  private final AlertConditionEvaluator evaluator;

  /**
   * Creates a new price alert for a user.
   *
   * <p>Steps: (1) build the entity and validate thresholds via {@link AlertConditionEvaluator}; (2)
   * reject duplicates (same user + ticker + condition + threshold1); (3) enforce the per-user
   * active quota; (4) persist; (5) write a {@code CREATED} audit row.
   *
   * @param userId owner of the alert (from the JWT principal)
   * @param request validated creation payload
   * @return the created alert as a response DTO
   * @throws DuplicateResourceException if an identical active alert already exists
   * @throws IllegalArgumentException if thresholds are invalid or the quota is exceeded
   */
  public PriceAlertResponse createAlert(Long userId, CreatePriceAlertRequest request) {
    log.debug(
        "Creating alert for userId={}, ticker={}, condition={}",
        userId,
        request.tickerSymbol(),
        request.condition());

    String ticker = request.tickerSymbol().toUpperCase();

    // 1. Validate thresholds via evaluator
    int cooldownMinutes =
        request.cooldownMinutes() != null ? request.cooldownMinutes() : DEFAULT_COOLDOWN_MINUTES;

    PriceAlert alert =
        PriceAlert.builder()
            .userId(userId)
            .tickerSymbol(ticker)
            .condition(request.condition())
            .threshold1(request.threshold1())
            .threshold2(request.threshold2())
            .cooldownMinutes(cooldownMinutes)
            .active(true)
            .build();

    evaluator.validateThresholds(alert);

    // 2. Check for duplicate alert
    boolean duplicate =
        alertRepository.existsByUserIdAndTickerSymbolAndConditionAndThreshold1(
            userId,
            request.tickerSymbol().toUpperCase(),
            request.condition(),
            request.threshold1());
    if (duplicate) {
      log.warn(
          "Duplicate alert request: userId={}, ticker={}, condition={}",
          userId,
          request.tickerSymbol(),
          request.condition());
      throw new DuplicateResourceException("An identical alert already exists for this ticker");
    }

    // 3. Check quota
    long userAlertCount = alertRepository.countByUserIdAndActiveTrue(userId);
    if (userAlertCount >= MAX_ACTIVE_ALERTS_PER_USER) {
      log.warn(
          "User quota exceeded: userId={}, count={}, max={}",
          userId,
          userAlertCount,
          MAX_ACTIVE_ALERTS_PER_USER);
      throw new IllegalArgumentException(
          "Maximum " + MAX_ACTIVE_ALERTS_PER_USER + " alerts per user");
    }

    // 4. Saved entity
    PriceAlert saved = alertRepository.save(alert);

    // 5. Record audit log
    writeAudit(saved, AlertEventType.CREATED, null, "Alert created");

    log.info("Alert created: id={}, userId={}", saved.getId(), userId);
    return mapToResponse(saved);
  }

  /**
   * Retrieve all active alerts for a user.
   *
   * <p>Used by REST endpoint GET /api/portfolio/alerts Users want to see and manage their alert
   * rules.
   *
   * @param userId user ID
   * @return list of alerts (empty if none)
   */
  public List<PriceAlertResponse> getUserAlerts(Long userId) {
    log.debug("Fetching alerts for userId={}", userId);
    List<PriceAlert> alerts = alertRepository.findByUserIdAndActiveTrue(userId);
    return alerts.stream().map(this::mapToResponse).collect(Collectors.toList());
  }

  /**
   * Updates the mutable fields of an existing alert (thresholds, cooldown, active status).
   *
   * <p>Ticker and condition are immutable — changing either would be a different rule, so callers
   * must delete and recreate instead. Only non-null request fields are applied.
   *
   * @param userId owner performing the update (ownership is enforced)
   * @param alertId id of the alert to update
   * @param request partial update payload
   * @return the updated alert as a response DTO
   * @throws ResourceNotFoundException if the alert does not exist or is not owned by the user
   */
  public PriceAlertResponse updateAlert(
      Long userId, Long alertId, UpdatePriceAlertRequest request) {
    log.debug("Updating alert: userId={}, alertId={}", userId, alertId);

    PriceAlert alert = requireOwnedAlert(userId, alertId);

    // Update allowed fields
    if (request.threshold1() != null) {
      alert.setThreshold1(request.threshold1());
    }
    if (request.threshold2() != null) {
      alert.setThreshold2(request.threshold2());
    }
    if (request.cooldownMinutes() != null) {
      alert.setCooldownMinutes(request.cooldownMinutes());
    }
    if (request.active() != null) {
      alert.setActive(request.active());
    }

    // Validate before saving
    evaluator.validateThresholds(alert);

    // Save
    PriceAlert updated = alertRepository.save(alert);
    log.info("Alert updated: alertId={}, userId={}", alertId, userId);

    return mapToResponse(updated);
  }

  /**
   * Deactivates (soft-deletes) an alert. The row and its audit trail are preserved so the user can
   * reactivate later and history remains available for investigation.
   *
   * @param userId owner performing the deactivation (ownership is enforced)
   * @param alertId id of the alert to deactivate
   * @throws ResourceNotFoundException if the alert does not exist or is not owned by the user
   */
  public void deactivateAlert(Long userId, Long alertId) {
    log.debug("Deactivating alert: userId={}, alertId={}", userId, alertId);

    PriceAlert alert = requireOwnedAlert(userId, alertId);

    alert.setActive(false);
    alertRepository.save(alert);

    // Record audit
    writeAudit(alert, AlertEventType.DEACTIVATED, null, "Alert deactivated by user");
    log.info("Alert deactivated: alertId={}, userId={}", alertId, userId);
  }

  /** Returns the audit history for an alert the user owns. */
  public List<AlertAuditLogResponse> getAlertHistory(Long userId, Long alertId) {
    requireOwnedAlert(userId, alertId);
    return auditLogRepository.findByPriceAlertIdOrderByCreatedAtDesc(alertId).stream()
        .map(
            a ->
                new AlertAuditLogResponse(
                    a.getEventType(), a.getPriceAtEvent(), a.getMessage(), a.getCreatedAt()))
        .toList();
  }

  /** Writes an audit log entry for an alert event. */
  private void writeAudit(PriceAlert alert, AlertEventType type, BigDecimal price, String message) {
    auditLogRepository.save(
        AlertAuditLog.builder()
            .priceAlertId(alert.getId())
            .userId(alert.getUserId())
            .tickerSymbol(alert.getTickerSymbol())
            .eventType(type)
            .priceAtEvent(price)
            .message(message)
            .build());
  }

  private PriceAlert requireOwnedAlert(Long userId, Long alertId) {
    return alertRepository
        .findByIdAndUserId(alertId, userId)
        .orElseThrow(
            () -> {
              log.warn("Alert not found or not owned: id={}, userId={}", alertId, userId);
              return new ResourceNotFoundException("Price alert not found");
            });
  }

  /** Helper: Convert entity to REST response DTO. */
  private PriceAlertResponse mapToResponse(PriceAlert a) {
    return new PriceAlertResponse(
        a.getId(),
        a.getTickerSymbol(),
        a.getCondition(),
        a.getThreshold1(),
        a.getThreshold2(),
        a.getCooldownMinutes(),
        a.getActive(),
        a.getLastTriggeredAt());
  }
}
