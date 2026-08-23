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
    List<ArtifactReference> artifacts,
    List<GenerationDraft> drafts,
    int draftsGenerated,
    int draftsRejected,
    List<String> violatedChecks,
    List<HandoffAttempt> handoffAttempts) {
  public StageAttempt(
      UUID id,
      int attempt,
      StageStatus status,
      Instant startedAt,
      Instant completedAt,
      long machineDurationMillis,
      long humanWaitDurationMillis,
      List<String> blockers,
      List<FeasibilityCheck> feasibilityChecks,
      List<ArtifactReference> artifacts) {
    this(
        id,
        attempt,
        status,
        startedAt,
        completedAt,
        machineDurationMillis,
        humanWaitDurationMillis,
        blockers,
        feasibilityChecks,
        artifacts,
        List.of(),
        0,
        0,
        List.of(),
        List.of());
  }

  public StageAttempt(
      UUID id,
      int attempt,
      StageStatus status,
      Instant startedAt,
      Instant completedAt,
      long machineDurationMillis,
      long humanWaitDurationMillis,
      List<String> blockers,
      List<FeasibilityCheck> feasibilityChecks,
      List<ArtifactReference> artifacts,
      List<GenerationDraft> drafts,
      int draftsGenerated,
      int draftsRejected,
      List<String> violatedChecks) {
    this(
        id,
        attempt,
        status,
        startedAt,
        completedAt,
        machineDurationMillis,
        humanWaitDurationMillis,
        blockers,
        feasibilityChecks,
        artifacts,
        drafts,
        draftsGenerated,
        draftsRejected,
        violatedChecks,
        List.of());
  }
}
