package com.aurora.studio.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

public record LlmRequest(
    String taskId,
    String promptTemplateId,
    String promptTemplateVersion,
    Map<String, Object> resolvedPromptInputs,
    Map<String, Object> responseSchema,
    int maxOutputTokens,
    Duration timeout,
    RedactionPolicy redactionPolicy,
    String renderedPrompt) {
  public String promptHash() {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(renderedPrompt.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception exception) {
      throw new IllegalStateException("unable to hash prompt", exception);
    }
  }
}
