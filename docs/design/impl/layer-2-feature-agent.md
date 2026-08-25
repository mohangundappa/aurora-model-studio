# Layer 2 Feature Agent

**Status: TO BUILD.** The current `InitiativeService.finishFeature` path is a
one-shot gateway call followed by `featureVerdicts`. This specification adds a
bounded feature loop and extracts the deterministic judge without changing
its authority.

Realises [Layer 2 design capabilities](../layer-2-design-capabilities.md), part of [the implementation specification index](README.md); prerequisite: [agent platform runtime](agent-platform-runtime.md).

## 1. Scope

Build a Feature Agent that searches governed metadata, detects exact governed
duplicates, drafts feature hypotheses and repairs them. Build a
`FeatureJudge` that owns source-column, observation-window, leakage,
point-in-time and reuse verdicts. It does not build features, query client
rows, approve a candidate or change `REUSE_THRESHOLD`.

## 2. Module and package layout

Use the existing `initiative` Maven module with the `agentplatform`
dependency. Create under
`initiative/src/main/java/com/aurora/studio/initiative/feature/`:

```text
FeatureDraft.java, FeatureVerdict.java, FeatureJudge.java
FeatureRepairLoop.java, GovernedFeatureCatalogTool.java
FeatureLoopDefinition.java
```

Extract the private method
`InitiativeService.featureVerdicts(Map<String,Object>, ModelRequirement,
List<KnowledgeObject>)` into the new `FeatureJudge` implementation. Preserve
the existing method's rule identifiers and statuses; `InitiativeService`
delegates to the extracted class during the transition.

## 3. Types

```java
public record FeatureDraft(
    String knowledgeKey,
    String name,
    String businessDefinition,
    String entity,
    String observationWindow,
    boolean pointInTimeAvailable,
    List<String> sourceColumns,
    List<EvidenceCitation> citations) {}

public record FeatureVerdict(
    String ruleId,
    String status,
    String reason,
    List<EvidenceCitation> citations) {}

public record FeatureCatalogLookup(
    String knowledgeKey,
    String name,
    String entity,
    List<String> sourceColumns,
    String observationWindow,
    boolean pointInTimeAvailable,
    Map<String, Object> canonicalAttributes,
    UUID knowledgeObjectId) {}

public interface FeatureJudge {
  List<FeatureVerdict> judge(
      FeatureDraft draft,
      ModelRequirement requirement,
      List<KnowledgeObject> governedAssets,
      List<FeatureCatalogLookup> existingFeatures);
}
```

The LLM schema remains the existing feature schema:
`name`, `businessDefinition`, `entity`, `observationWindow`,
`pointInTimeAvailable` and `sourceColumns`, wrapped in required `drafts[]`.
`knowledgeKey` is derived deterministically when absent; it is not model
chosen evidence.

## 4. Behaviour

`FeatureRepairLoop.run`:

1. Read the requirement and governed data-asset metadata from
   `KnowledgeService`; read approved feature catalog entries with their
   `knowledgeKey`, name, attributes and evidence.
2. Register `GovernedFeatureCatalogTool` and the deterministic
   `FeatureJudge`. The catalog tool returns metadata only.
3. Build an LLM request with task id
   `feature-repair-{stageAttemptId}-{attempt}`, template id
   `feature-repair`, version `1`, schema id `feature-repair-v1`, the existing
   redaction policy, 1200 output tokens and a ten-second provider timeout.
4. For every returned draft, normalise strings and source-column ordering only
   for comparison. Preserve the source-column list order in the submitted
   draft and preserve all cited evidence ids.
5. Apply the exact duplicate rule below before leakage and point-in-time
   verdicts. A duplicate yields `REUSE`, not a newly created feature
   candidate.
6. Persist the attempt and verdicts. A failed draft is the only input to a
   repair prompt; the prompt includes the prior structured verdict records,
   not a model-generated explanation of why it failed.
