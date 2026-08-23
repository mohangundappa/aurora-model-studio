package com.aurora.studio.initiative;

import java.time.Instant;
import java.util.List;

public record InitiativeEvent(
    long id,
    InitiativeStage stage,
    StageStatus fromStatus,
    StageStatus toStatus,
    String actor,
    String reason,
    List<ArtifactReference> artifacts,
    Instant at) {}
