package com.aurora.studio.initiative;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DesignPayloads {
  private DesignPayloads() {}

  @SuppressWarnings("unchecked")
  static List<Object> list(Object value) {
    return value instanceof List<?> values ? (List<Object>) values : List.of();
  }

  static Map<String, Object> map(Map<?, ?> value) {
    Map<String, Object> result = new LinkedHashMap<>();
    value.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  static String string(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? "" : String.valueOf(value);
  }
}
