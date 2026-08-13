package com.equitycart.portfolio.cqrs.bootstrap;

import com.equitycart.portfolio.cqrs.model.PortfolioReadModel;
import com.equitycart.portfolio.cqrs.model.ReadModelHolding;
import com.equitycart.portfolio.cqrs.model.ReadModelRewards;
import com.equitycart.portfolio.cqrs.repository.PortfolioReadModelRepository;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-time bootstrap: Populate MongoDB read model from PostgreSQL write model on startup.
 *
 * <p>Runs once per application startup if read model is empty.
 */
@Component
@RequiredArgsConstructor
public class ReadModelBootstrapper {

  private static final Logger log = LogManager.getLogger(ReadModelBootstrapper.class);

  private final PortfolioRepository portfolioRepository;
  private final StockBackRewardRepository stockBackRewardRepository;
  private final PortfolioReadModelRepository portfolioReadModelRepository;

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapReadModel() {
    log.info("Bootstrapping read model from write model...");
    long mongoCount = portfolioReadModelRepository.count();
    long postgresCount = portfolioRepository.count();

    if (mongoCount >= postgresCount) {
      log.info(
          "Read model already populated: {} docs in MongoDB, {} portfolios in PostgreSQL",
          mongoCount,
          postgresCount);
      return;
    }

    log.info(
        "Bootstrapping read model: {} portfolios in PostgreSQL, {} in MongoDB",
        postgresCount,
        mongoCount);

    long startTime = System.currentTimeMillis();
    List<Portfolio> allPortfolios = portfolioRepository.findAll();
    int bootStrappedCount = 0;

    for (Portfolio portfolio : allPortfolios) {
      try {
        PortfolioReadModel readModel = buildReadModel(portfolio);
        portfolioReadModelRepository.save(readModel);
        bootStrappedCount++;
      } catch (Exception e) {
        log.error("Error bootstrapping portfolio {}: {}", portfolio.getId(), e.getMessage());
      }
    }

    long endTime = System.currentTimeMillis() - startTime;
    log.info("Bootstrapped {} portfolios into read model in {} ms", bootStrappedCount, endTime);
  }

  private PortfolioReadModel buildReadModel(Portfolio portfolio) {
    List<ReadModelHolding> holdings =
        portfolio.getHoldings().stream()
            .map(
                holding ->
                    new ReadModelHolding(
                        holding.getTickerSymbol(),
                        holding.getQuantity(),
                        holding.getAverageBuyPrice(),
                        holding.getQuantity().multiply(holding.getAverageBuyPrice())))
            .toList();

    BigDecimal totalCostBasis =
        holdings.stream()
            .map(ReadModelHolding::getCostBasis)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<StockBackReward> rewards = stockBackRewardRepository.findByUserId(portfolio.getUserId());

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

    return PortfolioReadModel.builder()
        .userId(portfolio.getUserId())
        .portfolioId(portfolio.getId())
        .totalCostBasis(totalCostBasis)
        .holdingCount(holdings.size())
        .holdings(holdings)
        .rewards(rewardsSummary)
        .lastUpdatedAt(LocalDateTime.now())
        .version(System.currentTimeMillis())
        .build();
  }
}
