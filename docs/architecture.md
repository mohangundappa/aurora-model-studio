# Aurora Model Studio architecture

[See the standalone platform map](model-studio-platform.md) for the layered product view; this document remains the code-level architecture view.
[See the detailed layer designs](design/README.md) for implementation contracts and audit detail.
[See the implementation specifications](design/impl/README.md) for typed build contracts and sequence.
[See ADR 0002](adr/0002-per-layer-technology.md) for the per-layer technology rule.

Aurora Model Studio is the design and governance plane for customer-intelligence
artifacts. It imports and interprets source material, retrieves governed knowledge,
orchestrates human-gated initiative stages, and sends an immutable design package to
Aurora Intelligence. It does not train or serve models and does not own client
profiles.

**How to use this guide.** Start at Level 0 for the system boundary, use Level 2 for
the end-to-end flow, Level 3 for one request, Level 4 for persistence, Level 5 for
the model-versus-gate boundary, and Level 6 for the outbound seam. Level 1 explains
the module structure.

## Level 0 — system context

```mermaid
flowchart LR
  Client["Client CDP and MarTech estate<br/>Role: system of record<br/>Responsibility: profiles, consent, identity, audiences<br/>Tech: client-owned CDP and MarTech systems"]
  Runtime["Aurora Intelligence<br/>Role: runtime plane<br/>Responsibility: signals, decisions, experiments, customer experiences<br/>Tech: separate Aurora Intelligence product"]
  Studio["Aurora Model Studio<br/>Role: design and governance plane<br/>Responsibility: governed knowledge, discovery, initiative stages, approved handoff packages<br/>Tech: React frontend TO BUILD, Java 21 and Spring Boot 3.4.5 backend, Python/LangGraph agent runtime TO BUILD, PostgreSQL and pgvector on 5433, Flyway, JDBC, app on 8081, provider-neutral LLM gateway, Testcontainers and JUnit 5"]

  Client <--> Runtime
  Client <--> Studio
  Studio -->|"content-hashed design handoff"| Runtime
```

The client estate remains the system of record. The bidirectional arrows are adapter
seams, not replacement: Model Studio and Aurora Intelligence do not become the owner
of client profiles. Model Studio governs designs and candidate registration; Aurora
owns runtime execution after the handoff. Neither product trains a model.

## Level 1 — Maven modules and dependency direction

The root `pom.xml` is the Maven reactor aggregator (`packaging` is `pom`) and lists
the eight modules. The `app` module is the Spring Boot executable entrypoint and
runtime aggregator: `ModelStudioApplication` scans the product modules, Flyway runs
from the app, and the repackaged artifact listens on port `8081`.

The diagram arrows mean “the module at the tail depends directly on the module at the
head.” The arrows are the dependency direction declared by the module POMs.

```mermaid
flowchart LR
  common["common<br/>Role: shared kernel<br/>Responsibility: client context, IDs, lifecycle and domain enums<br/>Stack: Java 21 library"]
  gateway["gateway<br/>Role: provider boundary<br/>Responsibility: LLM request, redaction, schema and invocation handling<br/>Stack: Spring, JDBC, Jackson, deterministic and OpenAI adapters"]
  knowledge["knowledge<br/>Role: governed knowledge service<br/>Responsibility: versioning, evidence, confidence, lifecycle, retrieval and impact<br/>Stack: Spring, JDBC, PostgreSQL"]
  importer["importer<br/>Role: source backfill<br/>Responsibility: read Aurora Intelligence artifacts and persist typed knowledge<br/>Stack: Spring, JDBC, SnakeYAML and Jackson"]
  extraction["extraction<br/>Role: artifact interpretation<br/>Responsibility: parse source artifacts, request grounded interpretations and persist provenance<br/>Stack: Spring, LLM gateway, knowledge, SnakeYAML and Jackson"]
  discovery["discovery<br/>Role: knowledge discovery<br/>Responsibility: embedding recall, text recall, ranking and reuse classification<br/>Stack: Spring, JDBC, PostgreSQL and pgvector"]
  initiative["initiative<br/>Role: governed orchestration<br/>Responsibility: stages, validators, human gates, experiment design and handoff<br/>Stack: Spring MVC, JDBC, PostgreSQL and JSqlParser"]
  app["app<br/>Role: executable entrypoint<br/>Responsibility: boot, HTTP API, health, Flyway and command-line workflows<br/>Stack: Spring Boot 3.4.5, Actuator, Springdoc, JDBC, Flyway, PostgreSQL, Testcontainers"]

  gateway --> common
  knowledge --> common
  importer --> knowledge
  extraction --> gateway
  extraction --> knowledge
  discovery --> common
  discovery --> knowledge
  discovery --> gateway
  initiative --> common
  initiative --> knowledge
  initiative --> discovery
  app --> common
  app --> gateway
  app --> knowledge
  app --> importer
  app --> extraction
  app --> discovery
  app --> initiative
```

