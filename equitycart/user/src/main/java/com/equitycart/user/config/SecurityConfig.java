package com.equitycart.user.config;

import com.equitycart.user.enums.UserRoles;
import com.equitycart.user.filter.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the user module. Configures stateless session management,
 * JWT-based authentication via {@link JwtAuthFilter}, URL-level authorization rules, and
 * method-level security.
 */
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

  private static final Logger log = LogManager.getLogger(SecurityConfig.class);

  private final JwtAuthFilter jwtAuthFilter;

  /**
   * Creates a BCrypt password encoder bean for hashing user passwords.
   *
   * @return a {@link BCryptPasswordEncoder} instance
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Configures the HTTP security filter chain with CSRF disabled, stateless sessions, JWT filter
   * insertion, and URL authorization rules.
   *
   * @param httpSecurity the {@link HttpSecurity} builder
   * @return the configured {@link SecurityFilterChain}
   * @throws Exception if an error occurs during configuration
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
    log.info("Configuring security filter chain");
    return httpSecurity
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/auth/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole(UserRoles.ADMIN.name())
                    .requestMatchers(HttpMethod.POST, "/api/products/**")
                    .hasAnyRole(UserRoles.SELLER.name(), UserRoles.ADMIN.name())
                    .anyRequest()
                    .authenticated())
        .build();
  }
}
