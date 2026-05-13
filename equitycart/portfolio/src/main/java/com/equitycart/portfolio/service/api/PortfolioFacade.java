package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.dto.HoldingRequest;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.StockBackRewardResponse;
import java.util.List;

/**
 * Thin mapping facade between the REST controller and {@link PortfolioService}. Accepts DTOs,
 * delegates to the service (which works with entities/primitives), and maps results back to
 * response DTOs. No business logic lives here.
 */
public interface PortfolioFacade {

  /**
   * Returns the authenticated user's portfolio with all holdings mapped to response DTOs.
   *
   * @param userId the authenticated user's ID
   * @return portfolio view with holding details
   */
  PortfolioResponse getPortfolio(Long userId);

  /**
   * Adds or updates a holding for the authenticated user and returns the result as a DTO.
   *
   * @param userId the authenticated user's ID
   * @param request holding details (ticker, quantity, price)
   * @return the created or updated holding
   */
  HoldingResponse addHolding(Long userId, HoldingRequest request);

  /**
   * Returns all stock-back rewards for the authenticated user, mapped to response DTOs.
   *
   * @param userId the authenticated user's ID
   * @return list of reward history items
   */
  List<StockBackRewardResponse> getRewards(Long userId);
}
