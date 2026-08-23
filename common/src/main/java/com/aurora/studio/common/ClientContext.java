package com.aurora.studio.common;

import java.util.UUID;

public final class ClientContext {
  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private ClientContext() {}

  public static void set(UUID clientId) {
    CURRENT.set(clientId);
  }

  public static UUID require() {
    UUID clientId = CURRENT.get();
    if (clientId == null) throw new IllegalStateException("client context is not set");
    return clientId;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
