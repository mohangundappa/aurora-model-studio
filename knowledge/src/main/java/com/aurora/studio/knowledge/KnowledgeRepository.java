package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

  public boolean hasEvidence(String key, String sourceVersion) {
    return jdbc.queryForObject(
        "select exists(select 1 from knowledge_evidence e join knowledge_objects o on o.client_id=e.client_id and o.id=e.knowledge_object_id where e.client_id=? and o.knowledge_key=? and e.source_version=?)",
        Boolean.class,
        ClientContext.require(),
        key,
        sourceVersion);
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
    StringBuilder sql = new StringBuilder("select * from knowledge_objects where client_id=?");
    List<Object> parameters = new ArrayList<>();
    parameters.add(ClientContext.require());
    if (type != null) {
      sql.append(" and knowledge_type=?");
      parameters.add(type);
    }
    if (domain != null) {
      sql.append(" and business_domain=?");
      parameters.add(domain);
    }
    if (useCase != null) {
      sql.append(" and business_use_case=?");
      parameters.add(useCase);
    }
    if (status != null) {
      sql.append(" and lifecycle_status=?");
      parameters.add(status);
    }
    if (tag != null) {
      sql.append(" and ?=any(tags)");
      parameters.add(tag);
    }
    if (text != null) {
      sql.append(" and lower(name||' '||business_description) like lower(?)");
      parameters.add("%" + text + "%");
    }
    sql.append(" order by knowledge_key,version desc");
    return jdbc.query(sql.toString(), this::map, parameters.toArray());
  }

  public List<KnowledgeObject> searchGovernanceRules(String enforcementPoint) {
    String sql =
        "select * from knowledge_objects where client_id=? and knowledge_type='STANDARD' and lifecycle_status='APPROVED'";
    List<Object> parameters = new ArrayList<>();
    parameters.add(ClientContext.require());
    if (enforcementPoint != null) {
      sql += " and attributes->>'enforcementPoint'=?";
      parameters.add(enforcementPoint);
    }
    sql += " order by knowledge_key,version desc";
    return jdbc.query(sql, this::map, parameters.toArray());
  }

  public List<KnowledgeObject> discoveryRecall(
      float[] embedding,
      String query,
      String embeddingProvider,
      boolean includeCandidates,
      int limit) {
    String status = includeCandidates ? "" : " and o.lifecycle_status='APPROVED'";
    String vector = vectorLiteral(embedding);
    LinkedHashSet<UUID> ids = new LinkedHashSet<>();
    ids.addAll(
        jdbc
            .queryForList(
                "select o.id from knowledge_objects o join knowledge_embeddings e on e.client_id=o.client_id and e.knowledge_object_id=o.id where o.client_id=?"
                    + status
                    + " and e.embedding_provider=?"
                    + " order by e.embedding <=> ?::vector limit ?",
                UUID.class,
                ClientContext.require(),
                embeddingProvider,
                vector,
                limit)
            .stream()
            .toList());
    ids.addAll(
        jdbc
            .queryForList(
                "select id from knowledge_objects o where client_id=?"
                    + status
                    + " and to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(business_description,'') || ' ' || coalesce(canonical_taxonomy::text,'') || ' ' || coalesce(client_taxonomy::text,'') || ' ' || coalesce(attributes::text,'') || ' ' || coalesce(array_to_string(tags,' '),'')) @@ plainto_tsquery('simple', ?) limit ?",
                UUID.class,
                ClientContext.require(),
                query,
                limit)
            .stream()
            .toList());
    return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
  }

  public List<KnowledgeObject> allForEmbedding(boolean includeCandidates) {
    return search(null, null, null, includeCandidates ? null : "APPROVED", null, null);
  }

  public void updateEmbedding(UUID objectId, float[] embedding, String provider) {
    jdbc.update(
        "insert into knowledge_embeddings(client_id,knowledge_object_id,embedding,embedding_provider) values(?,?,?::vector,?) on conflict (client_id,knowledge_object_id) do update set embedding=excluded.embedding,embedding_provider=excluded.embedding_provider",
        ClientContext.require(),
        objectId,
        vectorLiteral(embedding),
        provider);
  }

  private String vectorLiteral(float[] embedding) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < embedding.length; index++) {
      if (index > 0) result.append(',');
      result.append(embedding[index]);
    }
    return result.append(']').toString();
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

  public List<KnowledgeObject> findDataAssetsByName(String name) {
    return jdbc.query(
        "select * from knowledge_objects where client_id=? and knowledge_type='DATA_ASSET' and lower(name)=lower(?) order by version desc",
        this::map,
        ClientContext.require(),
        name);
  }

  public List<KnowledgeObject> findByGovernedSubject(String subject) {
    return jdbc.query(
        "select * from knowledge_objects where client_id=? and attributes->>'governedSubject'=? order by knowledge_key,version desc",
        this::map,
        ClientContext.require(),
        subject);
  }

  public void addRelationshipIfAbsent(UUID from, String type, UUID to, UUID evidenceId) {
    jdbc.update(
        "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id,evidence_id) values(?,?,?,?,?) on conflict (client_id,from_object_id,relationship_type,to_object_id) do nothing",
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
                rs.getString("conflict_class"),
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
