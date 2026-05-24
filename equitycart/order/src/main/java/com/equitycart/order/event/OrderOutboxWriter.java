package com.equitycart.order.event;

import com.equitycart.commons.event.OrderDeliveredEvent;
import com.equitycart.commons.event.OrderItemEvent;
import com.equitycart.commons.event.OrderRefundedEvent;
import com.equitycart.commons.event.OrderReturnedEvent;
import com.equitycart.order.entity.Order;
import com.equitycart.order.entity.OrderItem;
import com.equitycart.order.entity.OutboxEvent;
import com.equitycart.order.enums.OutboxStatus;
import com.equitycart.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Writes order lifecycle events into the outbox table for reliable Kafka delivery. Part of the
 * Transactional Outbox Pattern — events are persisted atomically with the order status update (same
 * DB transaction), then published to Kafka asynchronously by {@link OutboxPoller}.
 *
 * <p>Serializes event DTOs to JSON via {@link com.fasterxml.jackson.databind.ObjectMapper} and
 * stores the FQCN in {@code payloadType} so the poller can re-hydrate the correct class.
 *
 * <p>The orderId is stored as {@code aggregateId} and used as the Kafka message key — guaranteeing
 * partition ordering for all events related to the same order.
 */
@Component
@RequiredArgsConstructor
public class OrderOutboxWriter {

  private static final Logger log = LogManager.getLogger(OrderOutboxWriter.class);

  private final ObjectMapper objectMapper;
  private final OutboxEventRepository outboxEventRepository;

  /**
   * Writes an outbox event for an order delivery. Serializes {@link OrderDeliveredEvent} to JSON
   * and persists as a PENDING outbox row within the caller's transaction.
   *
   * @param order the order entity that was just delivered
   */
  public void writeOutboxOrderDeliveredEvent(Order order) {
    OrderDeliveredEvent event =
        new OrderDeliveredEvent(
            order.getId(),
            order.getUserId(),
            createOrderItemEvent(order.getItems()),
            order.getTotalAmount(),
            LocalDateTime.now());

    String json = convertObjToJsonString(event);

    OutboxEvent outboxEvent =
        getOutboxEvent(
            order, json, event.getClass().getName(), "ORDER_DELIVERED", "order-delivered");

    outboxEventRepository.save(outboxEvent);
    log.info(
        "Outbox event written: eventType=ORDER_DELIVERED, orderId={}, topic=order-delivered",
        order.getId());
  }

  /**
   * Writes an outbox event for an order return. Serializes {@link OrderReturnedEvent} to JSON and
   * persists as a PENDING outbox row within the caller's transaction.
   *
   * @param order the order entity that was just returned
   */
  public void writeOutboxOrderReturnedEvent(Order order) {
    OrderReturnedEvent event = new OrderReturnedEvent();
    event.setOrderId(order.getId());
    event.setUserId(order.getUserId());
    event.setReturnedAt(LocalDateTime.now());

    String json = convertObjToJsonString(event);

    OutboxEvent outboxEvent =
        getOutboxEvent(order, json, event.getClass().getName(), "ORDER_RETURNED", "order-returned");

    outboxEventRepository.save(outboxEvent);
    log.info(
        "Outbox event written: eventType=ORDER_RETURNED, orderId={}, topic=order-returned",
        order.getId());
  }

  /**
   * Writes an outbox event for an order refund. Serializes {@link OrderRefundedEvent} to JSON and
   * persists as a PENDING outbox row within the caller's transaction. Includes the payment method
   * so downstream consumers (e.g., portfolio refund handler) can determine whether stock
   * restoration is needed.
   *
   * @param order the order entity that was just refunded
   */
  public void writeOutboxOrderRefundedEvent(Order order) {
    OrderRefundedEvent event =
        new OrderRefundedEvent(
            order.getId(), order.getUserId(), order.getPaymentMethod(), LocalDateTime.now());

    String json = convertObjToJsonString(event);

    OutboxEvent outboxEvent =
        getOutboxEvent(order, json, event.getClass().getName(), "ORDER_REFUNDED", "order-refunded");

    outboxEventRepository.save(outboxEvent);
    log.info(
        "Outbox event written: eventType=ORDER_REFUNDED, orderId={}, topic=order-refunded",
        order.getId());
  }

  /** Maps Order entity's line items to Kafka event DTOs (data snapshot for consumers). */
  private List<OrderItemEvent> createOrderItemEvent(List<OrderItem> orderItems) {

    return orderItems.stream()
        .map(
            orderItem -> {
              OrderItemEvent event = new OrderItemEvent();
              event.setProductId(orderItem.getProductId());
              event.setProductName(orderItem.getProductName());
              event.setQuantity(orderItem.getQuantity());
              event.setPriceAtPurchase(orderItem.getPriceAtPurchase());
              event.setSubtotal(orderItem.getSubTotal());
              return event;
            })
        .toList();
  }

  private String convertObjToJsonString(Object event) {
    String json;
    try {
      json = objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.error(
          "Failed to serialize event to JSON: class={}, error={}",
          event.getClass().getName(),
          e.getMessage());
      throw new RuntimeException(e);
    }
    return json;
  }

  private static OutboxEvent getOutboxEvent(
      Order order, String json, String className, String eventType, String topic) {
    return OutboxEvent.builder()
        .aggregateType("Order")
        .aggregateId(order.getId())
        .eventType(eventType)
        .topic(topic)
        .payload(json)
        .payloadType(className)
        .status(OutboxStatus.PENDING)
        .build();
  }
}
