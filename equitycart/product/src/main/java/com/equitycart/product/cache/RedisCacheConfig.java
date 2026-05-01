package com.equitycart.product.cache;

import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Redis cache configuration for the product module. Enables Spring's caching abstraction with Redis
 * as the backing store. Uses JSON serialization for human-readable cache entries and configures a
 * default TTL of 10 minutes for all caches.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

  /**
   * Configures the Redis cache manager with JSON serialization and default TTL.
   *
   * @param connectionFactory the Redis connection factory auto-configured by Spring Boot
   * @return configured RedisCacheManager instance
   */
  @Bean
  public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    // 1. Create JSON serializer
    GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

    // 2. Build default cache configuration
    RedisCacheConfiguration defaultConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

    // 3. Build and return cache manager
    return RedisCacheManager.builder(connectionFactory).cacheDefaults(defaultConfig).build();
  }
}
