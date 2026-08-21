package com.equitycart.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.equitycart.commons.dto.ProductDTO;
import com.equitycart.commons.exception.FlashSaleBusyException;
import com.equitycart.commons.exception.InvalidStatusTransitionException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.dto.CartItemResponse;
import com.equitycart.order.cart.service.api.CartService;
import com.equitycart.order.dto.FlashSalePurchaseRequest;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

  @Mock private OrderMetrics orderMetrics;
  @Mock private CartService cartService;
  @Mock private OrderRepository orderRepository;
  @Mock private com.equitycart.commons.feign.ProductFeignClient productFeignClient;
  @Mock private OrderOutboxWriter orderOutboxWriter;
  @Mock private FlashSaleLockManager flashSaleLockManager;

  @InjectMocks private OrderServiceImpl orderService;

  @Test
  void placeOrderShouldThrowWhenCartIsEmpty() {
    PlaceOrderRequest request = new PlaceOrderRequest("idem-1", "addr", "CARD");
    when(orderRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
    when(cartService.getCart("11")).thenReturn(new CartResponse("11", List.of(), BigDecimal.ZERO, null));

    assertThrows(ResourceNotFoundException.class, () -> orderService.placeOrder(11L, request));
    verify(orderMetrics).recordFailed();
  }

  @Test
  void updateOrderStatusShouldRestoreStockAndWriteReturnedOutboxEvent() {
    OrderItem item =
        OrderItem.builder()
            .productId(9L)
            .productName("P")
            .quantity(2)
            .priceAtPurchase(new BigDecimal("10.00"))
            .subTotal(new BigDecimal("20.00"))
            .build();
    Order order =
        Order.builder()
            .userId(11L)
            .status(OrderStatus.RETURN_REQUESTED)
            .totalAmount(new BigDecimal("20.00"))
            .idempotencyKey("idem-1")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .items(List.of(item))
            .build();
    order.setId(101L);
    order.setCreatedAt(LocalDateTime.now().minusDays(1));
    order.setUpdatedAt(LocalDateTime.now());
    when(orderRepository.findById(101L)).thenReturn(Optional.of(order));
    when(orderRepository.save(order)).thenReturn(order);

    orderService.updateOrderStatus(101L, new UpdateOrderStatusRequest("RETURNED"));

    verify(productFeignClient).restoreStock(9L, 2);
    verify(orderOutboxWriter).writeOutboxOrderReturnedEvent(order);
  }

  @Test
  void requestReturnShouldThrowWhenOrderNotDelivered() {
    Order order =
        Order.builder()
            .userId(11L)
            .status(OrderStatus.CREATED)
            .totalAmount(new BigDecimal("20.00"))
            .idempotencyKey("idem-1")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .items(List.of())
            .build();
    order.setId(101L);
    when(orderRepository.findById(101L)).thenReturn(Optional.of(order));

    assertThrows(InvalidStatusTransitionException.class, () -> orderService.requestReturn(11L, 101L));
  }

  @Test
  void getOrdersByUserIdShouldReturnEmptyListWhenNoOrders() {
    when(orderRepository.findByUserId(7L)).thenReturn(List.of());

    List<OrderResponse> responses = orderService.getOrdersByUserId(7L);

    assertEquals(0, responses.size());
  }

  @Test
  void placeOrderShouldReturnExistingOrderWhenIdempotencyKeyAlreadyUsed() {
    Order existing = baseOrder(OrderStatus.CREATED);
    existing.setId(901L);
    when(orderRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.of(existing));

    OrderResponse response = orderService.placeOrder(11L, new PlaceOrderRequest("idem-2", "addr", "CARD"));

    assertEquals(901L, response.orderId());
    verify(cartService, never()).getCart("11");
    verify(orderMetrics).recordPlaced();
  }

  @Test
  void placeOrderShouldCreateOrderAndClearCart() {
    CartItemResponse item =
        new CartItemResponse(77L, 2, new BigDecimal("10"), new BigDecimal("20"));
    CartResponse cart = new CartResponse("11", List.of(item), new BigDecimal("20"), null);
    when(orderRepository.findByIdempotencyKey("idem-3")).thenReturn(Optional.empty());
    when(cartService.getCart("11")).thenReturn(cart);
    when(productFeignClient.getProductById(77L))
        .thenReturn(new ProductDTO(77L, "Prod", new BigDecimal("10"), 10, 3L, true));
    when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
        .thenAnswer(inv -> {
          Order o = inv.getArgument(0);
          o.setId(902L);
          return o;
        });

    OrderResponse response = orderService.placeOrder(11L, new PlaceOrderRequest("idem-3", "addr", "CARD"));

    assertEquals(902L, response.orderId());
    verify(productFeignClient).deductStock(77L, 2);
    verify(cartService).clearCart("11");
    verify(orderMetrics).recordPlaced();
  }

  @Test
  void placeOrderShouldRecordFailureWhenDeductStockThrows() {
    CartItemResponse item =
        new CartItemResponse(77L, 2, new BigDecimal("10"), new BigDecimal("20"));
    CartResponse cart = new CartResponse("11", List.of(item), new BigDecimal("20"), null);
    when(orderRepository.findByIdempotencyKey("idem-4")).thenReturn(Optional.empty());
    when(cartService.getCart("11")).thenReturn(cart);
    when(productFeignClient.getProductById(77L))
        .thenReturn(new ProductDTO(77L, "Prod", new BigDecimal("10"), 10, 3L, true));
    org.mockito.Mockito.doThrow(new RuntimeException("stock fail"))
        .when(productFeignClient)
        .deductStock(77L, 2);

    assertThrows(
        RuntimeException.class,
        () -> orderService.placeOrder(11L, new PlaceOrderRequest("idem-4", "addr", "CARD")));
    verify(orderMetrics).recordFailed();
  }

  @Test
  void getOrderByIdShouldThrowWhenMissing() {
    when(orderRepository.findById(404L)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> orderService.getOrderById(404L));
  }

  @Test
  void getOrderByIdShouldReturnMappedOrder() {
    Order order = baseOrder(OrderStatus.CREATED);
    order.setId(405L);
    when(orderRepository.findById(405L)).thenReturn(Optional.of(order));

    OrderResponse response = orderService.getOrderById(405L);

    assertEquals(405L, response.orderId());
    assertEquals("CREATED", response.status());
  }

  @Test
  void updateOrderStatusShouldWriteDeliveredOutbox() {
    Order order = baseOrder(OrderStatus.SHIPPED);
    order.setId(201L);
    when(orderRepository.findById(201L)).thenReturn(Optional.of(order));
    when(orderRepository.save(order)).thenReturn(order);

    orderService.updateOrderStatus(201L, new UpdateOrderStatusRequest("DELIVERED"));

    verify(orderOutboxWriter).writeOutboxOrderDeliveredEvent(order);
  }

  @Test
  void updateOrderStatusShouldWriteRefundedOutbox() {
    Order order = baseOrder(OrderStatus.RETURNED);
    order.setId(202L);
    when(orderRepository.findById(202L)).thenReturn(Optional.of(order));
    when(orderRepository.save(order)).thenReturn(order);

    orderService.updateOrderStatus(202L, new UpdateOrderStatusRequest("REFUNDED"));

    verify(orderOutboxWriter).writeOutboxOrderRefundedEvent(order);
  }

  @Test
  void updateOrderStatusShouldThrowForUnknownStatusString() {
    Order order = baseOrder(OrderStatus.CREATED);
    order.setId(203L);
    when(orderRepository.findById(203L)).thenReturn(Optional.of(order));

    assertThrows(
        InvalidStatusTransitionException.class,
        () -> orderService.updateOrderStatus(203L, new UpdateOrderStatusRequest("NOPE")));
  }

  @Test
  void requestReturnShouldMoveDeliveredToReturnRequested() {
    Order order = baseOrder(OrderStatus.DELIVERED);
    order.setId(204L);
    when(orderRepository.findById(204L)).thenReturn(Optional.of(order));
    when(orderRepository.save(order)).thenReturn(order);

    OrderResponse response = orderService.requestReturn(11L, 204L);

    assertEquals("RETURN_REQUESTED", response.status());
  }

  @Test
  void requestReturnShouldThrowWhenOrderBelongsToOtherUser() {
    Order order = baseOrder(OrderStatus.DELIVERED);
    order.setId(205L);
    order.setUserId(22L);
    when(orderRepository.findById(205L)).thenReturn(Optional.of(order));

    assertThrows(ResourceNotFoundException.class, () -> orderService.requestReturn(11L, 205L));
  }

  @Test
  void getOrdersByUserIdShouldReturnMappedList() {
    Order order = baseOrder(OrderStatus.CONFIRMED);
    order.setId(206L);
    when(orderRepository.findByUserId(7L)).thenReturn(List.of(order));

    List<OrderResponse> responses = orderService.getOrdersByUserId(7L);

    assertEquals(1, responses.size());
    assertEquals(206L, responses.getFirst().orderId());
  }

  @Test
  void updateOrderStatusShouldThrowWhenTransitionInvalid() {
    Order order = baseOrder(OrderStatus.CREATED);
    order.setId(207L);
    when(orderRepository.findById(207L)).thenReturn(Optional.of(order));

    assertThrows(
        InvalidStatusTransitionException.class,
        () -> orderService.updateOrderStatus(207L, new UpdateOrderStatusRequest("RETURNED")));
  }

  @Test
  void placeFlashSaleOrderShouldFailWhenSaleWindowInactive() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", false);

    assertThrows(
        FlashSaleBusyException.class,
        () ->
            orderService.placeFlashSaleOrder(
                11L, new FlashSalePurchaseRequest(1L, 1, "idem-f1", "addr", "STOCK")));
    verify(orderMetrics).recordFailed();
  }

  @Test
  void placeFlashSaleOrderShouldFailWhenLockNotAcquired() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", true);
    ReflectionTestUtils.setField(orderService, "flashSaleStartTime", "");
    ReflectionTestUtils.setField(orderService, "flashSaleEndTime", "");
    when(orderRepository.findByIdempotencyKey("idem-f2")).thenReturn(Optional.empty());
    when(flashSaleLockManager.tryAcquireLock(2L, "idem-f2")).thenReturn(false);

    assertThrows(
        FlashSaleBusyException.class,
        () ->
            orderService.placeFlashSaleOrder(
                11L, new FlashSalePurchaseRequest(2L, 1, "idem-f2", "addr", "STOCK")));
    verify(orderMetrics).recordFailed();
  }

  @Test
  void placeFlashSaleOrderShouldCreateOrderAndReleaseLock() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", true);
    ReflectionTestUtils.setField(orderService, "flashSaleStartTime", "");
    ReflectionTestUtils.setField(orderService, "flashSaleEndTime", "");
    when(orderRepository.findByIdempotencyKey("idem-f3")).thenReturn(Optional.empty());
    when(flashSaleLockManager.tryAcquireLock(3L, "idem-f3")).thenReturn(true);
    when(productFeignClient.getProductById(3L))
        .thenReturn(new ProductDTO(3L, "FlashProd", new BigDecimal("50"), 10, 1L, true));
    when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
        .thenAnswer(
            inv -> {
              Order o = inv.getArgument(0);
              o.setId(700L);
              return o;
            });

    OrderResponse response =
        orderService.placeFlashSaleOrder(
            11L, new FlashSalePurchaseRequest(3L, 2, "idem-f3", "addr", "STOCK"));

    assertNotNull(response);
    assertEquals(700L, response.orderId());
    verify(productFeignClient).deductStock(3L, 2);
    verify(flashSaleLockManager).releaseLock(3L, "idem-f3");
    verify(orderMetrics).recordPlaced();
  }

  @Test
  void placeFlashSaleOrderShouldReturnExistingAfterLockRecheck() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", true);
    ReflectionTestUtils.setField(orderService, "flashSaleStartTime", "");
    ReflectionTestUtils.setField(orderService, "flashSaleEndTime", "");
    Order existing = baseOrder(OrderStatus.CREATED);
    existing.setId(701L);
    when(orderRepository.findByIdempotencyKey("idem-f5")).thenReturn(Optional.empty(), Optional.of(existing));
    when(flashSaleLockManager.tryAcquireLock(5L, "idem-f5")).thenReturn(true);

    OrderResponse response =
        orderService.placeFlashSaleOrder(
            11L, new FlashSalePurchaseRequest(5L, 2, "idem-f5", "addr", "STOCK"));

    assertEquals(701L, response.orderId());
    verify(flashSaleLockManager).releaseLock(5L, "idem-f5");
    verify(orderMetrics).recordPlaced();
  }

  @Test
  void placeFlashSaleOrderShouldFailForInvalidWindowConfiguration() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", true);
    ReflectionTestUtils.setField(orderService, "flashSaleStartTime", "bad-date");
    ReflectionTestUtils.setField(orderService, "flashSaleEndTime", "also-bad");

    assertThrows(
        FlashSaleBusyException.class,
        () ->
            orderService.placeFlashSaleOrder(
                11L, new FlashSalePurchaseRequest(6L, 1, "idem-f6", "addr", "STOCK")));
  }

  @Test
  void placeFlashSaleOrderShouldRestoreStockOnSaveFailure() {
    ReflectionTestUtils.setField(orderService, "flashSaleEnabled", true);
    ReflectionTestUtils.setField(orderService, "flashSaleStartTime", "");
    ReflectionTestUtils.setField(orderService, "flashSaleEndTime", "");
    when(orderRepository.findByIdempotencyKey("idem-f4")).thenReturn(Optional.empty());
    when(flashSaleLockManager.tryAcquireLock(4L, "idem-f4")).thenReturn(true);
    when(productFeignClient.getProductById(4L))
        .thenReturn(new ProductDTO(4L, "FlashProd", new BigDecimal("50"), 10, 1L, true));
    when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
        .thenThrow(new RuntimeException("db"));

    assertThrows(
        RuntimeException.class,
        () ->
            orderService.placeFlashSaleOrder(
                11L, new FlashSalePurchaseRequest(4L, 2, "idem-f4", "addr", "STOCK")));
    verify(productFeignClient).restoreStock(4L, 2);
    verify(flashSaleLockManager).releaseLock(4L, "idem-f4");
  }

  private Order baseOrder(OrderStatus status) {
    Order order =
        Order.builder()
            .userId(11L)
            .status(status)
            .totalAmount(new BigDecimal("20.00"))
            .idempotencyKey("idem-x")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .items(List.of())
            .build();
    order.setCreatedAt(LocalDateTime.now().minusDays(1));
    order.setUpdatedAt(LocalDateTime.now());
    return order;
  }
}
