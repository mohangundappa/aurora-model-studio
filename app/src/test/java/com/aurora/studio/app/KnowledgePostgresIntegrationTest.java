package com.aurora.studio.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.extraction.ExtractionService;
import com.aurora.studio.extraction.StructuralParser;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerSocketIsAvailable")
class KnowledgePostgresIntegrationTest {
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("aurora_studio")
          .withUsername("aurora")
          .withPassword("aurora");

  @Autowired JdbcTemplate jdbc;
  @Autowired ExtractionService extraction;
  @Autowired MockMvc mockMvc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static boolean dockerSocketIsAvailable() {
    return java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"));
  }

  @BeforeEach
  void setUp() {
    ClientContext.set(CLIENT);
  }

  @AfterEach
  void tearDown() {
    ClientContext.clear();
  }

  @Test
  void approvedRowsRejectRawUpdatesAndNonExtractedDeletes() {
    UUID id = insert("approved-immutable", CLIENT, "APPROVED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update knowledge_objects set name='changed' where client_id=? and id=?",
                    CLIENT,
                    id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("approved knowledge object");
    assertThatThrownBy(
            () ->
                jdbc.update("delete from knowledge_objects where client_id=? and id=?", CLIENT, id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("cannot be deleted");
  }

  @Test
  void auditRowsRejectRawUpdatesAndDeletes() {
    UUID id = insert("audit-immutable", CLIENT, "EXTRACTED");
    jdbc.update(
        "insert into knowledge_audit(client_id,knowledge_object_id,to_status,actor) values(?,?,?,?)",
        CLIENT,
        id,
        "PENDING_REVIEW",
        "test");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update knowledge_audit set actor='tampered' where client_id=? and knowledge_object_id=?",
                    CLIENT,
                    id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from knowledge_audit where client_id=? and knowledge_object_id=?",
                    CLIENT,
                    id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void onlyOneApprovedVersionPerClientAndKeyIsAllowed() {
    insert("one-approved", CLIENT, "APPROVED");
    assertThatThrownBy(() -> insert("one-approved", CLIENT, "APPROVED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void byIdQueriesCannotCrossClientBoundary() {
    UUID id = insert("private-object", OTHER, "APPROVED");
    Integer visible =
        jdbc.queryForObject(
            "select count(*) from knowledge_objects where client_id=? and id=?",
            Integer.class,
            CLIENT,
            id);
    assertThat(visible).isZero();
  }

  @Test
  void childReferencesCannotCrossClientBoundary() {
    UUID first = insert("tenant-a", CLIENT, "EXTRACTED");
    UUID second = insert("tenant-b", OTHER, "EXTRACTED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?)",
                    CLIENT,
                    first,
                    "USES",
                    second))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void invocationRowsAreAppendOnlyAndTriggerExists() {
    UUID invocation =
        jdbc.queryForObject(
            "insert into llm_invocations(client_id,task_id,provider,model,prompt_template_id,prompt_template_version,prompt_hash,schema_id,outcome) values(?,?,?,?,?,?,?,?,?) returning id",
            UUID.class,
            CLIENT,
            "task",
            "deterministic",
            "deterministic",
            "template",
            "1",
            "hash",
            "schema",
            "OK");
    Integer triggerCount =
        jdbc.queryForObject(
            "select count(*) from pg_trigger where tgrelid='llm_invocations'::regclass and tgname='llm_invocations_append_only' and not tgisinternal",
            Integer.class);
    assertThat(triggerCount).isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update llm_invocations set outcome='FAILED' where client_id=? and id=?",
                    CLIENT,
                    invocation))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from llm_invocations where client_id=? and id=?", CLIENT, invocation))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void initiativeGatesRequireHumanApiAndInitiativeEventsAreAppendOnly() {
    UUID requirement =
        jdbc.queryForObject(
            "insert into discovery_requirements(client_id,requirement) values(?, '{}'::jsonb) returning id",
            UUID.class,
            CLIENT);
    UUID initiative =
        jdbc.queryForObject(
            "insert into initiatives(client_id,requirement_id) values(?,?) returning id",
            UUID.class,
            CLIENT,
            requirement);
    UUID attempt =
        jdbc.queryForObject(
            "insert into initiative_stage_attempts(client_id,initiative_id,stage,attempt,status) values(?,?,?,?,?) returning id",
            UUID.class,
            CLIENT,
            initiative,
            "REUSE_DECISION",
            1,
            "AWAITING_APPROVAL");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into initiative_gate_decisions(client_id,initiative_id,stage_attempt_id,stage,decision,actor) values(?,?,?,?,?,?)",
                    CLIENT,
                    initiative,
                    attempt,
                    "REUSE_DECISION",
                    "APPROVE",
                    "initiative-orchestrator"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("human gate API");
    jdbc.update(
        "insert into initiative_events(client_id,initiative_id,stage,to_status,actor) values(?,?,?,?,?)",
        CLIENT,
        initiative,
        "REUSE_DECISION",
        "IN_PROGRESS",
        "initiative-orchestrator");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update initiative_events set reason='tampered' where client_id=? and initiative_id=?",
                    CLIENT,
                    initiative))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from initiative_events where client_id=? and initiative_id=?",
                    CLIENT,
                    initiative))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void extractionCandidateReferencesSuccessfulInvocationAndRemainsExtracted() {
    StructuralParser parser = new StructuralParser();
    var artifact =
        parser.artifact(
            Path.of("integration.yaml"),
            "FEATURE",
            "integration-feature",
            "integration-feature is a hotel feature.");
    var run = extraction.extractArtifacts(List.of(artifact), true);
    assertThat(run.candidateIds()).hasSize(1);
    UUID objectId = run.candidateIds().getFirst();
    var row =
        jdbc.queryForMap(
            "select o.lifecycle_status,o.confidence,o.synthetic,o.llm_invocation_id,i.outcome from knowledge_objects o join llm_invocations i on i.client_id=o.client_id and i.id=o.llm_invocation_id where o.client_id=? and o.id=?",
            CLIENT,
            objectId);
    assertThat(row)
        .containsEntry("lifecycle_status", "EXTRACTED")
        .containsEntry("synthetic", true)
        .containsEntry("outcome", "OK");
    assertThat(row.get("llm_invocation_id")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_field_provenance where client_id=? and knowledge_object_id=?",
                Integer.class,
                CLIENT,
                objectId))
        .isGreaterThan(0);
    assertThat(
            jdbc.queryForList(
                "select provenance,extraction_certainty from knowledge_field_provenance where client_id=? and knowledge_object_id=?",
                CLIENT,
                objectId))
        .anyMatch(
            provenanceRow ->
                "EVIDENCE_BACKED".equals(provenanceRow.get("provenance"))
                    && ((Number) provenanceRow.get("extraction_certainty")).doubleValue() == 1.0)
        .anyMatch(
            adaptedRow ->
                "ADAPTED".equals(adaptedRow.get("provenance"))
                    && ((Number) adaptedRow.get("extraction_certainty")).doubleValue() == 0.72);
  }

