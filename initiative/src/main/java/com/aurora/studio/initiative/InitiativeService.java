package com.aurora.studio.initiative;

import com.aurora.studio.common.RelationshipType;
import com.aurora.studio.discovery.DiscoveryCandidate;
import com.aurora.studio.discovery.DiscoveryRun;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitiativeService {
  private static final String AGENT = "initiative-orchestrator";
  private static final Set<InitiativeStage> GATED_STAGES =
      EnumSet.of(
          InitiativeStage.REUSE_DECISION,
          InitiativeStage.TARGETING_DESIGN,
          InitiativeStage.FEATURE_DESIGN,
          InitiativeStage.EXPERIMENT_DESIGN,
          InitiativeStage.HANDOFF);
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;
  private final KnowledgeService knowledge;

  public InitiativeService(
      InitiativeRepository repository, DiscoveryService discovery, KnowledgeService knowledge) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
  }

  @Transactional
  public Initiative create(CreateInitiativeRequest request) {
    if (request == null || request.requirementId() == null) {
      throw new IllegalArgumentException("requirementId is required");
    }
    discovery.getRequirement(request.requirementId());
    if (request.clientBaselineDurationMillis() != null
        && request.clientBaselineDurationMillis() < 0) {
      throw new IllegalArgumentException("clientBaselineDurationMillis must not be negative");
    }
    UUID id =
        repository.create(
            request.requirementId(),
            request.includeCandidates(),
            request.clientBaselineDurationMillis());
    return get(id);
  }

  public List<Initiative> list() {
    return repository.findAll().stream()
        .map(base -> assemble(base, repository.attempts(base.id())))
        .toList();
  }

  public Initiative get(UUID id) {
    InitiativeRepository.Base base =
        repository
            .find(id)
            .orElseThrow(() -> new IllegalArgumentException("Initiative was not found"));
    return assemble(base, repository.attempts(id));
  }

  @Transactional
  public List<Initiative> seedDemo() {
    if (knowledge.search(null, null, null, null, null, null, false).isEmpty()) {
      return List.of();
    }
    Optional<UUID> existingReuseRequirement =
        discovery.findRequirementByUseCase("booking propensity");
    Optional<UUID> existingCancellationRequirement =
        discovery.findRequirementByUseCase("booking cancellation prevention");
    Initiative reuse =
        existingReuseRequirement
            .flatMap(repository::findIdByRequirement)
            .map(this::get)
            .orElseGet(
                () ->
                    seedOne(
                        existingReuseRequirement.orElseGet(
                            () -> discovery.register(reuseRequirement()))));
    Initiative cancellation =
        existingCancellationRequirement
            .flatMap(repository::findIdByRequirement)
            .map(this::get)
            .orElseGet(
                () ->
                    seedOne(
                        existingCancellationRequirement.orElseGet(
                            () -> discovery.register(cancellationRequirement()))));
    return List.of(reuse, cancellation);
  }

  private ModelRequirement reuseRequirement() {
    ModelRequirement reuseRequirement =
        new ModelRequirement(
            "customer intelligence",
            "booking propensity",
            "BOOKING_COMPLETED",
            "BOOKING_COMPLETED",
            "eligible consented sessions",
            "30d",
            "batch",
            "prioritize outreach",
            Map.of("requiredFeatures", List.of("booking-intent")),
            Map.of(),
            Map.of(),
            List.of("BOOKING_COMPLETED"),
            false);
    return reuseRequirement;
  }

  private ModelRequirement cancellationRequirement() {
    ModelRequirement cancellationRequirement =
        new ModelRequirement(
            "customer intelligence",
            "booking cancellation prevention",
            "BOOKING_CANCELLED",
            "BOOKING_CANCELLED",
            "eligible consented sessions",
            "30d",
            "batch",
            "prioritize retention outreach",
            Map.of(),
            Map.of(),
            Map.of(),
            List.of("BOOKING_CANCELLED"),
            false);
    return cancellationRequirement;
  }

  private Initiative seedOne(UUID requirementId) {
    Initiative initiative = create(new CreateInitiativeRequest(requirementId, false, null));
    runStage(initiative.id(), InitiativeStage.KNOWLEDGE_DISCOVERY);
    runStage(initiative.id(), InitiativeStage.REUSE_DECISION);
    decide(
        initiative.id(),
        InitiativeStage.REUSE_DECISION,
        new GateDecisionRequest(
            "APPROVE", "seed-human-reviewer", "Seeded demo decision; identity is unverified"));
    runStage(initiative.id(), InitiativeStage.DATA_FEASIBILITY);
    return get(initiative.id());
  }

  @Transactional
  public Initiative runStage(UUID initiativeId, InitiativeStage stage) {
    InitiativeRepository.Base base = require(initiativeId);
    if (repository.latestAttempt(initiativeId, stage).isEmpty()) {
      throw new IllegalArgumentException("Unknown initiative stage");
    }
    InitiativeRepository.Attempt current = latest(initiativeId, stage);
    if (current.status() == StageStatus.NOT_IMPLEMENTED
        || current.status() == StageStatus.OUT_OF_SCOPE) {
      throw new IllegalStateException("Stage " + stage + " cannot be run");
    }
    if (current.status() == StageStatus.IN_PROGRESS
        || current.status() == StageStatus.AWAITING_APPROVAL) {
      throw new IllegalStateException("Stage is already running or awaiting approval");
    }
    if (stage != InitiativeStage.REQUIREMENT_INTAKE) {
      InitiativeStage predecessor = InitiativeStage.values()[stage.ordinal() - 1];
      StageStatus predecessorStatus = latest(initiativeId, predecessor).status();
      if (predecessorStatus != StageStatus.COMPLETED) {
        throw new IllegalStateException(
            "Stage " + stage + " cannot start before " + predecessor + " is completed");
      }
    }
    if (current.status() != StageStatus.PENDING) {
      int attempt = current.attempt() + 1;
      UUID attemptId = repository.insertAttempt(initiativeId, stage, attempt, StageStatus.PENDING);
      current =
          new InitiativeRepository.Attempt(
              attemptId,
              stage,
              attempt,
              StageStatus.PENDING,
              null,
              null,
              0,
              0,
              List.of(),
              List.of(),
              List.of());
    }
    Instant started = Instant.now();
    repository.start(current.id(), started);
    repository.insertEvent(
        initiativeId,
        stage,
        StageStatus.PENDING,
        StageStatus.IN_PROGRESS,
        AGENT,
        "Stage started",
        List.of());
    try {
      return switch (stage) {
        case KNOWLEDGE_DISCOVERY -> finishDiscovery(base, current, started);
        case REUSE_DECISION -> awaitReuseDecision(base, current, started);
        case DATA_FEASIBILITY -> finishFeasibility(base, current, started);
        case REQUIREMENT_INTAKE ->
            throw new IllegalStateException("Requirement intake is completed at creation");
        default -> throw new IllegalStateException("Stage " + stage + " is not implemented");
      };
    } catch (RuntimeException exception) {
      long elapsed = elapsed(started, Instant.now());
      repository.finish(
          current.id(),
          StageStatus.BLOCKED,
          Instant.now(),
          elapsed,
          0,
          List.of("STAGE_PRODUCER_FAILED:" + exception.getMessage()),
          List.of(),
          List.of());
      repository.insertEvent(
          initiativeId,
          stage,
          StageStatus.IN_PROGRESS,
          StageStatus.BLOCKED,
          AGENT,
          exception.getMessage(),
          List.of());
      throw exception;
    }
  }

  @Transactional
  public Initiative decide(UUID initiativeId, InitiativeStage stage, GateDecisionRequest request) {
    if (!GATED_STAGES.contains(stage)) {
      throw new IllegalArgumentException("Stage does not have a human gate");
    }
    if (request == null || request.actor() == null || request.actor().isBlank()) {
      throw new IllegalArgumentException("actor is required");
    }
    String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
    if (!Set.of("APPROVE", "REJECT", "RETURN").contains(decision)) {
      throw new IllegalArgumentException("decision must be APPROVE, REJECT, or RETURN");
    }
    if ((decision.equals("REJECT") || decision.equals("RETURN"))
        && (request.reason() == null || request.reason().isBlank())) {
      throw new IllegalArgumentException("reason is required for reject and return");
    }
    require(initiativeId);
    InitiativeRepository.Attempt current = latest(initiativeId, stage);
    if (current.status() != StageStatus.AWAITING_APPROVAL) {
      throw new IllegalStateException("Stage is not awaiting human approval");
    }
    Instant now = Instant.now();
    long wait = current.completedAt() == null ? 0 : elapsed(current.completedAt(), now);
    repository.insertGateDecision(
        initiativeId, current.id(), stage, decision, request.actor(), request.reason());
    StageStatus next =
        switch (decision) {
          case "APPROVE" -> StageStatus.COMPLETED;
          case "REJECT" -> StageStatus.REJECTED;
          default -> StageStatus.PENDING;
        };
    repository.finish(
        current.id(),
        next,
        now,
        current.machineDurationMillis(),
        wait,
        current.blockers(),
        current.feasibilityChecks(),
        current.artifacts());
    repository.insertEvent(
        initiativeId,
        stage,
        StageStatus.AWAITING_APPROVAL,
        next,
        request.actor(),
        request.reason(),
        current.artifacts());
    return get(initiativeId);
  }

  private Initiative finishDiscovery(
      InitiativeRepository.Base base, InitiativeRepository.Attempt attempt, Instant started) {
    DiscoveryRun run = discovery.run(base.requirementId(), base.includeCandidates());
    List<ArtifactReference> artifacts = discoveryArtifacts(run);
    boolean blocked =
        run.candidates().isEmpty() && run.reasonCodes().contains("NO_RECALL_CANDIDATE");
    StageStatus status = blocked ? StageStatus.BLOCKED : StageStatus.COMPLETED;
    List<String> blockers = blocked ? List.of("NO_RECALL_CANDIDATE") : List.of();
    Instant finished = Instant.now();
    repository.finish(
        attempt.id(),
        status,
        finished,
        elapsed(started, finished),
        0,
        blockers,
        List.of(),
        artifacts);
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        status,
        AGENT,
        blocked ? "Discovery produced no usable candidate" : "Discovery completed",
        artifacts);
    return get(base.id());
  }

  private Initiative awaitReuseDecision(
      InitiativeRepository.Base base, InitiativeRepository.Attempt attempt, Instant started) {
    InitiativeRepository.Attempt discoveryAttempt =
        latest(base.id(), InitiativeStage.KNOWLEDGE_DISCOVERY);
    List<ArtifactReference> artifacts = discoveryAttempt.artifacts();
    Instant finished = Instant.now();
    repository.awaitApproval(
        attempt.id(), finished, elapsed(started, finished), List.of(), artifacts);
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        StageStatus.AWAITING_APPROVAL,
        AGENT,
        "Awaiting human reuse decision; actor identity is self-declared and unverified",
        artifacts);
    return get(base.id());
  }

  private Initiative finishFeasibility(
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
      KnowledgeObject artifact = findObservable(observable, visible);
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
            resolveDataAssets(artifact, visibleById, base.includeCandidates());
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
        resolvedAssets.addAll(resolveDataAssets(feature, visibleById, base.includeCandidates()));
        if (knowledge.get(feature.id(), base.includeCandidates()).conflicts().stream()
            .anyMatch(conflict -> conflict.status().name().equals("OPEN") && conflict.blocking())) {
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
    StageStatus status = blockers.isEmpty() ? StageStatus.COMPLETED : StageStatus.BLOCKED;
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
    repository.finish(
        attempt.id(), status, finished, elapsed(started, finished), 0, blockers, checks, artifacts);
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        status,
        AGENT,
        blockers.isEmpty()
            ? "Deterministic feasibility checks completed"
            : "Data feasibility blocked",
        artifacts);
    return get(base.id());
  }

  private Set<KnowledgeObject> resolveDataAssets(
      KnowledgeObject artifact, Map<UUID, KnowledgeObject> visibleById, boolean includeCandidates) {
    Set<KnowledgeObject> assets = new java.util.LinkedHashSet<>();
    java.util.ArrayDeque<UUID> pending = new java.util.ArrayDeque<>();
    Set<UUID> visited = new java.util.HashSet<>();
    pending.add(artifact.id());
    while (!pending.isEmpty()) {
      UUID current = pending.removeFirst();
      if (!visited.add(current)) continue;
      KnowledgeObject currentObject = visibleById.get(current);
      if (currentObject != null && currentObject.knowledgeType().name().equals("DATA_ASSET")) {
        assets.add(currentObject);
        continue;
      }
      KnowledgePackage pack = knowledge.get(current, includeCandidates);
      for (KnowledgeRelationship relationship : pack.relationships()) {
        if (!relationship.fromObjectId().equals(current)) continue;
        UUID relatedId = relationship.toObjectId();
        KnowledgeObject related = visibleById.get(relatedId);
        if (related == null) continue;
        if (relationship.relationshipType() == RelationshipType.DERIVED_FROM
            && related.knowledgeType().name().equals("DATA_ASSET")) {
          assets.add(related);
        } else if (relationship.relationshipType() == RelationshipType.IMPLEMENTED_BY) {
          pending.addLast(relatedId);
        }
      }
    }
    return assets;
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

  private KnowledgeObject findObservable(String observable, List<KnowledgeObject> visible) {
    String expected = observable.toLowerCase();
    return visible.stream()
        .filter(
            object ->
                object.name().equalsIgnoreCase(observable)
                    || contains(object.attributes(), expected))
        .findFirst()
        .orElse(null);
  }

  private boolean contains(Object value, String expected) {
    if (value == null) return false;
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .anyMatch(
              entry -> contains(entry.getKey(), expected) || contains(entry.getValue(), expected));
    }
    if (value instanceof Collection<?> values) {
      return values.stream().anyMatch(item -> contains(item, expected));
    }
    String text = String.valueOf(value).toLowerCase();
    return text.equals(expected) || text.contains(expected);
  }

  private List<ArtifactReference> discoveryArtifacts(DiscoveryRun run) {
    List<ArtifactReference> artifacts = new ArrayList<>();
    artifacts.add(new ArtifactReference("DISCOVERY_RUN", run.id(), false));
    for (DiscoveryCandidate candidate : run.candidates()) {
      artifacts.add(
          new ArtifactReference("KNOWLEDGE_OBJECT", candidate.id(), candidate.synthetic()));
    }
    return artifacts;
  }

  private Initiative assemble(
      InitiativeRepository.Base base, List<InitiativeRepository.Attempt> attempts) {
    Map<InitiativeStage, List<InitiativeRepository.Attempt>> grouped = new LinkedHashMap<>();
    for (InitiativeStage stage : InitiativeStage.values()) grouped.put(stage, new ArrayList<>());
    attempts.forEach(
        attempt ->
            grouped.computeIfAbsent(attempt.stage(), ignored -> new ArrayList<>()).add(attempt));
    List<StageState> stages =
        grouped.entrySet().stream()
            .map(
                entry -> {
                  InitiativeRepository.Attempt current =
                      entry.getValue().stream()
                          .max(Comparator.comparingInt(InitiativeRepository.Attempt::attempt))
                          .orElseThrow();
                  return new StageState(
                      entry.getKey(),
                      current.status(),
                      current.attempt(),
                      entry.getValue().stream().map(this::stageAttempt).toList(),
                      stageNote(entry.getKey()));
                })
            .toList();
    List<ArtifactReference> artifacts =
        attempts.stream().flatMap(attempt -> attempt.artifacts().stream()).distinct().toList();
    List<String> blockers =
        attempts.stream()
            .filter(
                attempt ->
                    attempt.attempt()
                        == attempts.stream()
                            .filter(other -> other.stage() == attempt.stage())
                            .mapToInt(InitiativeRepository.Attempt::attempt)
                            .max()
                            .orElse(attempt.attempt()))
            .flatMap(attempt -> attempt.blockers().stream())
            .distinct()
            .toList();
    long machine =
        attempts.stream().mapToLong(InitiativeRepository.Attempt::machineDurationMillis).sum();
    long wait =
        attempts.stream().mapToLong(InitiativeRepository.Attempt::humanWaitDurationMillis).sum();
    Long baseline = base.clientBaselineDurationMillis();
    Long reduction = baseline == null ? null : baseline - machine;
    String comparisonNote =
        baseline == null
            ? "No client-declared baseline; delivery-time comparison is unavailable"
            : "Comparison is client-declared: baseline minus measured machine duration";
    String status =
        blockers.isEmpty()
            ? stages.stream().anyMatch(stage -> stage.status() == StageStatus.REJECTED)
                ? "REJECTED"
                : stages.stream().anyMatch(stage -> stage.status() == StageStatus.COMPLETED)
                    ? "ACTIVE"
                    : "PENDING"
            : "BLOCKED";
    return new Initiative(
        base.id(),
        base.requirementId(),
        discovery.getRequirement(base.requirementId()),
        status,
        base.includeCandidates(),
        false,
        base.createdAt(),
        stages,
        artifacts,
        blockers,
        repository.decisions(base.id()).stream().map(this::decision).toList(),
        new DurationSummary(machine, wait, baseline, reduction, baseline != null, comparisonNote),
        repository.events(base.id()).stream().map(this::event).toList());
  }

  private StageAttempt stageAttempt(InitiativeRepository.Attempt attempt) {
    return new StageAttempt(
        attempt.id(),
        attempt.attempt(),
        attempt.status(),
        attempt.startedAt(),
        attempt.completedAt(),
        attempt.machineDurationMillis(),
        attempt.humanWaitDurationMillis(),
        attempt.blockers(),
        attempt.feasibilityChecks(),
        attempt.artifacts());
  }

  private String stageNote(InitiativeStage stage) {
    if (stage == InitiativeStage.CANDIDATE_BUILD) {
      return "Training occurs in the client environment";
    }
    if (stage == InitiativeStage.TARGETING_DESIGN
        || stage == InitiativeStage.FEATURE_DESIGN
        || stage == InitiativeStage.EXPERIMENT_DESIGN
        || stage == InitiativeStage.HANDOFF) {
      return "NOT_IMPLEMENTED";
    }
    return null;
  }

  private GateDecision decision(InitiativeRepository.GateRow row) {
    return new GateDecision(
        row.id(),
        row.stage(),
        row.stageAttemptId(),
        row.decision(),
        row.actor(),
        row.actorVerified(),
        row.reason(),
        row.createdAt());
  }

  private InitiativeEvent event(InitiativeRepository.EventRow row) {
    return new InitiativeEvent(
        row.id(),
        row.stage(),
        row.fromStatus(),
        row.toStatus(),
        row.actor(),
        row.reason(),
        row.artifacts(),
        row.at());
  }

  private InitiativeRepository.Base require(UUID id) {
    return repository
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("Initiative was not found"));
  }

  private InitiativeRepository.Attempt latest(UUID initiativeId, InitiativeStage stage) {
    return repository
        .latestAttempt(initiativeId, stage)
        .orElseThrow(() -> new IllegalArgumentException("Unknown initiative stage"));
  }

  private long elapsed(Instant start, Instant end) {
    return Math.max(0, Duration.between(start, end).toMillis());
  }
}
