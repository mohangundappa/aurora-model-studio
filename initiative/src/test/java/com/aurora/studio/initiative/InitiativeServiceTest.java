package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.common.RelationshipType;
import com.aurora.studio.common.ValidationException;
import com.aurora.studio.discovery.DiscoveryRun;
import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.gateway.LlmGateway;
import com.aurora.studio.gateway.LlmOutcome;
import com.aurora.studio.gateway.LlmResult;
import com.aurora.studio.knowledge.KnowledgeEvidence;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class InitiativeServiceTest {
  private final InitiativeRepository repository =
      org.mockito.Mockito.mock(InitiativeRepository.class);
  private final DiscoveryService discovery = org.mockito.Mockito.mock(DiscoveryService.class);
  private final KnowledgeService knowledge = org.mockito.Mockito.mock(KnowledgeService.class);
  private final LlmGateway gateway = org.mockito.Mockito.mock(LlmGateway.class);
  private final InitiativeService service =
      new InitiativeService(repository, discovery, knowledge, gateway);
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
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any(), any());
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
            "Evidence is insufficient",
            List.of());
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
                stage.stage() == InitiativeStage.EXPERIMENT_DESIGN
                    || stage.stage() == InitiativeStage.HANDOFF)
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.PENDING));
    assertThat(
            result.stages().stream()
                .filter(stage -> stage.stage() == InitiativeStage.CANDIDATE_BUILD)
                .findFirst()
                .orElseThrow()
                .status())
        .isEqualTo(StageStatus.OUT_OF_SCOPE);
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

  @Test
  void concurrentRerunSurfacesConflictWithoutStartingLoser() {
    InitiativeRepository.Base base = base();
    InitiativeRepository.Attempt prior =
        attempt(UUID.randomUUID(), InitiativeStage.KNOWLEDGE_DISCOVERY, StageStatus.BLOCKED, 1);
    InitiativeRepository.Attempt intake =
        attempt(UUID.randomUUID(), InitiativeStage.REQUIREMENT_INTAKE, StageStatus.COMPLETED, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY))
        .thenReturn(Optional.of(prior));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REQUIREMENT_INTAKE))
        .thenReturn(Optional.of(intake));
    when(repository.insertAttempt(
            initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY, 2, StageStatus.PENDING))
        .thenThrow(new DuplicateKeyException("duplicate stage attempt"));

    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.KNOWLEDGE_DISCOVERY))
        .isInstanceOf(StageAlreadyRunningException.class)
        .hasMessage("Stage is already running or awaiting approval");
    verify(repository, never()).start(any(), any());
  }

  @Test
  void hostileGateTextIsRejectedBeforeInsert() {
    InitiativeRepository.Attempt awaiting =
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.REUSE_DECISION,
            1,
            StageStatus.AWAITING_APPROVAL,
            Instant.now().minusSeconds(2),
            Instant.now().minusSeconds(1),
            7,
            0,
            List.of(),
            List.of(),
            List.of());
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REUSE_DECISION))
        .thenReturn(Optional.of(awaiting));
    assertThatThrownBy(
            () ->
                service.decide(
                    initiativeId,
                    InitiativeStage.REUSE_DECISION,
                    new GateDecisionRequest("APPROVE", "x".repeat(201), null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("actor");
    assertThatThrownBy(
            () ->
                service.decide(
                    initiativeId,
                    InitiativeStage.REUSE_DECISION,
                    new GateDecisionRequest("APPROVE", "reviewer\u0000", null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("actor");
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void feasibilityUsesAssetsResolvedFromRequirementArtifacts() {
    KnowledgeObject unrelated = dataAsset(UUID.randomUUID(), "unrelated", List.of("OTHER_EVENT"));
    KnowledgeObject required =
        dataAsset(
            UUID.randomUUID(),
            "required",
            List.of("OTHER_EVENT"),
            Map.of("history", "14d", "refreshCadence", "1d", "pointInTimeAvailable", true));
    KnowledgeObject observable = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "14d", "2d", "eligible sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(unrelated, required, observable));
    when(knowledge.get(observable.id(), false))
        .thenReturn(
            packageFor(
                observable,
                List.of(
                    new KnowledgeRelationship(
                        UUID.randomUUID(),
                        observable.clientId(),
                        observable.id(),
                        RelationshipType.DERIVED_FROM,
                        required.id(),
                        null))));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    org.mockito.ArgumentCaptor<List<FeasibilityCheck>> checks =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.COMPLETED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of()),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-"))
        .allSatisfy(
            check -> {
              assertThat(check.artifactId()).isEqualTo(required.id());
              assertThat(check.status()).isEqualTo("PASS");
            });
  }

  @Test
  void feasibilityTraversesFeatureImplementationToDataAsset() {
    KnowledgeObject feature = feature(UUID.randomUUID(), "booking-intent");
    KnowledgeObject implementation =
        new KnowledgeObject(
            UUID.randomUUID(),
            feature.clientId(),
            "implementation:booking-intent",
            1,
            KnowledgeType.IMPLEMENTATION,
            "BookingIntentCalculator",
            "domain",
            "use-case",
            "implementation",
            Map.of(),
            Map.of(),
            List.of(),
            "APPROVED",
            Instant.now(),
            null,
            1.0,
            Map.of(),
            Map.of(),
            null,
            "test",
            null,
            "test",
            null,
            Map.of(),
            false);
    KnowledgeObject asset =
        dataAsset(
            UUID.randomUUID(),
            "raw_events",
            List.of("BOOKING_COMPLETED"),
            Map.of("history", "30d", "refreshCadence", "1d", "pointInTimeAvailable", true));
    ModelRequirement requirement =
        requirement(
            List.of(),
            Map.of("requiredFeatures", List.of("booking-intent")),
            "14d",
            "2d",
            "sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(feature, implementation, asset));
    when(knowledge.get(feature.id(), false))
        .thenReturn(
            packageFor(
                feature,
                List.of(
                    new KnowledgeRelationship(
                        UUID.randomUUID(),
                        feature.clientId(),
                        feature.id(),
                        RelationshipType.IMPLEMENTED_BY,
                        implementation.id(),
                        null))));
    when(knowledge.get(implementation.id(), false))
        .thenReturn(
            packageFor(
                implementation,
                List.of(
                    new KnowledgeRelationship(
                        UUID.randomUUID(),
                        feature.clientId(),
                        implementation.id(),
                        RelationshipType.DERIVED_FROM,
                        asset.id(),
                        null))));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    org.mockito.ArgumentCaptor<List<FeasibilityCheck>> checks =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.COMPLETED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of()),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-"))
        .allSatisfy(check -> assertThat(check.artifactId()).isEqualTo(asset.id()));
  }

  @Test
  void feasibilityDoesNotTreatDeclarationsAsAdequacy() {
    KnowledgeObject asset =
        dataAsset(
            UUID.randomUUID(),
            "adequacy",
            List.of("BOOKING_COMPLETED"),
            Map.of(
                "history", "7d",
                "refreshCadence", "3d",
                "grain", "one session per row",
                "pointInTimeAvailable", true));
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "14d", "1d", "eligible sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(asset));
    when(knowledge.get(asset.id(), false)).thenReturn(packageFor(asset));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    org.mockito.ArgumentCaptor<List<FeasibilityCheck>> checks =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            any(),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-history"))
        .singleElement()
        .satisfies(check -> assertThat(check.status()).isEqualTo("FAIL"));
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-refresh-cadence"))
        .singleElement()
        .satisfies(check -> assertThat(check.status()).isEqualTo("FAIL"));
  }

  @Test
  void declaredFalseHistoryIsUnknown() {
    KnowledgeObject asset =
        dataAsset(
            UUID.randomUUID(),
            "unknown-history",
            List.of("BOOKING_COMPLETED"),
            Map.of("history", false));
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "14d", "batch", "sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(asset));
    when(knowledge.get(asset.id(), false)).thenReturn(packageFor(asset));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    org.mockito.ArgumentCaptor<List<FeasibilityCheck>> checks =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository)
        .awaitApproval(eq(attemptId), any(), anyLong(), eq(List.of()), checks.capture(), any());
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-history"))
        .singleElement()
        .satisfies(check -> assertThat(check.status()).isEqualTo("UNKNOWN"));
  }

  @Test
  void unknownFeasibilityAwaitsApprovalAndBlocksSuccessorUntilAccepted() {
    KnowledgeObject asset =
        dataAsset(
            UUID.randomUUID(),
            "unknown",
            List.of("BOOKING_COMPLETED"),
            Map.of("history", false, "grain", "one immutable customer event per row"));
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "14d", "batch", "sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    InitiativeRepository.Attempt targeting =
        attempt(UUID.randomUUID(), InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(asset));
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(Optional.of(targeting));
    when(knowledge.get(asset.id(), false)).thenReturn(packageFor(asset));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    org.mockito.ArgumentCaptor<List<FeasibilityCheck>> checks =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(repository)
        .awaitApproval(eq(attemptId), any(), anyLong(), eq(List.of()), checks.capture(), any());
    assertThat(checks.getValue())
        .filteredOn(check -> check.name().startsWith("data-"))
        .allSatisfy(check -> assertThat(check.status()).isEqualTo("UNKNOWN"));
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any(), any());

    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before");
  }

  @Test
  void approvingUnknownFeasibilityRecordsAcceptedChecksAndCompletes() {
    List<FeasibilityCheck> unknown =
        List.of(
            new FeasibilityCheck("data-history:events", "UNKNOWN", UUID.randomUUID(), "not known"));
    InitiativeRepository.Attempt awaiting =
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.DATA_FEASIBILITY,
            1,
            StageStatus.AWAITING_APPROVAL,
            Instant.now().minusSeconds(2),
            Instant.now().minusSeconds(1),
            7,
            0,
            List.of(),
            unknown,
            List.of());
    java.util.concurrent.atomic.AtomicReference<InitiativeRepository.Attempt> current =
        new java.util.concurrent.atomic.AtomicReference<>(awaiting);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenAnswer(ignored -> Optional.of(current.get()));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(awaiting));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.decide(
        initiativeId,
        InitiativeStage.DATA_FEASIBILITY,
        new GateDecisionRequest(
            "APPROVE",
            "reviewer",
            "Accepted residual uncertainty",
            List.of("data-history:events")));

    verify(repository)
        .insertGateDecision(
            initiativeId,
            attemptId,
            InitiativeStage.DATA_FEASIBILITY,
            "APPROVE",
            "reviewer",
            "Accepted residual uncertainty",
            List.of("data-history:events"));
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.COMPLETED),
            any(),
            eq(7L),
            anyLong(),
            eq(List.of()),
            eq(unknown),
            eq(List.of()));

    current.set(
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.DATA_FEASIBILITY,
            1,
            StageStatus.REJECTED,
            awaiting.startedAt(),
            Instant.now(),
            7,
            2,
            List.of(),
            unknown,
            List.of()));
    InitiativeRepository.Attempt targeting =
        attempt(UUID.randomUUID(), InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(Optional.of(targeting));
    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before");
  }

  @Test
  void rejectingUnknownFeasibilityLeavesAttemptRejected() {
    List<FeasibilityCheck> unknown =
        List.of(
            new FeasibilityCheck("data-history:events", "UNKNOWN", UUID.randomUUID(), "not known"));
    InitiativeRepository.Attempt awaiting =
        new InitiativeRepository.Attempt(
            attemptId,
            InitiativeStage.DATA_FEASIBILITY,
            1,
            StageStatus.AWAITING_APPROVAL,
            Instant.now().minusSeconds(2),
            Instant.now().minusSeconds(1),
            7,
            0,
            List.of(),
            unknown,
            List.of());
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(Optional.of(awaiting));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(awaiting));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.decide(
        initiativeId,
        InitiativeStage.DATA_FEASIBILITY,
        new GateDecisionRequest("REJECT", "reviewer", "Cannot accept uncertainty"));

    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.REJECTED),
            any(),
            eq(7L),
            anyLong(),
            eq(List.of()),
            eq(unknown),
            eq(List.of()));
  }

  @Test
  void absentRequiredFeatureFailsWithNamedBlocker() {
    KnowledgeObject asset = dataAsset(UUID.randomUUID(), "events", List.of("BOOKING_COMPLETED"));
    ModelRequirement requirement =
        requirement(
            List.of("BOOKING_COMPLETED"),
            Map.of("requiredFeatures", List.of("missing-feature")),
            "14d",
            "batch",
            "sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(asset));
    when(knowledge.get(asset.id(), false)).thenReturn(packageFor(asset));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of("MISSING_REQUIRED_FEATURE:missing-feature")),
            any(),
            any());
  }

  @Test
  void requiredGovernedKnowledgeConflictBlocksRegardlessOfType() {
    KnowledgeObject implementation =
        knowledgeObject(
            UUID.randomUUID(),
            KnowledgeType.IMPLEMENTATION,
            "implementation:legacy/implementations/loyalty-tenure.java",
            "loyalty-tenure-implementation");
    KnowledgeObject specification =
        knowledgeObject(
            UUID.randomUUID(),
            KnowledgeType.STANDARD,
            "standard:loyalty-tenure-spec",
            "loyalty-tenure-spec");
    KnowledgeRelationship governed =
        new KnowledgeRelationship(
            UUID.randomUUID(),
            implementation.clientId(),
            implementation.id(),
            RelationshipType.GOVERNED_BY,
            specification.id(),
            null);
    com.aurora.studio.knowledge.KnowledgeConflict conflict =
        new com.aurora.studio.knowledge.KnowledgeConflict(
            UUID.randomUUID(),
            implementation.clientId(),
            implementation.id(),
            "measurementUnit",
            "BLOCKING",
            Map.of("current", "months", "other", "years"),
            com.aurora.studio.common.KnowledgeConflictStatus.OPEN,
            null);
    ModelRequirement requirement =
        requirement(
            List.of(),
            Map.of(
                "requiredKnowledgeKeys",
                List.of("implementation:legacy/implementations/loyalty-tenure.java")),
            "14d",
            "batch",
            "sessions");
    InitiativeRepository.Attempt feasibility =
        attempt(attemptId, InitiativeStage.DATA_FEASIBILITY, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt reuse =
        attempt(UUID.randomUUID(), InitiativeStage.REUSE_DECISION, StageStatus.COMPLETED, 1);
    prepareFeasibility(requirement, feasibility, reuse, List.of(implementation, specification));
    when(knowledge.get(implementation.id(), false))
        .thenReturn(packageFor(implementation, List.of(governed), List.of()));
    when(knowledge.get(specification.id(), false))
        .thenReturn(packageFor(specification, List.of(governed), List.of(conflict)));

    service.runStage(initiativeId, InitiativeStage.DATA_FEASIBILITY);

    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of("OPEN_CONFLICT:implementation:legacy/implementations/loyalty-tenure.java")),
            any(),
            any());
  }

  @Test
  void nearDuplicateFeatureDraftIsReportedForReuseWithoutCreatingCandidate() {
    KnowledgeObject asset = governedDataAsset(UUID.randomUUID(), "raw_events");
    KnowledgeObject approvedFeature = feature(UUID.randomUUID(), "booking-intent");
    ModelRequirement requirement = requirement(List.of(), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt featureAttempt =
        attempt(attemptId, InitiativeStage.FEATURE_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt targeting =
        attempt(UUID.randomUUID(), InitiativeStage.TARGETING_DESIGN, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, featureAttempt, targeting, List.of(asset));
    when(knowledge.search("FEATURE", null, null, "APPROVED", null, null, false))
        .thenReturn(List.of(approvedFeature));
    when(discovery.reuseScore(any(), eq(approvedFeature))).thenReturn(0.97);
    Map<String, Object> nearDuplicate =
        Map.of(
            "name",
            "booking-intent",
            "businessDefinition",
            "An explainable baseline combines funnel depth and recency.",
            "entity",
            "customer",
            "observationWindow",
            "30d ending strictly before as-of",
            "pointInTimeAvailable",
            true,
            "sourceColumns",
            List.of("session_id", "event_time"));
    when(gateway.complete(any())).thenReturn(llmResult(Map.of("drafts", List.of(nearDuplicate))));

    service.runStage(initiativeId, InitiativeStage.FEATURE_DESIGN);

    ArgumentCaptor<List<GenerationDraft>> drafts = ArgumentCaptor.forClass(List.class);
    verify(repository).saveDrafts(eq(attemptId), drafts.capture(), any());
    assertThat(drafts.getValue())
        .singleElement()
        .satisfies(
            draft -> {
              assertThat(draft.outcome()).isEqualTo("REUSE");
              assertThat(draft.validatorVerdicts())
                  .anySatisfy(
                      verdict ->
                          assertThat(verdict)
                              .isEqualTo(
                                  new ValidatorVerdict(
                                      "reuse-before-creation",
                                      "REUSE",
                                      "near-duplicate of approved feature:booking-intent")));
            });
    verify(knowledge, never()).createExtracted(any(), any(), any());
    verify(knowledge, never()).addEvidence(any(), any(), any(), any(), any(), any(), anyDouble());
  }

  @Test
  void rejectedTargetingDraftIsPersistedWithVerdictsAndNoWinnerOnlyRetry() {
    KnowledgeObject asset = governedDataAsset(UUID.randomUUID(), "raw_events");
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, targeting, feasibility, List.of(asset));
    Map<String, Object> rejected =
        Map.of(
            "cohortSql",
            "SELECT session_id, event_time FROM raw_events "
                + "WHERE event_name = 'BOOKING_COMPLETED' AND event_time <= :as_of",
            "labelSql",
            "",
            "asOfSemantics",
            "Available at the event_time as-of point.");
    when(gateway.complete(any())).thenReturn(llmResult(Map.of("drafts", List.of(rejected))));

    service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN);

    ArgumentCaptor<List<GenerationDraft>> drafts = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> violated = ArgumentCaptor.forClass(List.class);
    verify(repository).saveDrafts(eq(attemptId), drafts.capture(), violated.capture());
    assertThat(drafts.getValue()).hasSize(1);
    assertThat(drafts.getValue().getFirst().outcome()).isEqualTo("REJECTED");
    assertThat(drafts.getValue().getFirst().validatorVerdicts())
        .anySatisfy(verdict -> assertThat(verdict.name()).isEqualTo("target-leakage"));
    assertThat(violated.getValue())
        .containsExactly(
            "target-leakage:cohort query references target observable BOOKING_COMPLETED");
    verify(gateway).complete(any());
    verify(repository)
        .finish(
            eq(attemptId), eq(StageStatus.BLOCKED), any(), anyLong(), eq(0L), any(), any(), any());
  }

  @Test
  void allRejectedTargetingDraftsBlockStageAndReportViolations() {
    KnowledgeObject asset = governedDataAsset(UUID.randomUUID(), "raw_events");
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, targeting, feasibility, List.of(asset));
    Map<String, Object> rejected =
        Map.of(
            "cohortSql",
            "SELECT session_id, event_time FROM raw_events "
                + "WHERE event_name = 'BOOKING_COMPLETED' AND event_time <= :as_of",
            "labelSql",
            "",
            "asOfSemantics",
            "Available at the event_time as-of point.");
    UUID invocationId = UUID.randomUUID();
    when(gateway.complete(any()))
        .thenReturn(
            new LlmResult(
                invocationId,
                LlmOutcome.OK,
                Map.of("drafts", List.of(rejected)),
                null,
                1,
                1,
                0.0,
                1,
                0));

    service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN);

    ArgumentCaptor<List<GenerationDraft>> drafts = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> violated = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<FeasibilityCheck>> checks = ArgumentCaptor.forClass(List.class);
    verify(gateway).complete(any());
    verify(repository).saveDrafts(eq(attemptId), drafts.capture(), violated.capture());
    assertThat(drafts.getValue())
        .singleElement()
        .satisfies(
            draft -> {
              assertThat(draft.outcome()).isEqualTo("REJECTED");
              assertThat(draft.validatorVerdicts())
                  .anySatisfy(verdict -> assertThat(verdict.name()).isEqualTo("target-leakage"));
            });
    assertThat(violated.getValue())
        .containsExactly(
            "target-leakage:cohort query references target observable BOOKING_COMPLETED");
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of("TARGETING_DESIGN_VALIDATION_FAILED")),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .anySatisfy(
            check ->
                assertThat(check)
                    .extracting(FeasibilityCheck::name, FeasibilityCheck::status)
                    .containsExactly("target-leakage", "FAIL"));
  }

  @Test
  void designRequestContainsGovernedMetadataAndTargetContext() {
    KnowledgeObject asset =
        dataAsset(
            UUID.randomUUID(),
            "custom_events",
            List.of(),
            Map.of(
                "columns",
                List.of(
                    Map.of("name", "subject_key", "type", "VARCHAR(200)", "nullable", false),
                    Map.of("name", "occurred_at", "type", "TIMESTAMPTZ", "nullable", false)),
                "primaryKey",
                "subject_key",
                "eventTime",
                "occurred_at"));
    ModelRequirement requirement =
        requirement(List.of("PURCHASED"), Map.of(), "14d", "batch", "customers");
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, targeting, feasibility, List.of(asset));
    when(gateway.complete(any())).thenReturn(llmResult(Map.of("drafts", List.of())));

    service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN);

    ArgumentCaptor<com.aurora.studio.gateway.LlmRequest> request =
        ArgumentCaptor.forClass(com.aurora.studio.gateway.LlmRequest.class);
    verify(gateway).complete(request.capture());
    assertThat(request.getValue().resolvedPromptInputs())
        .containsEntry("targetObservable", "definition")
        .containsEntry(
            "governedDataAssets",
            List.of(
                Map.of(
                    "table",
                    "custom_events",
                    "columns",
                    List.of(
                        Map.of("name", "subject_key", "type", "VARCHAR(200)", "nullable", false),
                        Map.of("name", "occurred_at", "type", "TIMESTAMPTZ", "nullable", false)),
                    "entityColumn",
                    "subject_key",
                    "asOfColumn",
                    "occurred_at")));
    assertThat(request.getValue().renderedPrompt())
        .contains("custom_events", "subject_key", "occurred_at", "definition", "14d");
  }

  @Test
  void providerFailureIsContainedAndInvocationIsRecorded() {
    KnowledgeObject asset = governedDataAsset(UUID.randomUUID(), "raw_events");
    ModelRequirement requirement =
        requirement(List.of("BOOKING_COMPLETED"), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, targeting, feasibility, List.of(asset));
    UUID invocationId = UUID.randomUUID();
    when(gateway.complete(any()))
        .thenReturn(
            new LlmResult(
                invocationId,
                LlmOutcome.FAILED,
                Map.of(),
                "provider secret error",
                1,
                0,
                0.0,
                1,
                2));

    service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN);

    verify(repository)
        .saveDrafts(
            eq(attemptId),
            eq(List.of()),
            eq(List.of("provider-failure:Targeting design provider failed")));
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.PROVIDER_FAILED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of()),
            eq(List.of()),
            eq(List.of(new ArtifactReference("LLM_INVOCATION", invocationId, false))));
    verify(repository)
        .insertEvent(
            eq(initiativeId),
            eq(InitiativeStage.TARGETING_DESIGN),
            eq(StageStatus.IN_PROGRESS),
            eq(StageStatus.PROVIDER_FAILED),
            eq("initiative-orchestrator"),
            eq("Targeting design provider failed"),
            eq(List.of(new ArtifactReference("LLM_INVOCATION", invocationId, false))));
    verify(gateway).complete(any());
  }

  @Test
  void targetingUnknownVerdictAwaitsHumanGateWithoutMachineDecision() {
    KnowledgeObject asset = dataAsset(UUID.randomUUID(), "missing_columns", List.of());
    ModelRequirement requirement = requirement(List.of(), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, targeting, feasibility, List.of(asset));
    Map<String, Object> draft =
        Map.of(
            "cohortSql",
            "SELECT session_id, event_time FROM missing_columns WHERE event_time <= :as_of",
            "labelSql",
            "",
            "asOfSemantics",
            "Available strictly before as-of.");
    when(gateway.complete(any())).thenReturn(llmResult(Map.of("drafts", List.of(draft))));

    service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN);

    ArgumentCaptor<List<FeasibilityCheck>> checks = ArgumentCaptor.forClass(List.class);
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.AWAITING_APPROVAL),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of()),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .anySatisfy(
            check ->
                assertThat(check)
                    .extracting(FeasibilityCheck::name, FeasibilityCheck::status)
                    .containsExactly("governed-references", "UNKNOWN"));
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void featureUnknownVerdictAwaitsHumanGateWithoutMachineDecision() {
    KnowledgeObject asset = governedDataAsset(UUID.randomUUID(), "raw_events");
    ModelRequirement requirement = requirement(List.of(), Map.of(), "30d", "batch", "sessions");
    InitiativeRepository.Attempt featureAttempt =
        attempt(attemptId, InitiativeStage.FEATURE_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt targeting =
        attempt(UUID.randomUUID(), InitiativeStage.TARGETING_DESIGN, StageStatus.COMPLETED, 1);
    prepareDesign(requirement, featureAttempt, targeting, List.of(asset));
    when(knowledge.search("FEATURE", null, null, "APPROVED", null, null, false))
        .thenReturn(List.of());
    Map<String, Object> draft =
        Map.of(
            "name",
            "unverified-feature",
            "businessDefinition",
            "Counts governed events.",
            "entity",
            "session",
            "observationWindow",
            "30d ending strictly before as-of",
            "sourceColumns",
            List.of("session_id", "event_time"));
    when(gateway.complete(any())).thenReturn(llmResult(Map.of("drafts", List.of(draft))));
    KnowledgeObject candidate = feature(UUID.randomUUID(), "unverified-feature");
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            UUID.randomUUID(),
            candidate.clientId(),
            candidate.id(),
            "model-studio",
            "generation-record",
            "initiative://feature-design",
            UUID.randomUUID().toString(),
            "generated draft",
            1.0,
            Instant.now());
    when(knowledge.createExtracted(any(), any(), any())).thenReturn(candidate);
    when(knowledge.addEvidence(any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(evidence);

    service.runStage(initiativeId, InitiativeStage.FEATURE_DESIGN);

    ArgumentCaptor<List<FeasibilityCheck>> checks = ArgumentCaptor.forClass(List.class);
    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.AWAITING_APPROVAL),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of()),
            checks.capture(),
            any());
    assertThat(checks.getValue())
        .anySatisfy(
            check ->
                assertThat(check)
                    .extracting(FeasibilityCheck::name, FeasibilityCheck::status)
                    .containsExactly("point-in-time-availability", "UNKNOWN"));
    verify(repository, never()).insertGateDecision(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void experimentDesignNamesUnknownSampleInputsAndAwaitsApproval() {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement requirement =
        new ModelRequirement(
            "domain",
            "use-case",
            "BOOKING_COMPLETED",
            "BOOKING_COMPLETED",
            "sessions",
            "30d",
            "batch",
            "action",
            Map.of(),
            Map.of(),
            Map.of(),
            List.of("BOOKING_COMPLETED"),
            false);
    InitiativeRepository.Attempt experiment =
        attempt(attemptId, InitiativeStage.EXPERIMENT_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feature =
        attempt(UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.COMPLETED, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.EXPERIMENT_DESIGN))
        .thenReturn(Optional.of(experiment));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(feature));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search(null, null, null, null, null, null, true)).thenReturn(List.of(outcome));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(experiment, feature));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.EXPERIMENT_DESIGN);

    ArgumentCaptor<List<FeasibilityCheck>> checks = ArgumentCaptor.forClass(List.class);
    verify(repository)
        .awaitApproval(
            eq(attemptId), any(), anyLong(), eq(List.of()), checks.capture(), eq(List.of()));
    assertThat(checks.getValue())
        .filteredOn(check -> check.status().equals("UNKNOWN"))
        .extracting(FeasibilityCheck::name)
        .containsExactly(
            "sample-size-baselineConversionRate",
            "sample-size-minimumDetectableEffect",
            "sample-size-alpha",
            "sample-size-power",
            "minimum-exposures");
  }

  @Test
  void experimentDesignComputesKnownPerVariantSampleSizeDeterministically() {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement requirement =
        new ModelRequirement(
            "domain",
            "use-case",
            "BOOKING_COMPLETED",
            "BOOKING_COMPLETED",
            "sessions",
            "30d",
            "batch",
            "action",
            Map.of(
                "baselineConversionRate", 0.10,
                "minimumDetectableEffect", 0.02,
                "alpha", 0.05,
                "power", 0.80),
            Map.of(),
            Map.of(),
            List.of("BOOKING_COMPLETED"),
            false);
    InitiativeRepository.Attempt experiment =
        attempt(attemptId, InitiativeStage.EXPERIMENT_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feature =
        attempt(UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.COMPLETED, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.EXPERIMENT_DESIGN))
        .thenReturn(Optional.of(experiment));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(feature));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search(null, null, null, null, null, null, true)).thenReturn(List.of(outcome));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(experiment, feature));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.EXPERIMENT_DESIGN);

    ArgumentCaptor<List<GenerationDraft>> drafts = ArgumentCaptor.forClass(List.class);
    verify(repository).saveDrafts(eq(attemptId), drafts.capture(), any());
    Map<String, Object> design = drafts.getValue().getFirst().payload();
    assertThat(design.get("allocationSource")).isEqualTo("DEFAULT");
    assertThat(((Map<?, ?>) design.get("sampleSize")).get("minimumExposuresPerVariant"))
        .isEqualTo(3841);
    assertThat((String) design.get("decisionRule"))
        .contains("3841 exposures per variant")
        .contains("alpha 0.0500")
        .contains("power 0.8000");
  }

  @Test
  void invalidExperimentVariantsBlockBeforeHandoff() {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    Map<String, Object> invalidTreatment =
        Map.of(
            "name", "x".repeat(121), "role", "TREATMENT", "allocation", 50, "minimumExposures", 0);
    ModelRequirement requirement =
        new ModelRequirement(
            "domain",
            "use-case",
            "BOOKING_COMPLETED",
            "BOOKING_COMPLETED",
            "sessions",
            "30d",
            "batch",
            "action",
            Map.of(
                "experimentVariants",
                List.of(Map.of("name", "", "role", "CONTROL", "allocation", 50), invalidTreatment)),
            Map.of(),
            Map.of(),
            List.of("BOOKING_COMPLETED"),
            false);
    InitiativeRepository.Attempt experiment =
        attempt(attemptId, InitiativeStage.EXPERIMENT_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feature =
        attempt(UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.COMPLETED, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.EXPERIMENT_DESIGN))
        .thenReturn(Optional.of(experiment));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(feature));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search(null, null, null, null, null, null, true)).thenReturn(List.of(outcome));
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(experiment, feature));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.EXPERIMENT_DESIGN);

    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            org.mockito.ArgumentMatchers.argThat(
                blockers ->
                    blockers.contains("INVALID_VARIANT_NAMES")
                        && blockers.contains("INVALID_MINIMUM_EXPOSURES")),
            any(),
            eq(List.of()));
  }

  @Test
  void extractedGeneratedFeatureBlocksHandoffWithoutOutboundAttempt() {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    KnowledgeObject candidate = feature(UUID.randomUUID(), "generated-feature");
    candidate = withLifecycle(candidate, "EXTRACTED");
    ModelRequirement requirement =
        requirement(
            List.of("BOOKING_COMPLETED"),
            Map.of("requiredFeatures", List.of("generated-feature", "booking-intent")),
            "30d",
            "batch",
            "sessions");
    InitiativeRepository.Attempt handoff =
        attempt(attemptId, InitiativeStage.HANDOFF, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt experiment =
        attempt(UUID.randomUUID(), InitiativeStage.EXPERIMENT_DESIGN, StageStatus.COMPLETED, 1);
    InitiativeRepository.Attempt targeting =
        attempt(UUID.randomUUID(), InitiativeStage.TARGETING_DESIGN, StageStatus.COMPLETED, 1);
    InitiativeRepository.Attempt featureAttempt =
        new InitiativeRepository.Attempt(
            UUID.randomUUID(),
            InitiativeStage.FEATURE_DESIGN,
            1,
            StageStatus.COMPLETED,
            null,
            null,
            0,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new GenerationDraft(
                    "FEATURE", Map.of("name", "generated-feature"), "ACCEPTED", null, List.of())),
            1,
            0,
            List.of());
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.COMPLETED, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.HANDOFF))
        .thenReturn(Optional.of(handoff));
    when(repository.latestAttempt(initiativeId, InitiativeStage.EXPERIMENT_DESIGN))
        .thenReturn(Optional.of(experiment));
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(Optional.of(targeting));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(featureAttempt));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(Optional.of(feasibility));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    KnowledgeObject approved = feature(UUID.randomUUID(), "booking-intent");
    when(knowledge.search("FEATURE", null, null, null, null, null, true))
        .thenReturn(List.of(candidate, approved));
    when(knowledge.search(null, null, null, null, null, null, true))
        .thenReturn(List.of(outcome, candidate, approved));
    when(knowledge.get(candidate.id(), true)).thenReturn(packageFor(candidate));
    when(knowledge.get(approved.id(), true)).thenReturn(packageFor(approved));
    when(knowledge.get(outcome.id(), true)).thenReturn(packageFor(outcome));
    when(repository.attempts(initiativeId))
        .thenReturn(allAttempts(handoff, experiment), allAttempts(handoff, experiment));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());

    service.runStage(initiativeId, InitiativeStage.HANDOFF);

    verify(repository)
        .finish(
            eq(attemptId),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            org.mockito.ArgumentMatchers.argThat(
                blockers ->
                    blockers.contains("FEATURE_NOT_APPROVED:feature:generated-feature")
                        && !blockers.contains("FEATURE_NOT_APPROVED:feature:booking-intent")),
            eq(List.of()),
            eq(List.of()));
  }

  @Test
  void handoffRegistersThePackageBoundToTheAwaitingAttempt() throws Exception {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement requirement =
        requirement(
            List.of("BOOKING_COMPLETED"),
            Map.of("modelName", "booking-intent"),
            "30d",
            "batch",
            "sessions");
    InitiativeRepository.Attempt experiment =
        experimentAttempt(
            UUID.randomUUID(),
            new GenerationDraft(
                "EXPERIMENT",
                Map.of("primaryOutcomeEvent", "BOOKING_COMPLETED"),
                "ACCEPTED",
                null,
                List.of()));
    InitiativeRepository.Attempt handoff = awaitingHandoff(UUID.randomUUID(), experiment);
    InitiativeRepository.Base base = base();
    AuroraCandidateClient client = org.mockito.Mockito.mock(AuroraCandidateClient.class);
    InitiativeService serviceWithClient =
        new InitiativeService(
            repository, discovery, knowledge, gateway, client, new ObjectMapper());
    prepareHandoffPackage(base, requirement, outcome, experiment, handoff);
    HandoffPackage approved = invokeBuildPackage(serviceWithClient, base);
    when(repository.findPackage(handoff.artifacts().getFirst().id()))
        .thenReturn(Optional.of(approved));
    when(client.register(any(), any(), any()))
        .thenReturn(
            new AuroraCandidateClient.Registration(
                true, 201, "candidate-1", "AWAITING_WEIGHTS", null));

    serviceWithClient.decide(
        initiativeId,
        InitiativeStage.HANDOFF,
        new GateDecisionRequest("APPROVE", "reviewer", "Approved design package"));

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(client).register(eq("booking-intent"), payload.capture(), eq(approved.hash()));
    assertThat(payload.getValue()).containsEntry("packageHash", approved.hash());
    assertThat(payload.getValue()).doesNotContainKey("requirementId");
    assertThat(payload.getValue().get("targeting")).isEqualTo(approved.content().get("targeting"));
    verify(repository, never()).savePackage(any(), any(), any());
  }

  @Test
  void handoffRefusesWhenThePackageChangesAfterApproval() throws Exception {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement approvedRequirement =
        requirement(
            List.of("BOOKING_COMPLETED"),
            Map.of("modelName", "booking-intent"),
            "30d",
            "batch",
            "sessions");
    ModelRequirement changedRequirement =
        requirement(
            List.of("BOOKING_COMPLETED"),
            Map.of("modelName", "changed-model"),
            "30d",
            "batch",
            "sessions");
    InitiativeRepository.Attempt experiment =
        experimentAttempt(
            UUID.randomUUID(),
            new GenerationDraft(
                "EXPERIMENT",
                Map.of("primaryOutcomeEvent", "BOOKING_COMPLETED"),
                "ACCEPTED",
                null,
                List.of()));
    InitiativeRepository.Attempt handoff = awaitingHandoff(UUID.randomUUID(), experiment);
    InitiativeRepository.Base base = base();
    AuroraCandidateClient client = org.mockito.Mockito.mock(AuroraCandidateClient.class);
    InitiativeService serviceWithClient =
        new InitiativeService(
            repository, discovery, knowledge, gateway, client, new ObjectMapper());
    prepareHandoffPackage(base, approvedRequirement, outcome, experiment, handoff);
    when(discovery.getRequirement(any())).thenReturn(approvedRequirement, changedRequirement);
    HandoffPackage approved = invokeBuildPackage(serviceWithClient, base);
    when(repository.findPackage(handoff.artifacts().getFirst().id()))
        .thenReturn(Optional.of(approved));

    serviceWithClient.decide(
        initiativeId,
        InitiativeStage.HANDOFF,
        new GateDecisionRequest("APPROVE", "reviewer", "Approved design package"));

    verify(client, never()).register(any(), any(), any());
    verify(repository)
        .finish(
            eq(handoff.id()),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            anyLong(),
            eq(List.of("PACKAGE_CHANGED_SINCE_APPROVAL")),
            eq(List.of()),
            eq(handoff.artifacts()));
  }

  @Test
  void handoffRequiresModelNameDeclaredByRequirement() {
    KnowledgeObject outcome = observable(UUID.randomUUID(), "BOOKING_COMPLETED");
    ModelRequirement requirement =
        new ModelRequirement(
            "domain",
            "use-case",
            "BOOKING_COMPLETED",
            "BOOKING_COMPLETED",
            "sessions",
            "30d",
            "batch",
            "action",
            Map.of(),
            Map.of(),
            Map.of(),
            List.of("BOOKING_COMPLETED"),
            false);
    InitiativeRepository.Attempt experiment =
        experimentAttempt(
            UUID.randomUUID(),
            new GenerationDraft(
                "EXPERIMENT",
                Map.of("primaryOutcomeEvent", "BOOKING_COMPLETED"),
                "ACCEPTED",
                null,
                List.of()));
    InitiativeRepository.Attempt handoff =
        attempt(attemptId, InitiativeStage.HANDOFF, StageStatus.PENDING, 1);
    InitiativeRepository.Base base = base();
    prepareHandoffPackage(base, requirement, outcome, experiment, handoff);

    service.runStage(initiativeId, InitiativeStage.HANDOFF);

    verify(repository)
        .finish(
            eq(handoff.id()),
            eq(StageStatus.BLOCKED),
            any(),
            anyLong(),
            eq(0L),
            eq(List.of("MISSING_MODEL_NAME")),
            eq(List.of()),
            eq(handoff.artifacts()));
  }

  @Test
  void blockedTargetingPreventsFeatureDesign() {
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.BLOCKED, 1);
    InitiativeRepository.Attempt feature =
        attempt(UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.PENDING, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(feature));
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(Optional.of(targeting));

    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before TARGETING_DESIGN");
    verifyNoGatewayCall();
  }

  @Test
  void blockedFeasibilityPreventsBothNewDesignStages() {
    InitiativeRepository.Attempt feasibility =
        attempt(UUID.randomUUID(), InitiativeStage.DATA_FEASIBILITY, StageStatus.BLOCKED, 1);
    InitiativeRepository.Attempt targeting =
        attempt(attemptId, InitiativeStage.TARGETING_DESIGN, StageStatus.PENDING, 1);
    InitiativeRepository.Attempt feature =
        attempt(UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.PENDING, 1);
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(Optional.of(feasibility));
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(Optional.of(targeting));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(Optional.of(feature));

    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before DATA_FEASIBILITY");
    assertThatThrownBy(() -> service.runStage(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start before TARGETING_DESIGN");
    verifyNoGatewayCall();
  }

  private void prepareDesign(
      ModelRequirement requirement,
      InitiativeRepository.Attempt design,
      InitiativeRepository.Attempt predecessor,
      List<KnowledgeObject> assets) {
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, design.stage())).thenReturn(Optional.of(design));
    when(repository.latestAttempt(initiativeId, predecessor.stage()))
        .thenReturn(Optional.of(predecessor));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search("DATA_ASSET", null, null, null, null, null, false)).thenReturn(assets);
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(design, predecessor));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());
  }

  private void verifyNoGatewayCall() {
    verify(gateway, never()).complete(any());
  }

  private LlmResult llmResult(Map<String, Object> payload) {
    return new LlmResult(UUID.randomUUID(), LlmOutcome.OK, payload, null, 10, 10, 0.0, 1, 0);
  }

  private KnowledgeObject governedDataAsset(UUID id, String name) {
    return dataAsset(
        id,
        name,
        List.of(),
        Map.of(
            "columns",
            List.of(
                Map.of("name", "session_id", "type", "VARCHAR(200)", "nullable", false),
                Map.of("name", "event_time", "type", "TIMESTAMPTZ", "nullable", false),
                Map.of("name", "event_name", "type", "VARCHAR(120)", "nullable", false))));
  }

  private void prepareFeasibility(
      ModelRequirement requirement,
      InitiativeRepository.Attempt feasibility,
      InitiativeRepository.Attempt reuse,
      List<KnowledgeObject> visible) {
    when(repository.find(initiativeId)).thenReturn(Optional.of(base()));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(Optional.of(feasibility));
    when(repository.latestAttempt(initiativeId, InitiativeStage.REUSE_DECISION))
        .thenReturn(Optional.of(reuse));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search(null, null, null, null, null, null, false)).thenReturn(visible);
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(feasibility, reuse));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.events(initiativeId)).thenReturn(List.of());
  }

  private ModelRequirement requirement(
      List<String> observables,
      Map<String, Object> constraints,
      String horizon,
      String latency,
      String population) {
    return new ModelRequirement(
        "domain",
        "use-case",
        "target",
        "definition",
        population,
        horizon,
        latency,
        "action",
        constraints,
        Map.of(),
        Map.of(),
        observables,
        false);
  }

  private KnowledgeObject dataAsset(UUID id, String name, List<String> observables) {
    return dataAsset(id, name, observables, Map.of("observables", observables));
  }

  private KnowledgeObject feature(UUID id, String name) {
    return knowledgeObject(id, KnowledgeType.FEATURE, "feature:" + name, name);
  }

  private KnowledgeObject withLifecycle(KnowledgeObject object, String lifecycle) {
    return new KnowledgeObject(
        object.id(),
        object.clientId(),
        object.knowledgeKey(),
        object.version(),
        object.knowledgeType(),
        object.name(),
        object.businessDomain(),
        object.businessUseCase(),
        object.businessDescription(),
        object.canonicalTaxonomy(),
        object.clientTaxonomy(),
        object.tags(),
        lifecycle,
        object.effectiveFrom(),
        object.effectiveTo(),
        object.confidence(),
        object.confidenceBreakdown(),
        object.qualityAssessment(),
        object.llmInvocationId(),
        object.extractedBy(),
        object.reviewedBy(),
        object.approvedBy(),
        object.approvalComments(),
        object.attributes(),
        object.synthetic());
  }

  private KnowledgeObject knowledgeObject(UUID id, KnowledgeType type, String key, String name) {
    return new KnowledgeObject(
        id,
        UUID.randomUUID(),
        key,
        1,
        type,
        name,
        "domain",
        "use-case",
        "feature",
        Map.of(),
        Map.of(),
        List.of(),
        "APPROVED",
        Instant.now(),
        null,
        1.0,
        Map.of(),
        Map.of(),
        null,
        "test",
        null,
        "test",
        null,
        Map.of("businessDefinition", "feature"),
        false);
  }

  private KnowledgeObject dataAsset(
      UUID id, String name, List<String> observables, Map<String, Object> overrides) {
    Map<String, Object> attributes = new java.util.LinkedHashMap<>();
    attributes.put("observables", observables);
    attributes.put("grain", "one session per row");
    attributes.put("primaryKey", "session_id");
    attributes.put("eventTime", "event_time");
    attributes.putAll(overrides);
    return new KnowledgeObject(
        id,
        UUID.randomUUID(),
        "data-asset:" + name,
        1,
        KnowledgeType.DATA_ASSET,
        name,
        "domain",
        "use-case",
        "asset",
        Map.of(),
        Map.of(),
        List.of(),
        "APPROVED",
        Instant.now(),
        null,
        1.0,
        Map.of(),
        Map.of(),
        null,
        "test",
        null,
        "test",
        null,
        attributes,
        false);
  }

  private KnowledgeObject observable(UUID id, String name) {
    return new KnowledgeObject(
        id,
        UUID.randomUUID(),
        "event:" + name,
        1,
        KnowledgeType.STANDARD,
        name,
        "domain",
        "use-case",
        "event",
        Map.of(),
        Map.of(),
        List.of(),
        "APPROVED",
        Instant.now(),
        null,
        1.0,
        Map.of(),
        Map.of(),
        null,
        "test",
        null,
        "test",
        null,
        Map.of(),
        false);
  }

  private KnowledgePackage packageFor(KnowledgeObject object) {
    return packageFor(
        object,
        List.of(
            new KnowledgeRelationship(
                UUID.randomUUID(),
                object.clientId(),
                object.id(),
                RelationshipType.USES,
                object.id(),
                null)));
  }

  private KnowledgePackage packageFor(
      KnowledgeObject object, List<KnowledgeRelationship> relationships) {
    return packageFor(object, relationships, List.of());
  }

  private KnowledgePackage packageFor(
      KnowledgeObject object,
      List<KnowledgeRelationship> relationships,
      List<com.aurora.studio.knowledge.KnowledgeConflict> conflicts) {
    return new KnowledgePackage(
        object.id(),
        1,
        object.knowledgeType().name(),
        object.name(),
        object.businessDescription(),
        object.attributes(),
        List.of(),
        List.of(),
        List.of(),
        relationships,
        null,
        List.of(),
        1.0,
        Map.of(),
        null,
        "APPROVED",
        true,
        false,
        List.of(),
        conflicts,
        null);
  }

  private InitiativeRepository.Base base() {
    return new InitiativeRepository.Base(
        initiativeId, UUID.randomUUID(), false, null, Instant.now());
  }

  private void prepareHandoffPackage(
      InitiativeRepository.Base base,
      ModelRequirement requirement,
      KnowledgeObject outcome,
      InitiativeRepository.Attempt experiment,
      InitiativeRepository.Attempt handoff) {
    when(repository.find(initiativeId)).thenReturn(Optional.of(base));
    when(repository.latestAttempt(initiativeId, InitiativeStage.HANDOFF))
        .thenReturn(Optional.of(handoff));
    when(repository.latestAttempt(initiativeId, InitiativeStage.TARGETING_DESIGN))
        .thenReturn(
            Optional.of(
                attempt(
                    UUID.randomUUID(),
                    InitiativeStage.TARGETING_DESIGN,
                    StageStatus.COMPLETED,
                    1)));
    when(repository.latestAttempt(initiativeId, InitiativeStage.FEATURE_DESIGN))
        .thenReturn(
            Optional.of(
                attempt(
                    UUID.randomUUID(), InitiativeStage.FEATURE_DESIGN, StageStatus.COMPLETED, 1)));
    when(repository.latestAttempt(initiativeId, InitiativeStage.EXPERIMENT_DESIGN))
        .thenReturn(Optional.of(experiment));
    when(repository.latestAttempt(initiativeId, InitiativeStage.DATA_FEASIBILITY))
        .thenReturn(
            Optional.of(
                attempt(
                    UUID.randomUUID(),
                    InitiativeStage.DATA_FEASIBILITY,
                    StageStatus.COMPLETED,
                    1)));
    when(discovery.getRequirement(any())).thenReturn(requirement);
    when(knowledge.search("FEATURE", null, null, null, null, null, true)).thenReturn(List.of());
    when(knowledge.search(null, null, null, null, null, null, true)).thenReturn(List.of(outcome));
    when(knowledge.get(outcome.id(), true)).thenReturn(packageFor(outcome));
    when(repository.decisions(initiativeId)).thenReturn(List.of());
    when(repository.attempts(initiativeId)).thenReturn(allAttempts(handoff, experiment));
    when(repository.events(initiativeId)).thenReturn(List.of());
  }

  private HandoffPackage invokeBuildPackage(
      InitiativeService service, InitiativeRepository.Base base) throws Exception {
    java.lang.reflect.Method method =
        InitiativeService.class.getDeclaredMethod("buildPackage", InitiativeRepository.Base.class);
    method.setAccessible(true);
    return (HandoffPackage) method.invoke(service, base);
  }

  private InitiativeRepository.Attempt experimentAttempt(UUID id, GenerationDraft draft) {
    return new InitiativeRepository.Attempt(
        id,
        InitiativeStage.EXPERIMENT_DESIGN,
        1,
        StageStatus.COMPLETED,
        null,
        null,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(draft),
        1,
        0,
        List.of());
  }

  private InitiativeRepository.Attempt awaitingHandoff(
      UUID packageId, InitiativeRepository.Attempt experiment) {
    return new InitiativeRepository.Attempt(
        attemptId,
        InitiativeStage.HANDOFF,
        1,
        StageStatus.AWAITING_APPROVAL,
        Instant.now().minusSeconds(2),
        Instant.now().minusSeconds(1),
        5,
        0,
        List.of(),
        List.of(),
        List.of(new ArtifactReference("HANDOFF_PACKAGE", packageId, false)),
        List.of(),
        0,
        0,
        List.of());
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
    if (stage == InitiativeStage.EXPERIMENT_DESIGN || stage == InitiativeStage.HANDOFF) {
      return StageStatus.PENDING;
    }
    if (stage == InitiativeStage.CANDIDATE_BUILD) return StageStatus.OUT_OF_SCOPE;
    return StageStatus.PENDING;
  }

  private InitiativeRepository.Attempt attempt(
      UUID id, InitiativeStage stage, StageStatus status, int number) {
    return new InitiativeRepository.Attempt(
        id, stage, number, status, null, null, 0, 0, List.of(), List.of(), List.of());
  }
}
