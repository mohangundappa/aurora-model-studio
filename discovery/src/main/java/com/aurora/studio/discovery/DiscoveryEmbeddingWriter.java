package com.aurora.studio.discovery;

import com.aurora.studio.knowledge.KnowledgeEmbeddingWriter;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeRepository;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryEmbeddingWriter implements KnowledgeEmbeddingWriter {
  private final KnowledgeRepository repository;
  private final EmbeddingProvider provider;

  public DiscoveryEmbeddingWriter(KnowledgeRepository repository, EmbeddingProvider provider) {
    this.repository = repository;
    this.provider = provider;
  }

  @Override
  public void write(KnowledgeObject object) {
    Embedding embedding = provider.embed(searchText(object));
    repository.updateEmbedding(object.id(), embedding.vector(), embedding.provider());
  }

  private String searchText(KnowledgeObject object) {
    return object.name() + " " + object.businessDescription() + " " + object.attributes();
  }
}
