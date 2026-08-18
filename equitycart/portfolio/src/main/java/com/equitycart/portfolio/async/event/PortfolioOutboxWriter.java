package com.equitycart.portfolio.async.event;

import com.equitycart.portfolio.async.dto.PortfolioProjectionEvent;
import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Writes portfolio lifecycle events into the outbox table for reliable Kafka delivery. Part of the
 * Transactional Outbox Pattern — events are persisted atomically with portfolio state mutations
 * (same DB transaction), then published to Kafka asynchronously by {@link OutboxPoller} or Debezium
 * CDC.
 *
 * <p><b>Atomicity guarantee:</b> The caller (e.g., TradeServiceImpl) runs within the
 * same @Transactional boundary. Portfolio entity and outbox event are committed together. If either
 * fails, both roll back.
 *
 * <p><b>JSON serialization:</b> Events are serialized to JSON via {@link ObjectMapper} and stored
 * in the {@code payload} column (text type, not LOB, for CDC compatibility). The {@code
 * payloadType} FQCN is stored separately for deserialization.
 *
 * <p><b>Kafka key (userId):</b> Events are keyed by userId (aggregateId) to guarantee partition
 * ordering per user. All events for the same user route to the same Kafka partition, ensuring
 * consistent rebuild order.
 *
 * <p><b>8 event types supported:</b> SHARES_PURCHASED, SHARES_SOLD, REWARD_GRANTED, REWARD_VESTED,
 * REWARD_CANCELLED, REFUND_RESTORED, SELL_TO_SPEND, SELL_TO_SPEND_COMPENSATED
 *
 * <p><b>Caller patterns:</b> Injected into TradeServiceImpl, PortfolioServiceImpl,
 * VestingHelperImpl, StockBackRewardConsumer, SellToSpendSagaOrchestrator
 */
@Component
@RequiredArgsConstructor
public class PortfolioOutboxWriter {

  private static final Logger log = LogManager.getLogger(PortfolioOutboxWriter.class);

  private static final String TOPIC = "portfolio-readmodel-events";
  private final ObjectMapper objectMapper;
  private final PortfolioOutboxEventRepository outboxEventRepository;

  /**
   * Writes a SHARES_PURCHASED event when a user buys stock.
   *
   * @param h the Holding entity (with updated quantity and price)
   */
  public void writeSharesPurchasedEvent(Holding h) {
    log.debug(
        "Writing SHARES_PURCHASED event: userId={}, ticker={}, qty={}",
        h.getPortfolio().getUserId(),
        h.getTickerSymbol(),
        h.getQuantity());
    write(
        build(
            PortfolioEventType.SHARES_PURCHASED.name(),
            h.getPortfolio().getUserId(),
            h.getTickerSymbol(),
            h.getQuantity(),
            h.getAverageBuyPrice(),
            h.getQuantity().multiply(h.getAverageBuyPrice()),
            Map.of("holdingId", h.getId())));
  }

  /**
   * Writes a SHARES_SOLD event when a user sells stock.
   *
   * @param h the Holding entity (with reduced quantity)
   * @param soldQty the number of shares sold
   * @param soldPrice the price per share at sale
   */
  public void writeSharesSoldEvent(Holding h, BigDecimal soldQty, BigDecimal soldPrice) {
    log.debug(
        "Writing SHARES_SOLD event: userId={}, ticker={}, soldQty={}, soldPrice={}",
        h.getPortfolio().getUserId(),
        h.getTickerSymbol(),
        soldQty,
        soldPrice);
    write(
        build(
            PortfolioEventType.SHARES_SOLD.name(),
            h.getPortfolio().getUserId(),
            h.getTickerSymbol(),
            soldQty,
            soldPrice,
            soldQty.multiply(soldPrice),
            Map.of("holdingId", h.getId())));
  }

  /**
   * Writes a REWARD_GRANTED event when an order is delivered and stock-back reward is earned.
   *
   * @param r the StockBackReward entity with PENDING status
   */
  public void writeRewardGrantedEvent(StockBackReward r) {
    log.debug(
        "Writing REWARD_GRANTED event: userId={}, ticker={}, shares={}, dollarValue={}",
        r.getUserId(),
        r.getTickerSymbol(),
        r.getSharesEarned(),
        r.getDollarValue());
    write(
        build(
            PortfolioEventType.REWARD_GRANTED.name(),
            r.getUserId(),
            r.getTickerSymbol(),
            r.getSharesEarned(),
            BigDecimal.ZERO,
            r.getDollarValue(),
            Map.of("rewardId", r.getId(), "orderId", r.getOrderId())));
  }

  /**
   * Writes a REWARD_VESTED event when a reward moves from PENDING to VESTED status (30-day window
   * expired).
   *
   * @param r the StockBackReward entity with VESTED status
   */
  public void writeRewardVestedEvent(StockBackReward r) {
    log.debug(
        "Writing REWARD_VESTED event: userId={}, ticker={}, shares={}",
        r.getUserId(),
        r.getTickerSymbol(),
        r.getSharesEarned());
    write(
        build(
            PortfolioEventType.REWARD_VESTED.name(),
            r.getUserId(),
            r.getTickerSymbol(),
            r.getSharesEarned(),
            BigDecimal.ZERO,
            r.getDollarValue(),
            Map.of("rewardId", r.getId(), "orderId", r.getOrderId())));
  }

