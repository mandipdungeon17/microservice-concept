package com.equitycart.portfolio.saga.orchestrator;

import com.equitycart.commons.exception.InsufficientSharesException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
import com.equitycart.portfolio.saga.event.GiftSagaOutboxWriter;
import com.equitycart.portfolio.saga.repository.GiftSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orchestrates stock gifting as a compensatable multi-step saga.
 *
 * <p>Flow:
 *
 * <pre>
 * 1) debit giver holding
 * 2) credit receiver holding
 * 3) record ledger audit
 * </pre>
 *
 * <p>Each step persists a durable saga status so scheduler-based timeout recovery can compensate
 * from the last known point.
 */
@Component
@RequiredArgsConstructor
public class GiftSagaOrchestrator {

  private static final Logger log = LogManager.getLogger(GiftSagaOrchestrator.class);

  private final GiftSagaRepository giftSagaRepository;
  private final GiftSagaOutboxWriter giftSagaOutboxWriter;
  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;

  @Value("${equitycart.saga.timeout-seconds:30}")
  private long timeoutSeconds;

  /**
   * Starts gifting workflow or returns existing saga for duplicate idempotency key.
   *
   * @param giverUserId authenticated giver user id
   * @param request gift transfer request payload
   * @return existing or newly created saga
   */
  public GiftSaga startGift(Long giverUserId, GiftRequest request) {
    return giftSagaRepository
        .findByIdempotencyKey(request.idempotencyKey())
        .map(
            existing -> {
              log.info(
                  "Duplicate gift request idempotency key detected. key={}, sagaId={}, status={}",
                  request.idempotencyKey(),
                  existing.getSagaId(),
                  existing.getStatus());
              return existing;
            })
        .orElseGet(() -> executeNewSaga(giverUserId, request));
  }

  /** Executes saga for a new idempotency key. */
  private GiftSaga executeNewSaga(Long giverUserId, GiftRequest request) {
    Holding giverHolding = validateRequest(giverUserId, request);
    BigDecimal transferPricePerShare = giverHolding.getAverageBuyPrice();
    BigDecimal transferDollarValue = request.quantity().multiply(transferPricePerShare);

    GiftSaga savedSaga =
        giftSagaRepository.save(
            GiftSaga.builder()
                .sagaId(UUID.randomUUID())
                .giverUserId(giverUserId)
                .receiverUserId(request.receiverId())
                .tickerSymbol(request.tickerSymbol())
                .quantity(request.quantity())
                .transferPricePerShare(transferPricePerShare)
                .transferDollarValue(transferDollarValue)
                .idempotencyKey(request.idempotencyKey())
                .status(GiftSagaStatus.INITIATED)
                .giftStartedAt(LocalDateTime.now())
                .build());

    giftSagaOutboxWriter.writeGiftLifeCycleEvent(savedSaga, "GIFT_SAGA_STARTED", null);
    log.info(
        "Gift saga created: sagaId={}, giverUserId={}, receiverUserId={}, ticker={}, qty={}",
        savedSaga.getSagaId(),
        savedSaga.getGiverUserId(),
        savedSaga.getReceiverUserId(),
        savedSaga.getTickerSymbol(),
        savedSaga.getQuantity());

    try {
      executeStep1DebitGiver(savedSaga);
    } catch (Exception e) {
      savedSaga.setFailureReason(e.getMessage());
      compensate(savedSaga, 0);
      throw new RuntimeException("Gift saga failed in step 1 (debit giver)", e);
    }

    try {
      executeStep2CreditReceiver(savedSaga);
    } catch (Exception e) {
      savedSaga.setFailureReason(e.getMessage());
      compensate(savedSaga, 1);
      throw new RuntimeException("Gift saga failed in step 2 (credit receiver)", e);
    }

    try {
      executeStep3RecordLedger(savedSaga);
    } catch (Exception e) {
      savedSaga.setFailureReason(e.getMessage());
      compensate(savedSaga, 2);
      throw new RuntimeException("Gift saga failed in step 3 (record ledger)", e);
    }

    savedSaga.setStatus(GiftSagaStatus.COMPLETED);
    savedSaga = giftSagaRepository.save(savedSaga);
    giftSagaOutboxWriter.writeGiftLifeCycleEvent(savedSaga, "GIFT_SAGA_COMPLETED", null);
    log.info(
        "Gift saga completed: sagaId={}, giverUserId={}, receiverUserId={}",
        savedSaga.getSagaId(),
        savedSaga.getGiverUserId(),
        savedSaga.getReceiverUserId());

    return savedSaga;
  }

