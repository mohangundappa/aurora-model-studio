package com.aurora.studio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.common.RelationshipType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeServiceTest {
  private final KnowledgeRepository repository =
      org.mockito.Mockito.mock(KnowledgeRepository.class);
  private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
  private KnowledgeService service;

  @BeforeEach
  void setUp() {
    ClientContext.set(UUID.randomUUID());
    service = new KnowledgeService(repository, jdbc, new ObjectMapper(), new ConfidenceWeights());
  }

  @AfterEach
  void tearDown() {
    ClientContext.clear();
  }

  @Test
  void rejectsModelMissingTypeSpecificAttributes() {
    KnowledgeService.Draft draft =
        new KnowledgeService.Draft(
            "model:x",
            KnowledgeType.MODEL,
            "X",
            "domain",
            "use-case",
            "description",
            Map.of(),
            Map.of(),
            List.of(),
            Map.of("objective", "predict"),
            false);
    assertThatThrownBy(() -> service.create(draft, "actor"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scoredEntity");
  }

  @Test
  void computesAndStoresConfidenceBreakdown() {
    when(repository.findLatest(anyString())).thenReturn(Optional.empty());
    when(repository.save(any(KnowledgeObject.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    KnowledgeObject result =
        service.create(
            new KnowledgeService.Draft(
                "feature:x",
                KnowledgeType.FEATURE,
                "X",
                "domain",
                "use-case",
                "description",
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(
                    "businessDefinition", "x",
                    "entity", "customer",
                    "observationWindow", "30d",
                    "pointInTimeAvailable", true),
                false),
            "actor");
    assertThat(result.confidenceBreakdown())
        .containsKeys("sourceReliability", "completeness", "recency");
    assertThat(result.confidenceBreakdown().get("sourceReliability")).isNull();
    assertThat(result.confidenceBreakdown().get("recency")).isNull();
    assertThat(result.confidenceBreakdown().get("completeness")).isEqualTo(2.0 / 3.0);
    assertThat(result.qualityAssessment().get("completeness"))
        .isEqualTo(result.confidenceBreakdown().get("completeness"));
  }

  @Test
  void derivesExtractionCertaintyAndRecencyFromEvidence() {
    UUID id = UUID.randomUUID();
    Instant recordedAt = Instant.now();
    KnowledgeObject object = feature(id, Map.of());
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            UUID.randomUUID(),
            ClientContext.require(),
            id,
            "system",
            "source-file",
            "uri",
            "v1",
            "excerpt",
            0.4,
            recordedAt);
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.anyDouble()))
        .thenReturn(evidence.id());
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of());
    when(repository.conflicts(id)).thenReturn(List.of());
    when(repository.newestEvidenceForKey("feature:x")).thenReturn(Optional.of(recordedAt));

    service.addEvidence(id, "system", "source-file", "uri", "v1", "excerpt", 0.4);

    ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("confidence_breakdown"),
            any(),
            breakdown.capture(),
            any(),
            any(),
            any());
    assertThat(breakdown.getValue()).contains("\"extractionCertainty\":0.4");
    assertThat(breakdown.getValue()).contains("\"recency\":1.0");
  }

  @Test
  void illegalLifecycleTransitionNamesAttemptedTransition() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id))
        .thenReturn(
            Optional.of(
                new KnowledgeObject(
                    id,
                    ClientContext.require(),
                    "feature:x",
                    1,
                    KnowledgeType.FEATURE,
                    "X",
                    "domain",
                    "use-case",
                    "description",
                    Map.of(),
                    Map.of(),
                    List.of(),
                    "EXTRACTED",
                    null,
                    null,
                    0.7,
                    Map.of(),
                    Map.of(),
                    null,
                    "actor",
                    null,
                    null,
                    null,
                    Map.of(),
                    false)));
    assertThatThrownBy(() -> service.approve(id, "actor", null))
        .isInstanceOf(KnowledgeConflictException.class)
        .hasMessageContaining("PENDING_REVIEW -> APPROVED");
  }

  @Test
  void approvalRejectsIncompleteKnowledgeAndNamesMissingFields() {
    UUID id = UUID.randomUUID();
    KnowledgeObject object =
        new KnowledgeObject(
            id,
            ClientContext.require(),
            "feature:incomplete",
            1,
            KnowledgeType.FEATURE,
            "Incomplete",
            "domain",
            "use-case",
            "description",
            Map.of(),
            Map.of(),
            List.of(),
            "PENDING_REVIEW",
            null,
            null,
            0.2,
            Map.of(),
            Map.of(),
            null,
            "actor",
            null,
            null,
            null,
            Map.of("businessDefinition", "definition"),
            false);
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.evidence(id))
        .thenReturn(
            List.of(
                new KnowledgeEvidence(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    id,
                    "system",
                    "source-file",
                    "uri",
                    "v1",
                    "excerpt",
                    1.0,
                    Instant.now())));

    assertThatThrownBy(() -> service.approve(id, "actor", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entity")
        .hasMessageContaining("observationWindow")
        .hasMessageContaining("pointInTimeAvailable");
  }

  @Test
  void governedComparableDifferenceIsBlocking() {
    UUID id = UUID.randomUUID();
    KnowledgeObject implementation =
        new KnowledgeObject(
            id,
            ClientContext.require(),
            "implementation:loyalty",
            1,
            KnowledgeType.IMPLEMENTATION,
            "Loyalty implementation",
            "domain",
            "use-case",
            "implementation",
            Map.of(),
            Map.of(),
            List.of(),
            "EXTRACTED",
            null,
            null,
            0.7,
            Map.of(),
            Map.of(),
            null,
            "actor",
            null,
            null,
            null,
            Map.of("measurementUnit", "months"),
            false);
    KnowledgeObject specification =
        new KnowledgeObject(
            UUID.randomUUID(),
            ClientContext.require(),
            "standard:loyalty",
            1,
            KnowledgeType.STANDARD,
            "Loyalty specification",
            "domain",
            "use-case",
            "specification",
            Map.of(),
            Map.of(),
            List.of(),
            "EXTRACTED",
            null,
            null,
            0.7,
            Map.of(),
            Map.of(),
            null,
            "actor",
            null,
            null,
            null,
            Map.of("measurementUnit", "years", "governedRole", "SPECIFICATION"),
            false);
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            UUID.randomUUID(),
            ClientContext.require(),
            id,
            "system",
            "source-file",
            "uri",
            "v1",
            "excerpt",
            1.0,
            Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(implementation));
    when(repository.findById(specification.id())).thenReturn(Optional.of(specification));
    when(repository.addEvidence(
            any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble()))
        .thenReturn(evidence.id());
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of());
    when(repository.relationships(id))
        .thenReturn(
            List.of(
                new KnowledgeRelationship(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    id,
                    RelationshipType.GOVERNED_BY,
                    specification.id(),
                    null)));
    when(repository.conflicts(id)).thenReturn(List.of());
    when(repository.newestEvidenceForKey(anyString()))
        .thenReturn(Optional.of(evidence.recordedAt()));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "source-file", "uri", "v1", "excerpt", 1.0);

    ArgumentCaptor<String> conflictClass = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            conflictClass.capture(),
            any());
    assertThat(conflictClass.getValue()).isEqualTo("BLOCKING");
  }

  @Test
  void recordsOpenConflictAndCapsConfidenceWhenEvidenceDisagrees() {
    UUID id = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    KnowledgeObject object = feature(id, Map.of("businessDefinition", "new"));
    KnowledgeObject other = feature(UUID.randomUUID(), Map.of("businessDefinition", "old"));
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            evidenceId,
            ClientContext.require(),
            id,
            "system",
            "document",
            "uri",
            "v1",
            "excerpt",
            0.9,
            java.time.Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.anyDouble()))
        .thenReturn(evidenceId);
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of(other));
    when(repository.conflicts(id))
        .thenReturn(
            List.of(
                new KnowledgeConflict(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    id,
                    "businessDefinition",
                    Map.of(),
                    com.aurora.studio.common.KnowledgeConflictStatus.OPEN,
                    java.time.Instant.now())));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "document", "uri", "v1", "excerpt", 0.9);

    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            any(),
            any());
    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("update knowledge_objects"),
            eq(0.5),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void classifiesDescriptionDivergenceWithoutBlockingClass() {
    UUID id = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    KnowledgeObject object = feature(id, Map.of("businessDefinition", "new"));
    KnowledgeObject other = feature(UUID.randomUUID(), Map.of("businessDefinition", "old"));
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            evidenceId,
            ClientContext.require(),
            id,
            "system",
            "document",
            "uri",
            "v1",
            "excerpt",
            0.9,
            java.time.Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble()))
        .thenReturn(evidenceId);
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of(other));
    when(repository.conflicts(id)).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "document", "uri", "v1", "excerpt", 0.9);

    org.mockito.ArgumentCaptor<String> conflictClass = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            conflictClass.capture(),
            any());
    assertThat(conflictClass.getValue()).isEqualTo("DIVERGENT_DESCRIPTION");
  }

  @Test
  void classifiesComparableDifferencesAsBlocking() {
    UUID id = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    KnowledgeObject object = feature(id, Map.of("entity", "customer"));
    KnowledgeObject other = feature(UUID.randomUUID(), Map.of("entity", "guest"));
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            evidenceId,
            ClientContext.require(),
            id,
            "system",
            "document",
            "uri",
            "v1",
            "excerpt",
            0.9,
            java.time.Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble()))
        .thenReturn(evidenceId);
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of(other));
    when(repository.conflicts(id)).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "document", "uri", "v1", "excerpt", 0.9);

    org.mockito.ArgumentCaptor<String> conflictClass = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc, times(1))
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            conflictClass.capture(),
            any());
    assertThat(conflictClass.getValue()).isEqualTo("BLOCKING");
  }

  @Test
  void comparesImplementationLanguageWithoutMixingImplementationKind() {
    UUID id = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    KnowledgeObject object =
        implementation(
            id, Map.of("language", "Java", "implementationKind", "Spring calculator bean"));
    KnowledgeObject other = implementation(UUID.randomUUID(), Map.of("language", "Java"));
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            evidenceId,
            ClientContext.require(),
            id,
            "system",
            "source-file",
            "uri",
            "v1",
            "excerpt",
            0.9,
            java.time.Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble()))
        .thenReturn(evidenceId);
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of(other));
    when(repository.conflicts(id)).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "source-file", "uri", "v1", "excerpt", 0.9);

    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void implementationLanguageDisagreementRemainsBlocking() {
    UUID id = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    KnowledgeObject object =
        implementation(id, Map.of("language", "Java", "implementationKind", "calculator"));
    KnowledgeObject other =
        implementation(
            UUID.randomUUID(), Map.of("language", "Kotlin", "implementationKind", "calculator"));
    KnowledgeEvidence evidence =
        new KnowledgeEvidence(
            evidenceId,
            ClientContext.require(),
            id,
            "system",
            "source-file",
            "uri",
            "v1",
            "excerpt",
            0.9,
            java.time.Instant.now());
    when(repository.findById(id)).thenReturn(Optional.of(object));
    when(repository.addEvidence(
            any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble()))
        .thenReturn(evidenceId);
    when(repository.evidence(id)).thenReturn(List.of(evidence));
    when(repository.findByKeyExcluding(anyString(), any())).thenReturn(List.of(other));
    when(repository.conflicts(id)).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);

    service.addEvidence(id, "system", "source-file", "uri", "v1", "excerpt", 0.9);

    org.mockito.ArgumentCaptor<String> conflictClass = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("insert into knowledge_conflicts"),
            any(),
            any(),
            any(),
            conflictClass.capture(),
            any());
    assertThat(conflictClass.getValue()).isEqualTo("BLOCKING");
  }

  @Test
  void unapprovedObjectsAreAbsentFromDefaultSearchAndByIdRetrieval() {
    UUID id = UUID.randomUUID();
    KnowledgeObject candidate = feature(id, Map.of());
    when(repository.search("FEATURE", null, null, "APPROVED", null, null)).thenReturn(List.of());
    when(repository.findById(id)).thenReturn(Optional.of(candidate));

    assertThat(service.search("FEATURE", null, null, null, null, null, false)).isEmpty();
    assertThatThrownBy(() -> service.get(id, false)).isInstanceOf(KnowledgeNotFoundException.class);
  }

  @Test
  void generatedFeatureCandidateRequiresOptInEvidenceAndCompleteFields() {
    UUID id = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    Map<String, Object> incompleteAttributes =
        Map.of(
            "businessDefinition", "Generated hypothesis", "sourceColumns", List.of("event_time"));
    KnowledgeObject candidate = feature(id, incompleteAttributes, "EXTRACTED");
    KnowledgeObject pending = feature(id, incompleteAttributes, "PENDING_REVIEW");
    java.util.concurrent.atomic.AtomicReference<KnowledgeObject> current =
        new java.util.concurrent.atomic.AtomicReference<>(candidate);
    KnowledgeService.Draft draft =
        new KnowledgeService.Draft(
            "feature:generated:generated-hypothesis",
            KnowledgeType.FEATURE,
            "generated-hypothesis",
            "customer intelligence",
            "generated",
            "generated feature hypothesis",
            Map.of(),
            Map.of(),
            List.of("generated", "candidate"),
            incompleteAttributes,
            false);
    when(repository.findLatest(draft.knowledgeKey())).thenReturn(Optional.empty());
    when(repository.save(any(KnowledgeObject.class))).thenReturn(candidate);
    when(repository.findById(id)).thenAnswer(invocation -> Optional.of(current.get()));
    when(repository.search("FEATURE", null, null, "APPROVED", null, null)).thenReturn(List.of());
    when(repository.search("FEATURE", null, null, "EXTRACTED", null, null))
        .thenReturn(List.of(candidate));

    KnowledgeObject created = service.createExtracted(draft, "model-studio-agent", invocationId);

    assertThat(created.lifecycleStatus()).isEqualTo("EXTRACTED");
    verify(repository).linkInvocation(id, invocationId);
    assertThat(service.search("FEATURE", null, null, null, null, null, false)).isEmpty();
    assertThat(service.search("FEATURE", null, null, "EXTRACTED", null, null, true))
        .containsExactly(candidate);
    assertThatThrownBy(() -> service.get(id, false)).isInstanceOf(KnowledgeNotFoundException.class);

    service.submitForReview(id, "human-reviewer", "Review generated hypothesis");
    current.set(pending);
    when(repository.evidence(id)).thenReturn(List.of());
    assertThatThrownBy(() -> service.approve(id, "human-reviewer", "Approve"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without evidence");

    when(repository.evidence(id))
        .thenReturn(
            List.of(
                new KnowledgeEvidence(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    id,
                    "model-studio",
                    "generation-record",
                    "initiative://feature-design",
                    invocationId.toString(),
                    "generated draft",
                    1.0,
                    Instant.now())));
    assertThatThrownBy(() -> service.approve(id, "human-reviewer", "Approve"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entity")
        .hasMessageContaining("observationWindow")
        .hasMessageContaining("pointInTimeAvailable");
  }

  @Test
  void explicitCandidateSearchRequiresOptIn() {
    assertThatThrownBy(() -> service.search(null, null, null, "EXTRACTED", null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("includeCandidates=true");
  }

  @Test
  void listObjectsExposeTrustedAndSyntheticFields() throws Exception {
    KnowledgeObject object = feature(UUID.randomUUID(), Map.of());

    var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(object));

    assertThat(json.get("trusted").asBoolean()).isFalse();
    assertThat(json.get("synthetic").asBoolean()).isFalse();
  }

  @Test
  void impactSeparatesDependenciesFromDependents() {
    UUID root = UUID.randomUUID();
    UUID dependency = UUID.randomUUID();
    UUID dependent = UUID.randomUUID();
    when(repository.findById(root)).thenReturn(Optional.of(feature(root, Map.of())));
    when(repository.relationships(root))
        .thenReturn(
            List.of(
                new KnowledgeRelationship(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    root,
                    RelationshipType.USES,
                    dependency,
                    null),
                new KnowledgeRelationship(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    dependent,
                    RelationshipType.USES,
                    root,
                    null)));
    when(repository.relationships(dependency)).thenReturn(List.of());
    when(repository.relationships(dependent)).thenReturn(List.of());

    KnowledgeService.Impact result = service.analyzeImpact(root, 1, true);

    assertThat(result.dependsOn())
        .extracting(KnowledgeService.ImpactPath::objectId)
        .containsExactly(dependency);
    assertThat(result.dependents())
        .extracting(KnowledgeService.ImpactPath::objectId)
        .containsExactly(dependent);
    assertThat(result.dependsOn().getFirst().direction()).isEqualTo("DEPENDS_ON");
    assertThat(result.dependents().getFirst().direction()).isEqualTo("DEPENDENT");
  }

  @Test
  void resolvesImplementedByObjectsInKnowledgePackage() {
    UUID featureId = UUID.randomUUID();
    UUID implementationId = UUID.randomUUID();
    KnowledgeObject feature = feature(featureId, Map.of());
    KnowledgeObject implementation =
        new KnowledgeObject(
            implementationId,
            ClientContext.require(),
            "implementation:x",
            1,
            KnowledgeType.IMPLEMENTATION,
            "X implementation",
            "domain",
            "use-case",
            "description",
            Map.of(),
            Map.of(),
            List.of(),
            "EXTRACTED",
            null,
            null,
            0.7,
            Map.of(),
            Map.of(),
            null,
            "actor",
            null,
            null,
            null,
            Map.of(
                "language", "java",
                "sourceTraceability", "src/X.java"),
            false);
    when(repository.findById(featureId)).thenReturn(Optional.of(feature));
    when(repository.evidence(featureId)).thenReturn(List.of());
    when(repository.conflicts(featureId)).thenReturn(List.of());
    when(repository.relationships(featureId))
        .thenReturn(
            List.of(
                new KnowledgeRelationship(
                    UUID.randomUUID(),
                    ClientContext.require(),
                    featureId,
                    RelationshipType.IMPLEMENTED_BY,
                    implementationId,
                    null)));
    when(repository.findById(implementationId)).thenReturn(Optional.of(implementation));

    KnowledgePackage result = service.get(featureId, true);

    assertThat(result.implementations()).containsExactly(implementation);
  }

  @Test
  void packageCarriesTypeSpecificConstraintsAndUnknownPerformance() {
    UUID featureId = UUID.randomUUID();
    KnowledgeObject feature =
        feature(
            featureId,
            Map.of(
                "consentRequirement", "per-event consent",
                "observationWindow", "30d",
                "pointInTimeAvailable", true,
                "restrictedUsage", "analytics only"));
    when(repository.findById(featureId)).thenReturn(Optional.of(feature));
    when(repository.evidence(featureId)).thenReturn(List.of());
    when(repository.conflicts(featureId)).thenReturn(List.of());
    when(repository.relationships(featureId)).thenReturn(List.of());

    KnowledgePackage result = service.get(featureId, true);

    assertThat(result.constraints())
        .contains(
            "Consent required: per-event consent",
            "Observation window: 30d",
            "Point-in-time available: true",
            "Restricted usage: analytics only");
    assertThat(result.contextualPerformance()).isNull();
  }

  private KnowledgeObject feature(UUID id, Map<String, Object> attributes) {
    return feature(id, attributes, "EXTRACTED");
  }

  private KnowledgeObject feature(UUID id, Map<String, Object> attributes, String lifecycleStatus) {
    return new KnowledgeObject(
        id,
        ClientContext.require(),
        "feature:x",
        1,
        KnowledgeType.FEATURE,
        "X",
        "domain",
        "use-case",
        "description",
        Map.of(),
        Map.of(),
        List.of(),
        lifecycleStatus,
        null,
        null,
        0.7,
        Map.of(
            "sourceReliability", 1.0,
            "crossSourceAgreement", 1.0,
            "extractionCertainty", 1.0,
            "completeness", 1.0,
            "recency", 1.0,
            "executionEvidence", 1.0),
        Map.of(),
        null,
        "actor",
        null,
        null,
        null,
        attributes,
        false);
  }

  private KnowledgeObject implementation(UUID id, Map<String, Object> attributes) {
    return new KnowledgeObject(
        id,
        ClientContext.require(),
        "implementation:x",
        1,
        KnowledgeType.IMPLEMENTATION,
        "X",
        "domain",
        "use-case",
        "description",
        Map.of(),
        Map.of(),
        List.of(),
        "EXTRACTED",
        null,
        null,
        0.7,
        Map.of(),
        Map.of(),
        null,
        "actor",
        null,
        null,
        null,
        attributes,
        false);
  }
}
