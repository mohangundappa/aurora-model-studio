package com.aurora.studio.knowledge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KnowledgePackage(
    UUID id,
    int version,
    String type,
    String name,
    String businessDefinition,
    Map<String, Object> attributes,
    List<KnowledgeObject> implementations,
    List<KnowledgeEvidence> evidence,
    List<KnowledgeRelationship> relationships,
    ContextualPerformance contextualPerformance,
    List<String> constraints,
    double confidence,
    Map<String, Object> confidenceBreakdown,
    String approvalStatus,
    boolean trusted,
    boolean synthetic,
    List<String> warnings,
    List<KnowledgeConflict> conflicts,
    Double relevance) {}
