package com.equitycart.order.service.impl;

import com.equitycart.commons.dto.ProductDTO;
import com.equitycart.commons.exception.FlashSaleBusyException;
import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.commons.feign.ProductFeignClient;
import com.equitycart.order.cart.dto.CartItemResponse;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.service.api.CartService;
import com.equitycart.order.dto.FlashSalePurchaseRequest;
import com.equitycart.order.dto.OrderItemResponse;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.PlaceOrderRequest;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.entity.Order;
import com.equitycart.order.entity.OrderItem;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.event.OrderOutboxWriter;
import com.equitycart.order.lock.FlashSaleLockManager;
import com.equitycart.order.metrics.OrderMetrics;
import com.equitycart.order.repository.OrderRepository;
import com.equitycart.order.service.api.OrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link OrderService} handling order placement with pessimistic locking on
 * product inventory, idempotency via unique key, and cart-to-order conversion.
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

  private static final Logger log = LogManager.getLogger(OrderServiceImpl.class);
  private static final int FLASH_SALE_LOCK_RETRY_ATTEMPTS = 3;
  private static final long FLASH_SALE_LOCK_BASE_BACKOFF_MS = 50L;

  private final OrderMetrics orderMetrics;
  private final CartService cartService;
  private final OrderRepository orderRepository;
  private final ProductFeignClient productFeignClient;
  private final OrderOutboxWriter orderOutboxWriter;
  private final FlashSaleLockManager flashSaleLockManager;

  @Value("${equitycart.flash-sale.enabled:true}")
  private boolean flashSaleEnabled;

  @Value("${equitycart.flash-sale.start-time:}")
  private String flashSaleStartTime;

  @Value("${equitycart.flash-sale.end-time:}")
  private String flashSaleEndTime;

  /** {@inheritDoc} */
  @Transactional
  @Override
  public OrderResponse placeOrder(Long userId, PlaceOrderRequest request) {
    long startTime = System.nanoTime();
    boolean success = false;

    try {
      log.info(
          "Placing order for userId={} with idempotencyKey={}", userId, request.idempotencyKey());

      Optional<Order> orderOptional =
          orderRepository.findByIdempotencyKey(request.idempotencyKey());
      if (orderOptional.isPresent()) {
        log.info(
            "Duplicate request detected — returning existing order id={}",
            orderOptional.get().getId());
        success = true;
        return toResponse(orderOptional.get());
      }

      CartResponse cartResponse = cartService.getCart(String.valueOf(userId));

      if (cartResponse.items().isEmpty())
        throw new ResourceNotFoundException("Can't place an order with no items");

      List<CartItemResponse> itemResponses = cartResponse.items();

      Order order =
          Order.builder()
              .userId(Long.valueOf(cartResponse.userId()))
              .status(OrderStatus.CREATED)
              .idempotencyKey(request.idempotencyKey())
              .shippingAddress(request.shippingAddress())
              .paymentMethod(request.paymentMethod())
              .totalAmount(cartResponse.total())
              .build();

      for (CartItemResponse cartItemResponse : itemResponses) {
        ProductDTO product = productFeignClient.getProductById(cartItemResponse.productId());

        productFeignClient.deductStock(cartItemResponse.productId(), cartItemResponse.quantity());

        OrderItem orderItem =
            OrderItem.builder()
                .productId(cartItemResponse.productId())
                .productName(product.name())
                .quantity(cartItemResponse.quantity())
                .priceAtPurchase(cartItemResponse.price())
                .subTotal(cartItemResponse.subtotal())
                .build();

        order.addItem(orderItem);
      }

      Order savedOrder = orderRepository.save(order);
      cartService.clearCart(String.valueOf(userId));

      log.info(
          "Order placed successfully — orderId={}, items={}, total={}",
          savedOrder.getId(),
          savedOrder.getItems().size(),
          savedOrder.getTotalAmount());

      success = true;

      return toResponse(savedOrder);
    } finally {
      long elapsed = System.nanoTime() - startTime;
      if (success) {
        orderMetrics.recordPlaced();
        orderMetrics.recordPlacementDurationNanos(elapsed);
      } else {
        orderMetrics.recordFailed();
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public OrderResponse getOrderById(Long orderId) {
    log.debug("Fetching order by orderId={}", orderId);
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Order not found with order Id: " + orderId));

    return toResponse(order);
  }

  /** {@inheritDoc} */
  @Override
  public List<OrderResponse> getOrdersByUserId(Long userId) {
    log.debug("Fetching all orders for userId={}", userId);
    List<Order> orderList = orderRepository.findByUserId(userId);
    if (orderList.isEmpty()) return List.of();

    return orderList.stream().map(this::toResponse).toList();
  }

  /** {@inheritDoc} */
  @Transactional
  @Override
  public void updateOrderStatus(Long orderId, UpdateOrderStatusRequest request) {
    log.info("Updating order status for orderId={} to {}", orderId, request.status());
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Order not found with order Id: " + orderId));

    OrderStatus status;

    try {
      status = OrderStatus.valueOf(request.status());
    } catch (IllegalArgumentException e) {
      throw new InvalidStatusTransitionException("Invalid order status: " + request.status());
    }

    if (order.getStatus().canTransition(status)) {
      if (OrderStatus.RETURNED.equals(status)) {
        List<OrderItem> orderItems = order.getItems();

        for (OrderItem orderItem : orderItems) {
          productFeignClient.restoreStock(orderItem.getProductId(), orderItem.getQuantity());
        }
      }
      log.info("Order {} transitioned from {} to {}", orderId, order.getStatus(), status);

      order.setStatus(status);
      Order saveOrder = orderRepository.save(order);

      if (OrderStatus.DELIVERED.equals(status)) {
        orderOutboxWriter.writeOutboxOrderDeliveredEvent(saveOrder);
      } else if (OrderStatus.RETURNED.equals(status)) {
        orderOutboxWriter.writeOutboxOrderReturnedEvent(saveOrder);
      } else if (OrderStatus.REFUNDED.equals(status)) {
        orderOutboxWriter.writeOutboxOrderRefundedEvent(saveOrder);
      }

    } else
      throw new InvalidStatusTransitionException(
          "Invalid status transition for order Id: " + orderId);
  }

  /** {@inheritDoc} */
  @Transactional
  @Override
  public OrderResponse requestReturn(Long userId, Long orderId) {
    log.info("Return requested for orderId={} by userId={}", orderId, userId);
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Order not found with order Id: " + orderId));

    if (!order.getUserId().equals(userId))
      throw new ResourceNotFoundException("Order not found with order Id: " + orderId);

    if (OrderStatus.DELIVERED.equals(order.getStatus())) {
      order.setStatus(OrderStatus.RETURN_REQUESTED);
      orderRepository.save(order);
      log.info("Order {} moved to RETURN_REQUESTED", orderId);
    } else
      throw new InvalidStatusTransitionException(
          "Order is not delivered yet. Cannot process Return Request");

    return toResponse(order);
  }

  /** {@inheritDoc} */
  @Transactional
  @Override
  public OrderResponse placeFlashSaleOrder(Long userId, FlashSalePurchaseRequest request) {
    long startTime = System.nanoTime();
    boolean success = false;
    boolean lockAcquired = false;
    boolean stockDeducted = false;

    try {
      log.info(
          "Placing flash-sale order userId={} productId={} quantity={} idempotencyKey={}",
          userId,
          request.productId(),
          request.quantity(),
          request.idempotencyKey());

      if (!isFlashSaleActive()) {
        log.warn(
            "Flash-sale rejected because sale window is inactive. userId={} productId={} idempotencyKey={}",
            userId,
            request.productId(),
            request.idempotencyKey());
        throw new FlashSaleBusyException(
            "Flash sale is not active right now. Please try within the configured sale window.");
      }

      // Idempotency check before lock (fast path)
      Optional<Order> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());

      if (existing.isPresent()) {
        log.info("Flash-sale order already exists for idempotencyKey={}", request.idempotencyKey());
        success = true;
        return toResponse(existing.get());
      }

      lockAcquired = acquireFlashSaleLock(request.productId(), request.idempotencyKey());

      if (!lockAcquired) {
        throw new FlashSaleBusyException(
            "Flash sale is busy for productId="
                + request.productId()
                + ". Please try again later.");
      }

      // Idempotency re-check after lock (race-safe path)
      existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
      if (existing.isPresent()) {
        log.info(
            "Flash-sale order already exists for idempotencyKey={} after lock",
            request.idempotencyKey());
        success = true;
        return toResponse(existing.get());
      }

      ProductDTO product = productFeignClient.getProductById(request.productId());

      BigDecimal subTotal =
          product
              .price()
              .multiply(BigDecimal.valueOf(request.quantity()).setScale(4, RoundingMode.HALF_UP));

      Order order =
          Order.builder()
              .userId(userId)
              .status(OrderStatus.CREATED)
              .idempotencyKey(request.idempotencyKey())
              .shippingAddress(request.shippingAddress())
              .paymentMethod(request.paymentMethod())
              .totalAmount(subTotal)
              .build();

      OrderItem orderItem =
          OrderItem.builder()
              .productId(product.id())
              .productName(product.name())
              .quantity(request.quantity())
              .priceAtPurchase(product.price())
              .subTotal(subTotal)
              .build();

      order.addItem(orderItem);

      productFeignClient.deductStock(request.productId(), request.quantity());
      stockDeducted = true;

      Order savedOrder = orderRepository.save(order);

      log.info(
          "Flash-sale order placed successfully orderId={} productId={} qty={}",
          savedOrder.getId(),
          request.productId(),
          request.quantity());

      success = true;
      return toResponse(savedOrder);
    } catch (RuntimeException ex) {
      // Compensation if failure happens after stock deduction
      if (stockDeducted) {
        log.warn(
            "Flash-sale failed after stock deduction. Restoring stock productId={} qty={}",
            request.productId(),
            request.quantity(),
            ex);

        productFeignClient.restoreStock(request.productId(), request.quantity());
      }
      throw ex;
    } finally {
      if (lockAcquired) {
        flashSaleLockManager.releaseLock(request.productId(), request.idempotencyKey());
      }

      long elapsed = System.nanoTime() - startTime;
      if (success) {
        orderMetrics.recordPlaced();
        orderMetrics.recordPlacementDurationNanos(elapsed);
      } else {
        orderMetrics.recordFailed();
      }
    }
  }

  private boolean acquireFlashSaleLock(Long productId, String requestId) {
    int attempts = 0;

    while (attempts < FLASH_SALE_LOCK_RETRY_ATTEMPTS) {
      attempts++;

      if (flashSaleLockManager.tryAcquireLock(productId, requestId)) {
        log.debug(
            "Flash-sale lock acquired on attempt={} for productId={} requestId={}",
            attempts,
            productId,
            requestId);
        return true;
      }

      log.debug(
          "Flash-sale lock contention on attempt={} for productId={} requestId={}",
          attempts,
          productId,
          requestId);

      try {
        TimeUnit.MILLISECONDS.sleep(FLASH_SALE_LOCK_BASE_BACKOFF_MS * attempts);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for flash-sale lock", e);
      }
    }
    log.warn(
        "Flash-sale lock acquisition failed after {} attempts for productId={} requestId={}",
        FLASH_SALE_LOCK_RETRY_ATTEMPTS,
        productId,
        requestId);
    return false;
  }

  /**
   * Validates whether flash sale is currently active based on runtime configuration.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>{@code equitycart.flash-sale.enabled=false} → always inactive
   *   <li>empty start/end window → treated as active when enabled (open window)
   *   <li>both start and end provided (ISO-8601 Instant) → active only when now in range
   * </ul>
   *
   * @return {@code true} when flash-sale purchases are allowed for the current instant
   */
  private boolean isFlashSaleActive() {
    if (!flashSaleEnabled) {
      return false;
    }

    if (flashSaleStartTime == null
        || flashSaleStartTime.isBlank()
        || flashSaleEndTime == null
        || flashSaleEndTime.isBlank()) {
      return true;
    }

    try {
      Instant start = Instant.parse(flashSaleStartTime.trim());
      Instant end = Instant.parse(flashSaleEndTime.trim());
      Instant now = Instant.now();
      boolean active = !now.isBefore(start) && !now.isAfter(end);

      log.debug(
          "Flash-sale window check now={} start={} end={} active={}", now, start, end, active);
      return active;
    } catch (DateTimeParseException ex) {
      log.error(
          "Invalid flash-sale window configuration start='{}' end='{}'. Expected ISO-8601 Instant.",
          flashSaleStartTime,
          flashSaleEndTime,
          ex);
      return false;
    }
  }

  private OrderResponse toResponse(Order order) {
    List<OrderItem> orderItems = order.getItems();
    List<OrderItemResponse> itemResponses = orderItems.stream().map(this::toResponse).toList();

    return new OrderResponse(
        order.getId(),
        order.getUserId(),
        order.getStatus().name(),
        order.getTotalAmount(),
        order.getIdempotencyKey(),
        order.getShippingAddress(),
        order.getPaymentMethod(),
        itemResponses,
        order.getCreatedAt(),
        order.getUpdatedAt());
  }

  private OrderItemResponse toResponse(OrderItem orderItem) {
    return new OrderItemResponse(
        orderItem.getId(),
        orderItem.getProductName(),
        orderItem.getQuantity(),
        orderItem.getPriceAtPurchase(),
        orderItem.getSubTotal());
  }
}
