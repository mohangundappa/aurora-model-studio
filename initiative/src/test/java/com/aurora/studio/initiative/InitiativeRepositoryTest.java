package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class InitiativeRepositoryTest {
  private static final UUID CLIENT_ID = UUID.randomUUID();
  private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
  private final InitiativeRepository repository =
      new InitiativeRepository(jdbc, new ObjectMapper());

  @BeforeEach
  void setUp() {
    ClientContext.set(CLIENT_ID);
  }

  @AfterEach
  void tearDown() {
    ClientContext.clear();
  }

  @Test
  void saveDraftsPersistsRejectedDraftCountAndVerdicts() {
    UUID attemptId = UUID.randomUUID();
    GenerationDraft rejected =
        new GenerationDraft(
            "TARGETING",
            Map.of("cohortSql", "SELECT session_id FROM raw_events"),
            "REJECTED",
            UUID.randomUUID(),
            List.of(
                new ValidatorVerdict("target-leakage", "FAIL", "target observable referenced")));

    repository.saveDrafts(
        attemptId, List.of(rejected), List.of("target-leakage:target observable referenced"));

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    org.mockito.Mockito.verify(jdbc).update(contains("drafts_generated"), arguments.capture());
    assertThat(arguments.getValue()).hasSize(6);
    assertThat(arguments.getValue()[1]).isEqualTo(1);
    assertThat(arguments.getValue()[2]).isEqualTo(1L);
    assertThat((String) arguments.getValue()[0]).contains("\"outcome\":\"REJECTED\"");
    assertThat((String) arguments.getValue()[3])
        .contains("target-leakage:target observable referenced");
    assertThat(arguments.getValue()[4]).isEqualTo(CLIENT_ID);
    assertThat(arguments.getValue()[5]).isEqualTo(attemptId);
  }
}
