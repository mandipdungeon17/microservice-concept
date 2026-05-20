package com.equitycart.commons.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer error handling configuration. Replaces Spring Kafka's default error handler
 * (infinite retry) with a bounded-retry + Dead Letter Topic strategy.
 *
 * <p>Behavior: on listener exception → retry up to 3 times (1s apart) → if still failing, publish
 * the failed message to {@code <original-topic>.DLT} and commit the offset (unblock the consumer).
 *
 * <p>Non-retryable exceptions ({@link DeserializationException}, {@link NullPointerException}) skip
 * retries entirely and are sent to DLT immediately — retrying them would waste time since the
 * failure is permanent.
 */
@Configuration
public class KafkaConsumerConfig {

  @Bean
  public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    DeadLetterPublishingRecoverer recover = new DeadLetterPublishingRecoverer(kafkaTemplate);

    FixedBackOff backOff = new FixedBackOff(1000L, 3); // Retry every 1 second, up to 3 times

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recover, backOff);

    errorHandler.addNotRetryableExceptions(
        DeserializationException.class, NullPointerException.class);

    return errorHandler;
  }
}
