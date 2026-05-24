package com.equitycart.commons.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Event DTO published to the {@code sell-to-spend-saga} Kafka topic via the outbox. Tracks saga
 * lifecycle transitions for observability — a monitoring consumer can subscribe to visualize saga
 * progress, detect stuck sagas, and alert on failures.
 *
 * <p>Published at each state transition: SAGA_STARTED, SAGA_STEP_COMPLETED, SAGA_COMPLETED,
 * SAGA_COMPENSATING, SAGA_COMPENSATED, SAGA_FAILED.
 *
 * @param sagaId UUID correlation ID for the saga instance
 * @param orderId the order being paid via sell-to-spend
 * @param userId the user performing the operation
 * @param eventType lifecycle event name (e.g. "SAGA_COMPLETED")
 * @param status current {@link com.equitycart.portfolio.saga.enums.SagaStatus} name
 * @param stepName which step completed (nullable — only set for SAGA_STEP_COMPLETED)
 * @param failureReason exception message (nullable — only set on failure/compensation failure)
 * @param timestamp when this event was created
 */
public record SagaLifecycleEvent(
    UUID sagaId,
    Long orderId,
    Long userId,
    String eventType,
    String status,
    String stepName,
    String failureReason,
    LocalDateTime timestamp) {}
