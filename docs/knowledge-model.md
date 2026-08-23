# Enterprise Knowledge model

## Objects, evidence, and tenants

`knowledge_objects` stores immutable logical versions. Every object, evidence row,
relationship, conflict, and audit row has a `client_id`; repository queries require the
`ClientContext` populated from the `X-Aurora-Client` header. PostgreSQL also enforces
composite `(client_id, id)` foreign keys, so a child row cannot point across clients.
Actors are self-declared local-demo values, not authenticated identities.

The supported types are `MODEL`, `FEATURE`, `DATA_ASSET`, `IMPLEMENTATION`, `EXPERIMENT`,
and `STANDARD`. Type-required attributes are validated at write time. Recommended
attributes improve completeness but are not required. Evidence records the source
system, source type, URI, resolved source version, excerpt, extraction certainty, and
recorded timestamp. Candidates without evidence cannot be approved.

Source artifacts may declare their own lifecycle or review metadata. The importer and
extraction path preserve those values only under `attributes.sourceDeclared`; they are
descriptive source metadata, never Model Studio governance. The authoritative lifecycle,
confidence, approval, and reviewer columns are populated and enforced by Model Studio
itself, so a source-declared `APPROVED` or confidence value cannot make a candidate
trusted.

Relationships refer to exact object versions. A relationship from `A` to `B` means
that `A` depends on `B`; impact results therefore label outgoing paths `DEPENDS_ON`
and incoming paths `DEPENDENT`. Traversal is bounded to five hops, cycle-safe, and
returns path IDs and relationship types.

## Lifecycle and trust

The legal transitions are:

```text
EXTRACTED -> PENDING_REVIEW -> APPROVED -> SUPERSEDED
                                      -> DEPRECATED
PENDING_REVIEW -> DEPRECATED
```

All other transitions are rejected with HTTP 409 and include the attempted
`from -> to` transition. Approval requires evidence. Approved rows are protected by
a database trigger: their content is immutable, and only movement to `SUPERSEDED` or
`DEPRECATED` is allowed. Non-`EXTRACTED` rows cannot be deleted. A partial unique
index permits only one approved version per client and logical key.

Only `APPROVED` objects are trusted. Default search always applies `APPROVED`; an
explicit non-approved status is rejected unless `includeCandidates=true`. By-ID
retrieval hides non-approved objects unless candidates are explicitly requested.
List objects expose derived `trusted` and persisted `synthetic` fields. Audit rows
are append-only at the database layer: raw `UPDATE` and `DELETE` operations are
rejected. The audit actor remains self-declared and unverified.

## Confidence

Confidence is bounded to `0..1`, persisted with its signal breakdown and quality
assessment, and uses configurable weights:

| Signal | Weight | Derivation |
| --- | ---: | --- |
| Source reliability | 0.25 | Evidence `source_type` mapped to the documented source authority |
| Cross-source agreement | 0.20 | Distinct evidence source systems divided by evidence count; unknown with fewer than two evidence rows |
| Extraction certainty | 0.20 | Average of evidence `extraction_certainty` |
| Completeness | 0.15 | Populated type-required and recommended attributes divided by the type's total relevant attributes |
| Recency | 0.10 | Linear decay over the gap between the object's latest evidence and the newest evidence for its logical key |
| Execution evidence | 0.10 | `1` only for explicit execution evidence, otherwise `0` because absence is observable evidence of no execution |

Weights are configured under `studio.confidence`. Unknown signals are stored as
`null`, excluded from the weighted sum, and cause the remaining known weights to be
renormalized. An open conflict is preserved, surfaced as a warning, and caps the
result at `0.5`; phase 1 does not reconcile conflicting sources automatically.
Contextual performance is a typed field and remains `null` when no performance
evidence exists—zero is never fabricated.

## Packages and constraints

Structured packages include the source evidence, resolved implementations, exact
relationships, constraints derived from attributes, confidence, trust, synthetic
status, warnings, conflicts, lifecycle, and relevance. Feature constraints include
consent, observation window, point-in-time availability, and restricted usage when
those attributes exist. Standards include their enforcement point. Data assets
include available governance, retention, access, history, and quality limits.
