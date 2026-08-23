package com.aurora.studio.initiative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HandoffPackage(String hash, Map<String, Object> content) {
  public static HandoffPackage stored(String hash, Map<String, Object> content) {
    return new HandoffPackage(hash, immutableMap(content));
  }

  public static HandoffPackage create(ObjectMapper mapper, Map<String, Object> content) {
    Map<String, Object> ordered = immutableMap(content);
    try {
      String serialized = mapper.writeValueAsString(canonicalize(mapper.valueToTree(ordered)));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String hash =
          HexFormat.of().formatHex(digest.digest(serialized.getBytes(StandardCharsets.UTF_8)));
      return new HandoffPackage(hash, ordered);
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to hash handoff package", exception);
    }
  }

  private static JsonNode canonicalize(JsonNode value) {
    if (value.isObject()) {
      Map<String, JsonNode> fields = new java.util.TreeMap<>();
      value
          .fields()
          .forEachRemaining(entry -> fields.put(entry.getKey(), canonicalize(entry.getValue())));
      com.fasterxml.jackson.databind.node.ObjectNode ordered =
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      fields.forEach(ordered::set);
      return ordered;
    }
    if (value.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode ordered =
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      value.forEach(child -> ordered.add(canonicalize(child)));
      return ordered;
    }
    return value;
  }

  private static Map<String, Object> immutableMap(Map<?, ?> source) {
    List<Map.Entry<?, ?>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
    Map<String, Object> ordered = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : entries) {
      ordered.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
    }
    return Collections.unmodifiableMap(ordered);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return immutableMap(map);
    }
    if (value instanceof Collection<?> collection) {
      return Collections.unmodifiableList(
          collection.stream().map(HandoffPackage::immutableValue).toList());
    }
    return value;
  }
}
