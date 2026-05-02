package com.equitycart.order.cart.repository;

import com.equitycart.order.cart.dto.AddToCartRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed repository for shopping cart operations. Uses Redis Hash structure where each user's
 * cart is a Hash with product IDs as fields and serialized cart items as values. Cart entries
 * expire after {@value #CART_TTL_MINUTES} minutes of inactivity.
 */
@Repository
@RequiredArgsConstructor
public class CartRedisRepository {

  private static final Logger log = LogManager.getLogger(CartRedisRepository.class);
  private static final int CART_TTL_MINUTES = 30;

  private static final String KEY_PREFIX = "Cart:";
  private static final Duration CART_TTL = Duration.ofMinutes(CART_TTL_MINUTES);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public void addItem(String userId, AddToCartRequest item) {
    String key = KEY_PREFIX + userId;
    String field = item.productId().toString();
    String value = serialize(item);

    redisTemplate.opsForHash().put(key, field, value);
    redisTemplate.expire(key, CART_TTL);
    log.info(
        "Added product {} to cart for user: {}, qty: {}",
        item.productId(),
        userId,
        item.quantity());
  }

  public void removeItem(String userId, Long productId) {
    redisTemplate.opsForHash().delete(KEY_PREFIX + userId, productId.toString());
    log.info("Removed product {} from cart for user: {}", productId, userId);
  }

  public Map<Object, Object> getAllItems(String userId) {
    log.debug("Fetching all cart items for user: {}", userId);
    return redisTemplate.opsForHash().entries(KEY_PREFIX + userId);
  }

  public Optional<Long> getTtl(String userId) {
    Long ttl = redisTemplate.getExpire(KEY_PREFIX + userId);
    return ttl > 0 ? Optional.of(ttl) : Optional.empty();
  }

  public void clearCart(String userId) {
    redisTemplate.delete(KEY_PREFIX + userId);
    log.info("Cleared entire cart for user: {}", userId);
  }

  private String serialize(AddToCartRequest item) {
    try {
      return objectMapper.writeValueAsString(item);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize cart item for product: {}", item.productId(), e);
      throw new IllegalArgumentException("Failed to serialize cart item", e);
    }
  }
}
