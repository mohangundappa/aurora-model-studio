package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public KnowledgeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public KnowledgeObject save(KnowledgeObject object) {
    UUID id =
        object.id() == null
            ? jdbc.queryForObject(
                "insert into knowledge_objects(client_id,knowledge_key,version,knowledge_type,name,business_domain,business_use_case,business_description,canonical_taxonomy,client_taxonomy,tags,lifecycle_status,confidence,confidence_breakdown,quality_assessment,extracted_by,attributes,synthetic) "
                    + "values(?,?,?,?,?,?,?, ?,?::jsonb,?::jsonb,?, ?,?,?::jsonb,?::jsonb,?,?::jsonb,?) returning id",
                UUID.class,
                ClientContext.require(),
                object.knowledgeKey(),
                object.version(),
                object.knowledgeType().name(),
                object.name(),
                object.businessDomain(),
                object.businessUseCase(),
                object.businessDescription(),
                json(object.canonicalTaxonomy()),
                json(object.clientTaxonomy()),
                object.tags().toArray(String[]::new),
                object.lifecycleStatus(),
                object.confidence(),
                json(object.confidenceBreakdown()),
                json(object.qualityAssessment()),
                object.extractedBy(),
                json(object.attributes()),
                object.synthetic())
            : object.id();
    if (object.id() != null) {
      jdbc.update(
          "update knowledge_objects set lifecycle_status=?,effective_to=?,reviewed_by=?,approved_by=?,approval_comments=? where client_id=? and id=?",
          object.lifecycleStatus(),
          object.effectiveTo(),
          object.reviewedBy(),
          object.approvedBy(),
          object.approvalComments(),
          ClientContext.require(),
          object.id());
    }
    return findById(id).orElseThrow();
  }

  public Optional<KnowledgeObject> findById(UUID id) {
    return jdbc
        .query(
            "select * from knowledge_objects where client_id=? and id=?",
            this::map,
            ClientContext.require(),
            id)
        .stream()
        .findFirst();
  }

  public Optional<KnowledgeObject> findLatest(String key) {
    return jdbc
        .query(
            "select * from knowledge_objects where client_id=? and knowledge_key=? order by version desc limit 1",
            this::map,
            ClientContext.require(),
            key)
        .stream()
        .findFirst();
  }

  public Optional<KnowledgeObject> findApproved(String key) {
    return jdbc
        .query(
            "select * from knowledge_objects where client_id=? and knowledge_key=? and lifecycle_status='APPROVED'",
            this::map,
            ClientContext.require(),
            key)
        .stream()
        .findFirst();
  }

  public List<KnowledgeObject> findByKeyExcluding(String key, UUID excludedId) {
    return jdbc.query(
        "select * from knowledge_objects where client_id=? and knowledge_key=? and id<>?",
        this::map,
        ClientContext.require(),
        key,
        excludedId);
  }

  public List<KnowledgeObject> search(
      String type, String domain, String useCase, String status, String tag, String text) {
    return jdbc.query(
        "select * from knowledge_objects where client_id=? and (? is null or knowledge_type=?) and (? is null or business_domain=?) and (? is null or business_use_case=?) and (? is null or lifecycle_status=?) and (? is null or ?=any(tags)) and (? is null or lower(name||' '||business_description) like lower('%'||?||'%')) order by knowledge_key,version desc",
        this::map,
        ClientContext.require(),
        type,
        type,
        domain,
        domain,
        useCase,
        useCase,
        status,
        status,
        tag,
        tag,
        text,
        text);
  }

  public List<KnowledgeObject> searchGovernanceRules(String enforcementPoint) {
    return jdbc.query(
        "select * from knowledge_objects where client_id=? and knowledge_type='STANDARD' and lifecycle_status='APPROVED' and (? is null or attributes->>'enforcementPoint'=?) order by knowledge_key,version desc",
        this::map,
        ClientContext.require(),
        enforcementPoint,
        enforcementPoint);
  }

  public List<KnowledgeEvidence> evidence(UUID objectId) {
    return jdbc.query(
        "select * from knowledge_evidence where client_id=? and knowledge_object_id=? order by recorded_at",
        (rs, row) ->
            new KnowledgeEvidence(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("knowledge_object_id", UUID.class),
                rs.getString("source_system"),
                rs.getString("source_type"),
                rs.getString("source_uri"),
                rs.getString("source_version"),
                rs.getString("excerpt"),
                rs.getDouble("extraction_certainty"),
                rs.getTimestamp("recorded_at").toInstant()),
        ClientContext.require(),
        objectId);
  }

  public Optional<Instant> newestEvidenceForKey(String knowledgeKey) {
    List<Instant> timestamps =
        jdbc.query(
            "select max(e.recorded_at) from knowledge_evidence e join knowledge_objects o on o.client_id=e.client_id and o.id=e.knowledge_object_id where e.client_id=? and o.knowledge_key=?",
            (resultSet, rowNum) -> {
              java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
              return timestamp == null ? null : timestamp.toInstant();
            },
            ClientContext.require(),
            knowledgeKey);
    return timestamps.stream().filter(java.util.Objects::nonNull).findFirst();
  }

  public UUID addEvidence(
      UUID objectId,
      String sourceSystem,
      String sourceType,
      String sourceUri,
      String sourceVersion,
      String excerpt,
      double certainty) {
    return jdbc.queryForObject(
        "insert into knowledge_evidence(client_id,knowledge_object_id,source_system,source_type,source_uri,source_version,excerpt,extraction_certainty) values(?,?,?,?,?,?,?,?) returning id",
        UUID.class,
        ClientContext.require(),
        objectId,
        sourceSystem,
        sourceType,
        sourceUri,
        sourceVersion,
        excerpt,
        certainty);
  }

  public void linkInvocation(UUID objectId, UUID invocationId) {
    jdbc.update(
        "update knowledge_objects set llm_invocation_id=? where client_id=? and id=?",
        invocationId,
        ClientContext.require(),
        objectId);
  }

  public void saveFieldProvenance(FieldProvenance field) {
    jdbc.update(
        "insert into knowledge_field_provenance(client_id,knowledge_object_id,field_name,field_value,provenance,citation_evidence_id,citation_excerpt,extraction_certainty) values(?,?,?,?::jsonb,?,?,?,?)",
        ClientContext.require(),
        field.knowledgeObjectId(),
        field.fieldName(),
        json(field.fieldValue()),
        field.provenance(),
        field.citationEvidenceId(),
        field.citationExcerpt(),
        field.extractionCertainty());
  }

  public List<FieldProvenance> fieldProvenance(UUID objectId) {
    return jdbc.query(
        "select * from knowledge_field_provenance where client_id=? and knowledge_object_id=? order by created_at",
        (rs, row) ->
            new FieldProvenance(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("knowledge_object_id", UUID.class),
                rs.getString("field_name"),
                readObject(rs.getString("field_value")),
                rs.getString("provenance"),
                rs.getObject("citation_evidence_id", UUID.class),
                rs.getString("citation_excerpt"),
                rs.getDouble("extraction_certainty")),
        ClientContext.require(),
        objectId);
  }

  public List<KnowledgeRelationship> relationships(UUID objectId) {
    return jdbc.query(
        "select * from knowledge_relationships where client_id=? and (from_object_id=? or to_object_id=?)",
        (rs, row) ->
            new KnowledgeRelationship(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("from_object_id", UUID.class),
                com.aurora.studio.common.RelationshipType.valueOf(
                    rs.getString("relationship_type")),
                rs.getObject("to_object_id", UUID.class),
                rs.getObject("evidence_id", UUID.class)),
        ClientContext.require(),
        objectId,
        objectId);
  }

  public UUID addRelationship(UUID from, String type, UUID to, UUID evidenceId) {
    return jdbc.queryForObject(
        "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id,evidence_id) values(?,?,?,?,?) returning id",
        UUID.class,
        ClientContext.require(),
        from,
        type,
        to,
        evidenceId);
  }

  public List<KnowledgeConflict> conflicts(UUID objectId) {
    return jdbc.query(
        "select * from knowledge_conflicts where client_id=? and knowledge_object_id=?",
        (rs, row) ->
            new KnowledgeConflict(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("knowledge_object_id", UUID.class),
                rs.getString("field"),
                readMap(rs.getString("values")),
                com.aurora.studio.common.KnowledgeConflictStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("detected_at").toInstant()),
        ClientContext.require(),
        objectId);
  }

  private KnowledgeObject map(ResultSet rs, int row) throws SQLException {
    return new KnowledgeObject(
        rs.getObject("id", UUID.class),
        rs.getObject("client_id", UUID.class),
        rs.getString("knowledge_key"),
        rs.getInt("version"),
        com.aurora.studio.common.KnowledgeType.valueOf(rs.getString("knowledge_type")),
        rs.getString("name"),
        rs.getString("business_domain"),
        rs.getString("business_use_case"),
        rs.getString("business_description"),
        readMap(rs.getString("canonical_taxonomy")),
        readMap(rs.getString("client_taxonomy")),
        List.of((String[]) rs.getArray("tags").getArray()),
        rs.getString("lifecycle_status"),
        instant(rs, "effective_from"),
        instant(rs, "effective_to"),
        rs.getDouble("confidence"),
        readMap(rs.getString("confidence_breakdown")),
        readMap(rs.getString("quality_assessment")),
        rs.getObject("llm_invocation_id", UUID.class),
        rs.getString("extracted_by"),
        rs.getString("reviewed_by"),
        rs.getString("approved_by"),
        rs.getString("approval_comments"),
        readMap(rs.getString("attributes")),
        rs.getBoolean("synthetic"));
  }

  private java.time.Instant instant(ResultSet rs, String name) throws SQLException {
    java.sql.Timestamp timestamp = rs.getTimestamp(name);
    return timestamp == null ? null : timestamp.toInstant();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMap(String value) {
    try {
      return mapper.readValue(value, Map.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid knowledge json", exception);
    }
  }

  private Object readObject(String value) {
    try {
      return mapper.readValue(value, Object.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid knowledge json", exception);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid knowledge attributes", exception);
    }
  }
}
