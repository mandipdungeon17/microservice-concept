package com.equitycart.order.service.api;

import com.equitycart.order.dto.FlashSalePurchaseRequest;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.PlaceOrderRequest;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import java.util.List;

/**
 * Service interface for order lifecycle management including placement, retrieval, and status
 * transitions.
 */
public interface OrderService {

  /**
   * Places a new order from the user's current cart contents. Acquires pessimistic locks on each
   * product to safely decrement stock. Idempotent — duplicate requests with the same idempotency
   * key return the existing order without side effects.
   *
   * @param userId the authenticated user placing the order
   * @param request contains idempotency key, shipping address, and payment method
   * @return the created (or existing) order details
   */
  OrderResponse placeOrder(Long userId, PlaceOrderRequest request);

  /**
   * Retrieves a single order by its database identifier.
   *
   * @param orderId the order primary key
   * @return the order details including all line items
   */
  OrderResponse getOrderById(Long orderId);

  /**
   * Retrieves all orders placed by a given user.
   *
   * @param userId the user whose orders to retrieve
   * @return list of orders for the user
   */
  List<OrderResponse> getOrdersByUserId(Long userId);

  /**
   * Transitions an order to a new status after validating the transition is allowed by the state
   * machine rules defined in {@link com.equitycart.order.enums.OrderStatus}.
   *
   * @param orderId the order to update
   * @param request contains the target status as a string
   */
  void updateOrderStatus(Long orderId, UpdateOrderStatusRequest request);

  /**
   * Initiates a return request for a delivered order. Validates that the order belongs to the
   * requesting user and that the current status is DELIVERED before transitioning to
   * RETURN_REQUESTED.
   *
   * @param userId the authenticated user requesting the return
   * @param orderId the order to return
   * @return the updated order details with RETURN_REQUESTED status
   */
  OrderResponse requestReturn(Long userId, Long orderId);

  /**
   * Places a direct flash-sale order for one product under burst-protection controls.
   *
   * <p>Implementation responsibilities:
   *
   * <ul>
   *   <li>validate sale window active state
   *   <li>enforce product-scoped distributed lock with bounded retries
   *   <li>apply idempotency checks before and after lock acquisition
   *   <li>deduct stock via product-service and compensate on save failure
   * </ul>
   *
   * @param userId authenticated buyer identifier
   * @param request flash-sale payload
   * @return created order response or existing order for duplicate idempotency key
   */
  OrderResponse placeFlashSaleOrder(Long userId, FlashSalePurchaseRequest request);
}
