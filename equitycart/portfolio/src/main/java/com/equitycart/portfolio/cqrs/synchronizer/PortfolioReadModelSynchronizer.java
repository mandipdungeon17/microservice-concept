package com.equitycart.portfolio.cqrs.synchronizer;

import com.equitycart.portfolio.cqrs.model.PortfolioReadModel;
import com.equitycart.portfolio.cqrs.model.ReadModelHolding;
import com.equitycart.portfolio.cqrs.model.ReadModelRewards;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.enums.VestingStatus;
import com.equitycart.portfolio.repository.PortfolioRepository;
import com.equitycart.portfolio.repository.StockBackRewardRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * CQRS Read Model Synchronizer: Rebuilds denormalized portfolio_read_models collection from
 * PostgreSQL write model.
 *
 * <p><b>Design Pattern:</b> Event-driven projection via Kafka consumer. On each
 * PortfolioProjectionEvent received, this service rebuilds the user's complete read model snapshot
 * by querying PostgreSQL (Portfolio and StockBackReward entities) and upserting to MongoDB.
 *
 * <p><b>Why rebuilt per event (not incremental)?</b> Correctness-first approach. Full rebuild
 * guarantees consistency even if event order or payload is complex. Trade-off: more compute than
 * delta projection. Optimization deferred until metrics show it's a bottleneck.
 *
 * <p><b>Why Mongo upsert (not save)?</b> Idempotency. If the same event is replayed/retried, the
 * upsert by userId is idempotent. Using save(newDoc) without pre-setting ID would cause
 * duplicate-key errors on unique userId index.
 *
 * <p><b>Caller:</b> PortfolioReadModelOutboxConsumer.consume() (Kafka listener)
 */
@Service
@RequiredArgsConstructor
public class PortfolioReadModelSynchronizer {

  private static final Logger log = LogManager.getLogger(PortfolioReadModelSynchronizer.class);

  private final PortfolioRepository portfolioRepository;
  private final StockBackRewardRepository stockBackRewardRepository;
  private final MongoTemplate mongoTemplate;