7. Create a knowledge candidate only after a deterministic `ACCEPTED` result,
   using the existing `KnowledgeService.createExtracted` path and
   `knowledge.addEvidence` plus `knowledge.addFieldProvenance`. The candidate
   remains governed by its existing lifecycle and is not approved by the loop.

### Exact duplicate matching

The comparator is `FeatureDuplicateMatcher` (new):

1. If the draft declares a non-blank `knowledgeKey`, compare
   `trim().toLowerCase(Locale.ROOT)` for exact equality with an approved
   feature key.
2. Otherwise compare all of: case-insensitive trimmed `name`, case-insensitive
   trimmed `entity`, `observationWindow` after whitespace normalisation,
   `pointInTimeAvailable`, and the set of lower-case trimmed `sourceColumns`.
3. Compare canonical attributes by recursively sorted JSON equality after
   removing only generated metadata fields (`llmInvocationId`, lifecycle
   fields and timestamps). Any remaining attribute difference means no match.

There is no edit distance, embedding similarity, token overlap or threshold in
this comparator. A match returns the existing object id and `REUSE`; only no
match can proceed to candidate creation.

### Change to `InitiativeService.finishFeature`

Current signature, retained:

```java
private Initiative finishFeature(
    InitiativeRepository.Base base,
    InitiativeRepository.Attempt attempt,
    Instant started)
```

Pseudo-diff:

```diff
 private Initiative finishFeature(Base base, Attempt attempt, Instant started) {
-  LlmResult result = gateway.complete(new LlmRequest(... "feature-design", ...));
-  if (!result.successful()) return finishProviderFailure(...);
-  for (Map<String,Object> draft : drafts(result.payload())) {
-    List<ValidatorVerdict> verdicts = featureVerdicts(draft, requirement, assets);
-    if ("ACCEPTED".equals(outcome)) createFeatureCandidate(draft, result.invocationId());
-  }
-  return finishGeneratedStage(...);
+  LoopOutcome<FeatureDraft, FeatureVerdict> outcome =
+      featureRepairLoop.run(base.id(), attempt.id(),
+          discovery.getRequirement(base.requirementId()));
+  saveFeatureAttempts(attempt, started, outcome);
+  if (outcome.termination() == LoopTermination.ACCEPTED) {
+    createFeatureCandidate(outcome.lastDraft(), outcome.lastInvocationId());
+  }
+  return get(base.id());
 }
```

The actual implementation must adapt `LoopOutcome` to the existing
`GenerationDraft` JSON shape and preserve all current stage summary writes.
`createFeatureCandidate` remains below the human gate's existing design-stage
boundary; no new candidate is treated as approved.

## 5. Schema

No feature-specific migration is allocated. V15/V16 persist attempts, tool
calls and loop linkage. Existing `knowledge_objects`,
`knowledge_evidence` and `knowledge_field_provenance` remain the storage for a
generated feature candidate and its cited generation record. The
`knowledge_field_provenance.citation_evidence_id` foreign key must point to
the evidence row inserted in the same candidate-creation transaction.

## 6. HTTP contract

Retain:

```text
POST /api/initiatives/{id}/stages/FEATURE_DESIGN/run
```

No request body is required. The response is the existing `Initiative` JSON.

| Condition | Status | Result |
| --- | --- | --- |
| Valid run | 200 | Existing initiative and stage attempt |
| Missing predecessor | 400 | Existing error body |
| Unknown initiative | 404 | Existing error body |
| Exact duplicate | 200 | `REUSE` verdict; no new candidate |
| Failed judge | 200 | Repaired or blocked stage summary |
| Uncited field | 200 | `UNKNOWN`; human gate required |
| Provider refusal/failure | 200 | `PROVIDER_FAILED` |

## 7. Configuration

