# ADR 0002: Per-layer technology rule

[See the detailed layer designs](../design/README.md) for implementation contracts and audit detail.
[See the implementation specifications](../design/impl/README.md) for typed build contracts and sequence.

- Status: accepted
- Scope: technology choices for the Model Studio experience, backend and AI agent layers
- Supersedes: the loop-runtime portion of ADR 0001

## Context

The client has set a standing per-layer technology rule for this platform:
frontend React, backend Spring Boot, and AI Python with well-known AI
frameworks. The rule must distinguish the runtime that reasons from the code
that owns a verdict, persisted state or a governance invariant.

The existing repository is a Java 21 and Spring Boot application with governed
LLM egress, deterministic validators, database-backed initiative state and
human gates. The implementation specifications also define Python execution
services and a console, but two of those specifications use runtimes that
contradict the client's rule: the Layer 0 console is server-rendered Thymeleaf,
and the bounded agent loop is a Java controller.

## Decision

The client's rule is the decision:

| Layer | Technology | Responsibility |
| --- | --- | --- |
| Layer 0 experience | React and TypeScript | Render the authenticated workspace and submit existing JSON API operations |
| Backend, governance and system of record | Java 21 and Spring Boot | Own validators, verdicts, ledgers, persisted state, gates and API contracts |
| AI agent reasoning | Python with LangGraph | Plan, gather, interpret and iterate within a bounded capability graph |

The governing boundary is: LangGraph plans and iterates. Spring Boot holds the
verdict, the ledger and the persisted state. React shows it and never owns it.
The Python agent service has no credentials for the governance schema, calls
Java validators as tools over authenticated HTTP, calls the existing governed
`LlmGateway` for every completion, and reports each attempt to Java for
persistence. LangGraph's checkpointer, when present, is working memory only;
the Java ledger is the system of record.

This decision changes two merged specifications:

- `layer-0-console.md` changes from server-rendered Thymeleaf views to a React
  and TypeScript Vite SPA.
- `agent-platform-runtime.md` moves the bounded loop controller from Java to
  the Python agent service. Java keeps the tools, ledger and evidence control.

The following remain in Java regardless of the rule:

- thresholds, the six-dimension reuse scorecard and its `0.80` threshold;
- SQL and feature validators;
- sample-size mathematics and statistical tests;
- acceptance and promotion criteria;
- the nine-stage state machine and every state transition;
- human gates and the append-only ledger; and
- governed LLM egress through `LlmGateway`, including `llm_invocations`,
  redaction, cost and retry accounting.

The rule places AI reasoning in Python; it does not move any verdict there.
Python reports observations, proposed drafts, validator inputs and artifact
references. Java decides whether those outputs satisfy a rule or advance a
stage.

## Technology

The React console is a new `frontend/` project using React 18, TypeScript and
Vite. It is outside the Maven reactor and is served from the same origin as
the API in production. Vite is selected because this is an internal,
authenticated, read-mostly workspace with no SSR or SEO requirement. Exact
dependency versions are selected at implementation time and must be releases
at least seven days old.

The Python agent service uses FastAPI, uvicorn, Pydantic v2 and LangGraph.
LangGraph is the graph executor for bounded loops, not governance, the state
store or the verdict. Every completion goes through the Java `LlmGateway`
route, and every validator call goes through an authenticated Java tool route.
Exact dependency versions are selected at implementation time and must be
releases at least seven days old.

ADR 0001 still rejects AutoGen and still rejects LangChain as the provider
boundary. This ADR supersedes only its statement that LangGraph is optional:
LangGraph is now the specified runtime for new bounded agent loops.

## Consequences

The rule costs:

- a Node build and a second and third runtime alongside the Java application;
- three CI paths for Java, Python and the frontend;
- an authenticated bidirectional seam where the earlier outbound design had
  one direction;
- added latency because the Python loop calls Java once per tool call and once
  per completion; and
- Python's weaker typing next to correctness-critical numbers, which is
  acceptable only because those numbers and their decisions remain in Java.

LangGraph's checkpointer is not the system of record. It may retain working
graph state for a running request or debugging, but Java's append-only ledger
records attempts and tool calls and Java's persisted state controls the
initiative.

The four existing Java `LlmGateway` call sites remain unchanged: extraction
interpretation, discovery explanation, targeting draft and feature draft.
This decision governs new agent work. Migrating those four one-shot calls into
the Python agent service is a possible follow-up, not a requirement, and this
ADR implies no behaviour change for them. They are not deprecated or
violations of this decision.

React adds a separate build and runtime but does not add a workflow state
store. The browser fetches and re-fetches Java projections, and the Python
agent service cannot write a stage, gate or governance record directly.

Rejected:

- **Server-rendered Thymeleaf for the console.** It contradicts the client's
  React frontend rule and would make the delivered experience a second
  technology choice.
- **A Java bounded loop controller.** It contradicts the client's Python AI
  reasoning rule; Java instead exposes authenticated tools, completion and
  append routes and retains the verdict and ledger.
- **LangGraph as the orchestrator or system of record.** Its graph state and
  checkpointer cannot replace Spring Boot's deterministic validators,
  database constraints, append-only ledger or nine-stage state machine.
