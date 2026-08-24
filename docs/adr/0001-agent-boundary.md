# ADR 0001: Where AI agents fit, and where they must not

- Status: accepted
- Scope: Model Studio capability layers and their technology stack
- Supersedes: nothing

## Context

Model Studio today contains four LLM call sites and no agents. Each is a single
application call site: a prompt is sent, a strict JSON response is validated
against a schema, and the stage ends. The gateway may retry retryable provider
responses, but there is no agent loop, tool use, planning step, or component
that chooses what happens next.

The four call sites are the only uses of `LlmGateway.complete`:

| Call site | Purpose |
| --- | --- |
| `ExtractionService` | Interpret a parsed source artifact into governed attributes |
| `DiscoveryService` | Write explanation prose for a ranking it did not compute |
| `InitiativeService` (targeting) | Draft cohort SQL and optional label SQL |
| `InitiativeService` (feature) | Draft feature designs |

Discovery recall does not use the gateway; it uses `EmbeddingProvider`
(`DeterministicEmbeddingProvider` by default, `OpenAiEmbeddingProvider` opt-in).

Everything that gates a decision is deterministic code: the reuse scorecard and
its `REUSE_THRESHOLD` of `0.80` across six dimensions, feasibility verdicts of
`PASS`, `FAIL` or `UNKNOWN`, `SqlDesignValidator` (read-only, governed
references, required projections, point-in-time bounding, target leakage),
feature validation including target leakage and the point-in-time declaration,
sample-size arithmetic, stage predecessors, human gates, and the append-only
audit and handoff-attempt records enforced by database triggers.

Agentic capability is a capability the market wants and a capability this
platform genuinely lacks. The risk is not that agents are wrong here; it is that
adding them carelessly moves guarantees out of code and into a model's
judgement, which is the one property this product sells.

## Decision

**Agents own gathering, interpreting and iterating. Deterministic code owns the
verdict.**

An agent may act, observe a real outcome, and revise. An agent may not produce a
number that gates a decision, choose a threshold, select the next stage, approve
its own work, or supply a missing input by inference and have that input treated
as observed.

### Capability map

| Capability | Agent? | Agent responsibility | Must remain deterministic | Today |
| --- | --- | --- | --- | --- |
| Model Discovery | Yes | Interpret the requirement, identify missing information, ask for clarification, compare candidates, explain reuse potential | Similarity calculation, eligibility filters, access control, the reuse threshold | Deterministic recall and ranking, plus one application explanation call |
| Reuse Intelligence | Partially | Gather the evidence behind each dimension, explain why an asset is or is not reusable, name the gaps, recommend reuse versus rebuild | Six-dimension scoring, weights, the `0.80` threshold, mandatory policy rules | Fully deterministic; no LLM |
| Data Discovery | Yes, strong candidate | Explore the catalog, inspect schemas, profile candidate datasets, test grain, history and null rates, resolve `UNKNOWN` checks, propose joins and sources | Data-access permission, approved-query limits, sensitive-data controls, quality thresholds | Deterministic checks over declared metadata only |
| Targeting Design | Yes, strong candidate | Draft the design, read the validator verdicts, interpret the failures, repair, repeat within a bounded attempt budget | SQL safety rules, allowed tables and columns, business constraints, the approval gate | One application draft; validators accept or reject and the stage ends |
| Feature Intelligence | Yes, strong candidate | Search the governed feature catalog, generate hypotheses, detect duplicates of existing governed features, refine after validation | Leakage rules, feature certification, approved transformations, point-in-time correctness | One application draft with deterministic validation |
| Experiment planning | Yes | Propose experiments, propose a candidate algorithm set for a human to choose from, recommend the next experiment | Sample-size arithmetic, statistical tests, acceptance thresholds, promotion criteria | Deterministic variant validation and sample-size mathematics; no LLM |
| Experiment execution | Yes, but blocked | Launch runs, compare results, analyse failures | Promotion criteria, evaluation gates | Does not exist; `CANDIDATE_BUILD` is `OUT_OF_SCOPE` |
| Orchestrator | No as the system of record, yes as an advisor | Recommend the next action, summarise state, diagnose a blocked stage, suggest recovery | Stage sequence, predecessors, human gates, persisted state transitions, authorisation | Deterministic state machine; no advisor exists |
| Extraction | Yes | Extract, self-check the interpretation against the source excerpt, retry ambiguous fields, cross-check related artifacts, flag unresolved contradictions | Schema validation, source lineage, confidence thresholds, required-field rules | Single application interpretation pass per artifact |

