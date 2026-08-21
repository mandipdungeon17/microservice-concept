package com.equitycart.portfolio.saga.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
import com.equitycart.portfolio.saga.enums.SagaStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SagaOutboxWriterTest {

  @Mock private PortfolioOutboxEventRepository outboxEventRepository;
  @Mock private ObjectMapper objectMapper;
  @InjectMocks private SagaOutboxWriter writer;

  @Test
  void writeSagaLifecycleEventShouldPersistPendingOutboxEvent() throws Exception {
    SellToSpendSaga saga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(10L)
            .orderId(100L)
            .status(SagaStatus.STARTED)
            .failureReason(null)
            .compensationStartedAt(LocalDateTime.now())
            .build();
    saga.setId(5L);
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    writer.writeSagaLifecycleEvent(saga, "SAGA_STARTED", "INIT");

    ArgumentCaptor<PortfolioOutboxEvent> captor = ArgumentCaptor.forClass(PortfolioOutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    PortfolioOutboxEvent outboxEvent = captor.getValue();
    assertEquals("SellToSpendSaga", outboxEvent.getAggregateType());
    assertEquals(PortfolioOutboxStatus.PENDING, outboxEvent.getStatus());
    assertEquals("sell-to-spend-saga", outboxEvent.getTopic());
  }

  @Test
  void writeSagaLifecycleEventShouldThrowOnSerializationFailure() throws Exception {
    SellToSpendSaga saga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(10L)
            .orderId(100L)
            .status(SagaStatus.STARTED)
            .build();
    saga.setId(5L);
    when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

    assertThrows(
        RuntimeException.class, () -> writer.writeSagaLifecycleEvent(saga, "SAGA_STARTED", "INIT"));
  }
}
