package com.aurora.studio.discovery;

import java.util.List;
import java.util.UUID;

public record DiscoveryRun(
    UUID id,
    UUID requirementId,
    boolean includeCandidates,
    String embeddingProvider,
    String classification,
    List<String> reasonCodes,
    List<String> blockers,
    List<DiscoveryCandidate> candidates) {}
