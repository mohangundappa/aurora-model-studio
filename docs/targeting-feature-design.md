# Targeting and feature design

Model Studio sends targeting and feature proposals through the provider-neutral
LLM gateway. Every proposal is retained, including rejected proposals, together
with the invocation reference and deterministic validator verdicts.

## What SQL validation checks

Targeting SQL is parsed with JSqlParser and checked against governed data-asset
metadata. The validators check the SQL text and the declared metadata only:
whether it is one read-only `SELECT`, whether its tables and columns are
governed, whether its projections meet the cohort and label contracts, and
whether its time predicates are safe.

Generated SQL is never executed against client data. Therefore no validator
result is based on returned rows, database contents, query execution, or runtime
query success. This is deterministic metadata validation, not an execution-based
SQL verifier. If governed column metadata is absent, dependent checks remain
`UNKNOWN`; columns are not inferred from application code or prose.

Target leakage analysis only sees parsed references and governed lineage. Leakage
through an ungoverned or undocumented derivation is invisible to this validator,
and Model Studio does not claim to catch it. That explicit boundary is preferable
to presenting metadata-only analysis as proof that every possible leakage path was
checked.

JSqlParser supports only the PostgreSQL `SELECT` subset required by this demo.
It does not provide complete PostgreSQL dialect coverage. PostgreSQL constructs
outside that supported subset may be rejected as unsupported or produce an
unknown validation outcome, depending on which validator encounters them.

## Generated feature lifecycle

Generated features use the existing knowledge lifecycle as `EXTRACTED`
candidate objects. Their generated fields carry `AI_GENERATED_HYPOTHESIS`
provenance and a generation-record evidence reference. They are not visible to
trusted retrieval unless `includeCandidates=true` is requested. A candidate
must pass the existing human review and approval lifecycle before becoming
trusted, and a near-duplicate of an approved feature is reported for reuse
instead of creating a second definition.
