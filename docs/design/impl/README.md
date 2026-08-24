# Implementation specifications

**Status: TO BUILD unless a section explicitly names an existing class or
table.** These specifications take the detailed layer designs one step closer
to developer implementation. They cover the Java-side agent runtime and the
first capability loops; they do not implement Java, SQL, Python, adapters or a
console.

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
| Agent platform runtime | [agent-platform-runtime.md](agent-platform-runtime.md) | Gateway BUILT; registry, loops, ledger and evidence enforcement TO BUILD |
| Targeting Repair Agent | [layer-2-targeting-repair-agent.md](layer-2-targeting-repair-agent.md) | TO BUILD; wraps the existing targeting producer and validator |
| Feature Agent | [layer-2-feature-agent.md](layer-2-feature-agent.md) | TO BUILD; wraps the existing feature producer and verdicts |
| Discovery and Reuse Agents | [layer-2-discovery-and-reuse-agents.md](layer-2-discovery-and-reuse-agents.md) | TO BUILD; deterministic recall and scorecard remain BUILT |
| Advisor Agent | [layer-1-advisor-agent.md](layer-1-advisor-agent.md) | TO BUILD; read-only |
| Feature-set gate binding | [human-gate-feature-set-binding.md](human-gate-feature-set-binding.md) | TO BUILD; V17 is defined in the Python-side handoff |
| Data & Feature Execution Service | [layer-3-data-feature-service.md](layer-3-data-feature-service.md) | TO BUILD; Python service, only client-data access path |
| ML Execution Service | [layer-3-ml-service.md](layer-3-ml-service.md) | TO BUILD; Python training and evaluation service |
| Java-Python seam | [java-python-seam.md](java-python-seam.md) | TO BUILD; authenticated dispatch and execution ledger |
| Client adapters | [layer-4-client-adapters.md](layer-4-client-adapters.md) | TO BUILD; Python data and ML capability boundary |
| Layer 0 console | [layer-0-console.md](layer-0-console.md) | TO BUILD; server-rendered `app` views |

## Migration allocation

| Migration | Allocation | This handoff |
| --- | --- | --- |
| `V15__agent_attempt_ledger.sql` | Agent attempts and tool-call records | Define here |
| `V16__capability_loop_state.sql` | Bounded loop state linked to `initiative_stage_attempts` | Define here |
| `V17__approved_feature_sets.sql` | Exact versioned approved feature sets | Define here |
| `V18__execution_attempts.sql` | Python execution attempts | Define here |
| `V19__client_adapter_bindings.sql` | Client data and ML adapter bindings | Define here |

Flyway currently runs from `app/src/main/resources/db/migration`; the
Java-side implementation must place V15 and V16 there even though their
repositories live in `agentplatform`.

## Build order

1. Add the `agentplatform` Maven module, its typed tools, bounded loop
   controller and append-only attempt ledger. It depends only on `common` and
   `gateway`; `initiative` depends on it, never the reverse.
2. Build the Targeting Repair and Feature loops in `initiative`. Their judges
   reuse the current `SqlDesignValidator` and feature verdict rules, with
   every loop attempt written before the next repair call.
3. Add Discovery clarification handling and Reuse Evidence gathering. The
   existing `DiscoveryService` recall, scorecard, six dimensions and `0.80`
   threshold remain deterministic and immutable.
4. Add the read-only Advisor Agent over existing initiative reads.
5. Build the human-gate feature-set binding and V17 exact-content contract.
   This is the input boundary for both Python services.
6. Add V19 adapter bindings and implement `ClientDataAdapter` with enforced
   probes, query limits and refusal codes. This unblocks Data Profiling live
   observation.
7. Add V18 dispatch records and the authenticated Java-Python seam. Implement
   the Data & Feature Execution Service, which can execute only a hash-verified
   approved feature set.
8. Implement the ML Execution Service and its ML adapter. It trains only from
   the approved feature-set hash and returns observations and artifact
   references; Java evaluates acceptance and promotion criteria. This unblocks
   experiment execution and the execution stages 9 and 10 in the client's
   lifecycle.
9. Add the server-rendered Layer 0 console in `app`. It reads the whole
   initiative projection and submits only to the existing run and decision
   routes.

Before steps 6–8, Data Profiling live observation and experiment execution are
blocked: no Java-side loop can truthfully profile warehouse rows, build
features, train, evaluate or create model artifacts. Java remains the
authority for thresholds, sample-size mathematics, statistical tests,
promotion criteria and every state transition after Python is added.

## Cross-links

The [layer designs](..) state the architectural responsibilities and current
readiness. The [agent boundary ADR](../../adr/0001-agent-boundary.md) states
why the split is mandatory. This folder specifies proposed implementation
contracts; it does not turn TO BUILD designs into existing capabilities.
