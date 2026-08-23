package com.aurora.studio.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayServiceTest {
  private static final LlmRequest REQUEST =
      new LlmRequest(
          "task",
          "template",
          "1",
          Map.of("data", "value"),
          Map.of(
              "$id",
              "schema",
              "type",
              "object",
              "required",
              List.of("answer"),
              "properties",
              Map.of("answer", Map.of("type", "string"))),
          100,
          Duration.ofSeconds(1),
          RedactionPolicy.extractionDefault(),
          "instructions\n<data>value</data>");

  @Test
  void deterministicAdapterIsByteIdenticalAcrossRuns() {
    DeterministicLlmAdapter adapter = new DeterministicLlmAdapter();
    LlmRequest grounded =
        new LlmRequest(
            REQUEST.taskId(),
            REQUEST.promptTemplateId(),
            REQUEST.promptTemplateVersion(),
            Map.of(
                "structuralFacts", Map.of("name", "guest-value"),
                "evidenceExcerpts", List.of("guest-value is a hotel feature"),
                "interpretationFields", Map.of("businessRationale", "guest-value")),
            REQUEST.responseSchema(),
            REQUEST.maxOutputTokens(),
            REQUEST.timeout(),
            REQUEST.redactionPolicy(),
            REQUEST.renderedPrompt());
    assertThat(adapter.complete(grounded)).isEqualTo(adapter.complete(grounded));
  }

  @Test
  void providerRefusalIsRecordedWithoutRetries() {
    LlmInvocationRepository invocations = mock(LlmInvocationRepository.class);
    when(invocations.record(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    LlmAdapter adapter =
        request ->
            new LlmAdapterResponse(LlmOutcome.REFUSED, Map.of(), "no evidence", 2, 0, 0, false);
    LlmResult result =
        new GatewayService(adapter, invocations, "deterministic", "deterministic")
            .complete(REQUEST);
    assertThat(result.outcome()).isEqualTo(LlmOutcome.REFUSED);
    assertThat(result.retryCount()).isZero();
  }

  @Test
  void malformedProviderResponseBecomesSchemaInvalidAfterTwoRetries() {
    LlmInvocationRepository invocations = mock(LlmInvocationRepository.class);
    when(invocations.record(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    LlmAdapter adapter =
        request -> new LlmAdapterResponse(LlmOutcome.OK, Map.of("answer", 42), null, 2, 1, 0, true);
    LlmResult result =
        new GatewayService(adapter, invocations, "deterministic", "deterministic")
            .complete(REQUEST);
    assertThat(result.outcome()).isEqualTo(LlmOutcome.SCHEMA_INVALID);
    assertThat(result.payload()).isEmpty();
    assertThat(result.retryCount()).isEqualTo(2);
  }
}
