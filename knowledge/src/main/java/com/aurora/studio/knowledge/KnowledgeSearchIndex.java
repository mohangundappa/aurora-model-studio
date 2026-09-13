package com.aurora.studio.knowledge;

import java.util.List;
import java.util.UUID;

/** Tenant-scoped retrieval and embedding storage; lifecycle writes use KnowledgeService. */
public interface KnowledgeSearchIndex {
  List<KnowledgeObject> discoveryRecall(
      float[] embedding, String text, String provider, boolean includeCandidates, int limit);

  void updateEmbedding(UUID objectId, float[] embedding, String provider);
}
