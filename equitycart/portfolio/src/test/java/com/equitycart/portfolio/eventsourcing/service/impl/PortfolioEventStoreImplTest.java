package com.equitycart.portfolio.eventsourcing.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.eventsourcing.document.PortfolioEvent;
import com.equitycart.portfolio.eventsourcing.enums.PortfolioEventType;
import com.equitycart.portfolio.eventsourcing.repository.PortfolioEventRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioEventStoreImplTest {

  @Mock private PortfolioEventRepository portfolioEventRepository;
  @InjectMocks private PortfolioEventStoreImpl eventStore;

  @Test
  void appendShouldSaveEventWithSequenceOneWhenNoPreviousEvent() {
    when(portfolioEventRepository.findTopByUserIdOrderBySequenceNumberDesc(1L))
        .thenReturn(Optional.empty());

    eventStore.append(
        1L,
        PortfolioEventType.SHARES_PURCHASED,
        "AAPL",
        new BigDecimal("2"),
        new BigDecimal("100"),
        new BigDecimal("200"),
        Map.of("source", "test"));

    ArgumentCaptor<PortfolioEvent> captor = ArgumentCaptor.forClass(PortfolioEvent.class);
    verify(portfolioEventRepository).save(captor.capture());
    assertEquals(1L, captor.getValue().getSequenceNumber());
  }

  @Test
  void appendShouldNotThrowWhenRepositoryFails() {
    when(portfolioEventRepository.findTopByUserIdOrderBySequenceNumberDesc(1L))
        .thenThrow(new RuntimeException("db down"));

    assertDoesNotThrow(
        () ->
            eventStore.append(
                1L,
                PortfolioEventType.SHARES_PURCHASED,
                "AAPL",
                new BigDecimal("2"),
                new BigDecimal("100"),
                new BigDecimal("200"),
                Map.of()));
    verify(portfolioEventRepository, org.mockito.Mockito.never()).save(any());
  }
}

