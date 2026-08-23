package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.common.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
          List.of("language", "sourceTraceability"),
          KnowledgeType.EXPERIMENT,
          List.of("hypothesis", "metrics", "sampleSizes", "decision"),
          KnowledgeType.STANDARD,
          List.of("rule", "enforcementPoint"));
  private static final Map<KnowledgeType, List<String>> RECOMMENDED_FIELDS =
      Map.of(
          KnowledgeType.MODEL, List.of("features", "weights", "bias"),
          KnowledgeType.FEATURE, List.of("inputs", "sourceConstraints"),
          KnowledgeType.DATA_ASSET, List.of("retention", "partitioning", "accessPolicy"),
          KnowledgeType.IMPLEMENTATION, List.of("runtime", "owner"),
          KnowledgeType.EXPERIMENT, List.of("population", "duration", "guard"),
          KnowledgeType.STANDARD, List.of("rationale", "enforcementEvidence"));

  private final KnowledgeRepository repository;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ConfidenceWeights weights;
  private final List<KnowledgeEmbeddingWriter> embeddingWriters;

  public KnowledgeService(
      KnowledgeRepository repository,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      ConfidenceWeights weights) {
    this(repository, jdbc, mapper, weights, Collections.emptyList());
  }

  @Autowired
  public KnowledgeService(
      KnowledgeRepository repository,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      ConfidenceWeights weights,
      List<KnowledgeEmbeddingWriter> embeddingWriters) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.weights = weights;
    this.embeddingWriters = embeddingWriters;
  }

  @Transactional
  public KnowledgeObject create(Draft draft, String actor) {
    requireActor(actor);
    return create(draft, actor, true);
  }

  private KnowledgeObject create(Draft draft, String actor, boolean requireComplete) {
    validate(draft, requireComplete);
    int version =
        repository.findLatest(draft.knowledgeKey()).map(KnowledgeObject::version).orElse(0) + 1;
    Map<String, Object> breakdown =
        confidenceBreakdown(
            draft.knowledgeKey(), draft.knowledgeType(), draft.attributes(), List.of());
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
            qualityAssessment(breakdown),
            null,
            actor,
            null,
            null,
            null,
            draft.attributes(),
            draft.synthetic());
    KnowledgeObject saved = repository.save(object);
    embeddingWriters.forEach(writer -> writer.write(saved));
    return saved;
  }

  @Transactional
  public KnowledgeObject createExtracted(Draft draft, String actor, UUID invocationId) {
    KnowledgeObject object = create(draft, actor, false);
    repository.linkInvocation(object.id(), invocationId);
    return repository.findById(object.id()).orElseThrow();
  }

  @Transactional
  public KnowledgeObject submitForReview(UUID id, String actor, String comment) {
    requireActor(actor);
    KnowledgeObject object = require(id);
    transition(object, "EXTRACTED", "PENDING_REVIEW");
    audit(object.id(), "EXTRACTED", "PENDING_REVIEW", actor, comment);
    return repository.findById(id).orElseThrow();
  }

  @Transactional
  public KnowledgeObject approve(UUID id, String actor, String comment) {
    requireActor(actor);
    KnowledgeObject object = require(id);
    if (!object.lifecycleStatus().equals("PENDING_REVIEW")) {
      throw conflict(object, "PENDING_REVIEW", "APPROVED");
    }
    if (repository.evidence(id).isEmpty()) {
      throw new IllegalStateException("Knowledge object cannot be approved without evidence");
    }
    List<String> missing = missingRequiredFields(object.knowledgeType(), object.attributes());
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Knowledge object cannot be approved; missing required fields: "
              + String.join(", ", missing));
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
    requireActor(actor);
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

  private void requireActor(String actor) {
    if (actor == null || actor.isBlank()) {
      throw new ValidationException("actor is required");
    }
  }

  public List<KnowledgeObject> search(
      String type,
      String domain,
      String useCase,
      String status,
      String tag,
      String text,
      boolean includeCandidates) {
    if (!includeCandidates && status != null && !"APPROVED".equals(status)) {
      throw new IllegalArgumentException("Non-approved search requires includeCandidates=true");
    }
    String actualStatus = status;
    if (!includeCandidates) actualStatus = "APPROVED";
    return repository.search(type, domain, useCase, actualStatus, tag, text);
  }

  public KnowledgePackage get(UUID id, boolean includeCandidates) {
    KnowledgeObject object = requireVisible(id, includeCandidates);
    List<KnowledgeEvidence> evidence = repository.evidence(id);
    List<KnowledgeRelationship> relationships = repository.relationships(id);
    List<KnowledgeObject> implementations =
        relationships.stream()
            .filter(relationship -> relationship.relationshipType().name().equals("IMPLEMENTED_BY"))
            .map(
                relationship ->
                    relationship.fromObjectId().equals(id)
                        ? relationship.toObjectId()
                        : relationship.fromObjectId())
            .map(repository::findById)
            .flatMap(Optional::stream)
            .toList();
    List<String> warnings =
        repository.conflicts(id).stream()
            .filter(conflict -> conflict.status().name().equals("OPEN") && conflict.blocking())
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
        implementations,
        evidence,
        repository.fieldProvenance(id),
        relationships,
        null,
        constraints(object),
        object.confidence(),
        object.confidenceBreakdown(),
        object.llmInvocationId(),
        object.lifecycleStatus(),
        object.lifecycleStatus().equals("APPROVED"),
        object.synthetic(),
        warnings,
        repository.conflicts(id),
        null);
  }

  public List<KnowledgeEvidence> getSourceEvidence(UUID id, boolean includeCandidates) {
    requireVisible(id, includeCandidates);
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
    Map<String, Object> breakdown =
        confidenceBreakdown(
            object.knowledgeKey(), object.knowledgeType(), object.attributes(), evidence);
    double confidence =
        confidence(
            breakdown,
            repository.conflicts(objectId).stream()
                .anyMatch(c -> c.status().name().equals("OPEN") && c.blocking()));
    jdbc.update(
        "update knowledge_objects set confidence=?,confidence_breakdown=?::jsonb,quality_assessment=?::jsonb where client_id=? and id=?",
        confidence,
        json(breakdown),
        json(qualityAssessment(breakdown)),
        ClientContext.require(),
        objectId);
    return repository.evidence(objectId).stream()
        .filter(item -> item.id().equals(evidenceId))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void addFieldProvenance(
      UUID objectId,
      String fieldName,
      Object value,
      String provenance,
      UUID evidenceId,
      String excerpt,
      double certainty) {
    repository.saveFieldProvenance(
        new FieldProvenance(
            null,
            ClientContext.require(),
            objectId,
            fieldName,
            value,
            provenance,
            evidenceId,
            excerpt,
            certainty));
  }

  @Transactional
  public void linkReferencedDataAssets(UUID objectId, List<String> tableNames, UUID evidenceId) {
    if (tableNames == null || tableNames.isEmpty()) return;
    for (String tableName : tableNames) {
      if (tableName == null || tableName.isBlank()) continue;
      repository
          .findDataAssetsByName(tableName)
          .forEach(
              dataAsset ->
                  repository.addRelationshipIfAbsent(
                      objectId, "DERIVED_FROM", dataAsset.id(), evidenceId));
    }
  }

  @Transactional
  public void linkGovernedArtifacts(
      UUID objectId, String governedSubject, String governedRole, UUID evidenceId) {
    if (governedSubject == null
        || governedSubject.isBlank()
        || governedRole == null
        || governedRole.isBlank()) {
      return;
    }
    KnowledgeObject object = require(objectId);
    for (KnowledgeObject related : repository.findByGovernedSubject(governedSubject)) {
      if (related.id().equals(objectId)) continue;
      String relatedRole = String.valueOf(related.attributes().get("governedRole"));
      if ("IMPLEMENTATION".equals(governedRole) && "SPECIFICATION".equals(relatedRole)) {
        repository.addRelationshipIfAbsent(objectId, "GOVERNED_BY", related.id(), evidenceId);
      } else if ("SPECIFICATION".equals(governedRole) && "IMPLEMENTATION".equals(relatedRole)) {
        repository.addRelationshipIfAbsent(related.id(), "GOVERNED_BY", objectId, evidenceId);
      }
    }
    if (evidenceId != null) {
      detectConflicts(object, evidenceId);
    } else {
      repository.evidence(objectId).stream()
          .max(java.util.Comparator.comparing(KnowledgeEvidence::recordedAt))
          .ifPresent(evidence -> detectConflicts(object, evidence.id()));
    }
  }

  @Transactional
  public void linkRegisteredFeatureImplementations(UUID implementationId) {
    Map<String, KnowledgeObject> latestByKey = new LinkedHashMap<>();
    for (KnowledgeObject feature : repository.search("FEATURE", null, null, null, null, null)) {
      latestByKey.merge(feature.knowledgeKey(), feature, this::preferredFeature);
    }
    for (KnowledgeObject feature : latestByKey.values()) {
      repository.addRelationshipIfAbsent(feature.id(), "IMPLEMENTED_BY", implementationId, null);
    }
  }

  private KnowledgeObject preferredFeature(KnowledgeObject current, KnowledgeObject candidate) {
    if (current.lifecycleStatus().equals("APPROVED")) return current;
    if (candidate.lifecycleStatus().equals("APPROVED")) return candidate;
    return current.version() >= candidate.version() ? current : candidate;
  }

  public List<KnowledgePackage> governanceRules(String enforcementPoint) {
    return repository.searchGovernanceRules(enforcementPoint).stream()
        .map(object -> get(object.id(), true))
        .toList();
  }

  public Impact analyzeImpact(UUID id, int depth, boolean includeCandidates) {
    requireVisible(id, includeCandidates);
    int boundedDepth = Math.max(0, Math.min(depth, 5));
    List<ImpactPath> dependsOn = new ArrayList<>();
    List<ImpactPath> dependents = new ArrayList<>();
    ArrayDeque<Node> queue = new ArrayDeque<>();
    queue.add(new Node(id, List.of(id), 0));
    Set<UUID> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      if (node.depth() >= boundedDepth) continue;
      for (KnowledgeRelationship relationship : repository.relationships(node.id())) {
        boolean pointsAway = relationship.fromObjectId().equals(node.id());
        UUID next = pointsAway ? relationship.toObjectId() : relationship.fromObjectId();
        List<UUID> path = new ArrayList<>(node.path());
        path.add(next);
        ImpactPath impactPath =
            new ImpactPath(
                next,
                relationship.relationshipType().name(),
                pointsAway ? "DEPENDS_ON" : "DEPENDENT",
                path);
        if (pointsAway) dependsOn.add(impactPath);
        else dependents.add(impactPath);
        if (visited.add(next)) queue.add(new Node(next, path, node.depth() + 1));
      }
    }
    return new Impact(id, boundedDepth, dependsOn, dependents);
  }

  private void validate(Draft draft) {
    validate(draft, true);
  }

  private void validate(Draft draft, boolean requireComplete) {
    if (draft.knowledgeKey() == null || draft.knowledgeKey().isBlank())
      throw new ValidationException("knowledgeKey is required");
    if (draft.name() == null || draft.name().isBlank())
      throw new ValidationException("name is required");
    List<String> required = REQUIRED_FIELDS.get(draft.knowledgeType());
    if (requireComplete) {
      for (String field : required) {
        if (!draft.attributes().containsKey(field) || draft.attributes().get(field) == null) {
          throw new ValidationException(
              "attributes." + field + " is required for " + draft.knowledgeType());
        }
      }
    }
  }

  private Map<String, Object> confidenceBreakdown(
      String knowledgeKey,
      KnowledgeType type,
      Map<String, Object> attributes,
      List<KnowledgeEvidence> evidence) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceReliability", sourceReliability(evidence));
    result.put("crossSourceAgreement", crossSourceAgreement(evidence));
    result.put("extractionCertainty", extractionCertainty(evidence));
    result.put("completeness", completeness(type, attributes));
    result.put("recency", recency(knowledgeKey, evidence));
    result.put("executionEvidence", executionEvidence(attributes, evidence));
    result.put("unknownFields", unknownFields(type, attributes));
    return result;
  }

  private Map<String, Object> qualityAssessment(Map<String, Object> breakdown) {
    Map<String, Object> quality = new LinkedHashMap<>();
    quality.put("completeness", breakdown.get("completeness"));
    return quality;
  }

  private double confidence(Map<String, Object> breakdown, boolean conflict) {
    Map<String, Double> configured =
        Map.of(
            "sourceReliability", weights.sourceReliability(),
            "crossSourceAgreement", weights.crossSourceAgreement(),
            "extractionCertainty", weights.extractionCertainty(),
            "completeness", weights.completeness(),
            "recency", weights.recency(),
            "executionEvidence", weights.executionEvidence());
    double weighted = 0;
    double availableWeight = 0;
    for (Map.Entry<String, Double> entry : configured.entrySet()) {
      Double signal = decimal(breakdown.get(entry.getKey()));
      if (signal != null) {
        weighted += entry.getValue() * signal;
        availableWeight += entry.getValue();
      }
    }
    double value = availableWeight == 0 ? 0 : weighted / availableWeight;
    return conflict ? Math.min(0.5, value) : Math.max(0, Math.min(1, value));
  }

  private Double decimal(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private Double sourceReliability(List<KnowledgeEvidence> evidence) {
    if (evidence.isEmpty()) return null;
    List<Double> values =
        evidence.stream().map(item -> sourceReliability(item.sourceType())).toList();
    OptionalDouble average =
        values.stream().filter(value -> value != null).mapToDouble(Double::doubleValue).average();
    return average.isPresent() ? average.getAsDouble() : null;
  }

  private Double sourceReliability(String sourceType) {
    String normalized = sourceType.toLowerCase();
    if (normalized.contains("registry") || normalized.contains("executable")) return 1.0;
    if (normalized.contains("source-file") || normalized.contains("production-code")) return 0.85;
    if (normalized.contains("document")) return 0.65;
    if (normalized.contains("draft")) return 0.3;
    return null;
  }

  private Double crossSourceAgreement(List<KnowledgeEvidence> evidence) {
    if (evidence.size() < 2) return null;
    return (double) evidence.stream().map(KnowledgeEvidence::sourceSystem).distinct().count()
        / evidence.size();
  }

  private Double extractionCertainty(List<KnowledgeEvidence> evidence) {
    if (evidence.isEmpty()) return null;
    return evidence.stream()
        .mapToDouble(KnowledgeEvidence::extractionCertainty)
        .average()
        .orElse(0);
  }

  private double completeness(KnowledgeType type, Map<String, Object> attributes) {
    List<String> fields = new ArrayList<>(REQUIRED_FIELDS.get(type));
    fields.addAll(RECOMMENDED_FIELDS.get(type));
    long populated =
        fields.stream()
            .filter(field -> attributes.get(field) != null)
            .filter(field -> !(attributes.get(field) instanceof String value) || !value.isBlank())
            .count();
    return (double) populated / fields.size();
  }

  private List<String> unknownFields(KnowledgeType type, Map<String, Object> attributes) {
    List<String> fields = new ArrayList<>(REQUIRED_FIELDS.get(type));
    fields.addAll(RECOMMENDED_FIELDS.get(type));
    return fields.stream().filter(field -> attributes.get(field) == null).toList();
  }

  private List<String> missingRequiredFields(KnowledgeType type, Map<String, Object> attributes) {
    return REQUIRED_FIELDS.get(type).stream()
        .filter(
            field -> {
              Object value = attributes.get(field);
              return value == null || (value instanceof String text && text.isBlank());
            })
        .toList();
  }

  private Double recency(String knowledgeKey, List<KnowledgeEvidence> evidence) {
    if (evidence.isEmpty()) return null;
    Instant latest =
        evidence.stream().map(KnowledgeEvidence::recordedAt).max(Instant::compareTo).orElseThrow();
    Optional<Instant> newestForKey = repository.newestEvidenceForKey(knowledgeKey);
    if (newestForKey == null || newestForKey.isEmpty()) return null;
    double ageDays =
        Math.max(0, Duration.between(latest, newestForKey.orElseThrow()).toSeconds() / 86400.0);
    return Math.max(0, 1 - Math.min(1, ageDays / 365));
  }

  private List<String> constraints(KnowledgeObject object) {
    Map<String, Object> attributes = object.attributes();
    List<String> result = new ArrayList<>();
    if (object.knowledgeType() == KnowledgeType.FEATURE) {
      Object consent = attributes.get("consentRequirement");
      if (consent == null) consent = attributes.get("sourceConstraints");
      addConstraint(result, "Consent required", consent);
      addConstraint(result, "Observation window", attributes.get("observationWindow"));
      addConstraint(result, "Point-in-time available", attributes.get("pointInTimeAvailable"));
      addConstraint(result, "Restricted usage", attributes.get("restrictedUsage"));
    } else if (object.knowledgeType() == KnowledgeType.STANDARD) {
      addConstraint(result, "Enforcement point", attributes.get("enforcementPoint"));
    } else if (object.knowledgeType() == KnowledgeType.DATA_ASSET) {
      addConstraint(result, "Governance", attributes.get("governance"));
      addConstraint(result, "History", attributes.get("history"));
      addConstraint(result, "Retention", attributes.get("retention"));
      addConstraint(result, "Access policy", attributes.get("accessPolicy"));
      addConstraint(result, "Quality limits", attributes.get("qualityLimits"));
    } else {
      addConstraint(result, "Prediction horizon", attributes.get("predictionHorizon"));
      addConstraint(result, "Cohort", attributes.get("cohort"));
    }
    return result;
  }

  private void addConstraint(List<String> constraints, String label, Object value) {
    if (value != null) constraints.add(label + ": " + value);
  }

  private double executionEvidence(
      Map<String, Object> attributes, List<KnowledgeEvidence> evidence) {
    Object declared = attributes.get("executionEvidence");
    if (declared instanceof Boolean value && value) return 1.0;
    return evidence.stream().anyMatch(item -> item.sourceType().toLowerCase().contains("execution"))
        ? 1.0
        : 0.0;
  }

  private KnowledgeObject require(UUID id) {
    return repository.findById(id).orElseThrow(() -> new KnowledgeNotFoundException(id));
  }

  private KnowledgeObject requireVisible(UUID id, boolean includeCandidates) {
    KnowledgeObject object = require(id);
    if (!includeCandidates && !object.lifecycleStatus().equals("APPROVED")) {
      throw new KnowledgeNotFoundException(id);
    }
    return object;
  }

  private void transition(KnowledgeObject object, String from, String to) {
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
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid confidence breakdown", exception);
    }
  }

  private void detectConflicts(KnowledgeObject object, UUID evidenceId) {
    Set<UUID> compared = new HashSet<>();
    List<KnowledgeObject> others = new ArrayList<>();
    for (KnowledgeObject other :
        repository.findByKeyExcluding(object.knowledgeKey(), object.id())) {
      if (compared.add(other.id())) others.add(other);
    }
    for (KnowledgeRelationship relationship : repository.relationships(object.id())) {
      if (relationship.relationshipType()
          != com.aurora.studio.common.RelationshipType.GOVERNED_BY) {
        continue;
      }
      UUID otherId =
          relationship.fromObjectId().equals(object.id())
              ? relationship.toObjectId()
              : relationship.fromObjectId();
      repository.findById(otherId).filter(other -> compared.add(other.id())).ifPresent(others::add);
    }
    for (KnowledgeObject other : others) {
      List<String> fields = comparableConflictFields(object, other);
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
              "insert into knowledge_conflicts(client_id,knowledge_object_id,field,conflict_class,values,status) values(?,?,?,?,?::jsonb,'OPEN')",
              ClientContext.require(),
              object.id(),
              field,
              conflictClass(field),
              json(
                  Map.of(
                      "current", Map.of("value", currentValue, "evidenceId", evidenceId),
                      "other", Map.of("value", otherValue, "objectId", other.id()))));
        }
      }
    }
  }

  private String conflictClass(String field) {
    return field.equals("businessDefinition") ? "DIVERGENT_DESCRIPTION" : "BLOCKING";
  }

  private List<String> comparableConflictFields(KnowledgeObject object, KnowledgeObject other) {
    Set<String> fields = new LinkedHashSet<>();
    fields.addAll(REQUIRED_FIELDS.get(object.knowledgeType()));
    fields.addAll(REQUIRED_FIELDS.get(other.knowledgeType()));
    fields.addAll(
        List.of(
            "businessDefinition",
            "entity",
            "observationWindow",
            "targetEvent",
            "predictionHorizon",
            "grain",
            "measurementUnit",
            "implementationKind"));
    return fields.stream().toList();
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

  public record Impact(
      UUID root, int depth, List<ImpactPath> dependsOn, List<ImpactPath> dependents) {}

  public record ImpactPath(
      UUID objectId, String relationshipType, String direction, List<UUID> path) {}

  private record Node(UUID id, List<UUID> path, int depth) {}
}
