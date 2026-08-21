package com.equitycart.portfolio.saga.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.saga.entity.GiftSaga;
import com.equitycart.portfolio.saga.enums.GiftSagaStatus;
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
class GiftSagaOutboxWriterTest {

  @Mock private PortfolioOutboxEventRepository outboxEventRepository;
  @Mock private ObjectMapper objectMapper;
  @InjectMocks private GiftSagaOutboxWriter writer;

  @Test
  void writeGiftLifeCycleEventShouldPersistPendingOutboxEvent() throws Exception {
    GiftSaga saga =
        GiftSaga.builder()
            .sagaId(UUID.randomUUID())
            .giverUserId(1L)
            .receiverUserId(2L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .transferPricePerShare(new BigDecimal("100"))
            .transferDollarValue(new BigDecimal("100"))
            .idempotencyKey("idem")
            .status(GiftSagaStatus.INITIATED)
            .build();
    saga.setId(99L);
    saga.setUpdatedAt(LocalDateTime.now());
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"ok\":true}");

    writer.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_STARTED", "INIT");

    ArgumentCaptor<PortfolioOutboxEvent> captor = ArgumentCaptor.forClass(PortfolioOutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    PortfolioOutboxEvent outboxEvent = captor.getValue();
    assertEquals("GiftSaga", outboxEvent.getAggregateType());
    assertEquals("gift-saga", outboxEvent.getTopic());
    assertEquals(PortfolioOutboxStatus.PENDING, outboxEvent.getStatus());
  }

  @Test
  void writeGiftLifeCycleEventShouldThrowOnSerializationFailure() throws Exception {
    GiftSaga saga =
        GiftSaga.builder()
            .sagaId(UUID.randomUUID())
            .giverUserId(1L)
            .receiverUserId(2L)
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("1"))
            .transferPricePerShare(new BigDecimal("100"))
            .transferDollarValue(new BigDecimal("100"))
            .idempotencyKey("idem")
            .status(GiftSagaStatus.INITIATED)
            .build();
    saga.setId(99L);
    saga.setUpdatedAt(LocalDateTime.now());
    when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

    assertThrows(
        RuntimeException.class, () -> writer.writeGiftLifeCycleEvent(saga, "GIFT_SAGA_STARTED", "INIT"));
  }
}
