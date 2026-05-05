package com.equitycart.order.repository;

import com.equitycart.order.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link OrderItem} entities. */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

  /** Finds all line items belonging to a given order (via property path traversal: order.id). */
  List<OrderItem> findByOrderId(Long orderId);
}
