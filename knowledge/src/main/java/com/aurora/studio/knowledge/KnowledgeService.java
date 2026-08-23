package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.KnowledgeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {
  private static final Map<KnowledgeType, List<String>> REQUIRED_FIELDS =
      Map.of(
          KnowledgeType.MODEL,
          List.of("objective", "scoredEntity", "targetEvent", "predictionHorizon", "cohort"),
          KnowledgeType.FEATURE,
          List.of("businessDefinition", "entity", "observationWindow", "pointInTimeAvailable"),
          KnowledgeType.DATA_ASSET,
          List.of("grain", "primaryKey", "eventTime", "history"),
          KnowledgeType.IMPLEMENTATION,
          List.of("languageOrKind", "sourceTraceability"),
          KnowledgeType.EXPERIMENT,
          List.of("hypothesis", "metrics", "sampleSizes", "decision"),
          KnowledgeType.STANDARD,
          List.of("rule", "enforcementPoint"));

  private final KnowledgeRepository repository;
  private final JdbcTemplate jdbc;

  public KnowledgeService(KnowledgeRepository repository, JdbcTemplate jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Transactional
  public KnowledgeObject create(Draft draft, String actor) {
    validate(draft);
    int version =
        repository.findLatest(draft.knowledgeKey()).map(KnowledgeObject::version).orElse(0) + 1;
    Map<String, Object> breakdown = confidenceBreakdown(draft, 1);
    KnowledgeObject object =
        new KnowledgeObject(
            null,
            ClientContext.require(),
            draft.knowledgeKey(),
            version,
            draft.knowledgeType(),
            draft.name(),
            draft.businessDomain(),
            draft.businessUseCase(),
            draft.businessDescription(),
            draft.canonicalTaxonomy(),
            draft.clientTaxonomy(),
            draft.tags(),
            "EXTRACTED",
            null,
            null,
            confidence(breakdown, false),
            breakdown,
            Map.of("completeness", 1.0),
            actor,
            null,
            null,
            null,
            draft.attributes(),
            draft.synthetic());
    return repository.save(object);
  }

  @Transactional
  public KnowledgeObject submitForReview(UUID id, String actor, String comment) {
    KnowledgeObject object = require(id);
    transition(object, "EXTRACTED", "PENDING_REVIEW", actor, comment);
    audit(object.id(), "EXTRACTED", "PENDING_REVIEW", actor, comment);
    return repository.findById(id).orElseThrow();
  }

  @Transactional
  public KnowledgeObject approve(UUID id, String actor, String comment) {
    KnowledgeObject object = require(id);
    if (!object.lifecycleStatus().equals("PENDING_REVIEW")) {
      throw conflict(object, "PENDING_REVIEW", "APPROVED");
    }
    if (repository.evidence(id).isEmpty()) {
      throw new IllegalStateException("Knowledge object cannot be approved without evidence");
    }
    repository
        .findApproved(object.knowledgeKey())
        .filter(previous -> previous.id().equals(id) == false)
        .filter(previous -> previous.lifecycleStatus().equals("APPROVED"))
        .ifPresent(
            previous -> {
              jdbc.update(
                  "update knowledge_objects set lifecycle_status='SUPERSEDED',effective_to=now() where client_id=? and id=?",
                  ClientContext.require(),
                  previous.id());
              audit(
                  previous.id(),
                  "APPROVED",
                  "SUPERSEDED",
                  actor,
                  "Superseded by version " + object.version());
            });
    jdbc.update(
        "update knowledge_objects set lifecycle_status='APPROVED',approved_at=now(),approved_by=?,approval_comments=?,effective_from=coalesce(effective_from,now()) where client_id=? and id=?",
        actor,
        comment,
        ClientContext.require(),
        id);
    audit(id, "PENDING_REVIEW", "APPROVED", actor, comment);
    return repository.findById(id).orElseThrow();
  }

  @Transactional
  public KnowledgeObject deprecate(UUID id, String actor, String comment) {
    KnowledgeObject object = require(id);
    if (!List.of("APPROVED", "PENDING_REVIEW").contains(object.lifecycleStatus())) {
      throw conflict(object, object.lifecycleStatus(), "DEPRECATED");
    }
    jdbc.update(
        "update knowledge_objects set lifecycle_status='DEPRECATED',effective_to=now() where client_id=? and id=?",
        ClientContext.require(),
        id);
    audit(id, object.lifecycleStatus(), "DEPRECATED", actor, comment);
    return repository.findById(id).orElseThrow();
  }

  public List<KnowledgeObject> search(
      String type,
      String domain,
      String useCase,
      String status,
      String tag,
      String text,
      boolean includeCandidates) {
    String actualStatus = status;
    if (!includeCandidates && actualStatus == null) actualStatus = "APPROVED";
    return repository.search(type, domain, useCase, actualStatus, tag, text);
  }

  public KnowledgePackage get(UUID id, boolean includeCandidates) {
    KnowledgeObject object = require(id);
    if (!includeCandidates && !object.lifecycleStatus().equals("APPROVED")) {
      throw new KnowledgeNotFoundException(id);
    }
    List<KnowledgeEvidence> evidence = repository.evidence(id);
    List<String> warnings =
        repository.conflicts(id).stream()
            .filter(conflict -> conflict.status().name().equals("OPEN"))
            .map(
                conflict ->
                    "Open conflict on field " + conflict.field() + " caps confidence at 0.5")
            .toList();
    return new KnowledgePackage(
        object.id(),
        object.version(),
        object.knowledgeType().name(),
        object.name(),
        object.businessDescription(),
        object.attributes(),
        List.of(),
        evidence,
        repository.relationships(id),
        null,
        List.of(),
        object.confidence(),
        object.confidenceBreakdown(),
        object.lifecycleStatus(),
        object.lifecycleStatus().equals("APPROVED"),
        object.synthetic(),
        warnings,
        repository.conflicts(id),
        null);
  }

  public List<KnowledgeEvidence> getSourceEvidence(UUID id) {
    require(id);
    return repository.evidence(id);
  }

  @Transactional
  public KnowledgeEvidence addEvidence(
      UUID objectId,
      String sourceSystem,
      String sourceType,
      String sourceUri,
      String sourceVersion,
      String excerpt,
      double certainty) {
    KnowledgeObject object = require(objectId);
    UUID evidenceId =
        repository.addEvidence(
            objectId, sourceSystem, sourceType, sourceUri, sourceVersion, excerpt, certainty);
    detectConflicts(object, evidenceId);
    List<KnowledgeEvidence> evidence = repository.evidence(objectId);
    Map<String, Object> breakdown = new HashMap<>(object.confidenceBreakdown());
    breakdown.put("crossSourceAgreement", evidence.size() > 1 ? 1.0 : 0.5);
    double confidence =
        confidence(
            breakdown,
            repository.conflicts(objectId).stream()
                .anyMatch(c -> c.status().name().equals("OPEN")));
    jdbc.update(
        "update knowledge_objects set confidence=?,confidence_breakdown=?::jsonb where client_id=? and id=?",
        confidence,
        json(breakdown),
        ClientContext.require(),
        objectId);
    return repository.evidence(objectId).stream()
        .filter(item -> item.id().equals(evidenceId))
        .findFirst()
        .orElseThrow();
  }

  public List<KnowledgePackage> governanceRules(String enforcementPoint) {
    return search("STANDARD", null, null, "APPROVED", null, enforcementPoint, true).stream()
        .map(object -> get(object.id(), true))
        .toList();
  }

  public Impact analyzeImpact(UUID id, int depth) {
    require(id);
    int boundedDepth = Math.max(0, Math.min(depth, 5));
    List<ImpactPath> paths = new ArrayList<>();
    ArrayDeque<Node> queue = new ArrayDeque<>();
    queue.add(new Node(id, List.of(id), 0));
    Set<UUID> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      if (node.depth() >= boundedDepth) continue;
      for (KnowledgeRelationship relationship : repository.relationships(node.id())) {
        UUID next =
            relationship.fromObjectId().equals(node.id())
                ? relationship.toObjectId()
                : relationship.fromObjectId();
        List<UUID> path = new ArrayList<>(node.path());
        path.add(next);
        paths.add(new ImpactPath(next, relationship.relationshipType().name(), path));
        if (visited.add(next)) queue.add(new Node(next, path, node.depth() + 1));
      }
    }
    return new Impact(id, boundedDepth, paths);
  }

  private void validate(Draft draft) {
    if (draft.knowledgeKey() == null || draft.knowledgeKey().isBlank())
      throw new IllegalArgumentException("knowledgeKey is required");
    if (draft.name() == null || draft.name().isBlank())
      throw new IllegalArgumentException("name is required");
    List<String> required = REQUIRED_FIELDS.get(draft.knowledgeType());
    for (String field : required) {
      if (!draft.attributes().containsKey(field) || draft.attributes().get(field) == null) {
        throw new IllegalArgumentException(
            "attributes." + field + " is required for " + draft.knowledgeType());
      }
    }
  }

  private Map<String, Object> confidenceBreakdown(Draft draft, int evidenceCount) {
    Map<String, Object> result = new HashMap<>();
    result.put("sourceReliability", draft.synthetic() ? 0.25 : 0.75);
    result.put("crossSourceAgreement", evidenceCount > 1 ? 1.0 : 0.5);
    result.put("extractionCertainty", 0.9);
    result.put("completeness", 1.0);
    result.put("recency", 1.0);
    result.put(
        "executionEvidence", draft.knowledgeType() == KnowledgeType.IMPLEMENTATION ? 0.5 : 0.0);
    return result;
  }

  private double confidence(Map<String, Object> breakdown, boolean conflict) {
    double value =
        0.25 * number(breakdown.get("sourceReliability"))
            + 0.20 * number(breakdown.get("crossSourceAgreement"))
            + 0.20 * number(breakdown.get("extractionCertainty"))
            + 0.15 * number(breakdown.get("completeness"))
            + 0.10 * number(breakdown.get("recency"))
            + 0.10 * number(breakdown.get("executionEvidence"));
    return conflict ? Math.min(0.5, value) : Math.max(0, Math.min(1, value));
  }

  private double number(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  private KnowledgeObject require(UUID id) {
    return repository.findById(id).orElseThrow(() -> new KnowledgeNotFoundException(id));
  }

  private void transition(
      KnowledgeObject object, String from, String to, String actor, String comment) {
    if (!object.lifecycleStatus().equals(from)) throw conflict(object, from, to);
    jdbc.update(
        "update knowledge_objects set lifecycle_status=? where client_id=? and id=?",
        to,
        ClientContext.require(),
        object.id());
  }

  private KnowledgeConflictException conflict(KnowledgeObject object, String from, String to) {
    return new KnowledgeConflictException(
        "Illegal knowledge lifecycle transition " + from + " -> " + to + " for " + object.id());
  }

  private void audit(UUID id, String from, String to, String actor, String comment) {
    jdbc.update(
        "insert into knowledge_audit(client_id,knowledge_object_id,from_status,to_status,actor,comment) values(?,?,?,?,?,?)",
        ClientContext.require(),
        id,
        from,
        to,
        actor,
        comment);
  }

  private String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid confidence breakdown", exception);
    }
  }

  private void detectConflicts(KnowledgeObject object, UUID evidenceId) {
    List<String> fields = REQUIRED_FIELDS.get(object.knowledgeType());
    for (KnowledgeObject other :
        repository.findByKeyExcluding(object.knowledgeKey(), object.id())) {
      for (String field : fields) {
        Object currentValue = object.attributes().get(field);
        Object otherValue = other.attributes().get(field);
        if (currentValue == null
            || otherValue == null
            || String.valueOf(currentValue).equals(String.valueOf(otherValue))) {
          continue;
        }
        Integer count =
            jdbc.queryForObject(
                "select count(*) from knowledge_conflicts where client_id=? and knowledge_object_id=? and field=? and status='OPEN'",
                Integer.class,
                ClientContext.require(),
                object.id(),
                field);
        if (count == 0) {
          jdbc.update(
              "insert into knowledge_conflicts(client_id,knowledge_object_id,field,values,status) values(?,?,?,?::jsonb,'OPEN')",
              ClientContext.require(),
              object.id(),
              field,
              json(
                  Map.of(
                      "current", Map.of("value", currentValue, "evidenceId", evidenceId),
                      "other", Map.of("value", otherValue, "objectId", other.id()))));
        }
      }
    }
  }

  public record Draft(
      String knowledgeKey,
      KnowledgeType knowledgeType,
      String name,
      String businessDomain,
      String businessUseCase,
      String businessDescription,
      Map<String, Object> canonicalTaxonomy,
      Map<String, Object> clientTaxonomy,
      List<String> tags,
      Map<String, Object> attributes,
      boolean synthetic) {}

  public record Impact(UUID root, int depth, List<ImpactPath> paths) {}

  public record ImpactPath(UUID objectId, String relationshipType, List<UUID> path) {}

  private record Node(UUID id, List<UUID> path, int depth) {}
}
