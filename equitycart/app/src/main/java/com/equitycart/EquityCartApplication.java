package com.equitycart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EquityCartApplication {

  public static void main(String[] args) {
    SpringApplication.run(EquityCartApplication.class, args);
    System.out.println(
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Test@1234"));
  }
}
