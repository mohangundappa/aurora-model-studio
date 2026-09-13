package com.aurora.studio.initiative;

import static com.aurora.studio.initiative.DesignPayloads.map;
import static com.aurora.studio.initiative.StageTiming.elapsed;

import com.aurora.studio.discovery.DiscoveryService;
import com.aurora.studio.discovery.ModelRequirement;
import com.aurora.studio.knowledge.KnowledgeObject;
import com.aurora.studio.knowledge.KnowledgeService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class ExperimentDesignStage {
  private static final String AGENT = InitiativeActors.ORCHESTRATOR;
  private final InitiativeRepository repository;
  private final DiscoveryService discovery;
  private final KnowledgeService knowledge;
  private final InitiativeQueries initiatives;
  private final InitiativeKnowledge lineage;

  ExperimentDesignStage(
      InitiativeRepository repository,
      DiscoveryService discovery,
      KnowledgeService knowledge,
      InitiativeQueries initiatives,
      InitiativeKnowledge lineage) {
    this.repository = repository;
    this.discovery = discovery;
    this.knowledge = knowledge;
    this.initiatives = initiatives;
    this.lineage = lineage;
  }

  Initiative finishExperiment(
      InitiativeRepository.Base base, InitiativeRepository.Attempt attempt, Instant started) {
    ModelRequirement requirement = discovery.getRequirement(base.requirementId());
    List<KnowledgeObject> visible = knowledge.search(null, null, null, null, null, null, true);
    KnowledgeObject outcome = lineage.findObservable(requirement.observableDefinition(), visible);
    List<FeasibilityCheck> checks = new ArrayList<>();
    List<String> blockers = new ArrayList<>();
    if (outcome == null) {
      checks.add(
          new FeasibilityCheck(
              "primary-outcome-observable",
              "FAIL",
              null,
              "Primary outcome is not a governed observable"));
      blockers.add("MISSING_OUTCOME_OBSERVABLE:" + requirement.observableDefinition());
    } else {
      checks.add(
          new FeasibilityCheck(
              "primary-outcome-observable", "PASS", outcome.id(), "Primary outcome is governed"));
    }
    List<Map<String, Object>> variants = experimentVariants(requirement);
    validateVariants(variants, checks, blockers);
    checks.add(
        new FeasibilityCheck(
            "primary-outcome-event",
            requirement.observableDefinition().isBlank() ? "FAIL" : "PASS",
            outcome == null ? null : outcome.id(),
            requirement.observableDefinition().isBlank()
                ? "Primary outcome event must be declared"
                : "Primary outcome event is declared"));
    Map<String, Object> sampleInputs = sampleInputs(requirement);
    List<String> missingInputs =
        List.of("baselineConversionRate", "minimumDetectableEffect", "alpha", "power").stream()
            .filter(key -> sampleInputs.get(key) == null)
            .toList();
    for (String input :
        List.of("baselineConversionRate", "minimumDetectableEffect", "alpha", "power")) {
      Number value = (Number) sampleInputs.get(input);
      String status = value == null ? "UNKNOWN" : validSampleInput(input, value) ? "PASS" : "FAIL";
      checks.add(
          new FeasibilityCheck(
              "sample-size-" + input,
              status,
              null,
              value == null
                  ? input + " is not available from governed material"
                  : status.equals("PASS") ? input + " is governed" : input + " is invalid"));
      if (status.equals("FAIL")) blockers.add("INVALID_SAMPLE_SIZE_INPUT:" + input);
    }
    boolean sampleInputsValid =
        missingInputs.isEmpty()
            && List.of("baselineConversionRate", "minimumDetectableEffect", "alpha", "power")
                .stream()
                .allMatch(input -> validSampleInput(input, (Number) sampleInputs.get(input)));
    Integer computed = sampleInputsValid ? sampleSize(sampleInputs) : null;
    boolean minimumExposureInvalid =
        variants.stream()
            .anyMatch(
                variant ->
                    variant.containsKey("minimumExposures")
                        && !positiveInteger(variant.get("minimumExposures")));
    boolean minimumExposureKnown =
        variants.stream().allMatch(variant -> positiveInteger(variant.get("minimumExposures")))
            || computed != null;
    checks.add(
        new FeasibilityCheck(
            "minimum-exposures",
            minimumExposureInvalid ? "FAIL" : minimumExposureKnown ? "PASS" : "UNKNOWN",
            null,
            minimumExposureInvalid
                ? "Minimum exposures must be positive integers"
                : minimumExposureKnown
                    ? "Minimum exposures computed with a deterministic two-proportion calculation"
                    : "Minimum exposures are UNKNOWN until all named sample-size inputs are governed"));
    if (variants.stream().anyMatch(v -> !v.containsKey("minimumExposures"))) {
      variants =
          variants.stream()
              .map(
                  variant -> {
                    Map<String, Object> copy = new LinkedHashMap<>(variant);
                    copy.putIfAbsent("minimumExposures", computed);
                    return copy;
                  })
              .toList();
    }
    Map<String, Object> design = new LinkedHashMap<>();
    design.put("primaryOutcomeEvent", requirement.observableDefinition());
    design.put("variants", variants);
    design.put(
        "allocationSource",
        hasConfiguredExperimentVariants(requirement) ? "REQUIREMENT" : "DEFAULT");
    design.put("measurementWindow", requirement.outcomeHorizon());
    design.put("decisionRule", decisionRule(sampleInputs, computed));
    Map<String, Object> sampleSize = new LinkedHashMap<>(sampleInputs);
    sampleSize.put("minimumExposuresPerVariant", computed);
    design.put("sampleSize", sampleSize);
    GenerationDraft draft =
        new GenerationDraft(
            "EXPERIMENT", design, blockers.isEmpty() ? "ACCEPTED" : "REJECTED", null, List.of());
    boolean unknown = checks.stream().anyMatch(check -> check.status().equals("UNKNOWN"));
    StageStatus status =
        !blockers.isEmpty()
            ? StageStatus.BLOCKED
            : unknown ? StageStatus.AWAITING_APPROVAL : StageStatus.COMPLETED;
    List<String> violated =
        checks.stream()
            .filter(check -> !check.status().equals("PASS"))
            .map(check -> check.name() + ":" + check.reason())
            .toList();
    Instant finished = Instant.now();
    repository.saveDrafts(attempt.id(), List.of(draft), violated);
    if (status == StageStatus.AWAITING_APPROVAL) {
      repository.awaitApproval(
          attempt.id(), finished, elapsed(started, finished), blockers, checks, List.of());
    } else {
      repository.finish(
          attempt.id(),
          status,
          finished,
          elapsed(started, finished),
          0,
          blockers,
          checks,
          List.of());
    }
    repository.insertEvent(
        base.id(),
        attempt.stage(),
        StageStatus.IN_PROGRESS,
        status,
        AGENT,
        status == StageStatus.AWAITING_APPROVAL
            ? "Unknown sample-size inputs require explicit human acceptance"
            : blockers.isEmpty() ? "Experiment design completed" : "Experiment design blocked",
        List.of());
    return initiatives.get(base.id());
  }

  private boolean hasConfiguredExperimentVariants(ModelRequirement requirement) {
    Object configured = requirement.constraints().get("experimentVariants");
    return configured instanceof Collection<?> values && !values.isEmpty();
  }

  private List<Map<String, Object>> experimentVariants(ModelRequirement requirement) {
    Object configured = requirement.constraints().get("experimentVariants");
    if (configured instanceof Collection<?> values && !values.isEmpty()) {
      return values.stream()
          .filter(Map.class::isInstance)
          .map(value -> map((Map<?, ?>) value))
          .toList();
    }
    return List.of(
        Map.of("name", "control", "role", "CONTROL", "allocation", 50),
        Map.of("name", "treatment", "role", "TREATMENT", "allocation", 50));
  }

  private void validateVariants(
      List<Map<String, Object>> variants, List<FeasibilityCheck> checks, List<String> blockers) {
    long controls =
        variants.stream()
            .filter(v -> "CONTROL".equalsIgnoreCase(String.valueOf(v.getOrDefault("role", ""))))
            .count();
    long treatments =
        variants.stream()
            .filter(v -> "TREATMENT".equalsIgnoreCase(String.valueOf(v.getOrDefault("role", ""))))
            .count();
    long allocation =
        variants.stream()
            .map(v -> number(v.get("allocation")))
            .filter(java.util.Objects::nonNull)
            .mapToLong(Number::longValue)
            .sum();
    boolean namesValid =
        variants.stream()
                .map(v -> String.valueOf(v.getOrDefault("name", "")).trim())
                .noneMatch(String::isBlank)
            && variants.stream()
                    .map(v -> String.valueOf(v.getOrDefault("name", "")).trim().toLowerCase())
                    .distinct()
                    .count()
                == variants.size()
            && variants.stream()
                .map(v -> String.valueOf(v.getOrDefault("name", "")))
                .allMatch(name -> name.codePointCount(0, name.length()) <= 120);
    boolean allocationsValid =
        variants.stream().allMatch(v -> positiveInteger(v.get("allocation"))) && allocation == 100;
    checks.add(
        new FeasibilityCheck(
            "variant-roles",
            controls == 1 && treatments >= 1 ? "PASS" : "FAIL",
            null,
            controls == 1 && treatments >= 1
                ? "Exactly one control and at least one treatment"
                : "Experiment requires exactly one control and at least one treatment"));
    checks.add(
        new FeasibilityCheck(
            "variant-names",
            namesValid ? "PASS" : "FAIL",
            null,
            namesValid
                ? "Variant names are unique, non-blank, and fit Aurora's varchar(120)"
                : "Variant names must be unique, non-blank, and at most 120 characters"));
    checks.add(
        new FeasibilityCheck(
            "variant-allocations",
            allocationsValid ? "PASS" : "FAIL",
            null,
            allocationsValid
                ? "Variant allocations are positive integers summing to 100"
                : "Variant allocations must be positive integers summing to 100"));
    checks.add(
        new FeasibilityCheck(
            "variant-minimum-exposures",
            variants.stream()
                    .allMatch(
                        v ->
                            !v.containsKey("minimumExposures")
                                || positiveInteger(v.get("minimumExposures")))
                ? "PASS"
                : "FAIL",
            null,
            "Minimum exposures must be positive when declared"));
    if (controls != 1 || treatments < 1) blockers.add("INVALID_VARIANT_ROLES");
    if (!namesValid) blockers.add("INVALID_VARIANT_NAMES");
    if (!allocationsValid) blockers.add("INVALID_VARIANT_ALLOCATIONS");
    if (checks.get(checks.size() - 1).status().equals("FAIL")) {
      blockers.add("INVALID_MINIMUM_EXPOSURES");
    }
  }

  private Map<String, Object> sampleInputs(ModelRequirement requirement) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key :
        List.of("baselineConversionRate", "minimumDetectableEffect", "alpha", "power")) {
      result.put(key, number(requirement.constraints().get(key)));
    }
    return result;
  }

  private Number number(Object value) {
    if (value instanceof Number number) return number;
    if (value == null) return null;
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private boolean positiveInteger(Object value) {
    Number parsed = number(value);
    return parsed != null
        && Double.isFinite(parsed.doubleValue())
        && parsed.doubleValue() > 0
        && parsed.doubleValue() == Math.rint(parsed.doubleValue());
  }

  private boolean validSampleInput(String key, Number value) {
    double parsed = value.doubleValue();
    if (!Double.isFinite(parsed)) return false;
    return switch (key) {
      case "baselineConversionRate" -> parsed > 0 && parsed < 1;
      case "minimumDetectableEffect" -> parsed > 0 && parsed < 1;
      case "alpha", "power" -> parsed > 0 && parsed < 1;
      default -> false;
    };
  }

  private Integer sampleSize(Map<String, Object> inputs) {
    double baseline = ((Number) inputs.get("baselineConversionRate")).doubleValue();
    double effect = ((Number) inputs.get("minimumDetectableEffect")).doubleValue();
    double alpha = ((Number) inputs.get("alpha")).doubleValue();
    double power = ((Number) inputs.get("power")).doubleValue();
    if (baseline <= 0
        || baseline >= 1
        || effect <= 0
        || alpha <= 0
        || alpha >= 1
        || power <= 0
        || power >= 1) return null;
    double treatment = Math.min(0.999999, baseline + effect);
    double pooled = (baseline + treatment) / 2;
    double za = normalQuantile(1 - alpha / 2);
    double zp = normalQuantile(power);
    double value =
        Math.pow(
                za * Math.sqrt(2 * pooled * (1 - pooled))
                    + zp * Math.sqrt(baseline * (1 - baseline) + treatment * (1 - treatment)),
                2)
            / Math.pow(treatment - baseline, 2);
    return (int) Math.ceil(value);
  }

  private String decisionRule(Map<String, Object> inputs, Integer minimumExposuresPerVariant) {
    if (minimumExposuresPerVariant == null) return "UNKNOWN";
    return String.format(
        java.util.Locale.ROOT,
        "Ship if the observed primary outcome improvement is at least %.4f after at least %d exposures per variant, evaluated with two-sided alpha %.4f and power %.4f; otherwise iterate or stop.",
        ((Number) inputs.get("minimumDetectableEffect")).doubleValue(),
        minimumExposuresPerVariant,
        ((Number) inputs.get("alpha")).doubleValue(),
        ((Number) inputs.get("power")).doubleValue());
  }

  private double normalQuantile(double probability) {
    double[] coefficients = {
      -39.6968302866538,
      220.946098424521,
      -275.928510446969,
      138.357751867269,
      -30.6647980661472,
      2.50662827745924
    };
    double[] denominator = {
      -54.4760987982241, 161.585836858041, -155.698979859887, 66.8013118877197, -13.2806815528857
    };
    double[] lower = {
      -0.00778489400243029,
      -0.322396458041136,
      -2.40075827716184,
      -2.54973253934373,
      4.37466414146497,
      2.93816398269878
    };
    double[] upper = {0.00778469570904146, 0.32246712907004, 2.445134137143, 3.75440866190742};
    if (probability < 0.02425) {
      double q = Math.sqrt(-2 * Math.log(probability));
      return polynomial(lower, q) / (polynomial(upper, q) * q + 1);
    }
    if (probability > 1 - 0.02425) {
      double q = Math.sqrt(-2 * Math.log(1 - probability));
      return -polynomial(lower, q) / (polynomial(upper, q) * q + 1);
    }
    double q = probability - 0.5;
    double r = q * q;
    return polynomial(coefficients, r) * q / (polynomial(denominator, r) * r + 1);
  }

  private double polynomial(double[] coefficients, double value) {
    double result = 0;
    for (double coefficient : coefficients) result = result * value + coefficient;
    return result;
  }
}
