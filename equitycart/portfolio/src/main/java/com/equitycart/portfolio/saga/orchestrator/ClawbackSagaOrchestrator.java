package com.equitycart.portfolio.saga.orchestrator;

import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.commons.exception.InsufficientSharesException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.repository.HoldingRepository;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.saga.entity.ClawbackSaga;
import com.equitycart.portfolio.saga.enums.ClawbackStatus;
import com.equitycart.portfolio.saga.event.ClawbackOutboxWriter;
import com.equitycart.portfolio.saga.repository.ClawbackSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the clawback saga for returned orders whose stock-back rewards have already vested.
 *
 * <p>Why this exists: PENDING rewards can be cancelled directly, but VESTED rewards already changed
 * holdings and ledger state. Reversal must therefore run as a compensatable multi-step workflow.
 *
 * <p>Execution model: intentionally step-wise (not one large transaction). Each step persists saga
 * status so timeout recovery can resume/compensate from the last durable point.
 */
@Component
@RequiredArgsConstructor
public class ClawbackSagaOrchestrator {

  private static final Logger log = LogManager.getLogger(ClawbackSagaOrchestrator.class);

  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;
  private final StockBackRewardRepository stockBackRewardRepository;
  private final ClawbackSagaRepository clawbackSagaRepository;
  private final ClawbackOutboxWriter clawbackOutboxWriter;
  private final PortfolioEventStore portfolioEventStore;
  private final HoldingRepository holdingRepository;

  @Value("${equitycart.saga.timeout-seconds:30}")
  private long timeoutSeconds;

  /**
   * Entry-point called by return-event consumer for VESTED rewards.
   *
   * <p>Performs idempotency check (active saga for same order+reward) before creating a new saga.
   */
  public void handleOrderReturned(OrderReturnedEvent event, StockBackReward reward) {
    Optional<ClawbackSaga> existing =
        clawbackSagaRepository.findByOrderIdAndRewardIdAndStatusNotIn(
            event.getOrderId(), reward.getId(), ClawbackStatus.getTerminalStatuses());

    if (existing.isPresent()) {
      log.info(
          "Existing active clawback saga found. orderId={}, rewardId={}, sagaId={}, status={}",
          event.getOrderId(),
          reward.getId(),
          existing.get().getSagaId(),
          existing.get().getStatus());
      return;
    }

    ClawbackSaga saga =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(event.getUserId())
            .orderId(event.getOrderId())
            .rewardId(reward.getId())
            .rewardQuantity(reward.getSharesEarned())
            .status(ClawbackStatus.INITIATED)
            .clawbackStartedAt(LocalDateTime.now())
            .build();

    ClawbackSaga savedSaga = clawbackSagaRepository.save(saga);
    clawbackOutboxWriter.writeClawbackLifeCycleEvent(savedSaga, "CLAWBACK_STARTED", null);
    log.info(
        "Clawback saga created: sagaId={}, orderId={}, rewardId={}, userId={}, ticker={}, qty={}",
        savedSaga.getSagaId(),
        savedSaga.getOrderId(),
        savedSaga.getRewardId(),
        savedSaga.getUserId(),
        reward.getTickerSymbol(),
        reward.getSharesEarned());

    executeSaga(savedSaga);
  }

  /** Drives the saga through eligibility and 3 mutation steps, compensating on failure. */
  private void executeSaga(ClawbackSaga saga) {
    log.debug(
        "Executing clawback saga: sagaId={}, orderId={}, rewardId={}, currentStatus={}",
        saga.getSagaId(),
        saga.getOrderId(),
        saga.getRewardId(),
        saga.getStatus());

    StockBackReward reward;
    try {
      reward = checkEligibility(saga);
    } catch (Exception e) {
      saga.setStatus(ClawbackStatus.FAILED);
      saga.setFailureReason("Eligibility check failed: " + e.getMessage());
      ClawbackSaga failed = clawbackSagaRepository.save(saga);
      clawbackOutboxWriter.writeClawbackLifeCycleEvent(
          failed, "CLAWBACK_FAILED", "checkEligibility");
      throw new RuntimeException(e);
    }

    try {
      executeStep1ReduceHolding(saga, reward);
    } catch (Exception e) {
      saga.setFailureReason(e.getMessage());
      compensate(saga, 0, reward);
      throw new RuntimeException(e);
    }

    try {
      executeStep2RecordLedger(saga, reward);
    } catch (Exception e) {
      saga.setFailureReason(e.getMessage());
      compensate(saga, 1, reward);
      throw new RuntimeException(e);
    }

    try {
      executeStep3UpdateRewardStatus(saga, reward);
    } catch (Exception e) {
      saga.setFailureReason(e.getMessage());
      compensate(saga, 2, reward);
      throw new RuntimeException(e);
    }

    log.info(
        "Clawback saga completed: sagaId={}, orderId={}, rewardId={}",
        saga.getSagaId(),
        saga.getOrderId(),
        saga.getRewardId());
  }

