package com.aurora.studio.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiLlmAdapterTest {
  @Test
  void requestUsesStrictSchemaOwnedByLlmRequest() {
    Map<String, Object> schema =
        Map.of(
            "$id",
            "targeting-design-v1",
            "type",
            "object",
            "required",
            List.of("drafts"),
            "properties",
            Map.of(
                "drafts",
                Map.of(
                    "type",
                    "array",
                    "items",
                    Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("cohortSql", "labelSql", "asOfSemantics"),
                        "properties",
                        Map.of(
                            "cohortSql", Map.of("type", "string"),
                            "labelSql", Map.of("type", "string"),
                            "asOfSemantics", Map.of("type", "string")),
                        "additionalProperties",
                        false))),
            "additionalProperties",
            false);
    LlmRequest request =
        new LlmRequest(
            "targeting-design-test",
            "targeting-design",
            "1",
            Map.of("targetObservable", "BOOKING_COMPLETED"),
            schema,
            1200,
            Duration.ofSeconds(10),
            RedactionPolicy.extractionDefault(),
            "governed metadata prompt");

    Map<String, Object> body =
        new OpenAiLlmAdapter(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                "session-key",
                "gpt-4o-mini",
                URI.create("https://api.openai.com/v1/chat/completions"))
            .requestBody(request);

    assertThat(body).containsEntry("model", "gpt-4o-mini");
    assertThat(body).containsEntry("max_completion_tokens", 1200);
    assertThat(body)
        .extractingByKey("response_format")
        .isEqualTo(
            Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "targeting-design-test", "strict", true, "schema", schema)));
    assertThat(body)
        .extractingByKey("messages")
        .asList()
        .singleElement()
        .isEqualTo(Map.of("role", "user", "content", "governed metadata prompt"));
  }
}
