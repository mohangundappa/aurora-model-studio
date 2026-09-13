package com.aurora.studio.knowledge;

import java.util.Optional;

/** Tenant-scoped deduplication and provenance for source ingestion. */
public interface KnowledgeIngestion {
  Optional<KnowledgeObject> findLatest(String key);

  Optional<KnowledgeObject> findBySourceVersion(
      String key, String sourceSystem, String sourceVersion);

  void saveFieldProvenance(FieldProvenance field);
}
