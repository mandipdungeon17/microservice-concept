package com.equitycart.order.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.order.enums.OutboxStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/**
 * Outbox event entity for the Transactional Outbox Pattern. Represents a message that needs to be
 * published to a Kafka topic. Written atomically with business data (same DB transaction), then
 * polled and published asynchronously by {@link com.equitycart.order.event.OutboxPoller}.
 *
 * <p>Lifecycle: {@code PENDING → SENT}. Rows remain in the table after sending (audit trail). In
 * production, a cleanup job would archive or delete old SENT rows.
 */
@Entity
@Table(name = "outbox_events")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class OutboxEvent extends BaseEntity {

  String aggregateType; // e.g. "Order", "StockBackReward" — the domain object type

  Long aggregateId; // e.g. orderId — for debugging/queries

  String eventType; // e.g. "ORDER_DELIVERED" or "ORDER_RETURNED"

  String topic; // Kafka topic to publish to, e.g. "order-delivered"

  @Lob String payload; // JSON-serialized event DTO

  String payloadType; // Class name to serialize/deserialize payload into

  @Enumerated(EnumType.STRING)
  OutboxStatus status; // PENDING → SENT

  LocalDateTime publishedAt; // timestamp of when the event was published to Kafka
}
