# Human-gate feature-set binding

**Status: TO BUILD.** The existing human gate records a decision for a stage,
actor, reason and accepted unknown checks. This specification adds the
versioned feature-set binding required before Layer 3 can use an approved set.
It does not implement Python execution, client adapters or model training.

Realises [the human gate rail](../cross-cutting-human-gate.md), part of [the implementation specification index](README.md).

## 1. Scope

Extend the gate contract so an approval names an exact immutable feature-set
version and its canonical content hash. At execution time, the Python-side
service must refuse any feature-set content whose recomputed hash differs.
Existing `InitiativeService.decide`, actor checks, reasons,
`acceptedUnknownChecks`, `initiative_gate_decisions` and human-only database
guard remain in force.

## 2. Module and package layout

Java changes belong in the existing `initiative` module:

```text
initiative/src/main/java/com/aurora/studio/initiative/FeatureSetBinding.java
initiative/src/main/java/com/aurora/studio/initiative/FeatureSetCanonicalizer.java
initiative/src/main/java/com/aurora/studio/initiative/FeatureSetApproval.java
```

Extend the existing `GateDecisionRequest` with optional fields:

```java
String featureSetId;
Integer featureSetVersion;
String featureSetHash;
```

Do not add these fields to current gates unless the stage requires a feature
set. `V17__approved_feature_sets.sql` is defined in the Python-side handoff;
this document specifies the cross-service contract and links to its
authoritative DDL.

## 3. Types

```java
public record FeatureSetBinding(
    UUID featureSetId,
    int featureSetVersion,
    String featureSetHash) {}

public record FeatureSetApproval(
    UUID featureSetId,
    int featureSetVersion,
    String approvedHash,
    UUID gateDecisionId,
    List<String> acceptedUnknownChecks) {}

public interface FeatureSetCanonicalizer {
  String canonicalJson(Map<String, Object> featureSetContent);
  String sha256(String canonicalJson);
  FeatureSetBinding bind(UUID featureSetId, int version, Map<String, Object> content);
}

public interface FeatureSetVerifier {
  void requireMatch(FeatureSetBinding approved, Map<String, Object> content);
}
```

`FeatureSetCanonicalizer` must match `HandoffPackage.create` exactly:
recursively sort object keys, preserve array order, serialise compact UTF-8 JSON,
compute lowercase SHA-256. The transport envelope is not part of the content:
`initiativeId`, `idempotencyKey` and `contentHash` are excluded.

## 4. Behaviour

### Approval

1. `InitiativeService.decide` validates the existing gated stage, non-blank
   actor, non-blank reason, exact machine identity guard and decision verb.
2. For the Layer 3 feature-set approval contract, require all three binding
   fields. Reject a partial binding before any insert.
3. Load the client-scoped immutable feature-set version and recompute its
   canonical hash using `HandoffPackage`-equivalent logic.
4. Reject if the submitted hash differs from the stored feature-set hash.
5. For feasibility/design gates, preserve existing `acceptedUnknownChecks`
   sorting and exact equality. An `APPROVE` with UNKNOWN checks still requires
   every expected check name; a feature-set hash never substitutes for that
   list.
6. Insert the gate decision, then insert the V17 approved-feature-set row
   linked by its immutable gate-decision audit reference. Set
   `aurora.initiative_gate_actor` to `human` in the same transaction; the
   existing trigger rejects direct inserts and `actor_verified=true`.
7. Only after both records are committed may the execution handoff read the
   approved binding. The Java orchestrator still owns stage transitions.

### Execution verification

1. The execution request carries `featureSetId`, `featureSetVersion`,
   `featureSetHash` and the canonical feature-set content.
2. The Python service loads the approved version for the client and
   recomputes the hash over content only.
3. If id, version or hash differs, return `FEATURE_SET_HASH_MISMATCH` and
   persist an execution refusal in V18. Do not train or build anything.
4. If all match, pass exactly that canonical content to the deterministic
   execution judge. No request field can replace the approved content.

## 5. Schema

The authoritative V17 DDL is in `java-python-seam.md`, alongside V18, because
the approved set is the payload boundary consumed by both Python services.

## 6. HTTP contract

The existing gate route remains:

```text
POST /api/initiatives/{id}/stages/{stage}/decision
```

Extended request:

```json
{
  "decision": "APPROVE",
  "actor": "named-human",
  "reason": "Approved the exact feature set after review.",
  "acceptedUnknownChecks": [],
  "featureSetId": "00000000-0000-0000-0000-000000000030",
  "featureSetVersion": 4,
  "featureSetHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

Response remains the existing `Initiative` JSON. Proposed execution seam:

```text
POST /api/execution/feature-sets/verify
```

Request:

```java
public record VerifyFeatureSetRequest(
    UUID initiativeId,
    UUID featureSetId,
    int featureSetVersion,
    String featureSetHash,
    Map<String, Object> canonicalFeatureSet) {}
