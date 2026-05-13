package com.equitycart.portfolio.controller;

import com.equitycart.portfolio.dto.HoldingRequest;
import com.equitycart.portfolio.dto.HoldingResponse;
import com.equitycart.portfolio.dto.PortfolioResponse;
import com.equitycart.portfolio.dto.StockBackRewardResponse;
import com.equitycart.portfolio.service.api.PortfolioFacade;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for portfolio operations. All endpoints are scoped to the authenticated user —
 * userId is extracted from the JWT principal, never from a path variable.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private static final Logger logger = LogManager.getLogger(PortfolioController.class);

  private final PortfolioFacade portfolioFacade;

  /**
   * Returns the authenticated user's portfolio with all holdings.
   *
   * @param authentication JWT authentication containing userId as principal
   * @return portfolio with holding details
   */
  @GetMapping
  @ResponseStatus(HttpStatus.OK)
  public PortfolioResponse getPortfolio(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    logger.info("GET /api/portfolio — userId={}", userId);
    return portfolioFacade.getPortfolio(userId);
  }

  /**
   * Returns the authenticated user's stock-back reward history.
   *
   * @param authentication JWT authentication containing userId as principal
   * @return list of all rewards (any status) for this user
   */
  @GetMapping("/rewards")
  @ResponseStatus(HttpStatus.OK)
  public List<StockBackRewardResponse> getRewards(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    logger.info("GET /api/portfolio/rewards — userId={}", userId);
    return portfolioFacade.getRewards(userId);
  }

  /**
   * Adds or updates a holding for the authenticated user.
   *
   * @param authentication JWT authentication containing userId as principal
   * @param request holding details (ticker, quantity, price)
   * @return the created or updated holding
   */
  @PostMapping("/holdings")
  @ResponseStatus(HttpStatus.CREATED)
  public HoldingResponse addHolding(
      Authentication authentication, @Valid @RequestBody HoldingRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    logger.info(
        "POST /api/portfolio/holdings — userId={}, ticker={}", userId, request.tickerSymbol());
    return portfolioFacade.addHolding(userId, request);
  }
}
