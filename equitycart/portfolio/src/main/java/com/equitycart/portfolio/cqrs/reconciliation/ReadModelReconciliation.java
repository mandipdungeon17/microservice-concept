package com.equitycart.portfolio.cqrs.reconciliation;

import com.equitycart.portfolio.cqrs.model.PortfolioReadModel;
import com.equitycart.portfolio.cqrs.repository.PortfolioReadModelRepository;
import com.equitycart.portfolio.cqrs.synchronizer.PortfolioReadModelSynchronizer;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.repository.PortfolioRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Hourly reconciliation job: Detects and fixes inconsistencies between write model (PostgreSQL) and
 * read model (MongoDB).
 *
 * <p>Checks: 1. Read model exists for every portfolio 2. Holding counts match 3. Rebuilds from
 * scratch if mismatch detected
 */
@Service
@RequiredArgsConstructor
public class ReadModelReconciliation {

  private static final Logger log = LogManager.getLogger(ReadModelReconciliation.class);

  private final PortfolioRepository portfolioRepository;
  private final PortfolioReadModelRepository readModelRepository;
  private final PortfolioReadModelSynchronizer synchronizer;

  @Scheduled(fixedDelay = 86400000) // 24 hours
  public void reconcileReadModels() {
    log.info("Starting read model reconciliation job...");
    long startTime = System.currentTimeMillis();
    int missingCount = 0;
    int inconsistentCount = 0;

    try {
      for (Portfolio portfolio : portfolioRepository.findAll()) {
        Optional<PortfolioReadModel> readModel =
            readModelRepository.findByUserId(portfolio.getUserId());
        if (readModel.isEmpty()) {
          log.warn("Missing read model for userId: {}", portfolio.getUserId());
          synchronizer.rebuildReadModelForUser(portfolio.getUserId());
          missingCount++;
        } else {
          int writeHoldingCount = portfolio.getHoldings().size();
          int readHoldingCount = readModel.get().getHoldingCount();
          if (writeHoldingCount != readHoldingCount) {
            log.warn(
                "Inconsistent holding count for userId: {}. Write model: {}, Read model: {}",
                portfolio.getUserId(),
                writeHoldingCount,
                readHoldingCount);
            synchronizer.rebuildReadModelForUser(portfolio.getUserId());
            inconsistentCount++;
          }
        }
      }
      long elapsed = System.currentTimeMillis() - startTime;
      log.info(
          "Read model reconciliation completed: {} missing, {} inconsistent, elapsed time: {} ms",
          missingCount,
          inconsistentCount,
          elapsed);
    } catch (Exception e) {
      log.error("Read model reconciliation failed: {}", e.getMessage(), e);
    }
  }
}
