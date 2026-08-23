package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandoffPackageTest {
  @Test
  void hashIsDeterministicAndContentSnapshotIsImmutable() {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("requirementId", "requirement");
    Map<String, Object> feature = new LinkedHashMap<>();
    feature.put("version", 1);
    feature.put("knowledgeId", "feature:v1");
    content.put("features", List.of(feature));

    HandoffPackage first = HandoffPackage.create(new ObjectMapper(), content);
    HandoffPackage second = HandoffPackage.create(new ObjectMapper(), content);
    content.put("requirementId", "mutated");

    assertThat(first.hash()).isEqualTo(second.hash());
    assertThat(first.content()).containsEntry("requirementId", "requirement");
    assertThatThrownBy(() -> first.content().put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> ((Map) ((List<?>) first.content().get("features")).getFirst()).put("version", 2))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<?>) first.content().get("features")).clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void hashSurvivesJsonRoundTripOfRecordValues() {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> content =
        Map.of(
            "feasibility",
            Map.of(
                "checks",
                List.of(
                    new FeasibilityCheck(
                        "feature:booking-intent",
                        "PASS",
                        java.util.UUID.randomUUID(),
                        "Feature is visible"))));

    HandoffPackage original = HandoffPackage.create(mapper, content);
    Map<String, Object> roundTripped =
        mapper.convertValue(mapper.valueToTree(original.content()), Map.class);

    assertThat(HandoffPackage.create(mapper, roundTripped).hash()).isEqualTo(original.hash());
  }
}
