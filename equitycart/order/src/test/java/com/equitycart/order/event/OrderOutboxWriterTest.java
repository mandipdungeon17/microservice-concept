package com.equitycart.order.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.order.entity.Order;
import com.equitycart.order.entity.OrderItem;
import com.equitycart.order.entity.OutboxEvent;
import com.equitycart.order.enums.OrderStatus;
import com.equitycart.order.enums.OutboxStatus;
import com.equitycart.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderOutboxWriterTest {

  @Mock private ObjectMapper objectMapper;
  @Mock private OutboxEventRepository outboxEventRepository;
  @InjectMocks private OrderOutboxWriter orderOutboxWriter;

  @Test
  void writeOutboxOrderDeliveredEventShouldPersistPendingOutboxRow() throws Exception {
    OrderItem item =
        OrderItem.builder()
            .productId(11L)
            .productName("Prod")
            .quantity(2)
            .priceAtPurchase(new BigDecimal("10.00"))
            .subTotal(new BigDecimal("20.00"))
            .build();
    Order order =
        Order.builder()
            .userId(5L)
            .status(OrderStatus.DELIVERED)
            .idempotencyKey("idem")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .totalAmount(new BigDecimal("20.00"))
            .items(List.of(item))
            .build();
    order.setId(100L);
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    orderOutboxWriter.writeOutboxOrderDeliveredEvent(order);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    OutboxEvent outboxEvent = captor.getValue();
    assertEquals("Order", outboxEvent.getAggregateType());
    assertEquals(100L, outboxEvent.getAggregateId());
    assertEquals("ORDER_DELIVERED", outboxEvent.getEventType());
    assertEquals("order-delivered", outboxEvent.getTopic());
    assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
  }

  @Test
  void writeOutboxOrderReturnedEventShouldFailWhenSerializationFails() throws Exception {
    Order order =
        Order.builder()
            .userId(5L)
            .status(OrderStatus.RETURNED)
            .idempotencyKey("idem")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .totalAmount(new BigDecimal("20.00"))
            .items(List.of())
            .build();
    order.setId(100L);
    when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

    assertThrows(RuntimeException.class, () -> orderOutboxWriter.writeOutboxOrderReturnedEvent(order));
  }

  @Test
  void writeOutboxOrderReturnedEventShouldPersistPendingOutboxRow() throws Exception {
    Order order =
        Order.builder()
            .userId(7L)
            .status(OrderStatus.RETURNED)
            .idempotencyKey("idem")
            .shippingAddress("addr")
            .paymentMethod("CARD")
            .totalAmount(new BigDecimal("10.00"))
            .items(List.of())
            .build();
    order.setId(111L);
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    orderOutboxWriter.writeOutboxOrderReturnedEvent(order);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    assertEquals("ORDER_RETURNED", captor.getValue().getEventType());
    assertEquals("order-returned", captor.getValue().getTopic());
  }

  @Test
  void writeOutboxOrderRefundedEventShouldPersistPendingOutboxRow() throws Exception {
    Order order =
        Order.builder()
            .userId(7L)
            .status(OrderStatus.REFUNDED)
            .idempotencyKey("idem")
            .shippingAddress("addr")
            .paymentMethod("STOCK")
            .totalAmount(new BigDecimal("10.00"))
            .items(List.of())
            .build();
    order.setId(112L);
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    orderOutboxWriter.writeOutboxOrderRefundedEvent(order);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    assertEquals("ORDER_REFUNDED", captor.getValue().getEventType());
    assertEquals("order-refunded", captor.getValue().getTopic());
  }
}