Use the shared `aurora.agent.*` properties. The feature loop default is three
application attempts, twelve tool calls, ten-second tool timeout and
two-minute total loop timeout. No feature-specific property may change a
deterministic rule or duplicate comparator.

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `FEATURE-GOVERNED-SOURCE-COLUMNS` | Every declared source column is present in governed asset metadata. |
| `FEATURE-OBSERVATION-WINDOW` | The observation window ends before the as-of point. |
| `FEATURE-NO-TARGET-LEAKAGE` | Draft text and declared fields must not reference required target observables. |
| `FEATURE-POINT-IN-TIME-DECLARED` | `pointInTimeAvailable` must be explicitly present; missing declaration is `UNKNOWN`. |
| `FEATURE-EXACT-DUPLICATE` | Key equality or complete exact attribute equality returns `REUSE`. |
| `FEATURE-CITED-EVIDENCE` | Every field used to satisfy a rule has a valid evidence citation. |
| `FEATURE-REUSE-BEFORE-CREATION` | No candidate is created while an approved exact duplicate exists. |

The current source implementation checks target leakage with a textual
representation and checks `pointInTimeAvailable` as a declared boolean. The
extracted judge must preserve those behaviours first; semantic leakage
improvements require a separate decision.

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | --- |
| Missing source columns | `UNKNOWN` | Attempt and source-column verdict | 200 |
| Ungoverned source column | `REJECTED` | Attempt and failure verdict | 200 |
| Observation window not before as-of | `REJECTED` | Attempt and window verdict | 200 |
| Target observable appears | `REJECTED` | Attempt and leakage verdict | 200 |
| Missing point-in-time declaration | `HUMAN_GATE_REQUIRED` | Attempt and UNKNOWN verdict | 200 |
| Exact duplicate | `REUSE` | Attempt and existing object reference | 200 |
| Uncited field | `HUMAN_GATE_REQUIRED` | Attempt with failed citation record | 200 |
| Provider failure | `PROVIDER_FAILED` | Invocation and attempt | 200 |
| Budget exhausted with FAIL-only verdicts | `BLOCKED` | All attempts and stage summary | 200 |

## 10. Tests to write

Unit tests:

- `FeatureDuplicateMatchesExactKnowledgeKeyOnly`.
- `FeatureDuplicateMatchesAllNormalisedAttributes`.
- `FeatureDuplicateRejectsOneAttributeDifference`.
- `FeatureDuplicateDoesNotUseFuzzyNameMatching`.
- `FeatureJudgeRejectsTargetLeakage`.
- `FeatureJudgeUnknownsMissingPointInTimeDeclaration`.
- `FeatureJudgeRejectsUncitedThresholdInput`.
- `FeatureRepairPromptUsesStructuredVerdicts`.

`InitiativeServiceTest` additions:

- `featureRepairPersistsEveryAttempt`.
- `nearDuplicateFeatureReturnsReuseWithoutCandidateCreation`.
- `acceptedFeatureCandidateCarriesGenerationEvidenceAndProvenance`.
- `featureUnknownAwaitsApprovalWithoutMachineDecision`.
- `featureProviderFailureRecordsInvocation`.

Repository/Testcontainers tests:

- `featureAttemptLinksToClientScopedLoop`.
- `featureCandidateEvidenceAndProvenanceShareClient`.
- `featureAttemptsCannotBeUpdatedOrDeleted`.

## 11. Acceptance criteria

- [ ] `featureVerdicts` logic is extracted without changing current rule ids.
- [ ] Duplicate matching is exact and testable, with no fuzzy fallback.
- [ ] Every repair attempt has a ledger row and invocation linkage.
- [ ] Generated candidates retain evidence and field provenance.
- [ ] No feature build or client-data query occurs in Layer 2.
- [ ] The loop does not approve candidates or alter thresholds.
- [ ] Existing route and service signature remain compatible.

## 12. Open decisions

- Whether the canonical feature key should be supplied by the requirement or
  derived from entity/name. Recommendation: derive it only when absent and
  store the derivation in the draft for audit.
- Whether textual target leakage should become parser-aware. Recommendation:
  retain current behaviour in this implementation and create a separate
  validator change for semantic coverage.
