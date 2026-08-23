package com.aurora.studio.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeEvidence(
    UUID id,
    UUID clientId,
    UUID knowledgeObjectId,
    String sourceSystem,
    String sourceType,
    String sourceUri,
    String sourceVersion,
    String excerpt,
    double extractionCertainty,
    Instant recordedAt) {}
