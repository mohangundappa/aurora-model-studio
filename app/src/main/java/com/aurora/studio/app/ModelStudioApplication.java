package com.aurora.studio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.aurora.studio")
public class ModelStudioApplication {
  public static void main(String[] args) {
    SpringApplication.run(ModelStudioApplication.class, args);
  }
}
