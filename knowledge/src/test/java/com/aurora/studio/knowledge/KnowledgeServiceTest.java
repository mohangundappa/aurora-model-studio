package com.aurora.studio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.KnowledgeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeServiceTest {
  private final KnowledgeRepository repository =
      org.mockito.Mockito.mock(KnowledgeRepository.class);
  private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
  private KnowledgeService service;

  @BeforeEach
  void setUp() {
    ClientContext.set(UUID.randomUUID());
    service = new KnowledgeService(repository, jdbc);
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
            any());
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
