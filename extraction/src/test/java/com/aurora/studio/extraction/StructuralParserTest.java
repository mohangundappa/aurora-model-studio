package com.aurora.studio.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuralParserTest {
  private final StructuralParser parser = new StructuralParser();

  @Test
  void selectsDeclaredShapesAndReportsSkippedArtifacts(@TempDir Path root) throws Exception {
    Files.writeString(
        root.resolve("booking-intent.yaml"),
        "name: booking-intent\ninputs: [ROOM_VIEWED]\ncalculationType: RULE\n");
    Files.createDirectories(root.resolve("node_modules/package"));
    Files.writeString(
        root.resolve("node_modules/package/rule.yaml"),
        "name: dependency-doc\ninputs: [x]\ncalculationType: RULE\n");
    Files.createDirectories(root.resolve(".github/workflows"));
    Files.writeString(
        root.resolve(".github/workflows/ci.yaml"),
        "name: ci\ninputs: [x]\ncalculationType: RULE\n");
    Files.writeString(root.resolve("README.md"), "# unrelated\n");

    StructuralParser.ParseResult result =
        parser.parseResult(
            root,
            new ExtractionSourceSelection(
                List.of(new ExtractionSourceSelection.SourceSpec(".", List.of("*.yaml", "*.md")))));

    assertThat(result.artifacts())
        .singleElement()
        .satisfies(
            artifact -> {
              assertThat(artifact.knowledgeKey()).isEqualTo("feature:booking-intent");
              assertThat(artifact.kind()).isEqualTo("FEATURE");
            });
    assertThat(result.skippedArtifacts()).isEqualTo(3);
  }

  @Test
  void missingDeclaredRootFailsLoudly(@TempDir Path root) {
    assertThatThrownBy(
            () ->
                parser.parseResult(
                    root,
                    new ExtractionSourceSelection(
                        List.of(
                            new ExtractionSourceSelection.SourceSpec(
                                "signals", List.of("*.yaml"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Declared extraction root does not exist");
  }

  @Test
  void yamlMustMatchASupportedShape(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("unrelated.yaml"), "name: unrelated\npurpose: documentation\n");
    StructuralParser.ParseResult result =
        parser.parseResult(
            root,
            new ExtractionSourceSelection(
                List.of(new ExtractionSourceSelection.SourceSpec(".", List.of("*.yaml")))));

    assertThat(result.artifacts()).isEmpty();
    assertThat(result.skippedArtifacts()).isEqualTo(1);
  }

  @Test
  void sourceDeclaredGovernanceIsKeptSeparateFromStructuralAttributes(@TempDir Path root)
      throws Exception {
    Files.writeString(
        root.resolve("signal.yaml"),
        """
        name: booking-intent
        inputs: [ROOM_VIEWED]
        calculationType: RULE
        lifecycleStatus: DEPLOYED
        confidence: 0.8
        """);

    Artifact artifact =
        parser
            .parseResult(
                root,
                new ExtractionSourceSelection(
                    List.of(new ExtractionSourceSelection.SourceSpec(".", List.of("*.yaml")))))
            .artifacts()
            .getFirst();

    assertThat(artifact.structuralFact().inputs())
        .containsEntry("sourceDeclared", Map.of("lifecycleStatus", "DEPLOYED", "confidence", 0.8));
  }
}
