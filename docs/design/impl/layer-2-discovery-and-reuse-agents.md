# Layer 2 Discovery and Reuse Agents

**Status: TO BUILD for both agents.** `DiscoveryService` recall, ranking,
explanation and reuse scorecard are BUILT deterministic/application paths.
This specification adds Python/LangGraph requirement interpretation,
clarification questions and evidence gathering without allowing an agent to
change recall, weights, dimensions or thresholds.

Realises [Layer 2 design capabilities](../layer-2-design-capabilities.md), part of [the implementation specification index](README.md); prerequisites: [agent platform runtime](agent-platform-runtime.md) and [Python agent runtime](agent-runtime-python.md).

## 1. Scope

The Discovery Agent interprets a requirement, identifies missing information,
asks typed clarification questions and compares candidates. The Reuse Evidence
Agent gathers cited evidence for each reuse dimension and prepares a human
review packet. Neither agent computes or changes a score, `DiscoveryWeights`,
`REUSE_THRESHOLD`, candidate eligibility or access control. Neither accesses
client rows.

## 2. Module and package layout

Keep deterministic recall, ranking and scorecard inspection in the existing
`discovery` Maven module. Create the agent graphs under
`python/agent_service/agent_service/capabilities/discovery.py` and
`reuse.py`, alongside these Java records and integration types:

```text
discovery/src/main/java/com/aurora/studio/discovery/ClarificationQuestion.java
discovery/src/main/java/com/aurora/studio/discovery/ClarificationAnswer.java
discovery/src/main/java/com/aurora/studio/discovery/DiscoveryAgentResult.java
discovery/src/main/java/com/aurora/studio/discovery/ReuseEvidenceResult.java
discovery/src/main/java/com/aurora/studio/discovery/ReuseEvidencePacket.java
discovery/src/main/java/com/aurora/studio/discovery/CitedDimensionEvidence.java
```

The Python graphs call Java's authenticated tool, completion and append routes.
If an agent is invoked through an initiative, `initiative` owns the
orchestration call and persists the stage summary. The six dimensions,
weights and threshold remain Java-owned.

## 3. Types

```java
public record ClarificationQuestion(
    String questionId,
    String fieldPath,
    String question,
    QuestionKind kind,
    boolean required,
    List<EvidenceCitation> evidence) {}

public enum QuestionKind {
  MISSING_FIELD,
  AMBIGUOUS_TERM,
  CONFLICTING_REQUIREMENT,
  MISSING_TIME_BOUND,
  MISSING_POPULATION
}

public record ClarificationAnswer(
    String questionId,
    String answer,
    String actor,
    Instant answeredAt,
    List<EvidenceCitation> citations) {}

public interface DiscoveryAgent {
  DiscoveryInterpretation interpret(
      UUID initiativeId, ModelRequirement requirement, List<KnowledgeObject> visible);
}

public record DiscoveryInterpretation(
    ModelRequirement interpretedRequirement,
    List<ClarificationQuestion> questions,
    List<DiscoveryCandidate> candidates,
    List<EvidenceCitation> citations) {}

public record CitedDimensionEvidence(
    String dimension,
    Object value,
    List<EvidenceCitation> citations,
    String gap) {}

public record ReuseEvidencePacket(
    UUID candidateId,
    List<CitedDimensionEvidence> dimensions,
    List<String> gaps,
    String recommendationArgument) {}

public interface ReuseEvidenceAgent {
  ReuseEvidencePacket gather(
      ModelRequirement requirement, DiscoveryCandidate candidate);
}
```

The six exact dimensions are `targetAlignment`, `populationAlignment`,
`horizonAlignment`, `featureAvailability`, `dataAvailability` and
`implementationAvailability`. Existing scorecards also carry
`evidenceStrength` and `executionEvidence`; those are evidence dimensions in
the current `DiscoveryWeights`, not reuse dimensions and must not be renamed.

## 4. Behaviour

### Discovery Agent LangGraph

