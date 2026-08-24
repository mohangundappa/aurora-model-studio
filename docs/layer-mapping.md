# Model Studio layer mapping

This is a lookup from each diagram box to the Model Studio component that
implements it, or to the explicit gap where no component exists.

[See the detailed layer designs](design/README.md) for implementation contracts and audit detail.

## Experience layer

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Model Developer / Data Scientist — single business-facing Model Development Assistant | Not built | None | No console or frontend; interaction is HTTP plus `ImporterCommand` CLI runs |

## Model Development Orchestrator

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Initiative | Built | `InitiativeController`, `InitiativeService` | `POST /api/initiatives` and initiative reads |
| Workflow | Built | `InitiativeService`, `InitiativeStage`, `StageStatus`, `initiative_stage_attempts` | Enforces stage predecessors and records attempts |
| Approvals | Built | `InitiativeService.decide`, `initiative_gate_decisions` | `APPROVE`, `REJECT`, or `RETURN`; named actor and non-empty reason; append-only records |
| Guided autonomy | Partial | `InitiativeService.runStage` | Bounded stage producers stop at deterministic checks and human gates |
| Telemetry | Partial | `initiative_stage_attempts`, `initiative_events`, `DurationSummary` | Records append-only events and machine or human-wait durations, not a telemetry system |

## Specialised AI intelligence

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Model Discovery | Built | `DiscoveryController`, `DiscoveryService` | `POST /api/discovery/requirements`, `POST /api/discovery/runs`, and run reads |
| Reuse Intelligence | Built | `DiscoveryService.clearsReuse`, `REUSE_THRESHOLD` | Six gated dimensions must each reach `0.80` |
| Data Discovery | Partial | `InitiativeService`, `KnowledgeService`, `DATA_ASSET` | Feasibility over declared metadata; no warehouse connection or profiling |
| Targeting Design | Built | `InitiativeService`, `LlmGateway`, `SqlDesignValidator` | LLM drafts; deterministic SQL validation decides acceptance |
| Feature Intelligence | Built | `InitiativeService`, `LlmGateway` | LLM drafts; deterministic feature validation follows |
| Experimentation | Partial | `InitiativeService` | Designs variants, sample size, and decision rule; never executes or evaluates |

## Enterprise Knowledge

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Models | Built | `KnowledgeType.MODEL`, `knowledge_objects`, `KnowledgeController` | Versioned governed model knowledge |
| Features | Built | `KnowledgeType.FEATURE`, `knowledge_objects`, `KnowledgeController` | Versioned governed feature knowledge |
| Data | Built | `KnowledgeType.DATA_ASSET`, `knowledge_objects`, `KnowledgeController` | Data assets are governed metadata, not live warehouse data |
| Implementations | Built | `KnowledgeType.IMPLEMENTATION`, `knowledge_objects`, `KnowledgeController` | Implementation knowledge and source evidence |
| Experiments | Built | `KnowledgeType.EXPERIMENT`, `knowledge_objects`, `KnowledgeController` | Experiment designs are knowledge records |
| Governance | Built | `KnowledgeType.STANDARD`, `knowledge_audit`, `knowledge_conflicts`, `KnowledgeService` | Lifecycle, evidence, standards, conflicts, and audit |
| Human Decisions | Built | `initiative_gate_decisions`, `InitiativeService.decide` | Gate decisions are persisted separately from stage attempts |

## Controlled execution services

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Data & Feature Execution Service | Not built | None | No cohort execution, warehouse queries, profiling, feature build, or execution service |
| ML Execution Service | Not built | None | No experiments, training, tuning, evaluation, or model artifacts |

## Platform adapters

| Diagram element | Status | Implementing component | Note |
| --- | --- | --- | --- |
| Client Data Platform Adapters — Athena / Snowflake / Databricks / Spark | Not built | None | No client data-platform execution |
| Client ML Platform Adapters — SageMaker / Databricks / Azure ML / Vertex | Not built | None | No client ML-platform execution |
| Inbound source-artifact reader | Built | `AuroraBackfillImporter`, `StructuralParser` | Reads a configured source checkout in place |
| Outbound candidate-registration client | Built | `AuroraCandidateClient`, `HttpAuroraCandidateClient` | Posts a content-hashed package to a configured runtime platform |

## Lifecycle mapping

| Stage | Status | Model Studio stage | Note |
| --- | --- | --- | --- |
| 1. Requirement Understanding | Built | `REQUIREMENT_INTAKE` | Completed when the initiative is created |
| 2. Similar Model Discovery | Built | `KNOWLEDGE_DISCOVERY` | Discovery retrieves and ranks governed candidates |
| 3. Reference Selection | Built | `KNOWLEDGE_DISCOVERY` | No separate stage; discovery supplies the references |
| 4. Reuse Analysis | Built | `REUSE_DECISION` | Human decision over the reuse scorecard |
| 5. Data Discovery | Partial | `DATA_FEASIBILITY` | Checks governed data-asset metadata only |
| 6. Target or Cohort Design | Built | `TARGETING_DESIGN` | Drafts and validates cohort and optional label SQL |
| 7. Feature Discovery and Design | Built | `FEATURE_DESIGN` | Drafts and validates feature designs |
| 8. Experiment Design | Partial | `EXPERIMENT_DESIGN` | Design mathematics only; no execution or evaluation |
| 9. Model Build and Evaluation | Not built | None | `CANDIDATE_BUILD` is `OUT_OF_SCOPE`; no training or evaluation |
| 10. Candidate Approval | Not built | None | No trained candidate exists to approve |
| 11. Model Handoff Package | Built | `HANDOFF` | Approved package is registered while awaiting weights |

## What is missing

- A single business-facing Model Development Assistant console.
- Data and Feature Execution Service capabilities.
- ML Execution Service capabilities.
- Client data-platform and client ML-platform adapters.
- Model build, evaluation, and trained-candidate approval.
- Authenticated actor identity and a standalone telemetry system.

[Agent boundary ADR](adr/0001-agent-boundary.md) describes where AI agents fit and where verdicts stay deterministic.
