package com.equitycart.portfolio.event;

import com.equitycart.commons.dto.BrandTickerMappingDTO;
import com.equitycart.commons.dto.ProductDTO;
import com.equitycart.commons.event.OrderDeliveredEvent;
import com.equitycart.commons.event.OrderItemEvent;
import com.equitycart.commons.event.OrderRefundedEvent;
import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.commons.feign.ProductFeignClient;
import com.equitycart.ledger.enums.AccountType;
import com.equitycart.ledger.enums.ReferenceType;
import com.equitycart.ledger.service.api.LedgerService;
import com.equitycart.marketdata.service.api.MarketDataService;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.service.api.PortfolioEventStore;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import com.equitycart.portfolio.saga.repository.SellToSpendSagaRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import feign.FeignException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka consumer handling stock-back reward lifecycle events. Listens to three topics:
 *
 * <ul>
 *   <li>{@code order-delivered} — calculates fractional share rewards per brand/ticker, groups by
 *       ticker, and calls {@link PortfolioService#grantReward} once per ticker (idempotent).
 *   <li>{@code order-returned} — cancels any PENDING rewards for the returned order. Already-VESTED
 *       rewards cannot be cancelled (requires manual review).
 *   <li>{@code order-refunded} — restores shares sold via Sell-to-Spend back to the user's
 *       portfolio. Only processes STOCK-payment orders. Idempotent via {@code isRefunded} flag on
 *       the saga entity.
 * </ul>
 *
 * <p>Uses separate consumer group IDs for independent offset tracking: {@code
 * equitycart-reward-group}, {@code equitycart-cancellation-group}, and {@code
 * equitycart-refund-group}.
 *
 * <p>Error handling: transient failures (DB timeout, market API down) propagate and trigger retry
 * via {@link org.springframework.kafka.listener.DefaultErrorHandler}. After max retries, messages
 * are diverted to the Dead Letter Topic ({@code <topic>.DLT}).
 */
@Component
@RequiredArgsConstructor
public class StockBackRewardConsumer {

  private static final Logger log = LogManager.getLogger(StockBackRewardConsumer.class);

  private final PortfolioService portfolioService;
  private final ProductFeignClient productFeignClient;
  private final MarketDataService marketDataService;
  private final StockBackRewardRepository stockBackRewardRepository;
  private final LedgerService ledgerService;
  private final SellToSpendSagaRepository sellToSpendSagaRepository;
  private final PortfolioEventStore portfolioEventStore;

  @KafkaListener(
      topics = "order-delivered",
      groupId = "equitycart-reward-group",
      properties =
          "spring.json.value.default.type=com.equitycart.commons.event.OrderDeliveredEvent")
  void handleOrderDeliveredEvent(OrderDeliveredEvent event) {
    log.info(
        "Received order-delivered event for orderId={}, userId={}, itemCount={}",
        event.getOrderId(),
        event.getUserId(),
        event.getOrderItems().size());

    List<OrderItemEvent> orderItemEvents = event.getOrderItems();
    Map<String, List<OrderItemEvent>> listMap = new HashMap<>();
    Map<String, BrandTickerMappingDTO> tickerMappingBySymbol = new HashMap<>();

    for (OrderItemEvent item : orderItemEvents) {
      ProductDTO product;
      try {
        product = productFeignClient.getProductById(item.getProductId());
      } catch (FeignException feignException) {
        log.error(
            "Failed to fetch product details for productId={}, orderId={}. Skipping item. Error: {}",
            item.getProductId(),
            event.getOrderId(),
            feignException.getMessage());
        continue;
      }
      Long brandId = product.brandId();
      List<BrandTickerMappingDTO> tickerMappings =
          productFeignClient.getTickerMappingsByBrandId(brandId);
      if (tickerMappings.isEmpty()) {
        log.info(
            "No ticker mapping for brandId={}, product '{}' does not participate in stock-back",
            brandId,
            item.getProductName());
        continue;
      }
      String tickerSymbol = tickerMappings.getFirst().tickerSymbol();

      listMap.computeIfAbsent(tickerSymbol, k -> new ArrayList<>()).add(item);
      tickerMappingBySymbol.putIfAbsent(tickerSymbol, tickerMappings.getFirst());
    }

    listMap.forEach(
        (tickerSymbol, items) -> {
          BrandTickerMappingDTO mapping = tickerMappingBySymbol.get(tickerSymbol);
          BigDecimal rewardDollarValue = BigDecimal.ZERO;

          for (OrderItemEvent orderItem : items) {
            rewardDollarValue =
                rewardDollarValue.add(
                    orderItem.getSubtotal().multiply(mapping.stockBackPercentage()));
          }
          rewardDollarValue = rewardDollarValue.divide(new BigDecimal(100), RoundingMode.HALF_UP);

          BigDecimal currentMarketPrice = marketDataService.getPrice(tickerSymbol).price();
          BigDecimal sharesEarned =
              rewardDollarValue.divide(currentMarketPrice, 6, RoundingMode.HALF_UP);
          LocalDateTime vestingDate = LocalDateTime.now().plusDays(30);

          log.info(
              "Granting reward for orderId={}, ticker={}: rewardDollar={}, price={}, shares={}",
              event.getOrderId(),
              tickerSymbol,
              rewardDollarValue,
              currentMarketPrice,
              sharesEarned);

          portfolioService.grantReward(
              event.getOrderId(),
              event.getUserId(),
              tickerSymbol,
              sharesEarned,
              rewardDollarValue,
              vestingDate);
        });
  }

  @KafkaListener(
      topics = "order-returned",
      groupId = "equitycart-cancellation-group",
      properties = "spring.json.value.default.type=com.equitycart.commons.event.OrderReturnedEvent")
  void handleOrderReturnedEvent(OrderReturnedEvent event) {
    log.info(
        "Received order-returned event for orderId={}, userId={}",
        event.getOrderId(),
        event.getUserId());

    List<StockBackReward> stockBackRewards =
        stockBackRewardRepository.findByOrderId(event.getOrderId());
    if (stockBackRewards.isEmpty()) {
      log.info(
          "No stock-back rewards found for orderId={}, nothing to cancel.", event.getOrderId());
      return;
    }
    stockBackRewards.forEach(
        stockBackReward -> {
          if (stockBackReward.getStatus().equals(VestingStatus.PENDING)) {
            stockBackReward.setStatus(VestingStatus.CANCELLED);

            stockBackRewardRepository.save(stockBackReward);

            portfolioEventStore.append(
                event.getUserId(),
                PortfolioEventType.REWARD_CANCELLED,
                stockBackReward.getTickerSymbol(),
                stockBackReward.getSharesEarned(),
                null,
                null,
                Map.of("orderId", event.getOrderId()));

            log.info(
                "Cancelled PENDING reward for orderId={}, ticker={}",
                event.getOrderId(),
                stockBackReward.getTickerSymbol());
          } else if (stockBackReward.getStatus().equals(VestingStatus.VESTED)) {
            log.warn(
                "Reward for orderId={}, ticker={} already vested, cannot cancel. Manual review needed.",
                event.getOrderId(),
                stockBackReward.getTickerSymbol());
          }
        });
  }

  /**
   * Handles stock refund for orders paid via Sell-to-Spend. When an order is refunded, restores the
   * sold shares back to the user's portfolio and records a ledger reversal entry.
   *
   * <p>Only processes events where {@code paymentMethod == "STOCK"}. Uses the completed saga entity
   * to retrieve the original sale parameters (ticker, quantity, price). Idempotent via the {@code
   * isRefunded} flag on the saga entity.
   *
   * <p>Consumer group: {@code equitycart-refund-group} (independent offset from reward/cancellation
   * groups).
   */
  @KafkaListener(
      topics = "order-refunded",
      groupId = "equitycart-refund-group",
      properties = "spring.json.value.default.type=com.equitycart.commons.event.OrderRefundedEvent")
  @Transactional
  void handleOrderRefundedEvent(OrderRefundedEvent event) {
    log.info(
        "Received order-refunded event for orderId={}, userId={}, paymentMethod={}",
        event.orderId(),
        event.userId(),
        event.paymentMethod());

    if (!event.paymentMethod().equals("STOCK")) {
      log.info(
          "Non-STOCK payment method for orderId={}, skipping refund processing", event.orderId());
      return;
    }

    var sagaOptional =
        sellToSpendSagaRepository.findByOrderIdAndStatus(event.orderId(), SagaStatus.COMPLETED);

    if (sagaOptional.isEmpty()) {
      log.info(
          "No completed sell-to-spend saga found for orderId={}, skipping refund processing",
          event.orderId());
      return;
    }

    var saga = sagaOptional.get();

    if (saga.isRefunded()) {
      log.warn(
          "Refund already processed for orderId={}, sagaId={}, skipping",
          event.orderId(),
          saga.getSagaId());
      return;
    }

    log.info(
        "Processing stock refund for orderId={}, userId={}, ticker={}, quantity={}",
        event.orderId(),
        event.userId(),
        saga.getTickerSymbol(),
        saga.getQuantity());

    portfolioService.addOrUpdateHolding(
        saga.getUserId(), saga.getTickerSymbol(), saga.getQuantity(), saga.getPricePerShare());

    ledgerService.recordTransaction(
        AccountType.HOLDING_ASSET,
        AccountType.CASH,
        saga.getSaleProceeds(),
        ReferenceType.SELL_TO_SPEND_REVERSAL,
        event.orderId(),
        "REFUND: re-added shares for Order #" + event.orderId());

    saga.setRefunded(true);
    sellToSpendSagaRepository.save(saga);

    portfolioEventStore.append(
        saga.getUserId(),
        PortfolioEventType.REFUND_RESTORED,
        saga.getTickerSymbol(),
        saga.getQuantity(),
        saga.getPricePerShare(),
        saga.getSaleProceeds(),
        Map.of("orderId", event.orderId(), "sagaId", saga.getSagaId().toString()));

    log.info(
        "Stock refund completed: re-added {} shares of {} at {} for orderId={}",
        saga.getQuantity(),
        saga.getTickerSymbol(),
        saga.getPricePerShare(),
        event.orderId());
  }
}
