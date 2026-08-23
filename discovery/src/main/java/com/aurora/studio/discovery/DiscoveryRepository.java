package com.aurora.studio.discovery;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DiscoveryRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public DiscoveryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public UUID saveRequirement(ModelRequirement requirement) {
    return jdbc.queryForObject(
        "insert into discovery_requirements(client_id,requirement) values(?,?::jsonb) returning id",
        UUID.class,
        ClientContext.require(),
        json(requirement));
  }

  public Optional<ModelRequirement> findRequirement(UUID id) {
    return jdbc
        .query(
            "select requirement from discovery_requirements where client_id=? and id=?",
            (rs, row) -> readRequirement(rs.getString("requirement")),
            ClientContext.require(),
            id)
        .stream()
        .findFirst();
  }

  public Optional<UUID> findRequirementByUseCase(String businessUseCase) {
    return jdbc
        .query(
            "select id from discovery_requirements where client_id=? and requirement->>'businessUseCase'=? order by created_at limit 1",
            (rs, row) -> rs.getObject("id", UUID.class),
            ClientContext.require(),
            businessUseCase)
        .stream()
        .findFirst();
  }

  public UUID saveRun(
      UUID runId,
      UUID requirementId,
      boolean includeCandidates,
      Map<String, Double> weights,
      String embeddingProvider,
      Object result) {
    return jdbc.queryForObject(
        "insert into discovery_runs(id,client_id,requirement_id,include_candidates,weights,embedding_provider,result) values(?,?,?, ?,?::jsonb,?,?::jsonb) returning id",
        UUID.class,
        runId,
        ClientContext.require(),
        requirementId,
        includeCandidates,
        json(weights),
        embeddingProvider,
        json(result));
  }

  public Optional<Map<String, Object>> findRun(UUID id) {
    return jdbc
        .query(
            "select result from discovery_runs where client_id=? and id=?",
            (rs, row) -> readMap(rs.getString("result")),
            ClientContext.require(),
            id)
        .stream()
        .findFirst();
  }

  private ModelRequirement readRequirement(String value) {
    try {
      return mapper.readValue(value, ModelRequirement.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid discovery requirement", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMap(String value) {
    try {
      return mapper.readValue(value, Map.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("invalid discovery result", exception);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid discovery JSON", exception);
    }
  }
}
