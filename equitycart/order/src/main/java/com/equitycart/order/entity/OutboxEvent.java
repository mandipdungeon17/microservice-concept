package com.equitycart.order.entity;

import com.equitycart.commons.entity.BaseEntity;
import com.equitycart.order.enums.OutboxStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/**
 * Outbox event entity for the Transactional Outbox Pattern. Represents a message that needs to be
 * published to a Kafka topic. Written atomically with business data (same DB transaction), then
 * relayed asynchronously by either:
 *
 * <ul>
 *   <li>{@link com.equitycart.order.event.OutboxPoller} — polls PENDING rows every 5s (active when
 *       {@code !cdc} profile)
 *   <li>Debezium CDC — reads PostgreSQL WAL INSERT events via Kafka Connect (active when {@code
 *       cdc} profile)
 * </ul>
 *
 * <p>Lifecycle: {@code PENDING → SENT} (poller mode). In CDC mode, status remains PENDING because
 * Debezium captures the INSERT from the WAL without updating the row.
 *
 * <p>The {@code payload} column uses {@code columnDefinition = "text"} (not {@code @Lob}) to store
 * JSON inline. {@code @Lob} creates an OID reference in PostgreSQL — Debezium cannot follow OID
 * references when reading the WAL, it would publish the OID number instead of the JSON content.
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

  // @Lob creates OID reference in PostgreSQL — Debezium CDC reads OID number from WAL, not the
  // referenced content. text column stores JSON inline, making it CDC-compatible.
  @Column(columnDefinition = "text")
  String payload; // JSON-serialized event DTO

  String payloadType; // Class name to serialize/deserialize payload into

  @Enumerated(EnumType.STRING)
  OutboxStatus status; // PENDING → SENT

  LocalDateTime publishedAt; // timestamp of when the event was published to Kafka
}
