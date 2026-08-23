package com.aurora.studio.initiative;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record HandoffAttempt(
    UUID id,
    String packageHash,
    String endpoint,
    Map<String, Object> requestSummary,
    Integer responseStatus,
    String candidateId,
    String candidateStatus,
    String outcome,
    String failureCode,
    String failureMessage,
    Instant startedAt,
    Instant completedAt) {}
