package com.equitycart.portfolio.enums;

/**
 * Lifecycle states of a {@link com.equitycart.portfolio.entity.StockBackReward}.
 *
 * <p>Transition rules:
 *
 * <ul>
 *   <li>{@code PENDING → VESTED} — scheduled vesting job credits shares after vestingDate.
 *   <li>{@code PENDING → CANCELLED} — order was refunded/reversed before vesting.
 *   <li>Terminal states ({@code VESTED}, {@code CANCELLED}) allow no further transitions.
 * </ul>
 */
public enum VestingStatus {

  /** Reward granted but waiting for vesting date to pass. */
  PENDING,

  /** Shares credited to the user's holding — irreversible. */
  VESTED,

  /** Reward revoked due to order cancellation/refund before vesting. */
  CANCELLED
}
