package com.equitycart.portfolio.eventsourcing.enums;

/**
 * Classifies portfolio state-change events stored in the MongoDB event store.
 * Each value represents
 * a distinct business operation that mutates (or would have mutated) holding
 * state.
 *
 * <p>
 * Events that ADD shares to holdings: {@link #SHARES_PURCHASED},
 * {@link #REWARD_VESTED}, {@link
 * #SELL_TO_SPEND_COMPENSATED}, {@link #REFUND_RESTORED}.
 *
 * <p>
 * Events that REMOVE shares from holdings: {@link #SHARES_SOLD},
 * {@link #SELL_TO_SPEND}.
 *
 * <p>
 * Informational events (no holding change): {@link #REWARD_GRANTED},
 * {@link #REWARD_CANCELLED}.
 */
public enum PortfolioEventType {
  SHARES_PURCHASED,
  SHARES_SOLD,
  REWARD_GRANTED,
  REWARD_VESTED,
  REWARD_CANCELLED,
  SELL_TO_SPEND,
  SELL_TO_SPEND_COMPENSATED,
  REFUND_RESTORED;
}
