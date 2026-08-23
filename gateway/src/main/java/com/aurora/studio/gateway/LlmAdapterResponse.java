package com.aurora.studio.gateway;

import java.util.Map;

public record LlmAdapterResponse(
    LlmOutcome outcome,
    Map<String, Object> payload,
    String message,
    int inputTokens,
    int outputTokens,
    double cost,
    boolean retryable) {}
