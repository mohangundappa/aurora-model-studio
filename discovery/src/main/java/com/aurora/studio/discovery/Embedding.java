package com.aurora.studio.discovery;

public record Embedding(float[] vector, String provider) {
  public Embedding {
    vector = vector.clone();
  }
}
