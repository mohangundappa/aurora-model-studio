package com.aurora.studio.importer;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeRepository;
import com.aurora.studio.knowledge.KnowledgeService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class AuroraBackfillImporter {
  public static final UUID IMPORT_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final KnowledgeService service;
  private final KnowledgeRepository repository;
  private final JdbcTemplate jdbc;
  private final Yaml yaml = new Yaml();

  public AuroraBackfillImporter(
      KnowledgeService service, KnowledgeRepository repository, JdbcTemplate jdbc) {
    this.service = service;
    this.repository = repository;
    this.jdbc = jdbc;
  }

  public ImportResult importRepository(Path root) throws IOException {
    ClientContext.set(IMPORT_CLIENT);
    try {
      Map<String, Integer> counts = new LinkedHashMap<>();
      Map<String, UUID> objects = new HashMap<>();
      Path signalDir = root.resolve("signals/src/main/resources/signals");
      try (var files = Files.list(signalDir)) {
        for (Path file :
            files
                .filter(Files::isRegularFile)
                .filter(
                    candidate -> {
                      String name = candidate.getFileName().toString().toLowerCase();
                      return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                .sorted()
                .toList()) {
          Map<String, Object> source = readYaml(file);
          String name = String.valueOf(source.get("name"));
          String hash = hash(file);
          Map<String, Object> attributes = new LinkedHashMap<>(source);
          moveSourceDeclaredGovernance(attributes);
          attributes.put("businessDefinition", source.get("explanationTemplate"));
          attributes.put("entity", "customer");
          attributes.put("observationWindow", source.get("lookback"));
          attributes.put("pointInTimeAvailable", true);
          attributes.put("inputs", source.get("inputs"));
          attributes.put(
              "sourceConstraints",
              Map.of(
                  "freshness",
                  source.get("freshness"),
                  "expiry",
                  source.get("expiry"),
                  "consentRequired",
                  source.get("consentRequired")));
          KnowledgeObject feature =
              createIfChanged(
                  "feature:" + name,
                  KnowledgeType.FEATURE,
                  name,
                  "customer intelligence",
                  "customer signal development",
                  String.valueOf(source.get("explanationTemplate")),
                  attributes,
                  file,
                  hash,
                  root,
                  counts);
          if (feature != null) objects.put(name, feature.id());
        }
      }
      Path calculatorDir = root.resolve("signals/src/main/java/com/aurora/signals");
      for (String name : List.copyOf(objects.keySet())) {
        String className = toClassName(name) + "Calculator.java";
        Path file = calculatorDir.resolve(className);
        if (!Files.exists(file)) continue;
        KnowledgeObject implementation =
            createIfChanged(
                "implementation:calculator:" + name,
                KnowledgeType.IMPLEMENTATION,
                className.replace(".java", ""),
                "customer intelligence",
                "signal calculation",
                "Executable calculator for the " + name + " feature.",
                Map.of(
                    "languageOrKind",
                    "Java Spring calculator bean",
                    "sourceTraceability",
                    file.toString()),
                file,
                hash(file),
                root,
                counts);
        if (implementation != null) {
          jdbc.update(
              "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?) on conflict do nothing",
              IMPORT_CLIENT,
              objects.get(name),
              "IMPLEMENTED_BY",
              implementation.id());
        }
      }
      importModels(root, objects, counts);
      importDataAssets(root, counts);
      importPolicy(root, counts);
      importStandards(root, counts);
      return new ImportResult(counts, currentCommit(root));
    } finally {
      ClientContext.clear();
    }
  }

  private void importModels(Path root, Map<String, UUID> features, Map<String, Integer> counts)
      throws IOException {
    Path file = root.resolve("app/src/main/resources/db/migration/V7__model_registry.sql");
    String content = Files.readString(file);
    Matcher matcher =
        Pattern.compile(
                "\\('booking-intent','([0-9.]+)','[^']+','(\\[[^]]+\\])'::jsonb,\\s*'(\\{[^}]+\\})'::jsonb,([0-9.-]+)\\)")
            .matcher(content);
    while (matcher.find()) {
      String version = matcher.group(1);
      String hash = hash(file) + ":" + version;
      Map<String, Object> attributes =
          new LinkedHashMap<>(
              Map.of(
                  "objective", "predict booking intent",
                  "scoredEntity", "customer session",
                  "targetEvent", "BOOKING_COMPLETED",
                  "predictionHorizon", "30d",
                  "cohort", "eligible consented sessions",
                  "features", matcher.group(2),
                  "weights", matcher.group(3),
                  "bias", matcher.group(4)));
      KnowledgeObject model =
          createIfChanged(
              "model:booking-intent:" + version,
              KnowledgeType.MODEL,
              "booking-intent " + version,
              "customer intelligence",
              "booking propensity",
              "Baseline booking-intent model registered by Aurora Intelligence.",
              attributes,
              file,
              hash,
              root,
              counts);
      if (model != null) {
        boolean linked = false;
        for (String feature : features.keySet()) {
          if (matcher.group(2).contains(feature)) {
            jdbc.update(
                "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?) on conflict do nothing",
                IMPORT_CLIENT,
                model.id(),
                "USES",
                features.get(feature));
            for (UUID implementationId :
                jdbc.queryForList(
                    "select to_object_id from knowledge_relationships where client_id=? and from_object_id=? and relationship_type='IMPLEMENTED_BY'",
                    UUID.class,
                    IMPORT_CLIENT,
                    features.get(feature))) {
              jdbc.update(
                  "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?) on conflict do nothing",
                  IMPORT_CLIENT,
                  model.id(),
                  "IMPLEMENTED_BY",
                  implementationId);
            }
            linked = true;
          }
        }
        if (!linked && features.containsKey("booking-intent")) {
          jdbc.update(
              "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?) on conflict do nothing",
              IMPORT_CLIENT,
              model.id(),
              "USES",
              features.get("booking-intent"));
          linkImplementations(model.id(), features.get("booking-intent"));
        }
      }
    }
  }

  private void linkImplementations(UUID modelId, UUID featureId) {
    for (UUID implementationId :
        jdbc.queryForList(
            "select to_object_id from knowledge_relationships where client_id=? and from_object_id=? and relationship_type='IMPLEMENTED_BY'",
            UUID.class,
            IMPORT_CLIENT,
            featureId)) {
      jdbc.update(
          "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?) on conflict do nothing",
          IMPORT_CLIENT,
          modelId,
          "IMPLEMENTED_BY",
          implementationId);
    }
  }

  private void importDataAssets(Path root, Map<String, Integer> counts) throws IOException {
    Map<String, String> definitions =
        Map.of(
            "raw_events", "one immutable customer event per row; event_time is the event timestamp",
            "derived_signals",
                "one calculated signal snapshot per customer and signal; event_time is calculation time",
            "decisions", "one decision served per customer session; event_time is decision time",
            "experiment_exposures",
                "one experiment assignment per exposed subject; event_time is exposure time",
            "experiment_outcomes",
                "one measured outcome joined to an exposure; event_time is outcome time");
    Path migration = root.resolve("app/src/main/resources/db/migration/V1__initial_schema.sql");
    for (var entry : definitions.entrySet()) {
      createIfChanged(
          "data-asset:" + entry.getKey(),
          KnowledgeType.DATA_ASSET,
          entry.getKey(),
          "customer intelligence",
          "analytics data foundation",
          entry.getValue(),
          Map.of(
              "grain", entry.getValue().split(";")[0],
              "primaryKey", "repository-defined row identifier",
              "eventTime", "event_time",
              "history", "retained in PostgreSQL tables"),
          migration,
          hash(migration) + ":" + entry.getKey(),
          root,
          counts);
    }
  }

  private void importPolicy(Path root, Map<String, Integer> counts) throws IOException {
    Path file = root.resolve("decision/src/main/resources/decision-policy.yaml");
    createIfChanged(
        "implementation:decision-policy",
        KnowledgeType.IMPLEMENTATION,
        "Configured decision policy",
        "customer intelligence",
        "decisioning",
        "Provider-neutral decision policy configuration used by the decision runtime.",
        Map.of("languageOrKind", "YAML decision policy", "sourceTraceability", file.toString()),
        file,
        hash(file),
        root,
        counts);
  }

  private void importStandards(Path root, Map<String, Integer> counts) throws IOException {
    List<String[]> standards =
        List.of(
            new String[] {
              "minimum-exposures",
              "At least 30 exposed subjects per variant are required before directional rates and lift are reported.",
              "experiment analysis",
              "experiments/src/main/resources/experiments/destination-experience-v1.yaml"
            },
            new String[] {
              "per-event-consent",
              "Personalization consent is evaluated per event before a personalized decision or experiment exposure is recorded.",
              "event ingestion and signal calculation",
              "docs/data-flow.md"
            },
            new String[] {
              "no-causal-claims",
              "Observational associations and insufficient samples must not be presented as causal claims.",
              "insight and analysis copy",
              "docs/agent-evaluation.md"
            },
            new String[] {
              "no-presence-randomization",
              "Do not randomize signal-present versus signal-absent subjects; randomize only among eligible signal-present subjects.",
              "experiment assignment",
              "agents/src/main/java/com/aurora/agents/ExperimentationAgent.java"
            });
    for (String[] standard : standards) {
      Path source = root.resolve(standard[3]);
      createIfChanged(
          "standard:" + standard[0],
          KnowledgeType.STANDARD,
          standard[0],
          "governed experimentation",
          "safe model development",
          standard[1],
          Map.of("rule", standard[1], "enforcementPoint", standard[2]),
          source,
          hash(source) + ":" + standard[0],
          root,
          counts);
    }
  }

  private KnowledgeObject createIfChanged(
      String key,
      KnowledgeType type,
      String name,
      String domain,
      String useCase,
      String description,
      Map<String, Object> attributes,
      Path source,
      String sourceVersion,
      Path root,
      Map<String, Integer> counts)
      throws IOException {
    String evidenceVersion = currentCommit(root) + ":" + sourceVersion;
    Integer existing =
        jdbc.queryForObject(
            "select count(*) from knowledge_evidence e join knowledge_objects o on o.id=e.knowledge_object_id where e.client_id=? and o.knowledge_key=? and e.source_version=?",
            Integer.class,
            IMPORT_CLIENT,
            key,
            evidenceVersion);
    if (existing > 0) return repository.findLatest(key).orElse(null);
    KnowledgeObject object =
        service.create(
            new KnowledgeService.Draft(
                key,
                type,
                name,
                domain,
                useCase,
                description,
                Map.of(),
                Map.of(),
                List.of("imported", type.name().toLowerCase()),
                attributes,
                false),
            "backfill-importer");
    service.addEvidence(
        object.id(),
        "aurora-intelligence",
        "source-file",
        root.relativize(source).toString(),
        evidenceVersion,
        excerpt(source),
        0.95);
    counts.merge(type.name(), 1, Integer::sum);
    return object;
  }

  private Map<String, Object> readYaml(Path file) throws IOException {
    Object loaded = yaml.load(Files.readString(file));
    return loaded instanceof Map<?, ?> map
        ? map.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    e -> String.valueOf(e.getKey()), Map.Entry::getValue))
        : Map.of();
  }

  private void moveSourceDeclaredGovernance(Map<String, Object> attributes) {
    Map<String, Object> sourceDeclared = new LinkedHashMap<>();
    for (String field :
        List.of("lifecycleStatus", "approvalStatus", "confidence", "approvedBy", "reviewedBy")) {
      if (attributes.containsKey(field)) sourceDeclared.put(field, attributes.remove(field));
    }
    if (!sourceDeclared.isEmpty()) attributes.put("sourceDeclared", sourceDeclared);
  }

  private String excerpt(Path file) throws IOException {
    return Files.readString(file).substring(0, (int) Math.min(1000, Files.size(file)));
  }

  private String hash(Path file) throws IOException {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception exception) {
      throw new IOException("Unable to hash " + file, exception);
    }
  }

  private String currentCommit(Path root) throws IOException {
    try {
      Process process =
          new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() == 0 && !output.isBlank()) return output.trim();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    return "unresolved";
  }

  private String toClassName(String name) {
    StringBuilder result = new StringBuilder();
    for (String part : name.split("-"))
      result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    return result.toString();
  }

  public record ImportResult(Map<String, Integer> counts, String commit) {}
}
