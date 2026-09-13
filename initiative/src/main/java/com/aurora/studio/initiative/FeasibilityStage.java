package com.aurora.studio.initiative;

import static com.aurora.studio.initiative.StageTiming.elapsed;

import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class FeasibilityStage {
  private static final String AGENT = InitiativeActors.ORCHESTRATOR;
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;
  private final KnowledgeService knowledge;
  private final InitiativeQueries initiatives;
  private final InitiativeKnowledge lineage;

  FeasibilityStage(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      InitiativeQueries initiatives,
      InitiativeKnowledge lineage) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
    this.initiatives = initiatives;
    this.lineage = lineage;
  }

  Initiative finishFeasibility(
      InitiativeRepository.Base base, InitiativeRepository.Attempt attempt, Instant started) {
    ModelRequirement requirement = discovery.getRequirement(base.requirementId());
    List<KnowledgeObject> visible =
        knowledge.search(null, null, null, null, null, null, base.includeCandidates());
    Map<UUID, KnowledgeObject> visibleById =
        visible.stream()
            .collect(java.util.stream.Collectors.toMap(KnowledgeObject::id, object -> object));
    List<FeasibilityCheck> checks = new ArrayList<>();
    List<String> blockers = new ArrayList<>();
    Set<KnowledgeObject> resolvedAssets = new java.util.LinkedHashSet<>();
    for (String observable : requirement.requiredObservables()) {
      KnowledgeObject artifact = lineage.findObservable(observable, visible);
      if (artifact == null) {
        checks.add(
            new FeasibilityCheck(
                "observable:" + observable,
                "FAIL",
                null,
                "Instrumentation gap: add the observable before development can continue"));
        blockers.add("MISSING_TARGET_OBSERVABLE:" + observable);
      } else {
        checks.add(
            new FeasibilityCheck("observable:" + observable, "PASS", artifact.id(), "Resolved"));
        Set<KnowledgeObject> assets =
            lineage.resolveDataAssets(artifact, visibleById, base.includeCandidates());
        resolvedAssets.addAll(assets);
        if (assets.isEmpty()) {
          checks.add(
              new FeasibilityCheck(
                  "data-asset-resolution:" + observable,
                  "UNKNOWN",
                  null,
                  "No governed data asset is linked to the required observable"));
        }
      }
    }
    Object requiredFeatures = requirement.constraints().get("requiredFeatures");
    if (requiredFeatures instanceof Collection<?> values && !values.isEmpty()) {
      for (Object value : values) {
        KnowledgeObject feature =
            visible.stream()
                .filter(object -> object.knowledgeType().name().equals("FEATURE"))
                .filter(object -> object.name().equalsIgnoreCase(String.valueOf(value)))
                .findFirst()
                .orElse(null);
        checks.add(
            new FeasibilityCheck(
                "feature:" + value,
                feature == null ? "FAIL" : "PASS",
                feature == null ? null : feature.id(),
                feature == null ? "Required feature is not visible" : "Feature is visible"));
        if (feature == null) {
          blockers.add("MISSING_REQUIRED_FEATURE:" + value);
          continue;
        }
        resolvedAssets.addAll(
            lineage.resolveDataAssets(feature, visibleById, base.includeCandidates()));
        if (lineage.hasOpenBlockingConflict(feature, base.includeCandidates())) {
          checks.add(
              new FeasibilityCheck(
                  "feature-conflict:" + value,
                  "FAIL",
                  feature.id(),
                  "Required feature has an open governed conflict"));
          blockers.add("OPEN_CONFLICT:" + value);
        }
      }
    }
    for (String key : requiredKnowledgeKeys(requirement)) {
      KnowledgeObject artifact =
          visible.stream()
              .filter(object -> object.knowledgeKey().equals(key))
              .findFirst()
              .orElse(null);
      if (artifact == null) {
        checks.add(
            new FeasibilityCheck(
                "knowledge:" + key, "FAIL", null, "Required governed knowledge is not visible"));
        blockers.add("MISSING_REQUIRED_KNOWLEDGE:" + key);
        continue;
      }
      checks.add(
          new FeasibilityCheck(
              "knowledge:" + key, "PASS", artifact.id(), "Required governed knowledge is visible"));
      if (lineage.hasOpenBlockingConflict(artifact, base.includeCandidates())) {
        checks.add(
            new FeasibilityCheck(
                "knowledge-conflict:" + key,
                "FAIL",
                artifact.id(),
                "Required governed knowledge has an open governed conflict"));
        blockers.add("OPEN_CONFLICT:" + key);
      }
    }
    if (resolvedAssets.isEmpty()) {
      checks.add(
          new FeasibilityCheck(
              "data-asset-resolution",
              "UNKNOWN",
              null,
              "No governed data asset could be resolved from the requirement"));
    } else {
      resolvedAssets.forEach(
          dataAsset -> addDataAssetChecks(requirement, dataAsset, checks, blockers));
    }
    boolean hasUnknown = checks.stream().anyMatch(check -> check.status().equals("UNKNOWN"));
    StageStatus status =
        !blockers.isEmpty()
            ? StageStatus.BLOCKED
            : hasUnknown ? StageStatus.AWAITING_APPROVAL : StageStatus.COMPLETED;
    Instant finished = Instant.now();
    Map<UUID, Boolean> syntheticById =
        visible.stream()
            .collect(
                java.util.stream.Collectors.toMap(KnowledgeObject::id, KnowledgeObject::synthetic));
    List<ArtifactReference> artifacts =
        checks.stream()
            .filter(check -> check.artifactId() != null)
            .map(
                check ->
                    new ArtifactReference(
                        "KNOWLEDGE_OBJECT",
                        check.artifactId(),
                        syntheticById.getOrDefault(check.artifactId(), false)))
            .distinct()
            .toList();
    if (status == StageStatus.AWAITING_APPROVAL) {
      repository.awaitApproval(
          attempt.id(), finished, elapsed(started, finished), blockers, checks, artifacts);
    } else {
      repository.finish(
          attempt.id(),
          status,
          finished,
          elapsed(started, finished),
          0,
          blockers,
          checks,
          artifacts);
    }
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        status,
        AGENT,
        status == StageStatus.AWAITING_APPROVAL
            ? "UNKNOWN feasibility checks require explicit human acceptance"
            : blockers.isEmpty()
                ? "Deterministic feasibility checks completed"
                : "Data feasibility blocked",
        artifacts);
    return initiatives.get(base.id());
  }

  private List<String> requiredKnowledgeKeys(ModelRequirement requirement) {
    Set<String> keys = new java.util.LinkedHashSet<>();
    for (String constraint : List.of("requiredKnowledgeKeys", "requiredKnowledge")) {
      Object value = requirement.constraints().get(constraint);
      if (value instanceof Collection<?> values) {
        values.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .filter(key -> !key.isBlank())
            .forEach(keys::add);
      }
    }
    return List.copyOf(keys);
  }

  private void addDataAssetChecks(
      ModelRequirement requirement,
      KnowledgeObject dataAsset,
      List<FeasibilityCheck> checks,
      List<String> blockers) {
    addCheck(
        checks,
        blockers,
        "DATA_HISTORY:" + dataAsset.name(),
        check(
            "data-history:" + dataAsset.name(),
            dataAsset,
            compareHistory(dataAsset.attributes().get("history"), requirement.outcomeHorizon())));
    addCheck(
        checks,
        blockers,
        "DATA_REFRESH_CADENCE:" + dataAsset.name(),
        check(
            "data-refresh-cadence:" + dataAsset.name(),
            dataAsset,
            compareRefreshCadence(
                dataAsset.attributes().get("refreshCadence"), requirement.decisionLatency())));
    addCheck(
        checks,
        blockers,
        "DATA_GRAIN:" + dataAsset.name(),
        check(
            "data-grain:" + dataAsset.name(),
            dataAsset,
            compareGrain(dataAsset.attributes().get("grain"), requirement.population())));
    Object pointInTime = dataAsset.attributes().get("pointInTimeAvailable");
    addCheck(
        checks,
        blockers,
        "POINT_IN_TIME:" + dataAsset.name(),
        new FeasibilityCheck(
            "point-in-time-reconstruction:" + dataAsset.name(),
            pointInTime instanceof Boolean
                ? Boolean.TRUE.equals(pointInTime) ? "PASS" : "FAIL"
                : "UNKNOWN",
            dataAsset.id(),
            pointInTime instanceof Boolean
                ? Boolean.TRUE.equals(pointInTime)
                    ? "Point-in-time reconstruction is declared available"
                    : "Point-in-time reconstruction is declared unavailable"
                : "Point-in-time reconstruction is not declared"));
  }

  private void addCheck(
      List<FeasibilityCheck> checks,
      List<String> blockers,
      String blocker,
      FeasibilityCheck check) {
    checks.add(check);
    if (check.status().equals("FAIL")) blockers.add(blocker);
  }

  private FeasibilityCheck check(String name, KnowledgeObject artifact, CheckResult result) {
    return new FeasibilityCheck(name, result.status(), artifact.id(), result.reason());
  }

  private CheckResult compareHistory(Object history, String horizon) {
    if (history instanceof Boolean) {
      return new CheckResult("UNKNOWN", "History availability is declared, but depth is not");
    }
    Long historyMillis = durationMillis(history);
    Long horizonMillis = durationMillis(horizon);
    if (historyMillis == null || horizonMillis == null) {
      return new CheckResult(
          "UNKNOWN", "History depth cannot be compared with the requirement horizon");
    }
    return historyMillis >= horizonMillis
        ? new CheckResult("PASS", "Declared history depth meets the requirement horizon")
        : new CheckResult("FAIL", "Declared history depth is shorter than the requirement horizon");
  }

  private CheckResult compareRefreshCadence(Object cadence, String decisionLatency) {
    Long cadenceMillis = durationMillis(cadence);
    Long latencyMillis = durationMillis(decisionLatency);
    if (cadenceMillis == null || latencyMillis == null) {
      return new CheckResult(
          "UNKNOWN", "Refresh cadence and decision latency are not both machine-comparable");
    }
    return cadenceMillis <= latencyMillis
        ? new CheckResult("PASS", "Refresh cadence meets the decision latency")
        : new CheckResult("FAIL", "Refresh cadence is slower than the decision latency");
  }

  private CheckResult compareGrain(Object grain, String population) {
    if (grain == null || population == null || population.isBlank()) {
      return new CheckResult(
          "UNKNOWN", "Data grain and requirement population are not both declared");
    }
    String declared = String.valueOf(grain).toLowerCase();
    String requested = population.toLowerCase();
    Set<String> dimensions =
        Set.of("customer", "session", "account", "booking", "event", "property");
    Optional<String> declaredDimension = dimensions.stream().filter(declared::contains).findFirst();
    Optional<String> requestedDimension =
        dimensions.stream().filter(requested::contains).findFirst();
    if (declaredDimension.isEmpty() || requestedDimension.isEmpty()) {
      return new CheckResult("UNKNOWN", "Data grain semantics are not machine-comparable");
    }
    return declaredDimension.get().equals(requestedDimension.get())
        ? new CheckResult("PASS", "Declared data grain matches the requirement population")
        : new CheckResult(
            "UNKNOWN", "Data grain and requirement population use different semantic dimensions");
  }

  private Long durationMillis(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value).trim().toLowerCase();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)(ms|s|m|h|d|w)$").matcher(text);
    if (!matcher.matches()) return null;
    double amount = Double.parseDouble(matcher.group(1));
    long multiplier =
        switch (matcher.group(2)) {
          case "ms" -> 1L;
          case "s" -> 1_000L;
          case "m" -> 60_000L;
          case "h" -> 3_600_000L;
          case "d" -> 86_400_000L;
          case "w" -> 604_800_000L;
          default -> 0L;
        };
    return (long) (amount * multiplier);
  }

  private record CheckResult(String status, String reason) {}
}
