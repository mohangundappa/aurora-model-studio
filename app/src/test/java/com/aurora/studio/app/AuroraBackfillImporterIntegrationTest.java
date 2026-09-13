package com.aurora.studio.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.extraction.ExtractionService;
import com.aurora.studio.importer.AuroraBackfillImporter;
import com.aurora.studio.knowledge.KnowledgeIngestion;
import com.aurora.studio.knowledge.KnowledgeObject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerSocketIsAvailable")
class AuroraBackfillImporterIntegrationTest {
  private static final UUID CLIENT = AuroraBackfillImporter.IMPORT_CLIENT;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("aurora_studio")
          .withUsername("aurora")
          .withPassword("aurora");

  @Autowired AuroraBackfillImporter importer;
  @Autowired ExtractionService extraction;
  @Autowired KnowledgeIngestion ingestion;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static boolean dockerSocketIsAvailable() {
    return Files.exists(Path.of("/var/run/docker.sock"));
  }

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "truncate knowledge_audit, knowledge_conflicts, knowledge_relationships, knowledge_evidence, knowledge_objects restart identity cascade");
    ClientContext.clear();
  }

  @AfterEach
  void tearDown() {
    ClientContext.clear();
  }

  @Test
  void fixtureImportIsIdempotentAndChangedSourcesCreateVersions(@TempDir Path temp)
      throws Exception {
    Path fixture = copyFixture(temp);
    Files.createDirectories(fixture.resolve("signals/src/main/resources/signals/stray-directory"));
    Path bookingSignal = fixture.resolve("signals/src/main/resources/signals/booking-intent.yaml");
    Files.writeString(
        bookingSignal, Files.readString(bookingSignal) + "\nlifecycleStatus: DEPLOYED\n");

    AuroraBackfillImporter.ImportResult first = importer.importRepository(fixture);
    assertThat(first.counts())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "FEATURE", 2,
                "IMPLEMENTATION", 3,
                "MODEL", 1,
                "DATA_ASSET", 5,
                "STANDARD", 4));
    assertThat(countObjects()).isEqualTo(15);
    assertThat(
            jdbc.queryForObject(
                "select attributes->>'lifecycleStatus' is not null from knowledge_objects where client_id=? and knowledge_key=?",
                Boolean.class,
                CLIENT,
                "feature:booking-intent"))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select attributes->'sourceDeclared'->>'lifecycleStatus' from knowledge_objects where client_id=? and knowledge_key=?",
                String.class,
                CLIENT,
                "feature:booking-intent"))
        .isEqualTo("DEPLOYED");
    assertThat(
            jdbc.queryForObject(
                "select attributes->>'language' from knowledge_objects where client_id=? and knowledge_key=?",
                String.class,
                CLIENT,
                "implementation:calculator:booking-intent"))
        .isEqualTo("Java");
    assertThat(
            jdbc.queryForObject(
                "select attributes->>'implementationKind' from knowledge_objects where client_id=? and knowledge_key=?",
                String.class,
                CLIENT,
                "implementation:calculator:booking-intent"))
        .isEqualTo("Spring calculator bean");

    AuroraBackfillImporter.ImportResult second = importer.importRepository(fixture);
    assertThat(second.counts()).isEmpty();
    assertThat(countObjects()).isEqualTo(15);

    Path changed = fixture.resolve("signals/src/main/resources/signals/booking-intent.yaml");
    Files.writeString(changed, Files.readString(changed) + "\nchanged: true\n");
    AuroraBackfillImporter.ImportResult third = importer.importRepository(fixture);

    assertThat(third.counts()).containsEntry("FEATURE", 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_objects where client_id=? and knowledge_key=?",
                Integer.class,
                CLIENT,
                "feature:booking-intent"))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_objects where client_id=? and knowledge_key=? and version=1",
                Integer.class,
                CLIENT,
                "feature:booking-intent"))
        .isEqualTo(1);
  }

  @Test
  void extractionIsConvergentAndUsesImporterKeys(@TempDir Path temp) throws Exception {
    Path fixture = copyFixture(temp);
    importer.importRepository(fixture);

    ClientContext.set(CLIENT);
    try {
      ExtractionService.ExtractionRun first = extraction.extract(fixture, false);
      assertThat(first.counts()).containsEntry("EXPERIMENT", 1);
      assertThat(first.candidateIds()).hasSize(4);
      assertThat(first.skippedArtifacts()).isGreaterThan(0);

      ExtractionService.ExtractionRun second = extraction.extract(fixture, false);
      assertThat(second.counts()).isEmpty();
      assertThat(second.candidateIds()).isEmpty();
      assertThat(second.unchangedArtifacts()).isGreaterThan(0);

      Path changed =
          fixture.resolve(
              "experiments/src/main/resources/experiments/destination-experience-v1.yaml");
      Files.writeString(
          changed,
          Files.readString(changed)
              .replace("name: destination-experience", "name: revised-experience"));
      ExtractionService.ExtractionRun third = extraction.extract(fixture, false);
      assertThat(third.counts()).containsEntry("EXPERIMENT", 1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from knowledge_objects where client_id=? and knowledge_key=?",
                  Integer.class,
                  CLIENT,
                  "experiment:experiments/src/main/resources/experiments/destination-experience-v1.yaml"))
          .isEqualTo(2);
    } finally {
      ClientContext.clear();
    }
  }

  private int countObjects() {
    return jdbc.queryForObject(
        "select count(*) from knowledge_objects where client_id=?", Integer.class, CLIENT);
  }

  @Test
  void combinedImportAndExtractionPreserveRelationshipsOnRepeat(@TempDir Path temp)
      throws Exception {
    Path fixture = copyFixture(temp);
    importer.importRepository(fixture);
    extractFixture(fixture);
    int objects = countObjects();
    var evidence =
        jdbc.queryForList("select * from knowledge_evidence where client_id=? order by id", CLIENT);
    var relationships =
        jdbc.queryForList(
            "select * from knowledge_relationships where client_id=? order by id", CLIENT);
    assertThat(objects).isEqualTo(19);
    assertThat(relationships).hasSize(4);

    for (int run = 0; run < 2; run++) {
      assertThat(importer.importRepository(fixture).counts()).isEmpty();
      assertThat(extractFixture(fixture).candidateIds()).isEmpty();
      assertThat(countObjects()).isEqualTo(objects);
      assertThat(
              jdbc.queryForList(
                  "select * from knowledge_evidence where client_id=? order by id", CLIENT))
          .isEqualTo(evidence);
      assertThat(
              jdbc.queryForList(
                  "select * from knowledge_relationships where client_id=? order by id", CLIENT))
          .isEqualTo(relationships);
    }
  }

  private ExtractionService.ExtractionRun extractFixture(Path fixture) throws Exception {
    ClientContext.set(CLIENT);
    try {
      return extraction.extract(fixture, false);
    } finally {
      ClientContext.clear();
    }
  }

  @Test
  void evidenceLookupMatchesClientSourceAndVersion(@TempDir Path temp) throws Exception {
    Path fixture = copyFixture(temp);
    importer.importRepository(fixture);
    extractFixture(fixture);
    String key = "feature:booking-intent";
    String sourceVersion =
        jdbc.queryForObject(
            "select e.source_version from knowledge_evidence e join knowledge_objects o "
                + "on o.client_id=e.client_id and o.id=e.knowledge_object_id "
                + "where o.client_id=? and o.knowledge_key=? and o.version=1",
            String.class,
            CLIENT,
            key);
    ClientContext.set(CLIENT);
    assertThat(ingestion.findLatest(key).map(KnowledgeObject::version)).contains(2);
    assertThat(
            ingestion
                .findBySourceVersion(key, "aurora-intelligence", sourceVersion)
                .map(KnowledgeObject::version))
        .contains(1);
    assertThat(
            ingestion
                .findBySourceVersion(key, "aurora-estate", sourceVersion)
                .map(KnowledgeObject::version))
        .contains(2);
    assertThat(ingestion.findBySourceVersion(key, "other-source", sourceVersion)).isEmpty();
    assertThat(ingestion.findBySourceVersion(key, "aurora-intelligence", "missing-version"))
        .isEmpty();
    assertThat(ingestion.findBySourceVersion("missing-key", "aurora-intelligence", sourceVersion))
        .isEmpty();
    ClientContext.set(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    assertThat(ingestion.findBySourceVersion(key, "aurora-intelligence", sourceVersion)).isEmpty();
  }

  @Test
  void changedAndRevertedSourcesPreserveImportedVersions(@TempDir Path temp) throws Exception {
    Path fixture = copyFixture(temp);
    Path signal = fixture.resolve("signals/src/main/resources/signals/booking-intent.yaml");
    String original = Files.readString(signal);
    importer.importRepository(fixture);
    extractFixture(fixture);
    Files.writeString(signal, original.replace("Recent booking intent", "Updated booking intent"));
    assertThat(importer.importRepository(fixture).counts())
        .containsExactly(Map.entry("FEATURE", 1));
    assertThat(extractFixture(fixture).counts()).containsExactly(Map.entry("FEATURE", 1));
    var relationships =
        jdbc.queryForList(
            "select * from knowledge_relationships where client_id=? order by id", CLIENT);
    var versions =
        jdbc.queryForList(
            "select id,version from knowledge_objects where client_id=? and knowledge_key=? order by version",
            CLIENT,
            "feature:booking-intent");
    assertThat(versions).hasSize(4);
    assertThat(importer.importRepository(fixture).counts()).isEmpty();
    assertThat(extractFixture(fixture).candidateIds()).isEmpty();
    assertThat(
            jdbc.queryForList(
                "select * from knowledge_relationships where client_id=? order by id", CLIENT))
        .isEqualTo(relationships);
    Files.writeString(signal, original);
    assertThat(importer.importRepository(fixture).counts()).isEmpty();
    assertThat(
            jdbc.queryForList(
                "select * from knowledge_relationships where client_id=? order by id", CLIENT))
        .isEqualTo(relationships);
    assertThat(
            jdbc.queryForList(
                "select id,version from knowledge_objects where client_id=? and knowledge_key=? order by version",
                CLIENT,
                "feature:booking-intent"))
        .isEqualTo(versions);
  }

  private Path copyFixture(Path temp) throws Exception {
    URI uri = getClass().getClassLoader().getResource("aurora-fixture").toURI();
    Path source = Path.of(uri);
    Path destination = temp.resolve("aurora-fixture");
    try (var paths = Files.walk(source)) {
      for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
        Path target = destination.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else Files.copy(path, target);
      }
    }
    return destination;
  }
}