  /**
   * Rebuilds the denormalized portfolio read model for a single user from the PostgreSQL write
   * model.
   *
   * <p><b>Algorithm:</b>
   *
   * <ol>
   *   <li>Query PostgreSQL: fetch Portfolio by userId (throws if not found)
   *   <li>Extract Holdings: stream portfolio.holdings into ReadModelHolding records with
   *       precomputed costBasis (qty × avgPrice)
   *   <li>Calculate totalCostBasis: sum of all holding costBasis values
   *   <li>Fetch Rewards: query StockBackReward by userId from PostgreSQL
   *   <li>Aggregate Rewards: build ReadModelRewards with counts by VestingStatus and totals
   *   <li>Upsert to Mongo: use mongoTemplate.upsert(query by userId) to ensure idempotency
   * </ol>
   *
   * <p><b>Idempotency:</b> The MongoDB upsert query matches on userId (unique index). If called
   * twice with the same userId, the second call overwrites the first atomically. This is safe for
   * at-least-once event delivery (Kafka retries).
   *
   * <p><b>Logging:</b> Logs at debug level (entry, calculation steps, exit) for tracing rebuilds.
   * Caller should wrap in try-catch if recovery is needed.
   *
   * @param userId the user whose portfolio is being rebuilt
   * @throws IllegalArgumentException if no Portfolio found for userId
   */
  public void rebuildReadModelForUser(Long userId) {
    log.debug("Starting read model rebuild for userId={}", userId);
    long startTime = System.currentTimeMillis();

    Portfolio portfolio =
        portfolioRepository
            .findByUserId(userId)
            .orElseThrow(
                () -> new IllegalArgumentException("Portfolio not found for userId: " + userId));

    log.debug(
        "Found portfolio for userId={}, holdingCount={}", userId, portfolio.getHoldings().size());

    // Build holding list
    List<ReadModelHolding> holdings =
        portfolio.getHoldings().stream()
            .map(
                h ->
                    ReadModelHolding.builder()
                        .tickerSymbol(h.getTickerSymbol())
                        .quantity(h.getQuantity())
                        .averageBuyPrice(h.getAverageBuyPrice())
                        .costBasis(h.getQuantity().multiply(h.getAverageBuyPrice()))
                        .build())
            .toList();

    // Calculate total cost basis
    BigDecimal totalCostBasis =
        holdings.stream()
            .map(ReadModelHolding::getCostBasis)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    log.debug("Calculated totalCostBasis={} for userId={}", totalCostBasis, userId);

    // Build rewards summary
    List<StockBackReward> rewards = stockBackRewardRepository.findByUserId(userId);
    ReadModelRewards rewardsSummary =
        ReadModelRewards.builder()
            .totalCount(rewards.size())
            .pendingCount(
                (int) rewards.stream().filter(r -> r.getStatus() == VestingStatus.PENDING).count())
            .vestedCount(
                (int) rewards.stream().filter(r -> r.getStatus() == VestingStatus.VESTED).count())
            .totalSharesEarned(
                rewards.stream()
                    .map(StockBackReward::getSharesEarned)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
            .totalDollarValue(
                rewards.stream()
                    .map(StockBackReward::getDollarValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
            .build();

    log.debug(
        "Aggregated rewards for userId={}: total={}, pending={}, vested={}",
        userId,
        rewardsSummary.getTotalCount(),
        rewardsSummary.getPendingCount(),
        rewardsSummary.getVestedCount());

    // Upsert to MongoDB (idempotent by userId unique index)
    Query query = new Query(Criteria.where("userId").is(userId));

    Update update =
        new Update()
            .set("portfolioId", portfolio.getId())
            .set("totalCostBasis", totalCostBasis)
            .set("holdingCount", holdings.size())
            .set("holdings", holdings)
            .set("rewards", rewardsSummary)
            .set("lastUpdatedAt", LocalDateTime.now())
            .set("version", System.currentTimeMillis());

    mongoTemplate.upsert(query, update, PortfolioReadModel.class);

    long elapsed = System.currentTimeMillis() - startTime;
    log.debug(
        "Successfully upserted read model for userId={}, holdings={}, elapsed={}ms",
        userId,
        holdings.size(),
        elapsed);
  }

  /**
   * <b>Scheduled polling job (COMMENTED OUT - See NOTE below)</b>
   *
   * <p><b>WHY COMMENTED:</b> This method was initially designed as a scheduled poller to
   * continuously sync read models from the event store. However, it has been superseded by the
   * event-driven Kafka consumer architecture:
   *
   * <ol>
   *   <li><b>Debezium CDC (primary):</b> When `cdc` profile is active, Debezium tails the
   *       PostgreSQL WAL and publishes outbox events directly to Kafka. No polling needed.
   *   <li><b>OutboxPoller (fallback):</b> When `cdc` profile is inactive, OutboxPoller polls the
   *       outbox_events table every 5 seconds and publishes to Kafka. More efficient than polling
   *       the entire event store.
   *   <li><b>Event-driven rebuild:</b> Whether via Debezium or OutboxPoller, the
   *       PortfolioReadModelOutboxConsumer Kafka listener triggers rebuildReadModelForUser() on
   *       each event. This is event-driven, not time-driven polling.
   * </ol>
   *
   * <p><b>Kept for reference:</b> This code demonstrates the batch-polling approach and is useful
   * if operational needs shift (e.g., periodic full reconciliation). The commented implementation
   * is preserved as documentation of the polling pattern.
   *
   * <p><b>Current production behavior:</b> Read models are kept in sync via:
   *
   * <pre>
   *   write-side outbox event → Debezium/OutboxPoller → Kafka topic
   *   → PortfolioReadModelOutboxConsumer → rebuildReadModelForUser()
   * </pre>
   *
   * Scheduled reconciliation runs separately (ReadModelReconciliation, 24-hour job).
   */
  /*
  @Scheduled(fixedDelay = 5000) // 5 seconds
  public void synchronizeReadModels() {
   long startTime = System.currentTimeMillis();

   try {
     // Find all userIds that have recent events (last 1000)
     List<PortfolioEvent> recentEvents =
         portfolioEventRepository.findAll().stream().limit(1000).toList();

     if (recentEvents.isEmpty()) {
       log.debug("No recent events found, skipping sync");
       return;
     }

     // Get unique userIds
     List<Long> userIds = recentEvents.stream().map(PortfolioEvent::getUserId).distinct().toList();

     log.debug("Synchronizing read models for {} unique users", userIds.size());

     // Rebuild read model for each user
     int successCount = 0;
     for (Long userId : userIds) {
       try {
         rebuildReadModelForUser(userId);
         successCount++;
       } catch (Exception e) {
         log.warn("Failed to sync read model for userId {}: {}", userId, e.getMessage(), e);
       }
     }

     long elapsed = System.currentTimeMillis() - startTime;
     log.info(
         "Read model synchronization completed: {} users processed in {} ms",
         successCount,
         elapsed);

   } catch (Exception e) {
     log.error("Read model synchronization failed: {}", e.getMessage(), e);
   }
  }
  */
}
