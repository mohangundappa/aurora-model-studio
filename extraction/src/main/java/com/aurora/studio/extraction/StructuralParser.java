package com.aurora.studio.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class StructuralParser {
  public List<Artifact> parse(Path root) throws IOException {
    return parseResult(root, ExtractionSourceSelection.auroraDefaults()).artifacts();
  }

  public ParseResult parseResult(Path root, ExtractionSourceSelection selection)
      throws IOException {
    List<Artifact> artifacts = new ArrayList<>();
    Set<Path> selected = new HashSet<>();
    int skipped = 0;
    for (ExtractionSourceSelection.SourceSpec source : selection.sources()) {
      Path sourceRoot = root.resolve(source.root()).normalize();
      if (!Files.exists(sourceRoot)) {
        throw new IllegalArgumentException(
            "Declared extraction root does not exist: " + sourceRoot);
      }
      try (Stream<Path> files =
          Files.isDirectory(sourceRoot) ? Files.walk(sourceRoot) : Stream.of(sourceRoot)) {
        for (Path path :
            files
                .filter(Files::isRegularFile)
                .filter(candidate -> matches(candidate, sourceRoot, source.patterns()))
                .sorted()
                .toList()) {
          if (!selected.add(path) || excluded(root, path)) {
            skipped++;
            continue;
          }
          List<Artifact> parsed = read(root, path);
          if (parsed.isEmpty()) skipped++;
          else artifacts.addAll(parsed);
        }
      }
    }
    return new ParseResult(artifacts, skipped);
  }

  public Artifact artifact(Path path, String kind, String name, String content) {
    String excerpt = content.substring(0, Math.min(1200, content.length()));
    StructuralFact fact =
        new StructuralFact(
            name,
            kind,
            name,
            Map.of("contentLength", content.length()),
            identifiers(content, "table"),
            identifiers(content, "column"),
            path.toString(),
            hash(content),
            excerpt);
    return new Artifact(path, kind, name, excerpt, fact);
  }

  private List<Artifact> read(Path root, Path path) {
    try {
      String content = Files.readString(path);
      String relative = root.relativize(path).toString().replace('\\', '/');
      return switch (extension(path)) {
        case "yaml", "yml" -> recognizedYaml(path, relative, content);
        case "sql" -> recognizedModels(path, content);
        case "java" -> recognizedJava(root, path, content);
        case "md" -> recognizedMarkdown(path, content);
        default -> List.of();
      };
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to parse " + path, exception);
    }
  }

  private List<Artifact> recognizedYaml(Path path, String relative, String content) {
    Object loaded = new Yaml().load(content);
    if (!(loaded instanceof Map<?, ?> source)) return List.of();
    String name = string(source, "name");
    if (source.containsKey("inputs")
        && (source.containsKey("calculationType") || source.containsKey("explanationTemplate"))
        && !name.isBlank()) {
      return List.of(parsedArtifact(path, "FEATURE", name, content, "feature:" + name));
    }
    if ((source.containsKey("id")
            && source.containsKey("variants")
            && source.containsKey("primaryOutcomeEvent"))
        || (source.containsKey("name") && source.containsKey("guard"))) {
      String id = string(source, "id");
      return id.isBlank()
          ? List.of(parsedArtifact(path, "EXPERIMENT", name, content, "experiment:" + relative))
          : List.of(parsedArtifact(path, "EXPERIMENT", id, content, "experiment:" + id));
    }
    if (source.containsKey("rules")
            && source.containsKey("default")
            && source.containsKey("channel")
        || (path.getFileName().toString().equals("decision-policy.yaml")
            && source.containsKey("name"))) {
      return List.of(
          parsedArtifact(
              path,
              "IMPLEMENTATION",
              "Configured decision policy",
              content,
              "implementation:decision-policy"));
    }
    return List.of();
  }

  private List<Artifact> recognizedModels(Path path, String content) {
    if (!content.toLowerCase(Locale.ROOT).contains("insert into model_versions")) return List.of();
    Matcher matcher = Pattern.compile("\\('([^']+)','([0-9.]+)'").matcher(content);
    List<Artifact> artifacts = new ArrayList<>();
    while (matcher.find()) {
      String model = matcher.group(1);
      String version = matcher.group(2);
      artifacts.add(
          parsedArtifact(
              path,
              "MODEL",
              model + " " + version,
              content,
              "model:" + model + ":" + version,
              hash(content) + ":" + version));
    }
    return artifacts;
  }

  private List<Artifact> recognizedMarkdown(Path path, String content) {
    if (path.getFileName().toString().equals("data-flow.md")
        && content.contains("Consent is evaluated")) {
      return List.of(
          parsedArtifact(
              path,
              "STANDARD",
              "Consent is evaluated",
              content,
              "standard:per-event-consent",
              hash(content) + ":per-event-consent"));
    }
    if (path.getFileName().toString().equals("agent-evaluation.md")
        && content
            .toLowerCase(Locale.ROOT)
            .contains("observations must not become causal claims")) {
      return List.of(
          parsedArtifact(
              path,
              "STANDARD",
              "observations must not become causal claims",
              content,
              "standard:no-causal-claims",
              hash(content) + ":no-causal-claims"));
    }
    return List.of();
  }

  private List<Artifact> recognizedJava(Path root, Path path, String content) {
    boolean calculator =
        (content.contains("SignalCalculator")
                || content.contains("extends CalculatorSupport")
                || content.contains("extends TextAffinityCalculator"))
            && content.contains("public String name()");
    if (!calculator && identifiers(content, "table").isEmpty()) {
      return List.of();
    }
    String fileName = path.getFileName().toString().replaceFirst("\\.java$", "");
    if (!calculator) {
      return List.of(
          parsedArtifact(
              path,
              "IMPLEMENTATION",
              fileName,
              content,
              "implementation:source:" + root.relativize(path).toString().replace('\\', '/')));
    }
    if (!fileName.endsWith("Calculator") || fileName.equals("SignalCalculator")) return List.of();
    String signal =
        fileName
            .substring(0, fileName.length() - "Calculator".length())
            .replaceAll("([a-z])([A-Z])", "$1-$2")
            .toLowerCase(Locale.ROOT);
    if (!Files.exists(
        root.resolve("signals/src/main/resources/signals").resolve(signal + ".yaml"))) {
      return List.of();
    }
    return List.of(
        parsedArtifact(
            path, "IMPLEMENTATION", fileName, content, "implementation:calculator:" + signal));
  }

  private Artifact parsedArtifact(Path path, String kind, String name, String content, String key) {
    return parsedArtifact(path, kind, name, content, key, hash(content));
  }

  private Artifact parsedArtifact(
      Path path, String kind, String name, String content, String key, String sourceHash) {
    String excerpt = content.substring(0, Math.min(1200, content.length()));
    Map<String, Object> structuralAttributes = new java.util.LinkedHashMap<>();
    structuralAttributes.put("contentLength", content.length());
    Map<String, Object> sourceDeclared = sourceDeclaredGovernance(content);
    if (!sourceDeclared.isEmpty()) structuralAttributes.put("sourceDeclared", sourceDeclared);
    StructuralFact fact =
        new StructuralFact(
            name,
            kind,
            name,
            structuralAttributes,
            identifiers(content, "table"),
            identifiers(content, "column"),
            path.toString(),
            sourceHash,
            excerpt);
    return new Artifact(path, kind, name, excerpt, fact, key);
  }

  private Map<String, Object> sourceDeclaredGovernance(String content) {
    Object loaded;
    try {
      loaded = new Yaml().load(content);
    } catch (RuntimeException exception) {
      return Map.of();
    }
    if (!(loaded instanceof Map<?, ?> source)) return Map.of();
    Map<String, Object> declared = new java.util.LinkedHashMap<>();
    for (String field :
        List.of("lifecycleStatus", "approvalStatus", "confidence", "approvedBy", "reviewedBy")) {
      if (source.containsKey(field)) declared.put(field, source.get(field));
    }
    return declared;
  }

  private boolean matches(Path path, Path sourceRoot, List<String> patterns) {
    String fileName = path.getFileName().toString();
    return patterns.stream()
        .anyMatch(pattern -> pattern.equals(fileName) || glob(pattern, fileName));
  }

  private boolean glob(String pattern, String value) {
    String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
    return value.matches(regex);
  }

  private boolean excluded(Path root, Path path) {
    Path relative = root.relativize(path);
    for (Path segment : relative) {
      String value = segment.toString().toLowerCase(Locale.ROOT);
      if (Set.of(
              ".git", "node_modules", "target", "dist", "build", "generated", "generated-sources")
          .contains(value)) return true;
    }
    String normalized = relative.toString().replace('\\', '/');
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return normalized.startsWith(".github/workflows/")
        || fileName.endsWith(".lock")
        || Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json")
            .contains(fileName);
  }

  private String string(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private String extension(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }

  private List<String> identifiers(String content, String kind) {
    String marker = kind.equals("table") ? "table" : "column";
    Pattern pattern =
        kind.equals("table")
            ? Pattern.compile(
                "(?i)\\b(?:from|join|into|update|table)[\\s:=]+([a-zA-Z_][a-zA-Z0-9_]*)")
            : Pattern.compile("(?i)\\b" + marker + "[\\s:=]+([a-zA-Z_][a-zA-Z0-9_]*)");
    return pattern.matcher(content).results().map(match -> match.group(1)).distinct().toList();
  }

  private String hash(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to hash structural artifact", exception);
    }
  }

  public record ParseResult(List<Artifact> artifacts, int skippedArtifacts) {}
}
