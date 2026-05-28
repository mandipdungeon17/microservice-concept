package com.equitycart.portfolio.saga.orchestrator;

import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.service.api.OrderService;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import com.equitycart.portfolio.saga.event.SagaOutboxWriter;
import com.equitycart.portfolio.saga.repository.SellToSpendSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga Orchestrator for the Sell-to-Spend flow — the central coordinator that drives each step
 * sequentially and runs compensating transactions in reverse on failure.
 *
 * <p><b>Pattern:</b> Orchestration-based Saga (vs. Choreography). One class knows the full step
 * sequence, making the flow readable in a single location. The orchestrator calls services directly
 * (same JVM) and publishes lifecycle events to Kafka via the outbox for observability.
 *
 * <p><b>Steps:</b>
 *
 * <pre>
 * 1. reduceHolding()       — sell shares from portfolio
 * 2. recordTransaction()   — double-entry ledger (CASH ← HOLDING_ASSET)
 * 3. updateOrderStatus()   — confirm order (CREATED → CONFIRMED)
 * </pre>
 *
 * <p><b>Compensation (on failure):</b> Runs in reverse from the last completed step:
 *
 * <pre>
 * Undo step 2: recordTransaction(HOLDING_ASSET ← CASH, SELL_TO_SPEND_REVERSAL)
 * Undo step 1: addOrUpdateHolding() — re-adds sold shares
 * </pre>
 *
 * <p><b>Critical design choice:</b> {@code executeSaga()} is deliberately NOT
 * {@code @Transactional} — each step commits independently via its own repository save. This means
 * intermediate states ARE visible to other transactions (eventual consistency). If the app crashes
 * mid-saga, the timeout detector picks up from the last persisted state and compensates.
 *
 * <p><b>Timeout detection:</b> A {@code @Scheduled} method polls for sagas stuck in non-terminal
 * states beyond the configured timeout and triggers compensation.
 *
 * @see SagaStatus
 * @see SellToSpendSaga
 * @see com.equitycart.portfolio.saga.service.SellToSpendSagaServiceImpl
 */
@Component
@RequiredArgsConstructor
public class SellToSpendSagaOrchestrator {

  private static final Logger log = LogManager.getLogger(SellToSpendSagaOrchestrator.class);

  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;
  private final OrderService orderService;
  private final SellToSpendSagaRepository sellToSpendSagaRepository;
  private final SagaOutboxWriter sagaOutboxWriter;
  private final PortfolioEventStore portfolioEventStore;

  @Value("${equitycart.saga.timeout-seconds:30}")
  private long timeoutSeconds;

