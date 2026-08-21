package com.equitycart.marketdata.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.marketdata.client.AlphaVantageClient;
import com.equitycart.marketdata.dto.HealthScoreResponse;
import com.equitycart.marketdata.dto.StockPriceResponse;
import com.equitycart.marketdata.dto.StockQuote;
import com.equitycart.marketdata.entity.PriceHistory;
import com.equitycart.marketdata.repository.PriceHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceImplTest {

  @Mock private AlphaVantageClient alphaVantageClient;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ObjectMapper objectMapper;
  @Mock private PriceHistoryRepository priceHistoryRepository;

  @InjectMocks private MarketDataServiceImpl marketDataService;

  @SuppressWarnings("unchecked")
  @Test
  void getPriceShouldReturnCachedValueWhenPresent() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    StockPriceResponse cached =
        new StockPriceResponse(
            "AAPL", new BigDecimal("100.00"), new BigDecimal("1.00"), "1.00%", 1_000L, "2026-08-21", Instant.now());

    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.get("price:AAPL")).thenReturn("{json}");
    when(objectMapper.readValue("{json}", StockPriceResponse.class)).thenReturn(cached);

    StockPriceResponse response = marketDataService.getPrice("aapl");

    assertEquals("AAPL", response.symbol());
    assertEquals(new BigDecimal("100.00"), response.price());
  }

  @Test
  void getHistoryShouldDelegateToRepository() {
    List<PriceHistory> expected = List.of(PriceHistory.builder().symbol("AAPL").build());
    when(priceHistoryRepository.findBySymbolAndFetchedAtBetween(
            org.mockito.ArgumentMatchers.eq("AAPL"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(expected);

    List<PriceHistory> actual = marketDataService.getHistory("AAPL", 7);

    assertEquals(expected, actual);
  }

  @Test
  void getHealthScoreShouldReturnNullWhenPriceUnavailable() {
    MarketDataServiceImpl spy = org.mockito.Mockito.spy(marketDataService);
    doReturn(null).when(spy).getPrice("AAPL");

    HealthScoreResponse response = spy.getHealthScore("AAPL");

    assertNull(response);
  }

  @SuppressWarnings("unchecked")
  @Test
  void getPriceShouldCallApiAndCacheWhenMissing() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    StockQuote quote =
        new StockQuote(
            "MSFT",
            new BigDecimal("250.00"),
            new BigDecimal("2.00"),
            "0.8%",
            2_000_000L,
            "2026-08-21",
            Instant.now());
    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.get("price:MSFT")).thenReturn(null);
    when(alphaVantageClient.getStockQuote("MSFT")).thenReturn(Mono.just(quote));
    when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(StockPriceResponse.class)))
        .thenReturn("{\"ok\":true}");

    StockPriceResponse response = marketDataService.getPrice("MSFT");

    assertEquals("MSFT", response.symbol());
    verify(ops).set(org.mockito.ArgumentMatchers.eq("price:MSFT"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void getPriceShouldThrowWhenCachedJsonCannotDeserialize() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.get("price:TSLA")).thenReturn("bad");
    when(objectMapper.readValue("bad", StockPriceResponse.class))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad") {});

    assertThrows(IllegalArgumentException.class, () -> marketDataService.getPrice("TSLA"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void getPriceShouldThrowWhenApiResultCannotSerializeForCache() throws Exception {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    StockQuote quote =
        new StockQuote(
            "NFLX",
            new BigDecimal("500.00"),
            new BigDecimal("1.00"),
            "0.2%",
            1000L,
            "2026-08-21",
            Instant.now());
    when(redisTemplate.opsForValue()).thenReturn(ops);
    when(ops.get("price:NFLX")).thenReturn(null);
    when(alphaVantageClient.getStockQuote("NFLX")).thenReturn(Mono.just(quote));
    when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(StockPriceResponse.class)))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad") {});

    assertThrows(IllegalArgumentException.class, () -> marketDataService.getPrice("NFLX"));
  }

  @Test
  void getPricesShouldMapEachSymbol() {
    MarketDataServiceImpl spy = org.mockito.Mockito.spy(marketDataService);
    doReturn(
            new StockPriceResponse(
                "AAPL", BigDecimal.ONE, BigDecimal.ZERO, "0%", 1L, "2026-08-21", Instant.now()))
        .when(spy)
        .getPrice("AAPL");
    doReturn(
            new StockPriceResponse(
                "MSFT", BigDecimal.TEN, BigDecimal.ZERO, "0%", 1L, "2026-08-21", Instant.now()))
        .when(spy)
        .getPrice("MSFT");

    List<StockPriceResponse> responses = spy.getPrices(List.of("AAPL", "MSFT"));

    assertEquals(2, responses.size());
    assertEquals("MSFT", responses.get(1).symbol());
  }

  @Test
  void evictPriceCacheShouldDeleteUppercaseKey() {
    marketDataService.evictPriceCache("aapl");
    verify(redisTemplate).delete("price:AAPL");
  }

  @Test
  void getHealthScoreShouldApplySignalsAndClamp() {
    MarketDataServiceImpl spy = org.mockito.Mockito.spy(marketDataService);
    doReturn(
            new StockPriceResponse(
                "AAPL",
                new BigDecimal("120"),
                new BigDecimal("3"),
                "3.5%",
                2_500_000L,
                "2026-08-21",
                Instant.now()))
        .when(spy)
        .getPrice("AAPL");
    doReturn(
            List.of(
                PriceHistory.builder().price(new BigDecimal("100")).build(),
                PriceHistory.builder().price(new BigDecimal("120")).build()))
        .when(spy)
        .getHistory("AAPL", 7);

    HealthScoreResponse response = spy.getHealthScore("AAPL");

    assertEquals("AAPL", response.symbol());
    assertTrue(response.score() > 50);
  }

  @Test
  void getHealthScoreShouldHandleNegativeSignalsAndInsufficientHistory() {
    MarketDataServiceImpl spy = org.mockito.Mockito.spy(marketDataService);
    doReturn(
            new StockPriceResponse(
                "AAPL",
                new BigDecimal("120"),
                new BigDecimal("-3"),
                "-3.5%",
                500L,
                "2026-08-21",
                Instant.now()))
        .when(spy)
        .getPrice("AAPL");
    doReturn(List.of(PriceHistory.builder().price(new BigDecimal("120")).build()))
        .when(spy)
        .getHistory("AAPL", 7);

    HealthScoreResponse response = spy.getHealthScore("AAPL");

    assertEquals("AAPL", response.symbol());
    assertTrue(response.score() < 50);
  }

  @Test
  void streamPriceShouldMapQuoteToResponse() {
    when(alphaVantageClient.getStockQuote("AAPL"))
        .thenReturn(
            Mono.just(
                new StockQuote(
                    "AAPL", BigDecimal.ONE, BigDecimal.ZERO, "0%", 1L, "2026-08-21", Instant.now())));

    Flux<StockPriceResponse> stream = marketDataService.streamPrice("AAPL");
    StockPriceResponse first = stream.take(1).blockFirst(java.time.Duration.ofSeconds(6));

    assertEquals("AAPL", first.symbol());
    verify(alphaVantageClient).getStockQuote("AAPL");
  }

  @Test
  void streamPriceShouldSuppressErrors() {
    when(alphaVantageClient.getStockQuote("AAPL")).thenReturn(Mono.error(new RuntimeException("down")));

    StockPriceResponse first =
        marketDataService.streamPrice("AAPL").take(1).blockFirst(java.time.Duration.ofSeconds(6));

    assertNull(first);
    verify(alphaVantageClient).getStockQuote("AAPL");
  }
}
