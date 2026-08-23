package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.NOT_IMPLEMENTED));
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
    attributes.put("primaryKey", "id");
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
      return StageStatus.NOT_IMPLEMENTED;
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
