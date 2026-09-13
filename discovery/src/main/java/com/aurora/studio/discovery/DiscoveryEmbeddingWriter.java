package com.aurora.studio.discovery;

import com.aurora.studio.knowledge.KnowledgeEmbeddingWriter;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeSearchIndex;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryEmbeddingWriter implements KnowledgeEmbeddingWriter {
  private final KnowledgeSearchIndex index;
  private final EmbeddingProvider provider;

  public DiscoveryEmbeddingWriter(KnowledgeSearchIndex index, EmbeddingProvider provider) {
    this.index = index;
    this.provider = provider;
  }

  @Override
  public void write(KnowledgeObject object) {
    Embedding embedding = provider.embed(searchText(object));
    index.updateEmbedding(object.id(), embedding.vector(), embedding.provider());
  }

  private String searchText(KnowledgeObject object) {
    return object.name() + " " + object.businessDescription() + " " + object.attributes();
  }
}
