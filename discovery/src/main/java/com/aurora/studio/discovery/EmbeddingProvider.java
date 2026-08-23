package com.aurora.studio.discovery;

public interface EmbeddingProvider {
  Embedding embed(String text);
}
