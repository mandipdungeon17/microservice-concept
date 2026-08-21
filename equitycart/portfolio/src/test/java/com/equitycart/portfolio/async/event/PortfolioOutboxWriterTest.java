package com.equitycart.portfolio.async.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.entity.PortfolioOutboxEvent;
import com.equitycart.portfolio.async.enums.PortfolioOutboxStatus;
import com.equitycart.portfolio.async.repository.PortfolioOutboxEventRepository;
import com.equitycart.portfolio.entity.Holding;
import com.equitycart.portfolio.entity.Portfolio;
import com.equitycart.portfolio.entity.StockBackReward;
import com.equitycart.portfolio.saga.entity.SellToSpendSaga;
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
class PortfolioOutboxWriterTest {

  @Mock private ObjectMapper objectMapper;
  @Mock private PortfolioOutboxEventRepository outboxEventRepository;

  @InjectMocks private PortfolioOutboxWriter outboxWriter;

  @Test
  void writeSharesPurchasedEventShouldPersistPendingOutboxRow() throws Exception {
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"event\":\"ok\"}");

    Holding holding =
        Holding.builder()
            .tickerSymbol("AAPL")
            .quantity(new BigDecimal("2"))
            .averageBuyPrice(new BigDecimal("100"))
            .portfolio(Portfolio.builder().userId(5L).build())
            .build();
    holding.setId(101L);

    outboxWriter.writeSharesPurchasedEvent(holding);

    ArgumentCaptor<PortfolioOutboxEvent> captor = ArgumentCaptor.forClass(PortfolioOutboxEvent.class);
    verify(outboxEventRepository).save(captor.capture());
    PortfolioOutboxEvent saved = captor.getValue();
    assertEquals("Portfolio", saved.getAggregateType());
    assertEquals(5L, saved.getAggregateId());
    assertEquals("SHARES_PURCHASED", saved.getEventType());
    assertEquals("portfolio-readmodel-events", saved.getTopic());
    assertEquals(PortfolioOutboxStatus.PENDING, saved.getStatus());
  }

  @Test
  void writeRewardVestedEventShouldThrowWhenSerializationFails() throws Exception {
    when(objectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("boom") {});

    StockBackReward reward =
        StockBackReward.builder()
            .orderId(10L)
            .userId(5L)
            .tickerSymbol("AAPL")
            .sharesEarned(new BigDecimal("0.1"))
            .dollarValue(new BigDecimal("10"))
            .vestingDate(LocalDateTime.now())
            .build();
    reward.setId(22L);

    assertThrows(RuntimeException.class, () -> outboxWriter.writeRewardVestedEvent(reward));
  }

  @Test
  void writeAllEventTypesShouldPersistOutboxRows() throws Exception {
    when(objectMapper.writeValueAsString(any())).thenReturn("{\"event\":\"ok\"}");

    Holding holding =
        Holding.builder()
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("3"))
            .averageBuyPrice(new BigDecimal("120"))
            .portfolio(Portfolio.builder().userId(9L).build())
            .build();
    holding.setId(300L);

    StockBackReward reward =
        StockBackReward.builder()
            .orderId(99L)
            .userId(9L)
            .tickerSymbol("MSFT")
            .sharesEarned(new BigDecimal("0.3"))
            .dollarValue(new BigDecimal("36"))
            .vestingDate(LocalDateTime.now().plusDays(1))
            .build();
    reward.setId(400L);

    SellToSpendSaga saga =
        SellToSpendSaga.builder()
            .sagaId(UUID.randomUUID())
            .userId(9L)
            .orderId(199L)
            .tickerSymbol("MSFT")
            .quantity(new BigDecimal("1"))
            .pricePerShare(new BigDecimal("120"))
            .saleProceeds(new BigDecimal("120"))
            .build();

    outboxWriter.writeSharesSoldEvent(holding, new BigDecimal("1"), new BigDecimal("120"));
    outboxWriter.writeRewardGrantedEvent(reward);
    outboxWriter.writeRewardCancelledEvent(reward);
    outboxWriter.writeRefundRestoredEvent(saga, 199L);
    outboxWriter.writeSellToSpendEvent(saga);
    outboxWriter.writeSellToSpendCompensatedEvent(saga);

    verify(outboxEventRepository, org.mockito.Mockito.times(6)).save(any(PortfolioOutboxEvent.class));
  }
}
