package com.equitycart.portfolio.event;

import com.equitycart.commons.event.OrderDeliveredEvent;
import com.equitycart.commons.event.OrderItemEvent;
import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.marketdata.service.api.MarketDataService;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import com.equitycart.portfolio.service.api.PortfolioService;
import com.equitycart.product.entity.BrandTickerMapping;
import com.equitycart.product.entity.Product;
import com.equitycart.product.repository.BrandTickerMappingRepository;
import com.equitycart.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer handling stock-back reward lifecycle events. Listens to two topics:
 *
 * <ul>
 *   <li>{@code order-delivered} — calculates fractional share rewards per brand/ticker, groups by
 *       ticker, and calls {@link PortfolioService#grantReward} once per ticker (idempotent).
 *   <li>{@code order-returned} — cancels any PENDING rewards for the returned order. Already-VESTED
 *       rewards cannot be cancelled (requires manual review).
 * </ul>
 *
 * <p>Uses separate consumer group IDs for independent offset tracking: {@code
 * equitycart-reward-group} and {@code equitycart-cancellation-group}.
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
  private final ProductRepository productRepository;
  private final BrandTickerMappingRepository brandTickerMappingRepository;
  private final MarketDataService marketDataService;
  private final StockBackRewardRepository stockBackRewardRepository;

  @KafkaListener(topics = "order-delivered", groupId = "equitycart-reward-group")
  void handleOrderDelivered(OrderDeliveredEvent event) {
    log.info(
        "Received order-delivered event for orderId={}, userId={}, itemCount={}",
        event.getOrderId(),
        event.getUserId(),
        event.getOrderItems().size());

    List<OrderItemEvent> orderItemEvents = event.getOrderItems();
    Map<String, List<OrderItemEvent>> listMap = new HashMap<>();
    Map<String, BrandTickerMapping> tickerMappingBySymbol = new HashMap<>();

    for (OrderItemEvent item : orderItemEvents) {
      Optional<Product> product = productRepository.findById(item.getProductId());
      if (product.isEmpty()) {
        log.warn(
            "Product not found for productId={}, skipping reward for orderId={}",
            item.getProductId(),
            event.getOrderId());
        continue;
      }
      Long brandId = product.get().getBrand().getId();
      List<BrandTickerMapping> tickerMappings = brandTickerMappingRepository.findByBrandId(brandId);
      if (tickerMappings.isEmpty()) {
        log.info(
            "No ticker mapping for brandId={}, product '{}' does not participate in stock-back",
            brandId,
            item.getProductName());
        continue;
      }
      String tickerSymbol = tickerMappings.getFirst().getTickerSymbol();

      listMap.computeIfAbsent(tickerSymbol, k -> new ArrayList<>()).add(item);
      tickerMappingBySymbol.putIfAbsent(tickerSymbol, tickerMappings.getFirst());
    }

    listMap.forEach(
        (tickerSymbol, items) -> {
          BrandTickerMapping mapping = tickerMappingBySymbol.get(tickerSymbol);
          BigDecimal rewardDollarValue = BigDecimal.ZERO;

          for (OrderItemEvent orderItem : items) {
            rewardDollarValue =
                rewardDollarValue.add(
                    orderItem.getSubtotal().multiply(mapping.getStockBackPercentage()));
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

  @KafkaListener(topics = "order-returned", groupId = "equitycart-cancellation-group")
  void handleOrderReturned(OrderReturnedEvent event) {
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
}