The enforced rule is an acyclic layering: shared types sit at the bottom, providers
and persistence are reused by higher-level services, and only `app` assembles the
runtime. `common` depends on no product module. A code-level nuance is important:
`common` is a direct dependency of `gateway`, `knowledge`, `discovery`, `initiative`,
and `app`, but `importer` and `extraction` receive it transitively through their
declared dependencies (`knowledge`, and `gateway` plus `knowledge`, respectively).
Thus “everything depends on `common`” is true transitively, not as eight direct POM
edges.

## Level 2 — end-to-end product flow

The importer reads an Aurora Intelligence checkout in place; it does not copy or
modify that repository. The extraction path parses selected YAML, Java, SQL and
Markdown artifacts, then uses the gateway only for grounded interpretation. Both
paths write versioned knowledge and evidence through the knowledge service.

```mermaid
flowchart LR
  sources["Aurora Intelligence source artifacts<br/>signals, calculators, policies, experiments, registry SQL and governed docs"]
  importer["AuroraBackfillImporter<br/>typed backfill of features, implementations, models, data assets and standards"]
  extraction["ExtractionService<br/>structural parsing plus grounded interpretation"]
  governed["Governed knowledge<br/>objects, evidence, field provenance, confidence, conflicts and lifecycle"]
  discovery["DiscoveryService<br/>embedding and text recall"]
  reuse["Deterministic reuse scorecard<br/>six gated dimensions and classifications REUSE, ADAPT, GENERATE or NOT_RECOMMENDED"]
  initiative["InitiativeService<br/>requirement intake, discovery, reuse, feasibility, design, experiment and handoff stages"]
  package["Immutable HandoffPackage<br/>approved design content and SHA-256 package hash"]
  aurora["Aurora Intelligence candidate endpoint<br/>candidate registration outside Model Studio"]

  sources --> importer
  sources --> extraction
  importer --> governed
  extraction --> governed
  governed --> discovery
  discovery --> reuse
  reuse --> initiative
  initiative --> package
  package --> aurora
```

The initiative enum contains nine stages:
`REQUIREMENT_INTAKE`, `KNOWLEDGE_DISCOVERY`, `REUSE_DECISION`,
`DATA_FEASIBILITY`, `TARGETING_DESIGN`, `FEATURE_DESIGN`, `CANDIDATE_BUILD`,
`EXPERIMENT_DESIGN`, and `HANDOFF`. `CANDIDATE_BUILD` is represented as
`OUT_OF_SCOPE`; Model Studio creates no trained candidate.

Detailed behavior is split into [the knowledge model](knowledge-model.md),
[extraction](extraction.md), [discovery](discovery.md),
[targeting and feature design](targeting-feature-design.md), and
[experiment design and handoff](experiment-design-and-handoff.md).

## Level 3 — one targeting-design request

Targeting design shows the complete request path. `ClientScopeFilter` requires a
known UUID in `X-Aurora-Client`, places it in `ClientContext` for the request, and
clears it afterward. Every repository query and write uses that context.

