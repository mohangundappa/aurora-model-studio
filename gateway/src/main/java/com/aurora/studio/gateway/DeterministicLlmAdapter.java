package com.aurora.studio.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeterministicLlmAdapter implements LlmAdapter {
  @Override
  public LlmAdapterResponse complete(LlmRequest request) {
    Map<String, Object> inputs = request.resolvedPromptInputs();
    if (request.taskId().startsWith("discovery-explanation-")) {
      Object explanation = inputs.get("deterministicExplanation");
      if (explanation instanceof String text && !text.isBlank()) {
        Map<String, Object> payload = Map.of("explanation", text);
        return new LlmAdapterResponse(
            LlmOutcome.OK,
            payload,
            null,
            request.renderedPrompt().length() / 4,
            payload.toString().length() / 4,
            payload.toString().length() * 0.0000025,
            false);
      }
      return new LlmAdapterResponse(
          LlmOutcome.REFUSED, Map.of(), "missing deterministic explanation", 0, 0, 0, false);
    }
    Object evidence = inputs.get("evidenceExcerpts");
    Object facts = inputs.get("structuralFacts");
    if (!(evidence instanceof List<?> excerpts)
        || excerpts.isEmpty()
        || !(facts instanceof Map<?, ?> structural)
        || structural.isEmpty()) {
      return new LlmAdapterResponse(
          LlmOutcome.REFUSED, Map.of(), "insufficient grounded evidence", 0, 0, 0, false);
    }
    List<Map<String, Object>> fields = new ArrayList<>();
    Object requested = inputs.get("interpretationFields");
    if (requested instanceof Map<?, ?> requestedFields) {
      for (Map.Entry<?, ?> entry : requestedFields.entrySet()) {
        String field = String.valueOf(entry.getKey());
        String value = String.valueOf(entry.getValue());
        String citation = findCitation(value, excerpts);
        if (citation != null) {
          fields.add(
              Map.of(
                  "field", field,
                  "value", value,
                  "citation", citation,
                  "classification", "ADAPTED"));
        }
      }
    }
    if (fields.isEmpty()) {
      return new LlmAdapterResponse(
          LlmOutcome.REFUSED, Map.of(), "no supported interpretation", 0, 0, 0, false);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fields", fields);
    payload.put("relationships", List.of());
    int inputTokens = request.renderedPrompt().length() / 4;
    int outputTokens = payload.toString().length() / 4;
    return new LlmAdapterResponse(
        LlmOutcome.OK, payload, null, inputTokens, outputTokens, outputTokens * 0.00001, false);
  }

  private String findCitation(String value, List<?> excerpts) {
    for (Object excerpt : excerpts) {
      String text = String.valueOf(excerpt);
      if (text.contains(value)) return value;
      for (String word : value.split("\\s+")) {
        if (word.length() > 4 && text.contains(word)) return word;
      }
    }
    return null;
  }
}
