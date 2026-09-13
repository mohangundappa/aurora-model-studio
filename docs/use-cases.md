# Aurora Model Studio: use-case guide

## Purpose and audience

This guide is for business analysts, data engineers, data scientists, reviewers,
and developers deciding when to use Aurora Model Studio. It describes the
application implemented in this repository. Examples use the fictional Aurora
Hotels business; they are scenarios, not claims of measured business results.

For a guided introduction with exercises, use the
[junior training guide](junior-training-guide.md).

## 1. The problem the application solves

A team receives a request such as:

> Identify consented sessions likely to complete a booking within 30 days, so
> the marketing team can prioritize outreach.

Before anyone trains a model, the team needs to answer several questions:

- Do we already have a useful model, feature, or implementation?
- Where did its definition come from, and has anyone reviewed it?
- Can the available data describe the target and reconstruct the past safely?
- What population, labels, features, and experiment should we propose?
- Which assumptions are unresolved, and who accepted them?
- Exactly which design did we approve and send for implementation?

Model Studio keeps these decisions, their evidence, and their history together.
Its output is a governed **design package**. Client MLOps and execution systems
take responsibility for training, evaluation, deployment, serving, monitoring,
and rollback.

### Current operating model

| Area | What is available |
| --- | --- |
| User interaction | HTTP APIs and command-line tasks; no product frontend |
| Source input | A configured Aurora Intelligence checkout, read in place |
| Knowledge | Versioned objects, evidence, relationships, conflicts, provenance, review state |
| Discovery | Search and deterministic reuse ranking against a requirement |
| Workflow | Ordered initiative stages, attempts, decisions, blockers, and duration history |
| Design | Targeting SQL, feature proposals, and bounded experiment definitions |
| AI assistance | Deterministic offline adapter by default; optional external provider |
| Delivery | Approved, content-hashed package sent to a configured Aurora receiver |

## 2. Who uses it

These are suggested team responsibilities. They are not application-enforced
roles: local actors are caller-declared names, and the API reports their identity
as unverified.

| Team member | Main responsibility | Typical output |
| --- | --- | --- |
| Business analyst or product owner | Define the decision, outcome, population, and constraints | A specific model requirement |
| Data engineer | Explain source assets, observables, lineage, history, and data gaps | Evidence and feasibility findings |
| Data scientist | Assess reuse and inspect targeting, features, and experiment assumptions | A proposed design with stated limitations |
| Knowledge reviewer | Inspect evidence and approve or decline candidate knowledge | A recorded lifecycle decision |
| Initiative reviewer | Decide whether a gated stage can proceed | A decision, reason, and any accepted unknowns |
| Application developer | Maintain APIs, validators, and module contracts | Tested changes within module boundaries |
| Client MLOps or runtime owner | Receive the design and own execution afterward | Separately trained and evaluated model artifacts |

## 3. Use cases at a glance

| ID | Situation | Application outcome |
| --- | --- | --- |
| UC-01 | Definitions are scattered across an existing Aurora repository | A searchable catalog with source evidence |
| UC-02 | A new request may duplicate existing work | Ranked reuse options and explicit gaps |
| UC-03 | The requested outcome may not be observable | Feasibility checks and a blocked or reviewable initiative |
| UC-04 | A team needs a population and target-label design | Retained SQL proposals with validator findings |
| UC-05 | A design needs new or reusable features | Reuse findings or reviewable feature candidates |
| UC-06 | A team needs an experiment definition before execution | Validated variants, sample-size assumptions, and decision rule |
| UC-07 | A design is ready for implementation elsewhere | A governed package and recorded delivery outcome |
| UC-08 | A reviewer needs to reconstruct a decision or assess a change | Evidence, exact-version lineage, conflicts, and decision history |
| UC-09 | Source definitions evolve over time | New source-backed versions without drift on unchanged reimports |

## 4. Detailed use cases

### UC-01 — Build an evidence-backed catalog of existing assets

**Primary users:** data engineers and knowledge reviewers.

**Trigger:** the team needs to understand the definitions already present in an
Aurora Intelligence source checkout.

**Inputs:** source YAML, SQL, Java, and documentation in the supported repository
layout.

**Flow:**

1. Run the importer against the checkout.
2. Run structural extraction to create additional grounded interpretations.
3. Inspect candidates, source URIs, versions, excerpts, and field provenance.
4. Submit suitable candidates for review and approve them with an explicit actor.
5. Search the approved catalog during later design work.

