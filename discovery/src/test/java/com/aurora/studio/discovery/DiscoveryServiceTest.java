package com.aurora.studio.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aurora.studio.common.KnowledgeConflictStatus;
import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.common.RelationshipType;
import com.aurora.studio.gateway.LlmGateway;
import com.aurora.studio.gateway.LlmOutcome;
import com.aurora.studio.gateway.LlmResult;
import com.aurora.studio.knowledge.KnowledgeConflict;
import com.aurora.studio.knowledge.KnowledgeEvidence;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeRepository;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryServiceTest {
  @Test
  void deterministicEmbeddingIsByteIdenticalForIdenticalInput() {
    DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider();
    assertThat(provider.embed("same requirement").vector())
        .containsExactly(provider.embed("same requirement").vector());
  }

  @Test
  void explanationRejectsNumberAbsentFromScorecard() {
    assertThat(DiscoveryService.validNumbers("score 0.75 and 9", Map.of("targetAlignment", 0.75)))
        .isFalse();
    assertThat(DiscoveryService.validNumbers("score 0.75", Map.of("targetAlignment", 0.75)))
        .isTrue();
  }

  @Test
  void explanationRejectsObjectNotSuppliedToProvider() {
    UUID supplied = UUID.randomUUID();
    UUID absent = UUID.randomUUID();
    assertThat(DiscoveryService.validReferences("see " + supplied, Set.of(supplied.toString())))
        .isTrue();
    assertThat(DiscoveryService.validReferences("see " + absent, Set.of(supplied.toString())))
        .isFalse();
  }

  @Test
  void missingObservableBlocksRunButNotStructurallyStrongCandidate() {
    UUID modelId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    UUID implementationId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, false, modelAttributes());
    KnowledgeObject feature = object(featureId, KnowledgeType.FEATURE, false, Map.of());
    ModelRequirement requirement =
        requirement(
            List.of("UNAVAILABLE_EVENT"),
            false,
            Map.of("requiredFeatures", List.of("booking-intent")));
    DiscoveryService service =
        service(
            requirement,
            List.of(model, feature),
            modelPackage(model, featureId, implementationId),
            packageFor(feature));

    DiscoveryRun run = service.run(UUID.randomUUID(), false);

    assertThat(run.classification()).isEqualTo("NOT_RECOMMENDED");
    assertThat(run.blockers()).containsExactly("MISSING_TARGET_OBSERVABLE:UNAVAILABLE_EVENT");
    assertThat(run.candidates()).hasSize(2);
    assertThat(
            run.candidates().stream()
                .filter(item -> item.id().equals(modelId))
                .findFirst()
                .orElseThrow()
                .classification())
        .isEqualTo("REUSE");
  }

  @Test
  void exploratoryModeRanksSyntheticCandidateWithoutSyntheticBlocker() {
    UUID modelId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, true, modelAttributes());
    ModelRequirement requirement = requirement(List.of(), true, Map.of());
    DiscoveryService service =
        service(requirement, List.of(model), modelPackage(model, null, null));

    DiscoveryRun run = service.run(UUID.randomUUID(), true);

    DiscoveryCandidate candidate = run.candidates().getFirst();
    assertThat(run.classification()).isEqualTo("ADAPT");
    assertThat(candidate.synthetic()).isTrue();
    assertThat(candidate.blockers()).doesNotContain("SYNTHETIC_EVIDENCE_ONLY");
    assertThat(candidate.evidence()).allMatch(DiscoveryEvidence::synthetic);
  }

  @Test
  void missingRecallDoesNotSilentlyFallBackToFullCorpus() {
    UUID modelId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, false, modelAttributes());
    KnowledgeService knowledge = mock(KnowledgeService.class);
    KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    DiscoveryRepository repository = mock(DiscoveryRepository.class);
    EmbeddingProvider embeddings = text -> new Embedding(new float[32], "test-v1");
    LlmGateway gateway = mock(LlmGateway.class);
    DiscoveryWeights weights = new DiscoveryWeights();
    ModelRequirement requirement = requirement(List.of(), false, Map.of());
    when(repository.findRequirement(any())).thenReturn(java.util.Optional.of(requirement));
    when(knowledge.search(null, null, null, null, null, null, false)).thenReturn(List.of(model));
    when(knowledgeRepository.discoveryRecall(any(), any(), eq("test-v1"), eq(false), eq(20)))
        .thenReturn(List.of());
    when(repository.saveRun(any(), any(), eq(false), any(), eq("test-v1"), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    DiscoveryService service =
        new DiscoveryService(
            knowledge, knowledgeRepository, repository, embeddings, gateway, weights);

    DiscoveryRun run = service.run(UUID.randomUUID(), false);

    assertThat(run.classification()).isEqualTo("GENERATE");
    assertThat(run.candidates()).isEmpty();
    assertThat(run.reasonCodes()).contains("NO_RECALL_CANDIDATE");
    verifyNoInteractions(gateway);
    verify(knowledgeRepository).discoveryRecall(any(), any(), eq("test-v1"), eq(false), eq(20));
  }

  @Test
  void providerOutagePreservesVerdictAndScorecardWithoutProse() {
    UUID modelId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, false, modelAttributes());
    KnowledgeObject feature = object(featureId, KnowledgeType.FEATURE, false, Map.of());
    ModelRequirement requirement = requirement(List.of(), false, Map.of());
    DiscoveryService service =
        service(
            requirement,
            List.of(model, feature),
            modelPackage(model, featureId, UUID.randomUUID()),
            packageFor(feature));

    DiscoveryRun run = service.run(UUID.randomUUID(), false);

    DiscoveryCandidate candidate = run.candidates().getFirst();
    assertThat(candidate.classification()).isEqualTo("REUSE");
    assertThat(candidate.scorecard()).isNotEmpty();
    assertThat(candidate.explanation()).isNull();
  }

  @Test
  void discoveryRunDoesNotWriteObjectEmbeddings() {
    UUID modelId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, false, modelAttributes());
    ModelRequirement requirement = requirement(List.of(), false, Map.of());
    DiscoveryService service =
        service(requirement, List.of(model), modelPackage(model, null, null));

    service.run(UUID.randomUUID(), false);

    verify(serviceKnowledgeRepository, never()).updateEmbedding(any(), any(), any());
  }

  @Test
  void unknownDimensionsRemainNullWhileCompositeIsRenormalized() {
    UUID modelId = UUID.randomUUID();
    KnowledgeObject model =
        object(
            modelId,
            KnowledgeType.MODEL,
            false,
            Map.of(
                "objective", "predict booking intent",
                "targetEvent", "BOOKING_COMPLETED"));
    DiscoveryService service =
        service(
            requirement(List.of(), false, Map.of()),
            List.of(model),
            modelPackage(model, null, null));

    DiscoveryCandidate candidate = service.run(UUID.randomUUID(), false).candidates().getFirst();

    assertThat(candidate.scorecard()).containsEntry("populationAlignment", null);
    assertThat(candidate.scorecard()).containsEntry("horizonAlignment", null);
    assertThat(candidate.scorecard()).containsEntry("executionEvidence", null);
    assertThat(candidate.compositeScore()).isNotNull();
  }

  @Test
  void blockerPrecedenceBeatsHighCompositeScore() {
    UUID modelId = UUID.randomUUID();
    KnowledgeObject model = object(modelId, KnowledgeType.MODEL, false, modelAttributes());
    KnowledgeConflictStatus status = KnowledgeConflictStatus.OPEN;
    KnowledgeConflict conflict =
        new KnowledgeConflict(
            UUID.randomUUID(),
            model.clientId(),
            model.id(),
            "objective",
            Map.of(),
            status,
            Instant.now());
    KnowledgePackage base = modelPackage(model, null, UUID.randomUUID());
    KnowledgePackage conflicted =
        new KnowledgePackage(
            base.id(),
            base.version(),
            base.type(),
            base.name(),
            base.businessDefinition(),
            base.attributes(),
            base.implementations(),
            base.evidence(),
            List.of(),
            base.relationships(),
            null,
            List.of(),
            model.confidence(),
            model.confidenceBreakdown(),
            null,
            "APPROVED",
            true,
            false,
            List.of(),
            List.of(conflict),
            null);
    DiscoveryService service =
        service(requirement(List.of(), false, Map.of()), List.of(model), conflicted);

    DiscoveryCandidate candidate = service.run(UUID.randomUUID(), false).candidates().getFirst();

    assertThat(candidate.compositeScore()).isGreaterThan(0.8);
    assertThat(candidate.classification()).isEqualTo("NOT_RECOMMENDED");
    assertThat(candidate.blockers()).containsExactly("OPEN_CONFLICT");
  }

  @Test
  void nearDuplicateModelsBothSurfaceAndConflictIsReported() {
    KnowledgeObject first =
        object(UUID.randomUUID(), KnowledgeType.MODEL, false, modelAttributes());
    KnowledgeObject second =
        object(UUID.randomUUID(), KnowledgeType.MODEL, false, modelAttributes());
    KnowledgePackage firstPackage = modelPackage(first, null, UUID.randomUUID());
    KnowledgeConflict conflict =
        new KnowledgeConflict(
            UUID.randomUUID(),
            second.clientId(),
            second.id(),
            "objective",
            Map.of(),
            KnowledgeConflictStatus.OPEN,
            Instant.now());
    KnowledgePackage secondPackage =
        packageWithConflicts(modelPackage(second, null, UUID.randomUUID()), List.of(conflict));
    DiscoveryService service =
        service(
            requirement(List.of(), false, Map.of()),
            List.of(first, second),
            firstPackage,
            secondPackage);

    DiscoveryRun run = service.run(UUID.randomUUID(), false);

    assertThat(run.candidates())
        .extracting(DiscoveryCandidate::id)
        .containsExactlyInAnyOrder(first.id(), second.id());
    assertThat(
            run.candidates().stream()
                .filter(item -> item.id().equals(second.id()))
                .findFirst()
                .orElseThrow()
                .blockers())
        .containsExactly("OPEN_CONFLICT");
  }

  private KnowledgeRepository serviceKnowledgeRepository;

  private DiscoveryService service(
      ModelRequirement requirement, List<KnowledgeObject> visible, KnowledgePackage... packages) {
    KnowledgeService knowledge = mock(KnowledgeService.class);
    KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    serviceKnowledgeRepository = knowledgeRepository;
    DiscoveryRepository repository = mock(DiscoveryRepository.class);
    EmbeddingProvider embeddings = text -> new Embedding(new float[32], "test-v1");
    LlmGateway gateway = mock(LlmGateway.class);
    DiscoveryWeights weights = new DiscoveryWeights();
    UUID runId = UUID.randomUUID();
    when(repository.findRequirement(any())).thenReturn(java.util.Optional.of(requirement));
    when(knowledge.search(null, null, null, null, null, null, false)).thenReturn(visible);
    when(knowledge.search(null, null, null, null, null, null, true)).thenReturn(visible);
    when(knowledge.search("DATA_ASSET", null, null, null, null, null, false))
        .thenReturn(List.of(dataAsset(visible.getFirst().synthetic())));
    when(knowledge.search("DATA_ASSET", null, null, null, null, null, true))
        .thenReturn(List.of(dataAsset(visible.getFirst().synthetic())));
    when(knowledgeRepository.discoveryRecall(
            any(), any(), eq("test-v1"), any(Boolean.class), any(Integer.class)))
        .thenReturn(visible);
    when(repository.saveRun(any(), any(), any(Boolean.class), any(), eq("test-v1"), any()))
        .thenReturn(runId);
    for (KnowledgePackage pack : packages) {
      if (pack != null) when(knowledge.get(eq(pack.id()), any(Boolean.class))).thenReturn(pack);
    }
    when(gateway.complete(any()))
        .thenReturn(
            new LlmResult(
                UUID.randomUUID(), LlmOutcome.FAILED, Map.of(), "offline", 0, 0, 0, 1, 0));
    return new DiscoveryService(
        knowledge, knowledgeRepository, repository, embeddings, gateway, weights);
  }

  private ModelRequirement requirement(
      List<String> observables, boolean syntheticAllowed, Map<String, Object> constraints) {
    return new ModelRequirement(
        "customer intelligence",
        "booking propensity",
        "BOOKING_COMPLETED",
        "BOOKING_COMPLETED",
        "eligible consented sessions",
        "30d",
        "batch",
        "prioritize outreach",
        constraints,
        Map.of(),
        Map.of(),
        observables,
        syntheticAllowed);
  }

  private Map<String, Object> modelAttributes() {
    return Map.of(
        "objective", "predict booking intent",
        "scoredEntity", "customer session",
        "targetEvent", "BOOKING_COMPLETED",
        "predictionHorizon", "30d",
        "cohort", "eligible consented sessions");
  }

  private KnowledgeObject object(
      UUID id, KnowledgeType type, boolean synthetic, Map<String, Object> attributes) {
    return new KnowledgeObject(
        id,
        UUID.randomUUID(),
        type.name().toLowerCase() + ":" + id,
        1,
        type,
        type == KnowledgeType.MODEL
            ? "booking-intent"
            : type == KnowledgeType.FEATURE
                ? "booking-intent"
                : type == KnowledgeType.DATA_ASSET ? "raw_events" : type.name().toLowerCase(),
        "customer intelligence",
        "booking propensity",
        "predict booking intent for eligible consented sessions within 30d",
        Map.of(),
        Map.of(),
        List.of(),
        "APPROVED",
        Instant.now(),
        null,
        0.9,
        Map.of(),
        Map.of(),
        null,
        "test",
        null,
        null,
        null,
        attributes,
        synthetic);
  }

  private KnowledgeObject dataAsset(boolean synthetic) {
    return object(UUID.randomUUID(), KnowledgeType.DATA_ASSET, synthetic, Map.of("history", true));
  }

  private KnowledgePackage modelPackage(
      KnowledgeObject model, UUID featureId, UUID implementationId) {
    List<KnowledgeRelationship> relationships =
        featureId == null
            ? List.of()
            : List.of(
                new KnowledgeRelationship(
                    UUID.randomUUID(),
                    model.clientId(),
                    model.id(),
                    RelationshipType.USES,
                    featureId,
                    UUID.randomUUID()));
    List<KnowledgeObject> implementations =
        implementationId == null
            ? List.of()
            : List.of(
                object(
                    implementationId, KnowledgeType.IMPLEMENTATION, model.synthetic(), Map.of()));
    return packageFor(model, relationships, implementations);
  }

  private KnowledgePackage packageFor(KnowledgeObject object) {
    return packageFor(object, List.of(), List.of());
  }

  private KnowledgePackage packageFor(
      KnowledgeObject object,
      List<KnowledgeRelationship> relationships,
      List<KnowledgeObject> implementations) {
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            UUID.randomUUID(),
            object.clientId(),
            object.id(),
            "test",
            "fixture",
            "test.yaml",
            "v1",
            object.name(),
            1.0,
            Instant.now());
    return new KnowledgePackage(
        object.id(),
        object.version(),
        object.knowledgeType().name(),
        object.name(),
        object.businessDescription(),
        object.attributes(),
        implementations,
        List.of(evidence),
        List.of(),
        relationships,
        null,
        List.of(),
        object.confidence(),
        object.confidenceBreakdown(),
        object.llmInvocationId(),
        object.lifecycleStatus(),
        object.trusted(),
        object.synthetic(),
        List.of(),
        List.of(),
        null);
  }

  private KnowledgePackage packageWithConflicts(
      KnowledgePackage source, List<KnowledgeConflict> conflicts) {
    return new KnowledgePackage(
        source.id(),
        source.version(),
        source.type(),
        source.name(),
        source.businessDefinition(),
        source.attributes(),
        source.implementations(),
        source.evidence(),
        source.fieldProvenance(),
        source.relationships(),
        source.contextualPerformance(),
        source.constraints(),
        source.confidence(),
        source.confidenceBreakdown(),
        source.llmInvocationId(),
        source.approvalStatus(),
        source.trusted(),
        source.synthetic(),
        source.warnings(),
        conflicts,
        source.relevance());
  }
}
