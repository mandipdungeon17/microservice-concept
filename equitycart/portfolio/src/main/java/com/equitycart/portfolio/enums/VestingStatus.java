package com.equitycart.portfolio.enums;

/**
 * Lifecycle states of a {@link com.equitycart.portfolio.entity.StockBackReward}.
 *
 * <p>Transition rules:
 *
 * <ul>
 *   <li>{@code PENDING → VESTED} — scheduled vesting job credits shares after vestingDate.
 *   <li>{@code PENDING → CANCELLED} — order was refunded/reversed before vesting.
 *   <li>{@code VESTED → CLAWED_BACK} — return clawback saga reverses already-vested reward shares.
 *   <li>Terminal states ({@code CANCELLED}, {@code CLAWED_BACK}) allow no further transitions.
 * </ul>
 */
public enum VestingStatus {

  /** Reward granted but waiting for vesting date to pass. */
  PENDING,

  /** Shares credited to the user's holding — irreversible. */
  VESTED,

  /** Reward revoked due to order cancellation/refund before vesting. */
  CANCELLED,

  /** Reward clawed back due to specific conditions. */
  CLAWED_BACK;
}
