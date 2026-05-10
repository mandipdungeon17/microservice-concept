package com.equitycart.marketdata.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Configures a {@link WebClient} bean for calling the Alpha Vantage stock-market API. Uses Reactor
 * Netty's {@link HttpClient} with explicit connect (5 s) and response (10 s) timeouts so that
 * downstream failures are surfaced quickly rather than blocking indefinitely.
 */
@Configuration
public class WebClientConfig {

  @Value("${alphavantage.base-url}")
  private String alphaVantageBaseUrl;

  /**
   * Creates a {@link WebClient} pointed at the Alpha Vantage base URL with Netty-level timeouts.
   */
  @Bean
  public WebClient webClient() {
    return WebClient.builder()
        .baseUrl(alphaVantageBaseUrl)
        .clientConnector(
            new ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(10))))
        .build();
  }
}