**Output:** typed `MODEL`, `FEATURE`, `DATA_ASSET`, `IMPLEMENTATION`,
`EXPERIMENT`, and `STANDARD` records, where supported by the source artifacts.
Logical versions and their evidence remain identifiable.

**Example:** an engineer finds the `booking-intent` feature, follows its
implementation relationship, and reads the YAML and Java evidence before reuse.

**Success evidence:** the selected object has retrievable source evidence and an
appropriate review state. Default trusted retrieval exposes approved objects.

**Boundary:** this is a source-artifact adapter, not an arbitrary enterprise
crawler. Imported or extracted content is initially candidate knowledge.
Source text that says “APPROVED” cannot approve a Model Studio object.

**Entry points:** importer/extractor CLI; `/api/knowledge`.
See [knowledge](knowledge-model.md) and [extraction](extraction.md).

### UC-02 — Decide whether to reuse, adapt, or investigate further

**Primary users:** business analysts and data scientists.

**Trigger:** a request arrives for a new prediction or decision capability.

**Inputs:** business use case, prediction target, observable definition,
population, outcome horizon, decision latency, required action, and constraints.

**Flow:**

1. Register a structured requirement.
2. Run discovery against approved knowledge, or explicitly opt into candidates
   for exploration.
3. Inspect classifications, scorecard dimensions, blockers, and evidence.
4. Use the initiative's reuse gate to record the team's decision.

**Output:** a persisted discovery run with ranked candidates and reasons.
Deterministic code calculates the ranking and classification; AI-generated
explanations do not determine those verdicts.

**Example:** before rebuilding booking propensity, compare available booking
features and implementations against the new session population and horizon.

**Success evidence:** the reviewer can explain the recommendation using the
scorecard and gaps, including dimensions that remain unknown.

**Boundary:** a high reuse score is not predictive accuracy, expected lift, or
evidence that an existing model will work for this client.

**Entry points:** `POST /api/discovery/requirements`,
`POST /api/discovery/runs`, `GET /api/discovery/runs/{id}`;
initiative stages `KNOWLEDGE_DISCOVERY` and `REUSE_DECISION`.
See [discovery](discovery.md).

### UC-03 — Identify data and instrumentation gaps before development

**Primary users:** data engineers and data scientists.

**Trigger:** the team needs to determine whether a requirement has enough
governed data support to continue.

**Inputs:** required observables, required features or knowledge keys, linked data
assets, history depth, refresh cadence, grain, and point-in-time metadata.

**Flow:**

1. Complete discovery and the reuse decision.
2. Run `DATA_FEASIBILITY`.
3. Read each `PASS`, `FAIL`, or `UNKNOWN` result.
4. Resolve known blockers; explicitly review residual unknowns if the team wants
   to proceed.

**Output:** a completed, blocked, or approval-waiting stage, with named checks and
artifact references.

**Example:** a cancellation-prevention requirement needs `BOOKING_CANCELLED`.
If that event is absent from governed knowledge, the application reports
`MISSING_TARGET_OBSERVABLE:BOOKING_CANCELLED` rather than substituting another event.

**Success evidence:** the team can name the missing instrumentation or unresolved
metadata and explain the next action. A legitimate refusal is a useful outcome.

**Boundary:** checks inspect governed metadata. They do not profile a warehouse,
count real rows, verify access to client data, or prove that historical
reconstruction works in production. Accepting `UNKNOWN` preserves the unknown.

**Entry point:** initiative stage `DATA_FEASIBILITY`.
See [the platform map](model-studio-platform.md).

### UC-04 — Draft and inspect targeting and label SQL

**Primary users:** data scientists and data engineers.

**Trigger:** the team needs a proposed eligible population and target labels.

**Inputs:** the requirement, governed data assets and columns, observables, and
lineage.

**Flow:**

1. Complete data feasibility.
2. Request targeting design through the configured LLM gateway.
3. Inspect all retained proposals, including rejected proposals.
4. Review SQL shape, governed references, time conditions, and leakage findings.
5. Accept named unknowns only after review when the stage requires it.

**Output:** cohort and label SQL drafts, invocation references, and deterministic
validator verdicts.

**Example:** reject a proposed eligibility query that already filters for the
future booking-completion outcome.

