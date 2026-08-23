package com.aurora.studio.discovery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "studio.discovery")
public class DiscoveryWeights {
  private final Map<String, Double> weights = new LinkedHashMap<>();

  public DiscoveryWeights() {
    weights.put("targetAlignment", 0.20);
    weights.put("populationAlignment", 0.12);
    weights.put("horizonAlignment", 0.10);
    weights.put("featureAvailability", 0.16);
    weights.put("dataAvailability", 0.14);
    weights.put("implementationAvailability", 0.10);
    weights.put("evidenceStrength", 0.10);
    weights.put("executionEvidence", 0.08);
  }

  public Map<String, Double> weights() {
    return Map.copyOf(weights);
  }

  public void setWeights(Map<String, Double> configured) {
    weights.clear();
    if (configured != null) {
      configured.forEach((key, value) -> weights.put(toJavaPropertyName(key), value));
    }
  }

  private static String toJavaPropertyName(String key) {
    StringBuilder normalized = new StringBuilder();
    boolean uppercaseNext = false;
    for (char character : key.toCharArray()) {
      if (character == '-') {
        uppercaseNext = true;
      } else if (uppercaseNext) {
        normalized.append(Character.toUpperCase(character));
        uppercaseNext = false;
      } else {
        normalized.append(character);
      }
    }
    return normalized.toString();
  }
}
