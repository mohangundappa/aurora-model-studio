# Implementation specifications

**Status: TO BUILD unless a section explicitly names an existing class or
table.** This folder specifies all five layers and both cross-cutting rails.
Specifying is not building: these documents change no Java, SQL, Python,
template or configuration file.

The governing rule remains: agents gather, interpret and iterate; deterministic
code owns the verdict. No agent changes a threshold, approves its own work,
invents missing evidence or chooses the next initiative stage.

## Depth bar

Every specification defines the module and package, Java 21 types, method
ordering and transaction boundaries, exact schema, HTTP contract where needed,
configuration, stable deterministic rule identifiers, refusal outcomes,
tests, acceptance criteria and open decisions. Existing symbols are labelled
BUILT; proposed symbols, routes and tables are labelled TO BUILD.

## Specifications

| Component | Specification | Status |
| --- | --- | --- |
| Agent platform runtime | [agent-platform-runtime.md](agent-platform-runtime.md) | Gateway BUILT; Java tools, inbound API, ledger and evidence enforcement TO BUILD |
| Python agent runtime | [agent-runtime-python.md](agent-runtime-python.md) | TO BUILD; bounded LangGraph capability graphs |
| Targeting Repair Agent | [layer-2-targeting-repair-agent.md](layer-2-targeting-repair-agent.md) | TO BUILD; wraps the existing targeting producer and validator |
| Feature Agent | [layer-2-feature-agent.md](layer-2-feature-agent.md) | TO BUILD; wraps the existing feature producer and verdicts |
| Discovery and Reuse Agents | [layer-2-discovery-and-reuse-agents.md](layer-2-discovery-and-reuse-agents.md) | TO BUILD; deterministic recall and scorecard remain BUILT |
| Advisor Agent | [layer-1-advisor-agent.md](layer-1-advisor-agent.md) | TO BUILD; read-only |
| Feature-set gate binding | [human-gate-feature-set-binding.md](human-gate-feature-set-binding.md) | TO BUILD; V17 DDL is specified in [java-python-seam.md](java-python-seam.md) |
| Data & Feature Execution Service | [layer-3-data-feature-service.md](layer-3-data-feature-service.md) | TO BUILD; Python service, only client-data access path |
| ML Execution Service | [layer-3-ml-service.md](layer-3-ml-service.md) | TO BUILD; Python training and evaluation service |
| Java-Python seam | [java-python-seam.md](java-python-seam.md) | TO BUILD; authenticated dispatch and execution ledger |
| Client adapters | [layer-4-client-adapters.md](layer-4-client-adapters.md) | TO BUILD; Python data and ML capability boundary |
| Layer 0 console | [layer-0-console.md](layer-0-console.md) | TO BUILD; React SPA in `frontend/` |

## Migration allocation

| Migration | Allocation | Defining specification |
| --- | --- | --- |
| `V15__agent_attempt_ledger.sql` | Agent attempts and tool-call records | [agent-platform-runtime.md](agent-platform-runtime.md) |
| `V16__capability_loop_state.sql` | Bounded loop state linked to `initiative_stage_attempts` | [agent-platform-runtime.md](agent-platform-runtime.md) |
| `V17__approved_feature_sets.sql` | Exact versioned approved feature sets | [java-python-seam.md](java-python-seam.md) |
| `V18__execution_attempts.sql` | Python execution attempts | [java-python-seam.md](java-python-seam.md) |
| `V19__client_adapter_bindings.sql` | Client data and ML adapter bindings | [layer-4-client-adapters.md](layer-4-client-adapters.md) |

Flyway currently runs from `app/src/main/resources/db/migration`; the
Java-side implementation must place V15 and V16 there even though their
repositories live in `agentplatform`.

## Build order

1. Add the `agentplatform` Maven module, its typed tools, Java inbound API and
   append-only attempt ledger. It depends only on `common` and `gateway`;
   `initiative` depends on it, never the reverse.
2. Build the Python agent service with bounded LangGraph capability graphs and
   the authenticated callbacks to Java.
