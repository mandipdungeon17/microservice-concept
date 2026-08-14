package com.equitycart.ledger.enums;

/**
 * Identifies the business action that triggered a ledger entry. Paired with referenceId for
 * traceability.
 */
public enum ReferenceType {
  ORDER,
  TRADE,
  REWARD_VESTING,
  SELL_TO_SPEND,
  /** Compensation entry when sell-to-spend is reversed. */
  SELL_TO_SPEND_REVERSAL,
  /** Primary clawback ledger entry for vested reward reversal. */
  REWARD_CLAWBACK,
  /** Compensation entry if clawback ledger step must be reversed. */
  REWARD_CLAWBACK_REVERSAL,
  /** Primary ledger reference for stock gift transfer saga. */
  GIFT_TRANSFER,
  /** Compensation ledger reference when a gift transfer is reversed. */
  GIFT_TRANSFER_REVERSAL
}
