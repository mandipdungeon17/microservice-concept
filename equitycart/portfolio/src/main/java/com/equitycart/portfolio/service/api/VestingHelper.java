package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.entity.StockBackReward;

/**
 * Internal helper for vesting individual stock-back rewards in isolated transactions.
 *
 * <p>Exists as a separate bean so that {@code @Transactional(REQUIRES_NEW)} is honoured via the
 * Spring proxy — avoids the self-invocation trap that would occur if this logic lived directly in
 * {@link PortfolioService}.
 */
public interface VestingHelper {

  /**
   * Vests a single reward: credits the earned shares to the user's holding and transitions the
   * reward to VESTED status. Runs in REQUIRES_NEW so one failure doesn't affect others.
   *
   * @param reward the PENDING reward to vest
   */
  void vestSingleReward(StockBackReward reward);
}
