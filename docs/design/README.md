# Model Studio detailed layer designs

These documents answer how each conceptual layer is implemented today and how
the missing parts should be built. Read [the platform map](../model-studio-platform.md)
for the overall shape, [the layer mapping](../layer-mapping.md) for the terse
box lookup, and [the agent boundary ADR](../adr/0001-agent-boundary.md) for the
governance rule.

The architectural rule is: **agents own gathering, interpreting and iterating;
deterministic code owns the verdict.**

## Layers

| Layer | Design | Current status |
| --- | --- | --- |
| 0 | [Experience](layer-0-experience.md) | TO BUILD |
| 1 | [Governed orchestration](layer-1-orchestration.md) | BUILT; guided autonomy and telemetry are PARTIAL |
| 2 | [Design capabilities](layer-2-design-capabilities.md) | BUILT one-shot paths; agent loops TO BUILD |
| 2b | [Agent platform](layer-2b-agent-platform.md) | Gateway BUILT; tools, loops, ledger and evidence enforcement TO BUILD |
| 3 | [Controlled execution services](layer-3-execution-services.md) | TO BUILD |
| 4 | [Foundation](layer-4-foundation.md) | Enterprise Knowledge BUILT; client adapters TO BUILD |

## How to read these designs

Each layer uses the same sequence: purpose, status, components, interfaces,
data model, main path, the deterministic boundary, refusal behaviour,
technology, and risks. BUILT claims name the current source symbol or schema.
TO BUILD contracts are proposals, not existing routes or tables.

## Build summary

| Area | Built now | To build |
| --- | --- | --- |
| Business-facing experience | HTTP controllers and `ImporterCommand` only | Single initiative workspace with no local workflow state |
| Orchestration | Nine-stage state machine, gates, attempts, events and durations | Read-only advisor and richer telemetry |
| Design capabilities | Recall, reuse, feasibility, design validators and experiment mathematics | Bounded agent loops around those deterministic judges |
| Agent platform | `LlmGateway` and `EmbeddingProvider` | Tool registry, loop controller, attempt ledger and evidence-policy enforcement |
| Execution | None | Data and feature execution plus ML build and evaluation |
| Foundation | Governed knowledge, evidence, provenance, conflicts and embeddings | Swappable client data and ML platform adapters |
