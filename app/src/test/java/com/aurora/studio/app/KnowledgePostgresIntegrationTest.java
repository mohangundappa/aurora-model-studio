package com.aurora.studio.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.studio.common.ClientContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerSocketIsAvailable")
class KnowledgePostgresIntegrationTest {
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("aurora_studio")
          .withUsername("aurora")
          .withPassword("aurora");

  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static boolean dockerSocketIsAvailable() {
    return java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"));
  }

  @BeforeEach
  void setUp() {
    ClientContext.set(CLIENT);
  }

  @AfterEach
  void tearDown() {
    ClientContext.clear();
  }

  @Test
  void approvedRowsRejectRawUpdatesAndNonExtractedDeletes() {
    UUID id = insert("approved-immutable", CLIENT, "APPROVED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update knowledge_objects set name='changed' where client_id=? and id=?",
                    CLIENT,
                    id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("approved knowledge object");
    assertThatThrownBy(
            () ->
                jdbc.update("delete from knowledge_objects where client_id=? and id=?", CLIENT, id))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("cannot be deleted");
  }

  @Test
  void onlyOneApprovedVersionPerClientAndKeyIsAllowed() {
    insert("one-approved", CLIENT, "APPROVED");
    assertThatThrownBy(() -> insert("one-approved", CLIENT, "APPROVED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void byIdQueriesCannotCrossClientBoundary() {
    UUID id = insert("private-object", OTHER, "APPROVED");
    Integer visible =
        jdbc.queryForObject(
            "select count(*) from knowledge_objects where client_id=? and id=?",
            Integer.class,
            CLIENT,
            id);
    assertThat(visible).isZero();
  }

  @Test
  void impactRelationshipsCanContainCyclesWithoutUnboundedTraversal() {
    UUID first = insert("cycle-a", CLIENT, "EXTRACTED");
    UUID second = insert("cycle-b", CLIENT, "EXTRACTED");
    UUID third = insert("cycle-c", CLIENT, "EXTRACTED");
    jdbc.update(
        "insert into knowledge_relationships(client_id,from_object_id,relationship_type,to_object_id) values(?,?,?,?),(?,?,?,?),(?,?,?,?)",
        CLIENT,
        first,
        "USES",
        second,
        CLIENT,
        second,
        "USES",
        third,
        CLIENT,
        third,
        "USES",
        first);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_relationships where client_id=?",
                Integer.class,
                CLIENT))
        .isEqualTo(3);
  }

  private UUID insert(String key, UUID client, String status) {
    return jdbc.queryForObject(
        "insert into knowledge_objects(client_id,knowledge_key,version,knowledge_type,name,business_domain,business_use_case,business_description,lifecycle_status,confidence,confidence_breakdown,attributes,extracted_by,synthetic) values(?,?,?,?,?,?,?,?,?,?,'{}','{}','test',false) returning id",
        UUID.class,
        client,
        key,
        1,
        "FEATURE",
        key,
        "test",
        "test",
        "test",
        status,
        0.5);
  }
}
