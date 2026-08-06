package com.equitycart.portfolio.service.impl;

import com.equitycart.commons.event.NotificationEvent;
import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.enums.TradeType;
import com.equitycart.portfolio.event.NotificationPublisher;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.metrics.PortfolioMetrics;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.portfolio.service.api.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes manual buy/sell trades by delegating to {@link PortfolioService} for holding updates and
 * {@link LedgerService} for double-entry bookkeeping. Both operations run in a single transaction —
 * if the ledger write fails, the holding change rolls back.
 *
 * <p>Ledger accounting (from the user's perspective):
 *
 * <ul>
 *   <li>BUY: DEBIT HOLDING_ASSET (shares acquired), CREDIT CASH (money spent)
 *   <li>SELL: DEBIT CASH (money received), CREDIT HOLDING_ASSET (shares disposed)
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeService {

  private static final Logger logger = LogManager.getLogger(TradeServiceImpl.class);

  private final PortfolioService portfolioService;
  private final LedgerService ledgerService;
  private final PortfolioEventStore portfolioEventStore;
  private final NotificationPublisher notificationPublisher;
  private final PortfolioMetrics portfolioMetrics;

  /** {@inheritDoc} */
  @Override
  @Transactional
  public Holding executeTrade(
      Long userId, String tickerSymbol, BigDecimal qty, BigDecimal price, String tradeType) {
    TradeType type;
    try {
      type = TradeType.valueOf(tradeType);
    } catch (IllegalArgumentException e) {
      logger.warn("Invalid trade type '{}' for userId={}", tradeType, userId);
      throw new InvalidStatusTransitionException("Invalid Trade Type: " + tradeType);
    }

    Holding holding;
    BigDecimal amount = price.multiply(qty);
    if (type.equals(TradeType.BUY)) {
      holding = portfolioService.addOrUpdateHolding(userId, tickerSymbol, qty, price);
      ledgerService.recordTransaction(
          AccountType.HOLDING_ASSET,
          AccountType.CASH,
          amount,
          ReferenceType.TRADE,
          holding.getId(),
          "BUY " + qty + " " + tickerSymbol + " @ " + price);
      logger.info(
          "BUY trade completed: userId={}, ticker={}, qty={}, price={}, amount={}, holdingId={}",
          userId,
          tickerSymbol,
          qty,
          price,
          amount,
          holding.getId());

      portfolioEventStore.append(
          userId,
          PortfolioEventType.SHARES_PURCHASED,
          tickerSymbol,
          qty,
          price,
          amount,
          Map.of("tradeType", "BUY"));

      portfolioMetrics.recordTrade("BUY");
    } else {
      holding = portfolioService.reduceHolding(userId, tickerSymbol, qty);
      ledgerService.recordTransaction(
          AccountType.CASH,
          AccountType.HOLDING_ASSET,
          amount,
          ReferenceType.TRADE,
          holding.getId(),
          "SELL " + qty + " " + tickerSymbol + " @ " + price);
      logger.info(
          "SELL trade completed: userId={}, ticker={}, qty={}, price={}, amount={}, holdingId={}",
          userId,
          tickerSymbol,
          qty,
          price,
          amount,
          holding.getId());

      portfolioEventStore.append(
          userId,
          PortfolioEventType.SHARES_SOLD,
          tickerSymbol,
          qty,
          price,
          amount,
          Map.of("tradeType", "SELL"));
      portfolioMetrics.recordTrade("SELL");
    }

    NotificationEvent notificationEvent =
        new NotificationEvent(
            userId,
            "TRADE_EXECUTED",
            tickerSymbol,
            qty,
            price,
            amount,
            Map.of("tradeType", type.name()),
            LocalDateTime.now());

    notificationPublisher.publish(notificationEvent);

    return holding;
  }
}
