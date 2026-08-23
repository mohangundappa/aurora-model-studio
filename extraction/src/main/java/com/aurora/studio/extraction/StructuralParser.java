package com.aurora.studio.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class StructuralParser {
  public List<Artifact> parse(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> !path.toString().contains("/.git/"))
          .filter(path -> !path.toString().contains("/target/"))
          .filter(this::supported)
          .sorted()
          .map(path -> read(root, path))
          .toList();
    }
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

  private Artifact read(Path root, Path path) {
    try {
      String content = Files.readString(path);
      String fileName = path.getFileName().toString();
      String name = fileName.replaceFirst("\\.[^.]+$", "");
      String kind =
          switch (extension(path)) {
            case "yaml", "yml" -> "FEATURE";
            case "sql" -> "DATA_ASSET";
            case "java" -> "IMPLEMENTATION";
            case "md" -> "STANDARD";
            default -> "STANDARD";
          };
      return artifact(root.relativize(path), kind, name, content);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to parse " + path, exception);
    }
  }

  private boolean supported(Path path) {
    return List.of("yaml", "yml", "sql", "java", "md").contains(extension(path));
  }

  private String extension(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }

  private List<String> identifiers(String content, String kind) {
    String marker = kind.equals("table") ? "table" : "column";
    return Pattern.compile("(?i)\\b" + marker + "[\\s:=]+([a-zA-Z_][a-zA-Z0-9_]*)")
        .matcher(content)
        .results()
        .map(match -> match.group(1))
        .distinct()
        .toList();
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
}
