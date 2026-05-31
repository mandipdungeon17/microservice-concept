package com.equitycart.notification.service.channel.impl;

import com.equitycart.notification.service.channel.api.NotificationChannelStrategy;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email-based notification channel using Spring's {@link JavaMailSender} abstraction.
 *
 * <p>In development, connects to MailHog (localhost:1025 SMTP / localhost:8025 Web UI) which traps
 * all outgoing mail without real delivery. In production, the same code works with any SMTP server
 * by changing {@code spring.mail.*} properties.
 *
 * <p>Uses a hardcoded recipient email from config ({@code equitycart.notification.recipient-email})
 * since the User entity does not yet have an email profile field exposed to this service. Activate
 * via {@code equitycart.notification.channel=EMAIL}.
 */
@Component("emailChannel")
@RequiredArgsConstructor
public class EmailChannelStrategy implements NotificationChannelStrategy {

  private static final Logger log = LogManager.getLogger(EmailChannelStrategy.class);

  private final JavaMailSender javaMailSender;

  @Value("${equitycart.notification.recipient-email}")
  String recipientEmail;

  @Value("${equitycart.notification.sender-email:noreply@equitycart.local}")
  String senderEmail;

  @Override
  public void send(Long userId, String subject, String body) {
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(senderEmail);
      message.setTo(recipientEmail);
      message.setSubject(subject);
      message.setText(body);

      javaMailSender.send(message);
      log.info("Sent email to {}", recipientEmail);
    } catch (Exception e) {
      log.warn("Failed to send email notification to userId {}: {}", userId, e.getMessage());
    }
  }
}