  /** Step 1: debit transferred quantity from giver holding. */
  private void executeStep1DebitGiver(GiftSaga saga) {
    log.debug(
        "Gift step 1 start: sagaId={}, giverUserId={}, ticker={}, qty={}",
        saga.getSagaId(),
        saga.getGiverUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity());

    saga.setStatus(GiftSagaStatus.DEBITING_GIVER);
    giftSagaRepository.save(saga);

    portfolioService.reduceHolding(
        saga.getGiverUserId(), saga.getTickerSymbol(), saga.getQuantity());

    saga.setStatus(GiftSagaStatus.GIVER_DEBITED);
    giftSagaRepository.save(saga);
    giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_STEP_COMPLETED", "DEBIT_GIVER");
    log.info("Gift step 1 completed: sagaId={}, giver debited", saga.getSagaId());
  }

  /** Step 2: credit transferred quantity to receiver holding. */
  private void executeStep2CreditReceiver(GiftSaga saga) {
    log.debug(
        "Gift step 2 start: sagaId={}, receiverUserId={}, ticker={}, qty={}",
        saga.getSagaId(),
        saga.getReceiverUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity());

    saga.setStatus(GiftSagaStatus.CREDITING_RECEIVER);
    giftSagaRepository.save(saga);

    portfolioService.addOrUpdateHolding(
        saga.getReceiverUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity(),
        saga.getTransferPricePerShare());

    saga.setStatus(GiftSagaStatus.RECEIVER_CREDITED);
    giftSagaRepository.save(saga);
    giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_STEP_COMPLETED", "CREDIT_RECEIVER");
    log.info("Gift step 2 completed: sagaId={}, receiver credited", saga.getSagaId());
  }

  /** Step 3: record gift transfer ledger entries for auditability. */
  private void executeStep3RecordLedger(GiftSaga saga) {
    log.debug("Gift step 3 start: sagaId={}, recording ledger", saga.getSagaId());

    saga.setStatus(GiftSagaStatus.RECORDING_LEDGER);
    giftSagaRepository.save(saga);

    ledgerService.recordTransaction(
        AccountType.HOLDING_ASSET,
        AccountType.HOLDING_ASSET,
        saga.getTransferDollarValue(),
        ReferenceType.GIFT_TRANSFER,
        saga.getId(),
        "GIFT transfer: " + saga.getTickerSymbol() + " qty=" + saga.getQuantity());

    saga.setStatus(GiftSagaStatus.LEDGER_RECORDED);
    giftSagaRepository.save(saga);
    giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_STEP_COMPLETED", "RECORD_LEDGER");
    log.info("Gift step 3 completed: sagaId={}, ledger recorded", saga.getSagaId());
  }

  /**
   * Runs compensating actions in reverse step order.
   *
   * @param completedSteps number of successfully completed steps before failure
   */
  private void compensate(GiftSaga saga, int completedSteps) {
    log.warn(
        "Gift compensation started: sagaId={}, completedSteps={}, reason={}",
        saga.getSagaId(),
        completedSteps,
        saga.getFailureReason());

    saga.setStatus(GiftSagaStatus.COMPENSATING);
    saga.setCompensationStartedAt(LocalDateTime.now());
    giftSagaRepository.save(saga);
    giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_COMPENSATING", null);

    try {
      if (completedSteps >= 3) {
        ledgerService.recordTransaction(
            AccountType.HOLDING_ASSET,
            AccountType.HOLDING_ASSET,
            saga.getTransferDollarValue(),
            ReferenceType.GIFT_TRANSFER_REVERSAL,
            saga.getId(),
            "GIFT compensation: reverse transfer ledger audit for sagaId=" + saga.getSagaId());
        log.info("Gift compensation ledger reversal done: sagaId={}", saga.getSagaId());
      }

      if (completedSteps >= 2) {
        portfolioService.reduceHolding(
            saga.getReceiverUserId(), saga.getTickerSymbol(), saga.getQuantity());
        log.info("Gift compensation receiver debit done: sagaId={}", saga.getSagaId());
      }

      if (completedSteps >= 1) {
        portfolioService.addOrUpdateHolding(
            saga.getGiverUserId(),
            saga.getTickerSymbol(),
            saga.getQuantity(),
            saga.getTransferPricePerShare());
        log.info("Gift compensation giver restore done: sagaId={}", saga.getSagaId());
      }

      saga.setStatus(GiftSagaStatus.COMPENSATED);
      giftSagaRepository.save(saga);
      giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_COMPENSATED", null);
      log.warn("Gift saga compensated: sagaId={}", saga.getSagaId());
    } catch (Exception e) {
      saga.setStatus(GiftSagaStatus.FAILED);
      saga.setFailureReason("Compensation failed: " + e.getMessage());
      giftSagaRepository.save(saga);
      giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_FAILED", null);
      log.error("Gift compensation failed: sagaId={}", saga.getSagaId(), e);
    }
  }

