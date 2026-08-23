package com.aurora.studio.discovery;

import com.aurora.studio.gateway.LlmGateway;
import com.aurora.studio.gateway.LlmOutcome;
import com.aurora.studio.gateway.LlmRequest;
import com.aurora.studio.gateway.LlmResult;
import com.aurora.studio.gateway.RedactionPolicy;
import com.aurora.studio.knowledge.KnowledgeEvidence;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgePackage;
import com.aurora.studio.knowledge.KnowledgeRelationship;
import com.aurora.studio.knowledge.KnowledgeRepository;
import com.aurora.studio.knowledge.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoveryService {
  private static final double RECALL_FLOOR = 0.20;
  private static final double REUSE_THRESHOLD = 0.80;
  private static final Map<String, Object> EXPLANATION_SCHEMA =
      Map.of(
          "$id",
          "aurora.discovery.explanation.v1",
          "type",
          "object",
          "required",
          List.of("explanation"),
          "properties",
          Map.of("explanation", Map.of("type", "string")));

  private final KnowledgeService knowledge;
  private final KnowledgeRepository knowledgeRepository;
  private final DiscoveryRepository repository;
  private final EmbeddingProvider embeddings;
  private final LlmGateway gateway;
  private final DiscoveryWeights weights;
  private final ObjectMapper mapper;

  public DiscoveryService(
      KnowledgeService knowledge,
      KnowledgeRepository knowledgeRepository,
      DiscoveryRepository repository,
      EmbeddingProvider embeddings,
      LlmGateway gateway,
      DiscoveryWeights weights) {
    this(
        knowledge,
        knowledgeRepository,
        repository,
        embeddings,
        gateway,
        weights,
        new ObjectMapper());
  }

  @Autowired
  public DiscoveryService(
      KnowledgeService knowledge,
      KnowledgeRepository knowledgeRepository,
      DiscoveryRepository repository,
      EmbeddingProvider embeddings,
      LlmGateway gateway,
      DiscoveryWeights weights,
      ObjectMapper mapper) {
    this.knowledge = knowledge;
    this.knowledgeRepository = knowledgeRepository;
    this.repository = repository;
    this.embeddings = embeddings;
    this.gateway = gateway;
    this.weights = weights;
    this.mapper = mapper;
  }

  @Transactional
  public UUID register(ModelRequirement requirement) {
    validate(requirement);
    return repository.saveRequirement(requirement);
  }

  public ModelRequirement getRequirement(UUID requirementId) {
    return repository
        .findRequirement(requirementId)
        .orElseThrow(() -> new IllegalArgumentException("Discovery requirement was not found"));
  }

  public Optional<UUID> findRequirementByUseCase(String businessUseCase) {
    return repository.findRequirementByUseCase(businessUseCase);
  }

  @Transactional
  public DiscoveryRun run(UUID requirementId, boolean includeCandidates) {
    ModelRequirement requirement =
        repository
            .findRequirement(requirementId)
            .orElseThrow(() -> new IllegalArgumentException("Discovery requirement was not found"));
    List<KnowledgeObject> visible =
        knowledge.search(null, null, null, null, null, null, includeCandidates);
    Embedding requestEmbedding = embeddings.embed(requirementText(requirement));
    List<KnowledgeObject> recalled =
        knowledgeRepository.discoveryRecall(
            requestEmbedding.vector(),
            requirementText(requirement),
            requestEmbedding.provider(),
            includeCandidates,
            Math.min(100, Math.max(20, visible.size())));
    Set<UUID> recalledIds =
        recalled.stream().map(KnowledgeObject::id).collect(java.util.stream.Collectors.toSet());
    List<KnowledgeObject> candidates =
        visible.stream().filter(object -> recalledIds.contains(object.id())).toList();
    List<String> missingObservables = missingObservables(requirement, visible);
    List<DiscoveryCandidate> ranked =
        candidates.stream()
            .map(object -> rank(requirement, object, visible, includeCandidates))
            .sorted(
                java.util.Comparator.comparing(
                        DiscoveryCandidate::compositeScore,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                    .thenComparing(DiscoveryCandidate::knowledgeKey))
            .toList();
    List<String> runBlockers = new ArrayList<>(missingObservables);
    String classification = overallClassification(ranked, runBlockers);
    List<String> reasons = overallReasons(ranked, missingObservables);
    UUID runId = UUID.randomUUID();
    DiscoveryRun draft =
        new DiscoveryRun(
            runId,
            requirementId,
            includeCandidates,
            requestEmbedding.provider(),
            classification,
            reasons,
            runBlockers,
            ranked);
    UUID id =
        repository.saveRun(
            runId,
            requirementId,
            includeCandidates,
            weights.weights(),
            requestEmbedding.provider(),
            draft);
    return new DiscoveryRun(
        id,
        requirementId,
        includeCandidates,
        requestEmbedding.provider(),
        classification,
        reasons,
        runBlockers,
        ranked);
  }

  public DiscoveryRun get(UUID id) {
    Map<String, Object> result =
        repository
            .findRun(id)
            .orElseThrow(() -> new IllegalArgumentException("Discovery run was not found"));
    return mapper.convertValue(result, DiscoveryRun.class);
  }

  public int backfillEmbeddings(boolean includeCandidates) {
    List<KnowledgeObject> objects =
        knowledge.search(null, null, null, null, null, null, includeCandidates);
    for (KnowledgeObject object : objects) {
      Embedding embedding = embeddings.embed(searchText(object));
      knowledgeRepository.updateEmbedding(object.id(), embedding.vector(), embedding.provider());
    }
    return objects.size();
  }

  private DiscoveryCandidate rank(
      ModelRequirement requirement,
      KnowledgeObject object,
      List<KnowledgeObject> visible,
      boolean includeCandidates) {
    KnowledgePackage pack = knowledge.get(object.id(), includeCandidates);
    Map<String, Double> scorecard = new LinkedHashMap<>();
    scorecard.put("targetAlignment", targetAlignment(requirement, object));
    scorecard.put("populationAlignment", populationAlignment(requirement, object));
    scorecard.put("horizonAlignment", horizonAlignment(requirement, object));
    scorecard.put(
        "featureAvailability",
        featureAvailability(requirement, object, visible, pack, includeCandidates));
    scorecard.put("dataAvailability", dataAvailability(object, includeCandidates));
    scorecard.put(
        "implementationAvailability",
        object.knowledgeType().name().equals("MODEL")
            ? (pack.implementations().isEmpty() ? 0.0 : 1.0)
            : null);
    scorecard.put("evidenceStrength", object.confidence());
    scorecard.put(
        "executionEvidence", number(object.confidenceBreakdown().get("executionEvidence")));
    Double composite = composite(scorecard);
    List<String> blockers = new ArrayList<>();
    if (!pack.conflicts().stream()
        .filter(conflict -> conflict.status().name().equals("OPEN") && conflict.blocking())
        .toList()
        .isEmpty()) {
      blockers.add("OPEN_CONFLICT");
    }
    if (pack.evidence().isEmpty()) blockers.add("NO_EVIDENCE");
    if (!includeCandidates && !object.trusted()) blockers.add("UNAPPROVED_KNOWLEDGE");
    if (object.synthetic() && !requirement.syntheticEvidenceAllowed()) {
      blockers.add("SYNTHETIC_EVIDENCE_ONLY");
    }
    List<String> gaps = gaps(requirement, object, visible, pack, scorecard);
    String classification;
    List<String> reasons = new ArrayList<>();
    if (!blockers.isEmpty()) {
      classification = "NOT_RECOMMENDED";
      reasons.addAll(blockers);
    } else if (composite == null || composite < RECALL_FLOOR) {
      classification = "GENERATE";
      reasons.add("BELOW_RECALL_FLOOR");
    } else if (gaps.isEmpty() && clearsReuse(scorecard)) {
      classification = "REUSE";
      reasons.add("ALL_REQUIRED_DIMENSIONS_CLEAR");
    } else {
      classification = "ADAPT";
      reasons.add("GAPS_REQUIRE_ADAPTATION");
    }
    String explanation = explanation(object, classification, reasons, scorecard, pack, blockers);
    List<DiscoveryEvidence> evidence =
        pack.evidence().stream()
            .map(
                item ->
                    new DiscoveryEvidence(
                        item.id(), item.sourceUri(), item.excerpt(), object.synthetic()))
            .toList();
    return new DiscoveryCandidate(
        object.id(),
        object.knowledgeKey(),
        object.knowledgeType().name(),
        object.name(),
        object.synthetic(),
        object.trusted(),
        scorecard,
        composite,
        classification,
        reasons,
        blockers,
        gaps,
        evidence,
        explanation);
  }

  private String explanation(
      KnowledgeObject object,
      String classification,
      List<String> reasons,
      Map<String, Double> scorecard,
      KnowledgePackage pack,
      List<String> blockers) {
    String deterministic =
        "Candidate "
            + object.id()
            + " is classified "
            + classification
            + " for reasons "
            + reasons
            + ". Evidence "
            + pack.evidence().stream().map(KnowledgeEvidence::id).toList()
            + (object.synthetic() ? " is synthetic." : " is real.");
    Map<String, Object> inputs =
        Map.of(
            "scorecard",
            scorecard,
            "classification",
            classification,
            "reasonCodes",
            reasons,
            "candidateReferences",
            List.of(object.id().toString()),
            "evidenceExcerpts",
            pack.evidence().stream().map(KnowledgeEvidence::excerpt).toList(),
            "deterministicExplanation",
            deterministic);
    LlmResult result =
        gateway.complete(
            new LlmRequest(
                "discovery-explanation-" + object.id(),
                "discovery-explanation",
                "1",
                inputs,
                EXPLANATION_SCHEMA,
                300,
                Duration.ofSeconds(10),
                RedactionPolicy.extractionDefault(),
                "Explain only the supplied scorecard and evidence."));
    if (result.outcome() != LlmOutcome.OK) return null;
    Object draft = result.payload().get("explanation");
    if (!(draft instanceof String text)
        || !validReferences(text, Set.of(object.id().toString()))
        || !validNumbers(text, scorecard)) return null;
    return text;
  }

  static boolean validReferences(String text, Set<String> suppliedObjectIds) {
    Matcher matcher =
        Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
            .matcher(text);
    while (matcher.find()) if (!suppliedObjectIds.contains(matcher.group())) return false;
    return true;
  }

  static boolean validNumbers(String text, Map<String, Double> scorecard) {
    String withoutIds =
        text.replaceAll(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "");
    Set<String> allowed = new LinkedHashSet<>();
    scorecard.values().stream()
        .filter(java.util.Objects::nonNull)
        .forEach(
            value -> {
              allowed.add(String.valueOf(value));
              allowed.add(String.format(Locale.ROOT, "%.2f", value));
            });
    Matcher matcher = Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?").matcher(withoutIds);
    while (matcher.find()) {
      if (!allowed.contains(matcher.group())) return false;
    }
    return true;
  }

  private List<String> missingObservables(ModelRequirement requirement, List<KnowledgeObject> all) {
    return requirement.requiredObservables().stream()
        .filter(observable -> !observablePresent(observable, all))
        .map(observable -> "MISSING_TARGET_OBSERVABLE:" + observable)
        .toList();
  }

  private boolean observablePresent(String observable, List<KnowledgeObject> all) {
    String expected = observable.toLowerCase(Locale.ROOT);
    return all.stream()
        .anyMatch(
            object ->
                object.name().equalsIgnoreCase(observable)
                    || containsObservable(object.attributes(), expected));
  }

  private boolean containsObservable(Object value, String expected) {
    if (value == null) return false;
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .anyMatch(
              entry ->
                  containsObservable(entry.getKey(), expected)
                      || containsObservable(entry.getValue(), expected));
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().anyMatch(item -> containsObservable(item, expected));
    }
    String text = String.valueOf(value).toLowerCase(Locale.ROOT);
    return text.equals(expected) || text.contains(expected);
  }

  private List<String> gaps(
      ModelRequirement requirement,
      KnowledgeObject object,
      List<KnowledgeObject> visible,
      KnowledgePackage pack,
      Map<String, Double> scorecard) {
    List<String> gaps = new ArrayList<>();
    if (valueBelow(scorecard.get("targetAlignment")))
      gaps.add("TARGET_DEFINITION:" + object.knowledgeKey());
    if (valueBelow(scorecard.get("populationAlignment")))
      gaps.add("POPULATION:" + object.knowledgeKey());
    if (valueBelow(scorecard.get("horizonAlignment"))) gaps.add("HORIZON:" + object.knowledgeKey());
    if (valueBelow(scorecard.get("featureAvailability")))
      gaps.add("FEATURES:" + object.knowledgeKey());
    if (valueBelow(scorecard.get("dataAvailability"))) gaps.add("DATA:" + object.knowledgeKey());
    if (valueBelow(scorecard.get("implementationAvailability")))
      gaps.add("IMPLEMENTATION:" + object.knowledgeKey());
    return gaps;
  }

  private boolean clearsReuse(Map<String, Double> scorecard) {
    return List.of(
            "targetAlignment",
            "populationAlignment",
            "horizonAlignment",
            "featureAvailability",
            "dataAvailability",
            "implementationAvailability")
        .stream()
        .allMatch(key -> scorecard.get(key) != null && scorecard.get(key) >= REUSE_THRESHOLD);
  }

  private Double targetAlignment(ModelRequirement requirement, KnowledgeObject object) {
    String candidate =
        text(object.attributes(), "targetEvent", "objective", "businessRationale")
            + " "
            + object.businessDescription();
    return overlap(
        requirement.predictionTarget() + " " + requirement.observableDefinition(), candidate);
  }

  private Double populationAlignment(ModelRequirement requirement, KnowledgeObject object) {
    String candidate = text(object.attributes(), "cohort", "scoredEntity", "entity");
    return candidate.isBlank() ? null : overlap(requirement.population(), candidate);
  }

  private Double horizonAlignment(ModelRequirement requirement, KnowledgeObject object) {
    Integer requested = days(requirement.outcomeHorizon());
    Integer candidate = days(text(object.attributes(), "predictionHorizon", "observationWindow"));
    if (requested == null || candidate == null) return null;
    return Math.max(
        0,
        1.0
            - Math.min(
                1.0, Math.abs(requested - candidate) / (double) Math.max(requested, candidate)));
  }

  private Double featureAvailability(
      ModelRequirement requirement,
      KnowledgeObject object,
      List<KnowledgeObject> visible,
      KnowledgePackage pack,
      boolean includeCandidates) {
    Object required = requirement.constraints().get("requiredFeatures");
    if (required instanceof Collection<?> values && !values.isEmpty()) {
      long available =
          values.stream()
              .filter(
                  value ->
                      object.knowledgeType().name().equals("MODEL")
                          ? pack.relationships().stream()
                              .filter(
                                  relationship ->
                                      relationship.relationshipType().name().equals("USES"))
                              .map(relationship -> relationship.toObjectId())
                              .map(
                                  featureId ->
                                      visible.stream()
                                          .filter(candidate -> candidate.id().equals(featureId))
                                          .findFirst()
                                          .orElse(null))
                              .anyMatch(
                                  candidate ->
                                      candidate != null
                                          && candidate.knowledgeType().name().equals("FEATURE")
                                          && candidate
                                              .name()
                                              .equalsIgnoreCase(String.valueOf(value)))
                          : visible.stream()
                              .anyMatch(
                                  candidate ->
                                      candidate.knowledgeType().name().equals("FEATURE")
                                          && candidate
                                              .name()
                                              .equalsIgnoreCase(String.valueOf(value))
                                          && knowledge
                                              .get(candidate.id(), includeCandidates)
                                              .conflicts()
                                              .stream()
                                              .noneMatch(
                                                  conflict ->
                                                      conflict.status().name().equals("OPEN")
                                                          && conflict.blocking())))
              .count();
      return available / (double) values.size();
    }
    if (object.knowledgeType().name().equals("FEATURE")) {
      return pack.conflicts().stream()
              .noneMatch(conflict -> conflict.status().name().equals("OPEN") && conflict.blocking())
          ? 1.0
          : 0.0;
    }
    if (object.knowledgeType().name().equals("MODEL")) {
      long visibleDependencies =
          pack.relationships().stream()
              .filter(relationship -> relationship.relationshipType().name().equals("USES"))
              .map(KnowledgeRelationship::toObjectId)
              .filter(
                  dependencyId ->
                      visible.stream().anyMatch(candidate -> candidate.id().equals(dependencyId)))
              .count();
      return visibleDependencies == 0 ? 0.0 : 1.0;
    }
    Object features = object.attributes().get("features");
    if (features == null) return null;
    String value = String.valueOf(features);
    String[] names = value.replaceAll("[\\[\\]\"]", "").split(",");
    if (names.length == 0) return null;
    long available =
        Arrays.stream(names)
            .map(String::trim)
            .filter(
                name ->
                    visible.stream()
                        .anyMatch(
                            candidate ->
                                candidate.knowledgeType().name().equals("FEATURE")
                                    && candidate.name().equalsIgnoreCase(name)))
            .count();
    return available / (double) names.length;
  }

  private Double dataAvailability(KnowledgeObject object, boolean includeCandidates) {
    Object assets = object.attributes().get("dataAssets");
    if (assets instanceof Collection<?> values && !values.isEmpty()) {
      List<KnowledgeObject> dataAssets =
          knowledge.search("DATA_ASSET", null, null, null, null, null, includeCandidates);
      long available =
          values.stream()
              .filter(
                  value ->
                      dataAssets.stream()
                          .anyMatch(
                              candidate ->
                                  candidate.knowledgeType().name().equals("DATA_ASSET")
                                      && candidate.name().equalsIgnoreCase(String.valueOf(value))
                                      && Boolean.TRUE.equals(
                                          candidate.attributes().get("history"))))
              .count();
      return available / (double) values.size();
    }
    if (object.knowledgeType().name().equals("MODEL")
        && knowledge.search("DATA_ASSET", null, null, null, null, null, includeCandidates).stream()
            .anyMatch(
                candidate ->
                    candidate.knowledgeType().name().equals("DATA_ASSET")
                        && candidate.name().equals("raw_events"))) return 1.0;
    return null;
  }

  private Double composite(Map<String, Double> scorecard) {
    double weighted = 0;
    double available = 0;
    for (Map.Entry<String, Double> weight : weights.weights().entrySet()) {
      Double value = scorecard.get(weight.getKey());
      if (value != null) {
        weighted += value * weight.getValue();
        available += weight.getValue();
      }
    }
    return available == 0 ? null : weighted / available;
  }

  private String overallClassification(List<DiscoveryCandidate> candidates, List<String> blockers) {
    if (!blockers.isEmpty()) return "NOT_RECOMMENDED";
    if (candidates.isEmpty()) return "GENERATE";
    return candidates.getFirst().classification();
  }

  private List<String> overallReasons(
      List<DiscoveryCandidate> candidates, List<String> missingObservables) {
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    reasons.addAll(missingObservables);
    if (candidates.isEmpty()) {
      reasons.add("NO_RECALL_CANDIDATE");
      return reasons.stream().toList();
    }
    candidates.stream()
        .flatMap(candidate -> candidate.reasonCodes().stream())
        .forEach(reasons::add);
    return reasons.stream().toList();
  }

  private String requirementText(ModelRequirement requirement) {
    return String.join(
        " ",
        String.valueOf(requirement.businessDomain()),
        String.valueOf(requirement.businessUseCase()),
        String.valueOf(requirement.predictionTarget()),
        String.valueOf(requirement.observableDefinition()),
        String.valueOf(requirement.population()),
        String.valueOf(requirement.outcomeHorizon()),
        String.valueOf(requirement.decisionLatency()),
        String.valueOf(requirement.requiredAction()),
        String.valueOf(requirement.constraints()),
        String.valueOf(requirement.requiredObservables()));
  }

  private String searchText(KnowledgeObject object) {
    return object.name() + " " + object.businessDescription() + " " + object.attributes();
  }

  private String text(Map<String, Object> attributes, String... keys) {
    return Arrays.stream(keys)
        .map(attributes::get)
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .reduce("", (left, right) -> left + " " + right)
        .trim();
  }

  private Double overlap(String requested, String candidate) {
    Set<String> expected = tokens(requested);
    Set<String> actual = tokens(candidate);
    if (expected.isEmpty() || actual.isEmpty()) return null;
    return expected.stream().filter(actual::contains).count() / (double) expected.size();
  }

  private Set<String> tokens(String text) {
    return new LinkedHashSet<>(
        Arrays.stream(String.valueOf(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(token -> token.length() > 2)
            .toList());
  }

  private Integer days(String text) {
    Matcher matcher =
        Pattern.compile("(\\d+)\\s*(?:d|day|days)").matcher(String.valueOf(text).toLowerCase());
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private boolean valueBelow(Double value) {
    return value != null && value < REUSE_THRESHOLD;
  }

  private Double number(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private void validate(ModelRequirement requirement) {
    if (requirement == null
        || blank(requirement.businessDomain())
        || blank(requirement.businessUseCase())
        || blank(requirement.predictionTarget())
        || blank(requirement.observableDefinition())
        || blank(requirement.population())
        || blank(requirement.outcomeHorizon())
        || blank(requirement.decisionLatency())) {
      throw new IllegalArgumentException("Discovery requirement is incomplete");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
