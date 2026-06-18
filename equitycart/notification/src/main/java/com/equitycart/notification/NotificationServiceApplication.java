package com.equitycart.notification;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Standalone entry point for the Notification Service microservice.
 *
 * <p><b>Responsibilities:</b>
 *
 * <ul>
 *   <li>Subscribes to the {@code portfolio-notification} Kafka topic (Observer Pattern — consumer
 *       side of distributed pub/sub)
 *   <li>Routes events to the active channel strategy via {@code NotificationDispatcher} (Strategy
 *       Pattern — pluggable Email / Webhook / Log delivery)
 *   <li>Persists every dispatch attempt to {@code notification_logs} (PostgreSQL audit trail)
 *   <li>Exposes {@code GET /api/notifications} — notification history for authenticated users
 * </ul>
 *
 * <p><b>Why {@code @ComponentScan} IS required here (Phase 8 addition):</b> All
 * notification-specific beans ({@code NotificationConsumer}, channel strategies, {@code
 * NotificationDispatcherImpl}, {@code NotificationLogRepository}) live within {@code
 * com.equitycart.notification.*} and are covered by default scanning. However, cross-cutting
 * infrastructure beans from {@code com.equitycart.commons} (SecurityAutoConfig,
 * JwtAuthenticationFilter, GlobalExceptionHandler, MdcCorrelationFilter, KafkaConsumerConfig) must
 * also be registered. {@code @SpringBootApplication} only scans its own package — it does NOT scan
 * {@code com.equitycart.commons.*} unless explicitly told to via {@code @ComponentScan}. Note:
 * {@code @EntityScan} handles JPA entity discovery (e.g., BaseEntity) but has NO effect on
 * {@code @Component}/{@code @Configuration} bean registration.
 *
 * <p><b>Why {@code @EntityScan} is required:</b> {@code NotificationLog extends BaseEntity} — the
 * {@code @MappedSuperclass} lives at {@code com.equitycart.commons.entity}, outside the default
 * scan scope. Without explicitly including {@code com.equitycart.commons}, Hibernate would not
 * register {@code BaseEntity} as a managed superclass and would omit the inherited {@code id},
 * {@code createdAt}, and {@code updatedAt} columns from schema generation.
 *
 * <p><b>Why {@code @ConditionalOnProperty} properties are omitted from config:</b> {@code
 * SellToSpendSagaOrchestrator} uses {@code matchIfMissing = true} — the saga strategy is active by
 * default when {@code equitycart.sell-to-spend.strategy} is absent. The saga timeout uses
 * {@code @Value("${equitycart.saga.timeout-seconds:30}")} — Spring resolves the {@code :30} inline
 * default without any YAML entry. Only add a property when overriding its default.
 *
 * <p><b>Why product-service is not extracted as standalone in Phase 7:</b> Order-service and
 * portfolio-service both have direct {@code implementation project(':product-service')} classpath
 * dependencies — one for {@code ProductRepository}, the other for {@code ProductServiceImpl}.
 * Removing those dependencies would break both services immediately. Product-service extraction is
 * deferred to Phase 10, when Feign HTTP clients replace these direct repository/service injections.
 *
 * <p><b>Startup Flow:</b>
 *
 * <ol>
 *   <li>{@code main()} → {@code SpringApplication.run()} bootstraps the context
 *   <li>Spring Cloud Config client fetches {@code notification-service.yml} from Config Server
 *       (8888)
 *   <li>HikariCP connects to {@code equitycart_notification} PostgreSQL database
 *   <li>Hibernate auto-creates the {@code notification_logs} table
 *   <li>Spring Kafka consumer subscribes to {@code portfolio-notification} topic ({@code
 *       equitycart-notification-group})
 *   <li>Eureka client registers service as {@code NOTIFICATION-SERVICE} at {@code localhost:8761}
 * </ol>
 *
 * <p><b>Datastores:</b>
 *
 * <ul>
 *   <li><b>PostgreSQL</b> ({@code equitycart_notification}) — {@code notification_logs} audit table
 *   <li><b>Kafka</b> — consumer: {@code portfolio-notification} topic (producer: none — pure
 *       consumer)
 * </ul>
 *
 * <p><b>Security (Phase 8 Step 2):</b> Commons {@code SecurityAutoConfig} is active ({@code
 * equitycart.security.enabled=true} in notification-service.yml). {@code JwtAuthenticationFilter}
 * validates every request except {@code /api/auth/**} and {@code /actuator/**}. {@code
 * NotificationController} now correctly extracts {@code userId} from {@code
 * SecurityContextHolder.getContext().getAuthentication().getPrincipal()} — which is populated by
 * the filter as a {@code Long}. Phase 8 Steps 5-7 will migrate to OAuth2 Resource Server with
 * Keycloak RS256 tokens.
 *
 * <p><b>Verify After Startup:</b>
 *
 * <ul>
 *   <li>Eureka: {@code http://localhost:8761} → {@code NOTIFICATION-SERVICE} registered on port
 *       8087
 *   <li>Actuator: {@code GET http://localhost:8087/actuator/health} → 200 OK
 *   <li>Gateway routing: {@code GET http://localhost:8080/api/notifications} → 200 OK (empty list)
 * </ul>
 *
 * <p><b>Startup Dependency Order:</b> Config Server (8888) → Eureka (8761) → PostgreSQL → Kafka →
 * Notification-Service (8087)
 *
 * @see com.equitycart.notification.consumer.NotificationConsumer
 * @see com.equitycart.notification.service.impl.NotificationDispatcherImpl
 */
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(
    basePackages = {"com.equitycart.notification", "com.equitycart.commons"},
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootApplication.class))
@EntityScan(basePackages = {"com.equitycart.notification", "com.equitycart.commons"})
public class NotificationServiceApplication {

  private static final Logger log = LogManager.getLogger(NotificationServiceApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
    log.info(
        "Notification Service started — listening on port 8087, registered with Eureka as NOTIFICATION-SERVICE");
  }
}
