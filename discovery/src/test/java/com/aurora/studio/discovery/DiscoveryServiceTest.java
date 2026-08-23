package com.aurora.studio.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscoveryServiceTest {
  @Test
  void deterministicEmbeddingIsByteIdenticalForIdenticalInput() {
    DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider();
    assertThat(provider.embed("same requirement").vector())
        .containsExactly(provider.embed("same requirement").vector());
  }

  @Test
  void explanationRejectsNumberAbsentFromScorecard() {
    assertThat(DiscoveryService.validNumbers("score 0.75 and 9", Map.of("targetAlignment", 0.75)))
        .isFalse();
    assertThat(DiscoveryService.validNumbers("score 0.75", Map.of("targetAlignment", 0.75)))
        .isTrue();
  }

  @Test
  void explanationRejectsObjectNotSuppliedToProvider() {
    UUID supplied = UUID.randomUUID();
    UUID absent = UUID.randomUUID();
    assertThat(DiscoveryService.validReferences("see " + supplied, Set.of(supplied.toString())))
        .isTrue();
    assertThat(DiscoveryService.validReferences("see " + absent, Set.of(supplied.toString())))
        .isFalse();
  }
}
