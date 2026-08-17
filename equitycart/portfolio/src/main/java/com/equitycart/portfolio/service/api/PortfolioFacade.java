package com.equitycart.portfolio.service.api;

import com.equitycart.portfolio.alerts.dtos.AlertAuditLogResponse;
import com.equitycart.portfolio.alerts.dtos.CreatePriceAlertRequest;
import com.equitycart.portfolio.alerts.dtos.PriceAlertResponse;
import com.equitycart.portfolio.alerts.dtos.UpdatePriceAlertRequest;
import com.equitycart.portfolio.dto.GiftRequest;
import com.equitycart.portfolio.dto.GiftResponse;
import com.equitycart.portfolio.dto.HoldingRequest;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioAnalyticsResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.SellToSpendRequest;
import com.equitycart.portfolio.dto.SellToSpendResponse;
import com.equitycart.portfolio.dto.StockBackRewardResponse;
import com.equitycart.portfolio.dto.TradeRequest;
import com.equitycart.portfolio.dto.TradeResponse;
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

  /**
   * Executes a manual buy or sell trade and returns the result as a response DTO.
   *
   * @param userId the authenticated user's ID
   * @param request trade details (ticker, quantity, price, type)
   * @return trade execution result with post-trade holding state
   */
  TradeResponse executeTrade(Long userId, TradeRequest request);

  /**
   * Executes the "Sell to Spend" flow — sells stock and funds a pending order with the proceeds.
   *
   * @param userId the authenticated user's ID
   * @param request sell details (ticker, quantity, price) and the order to fund
   * @return confirmation with sale proceeds and updated order status
   */
  SellToSpendResponse sellToSpend(Long userId, SellToSpendRequest request);

  /**
   * Computes portfolio analytics — cost basis breakdown per holding, portfolio weight distribution,
   * and aggregated reward statistics. This is where the facade shines: composing data from multiple
   * service calls into a single rich response.
   *
   * @param userId the authenticated user's ID
   * @return analytics dashboard view with computed metrics
   */
  PortfolioAnalyticsResponse getAnalytics(Long userId);

  /**
   * Transfers shares from authenticated giver to another user via compensatable gifting saga.
   *
   * @param giverUserId source user gifting shares
   * @param request gifting payload with receiver/ticker/quantity/idempotencyKey
   * @return gifting saga response for client-side tracking
   */
  GiftResponse giftStock(Long giverUserId, GiftRequest request);

  PriceAlertResponse createPriceAlert(Long userId, CreatePriceAlertRequest request);

  List<PriceAlertResponse> getPriceAlerts(Long userId);

  PriceAlertResponse updatePriceAlert(Long userId, Long alertId, UpdatePriceAlertRequest request);

  void deactivatePriceAlert(Long userId, Long alertId);

  List<AlertAuditLogResponse> getPriceAlertHistory(Long userId, Long alertId);
}
