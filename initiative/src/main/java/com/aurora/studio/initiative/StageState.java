package com.aurora.studio.initiative;

import java.util.List;

public record StageState(
    InitiativeStage stage,
    StageStatus status,
    int currentAttempt,
    List<StageAttempt> attempts,
    String note) {}
