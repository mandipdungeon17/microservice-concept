package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;

/**
 * Service contract for the "Sell to Spend" payment flow — selling stock to fund a pending order.
 * See {@link com.equitycart.portfolio.service.impl.SellToSpendServiceImpl} for flow details.
 */
public interface SellToSpendService {

  /**
   * Sells stock from the user's portfolio and uses the proceeds to pay for a pending order.
   *
   * @param userId the authenticated user
   * @param request specifies which stock to sell, quantity, price, and which order to fund
   * @return result with order confirmation and sale details
   * @throws com.equitycart.commons.exception.ResourceNotFoundException if order doesn't belong to
   *     user
   * @throws com.equitycart.commons.exception.InvalidStatusTransitionException if order is not in
   *     CREATED state
   * @throws IllegalArgumentException if sale proceeds don't cover order total
   * @throws com.equitycart.commons.exception.InsufficientSharesException if user doesn't hold
   *     enough shares
   */
  SellToSpendResponse sellToSpend(Long userId, SellToSpendRequest request);
}
