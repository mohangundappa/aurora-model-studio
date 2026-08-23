package com.aurora.studio.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class OpenAiEmbeddingProvider implements EmbeddingProvider {
  private final ObjectMapper mapper;
  private final String apiKey;
  private final String model;
  private final URI endpoint;
  private final HttpClient client = HttpClient.newHttpClient();

  public OpenAiEmbeddingProvider(ObjectMapper mapper, String apiKey, String model, URI endpoint) {
    this.mapper = mapper;
    this.apiKey = apiKey;
    this.model = model;
    this.endpoint = endpoint;
  }

  @Override
  public Embedding embed(String text) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("OPENAI_API_KEY is not configured");
    }
    try {
      String body = mapper.writeValueAsString(Map.of("model", model, "input", text));
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Authorization", "Bearer " + apiKey)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("OpenAI embedding returned HTTP " + response.statusCode());
      }
      Map<String, Object> payload =
          mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
      List<?> data = (List<?>) payload.get("data");
      List<?> values = (List<?>) ((Map<?, ?>) data.getFirst()).get("embedding");
      float[] vector = new float[values.size()];
      for (int index = 0; index < vector.length; index++) {
        vector[index] = ((Number) values.get(index)).floatValue();
      }
      return new Embedding(vector, "openai-" + model);
    } catch (IOException | InterruptedException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("OpenAI embedding failed", exception);
    }
  }
}
