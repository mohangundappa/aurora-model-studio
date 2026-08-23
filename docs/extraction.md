# Extraction

Extraction has two ordered passes.

1. The deterministic structural pass reads only declared roots and patterns:
   signal definitions, calculator implementations, the decision policy,
   experiment definitions, the model registry migration, and two curated
   documents. Missing declared roots fail the run. Hard exclusions for
   `node_modules`, `.git`, build output, lockfiles, generated sources, and
   `.github/workflows` apply even when a root is broad. Files must match a
   supported shape; extension alone never creates knowledge. Skips are counted
   in the extraction run summary. The pass records identifiers, inputs,
   windows, types, referenced tables/columns, file paths, commit/content hashes,
   and bounded evidence excerpts without using a model. The run summary
   reports `skipped` for files that fail selection, exclusion, or shape
   recognition, separately from `unchanged` artifacts that are already
   represented by the same source version.
2. The interpretation pass receives those facts and excerpts in a constrained
   data envelope. It may propose descriptions, domains, use cases, taxonomy,
   recommended attributes, and relationship hypotheses. Every retained
   interpreted field must cite text that is actually present in an evidence
   excerpt. Unsupported fields are dropped.

Structural fields are `EVIDENCE_BACKED` with certainty `1.0`. Interpreted
fields carry their classification and the configured “model-interpreted”
certainty; the model cannot choose that value. Extraction can create only
`EXTRACTED` candidates. It cannot provide confidence, approve an object, or
advance lifecycle status. Confidence remains derived by the Knowledge service
from evidence and populated attributes, with unknown signals excluded and
weights renormalized.

Provider refusals, transport failures, and schema-invalid responses create no
candidate, but the invocation outcome is recorded. A model-assisted candidate
references its producing invocation. Approved objects are never overwritten.

Extraction uses the importer’s logical keys for recognized Aurora artifacts
(`feature:<name>`, `implementation:calculator:<name>`,
`implementation:decision-policy`, and `model:<name>:<version>`). Other
recognized artifacts use a stable kind-plus-relative-path identity. Before
interpretation, the source commit and content hash are compared with evidence
already stored for that key. Unchanged reruns create no object; a changed
source creates one new version and preserves the old version. Duplicate logical
keys in a single run are suppressed.

The synthetic legacy estate is separate from Aurora Intelligence backfill. Its
objects carry `synthetic=true` in storage and in retrieval packages, and runs
report synthetic and real counts separately. Synthetic fixtures include stale
specification/SQL disagreement, conflicting loyalty-tenure definitions,
near-duplicate models, an artifact without an implementation, and a
document-only artifact.

Extraction wording checks are tripwires, not proof of truthfulness. The
enforced controls are structured prompts, redaction, schema validation,
evidence citation checks, deterministic lifecycle transitions, and database
constraints.
