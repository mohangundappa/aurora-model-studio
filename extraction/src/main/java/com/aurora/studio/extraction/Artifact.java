package com.aurora.studio.extraction;

import java.nio.file.Path;

public record Artifact(
    Path path,
    String kind,
    String name,
    String excerpt,
    StructuralFact structuralFact,
    String knowledgeKey) {
  public Artifact(
      Path path, String kind, String name, String excerpt, StructuralFact structuralFact) {
    this(path, kind, name, excerpt, structuralFact, stableKey(path, kind, name));
  }

  private static String stableKey(Path path, String kind, String name) {
    return kind.toLowerCase() + ":" + path.toString().replace('\\', '/');
  }
}
