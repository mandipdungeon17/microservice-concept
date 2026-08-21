package com.equitycart.portfolio.async.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

  @Mock private KafkaTemplate<String, Object> kafkaTemplate;
  @Mock private PortfolioOutboxEventRepository outboxEventRepository;
  @Mock private ObjectMapper objectMapper;
  @InjectMocks private OutboxPoller outboxPoller;

  @Test
  void pollAndPublishShouldDoNothingWhenNoPendingRows() {
    when(outboxEventRepository.findByStatus(PortfolioOutboxStatus.PENDING)).thenReturn(List.of());

    outboxPoller.pollAndPublish();

    verify(kafkaTemplate, never()).send(any(), any(), any());
  }

  @Test
  void pollAndPublishShouldPublishAndMarkAsSent() throws Exception {
    PortfolioOutboxEvent event =
        PortfolioOutboxEvent.builder()
            .topic("portfolio-readmodel-events")
            .aggregateId(7L)
            .payloadType("java.lang.String")
            .payload("\"ok\"")
            .status(PortfolioOutboxStatus.PENDING)
            .build();
    event.setId(1L);
    when(outboxEventRepository.findByStatus(PortfolioOutboxStatus.PENDING)).thenReturn(List.of(event));
    when(objectMapper.readValue("\"ok\"", String.class)).thenReturn("ok");
    when(kafkaTemplate.send(eq("portfolio-readmodel-events"), eq("7"), eq("ok")))
        .thenReturn(CompletableFuture.completedFuture(null));

    outboxPoller.pollAndPublish();

    verify(kafkaTemplate).send("portfolio-readmodel-events", "7", "ok");
    verify(outboxEventRepository).save(event);
  }

  @Test
  void pollAndPublishShouldNotSaveWhenPublishFails() throws Exception {
    PortfolioOutboxEvent event =
        PortfolioOutboxEvent.builder()
            .topic("portfolio-readmodel-events")
            .aggregateId(7L)
            .payloadType("java.lang.String")
            .payload("\"ok\"")
            .status(PortfolioOutboxStatus.PENDING)
            .build();
    event.setId(2L);
    when(outboxEventRepository.findByStatus(PortfolioOutboxStatus.PENDING)).thenReturn(List.of(event));
    when(objectMapper.readValue("\"ok\"", String.class)).thenReturn("ok");
    when(kafkaTemplate.send(eq("portfolio-readmodel-events"), eq("7"), eq("ok")))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka")));

    outboxPoller.pollAndPublish();

    verify(outboxEventRepository, never()).save(event);
  }
}
