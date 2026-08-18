package com.equitycart.order.lock;

import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed lock manager for flash-sale purchases.
 *
 * <p>Conceptually this is the first concurrency gate before inventory mutation: for one product,
 * only one requestId can hold the lock at a time. Different products use different Redis keys and
 * therefore can proceed in parallel.
 *
 * <p>Technically it uses Redis {@code SET NX EX} via {@link StringRedisTemplate#setIfAbsent} with a
 * short TTL to avoid lock leaks. Release uses a Lua compare-and-delete script so one request cannot
 * accidentally release another request's lock.
 */
@Component
@RequiredArgsConstructor
public class FlashSaleLockManager {

  private static final Logger log = LogManager.getLogger(FlashSaleLockManager.class);

  private static final String LOCK_KEY_PREFIX = "flash_sale:lock:";

  @Value("${equitycart.flash-sale.lock-ttl-seconds:10}")
  private long lockTtlSeconds; // Lock time-to-live

  private static final RedisScript<Long> RELEASE_SCRIPT =
      RedisScript.of(
          """
                        if redis.call('get', KEYS[1]) == ARGV[1] then
                          return redis.call('del', KEYS[1])
                        end
                        return 0
                        """,
          Long.class);

  private final StringRedisTemplate redisTemplate;

  /**
   * Attempts to acquire a product-scoped lock.
   *
   * @param productId product identifier used to derive the Redis lock key
   * @param requestId ownership token stored as lock value (idempotency key in this flow)
   * @return {@code true} if lock was acquired, otherwise {@code false}
   */
  public boolean tryAcquireLock(Long productId, String requestId) {
    String lockKey = lockKey(productId);
    Boolean acquired =
        redisTemplate
            .opsForValue()
            .setIfAbsent(lockKey, requestId, lockTtlSeconds, TimeUnit.SECONDS);

    boolean success = Boolean.TRUE.equals(acquired);

    if (success) {
      log.debug("Flash-sale lock acquired productId={} requestId={}", productId, requestId);
    }
    return success;
  }

  /**
   * Releases lock only if {@code requestId} still owns it.
   *
   * <p>This avoids the classic race where a slow request wakes up and deletes a lock acquired by a
   * newer request.
   *
   * @param productId product identifier used to derive lock key
   * @param requestId owner token expected in Redis before delete
   */
  public void releaseLock(Long productId, String requestId) {
    String lockKey = lockKey(productId);
    Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey), requestId);

    if (Long.valueOf(1L).equals(released)) {
      log.debug("Flash-sale lock released productId={} requestId={}", productId, requestId);
    }
  }

  /** Builds a per-product lock key. */
  private String lockKey(Long productId) {
    return LOCK_KEY_PREFIX + productId;
  }
}
