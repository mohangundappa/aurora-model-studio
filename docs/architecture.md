# Architecture

Aurora Model Studio is a Spring Boot multi-module application:

- `common` contains tenant context and domain enums.
- `knowledge` contains versioned Enterprise Knowledge, JDBC persistence, lifecycle
  enforcement, confidence calculation, structured retrieval, and impact traversal.
- `importer` reads artifacts in an Aurora Intelligence checkout in place, hashes them,
  and writes extracted candidates with evidence. It never copies that source tree.
- `app` aggregates the modules, runs Flyway, exposes the HTTP API and health endpoint,
  and supplies Compose wiring.

## Request and tenant boundaries

The required `X-Aurora-Client` header is validated against configured client IDs;
missing, malformed, and unknown IDs return HTTP 400. `ClientContext` scopes repository
queries and writes. The database repeats that boundary: each knowledge table stores
`client_id`, `knowledge_objects` has a unique `(client_id, id)` key, and child tables
use composite foreign keys containing both the client and object ID. Cross-client
references therefore fail at the database layer as well as being absent from normal
repository queries.

Local actors are self-declared and unverified. They are recorded for demo traceability
only and do not constitute authentication, authorization, or an audit identity.

## Governed knowledge loop

1. Source artifacts become versioned `EXTRACTED` candidates with evidence.
2. Candidates may move to `PENDING_REVIEW`, then require human approval to become
   `APPROVED`.
3. Approved knowledge is the only default trusted retrieval result; candidate
   retrieval requires `includeCandidates=true`.
4. Approved versions may be superseded or deprecated, but their content cannot be
   edited. Audit rows are database-enforced append-only.
5. Confidence is computed from evidence and populated attributes. Unknown signals are
   omitted and weights are renormalized. Open conflicts warn and cap confidence at
   `0.5`.
6. Directional, bounded, cycle-safe impact traversal separates dependencies from
   dependents. Governance-rule lookup matches the structured
   `attributes.enforcementPoint` field.

The importer currently backfills features, implementations, models, data assets, and
standards from Aurora Intelligence without modifying that repository. Aurora Intelligence
remains a separate product and source system.

## Phase 1 boundaries

Phase 1 deliberately does not contain:

- AI, LLM, agent orchestration, or an AI gateway;
- semantic or vector search, graph database, or similarity ranking;
- catalog connectors, continuous synchronization, or production data ingestion;
- a frontend, production deployment, runtime model serving, or monitoring;
- a handoff receiving endpoint or implementation.

The handoff is a future HTTP contract only. Production deployment and monitoring remain
client MLOps responsibilities and require an explicit human decision in the governed
lifecycle.
