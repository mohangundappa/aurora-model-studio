package com.aurora.studio.discovery;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DiscoveryCandidate(
    UUID id,
    String knowledgeKey,
    String type,
    String name,
    boolean synthetic,
    boolean trusted,
    Map<String, Double> scorecard,
    Double compositeScore,
    String classification,
    List<String> reasonCodes,
    List<String> blockers,
    List<String> gaps,
    List<DiscoveryEvidence> evidence,
    String explanation) {}
