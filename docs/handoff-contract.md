# Candidate handoff

The Model Studio handoff transfers an approved design package to Aurora. It
does not register a trained model as `TESTED`: Model Studio never trains
anything and therefore supplies no weights or evaluation.

The receiving contract is:

```text
POST /api/models/{name}/candidates
  { studioInitiativeId, packageHash, targeting, features,
    experimentDesign, feasibility, evidence, notIncluded }
→ 201 { candidateId, status: "AWAITING_WEIGHTS" }
```

The package is immutable, deterministically serialized, SHA-256 hashed, and
uses that hash as the idempotency key. Aurora candidates are not model
versions and are not servable. A client must provide trained weights and a
human-controlled evaluation before a model version can earn `TESTED`.

Model Studio records every outbound attempt and contains transport or remote
failures without creating a local registration. The Aurora receiving endpoint
belongs to a separate repository and review.