```

| Condition | Status | Outcome |
| --- | --- | --- |
| Complete matching approval | 200 | Existing initiative/gate response |
| Partial binding | 400 | `FEATURE_SET_BINDING_REQUIRED` |
| Hash differs at approval | 409 | `FEATURE_SET_HASH_MISMATCH` |
| Unknown feature-set version | 404 | `FEATURE_SET_NOT_FOUND` |
| Unknown checks omitted | 400 | Existing accepted-unknown validation |
| Machine actor | 400 | Existing self-approval refusal |
| Execution hash mismatch | 409 | `FEATURE_SET_HASH_MISMATCH`; V18 refusal row |
| Direct database insert | SQL error | Existing human-gate trigger |

## 7. Configuration

No feature-set binding is configurable. Add only:

| Property | Type | Default | Validation |
| --- | --- | --- | --- |
| `aurora.feature-set.hash-algorithm` | `String` | `SHA-256` | only `SHA-256` accepted |
| `aurora.feature-set.require-binding` | `boolean` | `true` | cannot be false for Layer 3 |

The canonicalisation algorithm is a contract, not an operator setting.

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `FEATURE-SET-CANONICAL-OBJECT-KEYS` | Recursively sort JSON object keys. |
| `FEATURE-SET-CANONICAL-ARRAY-ORDER` | Preserve every array's submitted order. |
| `FEATURE-SET-CANONICAL-UTF8` | Hash compact UTF-8 JSON bytes. |
| `FEATURE-SET-CANONICAL-SHA256` | Store lowercase SHA-256 hex. |
| `FEATURE-SET-ENVELOPE-EXCLUDED` | Exclude initiative, idempotency and transport hash fields. |
| `FEATURE-SET-EXACT-VERSION` | Approval names one immutable id and version. |
| `FEATURE-SET-HASH-MATCH` | Execution recomputation must equal the approved hash. |
| `FEATURE-SET-UNKNOWN-ACCEPTANCE` | Accepted UNKNOWN checks must still exactly match expected names. |
| `FEATURE-SET-NO-SELF-APPROVAL` | Existing exact-set machine identity guard remains active. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | --- |
| Missing binding field | Refused | No gate insert | 400 |
| Hash mismatch at approval | Refused | No approval; optional validation audit | 409 |
| Hash mismatch at execution | Refused | V18 execution attempt | 409 |
| Unknown check not accepted | Refused | No approval | 400 |
| Machine actor | Refused | No approval | 400 |
| Direct SQL insert | Refused by trigger | No row | SQL error |
| Matching immutable set | Approved binding | Gate decision and V17 reference | 200 |

## 10. Tests to write

Unit tests:

- `FeatureSetCanonicalizerSortsNestedObjectKeys`.
- `FeatureSetCanonicalizerPreservesArrayOrder`.
- `FeatureSetCanonicalizerProducesLowercaseSha256`.
- `FeatureSetCanonicalizerExcludesTransportEnvelope`.
- `FeatureSetVerifierRejectsChangedContent`.
- `FeatureSetApprovalRequiresAllBindingFields`.
- `FeatureSetApprovalStillRequiresExactAcceptedUnknownChecks`.

`InitiativeServiceTest` additions:

- `featureSetApprovalStoresExactBinding`.
- `featureSetApprovalRejectsHashMismatchBeforeInsert`.
- `machineActorCannotApproveFeatureSet`.
- `featureSetApprovalDoesNotReplaceAcceptedUnknownChecks`.

Testcontainers tests:

- `ApprovedFeatureSetIsTenantScoped`.
- `ApprovedFeatureSetVersionIsImmutable`.
- `GateDecisionBindingCannotPointAcrossClients`.
- `FeatureSetHashMismatchIsRejectedByExecutionSeam`.

Reuse `HandoffPackageTest.hashIsDeterministicAndContentSnapshotIsImmutable` as
the canonicalisation regression fixture and extend it rather than introducing
a second hashing implementation.

## 11. Acceptance criteria

- [ ] The hash bytes and canonicalisation exactly match `HandoffPackage.create`.
- [ ] Envelope fields are excluded from the hash.
- [ ] Approval stores one immutable feature-set id/version/hash.
- [ ] Execution rejects any id, version or content hash mismatch before data
  access.
- [ ] `acceptedUnknownChecks` remains independently exact.
- [ ] Existing actor and human-session guards remain active.
- [ ] V17 is introduced only by this Python-side handoff; no earlier
      migration is changed.

## 12. Open decisions

- Which existing initiative stage owns the Layer 3 feature-set approval.
  Recommendation: add a dedicated execution approval stage rather than
  overloading `FEATURE_DESIGN`, because design approval and execution binding
  have different payloads.
- Whether V17 stores canonical JSON as `jsonb` or compact `text`.
  Recommendation: store both `jsonb` content for querying and the exact
  canonical hash; recompute from canonical content, never from transport JSON.
