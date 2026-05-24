package com.equitycart.order.service.impl;

import com.equitycart.commons.exception.InsufficientStockException;
import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.order.cart.dto.CartItemResponse;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.service.api.CartService;
import com.equitycart.order.dto.OrderItemResponse;
import com.equitycart.order.dto.OrderResponse;
import com.equitycart.order.dto.PlaceOrderRequest;
import com.equitycart.order.dto.UpdateOrderStatusRequest;
import com.equitycart.order.entity.Order;
import com.equitycart.order.entity.OrderItem;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.event.OrderOutboxWriter;
import com.equitycart.order.repository.OrderRepository;
import com.equitycart.order.service.api.OrderService;
import com.equitycart.product.entity.Product;
import com.equitycart.product.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

  private final CartService cartService;
  private final OrderRepository orderRepository;
  private final ProductRepository productRepository;
  private final OrderOutboxWriter orderOutboxWriter;

  /** {@inheritDoc} */
  @Transactional
  @Override
  public OrderResponse placeOrder(Long userId, PlaceOrderRequest request) {
    log.info(
        "Placing order for userId={} with idempotencyKey={}", userId, request.idempotencyKey());

    Optional<Order> orderOptional = orderRepository.findByIdempotencyKey(request.idempotencyKey());
    if (orderOptional.isPresent()) {
      log.info(
          "Duplicate request detected — returning existing order id={}",
          orderOptional.get().getId());
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
      Product product =
          productRepository
              .findByProductId(cartItemResponse.productId())
              .orElseThrow(() -> new ResourceNotFoundException("Product Not Found"));

      if (product.getStockQuantity() < cartItemResponse.quantity())
        throw new InsufficientStockException(
            "Insufficient stock for product: " + cartItemResponse.productId());

      product.setStockQuantity(product.getStockQuantity() - cartItemResponse.quantity());

      productRepository.save(product);

      OrderItem orderItem =
          OrderItem.builder()
              .productId(product.getId())
              .productName(product.getName())
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

    return toResponse(savedOrder);
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
          Product product =
              productRepository
                  .findByProductId(orderItem.getProductId())
                  .orElseThrow(
                      () ->
                          new ResourceNotFoundException(
                              "Product not found with product Id: " + orderItem.getProductId()));

          product.setStockQuantity(product.getStockQuantity() + orderItem.getQuantity());
          productRepository.save(product);
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
