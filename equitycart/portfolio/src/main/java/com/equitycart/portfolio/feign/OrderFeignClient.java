package com.equitycart.portfolio.feign;

import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client interface for HTTP communication with {@code ORDER-SERVICE}.
 *
 * <p><b>Why this lives in portfolio-service (not commons):</b> Placing this client in the commons
 * module would create a circular dependency: commons → order DTOs → commons. Only portfolio-service
 * needs to call order-service (for Sell-to-Spend), so the client lives in the consumer module.
 * {@code ProductFeignClient} lives in commons because multiple services consume it (order +
 * portfolio).
 *
 * <p><b>Usage:</b> Called by {@link
 * com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator} (Step 3: confirm order)
 * and by {@link com.equitycart.portfolio.service.impl.SellToSpendServiceImpl} (transactional
 * strategy: confirm order after sell).
 *
 * <p><b>Registration:</b> Discovered via {@code @EnableFeignClients(basePackages =
 * "com.equitycart.portfolio.feign")} on {@code PortfolioServiceApplication}. Routed through
 * Eureka's load balancer ({@code lb://ORDER-SERVICE}).
 *
 * @see com.equitycart.portfolio.saga.orchestrator.SellToSpendSagaOrchestrator
 * @see com.equitycart.portfolio.service.impl.SellToSpendServiceImpl
 */
@FeignClient(name = "ORDER-SERVICE")
public interface OrderFeignClient {

  /**
   * Fetches an order by its identifier. Used to validate order ownership and state before
   * Sell-to-Spend execution.
   *
   * @param orderId the order identifier
   * @return order response containing userId, status, totalAmount, and items
   */
  @GetMapping("/api/order/{orderId}")
  OrderResponse getOrderById(@PathVariable("orderId") Long orderId);

  /**
   * Updates an order's status via HTTP PATCH. Called during Sell-to-Spend to transition the order
   * from CREATED → CONFIRMED after shares are sold and ledger is recorded.
   *
   * <p>{@code @RequestBody} sends the status change as JSON — order-service's {@code
   * OrderServiceImpl.updateOrderStatus()} validates the transition using the state machine.
   *
   * @param orderId the order identifier
   * @param request the status update payload (contains target status name)
   */
  @PatchMapping("/api/order/{orderId}/status")
  void updateOrderStatus(
      @PathVariable("orderId") Long orderId, @RequestBody UpdateOrderStatusRequest request);
}
