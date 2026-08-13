package com.equitycart.portfolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "feature-flags.portfolio")
@Data
public class CQRSFeatureFlag {
  private boolean cqrsReadEnabled = false;
}
