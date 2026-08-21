package com.equitycart.order.cart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.order.cart.dto.AddToCartRequest;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.repository.CartRedisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

  @Mock private CartRedisRepository cartRedisRepository;
  @Mock private ObjectMapper objectMapper;
  @InjectMocks private CartServiceImpl cartService;

  @Test
  void getCartShouldReturnEmptyResponseWhenNoEntries() {
    when(cartRedisRepository.getAllItems("10")).thenReturn(Map.of());

    CartResponse response = cartService.getCart("10");

    assertEquals("10", response.userId());
    assertEquals(BigDecimal.ZERO, response.total());
    assertEquals(0, response.items().size());
  }

  @Test
  void getCartShouldAggregateSubtotalsFromEntries() throws Exception {
    AddToCartRequest item = new AddToCartRequest(1L, 2, new BigDecimal("5.50"));
    when(cartRedisRepository.getAllItems("10")).thenReturn(Map.of("1", "{\"mock\":\"json\"}"));
    when(cartRedisRepository.getTtl("10")).thenReturn(Optional.of(60L));
    when(objectMapper.readValue("{\"mock\":\"json\"}", AddToCartRequest.class)).thenReturn(item);

    CartResponse response = cartService.getCart("10");

    assertEquals(new BigDecimal("11.00"), response.total());
    assertEquals(1, response.items().size());
    assertNotNull(response.expiresAt());
  }

  @Test
  void getCartShouldThrowWhenEntryDeserializationFails() throws Exception {
    when(cartRedisRepository.getAllItems("10")).thenReturn(Map.of("1", "broken-json"));
    when(objectMapper.readValue("broken-json", AddToCartRequest.class))
        .thenThrow(new JsonProcessingException("bad") {});

    assertThrows(IllegalStateException.class, () -> cartService.getCart("10"));
  }

  @Test
  void addRemoveAndClearShouldDelegateToRepository() {
    AddToCartRequest request = new AddToCartRequest(10L, 3, new BigDecimal("9.99"));

    cartService.addItem("12", request);
    cartService.removeItem("12", 10L);
    cartService.clearCart("12");

    verify(cartRedisRepository).addItem("12", request);
    verify(cartRedisRepository).removeItem("12", 10L);
    verify(cartRedisRepository).clearCart("12");
  }

  @Test
  void getCartShouldReturnNullExpiryWhenTtlMissing() throws Exception {
    AddToCartRequest item = new AddToCartRequest(1L, 1, new BigDecimal("4.00"));
    when(cartRedisRepository.getAllItems("99")).thenReturn(Map.of("1", "{\"ok\":true}"));
    when(cartRedisRepository.getTtl("99")).thenReturn(Optional.empty());
    when(objectMapper.readValue("{\"ok\":true}", AddToCartRequest.class)).thenReturn(item);

    CartResponse response = cartService.getCart("99");

    assertEquals(new BigDecimal("4.00"), response.total());
    assertEquals(null, response.expiresAt());
  }
}