```mermaid
sequenceDiagram
  actor Caller as "API caller"
  participant Filter as "ClientScopeFilter"
  participant Controller as "InitiativeController"
  participant Service as "InitiativeService"
  participant Knowledge as "KnowledgeService"
  participant Gateway as "LlmGateway"
  participant Adapter as "Deterministic or OpenAI adapter"
  participant Validator as "SqlDesignValidator"
  participant Repository as "InitiativeRepository"

  Caller->>Filter: "POST stage run with X-Aurora-Client"
  alt missing, malformed or unknown tenant
    Filter-->>Caller: "HTTP 400 tenant denial"
  else known tenant
    Filter->>Filter: "set ClientContext"
    Filter->>Controller: "dispatch request"
    Controller->>Service: "runStage TARGETING_DESIGN"
    Service->>Repository: "check predecessor and current attempt"
    alt concurrent attempt insert collision
      Repository-->>Service: "StageAlreadyRunningException"
      Service-->>Controller: "map to HTTP 409"
      Controller-->>Caller: "HTTP 409 conflict"
    else stage can run
      Service->>Knowledge: "retrieve governed data assets and lineage"
      Service->>Gateway: "LlmRequest with RedactionPolicy and strict response schema"
      Gateway->>Adapter: "complete request"
      Adapter-->>Gateway: "provider result"
      alt provider failure or schema invalid
        Gateway-->>Service: "SCHEMA_INVALID or failed LLM result"
        Service->>Repository: "persist invocation and PROVIDER_FAILED attempt"
        Repository-->>Service: "stage state"
      else valid structured result
        Gateway-->>Service: "validated structured drafts"
        Service->>Validator: "validate cohort and label SQL"
        alt validator rejection
          Validator-->>Service: "FAIL verdicts"
          Service->>Repository: "persist rejected drafts and violated checks"
          Repository-->>Service: "BLOCKED when no draft is accepted"
        else checks pass or remain unknown
          Validator-->>Service: "PASS or UNKNOWN verdicts"
          Service->>Repository: "persist StageAttempt and InitiativeEvent"
          Repository-->>Service: "COMPLETED or AWAITING_APPROVAL"
        end
      end
    end
    Filter->>Filter: "clear ClientContext"
    Controller-->>Caller: "initiative state and gate outcome"
  end
```

The gateway retries retryable adapter results up to two times and performs the strict
schema check before `InitiativeService` sees a successful result. A non-successful
result has no deterministic fallback in the targeting path: the stage is recorded as
`PROVIDER_FAILED`. SQL validation is deterministic and covers read-only parsing,
governed references, output contracts, point-in-time bounds and target leakage.

The controller maps `StageAlreadyRunningException` to `409`. The ordinary status
guard for an attempt already marked `IN_PROGRESS` or `AWAITING_APPROVAL` raises an
`IllegalStateException`, which the controller maps to `400`; the `409` path is the
concurrent insert race.

The provider boundary is detailed in [LLM gateway](llm-gateway.md), and the design
validators are detailed in [targeting and feature design](targeting-feature-design.md).

## Level 4 — data model and lifecycle

Knowledge objects are immutable in content once approved. New source material creates
another version; approval supersedes the previous approved version for the same
client and key. Confidence is computed from known evidence and populated attributes;
unknown signals are omitted and weights are renormalized. An open blocking conflict
warns and caps confidence at `0.5`.

```mermaid
flowchart LR
  objects["knowledge_objects<br/>typed, versioned and lifecycle governed"]
  evidence["knowledge_evidence<br/>source excerpts and extraction certainty"]
  provenance["knowledge_field_provenance<br/>field citations and certainty"]
  relationships["knowledge_relationships<br/>directional dependencies and lineage"]
  conflicts["knowledge_conflicts<br/>OPEN or RESOLVED, blocking classification"]
  embeddings["knowledge_embeddings<br/>pgvector recall"]
  requirements["discovery_requirements"]
  runs["discovery_runs<br/>provider, weights, classification and result"]
  initiatives["initiatives<br/>requirement and includeCandidates"]
  attempts["initiative_stage_attempts<br/>status, blockers, checks, drafts and artifacts"]
  gates["initiative_gate_decisions<br/>APPROVE, REJECT or RETURN"]
  events["initiative_events<br/>status transitions and reasons"]
  invocations["llm_invocations<br/>provider, model, schema, outcome and cost history"]
  packages["initiative_handoff_packages and attempts<br/>hashed package and outbound audit"]

  objects --> evidence
  objects --> provenance
  objects --> relationships
  objects --> conflicts
  objects --> embeddings
  requirements --> runs
  embeddings --> runs
  runs --> initiatives
  initiatives --> attempts
  attempts --> gates
  attempts --> events
  attempts --> packages
  invocations --> objects
  objects -->|"only APPROVED is trusted by default<br/>includeCandidates=true opts into candidates"| runs
```

