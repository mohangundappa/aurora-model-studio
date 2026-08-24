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
    declaredObservables, notIncluded, clientId }
→ 201 { candidateId, status: "AWAITING_WEIGHTS" }
```

The `clientId` field is the originating Model Studio client ID, carried as
provenance. Aurora records and returns it with the candidate; it is not a
claim of tenant isolation in Aurora's single-brand showcase deployment.
Candidate reads and writes with a missing or incorrect
`X-Aurora-Studio-Token` return
`401 {"error": ...}`. If Aurora has no token configured, it fails closed with
`503 {"error": ...}`. Malformed request JSON returns
`400 {"error":"request body must be valid JSON"}`. The responses never reveal
the configured token. `HttpAuroraCandidateClient` records both `401` and `503`
provider responses as `AURORA_REJECTED`, preserving the HTTP status in the
attempt record; a missing local outbound token is instead
`AURORA_NOT_CONFIGURED` and no anonymous request is made.

The authenticated read is:

```text
GET /api/models/{name}/candidates
  X-Aurora-Studio-Token: <shared token>
→ 200 [ { candidateId, modelName, packageHash, studioInitiativeId,
          clientId, status, packageContent, createdAt } ]
```

The package is immutable, deterministically serialized, SHA-256 hashed, and
uses that hash as the idempotency key. The hash covers the package fields
including provenance, except the transport envelope fields `studioInitiativeId`
and `packageHash`;
maps are recursively key-sorted, arrays retain order, and Jackson's compact
UTF-8 JSON writer is used. Aurora repeats those exact steps before insertion.
Aurora candidates are not model
versions and are not servable. A client must provide trained weights and a
human-controlled evaluation before a model version can earn `TESTED`.

Model Studio records every outbound attempt and contains transport or remote
failures without creating a local registration. The Aurora receiving endpoint
belongs to a separate repository and review.

`HANDOFF` approval requires a named human actor and a non-empty reason. The
exact governed machine-identity check is a self-approval guard: it prevents the
known orchestrator identity from rubber-stamping the human gate it created, but
does not verify that arbitrary caller-supplied names are human. The approver
identity is caller-asserted and unverified (`actorIdentityVerified:false` is
disclosed in the API), so a caller may supply any name. Knowledge approval
likewise requires an explicit named actor—there is no anonymous default.

`HttpAuroraCandidateClient` has a Java-level default of
`http://localhost:8080`, which fails from inside the Model Studio container
because that address refers to the container itself. Compose overrides it with
`http://host.docker.internal:8080` plus a host-gateway mapping, which is the
working presenter topology. If the shared token is missing, the presenter
records `AURORA_NOT_CONFIGURED` and makes no anonymous POST.