3. Build the Targeting Repair, Feature, Discovery and Reuse graphs. Their
   deterministic judges remain Java-owned, with every attempt written before
   the next repair or interpretation call.
4. Add the read-only Advisor Agent over existing initiative reads.
5. Build the human-gate feature-set binding and V17 exact-content contract.
   This is the input boundary for the Python execution services.
6. Add V19 adapter bindings and implement `ClientDataAdapter` with enforced
   probes, query limits and refusal codes. This unblocks Data Profiling live
   observation.
7. Add V18 dispatch records and the authenticated Java-Python seam. Implement
   the Data & Feature Execution Service, which can execute only a hash-verified
   approved feature set.
8. Implement the ML Execution Service and its ML adapter. It trains only from
   the approved feature-set hash and returns observations and artifact
   references; Java evaluates acceptance and promotion criteria.
9. Add the React Layer 0 console in `frontend/`. It reads the whole initiative
   projection and submits only to the existing run and decision routes.

Before steps 6–8, Data Profiling live observation and experiment execution are
blocked: no Java-side loop can truthfully profile warehouse rows, build
features, train, evaluate or create model artifacts. Java remains the
authority for thresholds, sample-size mathematics, statistical tests,
promotion criteria and every state transition after Python is added.

## Design-to-implementation traceability

| Layer or rail | Design document | Implementation specification(s) | Migrations introduced | Build-order step | Current status |
| --- | --- | --- | --- | ---: | --- |
| Layer 0 · Experience | [layer-0-experience.md](../layer-0-experience.md) | [layer-0-console.md](layer-0-console.md) | None | 9 | TO BUILD; React SPA |
| Layer 1 · Governed orchestration | [layer-1-orchestration.md](../layer-1-orchestration.md) | [layer-1-advisor-agent.md](layer-1-advisor-agent.md) | None | 4 | BUILT system of record; advisor TO BUILD |
| Layer 2 · Design capabilities | [layer-2-design-capabilities.md](../layer-2-design-capabilities.md) | [layer-2-targeting-repair-agent.md](layer-2-targeting-repair-agent.md), [layer-2-feature-agent.md](layer-2-feature-agent.md), [layer-2-discovery-and-reuse-agents.md](layer-2-discovery-and-reuse-agents.md) | None | 3 | Four of six agent/judge pairs specified; Data Profiling and Experiment Planning remain without specs |
| Layer 3 · Execution capabilities | [layer-3-execution-capabilities.md](../layer-3-execution-capabilities.md) | [layer-3-data-feature-service.md](layer-3-data-feature-service.md), [layer-3-ml-service.md](layer-3-ml-service.md), [java-python-seam.md](java-python-seam.md) | V18 | 7–8 | TO BUILD; Python execution services |
| Layer 4 · Foundation | [layer-4-foundation.md](../layer-4-foundation.md) | [layer-4-client-adapters.md](layer-4-client-adapters.md) | V19 | 6 | Enterprise Knowledge BUILT; client adapters TO BUILD |
| Agent platform rail | [cross-cutting-agent-platform.md](../cross-cutting-agent-platform.md) | [agent-platform-runtime.md](agent-platform-runtime.md), [agent-runtime-python.md](agent-runtime-python.md) | V15, V16 | 1–2 | Gateway BUILT; Java and Python runtimes TO BUILD |
| Human gate rail | [cross-cutting-human-gate.md](../cross-cutting-human-gate.md) | [human-gate-feature-set-binding.md](human-gate-feature-set-binding.md) | V17 ([java-python-seam.md](java-python-seam.md)) | 5 | Gate mechanics BUILT; hash-bound feature-set binding TO BUILD |

## Cross-links

The [layer designs](..) state the architectural responsibilities and current
readiness. The [agent boundary ADR](../../adr/0001-agent-boundary.md) states
why the split is mandatory. This folder specifies proposed implementation
contracts; it does not turn TO BUILD designs into existing capabilities.