The lifecycle is:

```text
EXTRACTED → PENDING_REVIEW → APPROVED → SUPERSEDED or DEPRECATED
```

Only `APPROVED` knowledge is trusted by default. Candidate retrieval requires
`includeCandidates=true`; the same opt-in is carried by discovery and initiatives.
The unique approved-per-client-and-key index prevents two approved versions at once.

Database-level enforcement is provided by Flyway migrations `V1` through `V14`:

- `knowledge_audit` is append-only through `knowledge_audit_append_only`, and
  `llm_invocations` is append-only through `llm_invocations_append_only`.
- `discovery_requirements` and `discovery_runs` are append-only through their
  database triggers.
- `initiative_events` and `initiative_gate_decisions` are append-only through their
  database triggers.
- `initiative_handoff_attempts` is insert-only through its database trigger.
- Approved `knowledge_objects` cannot be edited in place; the
  `knowledge_approved_guard` permits only `SUPERSEDED` or `DEPRECATED` lifecycle
  transitions while protecting approved content.
- Composite foreign keys repeat `client_id` on knowledge and initiative relationships,
  preventing cross-client references at the database layer.

The human-gate trigger trusts the `aurora.initiative_gate_actor` session variable
that the API sets immediately before inserting a decision. This protects against
accidental machine writes through the normal path; it is not protection against an
adversary who can write to the database and set that variable. The approver identity
is caller-asserted and unverified (`actorIdentityVerified:false` is disclosed in the
API), so a caller may supply any name. The exact governed machine-identity check is
only a self-approval guard against the orchestrator's own identity rubber-stamping a
human gate it created; it does not verify arbitrary human identity.

## Level 5 — where AI stops and gates begin

The application has four model-assisted touchpoints: extraction interpretation,
discovery embeddings and recall, discovery explanation prose, and targeting plus
feature drafting. The main-code `LlmGateway` calls are exactly four calls across
three services: `ExtractionService`, `DiscoveryService`, and `InitiativeService`
(targeting and feature design). Discovery embeddings and recall use `EmbeddingProvider`,
not `LlmGateway`; the configured default is `DeterministicEmbeddingProvider`, while
`OpenAiEmbeddingProvider` is opt-in.

```mermaid
flowchart LR
  subgraph AI["Model-assisted touchpoints"]
    extractionAI["Extraction interpretation<br/>ExtractionService through LlmGateway"]
    discoveryAI["Discovery embeddings and recall<br/>EmbeddingProvider, deterministic default, OpenAI opt-in"]
    explanationAI["Discovery explanation prose<br/>DiscoveryService through LlmGateway"]
    draftingAI["Targeting and feature drafting<br/>InitiativeService through LlmGateway"]
  end

  subgraph Gates["Deterministic zone"]
    scorecard["Reuse scorecard<br/>0.80 threshold across six gated dimensions"]
    feasibility["Feasibility verdicts<br/>PASS, FAIL or UNKNOWN stage mapping"]
    validators["SQL, schema, leakage and point-in-time validators"]
    statistics["Sample-size mathematics"]
    lifecycle["Lifecycle and human gates"]
  end

  extractionAI -->|"grounded candidate fields"| lifecycle
  discoveryAI -->|"recalled candidates"| scorecard
  explanationAI -->|"explanation prose only"| scorecard
  draftingAI -->|"structured drafts"| validators
  scorecard --> feasibility
  feasibility --> validators
  validators --> lifecycle
  statistics --> lifecycle
  lifecycle -->|"model drafts and recalls; gates decide"| outcome["approved design or refusal"]
```

The six gated reuse dimensions are `targetAlignment`, `populationAlignment`,
`horizonAlignment`, `featureAvailability`, `dataAvailability`, and
`implementationAvailability`. `evidenceStrength` is present in the scorecard and
weighted in configuration, but it is not one of those six gated dimensions.

