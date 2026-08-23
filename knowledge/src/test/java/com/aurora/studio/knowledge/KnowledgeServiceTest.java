package com.aurora.studio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
  void unapprovedObjectsAreAbsentFromDefaultSearchAndByIdRetrieval() {
    UUID id = UUID.randomUUID();
    KnowledgeObject candidate = feature(id, Map.of());
    when(repository.search("FEATURE", null, null, "APPROVED", null, null)).thenReturn(List.of());
    when(repository.findById(id)).thenReturn(Optional.of(candidate));

    assertThat(service.search("FEATURE", null, null, null, null, null, false)).isEmpty();
    assertThatThrownBy(() -> service.get(id, false)).isInstanceOf(KnowledgeNotFoundException.class);
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

    KnowledgeService.Impact result = service.analyzeImpact(root, 1);

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
            "actor",
            null,
            null,
            null,
            Map.of(
                "languageOrKind", "java",
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
        "EXTRACTED",
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
        "actor",
        null,
        null,
        null,
        attributes,
        false);
  }
}
