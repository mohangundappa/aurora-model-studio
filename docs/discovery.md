# Model discovery and reuse

Discovery accepts a persisted `ModelRequirement`, recalls approved knowledge using the
union of pgvector nearest-neighbour and PostgreSQL full-text search, and applies a
deterministic scorecard. The scorecard is the verdict: an LLM can draft an explanation,
but it cannot rank, classify, or supply a number.

`POST /api/discovery/requirements` registers a requirement. Run it with
`POST /api/discovery/runs` and retrieve the append-only result with
`GET /api/discovery/runs/{id}`. The default corpus is approved-only. Candidate mode
requires `includeCandidates=true` and is labelled in the result.

Dimensions that cannot be derived remain `null`; configured weights under
`studio.discovery.weights` are renormalized over known dimensions. Blockers always
precede the composite score. In particular, a cancellation requirement reports the
missing `BOOKING_CANCELLED` event rather than inventing a proxy target.

Deterministic embeddings are the offline default and are byte-identical for identical
text. An OpenAI embedding adapter is shipped but requires `OPENAI_API_KEY`; provider
identities are persisted because vectors from different providers are not comparable.
Object vectors are written when knowledge is created or versioned. Existing objects can
be re-embedded with `--backfill-embeddings`; discovery runs embed only the requirement
and read vectors matching the active provider.

Requirements may declare `requiredObservables`. Each unresolved observable becomes a
run-level `MISSING_TARGET_OBSERVABLE:<name>` blocker, while candidate classifications
continue to describe their own structural fit. Synthetic evidence is blocked for an
ordinary client requirement and can be enabled explicitly with
`syntheticEvidenceAllowed` for exploratory demonstrations; synthetic watermarking
remains visible in every output.

The local demonstration corpus is deliberately small: 33 real Aurora artifacts and
the expanded watermarked synthetic estate are not an enterprise-scale legacy corpus.
Synthetic status is included on every discovery candidate and evidence record, and
synthetic evidence is never presented as client history.
