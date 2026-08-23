# Architecture

Aurora Model Studio is a Spring Boot multi-module application:

- `common` contains tenant-safe identifiers and enums.
- `knowledge` contains the versioned Enterprise Knowledge domain, JDBC persistence,
  lifecycle rules, confidence calculation, structured retrieval, and impact traversal.
- `importer` reads source artifacts from an Aurora Intelligence checkout without copying
  them, hashes each artifact, and writes extracted candidates with evidence.
- `app` aggregates the modules, runs Flyway, exposes OpenAPI, and supplies Compose wiring.

Every persistence operation is scoped through `ClientContext`, populated by the required
`X-Aurora-Client` request header. Unknown and missing clients are rejected with HTTP 400.
The database carries `client_id` on every knowledge table and all repository predicates
include it. The local actor parameter is deliberately self-declared and unverified;
audit rows do not imply authentication.

The phase 1 flow is:

1. Source artifacts become immutable-version candidates.
2. Evidence and deterministic confidence are recorded.
3. A candidate moves through review and human approval.
4. Approved knowledge is the only trusted retrieval result.
5. Relationships and bounded impact paths make dependencies inspectable.

Phase 1 deliberately has no LLM, AI gateway, semantic/vector search, graph database,
similarity ranking, catalog connectors, workflow orchestration, frontend, or production
handoff. The future handoff is an HTTP contract only. Production deployment and
monitoring are client MLOps concerns.