  /**
   * Executes the Sell-to-Spend Saga — drives all 3 steps sequentially and compensates on failure.
   * Not {@code @Transactional} by design — each step commits independently.
   *
   * @param userId the user performing the sell-to-spend
   * @param request contains orderId, tickerSymbol, quantity, pricePerShare
   * @return the saga entity in its final state (COMPLETED, COMPENSATED, or FAILED)
   * @throws RuntimeException wrapping the original exception if a step fails
   */
  public SellToSpendSaga executeSaga(Long userId, SellToSpendRequest request) {
    BigDecimal saleProceeds = request.quantity().multiply(request.pricePerShare());

    List<SagaStatus> terminalStatuses =
        List.of(SagaStatus.COMPLETED, SagaStatus.FAILED, SagaStatus.COMPENSATED);

    Optional<SellToSpendSaga> sellToSpendSagaOptional =
        sellToSpendSagaRepository.findByOrderIdAndStatusNotIn(request.orderId(), terminalStatuses);

    if (sellToSpendSagaOptional.isPresent()) {
      log.info(
          "Existing saga found for Order ID {} with status {}. Returning existing saga.",
          request.orderId(),
          sellToSpendSagaOptional.get().getStatus());
      return sellToSpendSagaOptional.get();
    }

    SellToSpendSaga sellToSpendSaga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(userId)
            .orderId(request.orderId())
            .tickerSymbol(request.tickerSymbol())
            .quantity(request.quantity())
            .pricePerShare(request.pricePerShare())
            .saleProceeds(saleProceeds)
            .status(SagaStatus.STARTED)
            .build();

    SellToSpendSaga savedSaga = sellToSpendSagaRepository.save(sellToSpendSaga);
    log.info(
        "Saga created: sagaId={}, orderId={}, userId={}, ticker={}, qty={}",
        savedSaga.getSagaId(),
        savedSaga.getOrderId(),
        userId,
        request.tickerSymbol(),
        request.quantity());

    sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_STARTED", null);

    try {
      executeStep1(savedSaga);
    } catch (Exception e) {
      log.error("Saga step 1 failed: sagaId={}, error={}", savedSaga.getSagaId(), e.getMessage());
      savedSaga.setStatus(SagaStatus.FAILED);
      savedSaga.setFailureReason(e.getMessage());
      sellToSpendSagaRepository.save(savedSaga);

      sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_FAILED", null);

      throw new RuntimeException(e);
    }

    try {
      executeStep2(savedSaga);
    } catch (Exception e) {
      log.error(
          "Saga step 2 failed: sagaId={}, error={}. Compensating step 1.",
          savedSaga.getSagaId(),
          e.getMessage());
      savedSaga.setFailureReason(e.getMessage());
      compensate(savedSaga, 1);

      throw new RuntimeException(e);
    }

    try {
      executeStep3(savedSaga);
    } catch (Exception e) {
      log.error(
          "Saga step 3 failed: sagaId={}, error={}. Compensating steps 2 and 1.",
          savedSaga.getSagaId(),
          e.getMessage());
      savedSaga.setFailureReason(e.getMessage());
      compensate(savedSaga, 2);

      throw new RuntimeException(e);
    }

    log.info(
        "Saga completed successfully: sagaId={}, orderId={}",
        savedSaga.getSagaId(),
        savedSaga.getOrderId());

    return savedSaga;
  }

  private void executeStep1(SellToSpendSaga saga) {
    SellToSpendSaga savedSaga;
    saga.setStatus(SagaStatus.REDUCING_HOLDING);
    savedSaga = sellToSpendSagaRepository.save(saga);

    portfolioService.reduceHolding(
        savedSaga.getUserId(), savedSaga.getTickerSymbol(), savedSaga.getQuantity());

    saga.setStatus(SagaStatus.HOLDING_REDUCED);
    savedSaga = sellToSpendSagaRepository.save(saga);

    portfolioEventStore.append(
        saga.getUserId(),
        PortfolioEventType.SELL_TO_SPEND,
        saga.getTickerSymbol(),
        saga.getQuantity(),
        saga.getPricePerShare(),
        saga.getSaleProceeds(),
        Map.of("orderId", saga.getOrderId(), "sagaId", saga.getSagaId().toString()));

    sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_STEP_COMPLETED", "REDUCE_HOLDING");

    log.info(
        "Saga step 1 completed: reduced {} shares of {} for userId={}",
        saga.getQuantity(),
        saga.getTickerSymbol(),
        saga.getUserId());
  }

  private void executeStep2(SellToSpendSaga saga) {
    SellToSpendSaga savedSaga;
    saga.setStatus(SagaStatus.RECORDING_LEDGER);
    savedSaga = sellToSpendSagaRepository.save(saga);

    ledgerService.recordTransaction(
        AccountType.CASH,
        AccountType.HOLDING_ASSET,
        savedSaga.getSaleProceeds(),
        ReferenceType.SELL_TO_SPEND,
        saga.getOrderId(),
        "SELL_TO_SPEND "
            + saga.getQuantity()
            + " "
            + saga.getTickerSymbol()
            + " for Order #"
            + saga.getOrderId());

    saga.setStatus(SagaStatus.LEDGER_RECORDED);
    savedSaga = sellToSpendSagaRepository.save(saga);

    sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_STEP_COMPLETED", "RECORD_LEDGER");

    log.info(
        "Saga step 2 completed: ledger recorded, proceeds={}, orderId={}",
        saga.getSaleProceeds(),
        saga.getOrderId());
  }

  private void executeStep3(SellToSpendSaga saga) {
    SellToSpendSaga savedSaga;
    saga.setStatus(SagaStatus.CONFIRMING_ORDER);
    savedSaga = sellToSpendSagaRepository.save(saga);

    orderService.updateOrderStatus(
        savedSaga.getOrderId(), new UpdateOrderStatusRequest(OrderStatus.CONFIRMED.name()));

    saga.setStatus(SagaStatus.COMPLETED);
    savedSaga = sellToSpendSagaRepository.save(saga);

    sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_STEP_COMPLETED", "CONFIRM_ORDER");

    log.info("Saga step 3 completed: order {} confirmed", saga.getOrderId());
  }