1. Request the submitted `ModelRequirement` and visible governed knowledge
   through Java's registered discovery tools.
2. Identify absent or ambiguous fields using deterministic required-field
   checks. The model may phrase a question and group related context, but it
   cannot mark a required field answered.
3. Return each question to Java for persistence in the current initiative
   stage attempt's typed `generation_drafts` JSON as `questionId`, field path,
   kind and evidence ids. No new migration is allocated for a question table.
4. Return questions before candidate comparison when any required question is
   unanswered. The next stage remains blocked until all required questions have
   answers; Python does not write that stage status.
5. Accept an answer only through a route owned by the initiative controller,
   validate the non-blank actor and answer, verify citations, and append the
   answer to the stage attempt ledger. The actor is caller-supplied and
   unverified, matching the existing gate model.
6. Re-run interpretation with the original requirement plus all persisted
   answers. The deterministic `DiscoveryService.run` then performs embedding
   recall, ranking and classification.

### Reuse Evidence Agent LangGraph

1. Receive a candidate and its current deterministic scorecard from Java.
2. Retrieve evidence rows for each of the six dimensions through Java tools.
3. Produce one `CitedDimensionEvidence` record per dimension. Missing or
   uncited input is a gap and resolves to `UNKNOWN`.
4. Return the packet to Java, which stores it in the attempt ledger and sends
   it to the existing human gate. The agent never changes a scorecard value or
   classification.
5. `DiscoveryService` remains the sole owner of `REUSE_THRESHOLD = 0.80` and
   the scorecard calculation. A human may approve a proposal with unknown
   dimensions only through the existing gate and accepted-unknown contract.

## 5. Schema

No new migration is allocated. Questions, answers and cited dimension packets
are JSON records in V16-linked `agent_attempts.input`, `draft` and `verdicts`,
with the stage-level summary mirrored into existing
`initiative_stage_attempts.generation_drafts` and `feasibility_checks`.
Every citation references the existing client-scoped
`knowledge_evidence(client_id, id)` row. A future dedicated question table
must use the same composite foreign-key and append-only trigger pattern.

## 6. HTTP contract

Existing routes remain:

```text
POST /api/discovery/requirements
POST /api/discovery/runs
GET  /api/discovery/runs/{id}
```

Proposed clarification routes, owned by `InitiativeController`:

```text
GET  /api/initiatives/{id}/stages/KNOWLEDGE_DISCOVERY/clarifications
POST /api/initiatives/{id}/stages/KNOWLEDGE_DISCOVERY/clarifications/{questionId}/answer
```

Request and response records:

```java
public record AnswerClarificationRequest(
    String answer, String actor, List<EvidenceCitation> citations) {}

public record ClarificationResponse(
    String questionId,
    String fieldPath,
    String question,
    boolean required,
    String answer,
    List<EvidenceCitation> citations) {}
```

Example:

```json
{
  "answer": "Only consented active customers are in scope.",
  "actor": "analyst@example.invalid",
  "citations": [{"evidenceId":"00000000-0000-0000-0000-000000000010",
                 "fieldPath":"population","excerpt":"requirement note"}]
}
```

| Condition | Status | Body |
| --- | --- | --- |
| Questions read | 200 | `ClarificationResponse[]` |
| Answer accepted | 200 | Updated response and citation ids |
| Missing answer/actor | 400 | `{"error":"answer and actor are required"}` |
| Invalid question id | 404 | Existing error shape |
| Cross-client citation | 400 | `{"error":"evidence citation is not visible"}` |
| Unanswered required question | 200 on stage run | Stage remains `BLOCKED` |

## 7. Configuration

Use the existing `studio.discovery.*` properties for weights and embedding
provider. Add only these proposed properties:

| Property | Type | Default | Validation |
| --- | --- | --- | --- |
| `aurora.discovery.max-clarification-questions` | `int` | `8` | 1–20 |
| `aurora.discovery.require-citation` | `boolean` | `true` | must remain true in governed mode |

