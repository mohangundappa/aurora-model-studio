package com.aurora.studio.initiative;

import com.aurora.studio.discovery.ModelRequirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Initiative(
    UUID id,
    UUID requirementId,
    ModelRequirement requirement,
    String status,
    boolean includeCandidates,
    boolean actorIdentityVerified,
    Instant createdAt,
    List<StageState> stages,
    List<ArtifactReference> artifacts,
    List<String> blockers,
    List<GateDecision> gateDecisions,
    DurationSummary durations,
    List<InitiativeEvent> events) {}
