package com.equitycart.order.cart.service.impl;

import com.equitycart.order.cart.dto.AddToCartRequest;
import com.equitycart.order.cart.dto.CartItemResponse;
import com.equitycart.order.cart.dto.CartResponse;
import com.equitycart.order.cart.repository.CartRedisRepository;
import com.equitycart.order.cart.service.api.CartService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link CartService} that orchestrates cart operations via {@link
 * CartRedisRepository}. Handles DTO transformation, total calculation, and TTL-based expiry
 * reporting.
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

  private static final Logger log = LogManager.getLogger(CartServiceImpl.class);

  private final CartRedisRepository cartRedisRepository;
  private final ObjectMapper objectMapper;

  /** {@inheritDoc} */
  @Override
  public void addItem(String userId, AddToCartRequest request) {
    log.info("Adding product {} to cart for user: {}", request.productId(), userId);
    cartRedisRepository.addItem(userId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void removeItem(String userId, Long productId) {
    log.info("Removing product {} from cart for user: {}", productId, userId);
    cartRedisRepository.removeItem(userId, productId);
  }

  /** {@inheritDoc} */
  @Override
  public CartResponse getCart(String userId) {
    log.debug("Retrieving cart for user: {}", userId);
    Map<Object, Object> entries = cartRedisRepository.getAllItems(userId);

    if (entries.isEmpty()) {
      log.debug("Cart is empty for user: {}", userId);
      return new CartResponse(userId, List.of(), BigDecimal.ZERO, null);
    }

    List<CartItemResponse> items =
        entries.values().stream()
            .map(value -> deserialize((String) value))
            .map(
                item ->
                    new CartItemResponse(
                        item.productId(),
                        item.quantity(),
                        item.price(),
                        item.price().multiply(BigDecimal.valueOf(item.quantity()))))
            .toList();

    BigDecimal total =
        items.stream().map(CartItemResponse::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

    Instant expiresAt =
        cartRedisRepository.getTtl(userId).map(ttl -> Instant.now().plusSeconds(ttl)).orElse(null);

    log.debug("Cart retrieved for user: {} with {} items, total: {}", userId, items.size(), total);
    return new CartResponse(userId, items, total, expiresAt);
  }

  /** {@inheritDoc} */
  @Override
  public void clearCart(String userId) {
    log.info("Clearing cart for user: {}", userId);
    cartRedisRepository.clearCart(userId);
  }

  private AddToCartRequest deserialize(String json) {
    try {
      return objectMapper.readValue(json, AddToCartRequest.class);
    } catch (JsonProcessingException e) {
      log.warn("Failed to deserialize cart item from JSON: {}", json, e);
      throw new IllegalStateException("Failed to deserialize cart item", e);
    }
  }
}
