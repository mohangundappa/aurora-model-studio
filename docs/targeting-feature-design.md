# Targeting and feature design

Model Studio sends targeting and feature proposals through the provider-neutral
LLM gateway. Every proposal is retained, including rejected proposals, together
with the invocation reference and deterministic validator verdicts.

Targeting SQL is parsed with JSqlParser and checked against governed data-asset
metadata. It is never executed against client data. The parser covers the
PostgreSQL `SELECT` subset used by the demo; this validation does not claim
complete PostgreSQL dialect coverage. Unknown governed columns remain
`UNKNOWN`, rather than being inferred from application code or prose.

Generated features use the existing knowledge lifecycle as `EXTRACTED`
candidate objects with `AI_GENERATED_HYPOTHESIS` field provenance. They are not
visible to trusted retrieval unless `includeCandidates=true` is requested and
must pass the existing human approval lifecycle before becoming trusted.
