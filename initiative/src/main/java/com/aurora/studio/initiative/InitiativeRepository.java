package com.aurora.studio.initiative;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InitiativeRepository {
  private static final List<InitiativeStage> STAGES = List.of(InitiativeStage.values());
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public InitiativeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public UUID create(UUID requirementId, boolean includeCandidates, Long baseline) {
    UUID id =
        jdbc.queryForObject(
            "insert into initiatives(client_id,requirement_id,include_candidates,client_baseline_duration_millis) values(?,?,?,?) returning id",
            UUID.class,
            ClientContext.require(),
            requirementId,
            includeCandidates,
            baseline);
    for (InitiativeStage stage : STAGES) {
      StageStatus status =
          stage == InitiativeStage.REQUIREMENT_INTAKE
              ? StageStatus.COMPLETED
              : stage == InitiativeStage.CANDIDATE_BUILD
                  ? StageStatus.OUT_OF_SCOPE
                  : notImplemented(stage) ? StageStatus.NOT_IMPLEMENTED : StageStatus.PENDING;
      UUID attempt = insertAttempt(id, stage, 1, status);
      if (stage == InitiativeStage.REQUIREMENT_INTAKE) {
        insertEvent(
            id,
            stage,
            null,
            status,
            "initiative-intake-human",
            "Requirement registered; actor identity is self-declared and unverified",
            List.of());
      }
    }
    return id;
  }

  public List<Base> findAll() {
    return jdbc.query(
        "select * from initiatives where client_id=? order by created_at desc",
        this::mapBase,
        ClientContext.require());
  }

  public Optional<Base> find(UUID id) {
    return jdbc
        .query(
            "select * from initiatives where client_id=? and id=?",
            this::mapBase,
            ClientContext.require(),
            id)
        .stream()
        .findFirst();
  }

  public Optional<UUID> findIdByRequirement(UUID requirementId) {
    return jdbc
        .query(
            "select id from initiatives where client_id=? and requirement_id=? order by created_at limit 1",
            (rs, row) -> rs.getObject("id", UUID.class),
            ClientContext.require(),
            requirementId)
        .stream()
        .findFirst();
  }

  public List<Attempt> attempts(UUID initiativeId) {
    return jdbc.query(
        "select * from initiative_stage_attempts where client_id=? and initiative_id=? order by stage,attempt",
        this::mapAttempt,
        ClientContext.require(),
        initiativeId);
  }

  public Optional<Attempt> latestAttempt(UUID initiativeId, InitiativeStage stage) {
    return jdbc
        .query(
            "select * from initiative_stage_attempts where client_id=? and initiative_id=? and stage=? order by attempt desc limit 1",
            this::mapAttempt,
            ClientContext.require(),
            initiativeId,
            stage.name())
        .stream()
        .findFirst();
  }

  public UUID insertAttempt(
      UUID initiativeId, InitiativeStage stage, int attempt, StageStatus status) {
    return jdbc.queryForObject(
        "insert into initiative_stage_attempts(client_id,initiative_id,stage,attempt,status) values(?,?,?,?,?) returning id",
        UUID.class,
        ClientContext.require(),
        initiativeId,
        stage.name(),
        attempt,
        status.name());
  }

  public void start(UUID attemptId, Instant startedAt) {
    jdbc.update(
        "update initiative_stage_attempts set status='IN_PROGRESS',started_at=? where client_id=? and id=?",
        Timestamp.from(startedAt),
        ClientContext.require(),
        attemptId);
  }

  public void finish(
      UUID attemptId,
      StageStatus status,
      Instant completedAt,
      long machineMillis,
      long waitMillis,
      List<String> blockers,
      List<FeasibilityCheck> checks,
      List<ArtifactReference> artifacts) {
    jdbc.update(
        "update initiative_stage_attempts set status=?,completed_at=?,machine_duration_millis=?,human_wait_duration_millis=?,blockers=?::jsonb,feasibility_checks=?::jsonb,artifact_ids=?::jsonb where client_id=? and id=?",
        status.name(),
        Timestamp.from(completedAt),
        machineMillis,
        waitMillis,
        json(blockers),
        json(checks),
        json(artifacts),
        ClientContext.require(),
        attemptId);
  }

  public void saveDrafts(
      UUID attemptId, List<GenerationDraft> drafts, List<String> violatedChecks) {
    jdbc.update(
        "update initiative_stage_attempts set generation_drafts=?::jsonb,drafts_generated=?,drafts_rejected=?,violated_checks=?::jsonb where client_id=? and id=?",
        json(drafts),
        drafts.size(),
        drafts.stream().filter(draft -> "REJECTED".equals(draft.outcome())).count(),
        json(violatedChecks),
        ClientContext.require(),
        attemptId);
  }

  public void awaitApproval(
      UUID attemptId,
      Instant completedAt,
      long machineMillis,
      List<String> blockers,
      List<ArtifactReference> artifacts) {
    awaitApproval(attemptId, completedAt, machineMillis, blockers, List.of(), artifacts);
  }

  public void awaitApproval(
      UUID attemptId,
      Instant completedAt,
      long machineMillis,
      List<String> blockers,
      List<FeasibilityCheck> checks,
      List<ArtifactReference> artifacts) {
    finish(
        attemptId,
        StageStatus.AWAITING_APPROVAL,
        completedAt,
        machineMillis,
        0,
        blockers,
        checks,
        artifacts);
  }

  public void insertEvent(
      UUID initiativeId,
      InitiativeStage stage,
      StageStatus from,
      StageStatus to,
      String actor,
      String reason,
      List<ArtifactReference> artifacts) {
    jdbc.update(
        "insert into initiative_events(client_id,initiative_id,stage,from_status,to_status,actor,reason,artifact_ids) values(?,?,?,?,?,?,?,?::jsonb)",
        ClientContext.require(),
        initiativeId,
        stage.name(),
        from == null ? null : from.name(),
        to.name(),
        actor,
        reason,
        json(artifacts));
  }

  public UUID insertGateDecision(
      UUID initiativeId,
      UUID attemptId,
      InitiativeStage stage,
      String decision,
      String actor,
      String reason,
      List<String> acceptedUnknownChecks) {
    jdbc.queryForObject(
        "select set_config('aurora.initiative_gate_actor','human',true)", String.class);
    return jdbc.queryForObject(
        "insert into initiative_gate_decisions(client_id,initiative_id,stage_attempt_id,stage,decision,actor,actor_verified,reason,accepted_unknown_checks) values(?,?,?,?,?,?,false,?,?::jsonb) returning id",
        UUID.class,
        ClientContext.require(),
        initiativeId,
        attemptId,
        stage.name(),
        decision,
        actor,
        reason,
        json(acceptedUnknownChecks));
  }

  public List<GateRow> decisions(UUID initiativeId) {
    return jdbc.query(
        "select * from initiative_gate_decisions where client_id=? and initiative_id=? order by created_at",
        (rs, row) ->
            new GateRow(
                rs.getObject("id", UUID.class),
                InitiativeStage.valueOf(rs.getString("stage")),
                rs.getObject("stage_attempt_id", UUID.class),
                rs.getString("decision"),
                rs.getString("actor"),
                rs.getBoolean("actor_verified"),
                rs.getString("reason"),
                readStrings(rs.getString("accepted_unknown_checks")),
                instant(rs, "created_at")),
        ClientContext.require(),
        initiativeId);
  }

  public List<EventRow> events(UUID initiativeId) {
    return jdbc.query(
        "select * from initiative_events where client_id=? and initiative_id=? order by at,id",
        (rs, row) ->
            new EventRow(
                rs.getLong("id"),
                InitiativeStage.valueOf(rs.getString("stage")),
                nullableStageStatus(rs.getString("from_status")),
                StageStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor"),
                rs.getString("reason"),
                readArtifacts(rs.getString("artifact_ids")),
                instant(rs, "at")),
        ClientContext.require(),
        initiativeId);
  }

  private Base mapBase(ResultSet rs, int row) throws SQLException {
    return new Base(
        rs.getObject("id", UUID.class),
        rs.getObject("requirement_id", UUID.class),
        rs.getBoolean("include_candidates"),
        (Long) rs.getObject("client_baseline_duration_millis"),
        instant(rs, "created_at"));
  }

  private Attempt mapAttempt(ResultSet rs, int row) throws SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        InitiativeStage.valueOf(rs.getString("stage")),
        rs.getInt("attempt"),
        StageStatus.valueOf(rs.getString("status")),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        rs.getLong("machine_duration_millis"),
        rs.getLong("human_wait_duration_millis"),
        readStrings(rs.getString("blockers")),
        readChecks(rs.getString("feasibility_checks")),
        readArtifacts(rs.getString("artifact_ids")),
        readDrafts(rs.getString("generation_drafts")),
        rs.getInt("drafts_generated"),
        rs.getInt("drafts_rejected"),
        readStrings(rs.getString("violated_checks")),
        handoffAttempts(rs.getObject("id", UUID.class)));
  }

  public List<HandoffAttempt> handoffAttempts(UUID stageAttemptId) {
    return jdbc.query(
        "select * from initiative_handoff_attempts where client_id=? and stage_attempt_id=? order by created_at",
        (rs, row) ->
            new HandoffAttempt(
                rs.getObject("id", UUID.class),
                rs.getString("package_hash"),
                rs.getString("endpoint"),
                readMap(rs.getString("request_summary")),
                (Integer) rs.getObject("response_status"),
                rs.getString("candidate_id"),
                rs.getString("candidate_status"),
                rs.getString("outcome"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                instant(rs, "started_at"),
                instant(rs, "completed_at")),
        ClientContext.require(),
        stageAttemptId);
  }

  public UUID savePackage(
      UUID initiativeId, String packageHash, Map<String, Object> packageContent) {
    return jdbc.queryForObject(
        "insert into initiative_handoff_packages(client_id,initiative_id,package_hash,package) values(?,?,?,?::jsonb) on conflict (client_id,package_hash) do update set package_hash=excluded.package_hash returning id",
        UUID.class,
        ClientContext.require(),
        initiativeId,
        packageHash,
        json(packageContent));
  }

  public UUID saveHandoffAttempt(
      UUID initiativeId,
      UUID stageAttemptId,
      String packageHash,
      String endpoint,
      Map<String, Object> requestSummary,
      Integer responseStatus,
      String candidateId,
      String candidateStatus,
      String outcome,
      String failureCode,
      String failureMessage,
      Instant startedAt,
      Instant completedAt) {
    return jdbc.queryForObject(
        "insert into initiative_handoff_attempts(client_id,initiative_id,stage_attempt_id,package_hash,endpoint,request_summary,response_status,candidate_id,candidate_status,outcome,failure_code,failure_message,started_at,completed_at) values(?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?) returning id",
        UUID.class,
        ClientContext.require(),
        initiativeId,
        stageAttemptId,
        packageHash,
        endpoint,
        json(requestSummary),
        responseStatus,
        candidateId,
        candidateStatus,
        outcome,
        failureCode,
        failureMessage,
        Timestamp.from(startedAt),
        Timestamp.from(completedAt));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private boolean notImplemented(InitiativeStage stage) {
    return false;
  }

  private StageStatus nullableStageStatus(String value) {
    return value == null ? null : StageStatus.valueOf(value);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid initiative JSON", exception);
    }
  }

  private List<String> readStrings(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid initiative blockers", exception);
    }
  }

  private Map<String, Object> readMap(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid initiative JSON", exception);
    }
  }

  private List<FeasibilityCheck> readChecks(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid feasibility checks", exception);
    }
  }

  private List<ArtifactReference> readArtifacts(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid initiative artifacts", exception);
    }
  }

  private List<GenerationDraft> readDrafts(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid generation drafts", exception);
    }
  }

  public record Base(
      UUID id,
      UUID requirementId,
      boolean includeCandidates,
      Long clientBaselineDurationMillis,
      Instant createdAt) {}

  public record Attempt(
      UUID id,
      InitiativeStage stage,
      int attempt,
      StageStatus status,
      Instant startedAt,
      Instant completedAt,
      long machineDurationMillis,
      long humanWaitDurationMillis,
      List<String> blockers,
      List<FeasibilityCheck> feasibilityChecks,
      List<ArtifactReference> artifacts,
      List<GenerationDraft> drafts,
      int draftsGenerated,
      int draftsRejected,
      List<String> violatedChecks,
      List<HandoffAttempt> handoffAttempts) {
    public Attempt(
        UUID id,
        InitiativeStage stage,
        int attempt,
        StageStatus status,
        Instant startedAt,
        Instant completedAt,
        long machineDurationMillis,
        long humanWaitDurationMillis,
        List<String> blockers,
        List<FeasibilityCheck> feasibilityChecks,
        List<ArtifactReference> artifacts) {
      this(
          id,
          stage,
          attempt,
          status,
          startedAt,
          completedAt,
          machineDurationMillis,
          humanWaitDurationMillis,
          blockers,
          feasibilityChecks,
          artifacts,
          List.of(),
          0,
          0,
          List.of(),
          List.of());
    }

    public Attempt(
        UUID id,
        InitiativeStage stage,
        int attempt,
        StageStatus status,
        Instant startedAt,
        Instant completedAt,
        long machineDurationMillis,
        long humanWaitDurationMillis,
        List<String> blockers,
        List<FeasibilityCheck> feasibilityChecks,
        List<ArtifactReference> artifacts,
        List<GenerationDraft> drafts,
        int draftsGenerated,
        int draftsRejected,
        List<String> violatedChecks) {
      this(
          id,
          stage,
          attempt,
          status,
          startedAt,
          completedAt,
          machineDurationMillis,
          humanWaitDurationMillis,
          blockers,
          feasibilityChecks,
          artifacts,
          drafts,
          draftsGenerated,
          draftsRejected,
          violatedChecks,
          List.of());
    }
  }

  public record GateRow(
      UUID id,
      InitiativeStage stage,
      UUID stageAttemptId,
      String decision,
      String actor,
      boolean actorVerified,
      String reason,
      List<String> acceptedUnknownChecks,
      Instant createdAt) {}

  public record EventRow(
      long id,
      InitiativeStage stage,
      StageStatus fromStatus,
      StageStatus toStatus,
      String actor,
      String reason,
      List<ArtifactReference> artifacts,
      Instant at) {}
}
