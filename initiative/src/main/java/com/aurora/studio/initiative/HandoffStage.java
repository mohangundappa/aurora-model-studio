package com.aurora.studio.initiative;

import static com.aurora.studio.initiative.DesignPayloads.map;
import static com.aurora.studio.initiative.DesignPayloads.string;
import static com.aurora.studio.initiative.StageTiming.elapsed;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeEvidence;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class HandoffStage {
  private static final String AGENT = InitiativeActors.ORCHESTRATOR;
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;
  private final KnowledgeService knowledge;
  private final InitiativeQueries initiatives;
  private final InitiativeKnowledge lineage;
  private final AuroraCandidateClient aurora;
  private final ObjectMapper mapper;

  HandoffStage(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      InitiativeQueries initiatives,
      InitiativeKnowledge lineage,
      AuroraCandidateClient aurora,
      ObjectMapper mapper) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
    this.initiatives = initiatives;
    this.lineage = lineage;
    this.aurora = aurora;
    this.mapper = mapper;
  }

  Initiative awaitHandoff(
      InitiativeRepository.Base base, InitiativeRepository.Attempt attempt, Instant started) {
    List<String> blockers = handoffBlockers(base);
    Instant finished = Instant.now();
    if (!blockers.isEmpty()) {
      repository.finish(
          attempt.id(),
          StageStatus.BLOCKED,
          finished,
          elapsed(started, finished),
          0,
          blockers,
          List.of(),
          List.of());
      repository.insertEvent(
          base.id(),
          attempt.stage(),
          StageStatus.IN_PROGRESS,
          StageStatus.BLOCKED,
          AGENT,
          "Handoff preconditions failed",
          List.of());
      return initiatives.get(base.id());
    }
    HandoffPackage handoff = buildPackage(base);
    UUID packageId = repository.savePackage(base.id(), handoff.hash(), handoff.content());
    List<ArtifactReference> artifacts =
        List.of(new ArtifactReference("HANDOFF_PACKAGE", packageId, false));
    repository.awaitApproval(
        attempt.id(), finished, elapsed(started, finished), List.of(), List.of(), artifacts);
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        StageStatus.AWAITING_APPROVAL,
        AGENT,
        "Awaiting human handoff approval",
        artifacts);
    return initiatives.get(base.id());
  }

  private List<String> handoffBlockers(InitiativeRepository.Base base) {
    List<String> blockers = new ArrayList<>();
    InitiativeRepository.Attempt targeting =
        initiatives.latest(base.id(), InitiativeStage.TARGETING_DESIGN);
    InitiativeRepository.Attempt feature =
        initiatives.latest(base.id(), InitiativeStage.FEATURE_DESIGN);
    InitiativeRepository.Attempt experiment =
        initiatives.latest(base.id(), InitiativeStage.EXPERIMENT_DESIGN);
    InitiativeRepository.Attempt feasibility =
        initiatives.latest(base.id(), InitiativeStage.DATA_FEASIBILITY);
    if (targeting.status() != StageStatus.COMPLETED) blockers.add("TARGETING_DESIGN_NOT_COMPLETED");
    if (feature.status() != StageStatus.COMPLETED) blockers.add("FEATURE_DESIGN_NOT_COMPLETED");
    ModelRequirement requirement = discovery.getRequirement(base.requirementId());
    String modelName = modelName(requirement);
    if (modelName.isBlank()) blockers.add("MISSING_MODEL_NAME");
    List<KnowledgeObject> features = referencedFeatures(requirement, feature);
    Set<KnowledgeObject> dataAssets = new java.util.LinkedHashSet<>();
    Map<UUID, KnowledgeObject> visibleById =
        knowledge.search(null, null, null, null, null, null, true).stream()
            .collect(java.util.stream.Collectors.toMap(KnowledgeObject::id, value -> value));
    for (KnowledgeObject object : features) {
      if (!"APPROVED".equals(object.lifecycleStatus())) {
        blockers.add("FEATURE_NOT_APPROVED:" + object.knowledgeKey());
      }
      if (lineage.hasOpenBlockingConflict(object, true)) {
        blockers.add("OPEN_CONFLICT:" + object.knowledgeKey());
      }
      dataAssets.addAll(lineage.resolveDataAssets(object, visibleById, true));
    }
    if (experiment.status() != StageStatus.COMPLETED) {
      blockers.add("EXPERIMENT_DESIGN_NOT_COMPLETED");
    }
    List<String> unknown =
        feasibility.feasibilityChecks().stream()
            .filter(check -> check.status().equals("UNKNOWN"))
            .map(FeasibilityCheck::name)
            .toList();
    if (!unknown.isEmpty() && !acceptedUnknowns(base.id(), feasibility, unknown)) {
      unknown.forEach(check -> blockers.add("DATA_FEASIBILITY_UNKNOWN_NOT_ACCEPTED:" + check));
    }
    KnowledgeObject outcome =
        lineage.findObservable(
            requirement.observableDefinition(), visibleById.values().stream().toList());
    if (outcome == null) {
      blockers.add("MISSING_REQUIRED_OBSERVABLE:" + requirement.observableDefinition());
    } else if (lineage.hasOpenBlockingConflict(outcome, true)) {
      blockers.add("OPEN_CONFLICT:" + outcome.knowledgeKey());
    } else {
      dataAssets.addAll(lineage.resolveDataAssets(outcome, visibleById, true));
    }
    dataAssets.stream()
        .filter(asset -> lineage.hasOpenBlockingConflict(asset, true))
        .map(KnowledgeObject::knowledgeKey)
        .map(key -> "OPEN_CONFLICT:" + key)
        .forEach(blockers::add);
    return blockers.stream().distinct().toList();
  }

  private boolean acceptedUnknowns(
      UUID initiativeId, InitiativeRepository.Attempt attempt, List<String> unknown) {
    return repository.decisions(initiativeId).stream()
        .anyMatch(
            row ->
                row.stage() == attempt.stage()
                    && row.stageAttemptId().equals(attempt.id())
                    && row.decision().equals("APPROVE")
                    && row.actor() != null
                    && !row.actor().isBlank()
                    && row.acceptedUnknownChecks().equals(unknown.stream().sorted().toList()));
  }

  private List<KnowledgeObject> referencedFeatures(
      ModelRequirement requirement, InitiativeRepository.Attempt featureAttempt) {
    List<KnowledgeObject> available =
        knowledge.search("FEATURE", null, null, null, null, null, true);
    Set<String> names = new java.util.LinkedHashSet<>();
    Object required = requirement.constraints().get("requiredFeatures");
    if (required instanceof Collection<?> values) {
      values.forEach(value -> names.add(String.valueOf(value)));
    }
    featureAttempt.drafts().stream()
        .filter(draft -> "ACCEPTED".equals(draft.outcome()) || "REUSE".equals(draft.outcome()))
        .map(draft -> string(draft.payload(), "name"))
        .filter(name -> !name.isBlank())
        .forEach(names::add);
    return available.stream()
        .filter(object -> names.stream().anyMatch(name -> object.name().equalsIgnoreCase(name)))
        .sorted(
            // Only approved knowledge is trusted; within that lifecycle choice, newest wins.
            Comparator.comparing(KnowledgeObject::knowledgeKey)
                .thenComparingInt(object -> -featureLifecyclePriority(object.lifecycleStatus()))
                .thenComparingInt(object -> -object.version()))
        .collect(
            java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(
                    KnowledgeObject::knowledgeKey,
                    object -> object,
                    (first, ignored) -> first,
                    LinkedHashMap::new),
                map -> List.copyOf(map.values())));
  }

  private int featureLifecyclePriority(String lifecycleStatus) {
    if ("APPROVED".equals(lifecycleStatus)) return 2;
    if ("PENDING_REVIEW".equals(lifecycleStatus)) return 1;
    return 0;
  }

  HandoffPackage buildPackage(InitiativeRepository.Base base) {
    ModelRequirement requirement = discovery.getRequirement(base.requirementId());
    InitiativeRepository.Attempt targeting =
        initiatives.latest(base.id(), InitiativeStage.TARGETING_DESIGN);
    InitiativeRepository.Attempt feature =
        initiatives.latest(base.id(), InitiativeStage.FEATURE_DESIGN);
    InitiativeRepository.Attempt experiment =
        initiatives.latest(base.id(), InitiativeStage.EXPERIMENT_DESIGN);
    List<KnowledgeObject> features = referencedFeatures(requirement, feature);
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<KnowledgeObject> referenced = new ArrayList<>(features);
    List<KnowledgeObject> visible = knowledge.search(null, null, null, null, null, null, true);
    KnowledgeObject outcome = lineage.findObservable(requirement.observableDefinition(), visible);
    if (outcome != null) referenced.add(outcome);
    Set<KnowledgeObject> dataAssets = new java.util.LinkedHashSet<>();
    Map<UUID, KnowledgeObject> visibleById =
        visible.stream()
            .collect(java.util.stream.Collectors.toMap(KnowledgeObject::id, value -> value));
    for (KnowledgeObject object : referenced) {
      dataAssets.addAll(lineage.resolveDataAssets(object, visibleById, true));
    }
    referenced.addAll(dataAssets);
    for (KnowledgeObject object : referenced) {
      KnowledgePackage pack = knowledge.get(object.id(), true);
      for (KnowledgeEvidence item : pack.evidence()) {
        evidence.add(
            Map.of(
                "knowledgeId", object.id().toString(),
                "sourceFile", item.sourceUri(),
                "commit", item.sourceVersion(),
                "excerptSha256", evidenceHash(item)));
      }
    }
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("requirementId", base.requirementId().toString());
    content.put("modelName", modelName(requirement));
    content.put("declaredObservables", requirement.requiredObservables());
    content.put(
        "targeting",
        Map.of(
            "design",
            acceptedPayload(targeting),
            "validatorVerdicts",
            targeting.drafts().stream()
                .filter(
                    draft -> "ACCEPTED".equals(draft.outcome()) || "REUSE".equals(draft.outcome()))
                .flatMap(draft -> draft.validatorVerdicts().stream())
                .toList()));
    content.put(
        "features",
        features.stream()
            .map(
                object ->
                    Map.of(
                        "knowledgeId", object.id().toString(),
                        "knowledgeKey", object.knowledgeKey(),
                        "version", object.version()))
            .toList());
    content.put(
        "dataAssets",
        dataAssets.stream()
            .map(
                object ->
                    Map.of(
                        "knowledgeId", object.id().toString(),
                        "knowledgeKey", object.knowledgeKey(),
                        "version", object.version()))
            .toList());
    content.put(
        "experimentDesign",
        experiment.drafts().stream().findFirst().map(GenerationDraft::payload).orElse(Map.of()));
    content.put(
        "feasibility",
        Map.of(
            "checks",
            initiatives.latest(base.id(), InitiativeStage.DATA_FEASIBILITY).feasibilityChecks(),
            "acceptedBy",
            repository.decisions(base.id()).stream()
                .filter(
                    row ->
                        row.stage() == InitiativeStage.DATA_FEASIBILITY
                            && "APPROVE".equals(row.decision()))
                .map(row -> row.actor())
                .toList()));
    content.put("evidence", evidence);
    content.put("notIncluded", List.of("trained model", "weights", "evaluation", "expected lift"));
    content.put("clientId", ClientContext.require().toString());
    return HandoffPackage.create(mapper, content);
  }

  private String evidenceHash(KnowledgeEvidence evidence) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(evidence.excerpt().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to hash evidence", exception);
    }
  }

  private Map<String, Object> acceptedPayload(InitiativeRepository.Attempt attempt) {
    return attempt.drafts().stream()
        .filter(draft -> "ACCEPTED".equals(draft.outcome()) || "REUSE".equals(draft.outcome()))
        .findFirst()
        .map(GenerationDraft::payload)
        .orElse(Map.of());
  }

  Initiative completeHandoff(
      UUID initiativeId,
      InitiativeRepository.Base base,
      InitiativeRepository.Attempt attempt,
      GateDecisionRequest request,
      Instant started,
      long wait) {
    ArtifactReference packageArtifact =
        attempt.artifacts().stream()
            .filter(artifact -> artifact.type().equals("HANDOFF_PACKAGE"))
            .findFirst()
            .orElse(null);
    if (packageArtifact == null) {
      return finishHandoffBlocker(
          initiativeId, attempt, request, started, wait, List.of("HANDOFF_PACKAGE_NOT_FOUND"));
    }
    HandoffPackage handoff =
        repository
            .findPackage(packageArtifact.id())
            .orElseThrow(() -> new IllegalStateException("Approved handoff package was not found"));
    HandoffPackage current = buildPackage(base);
    if (!handoff.hash().equals(current.hash())) {
      return finishHandoffBlocker(
          initiativeId, attempt, request, started, wait, List.of("PACKAGE_CHANGED_SINCE_APPROVAL"));
    }
    ModelRequirement requirement = discovery.getRequirement(base.requirementId());
    String name = modelName(requirement);
    if (name.isBlank()) {
      return finishHandoffBlocker(
          initiativeId, attempt, request, started, wait, List.of("MISSING_MODEL_NAME"));
    }
    Map<String, Object> payload = new LinkedHashMap<>(handoff.content());
    payload.put("studioInitiativeId", base.id().toString());
    payload.put("packageHash", handoff.hash());
    Instant outboundStarted = Instant.now();
    AuroraCandidateClient.Registration registration =
        aurora == null
            ? new AuroraCandidateClient.Registration(
                false, null, null, null, "AURORA_NOT_CONFIGURED")
            : aurora.register(name, payload, handoff.hash());
    Instant outboundFinished = Instant.now();
    String outcome = registration.successful() ? "REGISTERED" : "PROVIDER_FAILED";
    repository.saveHandoffAttempt(
        base.id(),
        attempt.id(),
        handoff.hash(),
        aurora == null ? "/api/models/" + name + "/candidates" : aurora.endpoint(name),
        Map.of("packageHash", handoff.hash(), "fieldNames", payload.keySet()),
        registration.responseStatus(),
        registration.candidateId(),
        registration.candidateStatus(),
        outcome,
        registration.failureCode(),
        registration.successful() ? null : "Aurora candidate registration failed",
        outboundStarted,
        outboundFinished);
    List<ArtifactReference> artifacts =
        List.of(new ArtifactReference("HANDOFF_PACKAGE", packageArtifact.id(), false));
    StageStatus status =
        registration.successful() ? StageStatus.COMPLETED : StageStatus.PROVIDER_FAILED;
    repository.finish(
        attempt.id(),
        status,
        outboundFinished,
        attempt.machineDurationMillis() + elapsed(started, outboundFinished),
        wait,
        List.of(),
        List.of(),
        artifacts);
    repository.insertEvent(
        initiativeId,
        InitiativeStage.HANDOFF,
        StageStatus.AWAITING_APPROVAL,
        status,
        request.actor(),
        registration.successful()
            ? "Design package registered; Aurora awaits client-trained weights"
            : "Aurora candidate registration failed",
        artifacts);
    return initiatives.get(initiativeId);
  }

  private Initiative finishHandoffBlocker(
      UUID initiativeId,
      InitiativeRepository.Attempt attempt,
      GateDecisionRequest request,
      Instant started,
      long wait,
      List<String> blockers) {
    Instant finished = Instant.now();
    repository.finish(
        attempt.id(),
        StageStatus.BLOCKED,
        finished,
        attempt.machineDurationMillis() + elapsed(started, finished),
        wait,
        blockers,
        List.of(),
        attempt.artifacts());
    repository.insertEvent(
        initiativeId,
        InitiativeStage.HANDOFF,
        StageStatus.AWAITING_APPROVAL,
        StageStatus.BLOCKED,
        request.actor(),
        String.join(", ", blockers),
        attempt.artifacts());
    return initiatives.get(initiativeId);
  }

  private String modelName(ModelRequirement requirement) {
    Object value = requirement.constraints().get("modelName");
    return value == null ? "" : String.valueOf(value).trim();
  }
}
