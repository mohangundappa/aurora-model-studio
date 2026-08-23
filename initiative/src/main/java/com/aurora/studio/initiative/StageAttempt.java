package com.aurora.studio.initiative;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StageAttempt(
    UUID id,
    int attempt,
    StageStatus status,
    Instant startedAt,
    Instant completedAt,
    long machineDurationMillis,
    long humanWaitDurationMillis,
    List<String> blockers,
    List<FeasibilityCheck> feasibilityChecks,
    List<ArtifactReference> artifacts) {}