The existing `studio.discovery.weights` map remains configuration for
deterministic ranking, not agent input.

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `DISCOVERY-REQUIRED-QUESTION` | Every missing required field creates a question. |
| `DISCOVERY-QUESTION-ANSWERED` | A required question is unresolved until a non-blank answer is persisted. |
| `DISCOVERY-ANSWER-CITED` | A governed answer needs a visible evidence citation. |
| `DISCOVERY-RECALL-FLOOR` | Existing `RECALL_FLOOR = 0.20` remains code-owned. |
| `REUSE-SIX-DIMENSIONS` | Exactly the six named reuse dimensions feed the reuse decision. |
| `REUSE-THRESHOLD` | Existing `REUSE_THRESHOLD = 0.80` is immutable to agents. |
| `REUSE-EVIDENCE-CITED` | Each dimension value must cite evidence or resolve `UNKNOWN`. |
| `REUSE-NO-SCORE-WRITE` | Agents cannot write scorecard values or weights. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | --- |
| Missing required requirement field | Clarification required | Question in attempt JSON | 200 |
| Required question unanswered | `BLOCKED` | Question and stage summary | 200 |
| Uncited answer | `UNKNOWN` | Answer plus citation failure | 400 for answer; 200 gate summary |
| Missing evidence for dimension | `UNKNOWN` | Dimension packet and gap | 200 |
| Candidate below recall floor | `GENERATE` | Existing discovery run | 200 |
| High score with blocker | `NOT_RECOMMENDED` | Existing discovery run | 200 |
| Provider refusal during explanation | Deterministic result without prose | `llm_invocations` | 200 |
| Agent attempts threshold change | Refused | Tool-call refusal | 400 |

## 10. Tests to write

Discovery unit tests:

- `MissingPopulationCreatesRequiredQuestion`.
- `UnansweredRequiredQuestionBlocksInterpretation`.
- `ClarificationAnswerPersistsActorAndCitations`.
- `CrossClientClarificationCitationIsRejected`.
- `AnsweredQuestionsAreIncludedInNextInterpretation`.
- `DiscoveryAgentCannotChangeRecallFloor`.

Reuse unit tests:

- `ReuseEvidenceCreatesSixDimensionPacket`.
- `UncitedDimensionResolvesUnknown`.
- `ReuseAgentCannotChangeThreshold`.
- `ReuseAgentCannotChangeScorecard`.

Integration tests:

- `DiscoveryClarificationsRemainClientScoped` with Testcontainers.
- `ReuseEvidenceAndQuestionsAreReplayableFromAttemptLedger`.
- `DiscoveryRunStillUsesEmbeddingProviderForRecall`.
- `DiscoveryExplanationUsesGatewayOnlyForProse`.

Use existing `DiscoveryServiceTest` cases such as
`missingRecallDoesNotSilentlyFallBackToFullCorpus`,
`unknownDimensionsRemainNullWhileCompositeIsRenormalized` and
`providerOutagePreservesVerdictAndScorecardWithoutProse` as regression
fixtures.

## 11. Acceptance criteria

- [ ] Every required clarification has a stable id and persisted answer path.
- [ ] Unanswered questions block without being guessed by the model.
- [ ] Every reuse dimension packet contains a citation or an explicit gap.
- [ ] No agent writes `DiscoveryWeights`, `REUSE_THRESHOLD` or scorecard values.
- [ ] Discovery recall still uses `EmbeddingProvider`, not `LlmGateway`.
- [ ] Existing classification and blocker precedence remain unchanged.
- [ ] Cross-client citations are refused.

## 12. Open decisions

- Whether clarification answers deserve a dedicated append-only table.
  Recommendation: use V16 JSON for the first implementation, then add a table
  when the console requires queryable conversation history.
- Whether a human may answer without evidence for purely subjective
  requirements. Recommendation: permit it only as `UNKNOWN` and require
  explicit gate acceptance.
