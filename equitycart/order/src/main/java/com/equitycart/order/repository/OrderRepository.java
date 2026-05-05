package com.equitycart.order.repository;

import com.equitycart.order.entity.Order;
import com.equitycart.order.enums.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link Order} entities. */
public interface OrderRepository extends JpaRepository<Order, Long> {

  /** Finds all orders placed by a given user. */
  List<Order> findByUserId(Long userId);

  /** Finds an order by its unique idempotency key (for duplicate detection). */
  Optional<Order> findByIdempotencyKey(String key);

  /** Finds all orders for a user filtered by status. */
  List<Order> findByUserIdAndStatus(Long userId, OrderStatus status);
}
