package com.equitycart.portfolio.async.repository;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PortfolioOutboxEvent} entities. Used by the outbox poller
 * to discover PENDING events for publication.
 */
public interface PortfolioOutboxEventRepository extends JpaRepository<PortfolioOutboxEvent, Long> {

  /**
   * Finds all outbox events in the given status. The poller calls this with {@code
   * OutboxStatus.PENDING} to get events awaiting Kafka publication.
   *
   * @param status the target status to filter by
   * @return list of matching outbox events (empty if none pending)
   */
  List<PortfolioOutboxEvent> findByStatus(PortfolioOutboxStatus status);
}
