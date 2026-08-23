package com.aurora.studio.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscoveryConfiguration {
  @Bean
  EmbeddingProvider embeddingProvider(
      ObjectMapper mapper,
      @Value("${studio.discovery.embedding-provider:deterministic}") String provider,
      @Value("${OPENAI_API_KEY:}") String apiKey,
      @Value("${studio.discovery.embedding-model:text-embedding-3-small}") String model,
      @Value("${studio.discovery.embedding-endpoint:https://api.openai.com/v1/embeddings}")
          String endpoint) {
    if ("openai".equalsIgnoreCase(provider) && !apiKey.isBlank()) {
      return new OpenAiEmbeddingProvider(mapper, apiKey, model, URI.create(endpoint));
    }
    return new DeterministicEmbeddingProvider();
  }
}
