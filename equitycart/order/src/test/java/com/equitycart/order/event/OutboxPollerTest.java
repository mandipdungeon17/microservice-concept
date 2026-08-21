package com.equitycart.order.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.order.entity.OutboxEvent;
import com.equitycart.order.enums.OutboxStatus;
import com.equitycart.order.repository.OutboxEventRepository;
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
  @Mock private OutboxEventRepository outboxEventRepository;
  @Mock private ObjectMapper objectMapper;

  @InjectMocks private OutboxPoller outboxPoller;

  @Test
  void pollAndPublishShouldDoNothingWhenNoPendingEvents() {
    when(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).thenReturn(List.of());

    outboxPoller.pollAndPublish();

    verify(kafkaTemplate, never()).send(any(), any(), any());
  }

  @Test
  void pollAndPublishShouldSendAndMarkEventAsSent() throws Exception {
    OutboxEvent event = OutboxEvent.builder().topic("order-delivered").aggregateId(77L).payloadType("java.lang.String").payload("\"ok\"").status(OutboxStatus.PENDING).build();
    event.setId(1L);
    when(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).thenReturn(List.of(event));
    when(objectMapper.readValue("\"ok\"", String.class)).thenReturn("ok");
    when(kafkaTemplate.send(eq("order-delivered"), eq("77"), eq("ok")))
        .thenReturn(CompletableFuture.completedFuture(null));

    outboxPoller.pollAndPublish();

    verify(outboxEventRepository).save(event);
    verify(kafkaTemplate).send("order-delivered", "77", "ok");
  }

  @Test
  void pollAndPublishShouldLeavePendingWhenPublishingFails() throws Exception {
    OutboxEvent event =
        OutboxEvent.builder()
            .topic("order-delivered")
            .aggregateId(77L)
            .payloadType("java.lang.String")
            .payload("\"ok\"")
            .status(OutboxStatus.PENDING)
            .build();
    event.setId(2L);
    when(outboxEventRepository.findByStatus(OutboxStatus.PENDING)).thenReturn(List.of(event));
    when(objectMapper.readValue("\"ok\"", String.class)).thenReturn("ok");
    when(kafkaTemplate.send(eq("order-delivered"), eq("77"), eq("ok")))
        .thenReturn(
            CompletableFuture.failedFuture(
                new RuntimeException("kafka unavailable")));

    outboxPoller.pollAndPublish();

    verify(outboxEventRepository, never()).save(event);
  }
}
