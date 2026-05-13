package com.equitycart.portfolio.repository;

import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link Holding} entities. */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

  /**
   * Finds a holding for a specific ticker within a portfolio.
   *
   * @param portfolio the owning portfolio
   * @param tickerSymbol the exchange ticker symbol
   * @return the holding, or empty if this ticker is not held in the portfolio
   */
  Optional<Holding> findByPortfolioAndTickerSymbol(Portfolio portfolio, String tickerSymbol);
}
