package com.aurora.studio.initiative;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GateDecision(
    UUID id,
    InitiativeStage stage,
    UUID stageAttemptId,
    String decision,
    String actor,
    boolean actorVerified,
    String reason,
    List<String> acceptedUnknownChecks,
    Instant createdAt) {}