  @Test
  void impactRelationshipsCanContainCyclesWithoutUnboundedTraversal() {
    UUID first = insert("cycle-a", CLIENT, "EXTRACTED");
    UUID second = insert("cycle-b", CLIENT, "EXTRACTED");
    UUID third = insert("cycle-c", CLIENT, "EXTRACTED");
    jdbc.update(
        "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?),(?,?,?,?),(?,?,?,?)",
        CLIENT,
        first,
        "USES",
        second,
        CLIENT,
        second,
        "USES",
        third,
        CLIENT,
        third,
        "USES",
        first);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_relationships where client_id=?",
                Integer.class,
                CLIENT))
        .isEqualTo(3);
  }

  @Test
  void candidateRoutesRequireExplicitOptInAndSearchDefaultsToApproved() throws Exception {
    UUID id = insert("candidate-route", CLIENT, "EXTRACTED");
    jdbc.update(
        "insert into knowledge_evidence(client_id,knowledge_object_id,source_system,source_type,source_uri,source_version,excerpt,extraction_certainty) values(?,?,?,?,?,?,?,?)",
        CLIENT,
        id,
        "test",
        "source-file",
        "candidate-route.yaml",
        "v1",
        "candidate-route evidence",
        1.0);

    mockMvc
        .perform(get("/api/knowledge/{id}", id).header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/knowledge/{id}/evidence", id).header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/knowledge/{id}/impact", id).header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/knowledge").header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mockMvc
        .perform(
            get("/api/knowledge/{id}", id)
                .param("includeCandidates", "true")
                .header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trusted").value(false));
    mockMvc
        .perform(
            get("/api/knowledge/{id}/evidence", id)
                .param("includeCandidates", "true")
                .header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].excerpt").value("candidate-route evidence"));
    mockMvc
        .perform(
            get("/api/knowledge/{id}/impact", id)
                .param("includeCandidates", "true")
                .header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void governanceRulesWithoutFilterReturnApprovedRules() throws Exception {
    jdbc.update(
        "insert into knowledge_objects(client_id,knowledge_key,version,knowledge_type,name,business_domain,business_use_case,business_description,lifecycle_status,confidence,confidence_breakdown,attributes,extracted_by,synthetic) values(?,?,?,?,?,?,?,?,?,?,'{}',?::jsonb,'test',false)",
        CLIENT,
        "standard:governance-route",
        1,
        "STANDARD",
        "governance-route",
        "governance",
        "testing",
        "governance route test",
        "APPROVED",
        0.5,
        "{\"rule\":\"test\",\"enforcementPoint\":\"testing\"}");
    mockMvc
        .perform(
            get("/api/knowledge/governance-rules").header("X-Aurora-Client", CLIENT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("governance-route"));
  }

  @Test
  void clientFilterRejectsNonCanonicalIdsWithJsonMessages() throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("X-Aurora-Client", "0-0-0-0-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("X-Aurora-Client must be a UUID"));
    mockMvc
        .perform(
            get("/actuator/health")
                .header("X-Aurora-Client", "00000000-0000-0000-0000-000000000001 "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("X-Aurora-Client must be a UUID"));
    mockMvc
        .perform(
            get("/actuator/health")
                .header("X-Aurora-Client", "00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Unknown Aurora client"));
  }

  private UUID insert(String key, UUID client, String status) {
    return jdbc.queryForObject(
        "insert into knowledge_objects(client_id,knowledge_key,version,knowledge_type,name,business_domain,business_use_case,business_description,lifecycle_status,confidence,confidence_breakdown,attributes,extracted_by,synthetic) values(?,?,?,?,?,?,?,?,?,?,'{}','{}','test',false) returning id",
        UUID.class,
        client,
        key,
        1,
        "FEATURE",
        key,
        "test",
        "test",
        "test",
        status,
        0.5);
  }
}