Three notes where the capability is narrower or wider than its label:

- Experimentation is two agents, not one. Planning is buildable against the
  existing deterministic design mathematics. Execution requires an ML Execution
  Service that does not exist, so there is nothing to launch a run against and
  no artifact to compare.
- Proposing a candidate algorithm set is a recommendation. Selecting the
  algorithm that is then promoted is a decision, and stays with a human.
- Model Discovery already performs recall, ranking and explanation. The new
  agentic behaviour is requirement interpretation and asking for clarification
  when the requirement is under-specified.

### Agent-supplied evidence rule

Keeping the scorers deterministic stops protecting the outcome once an agent
supplies what the scorers read. A dimension input established by agent inference
rather than by a source excerpt would produce a number that looks computed and
is not.

Therefore:

1. An input an agent supplies must record its provenance and its citation, using
   the existing `FieldProvenance` fields (`provenance`, `citationEvidenceId`,
   `citationExcerpt`, `extractionCertainty`).
2. An input asserted by inference, without a citation, must not silently satisfy
   a threshold. The affected check resolves as `UNKNOWN` and reaches a human
   gate, in the same way an unknown feasibility check with no blockers already
   resolves to `AWAITING_APPROVAL` rather than `COMPLETED`.
3. Disagreement between an agent's assertion and existing governed knowledge is
   recorded as a conflict rather than overwritten.

This policy uses existing capabilities rather than requiring a new subsystem:
field-level provenance, evidence excerpts, confidence weighting and
`knowledge_conflicts` already exist.

### Readiness

Buildable now, because the environment an agent would act against already
exists:

- Targeting Design and Feature Intelligence — one bounded repair-loop agent
  serves both, judged by the existing validators.
- Extraction self-check.
- Orchestrator advisor, read-only.
- Requirement clarification in Model Discovery.
- Reuse evidence gathering.

Blocked until the execution layer exists:

- Data Discovery, which needs warehouse access to profile anything.
- Experiment execution, which needs an ML Execution Service.

The first slice is the repair-loop agent: draft, run the validators, feed the
verdicts back, redraft within a fixed attempt budget, persist every attempt. It
is the only agent whose judging environment is already built, and it is the piece
that makes generation demonstrable rather than asserted. This is not a
theoretical gap: in observed runs against a real provider, targeting design was
rejected on the first attempt every time, so without a repair loop the stage ends
`BLOCKED` and nothing downstream runs. The verdicts that rejected it are exactly
the feedback a repair loop would consume.

### Technology

| Layer | Runtime | Rationale |
| --- | --- | --- |
| Enterprise knowledge, orchestration state, human gates, validators, thresholds, sample-size mathematics, audit and database invariants | Java 21 and Spring Boot, as today | Guarantees are enforced by schema constraints, append-only triggers and typed deterministic code, and must stay reproducible |
| Data and feature execution, ML execution, profiling, training, tuning, evaluation, model artifacts, client platform SDKs | Python service over HTTP | The required ecosystem is Python-first in practice; reimplementing it in Java to avoid a second runtime is the worse trade |
| Bounded agent internals: repair loops, self-check passes, extraction fan-out | Python, optionally LangGraph | Cyclic, typed, bounded workflow state is what a graph executor is for |

Rejected:

- **LangGraph for the orchestrator.** Its state lives in a checkpointer, not in a
  schema with constraints and append-only triggers. Adopting it for the
  nine-stage workflow would move governance from "the database refuses" to "the
  framework remembers".
- **AutoGen.** Conversational multi-agent autonomy is the opposite of a governed,
  auditable stage graph.
- **LangChain as the provider boundary.** The gateway already owns provider
  selection, redaction, strict response schemas and the `llm_invocations` audit.
  Any framework adopted calls through the gateway rather than replacing it.

Costs accepted: a second runtime, a second CI pipeline, contract tests across
the seam, and weaker typing in Python, which is why correctness-critical
arithmetic stays on the Java side.

## Consequences

- Three of the six capability boxes contain no model today and are expected to
  keep their verdicts model-free after agents arrive.
- Agentic behaviour becomes a bounded loop against a deterministic judge, which
  is testable: the judge is the specification.
- Refusal remains a feature. An agent that cannot move a threshold, cannot
  approve its own work and cannot invent a baseline is the property being sold.

This ADR records a boundary and a sequence. It does not authorise the Python
service, a framework dependency, or any agent implementation; each is a separate
decision and a separate change.
