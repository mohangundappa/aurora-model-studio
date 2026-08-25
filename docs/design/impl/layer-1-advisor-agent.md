# Layer 1 Advisor Agent

**Status: TO BUILD.** The Advisor Agent is a read-only diagnosis surface over
the built orchestration state machine. It cannot run a stage, decide a gate,
write an event, change an attempt or choose the next stage.

Realises [Layer 1 governed orchestration](../layer-1-orchestration.md), part of [the implementation specification index](README.md).

## 1. Scope

Build an advisor that reads initiative state, stage attempts, blockers,
feasibility checks, events, gate decisions and duration summaries, diagnoses
one blocked or waiting stage, and recommends recovery actions. It does not
execute recommendations, call write methods, infer missing evidence as fact,
or replace `InitiativeService`.

The first version remains deterministic Java. The client's Python agent rule
does not force a rewrite: if advisor wording becomes LLM-generated later, only
that narration runs in the Python agent service; diagnosis remains Java. No
current behaviour change is implied.

## 2. Module and package layout

Use the existing `initiative` module and add the `agentplatform` dependency
only if the recommendation wording uses the gateway. The first version
should be deterministic and not need an LLM. Create:

```text
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorAgent.java
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorReadRepository.java
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorService.java
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorDiagnosis.java
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorRecommendation.java
initiative/src/main/java/com/aurora/studio/initiative/advisor/AdvisorController.java
```

`AdvisorReadRepository` is a new interface backed by a read-only implementation
that may delegate SQL SELECTs to `InitiativeRepository` or use a separate
`JdbcTemplate` wrapper. It must not expose `insertAttempt`, `start`, `finish`,
`insertEvent`, `insertGateDecision`, `saveDrafts` or any other write method.

## 3. Types

```java
public interface AdvisorReadRepository {
  Optional<Initiative> findInitiative(UUID initiativeId);
  List<InitiativeRepository.Attempt> findAttempts(UUID initiativeId);
  List<InitiativeEvent> findEvents(UUID initiativeId);
  List<GateDecision> findGateDecisions(UUID initiativeId);
  DurationSummary summarizeDuration(UUID initiativeId);
}

public record AdvisorDiagnosis(
    UUID initiativeId,
    InitiativeStage stage,
    StageStatus status,
    List<String> blockers,
    List<FeasibilityCheck> unknownChecks,
    String diagnosis) {}

public record AdvisorRecommendation(
    String recommendationId,
    String action,
    String rationale,
    List<String> requiredInputs,
    boolean requiresHumanDecision) {}

public record AdvisorResponse(
    AdvisorDiagnosis diagnosis,
    List<AdvisorRecommendation> recommendations,
    Instant generatedAt) {}

public interface AdvisorAgent {
  AdvisorResponse advise(UUID initiativeId);
}
```

`InitiativeRepository.Attempt`, `InitiativeEvent`, `GateDecision` and
`DurationSummary` are existing types. The advisor reads their existing fields;
it does not create parallel workflow records.

## 4. Behaviour

1. `AdvisorController` obtains the initiative id and delegates to
   `AdvisorService`; it never injects `InitiativeService`.
2. `AdvisorService` loads the initiative, attempts, events, decisions and
   durations through `AdvisorReadRepository`.
3. Select the latest stage with `BLOCKED`, `AWAITING_APPROVAL`,
   `PROVIDER_FAILED`, `REJECTED` or `PENDING` behind a blocked predecessor.
4. Diagnose only from persisted blockers and named feasibility checks. If no
   persisted evidence explains the state, return
   `INSUFFICIENT_PERSISTED_EVIDENCE` rather than inventing a cause.
5. Build recommendations from a fixed mapping, for example
   `ANSWER_CLARIFICATION`, `REVIEW_UNKNOWN_CHECKS`, `RETRY_PROVIDER`,
   `RETURN_STAGE_FOR_REPAIR` or `SUPPLY_MISSING_KNOWLEDGE`.
6. Mark recommendations requiring a human decision. Do not call any write
   repository method and do not run the recommended stage.
7. Return a response assembled only from the read snapshot. No persistence is
   performed by an advisory call.

## 5. Schema

No migration is required. The advisor reads
`initiatives`, `initiative_stage_attempts`, `initiative_events`,
`initiative_gate_decisions` and their existing composite client-scoped keys.
No advisor audit row is written in this first version; HTTP access logging, if
needed, belongs to the application boundary and must not masquerade as a
workflow event.

## 6. HTTP contract

