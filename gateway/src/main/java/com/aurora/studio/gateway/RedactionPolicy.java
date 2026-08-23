package com.aurora.studio.gateway;

import java.util.List;
import java.util.regex.Pattern;

public record RedactionPolicy(
    boolean includeEvidenceExcerpts,
    boolean includeStructuralFacts,
    List<String> redactedPatterns) {
  public static RedactionPolicy extractionDefault() {
    return new RedactionPolicy(true, true, List.of("password", "secret", "api_key", "token"));
  }

  public String redact(String text) {
    if (!includeEvidenceExcerpts() || text == null) return "";
    String result = text;
    for (String pattern : redactedPatterns()) {
      result =
          Pattern.compile("(?i)(" + Pattern.quote(pattern) + "\\s*[:=]\\s*)([^\\s,;]+)")
              .matcher(result)
              .replaceAll("$1[REDACTED]");
    }
    return result.replaceAll(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b",
        "[CLIENT-ID-REDACTED]");
  }
}
