package com.aurora.studio.initiative;

import java.util.List;

public record GateDecisionRequest(
    String decision, String actor, String reason, List<String> acceptedUnknownChecks) {
  public GateDecisionRequest(String decision, String actor, String reason) {
    this(decision, actor, reason, List.of());
  }
}
