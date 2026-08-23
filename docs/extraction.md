# Extraction

Extraction has two ordered passes.

1. The deterministic structural pass reads SQL migrations, YAML definitions,
   Java calculators, and Markdown documents. It records identifiers, inputs,
   windows, types, referenced tables/columns, file paths, commit/content hashes,
   and bounded evidence excerpts without using a model.
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