  private void compensate(SellToSpendSaga saga, int completedSteps) {
    saga.setStatus(SagaStatus.COMPENSATING);
    saga.setCompensationStartedAt(LocalDateTime.now());
    sellToSpendSagaRepository.save(saga);

    sagaOutboxWriter.writeSagaLifecycleEvent(saga, "SAGA_COMPENSATING", null);

    try {
      if (completedSteps >= 2) {
        // Undo step 2: reverse ledger entry
        ledgerService.recordTransaction(
            AccountType.HOLDING_ASSET,
            AccountType.CASH, // SWAPPED debit/credit
            saga.getSaleProceeds(),
            ReferenceType.SELL_TO_SPEND_REVERSAL,
            saga.getOrderId(),
            "SAGA COMPENSATION: reverse sell-to-spend for Order #" + saga.getOrderId());

        log.info("Compensated step 2: ledger reversed");
      }

      if (completedSteps >= 1) {
        // Undo step 1: re-add sold shares
        portfolioService.addOrUpdateHolding(
            saga.getUserId(), saga.getTickerSymbol(), saga.getQuantity(), saga.getPricePerShare());

        portfolioEventStore.append(
            saga.getUserId(),
            PortfolioEventType.SELL_TO_SPEND_COMPENSATED,
            saga.getTickerSymbol(),
            saga.getQuantity(),
            saga.getPricePerShare(),
            saga.getSaleProceeds(),
            Map.of("sagaId", saga.getSagaId().toString(), "reason", saga.getFailureReason()));

        log.info("Compensated step 1: shares re-added");
      }

      saga.setStatus(SagaStatus.COMPENSATED);
      SellToSpendSaga savedSaga = sellToSpendSagaRepository.save(saga);

      sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_COMPENSATED", null);

    } catch (Exception e) {
      saga.setStatus(SagaStatus.FAILED);
      saga.setFailureReason(
          "Compensation failed after step " + completedSteps + ": " + e.getMessage());
      SellToSpendSaga savedSaga = sellToSpendSagaRepository.save(saga);

      sagaOutboxWriter.writeSagaLifecycleEvent(savedSaga, "SAGA_FAILED", null);

      log.error(
          "CRITICAL: compensation failed for sagaId={}, orderId={} — manual intervention required",
          saga.getSagaId(),
          saga.getOrderId());
    }
  }

  /**
   * Scheduled timeout detector — polls for sagas stuck in non-terminal states beyond the configured
   * timeout and triggers compensation or marks them as FAILED.
   */
  @Scheduled(fixedRate = 30000)
  @Transactional
  public void detectTimedOutSagas() {
    List<SagaStatus> terminalStatuses =
        List.of(SagaStatus.COMPLETED, SagaStatus.FAILED, SagaStatus.COMPENSATED);

    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeoutSeconds);

    sellToSpendSagaRepository
        .findByStatusNotInAndUpdatedAtBefore(terminalStatuses, cutoff)
        .forEach(
            saga -> {
              int completedSteps = 0;
              if (List.of(SagaStatus.HOLDING_REDUCED, SagaStatus.RECORDING_LEDGER)
                  .contains(saga.getStatus())) {
                completedSteps = 1;
              } else if (List.of(SagaStatus.LEDGER_RECORDED, SagaStatus.CONFIRMING_ORDER)
                  .contains(saga.getStatus())) {
                completedSteps = 2;
              }

              if (completedSteps > 0) {
                compensate(saga, completedSteps);
              } else {
                saga.setStatus(SagaStatus.FAILED);
                sellToSpendSagaRepository.save(saga);

                sagaOutboxWriter.writeSagaLifecycleEvent(saga, "SAGA_FAILED", null);
              }

              log.warn(
                  "Timed-out saga detected: sagaId={}, status={}, orderId={}",
                  saga.getSagaId(),
                  saga.getStatus(),
                  saga.getOrderId());
            });
  }
}
