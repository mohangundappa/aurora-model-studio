package com.aurora.studio.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GatewayConfigurationTest {
  @Test
  void refusesDeterministicModelWhenOpenAiProviderIsSelected() {
    assertThatThrownBy(
            () ->
                new GatewayConfiguration()
                    .llmAdapter(
                        new ObjectMapper(),
                        "openai",
                        "session-key",
                        "deterministic",
                        "https://api.openai.com/v1/chat/completions"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("studio.llm.model")
        .hasMessageContaining("deterministic");
  }

  @Test
  void deterministicProviderStillWorksWithoutAnApiKey() {
    new GatewayConfiguration()
        .llmAdapter(
            new ObjectMapper(), "deterministic", "", "deterministic", "https://unused.example");
  }
}
