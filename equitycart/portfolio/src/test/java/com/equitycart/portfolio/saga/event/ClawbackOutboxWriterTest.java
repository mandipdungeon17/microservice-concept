package com.equitycart.portfolio.saga.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.saga.entity.ClawbackSaga;
import com.equitycart.portfolio.saga.enums.ClawbackStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClawbackOutboxWriterTest {

  @Mock private PortfolioOutboxEventRepository outboxEventRepository;
  @Mock private ObjectMapper objectMapper;
  @InjectMocks private ClawbackOutboxWriter writer;

  @Test
  void writeClawbackLifeCycleEventShouldPersistPendingOutboxEvent() throws Exception {
    ClawbackSaga saga =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(1L)
            .orderId(20L)
            .rewardId(30L)
            .rewardQuantity(new BigDecimal("1"))
            .status(ClawbackStatus.INITIATED)
            .build();
    saga.setId(5L);
    saga.setUpdatedAt(LocalDateTime.now());
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    writer.writeClawbackLifeCycleEvent(saga, "CLAWBACK_STARTED", "INIT");

    ArgumentCaptor<PortfolioOutboxEvent> captor = ArgumentCaptor.forClass(PortfolioOutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    PortfolioOutboxEvent outboxEvent = captor.getValue();
    assertEquals("ClawbackSaga", outboxEvent.getAggregateType());
    assertEquals("clawback-saga", outboxEvent.getTopic());
    assertEquals(PortfolioOutboxStatus.PENDING, outboxEvent.getStatus());
  }

  @Test
  void writeClawbackLifeCycleEventShouldThrowOnSerializationFailure() throws Exception {
    ClawbackSaga saga =
        ClawbackSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(1L)
            .orderId(20L)
            .rewardId(30L)
            .rewardQuantity(new BigDecimal("1"))
            .status(ClawbackStatus.INITIATED)
            .build();
    saga.setId(5L);
    saga.setUpdatedAt(LocalDateTime.now());
    when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

    assertThrows(
        RuntimeException.class,
        () -> writer.writeClawbackLifeCycleEvent(saga, "CLAWBACK_STARTED", "INIT"));
  }
}
