package com.equitycart.portfolio.saga.enums;

import java.util.List;

/**
 * State machine for stock gifting saga.
 *
 * <p>Forward flow:
 *
 * <pre>
 * INITIATED -> DEBITING_GIVER -> GIVER_DEBITED -> CREDITING_RECEIVER
 *   -> RECEIVER_CREDITED -> RECORDING_LEDGER -> LEDGER_RECORDED -> COMPLETED
 * </pre>
 *
 * <p>Failure flow:
 *
 * <pre>
 * (any active state) -> COMPENSATING -> COMPENSATED | FAILED
 * </pre>
 */
public enum GiftSagaStatus {
  INITIATED,
  DEBITING_GIVER,
  GIVER_DEBITED,
  CREDITING_RECEIVER,
  RECEIVER_CREDITED,
  RECORDING_LEDGER,
  LEDGER_RECORDED,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  FAILED;

  /** Statuses considered terminal for timeout/idempotency filtering. */
  public static List<GiftSagaStatus> terminalStatuses() {
    return List.of(COMPLETED, COMPENSATED, FAILED);
  }
}
