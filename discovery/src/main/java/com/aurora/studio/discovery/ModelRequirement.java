package com.aurora.studio.discovery;

import java.util.Map;

public record ModelRequirement(
    String businessDomain,
    String businessUseCase,
    String predictionTarget,
    String observableDefinition,
    String population,
    String outcomeHorizon,
    String decisionLatency,
    String requiredAction,
    Map<String, Object> constraints,
    Map<String, Object> clientTaxonomy,
    Map<String, Object> canonicalTaxonomy) {
  public ModelRequirement {
    constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    clientTaxonomy = clientTaxonomy == null ? Map.of() : Map.copyOf(clientTaxonomy);
    canonicalTaxonomy = canonicalTaxonomy == null ? Map.of() : Map.copyOf(canonicalTaxonomy);
  }
}
