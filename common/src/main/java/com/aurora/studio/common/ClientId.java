package com.aurora.studio.common;

import java.util.UUID;

public record ClientId(UUID value) {
  public ClientId {
    if (value == null) throw new IllegalArgumentException("client id is required");
  }

  public static ClientId parse(String value) {
    try {
      if (value == null
          || !value.matches(
              "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
        throw new IllegalArgumentException("not canonical");
      }
      return new ClientId(UUID.fromString(value));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("X-Aurora-Client must be a UUID", exception);
    }
  }
}
