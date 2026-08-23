package com.aurora.studio.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GatewayService implements LlmGateway {
  private final LlmAdapter adapter;
  private final LlmInvocationRepository invocations;
  private final String provider;
  private final String model;

  public GatewayService(
      LlmAdapter adapter,
      LlmInvocationRepository invocations,
      @Value("${studio.llm.provider:deterministic}") String provider,
      @Value("${studio.llm.model:deterministic}") String model) {
    this.adapter = adapter;
    this.invocations = invocations;
    this.provider = provider;
    this.model = model;
  }

  @Override
  public LlmResult complete(LlmRequest request) {
    Instant started = Instant.now();
    LlmAdapterResponse response = null;
    int retries = 0;
    for (int attempt = 0; attempt <= 2; attempt++) {
      response = adapter.complete(request);
      if (response.outcome() == LlmOutcome.OK
          && valid(request.responseSchema(), response.payload())) break;
      if (response.outcome() == LlmOutcome.REFUSED || !response.retryable()) break;
      if (attempt < 2) retries++;
    }
    LlmOutcome outcome = response.outcome();
    Map<String, Object> payload = response.payload();
    String message = response.message();
    if (outcome == LlmOutcome.OK && !valid(request.responseSchema(), payload)) {
      outcome = LlmOutcome.SCHEMA_INVALID;
      payload = Map.of();
      message = "provider response failed schema validation";
    }
    long latency = java.time.Duration.between(started, Instant.now()).toMillis();
    LlmResult result =
        new LlmResult(
            null,
            outcome,
            payload == null ? Map.of() : payload,
            message,
            response.inputTokens(),
            response.outputTokens(),
            response.cost(),
            latency,
            retries);
    UUID invocationId = invocations.record(request, result, provider, model);
    return new LlmResult(
        invocationId,
        result.outcome(),
        result.payload(),
        result.message(),
        result.inputTokens(),
        result.outputTokens(),
        result.cost(),
        result.latencyMillis(),
        result.retryCount());
  }

  private boolean valid(Map<String, Object> schema, Map<String, Object> payload) {
    return validValue(schema, payload);
  }

  private boolean validValue(Map<String, Object> schema, Object value) {
    if (schema == null || value == null) return false;
    Object required = schema.get("required");
    if (required instanceof List<?> fields
        && value instanceof Map<?, ?> map
        && fields.stream().anyMatch(field -> !map.containsKey(String.valueOf(field)))) return false;
    String type = String.valueOf(schema.getOrDefault("type", "object"));
    if (type.equals("object")) {
      if (!(value instanceof Map<?, ?> map)) return false;
      Object properties = schema.get("properties");
      if (properties instanceof Map<?, ?> definitions) {
        for (Map.Entry<?, ?> entry : definitions.entrySet()) {
          Object child = map.get(String.valueOf(entry.getKey()));
          if (child != null
              && entry.getValue() instanceof Map<?, ?> childSchema
              && !validValue(cast(childSchema), child)) return false;
        }
      }
      return true;
    }
    if (type.equals("array")) {
      if (!(value instanceof List<?> list)) return false;
      Object items = schema.get("items");
      return !(items instanceof Map<?, ?> itemSchema)
          || list.stream().allMatch(item -> validValue(cast(itemSchema), item));
    }
    return switch (type) {
      case "string" -> value instanceof String;
      case "number", "integer" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      default -> true;
    };
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> cast(Map<?, ?> value) {
    return (Map<String, Object>) value;
  }
}
