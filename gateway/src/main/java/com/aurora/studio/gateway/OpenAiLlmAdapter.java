package com.aurora.studio.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class OpenAiLlmAdapter implements LlmAdapter {
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String apiKey;
  private final String model;
  private final URI endpoint;

  public OpenAiLlmAdapter(ObjectMapper mapper, String apiKey, String model, URI endpoint) {
    this.mapper = mapper;
    this.apiKey = apiKey;
    this.model = model;
    this.endpoint = endpoint;
    this.client = HttpClient.newHttpClient();
  }

  @Override
  public LlmAdapterResponse complete(LlmRequest request) {
    if (apiKey == null || apiKey.isBlank()) {
      return new LlmAdapterResponse(
          LlmOutcome.FAILED, Map.of(), "OPENAI_API_KEY is not configured", 0, 0, 0, false);
    }
    try {
      Map<String, Object> body =
          Map.of(
              "model",
              model,
              "messages",
              List.of(Map.of("role", "user", "content", request.renderedPrompt())),
              "response_format",
              Map.of(
                  "type",
                  "json_schema",
                  "json_schema",
                  Map.of("name", request.taskId(), "schema", request.responseSchema())),
              "max_completion_tokens",
              request.maxOutputTokens());
      HttpRequest httpRequest =
          HttpRequest.newBuilder(endpoint)
              .timeout(request.timeout())
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return new LlmAdapterResponse(
            LlmOutcome.FAILED,
            Map.of(),
            "OpenAI returned HTTP " + response.statusCode(),
            0,
            0,
            0,
            true);
      }
      JsonNode root = mapper.readTree(response.body());
      JsonNode message = root.path("choices").path(0).path("message");
      if (message.hasNonNull("refusal")) {
        return new LlmAdapterResponse(
            LlmOutcome.REFUSED, Map.of(), message.path("refusal").asText(), 0, 0, 0, false);
      }
      String content = message.path("content").asText("");
      if (content.isBlank()) {
        return new LlmAdapterResponse(
            LlmOutcome.SCHEMA_INVALID,
            Map.of(),
            "OpenAI returned empty structured content",
            0,
            0,
            0,
            true);
      }
      Map<String, Object> payload = mapper.readValue(content, Map.class);
      int inputTokens = root.path("usage").path("prompt_tokens").asInt();
      int outputTokens = root.path("usage").path("completion_tokens").asInt();
      return new LlmAdapterResponse(
          LlmOutcome.OK, payload, null, inputTokens, outputTokens, 0, false);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new LlmAdapterResponse(
          LlmOutcome.FAILED, Map.of(), "OpenAI request interrupted", 0, 0, 0, true);
    } catch (IOException | RuntimeException exception) {
      return new LlmAdapterResponse(
          LlmOutcome.FAILED, Map.of(), "OpenAI request failed", 0, 0, 0, true);
    }
  }
}
