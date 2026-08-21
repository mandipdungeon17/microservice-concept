package com.equitycart.portfolio.cqrs.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.portfolio.async.dto.PortfolioProjectionEvent;
import com.equitycart.portfolio.cqrs.synchronizer.PortfolioReadModelSynchronizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioReadModelOutboxConsumerTest {

  @Mock private ObjectMapper objectMapper;
  @Mock private PortfolioReadModelSynchronizer portfolioReadModelSynchronizer;
  @InjectMocks private PortfolioReadModelOutboxConsumer consumer;

  @Test
  void consumeShouldDeserializeEventAndTriggerReadModelRebuild() throws Exception {
    PortfolioProjectionEvent event =
        new PortfolioProjectionEvent(
            "evt-1",
            "SHARES_PURCHASED",
            55L,
            "AAPL",
            new BigDecimal("1"),
            new BigDecimal("100"),
            new BigDecimal("100"),
            LocalDateTime.now(),
            Map.of("k", "v"));
    when(objectMapper.readValue("{json}", PortfolioProjectionEvent.class)).thenReturn(event);

    consumer.consume("{json}");

    verify(portfolioReadModelSynchronizer).rebuildReadModelForUser(55L);
  }
}

