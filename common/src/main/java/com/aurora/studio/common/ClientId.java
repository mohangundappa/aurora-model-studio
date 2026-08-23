package com.aurora.studio.common;

import java.util.UUID;

public record ClientId(UUID value) {
  public ClientId {
    if (value == null) throw new IllegalArgumentException("client id is required");
  }

  public static ClientId parse(String value) {
    try {
      return new ClientId(UUID.fromString(value));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("X-Aurora-Client must be a UUID", exception);
    }
  }
}
