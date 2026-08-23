package com.aurora.studio.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class DeterministicEmbeddingProvider implements EmbeddingProvider {
  public static final int DIMENSIONS = 32;

  @Override
  public Embedding embed(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      float[] vector = new float[DIMENSIONS];
      for (int index = 0; index < vector.length; index++) {
        vector[index] = (digest[index] & 0xff) / 255.0f;
      }
      return new Embedding(vector, "deterministic-v1");
    } catch (Exception exception) {
      throw new IllegalStateException("unable to create deterministic embedding", exception);
    }
  }
}