The deterministic zone also includes strict response-schema validation, SQL
read-only and governed-reference checks, target-leakage checks, point-in-time checks,
feasibility mapping, deterministic sample-size calculation, lifecycle transitions,
and explicit human decisions. The model drafts or recalls material; the gates decide
what can proceed.

## Level 6 — outbound seam

The handoff is an approved, immutable design package, not a trained model. The
`AuroraCandidateClient` abstraction is implemented by
`HttpAuroraCandidateClient`; Compose supplies the cross-container
`host.docker.internal` default and the shared write token.

```mermaid
sequenceDiagram
  participant Studio as "Model Studio"
  participant Package as "HandoffPackage"
  participant Human as "Named human"
  participant Client as "HttpAuroraCandidateClient"
  participant Aurora as "Aurora candidate endpoint"
  participant Registry as "Aurora candidate storage"

  Studio->>Package: "build approved content"
  Package->>Package: "canonicalize object keys and compute SHA-256"
  Package-->>Studio: "package hash and immutable content"
  Studio->>Human: "request handoff approval"
  Human-->>Studio: "APPROVE with non-empty reason"
  alt Model Studio token is missing
    Studio-->>Studio: "AURORA_NOT_CONFIGURED"
  else outbound configured
    Studio->>Client: "register name, payload, package hash"
    Client->>Aurora: "POST with token and Idempotency-Key"
    alt bad token
      Aurora-->>Client: "HTTP 401"
      Client-->>Studio: "AURORA_REJECTED with status 401"
    else Aurora token is unconfigured
      Aurora-->>Client: "HTTP 503"
      Client-->>Studio: "AURORA_REJECTED with status 503"
    else package hash mismatch
      Aurora-->>Client: "HTTP 400"
      Client-->>Studio: "AURORA_REJECTED with status 400"
    else valid package
      Aurora->>Aurora: "recompute package hash server-side"
      Aurora->>Registry: "store candidate as AWAITING_WEIGHTS and audit"
      Registry-->>Aurora: "candidate id"
      Aurora-->>Client: "HTTP 201 with candidate id"
      Client-->>Studio: "REGISTERED and AWAITING_WEIGHTS"
      Client->>Aurora: "replay with same Idempotency-Key"
      Aurora-->>Client: "HTTP 201 with same candidate id"
    end
  end
```

The package contains the requirement trace, model name, targeting design and
validator verdicts, feature and data-asset references, experiment design, feasibility
checks, evidence, and declared observables. It deliberately excludes a trained model,
weights, evaluation, and expected lift. Aurora recomputes the hash before accepting
the package and stores the result separately as a candidate with status
`AWAITING_WEIGHTS`; it is not a `model_versions` row, is not `TESTED`, and is not
servable.

Weights and evaluation come from client MLOps, which may later produce a tested
downstream model. Model Studio does not train, evaluate, deploy, serve, or monitor
that model. The outbound contract and a runnable walkthrough are documented in
[handoff contract](handoff-contract.md) and [handoff walkthrough](handoff-walkthrough.md).

The current embedding schema has a real provider limitation: PostgreSQL stores
`vector(32)`, while OpenAI `text-embedding-3-small` returns 1536 dimensions. There is
no embedding-model or embedding-version column. Enabling OpenAI embeddings therefore
requires a migration and design change rather than only a configuration switch.

## Tenant and governance boundaries

The required `X-Aurora-Client` header is validated against the configured client IDs;
missing, malformed, and unknown IDs return HTTP 400. `ClientContext` scopes repository
queries and writes. Each knowledge and initiative table carries `client_id`, and
composite foreign keys repeat that boundary for child references.

Local actors are self-declared and unverified. They are recorded for demo traceability
only and do not constitute authentication, authorization, or an audit identity.

## Honest limits

- `CANDIDATE_BUILD` is permanently out of scope in Model Studio.
- Model Studio does not train models or provide evaluation, serving, or monitoring.
- Client MLOps owns weights, evaluation, deployment, and runtime operations.
- Actors are self-declared and unverified.
- The leakage validator has an open false negative for human-readable event spellings.
- Aurora Intelligence remains a separate product and source system; the importer
  reads its checkout in place without modifying it.
