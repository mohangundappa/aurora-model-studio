package com.aurora.studio.knowledge;

import com.aurora.studio.common.KnowledgeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeObject(
    UUID id,
    UUID clientId,
    String knowledgeKey,
    int version,
    KnowledgeType knowledgeType,
    String name,
    String businessDomain,
    String businessUseCase,
    String businessDescription,
    Map<String, Object> canonicalTaxonomy,
    Map<String, Object> clientTaxonomy,
    java.util.List<String> tags,
    String lifecycleStatus,
    Instant effectiveFrom,
    Instant effectiveTo,
    double confidence,
    Map<String, Object> confidenceBreakdown,
    Map<String, Object> qualityAssessment,
    String extractedBy,
    String reviewedBy,
    String approvedBy,
    String approvalComments,
    Map<String, Object> attributes,
    boolean synthetic) {
  @JsonProperty
  public boolean trusted() {
    return "APPROVED".equals(lifecycleStatus);
  }
}
