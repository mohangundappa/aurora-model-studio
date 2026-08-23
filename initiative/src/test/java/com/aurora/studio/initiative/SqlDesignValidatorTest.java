package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlDesignValidatorTest {
  private static final ModelRequirement REQUIREMENT =
      new ModelRequirement(
          "commerce",
          "booking",
          "booking",
          "completed booking",
          "sessions",
          "30d",
          "1d",
          "retain",
          Map.of(),
          Map.of(),
          Map.of(),
          List.of("BOOKING_COMPLETED"),
          false);

  @Test
  void rejectsLeakageAndForwardLookingPredicates() {
    List<ValidatorVerdict> leakage =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, event_time FROM raw_events "
                + "WHERE event_name = 'BOOKING_COMPLETED' AND event_time <= :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(leakage)
        .anySatisfy(
            verdict ->
                assertThat(verdict.reason()).contains("target observable BOOKING_COMPLETED"));

    List<ValidatorVerdict> forward =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, event_time FROM raw_events WHERE event_time > :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(forward)
        .anySatisfy(verdict -> assertThat(verdict.reason()).contains("forward-looking predicate"));
  }

  @Test
  void acceptsBoundedLookbackPredicate() {
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, event_time FROM raw_events "
                + "WHERE event_time <= :as_of "
                + "AND event_time > :as_of - interval '30 days'",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("point-in-time-safety"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("PASS");
  }

  @Test
  void rejectsUnknownColumnsSelectStarAndMultipleStatements() {
    List<ValidatorVerdict> unknownColumn =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, made_up FROM raw_events WHERE event_time <= :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(unknownColumn)
        .anySatisfy(verdict -> assertThat(verdict.reason()).contains("unknown governed column"));

    List<ValidatorVerdict> star =
        SqlDesignValidator.validateCohort(
            "SELECT * FROM raw_events WHERE event_time <= :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(star).anySatisfy(verdict -> assertThat(verdict.reason()).contains("SELECT *"));

    List<ValidatorVerdict> multiple =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, event_time FROM raw_events; SELECT session_id, event_time FROM raw_events",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(multiple)
        .anySatisfy(verdict -> assertThat(verdict.reason()).contains("multiple SQL statements"));
  }

  @Test
  void absentGovernedColumnsAreUnknown() {
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT session_id, event_time FROM raw_events WHERE event_time <= :as_of",
            REQUIREMENT,
            List.of(assetWithoutColumns()));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("governed-references"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("UNKNOWN");
  }

  private static KnowledgeObject rawEvents() {
    return asset(
        Map.of(
            "columns",
            List.of(
                Map.of("name", "event_id"),
                Map.of("name", "event_name"),
                Map.of("name", "event_time"),
                Map.of("name", "session_id"),
                Map.of("name", "customer_id"))));
  }

  private static KnowledgeObject assetWithoutColumns() {
    return asset(Map.of());
  }

  private static KnowledgeObject asset(Map<String, Object> attributes) {
    return new KnowledgeObject(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "data-asset:raw_events",
        1,
        KnowledgeType.DATA_ASSET,
        "raw_events",
        null,
        null,
        null,
        Map.of(),
        Map.of(),
        List.of(),
        "APPROVED",
        Instant.now(),
        null,
        1.0,
        Map.of(),
        Map.of(),
        null,
        "test",
        null,
        null,
        null,
        attributes,
        false);
  }
}
