package com.aurora.studio.gateway;

import java.util.Map;
import java.util.UUID;

public record LlmResult(
    UUID invocationId,
    LlmOutcome outcome,
    Map<String, Object> payload,
    String message,
    int inputTokens,
    int outputTokens,
    double cost,
    long latencyMillis,
    int retryCount) {
  public boolean successful() {
    return outcome == LlmOutcome.OK;
  }
}
