package com.equitycart.portfolio.cqrs.controller;

import com.equitycart.portfolio.cqrs.dtos.HoldingReadResponse;
import com.equitycart.portfolio.cqrs.dtos.PortfolioReadResponse;
import com.equitycart.portfolio.cqrs.model.PortfolioReadModel;
import com.equitycart.portfolio.cqrs.repository.PortfolioReadModelRepository;
import com.equitycart.portfolio.dto.HoldingAnalyticsResponse;
import com.equitycart.portfolio.dto.PortfolioAnalyticsResponse;
import com.equitycart.portfolio.dto.RewardSummaryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CQRS Read API: Fast portfolio queries from denormalized MongoDB read model.
 *
 * <p>Endpoint differences: /api/portfolio/{userId} = Legacy (PostgreSQL, ~17ms)
 * /api/v2/portfolio/{userId} = CQRS (MongoDB, ~5ms)
 *
 * <p>Both return same data; use feature flag to route traffic gradually.
 */
@RestController
@RequestMapping("/api/v2/portfolio")
@RequiredArgsConstructor
public class PortfolioReadController {

  private static final Logger log = LogManager.getLogger(PortfolioReadController.class);

  private final PortfolioReadModelRepository readModelRepository;

  /**
   * Fast portfolio read from MongoDB read model. Expected latency: <10ms (vs 17ms from PostgreSQL)
   */
  @GetMapping("/{userId}")
  public ResponseEntity<PortfolioReadResponse> getPortfolio(@PathVariable Long userId) {
    long startTime = System.currentTimeMillis();

    PortfolioReadModel readModel = readModelRepository.findByUserId(userId).orElse(null);

    if (readModel == null) {
      log.warn("Read model not found for userId={}, returning 404", userId);
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    PortfolioReadResponse response =
        new PortfolioReadResponse(
            userId,
            readModel.getPortfolioId(),
            readModel.getHoldingCount(),
            readModel.getTotalCostBasis(),
            readModel.getHoldings().stream()
                .map(
                    h ->
                        new HoldingReadResponse(
                            h.getTickerSymbol(),
                            h.getQuantity(),
                            h.getAverageBuyPrice(),
                            h.getCostBasis()))
                .toList(),
            readModel.getLastUpdatedAt());

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("Portfolio read for userId={} took {} ms", userId, elapsed);

    return ResponseEntity.ok(response);
  }

  /**
   * Fast portfolio analytics from MongoDB read model. Expected latency: <20ms (vs 70-500ms from
   * PostgreSQL)
   *
   * <p>Improvement: No stream aggregation, all values pre-computed in read model.
   */
  @GetMapping("/{userId}/analytics")
  public ResponseEntity<PortfolioAnalyticsResponse> getPortfolioAnalytics(
      @PathVariable Long userId) {
    long startTime = System.currentTimeMillis();

    PortfolioReadModel readModel = readModelRepository.findByUserId(userId).orElse(null);

    if (readModel == null) {
      log.warn("Read model not found for userId={}, returning 404", userId);
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    BigDecimal totalCostBasis = readModel.getTotalCostBasis();

    PortfolioAnalyticsResponse response =
        new PortfolioAnalyticsResponse(
            userId,
            readModel.getHoldingCount(),
            totalCostBasis,
            readModel.getHoldings().stream()
                .map(
                    h ->
                        new HoldingAnalyticsResponse(
                            h.getTickerSymbol(),
                            h.getQuantity(),
                            h.getAverageBuyPrice(),
                            h.getCostBasis(),
                            calculateWeight(h.getCostBasis(), totalCostBasis)))
                .toList(),
            new RewardSummaryResponse(
                readModel.getRewards().getTotalCount(),
                readModel.getRewards().getPendingCount(),
                readModel.getRewards().getVestedCount(),
                readModel.getRewards().getTotalSharesEarned(),
                readModel.getRewards().getTotalDollarValue()));

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("Portfolio Analytics for userId={} took {} ms", userId, elapsed);
    return ResponseEntity.ok(response);
  }

  private BigDecimal calculateWeight(BigDecimal costBasis, BigDecimal totalCostBasis) {
    if (totalCostBasis.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    return costBasis
        .multiply(BigDecimal.valueOf(100))
        .divide(totalCostBasis, 2, RoundingMode.HALF_UP);
  }
}
