package com.equitycart.portfolio.eventsourcing.controller;

import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.eventsourcing.document.PortfolioEvent;
import com.equitycart.portfolio.eventsourcing.dto.PortfolioEventResponse;
import com.equitycart.portfolio.eventsourcing.dto.ProjectedHoldingResponse;
import com.equitycart.portfolio.eventsourcing.projection.PortfolioProjectionService;
import com.equitycart.portfolio.eventsourcing.repository.PortfolioEventRepository;
import com.equitycart.portfolio.repository.PortfolioRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the portfolio event store as a read-only API.
 * Provides event timeline
 * access (with optional ticker/time-range filters), projection rebuilding, and
 * consistency
 * validation between the event-sourced state and PostgreSQL holdings.
 *
 * <p>
 * All endpoints require authentication — events are scoped to the authenticated
 * user's
 * portfolio.
 */
@RestController
@RequestMapping("/api/portfolio/events")
@RequiredArgsConstructor
public class PortfolioEventController {

  private final PortfolioEventRepository portfolioEventRepository;
  private final PortfolioRepository portfolioRepository;
  private final PortfolioProjectionService portfolioProjectionService;

  @GetMapping
  @ResponseStatus(HttpStatus.OK)
  public List<PortfolioEventResponse> getAllEvents(
      Authentication authentication,
      @RequestParam(required = false) String ticker,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    Long userId = (Long) authentication.getPrincipal();
    List<PortfolioEvent> portfolioEvents;
    if (ticker != null) {
      portfolioEvents = portfolioEventRepository.findByUserIdAndTickerSymbolOrderBySequenceNumberAsc(
          userId, ticker);
    } else if (from != null && to != null) {
      portfolioEvents = portfolioEventRepository.findByUserIdAndTimestampBetweenOrderBySequenceNumberAsc(
          userId, from, to);
    } else {
      portfolioEvents = portfolioEventRepository.findByUserIdOrderBySequenceNumberAsc(userId);
    }

    return portfolioEvents.stream()
        .map(
            event -> new PortfolioEventResponse(
                event.getEventId(),
                event.getEventType(),
                event.getTickerSymbol(),
                event.getQuantity(),
                event.getPricePerShare(),
                event.getTotalValue(),
                event.getMetadata(),
                event.getTimestamp(),
                event.getSequenceNumber()))
        .toList();
  }

  @GetMapping("/projection")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, ProjectedHoldingResponse> getProjectedHoldings(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return portfolioProjectionService.rebuildHoldings(userId);
  }

  @GetMapping("/projection/validate")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, String> validate(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    Portfolio portfolioId = portfolioRepository
        .findByUserId(userId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Portfolio not found for userId: " + userId));
    return portfolioProjectionService.validateConsistency(userId, portfolioId.getId());
  }
}