  /** Scheduled recovery for stuck gifting sagas. */
  @Scheduled(fixedRate = 30000) // every 30 seconds
  @Transactional
  public void detectTimedOutSagas() {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeoutSeconds);
    giftSagaRepository
        .findByStatusNotInAndUpdatedAtBefore(GiftSagaStatus.terminalStatuses(), cutoff)
        .forEach(
            saga -> {
              int completedSteps = completedStepsForStatus(saga.getStatus());
              log.warn(
                  "Timed-out gift saga detected: sagaId={}, status={}, completedSteps={}",
                  saga.getSagaId(),
                  saga.getStatus(),
                  completedSteps);

              if (completedSteps > 0) {
                compensate(saga, completedSteps);
              } else {
                saga.setStatus(GiftSagaStatus.FAILED);
                saga.setFailureReason("Timed out before first irreversible step");
                giftSagaRepository.save(saga);
                giftSagaOutboxWriter.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_FAILED", "TIMEOUT");
              }
            });
  }

  /** Maps status to number of fully completed forward steps. */
  private int completedStepsForStatus(GiftSagaStatus status) {
    if (List.of(GiftSagaStatus.GIVER_DEBITED, GiftSagaStatus.CREDITING_RECEIVER).contains(status)) {
      return 1;
    }
    if (List.of(GiftSagaStatus.RECEIVER_CREDITED, GiftSagaStatus.RECORDING_LEDGER)
        .contains(status)) {
      return 2;
    }
    if (status == GiftSagaStatus.LEDGER_RECORDED) {
      return 3;
    }
    return 0;
  }

  /** Validates gift invariants before starting saga state mutation. */
  private Holding validateRequest(Long giverUserId, GiftRequest request) {
    if (giverUserId.equals(request.receiverId())) {
      throw new IllegalArgumentException("Self-gift is not allowed");
    }

    if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Gift quantity must be positive");
    }

    Portfolio giverPortfolio = portfolioService.getOrCreatePortfolio(giverUserId);
    Holding giverHolding =
        getGiverHoldingOrThrow(giverPortfolio, request.tickerSymbol(), giverUserId);
    if (giverHolding.getAverageBuyPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalStateException(
          "Invalid holding cost basis for gifting. ticker="
              + request.tickerSymbol()
              + ", userId="
              + giverUserId
              + ", averageBuyPrice="
              + giverHolding.getAverageBuyPrice());
    }
    if (giverHolding.getQuantity().compareTo(request.quantity()) < 0) {
      throw new InsufficientSharesException(
          "Insufficient shares for gifting. Required="
              + request.quantity()
              + ", current="
              + giverHolding.getQuantity());
    }
    return giverHolding;
  }

  private static Holding getGiverHoldingOrThrow(
      Portfolio giverPortfolio, String tickerSymbol, Long giverUserId) {
    return giverPortfolio.getHoldings().stream()
        .filter(holding -> holding.getTickerSymbol().equalsIgnoreCase(tickerSymbol))
        .findFirst()
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "No holding found for ticker " + tickerSymbol + " for userId " + giverUserId));
  }
}
