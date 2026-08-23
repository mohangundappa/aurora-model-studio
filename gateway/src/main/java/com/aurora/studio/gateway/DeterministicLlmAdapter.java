package com.aurora.studio.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeterministicLlmAdapter implements LlmAdapter {
  @Override
  public LlmAdapterResponse complete(LlmRequest request) {
    Map<String, Object> inputs = request.resolvedPromptInputs();
    if (request.taskId().startsWith("targeting-design-")) {
      Map<String, Object> rejected =
          Map.of(
              "cohortSql",
              "SELECT session_id, event_time FROM raw_events WHERE event_name = 'BOOKING_COMPLETED' AND event_time <= :as_of",
              "labelSql",
              "SELECT session_id, CASE WHEN event_name = 'BOOKING_COMPLETED' THEN 1 ELSE 0 END AS label FROM raw_events WHERE event_time > :as_of AND event_time <= :as_of + interval '30 days'",
              "asOfSemantics",
              "Cohort features are available at the event_time as-of point.");
      Map<String, Object> accepted =
          Map.of(
              "cohortSql",
              "SELECT session_id, event_time FROM raw_events WHERE event_time <= :as_of AND event_time > :as_of - interval '30 days'",
              "labelSql",
              "SELECT session_id, CASE WHEN event_name = 'BOOKING_COMPLETED' THEN 1 ELSE 0 END AS label FROM raw_events WHERE event_time > :as_of AND event_time <= :as_of + interval '30 days'",
              "asOfSemantics",
              "Cohort features are available at the event_time as-of point.");
      Map<String, Object> payload = Map.of("drafts", List.of(rejected, accepted));
      return new LlmAdapterResponse(
          LlmOutcome.OK,
          payload,
          null,
          request.renderedPrompt().length() / 4,
          payload.toString().length() / 4,
          payload.toString().length() * 0.00001,
          false);
    }
    if (request.taskId().startsWith("feature-design-")) {
      Map<String, Object> feature =
          Map.of(
              "name",
              "recent-session-engagement",
              "businessDefinition",
              "Count of recent customer session events before scoring.",
              "entity",
              "session",
              "observationWindow",
              "30d ending strictly before as-of",
              "pointInTimeAvailable",
              true,
              "sourceColumns",
              List.of("session_id", "event_time", "event_name"),
              "asOfSemantics",
              "Available strictly before as-of.");
      Map<String, Object> payload = Map.of("drafts", List.of(feature));
      return new LlmAdapterResponse(
          LlmOutcome.OK,
          payload,
          null,
          request.renderedPrompt().length() / 4,
          payload.toString().length() / 4,
          payload.toString().length() * 0.00001,
          false);
    }
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
