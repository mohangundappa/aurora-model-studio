package com.aurora.studio.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {
  @Bean
  public LlmAdapter llmAdapter(
      ObjectMapper mapper,
      @Value("${studio.llm.provider:deterministic}") String provider,
      @Value("${OPENAI_API_KEY:}") String apiKey,
      @Value("${studio.llm.model:gpt-4o-mini}") String model,
      @Value("${studio.llm.endpoint:https://api.openai.com/v1/chat/completions}") String endpoint) {
    if ("openai".equalsIgnoreCase(provider) && !apiKey.isBlank()) {
      return new OpenAiLlmAdapter(mapper, apiKey, model, URI.create(endpoint));
    }
    return new DeterministicLlmAdapter();
  }
}
