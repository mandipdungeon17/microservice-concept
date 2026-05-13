package com.equitycart.portfolio.entity;

import com.equitycart.commons.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a user's investment portfolio — the top-level container that groups all {@link
 * Holding} positions belonging to a single user.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>One-to-one relationship with a user (enforced by unique constraint on {@code userId}).
 *   <li>Owns its holdings via {@code CascadeType.ALL} + {@code orphanRemoval} so that
 *       adding/removing from the collection is the only mutation path — no direct Holding
 *       persistence needed.
 *   <li>Created lazily on first successful order settlement, not at user registration time.
 * </ul>
 */
@Entity
@Table(name = "portfolios")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class Portfolio extends BaseEntity {

  /** Foreign key to the user service; unique to guarantee one portfolio per user. */
  @Column(unique = true, nullable = false)
  private Long userId;

  /**
   * All stock positions held within this portfolio. Cascade ensures holdings are persisted/removed
   * together with the portfolio.
   */
  @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
  @Builder.Default
  List<Holding> holdings = new ArrayList<>();
}
