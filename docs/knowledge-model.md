# Enterprise Knowledge model

`knowledge_objects` stores one logical key as immutable versions. Each row carries
client scope, type, business definition, type-specific JSON attributes, lifecycle
status, taxonomy, tags, synthetic marker, quality metadata, and deterministic
confidence with its breakdown. Evidence rows identify a source system, source type,
source URI, resolved source version, excerpt, and extraction certainty.

The supported types are `MODEL`, `FEATURE`, `DATA_ASSET`, `IMPLEMENTATION`, `EXPERIMENT`,
and `STANDARD`. Type-specific fields are validated at write time. Candidates without
evidence cannot be approved. Relationships refer to exact object versions and may cite
evidence. Conflicts preserve disagreeing values and their evidence; phase 1 records and
exposes conflicts rather than attempting automatic source reconciliation.

Allowed lifecycle transitions are:

```text
EXTRACTED -> PENDING_REVIEW -> APPROVED -> SUPERSEDED
                                      -> DEPRECATED
PENDING_REVIEW -> DEPRECATED
```

Approved objects are immutable in the database except for the lifecycle move to
`SUPERSEDED` or `DEPRECATED`. A partial unique index ensures only one approved version
exists per client and logical key. Audit rows are append-only and actor names are
self-declared and unverified in this local demonstration.

Confidence is computed from weighted signals: source reliability (0.25), cross-source
agreement (0.20), extraction certainty (0.20), completeness (0.15), recency (0.10),
and execution evidence (0.10). It is bounded to 0..1. An open conflict caps it at 0.5
and every package response carries an explicit warning. Unknown contextual performance
is `null`, never a fabricated zero.

Only approved objects are trusted. Candidates are excluded from default retrieval and
must be requested explicitly. Every response includes `trusted` and `synthetic`.
