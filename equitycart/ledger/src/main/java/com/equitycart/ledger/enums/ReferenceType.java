package com.equitycart.ledger.enums;

/**
 * Identifies the business action that triggered a ledger entry. Paired with referenceId for
 * traceability.
 */
public enum ReferenceType {
  ORDER,
  TRADE,
  REWARD_VESTING,
  SELL_TO_SPEND
}
