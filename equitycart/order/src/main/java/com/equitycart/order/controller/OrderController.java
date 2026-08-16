package com.equitycart.order.controller;

import com.equitycart.order.dto.FlashSalePurchaseRequest;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.PlaceOrderRequest;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.service.api.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for order operations. Provides endpoints for placing orders, retrieving
 * individual orders, and listing all orders for the authenticated user.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/order")
public class OrderController {

  private static final Logger log = LogManager.getLogger(OrderController.class);

  private final OrderService orderService;

  /** Places a new order from the authenticated user's cart contents. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrderResponse placeOrder(
      Authentication authentication, @Valid @RequestBody PlaceOrderRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    log.info("POST /api/order - placing order for userId={}", userId);
    return orderService.placeOrder(userId, request);
  }

  /** Retrieves a single order by its identifier. */
  @GetMapping("/{orderId}")
  @ResponseStatus(HttpStatus.OK)
  public OrderResponse getOrder(@PathVariable Long orderId) {
    log.debug("GET /api/order/{} - fetching order", orderId);
    return orderService.getOrderById(orderId);
  }

  /** Retrieves all orders for the authenticated user. */
  @GetMapping
  @ResponseStatus(HttpStatus.OK)
  public List<OrderResponse> getOrder(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    log.debug("GET /api/order - fetching all orders for userId={}", userId);
    return orderService.getOrdersByUserId(userId);
  }

  /** Updates order status. Restricted to ADMIN role. */
  @PreAuthorize("hasRole('ADMIN')")
  @PatchMapping("/{orderId}/status")
  @ResponseStatus(HttpStatus.OK)
  public void updateOrderStatus(
      @PathVariable Long orderId, @Valid @RequestBody UpdateOrderStatusRequest request) {
    log.info("PATCH /api/order/{}/status - transitioning to {}", orderId, request.status());
    orderService.updateOrderStatus(orderId, request);
  }

  /** Initiates a return request for a delivered order. Available to the order owner. */
  @PatchMapping("/{orderId}/return")
  @ResponseStatus(HttpStatus.OK)
  public OrderResponse requestReturn(Authentication authentication, @PathVariable Long orderId) {
    Long userId = (Long) authentication.getPrincipal();
    log.info("PATCH /api/order/{}/return - return requested by userId={}", orderId, userId);
    return orderService.requestReturn(userId, orderId);
  }

  /**
   * Places a direct flash-sale order for a single product.
   *
   * <p>Unlike regular {@link #placeOrder(Authentication, PlaceOrderRequest)} this bypasses cart
   * aggregation and enters the lock-protected flash-sale path for burst traffic.
   *
   * @param authentication current authenticated principal containing userId
   * @param request flash-sale purchase details
   * @return created order response (or idempotent replay result)
   */
  @PostMapping("/flash-sale")
  @ResponseStatus(HttpStatus.CREATED)
  public OrderResponse placeFlashSaleOrder(
      Authentication authentication, @Valid @RequestBody FlashSalePurchaseRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    log.info(
        "POST /api/order/flash-sale - userId={}, productId={}, quantity={}",
        userId,
        request.productId(),
        request.quantity());

    return orderService.placeFlashSaleOrder(userId, request);
  }
}
