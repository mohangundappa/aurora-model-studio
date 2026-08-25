# Model Studio detailed layer designs

These documents answer how each conceptual layer is implemented today and how
the missing parts should be built. Read [the platform map](../model-studio-platform.md)
for the overall shape, [the layer mapping](../layer-mapping.md) for the terse
box lookup, and [the agent boundary ADR](../adr/0001-agent-boundary.md) for the
governance rule.
For implementation-ready Java-side specifications, read the
[implementation specifications](impl/README.md).

The structural rule from the architecture diagram is:

> “every AI agent is paired with the deterministic judge that owns its verdict.
> Agents gather, interpret and iterate. Formulas, validators and thresholds
> decide.”

## Layers

| Layer | Design | Implementation specifications | Current status |
| --- | --- | --- |
| 0 · Experience | [Experience](layer-0-experience.md) | [Layer 0 console](impl/layer-0-console.md) | TO BUILD; conversational, not a form |
| 1 · Governed orchestration | [Governed orchestration](layer-1-orchestration.md) | [Advisor Agent](impl/layer-1-advisor-agent.md) | BUILT; system of record; advisor TO BUILD |
| 2 · Design capabilities | [Design capabilities](layer-2-design-capabilities.md) | [Targeting](impl/layer-2-targeting-repair-agent.md), [Feature](impl/layer-2-feature-agent.md), [Discovery and Reuse](impl/layer-2-discovery-and-reuse-agents.md) | Agent/judge pairs; one-shot paths BUILT, loops TO BUILD |
| 3 · Execution capabilities | [Execution capabilities](layer-3-execution-capabilities.md) | [Data and Feature](impl/layer-3-data-feature-service.md), [ML](impl/layer-3-ml-service.md), [Java-Python seam](impl/java-python-seam.md) | TO BUILD; Python; only place client data is touched |
| 4 · Foundation | [Foundation](layer-4-foundation.md) | [Client adapters](impl/layer-4-client-adapters.md) | Enterprise Knowledge BUILT; client adapters TO BUILD |

## Cross-cutting rails

| Rail | Design | Implementation specifications | Current status |
| --- | --- | --- |
| Agent platform | [Agent platform](cross-cutting-agent-platform.md) | [Agent platform runtime](impl/agent-platform-runtime.md) | Gateway BUILT; tools, loops, ledger and evidence rule TO BUILD |
| Human gate | [Human gate](cross-cutting-human-gate.md) | [Feature-set binding](impl/human-gate-feature-set-binding.md) | Built mechanics; Layer 3 hash-bound feature-set gate TO BUILD |

## How to read these designs

Each layer uses the same sequence: purpose, status, components, interfaces,
data model, main path, the deterministic boundary, refusal behaviour,
technology, and risks. BUILT claims name the current source symbol or schema.
TO BUILD contracts are proposals, not existing routes or tables.

## Reading path to implementation

Start with the [platform map](../model-studio-platform.md) for system boundaries
and responsibilities. Read the [layer design](layer-0-experience.md) or the
relevant layer and rail design for what the capability is and why it belongs
there. Follow its implementation specification for what to type, including
interfaces, schemas, behaviour and tests. Use the [implementation build
order](impl/README.md#build-order) to sequence the work and its prerequisites.

## Build summary

| Area | Built now | To build |
| --- | --- | --- |
| Business-facing experience | HTTP controllers and `ImporterCommand` only | Single initiative workspace with no local workflow state |
| Orchestration | Nine-stage state machine, gates, attempts, events and durations | Read-only advisor and richer telemetry |
| Design capabilities | Recall, reuse, feasibility, design validators and experiment mathematics | Bounded agent loops around those deterministic judges |
| Agent platform | `LlmGateway` and `EmbeddingProvider` | Tool registry, loop controller, attempt ledger and evidence-policy enforcement |
| Execution | None | Data and feature execution plus ML build and evaluation |
| Foundation | Governed knowledge, evidence, provenance, conflicts and embeddings | Swappable client data and ML platform adapters |

No agent can move a threshold, approve its own work, invent a missing input, or
choose the next stage.
