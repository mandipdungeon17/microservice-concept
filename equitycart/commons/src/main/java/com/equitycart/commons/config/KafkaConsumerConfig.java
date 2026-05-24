package com.equitycart.commons.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka consumer error handling configuration. Replaces Spring Kafka's default error handler
 * (infinite retry) with a bounded-retry + Dead Letter Topic strategy.
 *
 * <p>Behavior: on listener exception → retry up to 3 times with exponential backoff (1s → 2s → 4s,
 * capped at 10s) → if still failing, publish the failed message to {@code <original-topic>.DLT} and
 * commit the offset (unblock the consumer).
 *
 * <p>Non-retryable exceptions ({@link DeserializationException}, {@link NullPointerException}) skip
 * retries entirely and are sent to DLT immediately — retrying them would waste time since the
 * failure is permanent.
 *
 * @see ExponentialBackOffWithMaxRetries
 * @see DefaultErrorHandler
 * @see DeadLetterPublishingRecoverer
 */
@Configuration
public class KafkaConsumerConfig {

  @Bean
  public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    DeadLetterPublishingRecoverer recover = new DeadLetterPublishingRecoverer(kafkaTemplate);

    /**
     * FixedBackOff: simple, constant delay between retries. Good for predictable retry intervals.
     * ExponentialBackOff: increasing delay between retries. Good for handling transient issues that
     * may resolve over time, but can lead to longer wait times if the issue persists.
     *
     * <p>Fixed interval creates synchronized retry storms. If 100 consumers fail on the same DB
     * blip, they ALL retry at T+1s, T+2s, T+3s — hitting the recovering database with 100
     * concurrent queries at each tick. Exponential spread retries over increasing windows (1s, 2s,
     * 4s), reducing peak load on the recovering resource. Adding jitter (random ±20% on each
     * interval) further desynchronizes — that's what AWS SDKs do.
     *
     * <p>FixedBackOff backOff = new FixedBackOff(1000L, 3); // Retry every 1 second, up to 3 times
     */
    ExponentialBackOff backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1000L); // Start with 1 second
    backOff.setMultiplier(2.0); // Each subsequent delay = previous × 2
    backOff.setMaxInterval(10000L); // Cap the backoff at 10 seconds

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recover, backOff);

    errorHandler.addNotRetryableExceptions(
        DeserializationException.class, NullPointerException.class);

    return errorHandler;
  }
}