**Success evidence:** the accepted proposal has explainable validator results;
rejected proposals and their reasons remain visible.

**Boundary:** SQL is not executed. Validation covers a supported PostgreSQL
`SELECT` subset and declared metadata. Undocumented derivations and incomplete
lineage can hide leakage.

**Entry point:** initiative stage `TARGETING_DESIGN`.
See [targeting and feature design](targeting-feature-design.md).

### UC-05 — Propose features without silently making them trusted

**Primary users:** data scientists and knowledge reviewers.

**Trigger:** an initiative needs features beyond the initially discovered assets.

**Inputs:** the requirement, governed source columns, existing approved features,
and feature constraints.

**Flow:**

1. Run feature design after targeting.
2. Inspect reuse findings and proposed feature definitions.
3. Follow generation evidence and `AI_GENERATED_HYPOTHESIS` provenance.
4. Review a new candidate through the knowledge lifecycle before it is trusted.

**Output:** an existing feature selected for reuse or an `EXTRACTED` feature
candidate with retained proposal and validation records.

**Example:** the deterministic adapter proposes `recent-session-engagement`.
Its generation record explains its origin, but does not demonstrate predictive
value.

**Success evidence:** approved reuse avoids unnecessary duplication; newly
generated features remain distinguishable from approved knowledge.

**Boundary:** approving a feature-design stage does not approve its knowledge
objects. Handoff separately checks that referenced features are approved.
The application does not execute a feature build.

**Entry points:** initiative stage `FEATURE_DESIGN`; knowledge review endpoints.
See [targeting and feature design](targeting-feature-design.md).

### UC-06 — Define a bounded experiment

**Primary users:** data scientists and experiment reviewers.

**Trigger:** a proposed intervention needs an experiment design.

**Inputs:** control and treatment variants, allocations, target observable,
baseline conversion rate, minimum detectable effect, alpha, and power.

**Flow:**

1. Complete feature design.
2. Run experiment design.
3. Check variant names, allocations, sample-size inputs, and exposure thresholds.
4. Review any unknown assumptions before completing the stage.

**Output:** an experiment definition, a deterministic two-proportion sample-size
calculation when inputs are available, and a decision rule or explicit `UNKNOWN`.

**Example:** training inputs of baseline `0.10`, absolute detectable difference
`0.02`, alpha `0.05`, and power `0.80` produce 3,841 exposures per variant in the
current implementation. Those inputs are an example, not observed client data.

**Success evidence:** the team can trace the threshold back to declared inputs.
Invalid allocations block the stage; absent assumptions are not filled with
invented numbers.

**Boundary:** the application does not assign real traffic, run the experiment,
measure uplift, or establish a causal result.

**Entry point:** initiative stage `EXPERIMENT_DESIGN`.
See [experiment design and handoff](experiment-design-and-handoff.md).

### UC-07 — Hand an approved design to the runtime team

**Primary users:** initiative reviewers and the Aurora runtime owner.

**Trigger:** the proposed design is ready for implementation outside Model Studio.

**Inputs:** completed predecessor stages, approved referenced features, accepted
unknown checks, required observables, no relevant blocking conflicts, and a
configured receiver.

**Flow:**

1. Run `HANDOFF` to check prerequisites and persist the proposed package.
2. Review the package and its hash.
3. Record an explicit human approval.
4. Verify the recorded receiver result, or investigate a recorded failure.

**Output:** a content-hashed design package and a delivery-attempt record.
A successful receiver response supplies a candidate ID and `AWAITING_WEIGHTS`.

**Example:** send a booking-intent design for client MLOps to implement and train.
The content hash binds the approval to the package, and serves as the outbound
idempotency key.

**Success evidence:** a real receiver returns HTTP 201, a candidate ID, and
`AWAITING_WEIGHTS`. A local stage response alone is insufficient proof.

**Boundary:** registration creates a design candidate. It does not create weights
or a deployed model. `CANDIDATE_BUILD` is permanently `OUT_OF_SCOPE` here.
A missing local token records `AURORA_NOT_CONFIGURED` without sending a request.

**Entry points:** initiative stage `HANDOFF`; the external
`POST /api/models/{name}/candidates` seam.
See [the handoff contract](handoff-contract.md).

### UC-08 — Explain decisions and inspect change impact

