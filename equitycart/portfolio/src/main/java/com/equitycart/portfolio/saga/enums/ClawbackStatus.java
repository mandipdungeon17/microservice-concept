package com.equitycart.portfolio.saga.enums;

import java.util.List;

/**
 * State machine for the Return Clawback Saga lifecycle.
 *
 * <p>Happy-path progression:
 *
 * <pre>
 * INITIATED -> CHECKING_ELIGIBILITY -> REDUCING_HOLDING -> HOLDING_REDUCED
 *   -> RECORDING_LEDGER -> LEDGER_RECORDED -> UPDATING_REWARD_STATUS -> COMPLETED
 * </pre>
 *
 * <p>Failure path:
 *
 * <pre>
 * any active state -> COMPENSATING -> COMPENSATED | FAILED
 * </pre>
 */
public enum ClawbackStatus {
  INITIATED,
  CHECKING_ELIGIBILITY,
  REDUCING_HOLDING,
  HOLDING_REDUCED,
  RECORDING_LEDGER,
  LEDGER_RECORDED,
  UPDATING_REWARD_STATUS,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  FAILED;

  /** Terminal statuses are excluded from active-saga idempotency/time-out queries. */
  public static List<ClawbackStatus> getTerminalStatuses() {
    return List.of(COMPLETED, COMPENSATED, FAILED);
  }
}
