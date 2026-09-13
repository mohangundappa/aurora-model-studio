package com.aurora.studio.initiative;

import static com.aurora.studio.initiative.StageTiming.elapsed;

import com.aurora.studio.common.ResourceNotFoundException;
import com.aurora.studio.common.ValidationException;
import com.aurora.studio.discovery.DiscoveryCandidate;
import com.aurora.studio.discovery.DiscoveryRun;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.gateway.LlmGateway;
import com.aurora.studio.knowledge.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitiativeService {
  private static final String AGENT = InitiativeActors.ORCHESTRATOR;
  private static final Set<InitiativeStage> GATED_STAGES =
      EnumSet.of(
          InitiativeStage.REUSE_DECISION,
          InitiativeStage.DATA_FEASIBILITY,
          InitiativeStage.TARGETING_DESIGN,
          InitiativeStage.FEATURE_DESIGN,
          InitiativeStage.EXPERIMENT_DESIGN,
          InitiativeStage.HANDOFF);
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;
  private final KnowledgeService knowledge;
  private final InitiativeQueries initiatives;
  private final FeasibilityStage feasibility;
  private final ExperimentDesignStage experiments;
  private final GeneratedDesignStage designs;
  private final HandoffStage handoff;

  public InitiativeService(
      InitiativeRepository repository, DiscoveryService discovery, KnowledgeService knowledge) {
    this(repository, discovery, knowledge, null, null, new ObjectMapper());
  }

  public InitiativeService(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      LlmGateway gateway) {
    this(repository, discovery, knowledge, gateway, null, new ObjectMapper());
  }

  public InitiativeService(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      LlmGateway gateway,
      AuroraCandidateClient aurora,
      ObjectMapper mapper) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
    this.initiatives = new InitiativeQueries(repository, discovery);
    InitiativeKnowledge lineage = new InitiativeKnowledge(knowledge);
    this.feasibility = new FeasibilityStage(repository, discovery, knowledge, initiatives, lineage);
    this.experiments =
        new ExperimentDesignStage(repository, discovery, knowledge, initiatives, lineage);
    this.designs = new GeneratedDesignStage(repository, discovery, knowledge, initiatives, gateway);
    this.handoff =
        new HandoffStage(repository, discovery, knowledge, initiatives, lineage, aurora, mapper);
  }

  @Autowired
  InitiativeService(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      InitiativeQueries initiatives,
      FeasibilityStage feasibility,
      ExperimentDesignStage experiments,
      GeneratedDesignStage designs,
      HandoffStage handoff) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
    this.initiatives = initiatives;
    this.feasibility = feasibility;
    this.experiments = experiments;
    this.designs = designs;
    this.handoff = handoff;
  }

  @Transactional
  public Initiative create(CreateInitiativeRequest request) {
    if (request == null || request.requirementId() == null) {
      throw new ValidationException("requirementId is required");
    }
    discovery.getRequirement(request.requirementId());
    if (request.clientBaselineDurationMillis() != null
        && request.clientBaselineDurationMillis() < 0) {
      throw new ValidationException("clientBaselineDurationMillis must not be negative");
    }
    UUID id =
        repository.create(
            request.requirementId(),
            request.includeCandidates(),
            request.clientBaselineDurationMillis());
    return get(id);
  }

  public List<Initiative> list() {
    return initiatives.list();
  }

  public Initiative get(UUID id) {
    return initiatives.get(id);
  }

  private HandoffPackage buildPackage(InitiativeRepository.Base base) {
    return handoff.buildPackage(base);
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
            Map.of("requiredFeatures", List.of("booking-intent"), "modelName", "booking-intent"),
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
            Map.of("modelName", "booking-cancellation-prevention"),
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
    InitiativeRepository.Attempt feasibilityAttempt =
        repository.latestAttempt(initiative.id(), InitiativeStage.DATA_FEASIBILITY).orElseThrow();
    List<String> unknown =
        feasibilityAttempt.feasibilityChecks().stream()
            .filter(check -> check.status().equals("UNKNOWN"))
            .map(FeasibilityCheck::name)
            .toList();
    if (feasibilityAttempt.status() == StageStatus.AWAITING_APPROVAL && !unknown.isEmpty()) {
      decide(
          initiative.id(),
          InitiativeStage.DATA_FEASIBILITY,
          new GateDecisionRequest(
              "APPROVE",
              "seed-human-reviewer",
              "Seeded demo acceptance of residual uncertainty",
              unknown));
      runStage(initiative.id(), InitiativeStage.TARGETING_DESIGN);
      runStage(initiative.id(), InitiativeStage.FEATURE_DESIGN);
      runStage(initiative.id(), InitiativeStage.DATA_FEASIBILITY);
      runStage(initiative.id(), InitiativeStage.EXPERIMENT_DESIGN);
    }
    return get(initiative.id());
  }

  @Transactional
  public Initiative runStage(UUID initiativeId, InitiativeStage stage) {
    InitiativeRepository.Base base = require(initiativeId);
    if (repository.latestAttempt(initiativeId, stage).isEmpty()) {
      throw new ResourceNotFoundException("Unknown initiative stage");
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
      InitiativeStage predecessor = predecessor(stage);
      StageStatus predecessorStatus = latest(initiativeId, predecessor).status();
      if (predecessorStatus != StageStatus.COMPLETED) {
        throw new IllegalStateException(
            "Stage " + stage + " cannot start before " + predecessor + " is completed");
      }
    }
    if (current.status() != StageStatus.PENDING) {
      int attempt = current.attempt() + 1;
      UUID attemptId;
      try {
        attemptId = repository.insertAttempt(initiativeId, stage, attempt, StageStatus.PENDING);
      } catch (DuplicateKeyException exception) {
        throw new StageAlreadyRunningException();
      }
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
        case DATA_FEASIBILITY -> feasibility.finishFeasibility(base, current, started);
        case TARGETING_DESIGN -> designs.finishTargeting(base, current, started);
        case FEATURE_DESIGN -> designs.finishFeature(base, current, started);
        case EXPERIMENT_DESIGN -> experiments.finishExperiment(base, current, started);
        case HANDOFF -> handoff.awaitHandoff(base, current, started);
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

  private InitiativeStage predecessor(InitiativeStage stage) {
    return switch (stage) {
      case EXPERIMENT_DESIGN -> InitiativeStage.FEATURE_DESIGN;
      case HANDOFF -> InitiativeStage.EXPERIMENT_DESIGN;
      default -> InitiativeStage.values()[stage.ordinal() - 1];
    };
  }

  @Transactional
  public Initiative decide(UUID initiativeId, InitiativeStage stage, GateDecisionRequest request) {
    if (!GATED_STAGES.contains(stage)) {
      throw new ValidationException("Stage does not have a human gate");
    }
    if (request == null || request.actor() == null || request.actor().isBlank()) {
      throw new ValidationException("actor is required");
    }
    validateGateText("actor", request.actor());
    if (request.reason() != null) validateGateText("reason", request.reason());
    if (InitiativeActors.isGovernedMachineIdentity(request.actor())) {
      throw new ValidationException(
          "known machine identities cannot approve human-gated stages they created");
    }
    String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
    if (!Set.of("APPROVE", "REJECT", "RETURN").contains(decision)) {
      throw new ValidationException("decision must be APPROVE, REJECT, or RETURN");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw new ValidationException("reason is required for every gate decision");
    }
    require(initiativeId);
    InitiativeRepository.Attempt current = latest(initiativeId, stage);
    if (current.status() != StageStatus.AWAITING_APPROVAL) {
      throw new IllegalStateException("Stage is not awaiting human approval");
    }
    List<String> unknownChecks =
        current.feasibilityChecks().stream()
            .filter(check -> check.status().equals("UNKNOWN"))
            .map(FeasibilityCheck::name)
            .distinct()
            .toList();
    if (request.acceptedUnknownChecks() != null) {
      request
          .acceptedUnknownChecks()
          .forEach(check -> validateGateText("acceptedUnknownChecks", check));
    }
    List<String> acceptedUnknownChecks =
        request.acceptedUnknownChecks() == null
            ? List.of()
            : request.acceptedUnknownChecks().stream().distinct().sorted().toList();
    if ((stage == InitiativeStage.DATA_FEASIBILITY
            || stage == InitiativeStage.TARGETING_DESIGN
            || stage == InitiativeStage.FEATURE_DESIGN
            || stage == InitiativeStage.EXPERIMENT_DESIGN)
        && !unknownChecks.isEmpty()) {
      List<String> expectedUnknownChecks = unknownChecks.stream().sorted().toList();
      if (decision.equals("APPROVE") && !acceptedUnknownChecks.equals(expectedUnknownChecks)) {
        throw new ValidationException(
            "acceptedUnknownChecks must name every UNKNOWN feasibility check: "
                + String.join(", ", expectedUnknownChecks));
      }
      if (!decision.equals("APPROVE") && !acceptedUnknownChecks.isEmpty()) {
        throw new ValidationException(
            "acceptedUnknownChecks is only valid when approving UNKNOWN feasibility checks");
      }
    } else if (!acceptedUnknownChecks.isEmpty()) {
      throw new ValidationException("acceptedUnknownChecks is only valid for UNKNOWN gated checks");
    }
    Instant now = Instant.now();
    long wait = current.completedAt() == null ? 0 : elapsed(current.completedAt(), now);
    repository.insertGateDecision(
        initiativeId,
        current.id(),
        stage,
        decision,
        request.actor(),
        request.reason(),
        acceptedUnknownChecks);
    if (stage == InitiativeStage.HANDOFF && decision.equals("APPROVE")) {
      return handoff.completeHandoff(
          initiativeId, require(initiativeId), current, request, now, wait);
    }
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

  private List<ArtifactReference> discoveryArtifacts(DiscoveryRun run) {
    List<ArtifactReference> artifacts = new ArrayList<>();
    artifacts.add(new ArtifactReference("DISCOVERY_RUN", run.id(), false));
    for (DiscoveryCandidate candidate : run.candidates()) {
      artifacts.add(
          new ArtifactReference("KNOWLEDGE_OBJECT", candidate.id(), candidate.synthetic()));
    }
    return artifacts;
  }

  private InitiativeRepository.Base require(UUID id) {
    return repository
        .find(id)
        .orElseThrow(() -> new ResourceNotFoundException("Initiative was not found"));
  }

  private InitiativeRepository.Attempt latest(UUID initiativeId, InitiativeStage stage) {
    return repository
        .latestAttempt(initiativeId, stage)
        .orElseThrow(() -> new ResourceNotFoundException("Unknown initiative stage"));
  }

  private void validateGateText(String field, String value) {
    if (value == null) {
      throw new ValidationException(field + " must not be null");
    }
    if (value.codePointCount(0, value.length()) > 200) {
      throw new ValidationException(field + " must be at most 200 characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new ValidationException(field + " must not contain control characters");
    }
  }
}
