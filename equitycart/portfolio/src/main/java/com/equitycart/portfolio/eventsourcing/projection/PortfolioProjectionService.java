package com.equitycart.portfolio.eventsourcing.projection;

import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.eventsourcing.document.PortfolioEvent;
import com.equitycart.portfolio.eventsourcing.dto.ProjectedHoldingResponse;
import com.equitycart.portfolio.eventsourcing.repository.PortfolioEventRepository;
import com.equitycart.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Rebuilds portfolio holding state by replaying events from the MongoDB event
 * store. Demonstrates
 * the core Event Sourcing concept: current state = f(all past events).
 *
 * <p>
 * The projection applies each event in sequence-number order to an empty state
 * map, computing
 * weighted-average prices for additions and preserving avg price on sells. The
 * result is the
 * portfolio as it would look if derived entirely from the event log —
 * independent of PostgreSQL.
 *
 * <p>
 * Also provides a consistency validation method that compares the projected
 * state against the
 * PostgreSQL holdings, detecting any drift between the two stores (expected for
 * operations that
 * occurred before event sourcing was enabled).
 */
@Service
@RequiredArgsConstructor
public class PortfolioProjectionService {

  private static final Logger log = LogManager.getLogger(PortfolioProjectionService.class);

  private final PortfolioEventRepository portfolioEventRepository;
  private final HoldingRepository holdingRepository;

  public Map<String, ProjectedHoldingResponse> rebuildHoldings(Long userId) {
    List<PortfolioEvent> events = portfolioEventRepository.findByUserIdOrderBySequenceNumberAsc(userId);
    log.info("Rebuilding holdings for userId={} from {} events", userId, events.size());
    Map<String, ProjectedHoldingResponse> map = new HashMap<>();

    for (PortfolioEvent e : events) {
      String ticker = e.getTickerSymbol();
      BigDecimal eventPrice = e.getPricePerShare() == null ? BigDecimal.ZERO : e.getPricePerShare();
      BigDecimal eventQty = e.getQuantity();

      switch (e.getEventType()) {
        case "SHARES_PURCHASED",
            "REWARD_VESTED",
            "SELL_TO_SPEND_COMPENSATED",
            "REFUND_RESTORED" -> {
          ProjectedHoldingResponse existing = map.get(ticker);
          BigDecimal oldQty = existing == null ? BigDecimal.ZERO : existing.quantity();
          BigDecimal oldAvg = existing == null ? BigDecimal.ZERO : existing.averageBuyPrice();

          BigDecimal newQty = oldQty.add(eventQty);
          BigDecimal newAvg;
          if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            newAvg = BigDecimal.ZERO;
          } else {
            newAvg = oldQty
                .multiply(oldAvg)
                .add(eventQty.multiply(eventPrice))
                .divide(newQty, 6, RoundingMode.HALF_UP);
          }
          map.put(ticker, new ProjectedHoldingResponse(ticker, newQty, newAvg));
        }
        case "SHARES_SOLD", "SELL_TO_SPEND" -> {
          ProjectedHoldingResponse existing = map.get(ticker);
          if (existing != null) {
            BigDecimal newQty = existing.quantity().subtract(eventQty);
            map.put(
                ticker, new ProjectedHoldingResponse(ticker, newQty, existing.averageBuyPrice()));
          }
        }
        case "REWARD_GRANTED", "REWARD_CANCELLED" -> {
          // Informational only — no holding change
        }
        default -> {
          // Unknown event type — skip
        }
      }
    }

    // Remove fully sold positions (quantity <= 0)
    map.entrySet().removeIf(entry -> entry.getValue().quantity().compareTo(BigDecimal.ZERO) <= 0);

    return map;
  }

  public Map<String, String> validateConsistency(Long userId, Long portfolioId) {
    Map<String, ProjectedHoldingResponse> projected = rebuildHoldings(userId);

    List<Holding> actualHoldings = holdingRepository.findByPortfolioId(portfolioId);
    log.info(
        "Validating consistency for userId={}: {} projected holdings, {} actual holdings",
        userId,
        projected.size(),
        actualHoldings.size());
    Map<String, Holding> actualByTicker = actualHoldings.stream()
        .collect(Collectors.toMap(Holding::getTickerSymbol, h -> h));

    Map<String, String> results = new HashMap<>();

    // Check all projected tickers
    for (Map.Entry<String, ProjectedHoldingResponse> entry : projected.entrySet()) {
      String ticker = entry.getKey();
      ProjectedHoldingResponse proj = entry.getValue();
      Holding actual = actualByTicker.get(ticker);

      if (actual == null) {
        results.put(
            ticker,
            "MISMATCH: projected=qty:"
                + proj.quantity()
                + "/avg:"
                + proj.averageBuyPrice()
                + ", actual=NOT_FOUND");
      } else if (proj.quantity().compareTo(actual.getQuantity()) == 0
          && proj.averageBuyPrice().compareTo(actual.getAverageBuyPrice()) == 0) {
        results.put(ticker, "MATCH");
      } else {
        results.put(
            ticker,
            "MISMATCH: projected=qty:"
                + proj.quantity()
                + "/avg:"
                + proj.averageBuyPrice()
                + ", actual=qty:"
                + actual.getQuantity()
                + "/avg:"
                + actual.getAverageBuyPrice());
      }
      actualByTicker.remove(ticker);
    }

    // Any remaining actual holdings not in projected state
    for (Map.Entry<String, Holding> entry : actualByTicker.entrySet()) {
      Holding actual = entry.getValue();
      results.put(
          entry.getKey(),
          "MISMATCH: projected=NOT_FOUND, actual=qty:"
              + actual.getQuantity()
              + "/avg:"
              + actual.getAverageBuyPrice());
    }

    return results;
  }
}
