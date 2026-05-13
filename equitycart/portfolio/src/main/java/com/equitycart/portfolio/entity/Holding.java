package com.equitycart.portfolio.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a single stock position within a {@link Portfolio} — the aggregated quantity and cost
 * basis for one ticker symbol.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>Composite uniqueness ({@code portfolio_id + ticker_symbol}) prevents duplicate positions —
 *       new buys increase quantity and recalculate the average price.
 *   <li>{@code @Version} enables optimistic locking so concurrent order settlements for the same
 *       ticker are detected and retried rather than silently overwriting.
 *   <li>Precision choices: quantity uses scale=6 to support fractional shares (stock-back rewards);
 *       averageBuyPrice uses scale=4 matching exchange price granularity.
 * </ul>
 */
@Entity
@Table(
    name = "holdings",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_portfolio_ticker",
            columnNames = {"portfolio_id", "ticker_symbol"}))
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class Holding extends BaseEntity {

  /** Exchange ticker symbol (e.g. "AAPL", "TSLA"). */
  @Column(nullable = false)
  private String tickerSymbol;

  /** Total shares held; supports fractional via scale=6. */
  @Column(precision = 19, scale = 6, nullable = false)
  private BigDecimal quantity;

  /** Weighted-average purchase price per share; recalculated on each buy fill. */
  @Column(precision = 19, scale = 4, nullable = false)
  private BigDecimal averageBuyPrice;

  /** Optimistic lock version — detects concurrent modifications to the same holding. */
  @Version private Long version;

  /** Owning portfolio; LAZY-fetched since most operations already have the portfolio loaded. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "portfolio_id", nullable = false)
  private Portfolio portfolio;
}
