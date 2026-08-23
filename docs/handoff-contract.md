# Candidate handoff

The Model Studio handoff transfers an approved design package to Aurora. It
does not register a trained model as `TESTED`: Model Studio never trains
anything and therefore supplies no weights or evaluation.

The receiving contract is:

```text
POST /api/models/{name}/candidates
  X-Aurora-Studio-Token: <shared token>
  Idempotency-Key: <packageHash>
  { studioInitiativeId, requirementId, packageHash, modelName, targeting,
    features, dataAssets, experimentDesign, feasibility, evidence,
    declaredObservables, notIncluded }
→ 201 { candidateId, status: "AWAITING_WEIGHTS" }
```

The package is immutable, deterministically serialized, SHA-256 hashed, and
uses that hash as the idempotency key. The hash covers the package fields
except the transport envelope fields `studioInitiativeId` and `packageHash`;
maps are recursively key-sorted, arrays retain order, and Jackson's compact
UTF-8 JSON writer is used. Aurora repeats those exact steps before insertion.
Aurora candidates are not model
versions and are not servable. A client must provide trained weights and a
human-controlled evaluation before a model version can earn `TESTED`.

Model Studio records every outbound attempt and contains transport or remote
failures without creating a local registration. The Aurora receiving endpoint
belongs to a separate repository and review.

`HANDOFF` approval requires a named human actor and a non-empty reason; agent
identities, including the orchestrator, cannot approve. Knowledge approval
likewise requires an explicit named actor—there is no anonymous default.

`HttpAuroraCandidateClient` has a Java-level default of
`http://localhost:8080`, which fails from inside the Model Studio container
because that address refers to the container itself. Compose overrides it with
`http://host.docker.internal:8080` plus a host-gateway mapping, which is the
working presenter topology. If the shared token is missing, the presenter
records `AURORA_NOT_CONFIGURED` and makes no anonymous POST.
