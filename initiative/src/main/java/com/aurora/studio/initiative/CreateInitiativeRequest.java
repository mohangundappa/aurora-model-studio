package com.aurora.studio.initiative;

import java.util.UUID;

public record CreateInitiativeRequest(
    UUID requirementId, boolean includeCandidates, Long clientBaselineDurationMillis) {}