  /**
   * Validates invariants before first irreversible action:
   *
   * <ul>
   *   <li>reward belongs to same user/order
   *   <li>reward is currently VESTED
   *   <li>holding exists and has enough quantity for clawback
   * </ul>
   */
  private StockBackReward checkEligibility(ClawbackSaga saga) {
    saga.setStatus(ClawbackStatus.CHECKING_ELIGIBILITY);
    clawbackSagaRepository.save(saga);

    StockBackReward reward = getRewardOrThrow(saga.getRewardId());

    if (!reward.getUserId().equals(saga.getUserId())) {
      throw new IllegalStateException("Reward userId mismatch with saga userId");
    }

    if (!reward.getOrderId().equals(saga.getOrderId())) {
      throw new IllegalStateException("Reward orderId mismatch with saga orderId");
    }

    if (!reward.getStatus().equals(VestingStatus.VESTED)) {
      throw new IllegalStateException(
          "Reward is not VESTED. Current status="
              + reward.getStatus()
              + ", rewardId="
              + reward.getId());
    }

    // verify holding sufficiency before mutating state
    Portfolio portfolio = portfolioService.getOrCreatePortfolio(saga.getUserId());

    Holding holding =
        holdingRepository
            .findByPortfolioAndTickerSymbol(portfolio, reward.getTickerSymbol())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "No holding found for ticker "
                            + reward.getTickerSymbol()
                            + " for userId "
                            + saga.getUserId()));

    if (holding.getQuantity().compareTo(saga.getRewardQuantity()) < 0) {
      throw new InsufficientSharesException(
          "Insufficient shares for clawback. Required="
              + saga.getRewardQuantity()
              + ", current="
              + holding.getQuantity());
    }
    log.info(
        "Clawback eligibility passed: sagaId={}, rewardId={}, ticker={}, qty={}",
        saga.getSagaId(),
        reward.getId(),
        reward.getTickerSymbol(),
        saga.getRewardQuantity());

    return reward;
  }

  /** Step 1: remove previously vested reward shares from holding. */
  private void executeStep1ReduceHolding(ClawbackSaga saga, StockBackReward reward) {
    log.debug(
        "Clawback step 1 start: sagaId={}, userId={}, ticker={}, qty={}",
        saga.getSagaId(),
        saga.getUserId(),
        reward.getTickerSymbol(),
        saga.getRewardQuantity());

    saga.setStatus(ClawbackStatus.REDUCING_HOLDING);
    clawbackSagaRepository.save(saga);

    portfolioService.reduceHolding(
        saga.getUserId(), reward.getTickerSymbol(), saga.getRewardQuantity());

    saga.setStatus(ClawbackStatus.HOLDING_REDUCED);
    ClawbackSaga savedSaga = clawbackSagaRepository.save(saga);

    clawbackOutboxWriter.writeClawbackLifeCycleEvent(
        savedSaga, "CLAWBACK_STEP_COMPLETED", "REDUCE_HOLDING");

    log.info(
        "Clawback step 1 completed: sagaId={}, ticker={}, qty={}",
        saga.getSagaId(),
        reward.getTickerSymbol(),
        saga.getRewardQuantity());
  }

  /** Step 2: record accounting reversal for the vested reward value. */
  private void executeStep2RecordLedger(ClawbackSaga saga, StockBackReward reward) {
    log.debug(
        "Clawback step 2 start: sagaId={}, rewardId={}, amount={}",
        saga.getSagaId(),
        reward.getId(),
        reward.getDollarValue());

    saga.setStatus(ClawbackStatus.RECORDING_LEDGER);
    clawbackSagaRepository.save(saga);

    ledgerService.recordTransaction(
        AccountType.STOCK_BACK,
        AccountType.HOLDING_ASSET,
        reward.getDollarValue(),
        ReferenceType.REWARD_CLAWBACK,
        reward.getId(),
        "CLAWBACK: reverse vested reward shares for rewardId=" + reward.getId());

    saga.setStatus(ClawbackStatus.LEDGER_RECORDED);
    ClawbackSaga savedSaga = clawbackSagaRepository.save(saga);

    clawbackOutboxWriter.writeClawbackLifeCycleEvent(
        savedSaga, "CLAWBACK_STEP_COMPLETED", "RECORD_LEDGER");

    log.info(
        "Clawback step 2 completed: sagaId={}, rewardId={}, ledgerAmount={}",
        saga.getSagaId(),
        reward.getId(),
        reward.getDollarValue());
  }

  /** Step 3: finalize reward lifecycle by marking it CLAWED_BACK. */
  private void executeStep3UpdateRewardStatus(ClawbackSaga saga, StockBackReward reward) {
    log.debug(
        "Clawback step 3 start: sagaId={}, rewardId={}, currentRewardStatus={}",
        saga.getSagaId(),
        reward.getId(),
        reward.getStatus());

    saga.setStatus(ClawbackStatus.UPDATING_REWARD_STATUS);
    clawbackSagaRepository.save(saga);

    reward.setStatus(VestingStatus.CLAWED_BACK);
    stockBackRewardRepository.save(reward);

    saga.setStatus(ClawbackStatus.COMPLETED);
    ClawbackSaga savedSaga = clawbackSagaRepository.save(saga);

    portfolioEventStore.append(
        saga.getUserId(),
        PortfolioEventType.REWARD_CLAWED_BACK,
        reward.getTickerSymbol(),
        saga.getRewardQuantity(),
        BigDecimal.ZERO,
        reward.getDollarValue(),
        Map.of(
            "orderId", saga.getOrderId(),
            "rewardId", reward.getId(),
            "sagaId", saga.getSagaId().toString()));

    clawbackOutboxWriter.writeClawbackLifeCycleEvent(
        savedSaga, "CLAWBACK_COMPLETED", "UPDATE_REWARD_STATUS");

    log.info(
        "Clawback step 3 completed: sagaId={}, rewardId={}, marked CLAWED_BACK",
        saga.getSagaId(),
        reward.getId());
  }

  /**
   * Compensation reverses already-completed steps in reverse order.
   *
   * @param completedSteps 0=no irreversible step completed, 1=holding reduced, 2=ledger recorded
   */
  private void compensate(ClawbackSaga saga, int completedSteps, StockBackReward reward) {
    log.warn(
        "Compensation started: sagaId={}, orderId={}, rewardId={}, completedSteps={}, reason={}",
        saga.getSagaId(),
        saga.getOrderId(),
        saga.getRewardId(),
        completedSteps,
        saga.getFailureReason());

    saga.setStatus(ClawbackStatus.COMPENSATING);
    saga.setCompensationStartedAt(LocalDateTime.now());
    clawbackSagaRepository.save(saga);

    clawbackOutboxWriter.writeClawbackLifeCycleEvent(saga, "CLAWBACK_COMPENSATING", null);

    try {
      if (completedSteps >= 2) {
        ledgerService.recordTransaction(
            AccountType.HOLDING_ASSET,
            AccountType.STOCK_BACK,
            reward.getDollarValue(),
            ReferenceType.REWARD_CLAWBACK_REVERSAL,
            reward.getId(),
            "CLAWBACK COMPENSATION: reverse clawback ledger for rewardId=" + reward.getId());
        log.info("Clawback compensation: reversed ledger for sagaId={}", saga.getSagaId());
      }

      if (completedSteps >= 1) {
        portfolioService.addOrUpdateHolding(
            saga.getUserId(), reward.getTickerSymbol(), saga.getRewardQuantity(), BigDecimal.ZERO);
        log.info("Clawback compensation: restored holding for sagaId={}", saga.getSagaId());
      }

      if (reward.getStatus() == VestingStatus.CLAWED_BACK) {
        reward.setStatus(VestingStatus.VESTED);
        stockBackRewardRepository.save(reward);
        log.info(
            "Clawback compensation: restored reward status to VESTED for sagaId={}",
            saga.getSagaId());
      }

      saga.setStatus(ClawbackStatus.COMPENSATED);
      ClawbackSaga compensatedSaga = clawbackSagaRepository.save(saga);

      portfolioEventStore.append(
          saga.getUserId(),
          PortfolioEventType.REWARD_CLAWBACK_COMPENSATED,
          reward.getTickerSymbol(),
          saga.getRewardQuantity(),
          BigDecimal.ZERO,
          reward.getDollarValue(),
          Map.of(
              "orderId", saga.getOrderId(),
              "rewardId", reward.getId(),
              "sagaId", saga.getSagaId().toString(),
              "reason", saga.getFailureReason()));

      clawbackOutboxWriter.writeClawbackLifeCycleEvent(
          compensatedSaga, "CLAWBACK_COMPENSATED", null);
      log.info(
          "Compensation completed: sagaId={}, orderId={}, rewardId={}",
          saga.getSagaId(),
          saga.getOrderId(),
          saga.getRewardId());

    } catch (Exception e) {
      saga.setStatus(ClawbackStatus.FAILED);
      saga.setFailureReason("Compensation failed: " + e.getMessage());
      ClawbackSaga failedCompensationSaga = clawbackSagaRepository.save(saga);
      clawbackOutboxWriter.writeClawbackLifeCycleEvent(
          failedCompensationSaga, "CLAWBACK_FAILED", null);
      log.error(
          "CRITICAL: Clawback compensation failed. sagaId={}, orderId={}, rewardId={}",
          saga.getSagaId(),
          saga.getOrderId(),
          saga.getRewardId(),
          e);
    }
  }

  /** Scheduled recovery for sagas stuck in non-terminal states beyond configured timeout. */
  @Scheduled(fixedRate = 300000) // every 5 minutes
  @Transactional
  public void detectTimeouts() {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeoutSeconds);

    clawbackSagaRepository
        .findByStatusNotInAndUpdatedAtBefore(ClawbackStatus.getTerminalStatuses(), cutoff)
        .forEach(
            saga -> {
              log.warn(
                  "Timed-out saga detected: sagaId={}, status={}, orderId={}, rewardId={}",
                  saga.getSagaId(),
                  saga.getStatus(),
                  saga.getOrderId(),
                  saga.getRewardId());

              int completedSteps = 0;

              if (List.of(ClawbackStatus.HOLDING_REDUCED, ClawbackStatus.RECORDING_LEDGER)
                  .contains(saga.getStatus())) {
                completedSteps = 1;
              } else if (List.of(
                      ClawbackStatus.LEDGER_RECORDED, ClawbackStatus.UPDATING_REWARD_STATUS)
                  .contains(saga.getStatus())) {
                completedSteps = 2;
              }

              if (completedSteps > 0) {
                compensate(saga, completedSteps, getRewardOrThrow(saga.getRewardId()));
              } else {
                saga.setStatus(ClawbackStatus.FAILED);
                saga.setFailureReason("Timed out before first irreversible step");
                ClawbackSaga failedSaga = clawbackSagaRepository.save(saga);
                clawbackOutboxWriter.writeClawbackLifeCycleEvent(
                    failedSaga, "CLAWBACK_FAILED", "TIMEOUT");
                log.warn(
                    "Clawback saga timed out without completing any steps: sagaId={}, status={}, orderId={}, rewardId={}",
                    saga.getSagaId(),
                    saga.getStatus(),
                    saga.getOrderId(),
                    saga.getRewardId());
              }
            });
  }

  /** Loads reward by id or fails fast with explicit not-found exception. */
  private StockBackReward getRewardOrThrow(Long rewardId) {
    return stockBackRewardRepository
        .findById(rewardId)
        .orElseThrow(() -> new ResourceNotFoundException("Reward not found for id: " + rewardId));
  }
}