Proposed route:

```text
GET /api/initiatives/{id}/advisor
```

Example response:

```json
{
  "diagnosis": {
    "initiativeId": "00000000-0000-0000-0000-000000000020",
    "stage": "DATA_FEASIBILITY",
    "status": "AWAITING_APPROVAL",
    "blockers": [],
    "unknownChecks": [
      {"name":"data-asset-resolution","status":"UNKNOWN",
       "artifactId":null,"reason":"No governed data asset is linked"}
    ],
    "diagnosis": "Persisted feasibility evidence is incomplete."
  },
  "recommendations": [
    {"recommendationId":"REVIEW_UNKNOWN_CHECKS",
     "action":"Review and explicitly accept or reject the unknown checks.",
     "rationale":"The stage cannot complete without a human gate decision.",
     "requiredInputs":["acceptedUnknownChecks"],"requiresHumanDecision":true}
  ],
  "generatedAt":"2025-01-01T00:00:00Z"
}
```

| Condition | Status | Body |
| --- | --- | --- |
| Snapshot available | 200 | `AdvisorResponse` |
| Unknown initiative | 404 | Existing error response |
| No actionable blocker | 200 | Diagnosis with `NO_ACTIONABLE_BLOCKER` |
| Missing persisted evidence | 200 | Diagnosis with `INSUFFICIENT_PERSISTED_EVIDENCE` |
| Any attempted write | Must not occur | Test failure; no public success path |

## 7. Configuration

No new configuration is required for the deterministic first version. If
natural-language recommendations are later added, use existing gateway
properties plus:

| Property | Type | Default | Validation |
| --- | --- | --- | --- |
| `aurora.advisor.enabled` | `boolean` | `true` | must not grant write access |
| `aurora.advisor.max-recommendations` | `int` | `5` | 1–20 |

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `ADVISOR-READ-ONLY` | The advisor has no write repository or service dependency. |
| `ADVISOR-LATEST-ACTIONABLE` | Diagnose the latest persisted actionable stage. |
| `ADVISOR-NO-INVENTION` | Missing persisted evidence yields an explicit insufficient-evidence diagnosis. |
| `ADVISOR-BLOCKER-MAPPING` | Recommendations derive only from known blocker/check identifiers. |
| `ADVISOR-HUMAN-GATE` | Gate decisions remain human actions; recommendations cannot approve. |
| `ADVISOR-CLIENT-SCOPE` | All reads use the current client context. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | --- |
| Initiative missing | Refused | None | 404 |
| Blocked stage with known blocker | Diagnosis and recommendation | None | 200 |
| Unknown check awaiting approval | Human-gate recommendation | None | 200 |
| Provider-failed stage | Retry/recovery recommendation | None | 200 |
| No persisted reason | Insufficient evidence | None | 200 |
| Attempt to invoke write method | Design/test failure | None | Must be impossible |

## 10. Tests to write

Unit tests:

- `AdvisorSelectsLatestBlockedStage`.
- `AdvisorMapsUnknownChecksToHumanReview`.
- `AdvisorDoesNotInventDiagnosisWithoutPersistedEvidence`.
- `AdvisorProducesNoMoreThanConfiguredRecommendations`.
- `AdvisorReadRepositoryHasNoWriteMethods` using reflection on the interface.

`@SpringBootTest` tests:

- `AdvisorRouteReturnsReadOnlySnapshot`.
- `AdvisorRouteIsClientScoped`.
- `AdvisorCallLeavesInitiativeStateUnchanged`: snapshot attempts, events and
  decisions before and after a call and assert exact equality.

Testcontainers tests:

- `AdvisorReadsCompositeClientKeys`.
- `AdvisorCannotObserveAnotherClientInitiative`.

## 11. Acceptance criteria

- [ ] `AdvisorController` has no `InitiativeService` dependency.
- [ ] The read repository interface contains only SELECT-shaped methods.
- [ ] A complete advisory call changes no initiative table.
- [ ] Diagnoses cite persisted blockers/checks or say evidence is insufficient.
- [ ] Recommendations are labelled as recommendations and cannot execute.
- [ ] Cross-client attempts and events are invisible.

## 12. Open decisions

- Whether advisor calls require their own append-only audit table.
  Recommendation: add one only when access auditing is required; never use
  `initiative_events` because advisory reads are not state transitions.
- Whether recommendations should be prose generated by the gateway.
  Recommendation: deterministic templates first; if an LLM is added, keep the
  fixed recommendation code as the verdict.
