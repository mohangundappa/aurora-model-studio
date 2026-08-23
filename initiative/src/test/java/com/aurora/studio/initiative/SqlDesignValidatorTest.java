package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.studio.common.KnowledgeType;
import com.aurora.studio.common.RelationshipType;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeRelationship;
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
            "SELECT event_id, event_time FROM raw_events "
                + "WHERE event_name = 'BOOKING_COMPLETED' AND event_time <= :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(leakage)
        .anySatisfy(
            verdict ->
                assertThat(verdict.reason()).contains("target observable BOOKING_COMPLETED"));

    List<ValidatorVerdict> forward =
        SqlDesignValidator.validateCohort(
            "SELECT event_id, event_time FROM raw_events WHERE event_time > :as_of",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(forward)
        .anySatisfy(verdict -> assertThat(verdict.reason()).contains("forward-looking predicate"));
  }

  @Test
  void acceptsBoundedLookbackPredicate() {
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT event_id, event_time FROM raw_events "
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
            "SELECT event_id, made_up FROM raw_events WHERE event_time <= :as_of",
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
            "SELECT event_id, event_time FROM raw_events; SELECT event_id, event_time FROM raw_events",
            REQUIREMENT,
            List.of(rawEvents()));
    assertThat(multiple)
        .anySatisfy(verdict -> assertThat(verdict.reason()).contains("multiple SQL statements"));
  }

  @Test
  void absentGovernedColumnsAreUnknown() {
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT event_id, event_time FROM raw_events WHERE event_time <= :as_of",
            REQUIREMENT,
            List.of(assetWithoutColumns()));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("governed-references"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("UNKNOWN");
  }

  @Test
  void outputContractsFollowDeclaredAssetMetadata() {
    KnowledgeObject asset = customAsset("subject_key", "occurred_at");
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT subject_key, occurred_at FROM custom_events " + "WHERE occurred_at <= :as_of",
            REQUIREMENT,
            List.of(asset));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("output-contract"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("PASS", "PASS");
  }

  @Test
  void missingContractMetadataRemainsUnknown() {
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT event_id, event_time FROM raw_events WHERE event_time <= :as_of",
            REQUIREMENT,
            List.of(assetWithoutColumns()));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("output-contract"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("UNKNOWN", "UNKNOWN");
  }

  @Test
  void derivedTargetColumnIsRejectedThroughGovernedLineage() {
    KnowledgeObject asset =
        assetWithColumns(
            "derived_events",
            List.of(
                Map.of("name", "subject_key"),
                Map.of("name", "occurred_at"),
                Map.of("name", "booking_score")),
            "subject_key",
            "occurred_at");
    KnowledgeObject implementation =
        object(
            "implementation:booking-score",
            "booking-score",
            KnowledgeType.IMPLEMENTATION,
            Map.of("targetEvent", "BOOKING_COMPLETED"));
    KnowledgeRelationship relationship =
        new KnowledgeRelationship(
            UUID.randomUUID(),
            asset.clientId(),
            implementation.id(),
            RelationshipType.DERIVED_FROM,
            asset.id(),
            null);
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT subject_key, occurred_at FROM derived_events "
                + "WHERE booking_score > 0 AND occurred_at <= :as_of",
            REQUIREMENT,
            List.of(asset),
            List.of(asset, implementation),
            List.of(relationship));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("target-leakage"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("FAIL");
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("target-leakage"))
        .extracting(ValidatorVerdict::reason)
        .singleElement()
        .satisfies(reason -> assertThat(reason).contains("BOOKING_COMPLETED"));
  }

  @Test
  void forwardLookingPredicateNestedInBooleanExpressionIsRejected() {
    KnowledgeObject asset = customAsset("subject_key", "occurred_at");
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateCohort(
            "SELECT subject_key, occurred_at FROM custom_events "
                + "WHERE (occurred_at <= :as_of AND subject_key IS NOT NULL) "
                + "OR (subject_key = 'x' AND occurred_at > :as_of)",
            REQUIREMENT,
            List.of(asset));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("point-in-time-safety"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("FAIL");
  }

  @Test
  void equivalentHorizonUnitsCompareAsDurations() {
    KnowledgeObject asset =
        assetWithColumns(
            "custom_events",
            List.of(
                Map.of("name", "subject_key"),
                Map.of("name", "occurred_at"),
                Map.of("name", "event_id")),
            "subject_key",
            "occurred_at");
    List<ValidatorVerdict> results =
        SqlDesignValidator.validateLabel(
            "SELECT subject_key, CASE WHEN event_id IS NOT NULL THEN 1 ELSE 0 END AS label "
                + "FROM custom_events WHERE occurred_at > :as_of "
                + "AND occurred_at <= :as_of + interval '720 hours'",
            REQUIREMENT,
            List.of(asset));
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("label-horizon-agreement"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("PASS");
    assertThat(results)
        .filteredOn(verdict -> verdict.name().equals("output-contract"))
        .extracting(ValidatorVerdict::status)
        .containsExactly("PASS");
  }

  private static KnowledgeObject rawEvents() {
    return asset(
        Map.of(
            "primaryKey",
            "event_id",
            "eventTime",
            "event_time",
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

  private static KnowledgeObject customAsset(String primaryKey, String eventTime) {
    return assetWithColumns(
        "custom_events",
        List.of(Map.of("name", primaryKey), Map.of("name", eventTime), Map.of("name", "event_id")),
        primaryKey,
        eventTime);
  }

  private static KnowledgeObject assetWithColumns(
      String name, List<Map<String, Object>> columns, String primaryKey, String eventTime) {
    return object(
        "data-asset:" + name,
        name,
        KnowledgeType.DATA_ASSET,
        Map.of("columns", columns, "primaryKey", primaryKey, "eventTime", eventTime));
  }

  private static KnowledgeObject asset(Map<String, Object> attributes) {
    return object("data-asset:raw_events", "raw_events", KnowledgeType.DATA_ASSET, attributes);
  }

  private static KnowledgeObject object(
      String key, String name, KnowledgeType type, Map<String, Object> attributes) {
    return new KnowledgeObject(
        UUID.randomUUID(),
        UUID.randomUUID(),
        key,
        1,
        type,
        name,
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
