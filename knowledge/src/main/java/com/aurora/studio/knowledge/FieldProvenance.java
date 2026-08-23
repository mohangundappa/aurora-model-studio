package com.aurora.studio.knowledge;

import java.util.UUID;

public record FieldProvenance(
    UUID id,
    UUID clientId,
    UUID knowledgeObjectId,
    String fieldName,
    Object fieldValue,
    String provenance,
    UUID citationEvidenceId,
    String citationExcerpt,
    double extractionCertainty) {}