  /**
   * Writes a REWARD_CANCELLED event when a reward is clawed back (e.g., order returned within
   * vesting period).
   *
   * @param r the StockBackReward entity being cancelled
   */
  public void writeRewardCancelledEvent(StockBackReward r) {
    log.debug(
        "Writing REWARD_CANCELLED event: userId={}, ticker={}, shares={}",
        r.getUserId(),
        r.getTickerSymbol(),
        r.getSharesEarned());
    write(
        build(
            PortfolioEventType.REWARD_CANCELLED.name(),
            r.getUserId(),
            r.getTickerSymbol(),
            r.getSharesEarned(),
            BigDecimal.ZERO,
            r.getDollarValue(),
            Map.of("rewardId", r.getId(), "orderId", r.getOrderId())));
  }

  /**
   * Writes a REFUND_RESTORED event when a sell-to-spend saga is compensated and stock is returned
   * to the user.
   *
   * @param saga the SellToSpendSaga with COMPENSATED status
   * @param orderId the order that triggered the compensation
   */
  public void writeRefundRestoredEvent(SellToSpendSaga saga, Long orderId) {
    log.debug(
        "Writing REFUND_RESTORED event: userId={}, ticker={}, qty={}, orderId={}, sagaId={}",
        saga.getUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity(),
        orderId,
        saga.getSagaId());
    write(
        build(
            PortfolioEventType.REFUND_RESTORED.name(),
            saga.getUserId(),
            saga.getTickerSymbol(),
            saga.getQuantity(),
            saga.getPricePerShare(),
            saga.getSaleProceeds(),
            Map.of("orderId", orderId, "sagaId", saga.getSagaId().toString())));
  }

  /**
   * Writes a SELL_TO_SPEND event when user initiates stock liquidation to pay for an order.
   *
   * @param saga the SellToSpendSaga with INITIATED status
   */
  public void writeSellToSpendEvent(SellToSpendSaga saga) {
    log.debug(
        "Writing SELL_TO_SPEND event: userId={}, ticker={}, qty={}, proceeds={}, orderId={}",
        saga.getUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity(),
        saga.getSaleProceeds(),
        saga.getOrderId());
    write(
        build(
            PortfolioEventType.SELL_TO_SPEND.name(),
            saga.getUserId(),
            saga.getTickerSymbol(),
            saga.getQuantity(),
            saga.getPricePerShare(),
            saga.getSaleProceeds(),
            Map.of("orderId", saga.getOrderId(), "sagaId", saga.getSagaId().toString())));
  }

  /**
   * Writes a SELL_TO_SPEND_COMPENSATED event when a sell-to-spend saga fails and the sale is
   * reversed.
   *
   * @param saga the SellToSpendSaga with COMPENSATED status
   */
  public void writeSellToSpendCompensatedEvent(SellToSpendSaga saga) {
    log.debug(
        "Writing SELL_TO_SPEND_COMPENSATED event: userId={}, ticker={}, qty={}, orderId={}, sagaId={}",
        saga.getUserId(),
        saga.getTickerSymbol(),
        saga.getQuantity(),
        saga.getOrderId(),
        saga.getSagaId());
    write(
        build(
            PortfolioEventType.SELL_TO_SPEND_COMPENSATED.name(),
            saga.getUserId(),
            saga.getTickerSymbol(),
            saga.getQuantity(),
            saga.getPricePerShare(),
            saga.getSaleProceeds(),
            Map.of("orderId", saga.getOrderId(), "sagaId", saga.getSagaId().toString())));
  }

  /**
   * Builds a PortfolioProjectionEvent DTO from raw event data.
   *
   * <p>Generates a unique eventId (UUID), captures current timestamp, and packages all metadata.
   *
   * @param type the event type (enum name, e.g., "SHARES_PURCHASED")
   * @param userId the user ID (used as Kafka partition key)
   * @param ticker the stock ticker symbol
   * @param qty the quantity involved (shares or dollar amount)
   * @param price the price per share (or zero for reward events)
   * @param total the total value (qty × price or precomputed)
   * @param metadata additional tracing info (orderId, rewardId, sagaId, holdingId)
   * @return a PortfolioProjectionEvent ready for serialization
   */
  private PortfolioProjectionEvent build(
      String type,
      Long userId,
      String ticker,
      BigDecimal qty,
      BigDecimal price,
      BigDecimal total,
      Map<String, Object> metadata) {
    return new PortfolioProjectionEvent(
        UUID.randomUUID().toString(),
        type,
        userId,
        ticker,
        qty,
        price,
        total,
        LocalDateTime.now(),
        metadata);
  }

  /**
   * Writes an event to the outbox table, atomically with the calling transaction.
   *
   * <p><b>Serialization:</b> The event DTO is serialized to JSON and stored in the {@code payload}
   * column. The {@code payloadType} stores the FQCN for later deserialization.
   *
   * <p><b>Failure handling:</b> JsonProcessingException is wrapped in RuntimeException, causing the
   * transaction to fail visibly. No silent swallowing. Caller is responsible for recovery.
   *
   * @param event the PortfolioProjectionEvent to write
   * @throws RuntimeException if JSON serialization fails
   */
  private void write(PortfolioProjectionEvent event) {
    try {
      String json = objectMapper.writeValueAsString(event);
      PortfolioOutboxEvent outboxEvent =
          PortfolioOutboxEvent.builder()
              .aggregateType("Portfolio")
              .aggregateId(event.userId()) // key by userId
              .eventType(event.eventType())
              .topic(TOPIC)
              .payload(json)
              .payloadType(PortfolioProjectionEvent.class.getName())
              .status(PortfolioOutboxStatus.PENDING)
              .build();
      outboxEventRepository.save(outboxEvent);
      log.debug(
          "Outbox event persisted: eventType={}, userId={}, topic={}",
          event.eventType(),
          event.userId(),
          TOPIC);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize outbox event: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to serialize outbox event", e);
    }
  }
}
