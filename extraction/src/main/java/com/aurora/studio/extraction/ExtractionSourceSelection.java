package com.aurora.studio.extraction;

import java.util.List;

public record ExtractionSourceSelection(List<SourceSpec> sources) {
  public static ExtractionSourceSelection auroraDefaults() {
    return new ExtractionSourceSelection(
        List.of(
            new SourceSpec("signals/src/main/resources/signals", List.of("*.yaml", "*.yml")),
            new SourceSpec("signals/src/main/java/com/aurora/signals", List.of("*.java")),
            new SourceSpec("decision/src/main/resources", List.of("decision-policy.yaml")),
            new SourceSpec(
                "experiments/src/main/resources/experiments", List.of("*.yaml", "*.yml")),
            new SourceSpec(
                "app/src/main/resources/db/migration", List.of("V7__model_registry.sql")),
            new SourceSpec("docs", List.of("data-flow.md", "agent-evaluation.md"))));
  }

  public record SourceSpec(String root, List<String> patterns) {}
}
