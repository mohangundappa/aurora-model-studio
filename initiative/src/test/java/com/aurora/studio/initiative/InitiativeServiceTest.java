package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aurora.studio.discovery.DiscoveryRun;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InitiativeServiceTest {
  private final InitiativeRepository repository =
      org.mockito.Mockito.mock(InitiativeRepository.class);
  private final DiscoveryService discovery = org.mockito.Mockito.mock(DiscoveryService.class);
  private final KnowledgeService knowledge = org.mockito.Mockito.mock(KnowledgeService.class);
  private final InitiativeService service = new InitiativeService(repository, discovery, knowledge);
  private final UUID initiativeId = UUID.randomUUID();
  private final UUID attemptId = UUID.randomUUID();

  @Test
  void orchestrationAwaitsHumanGateWithoutWritingDecision() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt discoveryAttempt =
        attempt(UUID.randomUUID(), InitiativeStage.KNOWLEDGE_DISCOVERY, StageStatus.COMPLETED, 1);
    InitiativeRepository.Attempt reuseAttempt =
        attempt(attemptId, InitiativeStage.REUSE_DECISION, StageStatus.PENDING, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REUSE_DECISION))
        .thenReturn(Optional.of(reuseAttempt));
    when(repository.latestAttempt(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY))
        .thenReturn(Optional.of(discoveryAttempt));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(discoveryAttempt, reuseAttempt));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.REUSE_DECISION);

    verify(repository).awaitApproval(eq(attemptId), any(), eq(0L), eq(List.of()), eq(List.of()));
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectedGateRecordsReasonAndRejectsAttempt() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt awaiting =
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.REUSE_DECISION,
            1,
            StageStatus.AWAITING_APPROVAL,
            Instant.now().minusSeconds(2),
            Instant.now().minusSeconds(1),
            5,
            0,
            List.of(),
            List.of(),
            List.of());
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REUSE_DECISION))
        .thenReturn(Optional.of(awaiting));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(awaiting));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.decide(
        initiativeId,
        InitiativeStage.REUSE_DECISION,
        new GateDecisionRequest("reject", "reviewer", "Evidence is insufficient"));

    verify(repository)
        .insertGateDecision(
            initiativeId,
            attemptId,
            InitiativeStage.REUSE_DECISION,
            "REJECT",
            "reviewer",
            "Evidence is insufficient");
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.REJECTED),
            any(),
            eq(5L),
            anyLong(),
            eq(List.of()),
            eq(List.of()),
            eq(List.of()));
  }

  @Test
  void responseKeepsMachineAndHumanWaitSeparateAndOmitsUnfoundedComparison() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt attempt =
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.REQUIREMENT_INTAKE,
            1,
            StageStatus.COMPLETED,
            Instant.now().minusSeconds(2),
            Instant.now().minusSeconds(1),
            17,
            29,
            List.of(),
            List.of(),
            List.of());
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(attempt));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    Initiative result = service.get(initiativeId);

    assertThat(result.durations().machineDurationMillis()).isEqualTo(17);
    assertThat(result.durations().humanWaitDurationMillis()).isEqualTo(29);
    assertThat(result.durations().deliveryTimeReductionMillis()).isNull();
    assertThat(result.durations().comparisonClientDeclared()).isFalse();
    assertThat(result.stages()).hasSize(9);
    assertThat(result.stages())
        .filteredOn(
            stage ->
                stage.stage() == InitiativeStage.TARGETING_DESIGN
                    || stage.stage() == InitiativeStage.FEATURE_DESIGN
                    || stage.stage() == InitiativeStage.EXPERIMENT_DESIGN
                    || stage.stage() == InitiativeStage.HANDOFF)
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.NOT_IMPLEMENTED));
    assertThat(
            result.stages().stream()
                .filter(stage -> stage.stage() == InitiativeStage.CANDIDATE_BUILD)
                .findFirst()
                .orElseThrow()
                .note())
        .contains("PERMANENTLY_OUT_OF_SCOPE");
  }

  @Test
  void stageCannotStartBeforePredecessorCompletes() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.PENDING, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(Optional.of(feasibility));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REUSE_DECISION))
        .thenReturn(Optional.of(reuse));

    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before");
    verify(repository, never()).start(any(), any());
  }

  @Test
  void rerunCreatesNewAttemptAfterPriorAttempt() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt prior =
        attempt(UUID.randomUUID(), InitiativeStage.KNOWLEDGE_DISCOVERY, StageStatus.BLOCKED, 1);
    InitiativeRepository.Attempt intake =
        attempt(UUID.randomUUID(), InitiativeStage.REQUIREMENT_INTAKE, StageStatus.COMPLETED, 1);
    UUID rerunId = UUID.randomUUID();
    InitiativeRepository.Attempt rerun =
        attempt(rerunId, InitiativeStage.KNOWLEDGE_DISCOVERY, StageStatus.PENDING, 2);
    DiscoveryRun discoveryRun =
        new DiscoveryRun(
            UUID.randomUUID(),
            base.requirementId(),
            false,
            "deterministic-v1",
            "REUSE",
            List.of(),
            List.of(),
            List.of());
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY))
        .thenReturn(Optional.of(prior));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REQUIREMENT_INTAKE))
        .thenReturn(Optional.of(intake));
    when(repository.insertAttempt(
            initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY, 2, StageStatus.PENDING))
        .thenReturn(rerunId);
    when(discovery.run(base.requirementId(), false)).thenReturn(discoveryRun);
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(rerun, intake));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY);

    verify(repository)
        .insertAttempt(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY, 2, StageStatus.PENDING);
  }

  private InitiativeRepository.Base base() {
    return new InitiativeRepository.Base(
        initiativeId, UUID.randomUUID(), false, null, Instant.now());
  }

  private List<InitiativeRepository.Attempt> allAttempts(InitiativeRepository.Attempt replacement) {
    return java.util.Arrays.stream(InitiativeStage.values())
        .map(
            stage ->
                stage == replacement.stage()
                    ? replacement
                    : attempt(UUID.randomUUID(), stage, defaultStatus(stage), 1))
        .toList();
  }

  private List<InitiativeRepository.Attempt> allAttempts(
      InitiativeRepository.Attempt first, InitiativeRepository.Attempt second) {
    return java.util.Arrays.stream(InitiativeStage.values())
        .map(
            stage ->
                stage == first.stage()
                    ? first
                    : stage == second.stage()
                        ? second
                        : attempt(UUID.randomUUID(), stage, defaultStatus(stage), 1))
        .toList();
  }

  private StageStatus defaultStatus(InitiativeStage stage) {
    if (stage == InitiativeStage.REQUIREMENT_INTAKE) return StageStatus.COMPLETED;
    if (stage == InitiativeStage.CANDIDATE_BUILD
        || stage == InitiativeStage.TARGETING_DESIGN
        || stage == InitiativeStage.FEATURE_DESIGN
        || stage == InitiativeStage.EXPERIMENT_DESIGN
        || stage == InitiativeStage.HANDOFF) {
      return StageStatus.NOT_IMPLEMENTED;
    }
    return StageStatus.PENDING;
  }

  private InitiativeRepository.Attempt attempt(
      UUID id, InitiativeStage stage, StageStatus status, int number) {
    return new InitiativeRepository.Attempt(
        id, stage, number, status, null, null, 0, 0, List.of(), List.of(), List.of());
  }
}
