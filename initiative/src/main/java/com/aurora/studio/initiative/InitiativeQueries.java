package com.aurora.studio.initiative;

import com.aurora.studio.common.ResourceNotFoundException;
import com.aurora.studio.discovery.DiscoveryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class InitiativeQueries {
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;

  InitiativeQueries(InitiativeRepository repository, DiscoveryService discovery) {
    this.repository = repository;
    this.discovery = discovery;
  }

  List<Initiative> list() {
    return repository.findAll().stream()
        .map(base -> assemble(base, repository.attempts(base.id())))
        .toList();
  }

  Initiative get(UUID id) {
    InitiativeRepository.Base base =
        repository
            .find(id)
            .orElseThrow(() -> new ResourceNotFoundException("Initiative was not found"));
    return assemble(base, repository.attempts(id));
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
        attempt.artifacts(),
        attempt.drafts(),
        attempt.draftsGenerated(),
        attempt.draftsRejected(),
        attempt.violatedChecks(),
        attempt.handoffAttempts());
  }

  private String stageNote(InitiativeStage stage) {
    if (stage == InitiativeStage.CANDIDATE_BUILD) {
      return "Training occurs in the client environment";
    }
    if (stage == InitiativeStage.EXPERIMENT_DESIGN) {
      return "Deterministic design; training, weights, and evaluation remain client-owned";
    }
    if (stage == InitiativeStage.HANDOFF) {
      return "Human-approved design package; Aurora awaits client-trained weights";
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
        row.acceptedUnknownChecks(),
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

  InitiativeRepository.Attempt latest(UUID initiativeId, InitiativeStage stage) {
    return repository
        .latestAttempt(initiativeId, stage)
        .orElseThrow(() -> new ResourceNotFoundException("Unknown initiative stage"));
  }
}
