package com.aurora.studio.knowledge;

import com.aurora.studio.common.KnowledgeConflictStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeConflict(
    UUID id,
    UUID clientId,
    UUID knowledgeObjectId,
    String field,
    Map<String, Object> values,
    KnowledgeConflictStatus status,
    Instant detectedAt) {}
