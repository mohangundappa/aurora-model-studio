package com.aurora.studio.knowledge;

import java.util.UUID;

public class KnowledgeNotFoundException extends RuntimeException {
  public KnowledgeNotFoundException(UUID id) {
    super("Knowledge object " + id + " was not found for this client");
  }
}
