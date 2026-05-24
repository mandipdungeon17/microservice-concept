package com.equitycart.portfolio.saga.enums;

/**
 * State machine for the Sell-to-Spend Saga lifecycle. Each value represents a discrete point in the
 * saga's progression — either actively executing a step, having completed a step, or in a terminal
 * state (no further transitions).
 *
 * <p>Transition flow (happy path):
 *
 * <pre>
 * STARTED → REDUCING_HOLDING → HOLDING_REDUCED → RECORDING_LEDGER
 *   → LEDGER_RECORDED → CONFIRMING_ORDER → COMPLETED
 * </pre>
 *
 * <p>Failure triggers compensation:
 *
 * <pre>
 * (any executing state) → COMPENSATING → COMPENSATED | FAILED
 * </pre>
 *
 * <p>Terminal states ({@link #isTerminal()}): COMPLETED, COMPENSATED, FAILED — no further
 * transitions occur, and the timeout detector ignores these sagas.
 */
public enum SagaStatus {
  STARTED,
  REDUCING_HOLDING,
  HOLDING_REDUCED,
  RECORDING_LEDGER,
  LEDGER_RECORDED,
  CONFIRMING_ORDER,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  FAILED;

  public boolean isTerminal() {
    return this == COMPLETED || this == COMPENSATED || this == FAILED;
  }
}