**Primary users:** reviewers, maintainers, and data engineers.

**Trigger:** someone asks why an asset was trusted, why a stage stopped, or what
depends on a changing definition.

**Inputs:** a knowledge-object ID or initiative ID within the current client.

**Flow:**

1. Retrieve the object package, evidence, provenance, and conflicts.
2. Inspect exact-version relationships and bounded impact paths.
3. Retrieve initiative attempts, events, gate decisions, and durations.
4. Explain the decision from those records, preserving uncertainty.

**Output:** a traceable account of source, review, dependency, and workflow state.

**Example:** before replacing a feature definition, identify dependent models and
the implementations it relies on. For a refused handoff, locate the precise
unapproved feature or conflict.

**Success evidence:** the explanation cites object versions, evidence, check
names, and decisions rather than relying on a verbal account.

**Boundary:** impact traversal is bounded to five hops and only knows persisted
relationships. Tenant-scoped records and append-only audit storage do not make
caller-declared actors authenticated identities. There is no general audit UI.

**Entry points:** `/api/knowledge/{id}`, `/{id}/evidence`, `/{id}/impact`;
`GET /api/initiatives/{id}`. Some audit and package inspection uses SQL.
See [knowledge](knowledge-model.md).

### UC-09 — Refresh source knowledge while preserving lineage

**Primary users:** data engineers and application maintainers.

**Trigger:** an Aurora source checkout changes or a scheduled import is rerun.

**Inputs:** the same source layout, with unchanged, changed, or reverted artifacts.

**Flow:**

1. Import and extract the source.
2. Compare object versions, evidence, and relationship endpoints.
3. Review newly created versions where content changed.
4. Repeat an unchanged run to check convergence.

**Output:** unchanged imports reuse the object owning the matching importer
evidence; changed sources can create additional versions.

**Example:** extraction creates a newer interpretation of a booking feature.
An unchanged importer run still links to the original imported evidence owner,
so it does not silently redirect model relationships to the extracted version.

**Success evidence:** unchanged combined runs preserve exact relationship tuples
as well as object and evidence counts. Reverting an import can reuse its earlier
source-backed version.

**Boundary:** importer and extraction have different source identities and
version behavior. A reverted import does not delete later versions or old edges.
Previously affected databases need an independently reviewed cleanup if unwanted
relationships from older application revisions remain.

**Entry points:** importer/extractor CLI and the knowledge ingestion contract.
See [module architecture](module-architecture.md).

## 5. A complete example and a useful refusal

### Booking propensity

The analyst defines `BOOKING_COMPLETED` over a 30-day horizon. The team imports
source knowledge, reviews suitable records, and runs discovery. An initiative
then records a reuse decision, data-feasibility findings, targeting and feature
designs, and an experiment definition. After referenced features are approved,
a reviewer approves the hashed handoff package.

The result is an implementable design with its evidence and decisions. Whether it
predicts accurately or improves marketing outcomes must be established later by
the client execution and evaluation process.

### Cancellation prevention

The analyst instead requires `BOOKING_CANCELLED`, but the catalog does not contain
that observable. Discovery exposes the gap and feasibility blocks progress.

The useful result is a specific instrumentation requirement. The team should
obtain the event and supporting evidence before resuming; changing the target
merely to make the workflow pass would change the business request.

## 6. Choosing the right tool

Use Model Studio when the immediate need is to assemble and review an
evidence-backed design from governed knowledge.

Use the client data platform for querying or profiling real data and building
features. Use the client's ML platform for training, tuning, evaluation, model
artifacts, deployment, and operations. Use the runtime platform to execute
decisions and experiments after the appropriate external implementation.

Importing knowledge improves the catalog available to the application. It does
not fine-tune the LLM or train a predictive model. The default deterministic
provider makes the workshop reproducible without external credentials.

## 7. Suggested adoption measures

These are suggested team measures, not built-in dashboards or promised savings:

- Can a reviewer trace a proposed feature to its source and exact version?
- How many requirements expose missing observables before implementation begins?
- How often does the team reuse an approved asset instead of creating a duplicate?
- Are accepted unknowns named, explained, and assigned follow-up actions?
- Can the receiver identify the exact approved package?

For the developer implementation map, continue with
[module architecture](module-architecture.md). For hands-on onboarding, continue
with [the junior training guide](junior-training-guide.md).
