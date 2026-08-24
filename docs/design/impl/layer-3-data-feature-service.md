# Layer 3: Data & Feature Execution Service

**Status: TO BUILD.** This is a Python service and is not present in the
repository. It is the only proposed component in this specification that may
read client data. Java remains the authority for thresholds, sample-size
mathematics, statistical tests, promotion criteria and every state transition.

## 1. Scope

Build an authenticated execution service that verifies an approved,
hash-bound feature-set contract, probes the configured client data adapter,
executes a bounded cohort query, builds the declared features and returns
observations, verdict inputs and artifact references. It does not interpret
business requirements, approve a feature set, change a threshold, promote a
model, or write Model Studio initiative state. It must refuse before client
data access when the content hash is wrong, the adapter is unavailable or the
request exceeds its limits.

## 2. Module and package layout

Create the following TO BUILD Python project:

```text
python/
  common/
    pyproject.toml
    aurora_common/__init__.py
    aurora_common/canonical_json.py
    aurora_common/client_scope.py
    aurora_common/attempts.py
    tests/test_canonical_json.py
  data_feature_service/
    pyproject.toml
    aurora_data_feature_service/__init__.py
    aurora_data_feature_service/main.py
    aurora_data_feature_service/config.py
    aurora_data_feature_service/models.py
    aurora_data_feature_service/routes.py
    aurora_data_feature_service/executor.py
    aurora_data_feature_service/quality.py
    tests/test_routes.py
    tests/test_hash_contract.py
    tests/test_fixture_parity.py
Makefile
contracts/feature-set-hash-fixtures.json
```

Use FastAPI, uvicorn and pydantic v2 for HTTP and validation. Use Polars for
bounded, columnar profiling and feature transformations: its lazy execution
plan makes row and column limits explicit before collection. Use the
TO BUILD `ClientDataAdapter` protocol from
`layer-4-client-adapters.md`; this service must not contain vendor SDK calls.

Each `pyproject.toml` declares the package name and dependencies using exact
`==` pins selected at implementation time. The selected release for every
dependency must be at least seven days old when pinned; this specification does
not invent version numbers. The workspace `Makefile` adds `python-test`,
`python-format` and `python-contract-fixtures` targets and runs both Python
projects from the repository root.

Required dependency names for `data_feature_service` are `fastapi`,
`uvicorn[standard]`, `pydantic`, `pydantic-settings`, `polars`, `httpx`,
`pytest` and `pytest-asyncio`. The shared project declares `pydantic`,
`pytest` and the repository's canonicalisation test dependencies. Every entry
uses the constraint `== <implementation-selected release>` and the pinning
rule above.

## 3. Types

The following pydantic v2 models are TO BUILD. UUIDs are represented as
`UUID`, timestamps as timezone-aware `datetime`, and hashes as lowercase
64-character hexadecimal strings.

```python
class FeatureDefinition(BaseModel):
    name: str
    entity: str
    source_columns: list[str]
    observation_window: str
    as_of_column: str
    point_in_time_available: bool
    expression: str
    description: str

class ApprovedFeatureSet(BaseModel):
    feature_set_id: UUID
    feature_set_version: int
    feature_set_hash: str
    features: list[FeatureDefinition]
    canonical_content: dict[str, object]

class QualityThreshold(BaseModel):
    name: str
    operator: Literal["GTE", "LTE", "EQ"]
    value: float

class DataExecutionRequest(BaseModel):
    execution_attempt_id: UUID
    initiative_id: UUID
    stage_attempt_id: UUID
    idempotency_key: str
    content_hash: str
    approved_feature_set: ApprovedFeatureSet
    cohort_sql: str
    as_of: datetime
    quality_thresholds: list[QualityThreshold]
    max_rows: int
    max_bytes: int
    timeout_seconds: int

class Observation(BaseModel):
    name: str
    value: float | int | str | bool | None
    unit: str | None
    citation: str

class FeatureObservation(BaseModel):
    feature_name: str
    row_count: int
    null_count: int
    distinct_count: int
    min_value: float | None
    max_value: float | None
    point_in_time_available: bool | None
    citation: str

class DataExecutionResponse(BaseModel):
    execution_attempt_id: UUID
    idempotency_key: str
    feature_set_hash: str
    status: Literal["COMPLETED", "UNKNOWN", "REFUSED", "FAILED"]
    observations: list[Observation]
    feature_observations: list[FeatureObservation]
    quality_inputs: list[QualityThreshold]
    artifact_references: list[str]
    warnings: list[str]
    response_hash: str
    completed_at: datetime

class ErrorResponse(BaseModel):
    code: str
    message: str
    execution_attempt_id: UUID | None
```

`quality_thresholds` are requested checks, not decisions. The response reports
the measured values and citations; Java applies the configured verdict rules.

## 4. Behaviour

`POST /internal/v1/data-feature/execute` runs this ordering:

1. Authenticate the service request and parse `X-Aurora-Client`,
   `Idempotency-Key` and `X-Aurora-Content-Hash`. Reject a missing or invalid
   client scope before dependency resolution.
2. Validate the pydantic request, positive limits and the equality of the
   transport `content_hash` and `approved_feature_set.feature_set_hash`.
3. Canonicalise `approved_feature_set.canonical_content` using the shared
   algorithm, calculate lowercase SHA-256 and compare it with the supplied
   hash. Do this before `probe()` and before any query.
4. Look up the idempotency key in the service attempt store. Return the exact
   stored response for an identical replay; return `409 IDEMPOTENCY_KEY_REUSE`
   when the key is paired with a different hash or request body.
5. Call `ClientDataAdapter.probe()` and require the capability report to allow
   read-only query execution. Persist a refusal observation if it does not.
6. Submit the cohort SQL through the adapter with the request row, byte and
   timeout limits. The adapter must reject SQL outside its allow-list and must
   not return an unbounded result.
7. Profile grain, history, nulls and row counts with Polars. Build only the
   declared `FeatureDefinition` expressions and retain each observation
   citation as `adapter://<binding>/<query-id>`.
8. Run local leakage and point-in-time checks as observations. These checks
   supplement, and do not replace, Java's deterministic judge. A missing
   timestamp or unavailable history is `UNKNOWN`, never a fabricated pass.
9. Canonicalise the response envelope excluding `response_hash`, calculate
   lowercase SHA-256 and populate `response_hash`. Store the response and
   attempt metadata through the append-only execution
   record contract in `java-python-seam.md`. Return the same response on a
   replay.

No database transaction in Python can advance an initiative. Java records the
dispatch and owns the transition after inspecting the response.

The Python checks are execution preflights only. Java remains the judge for
governed rule identifiers such as `target-leakage`,
`point-in-time-availability`, `observation-window-before-as-of`,
`governed-source-columns` and all configured quality thresholds.

## 5. Schema

No Python-owned relational migration is introduced. V18 records the dispatch
and response in Java; V19 records the adapter binding and limits. Python's
idempotency store is a TO BUILD deployment-local durable store only if the
service cannot synchronously consult V18. It stores
`client_id`, `idempotency_key`, `content_hash`, request digest and the complete
response, with a unique key on `(client_id, idempotency_key)`.

The service must emit an execution attempt reference that Java can insert into
`execution_attempts`. It must never update `initiative_stage_attempts`,
`initiative_events` or `initiative_gate_decisions`.

## 6. HTTP contract

Route: `POST /internal/v1/data-feature/execute`.

Required headers:

```text
X-Aurora-Service-Token: deployment-provided token
X-Aurora-Client: client UUID
Idempotency-Key: stable execution UUID or dispatch key
X-Aurora-Content-Hash: lowercase SHA-256
Content-Type: application/json
```

Example request:

```json
{
  "execution_attempt_id": "11111111-1111-1111-1111-111111111111",
  "initiative_id": "22222222-2222-2222-2222-222222222222",
  "stage_attempt_id": "33333333-3333-3333-3333-333333333333",
  "idempotency_key": "dispatch-1",
  "content_hash": "1094e9abfb53f8a426d0868532c74f2192c0d07f8c860d3a50a136c0221f96b5",
  "approved_feature_set": {
    "feature_set_id": "44444444-4444-4444-4444-444444444444",
    "feature_set_version": 1,
    "feature_set_hash": "1094e9abfb53f8a426d0868532c74f2192c0d07f8c860d3a50a136c0221f96b5",
    "features": [],
    "canonical_content": {
      "z": 1,
      "a": {"b": [2], "a": "x"},
      "items": ["first", "second"]
    }
  },
  "cohort_sql": "select entity_id, as_of from governed_cohort",
  "as_of": "2025-01-01T00:00:00Z",
  "quality_thresholds": [],
  "max_rows": 100000,
  "max_bytes": 104857600,
  "timeout_seconds": 30
}
```

| Condition | Status | Body code |
| --- | ---: | --- |
| Completed observations | 200 | `COMPLETED` |
| Unknown history, quality or time availability | 200 | `UNKNOWN` |
| Missing authentication or client scope | 401 | `SERVICE_UNAUTHENTICATED` |
| Invalid request or non-positive limit | 400 | `REQUEST_INVALID` |
| Hash mismatch before adapter access | 409 | `FEATURE_SET_HASH_MISMATCH` |
| Idempotency key reused with another request | 409 | `IDEMPOTENCY_KEY_REUSE` |
| Adapter absent or capability refused | 424 | `ADAPTER_UNAVAILABLE` or adapter refusal code |
| Query exceeds row, byte or time limit | 422 | `QUERY_LIMIT_EXCEEDED` |
| Adapter rejects query | 422 | `QUERY_REFUSED` |
| Unexpected service failure | 500 | `EXECUTION_FAILED` |

## 7. Configuration

Environment variables are TO BUILD and bind through pydantic settings:

| Environment variable | Type | Default | Validation |
| --- | --- | --- | --- |
| `AURORA_DATA_FEATURE_HOST` | `str` | `0.0.0.0` | non-blank |
| `AURORA_DATA_FEATURE_PORT` | `int` | `8091` | 1–65535 |
| `AURORA_SERVICE_TOKEN` | `str` | unset | required outside local development; never logged |
| `AURORA_JAVA_BASE_URL` | `str` | unset | HTTPS outside local development |
| `AURORA_REQUEST_TIMEOUT_SECONDS` | `int` | `30` | 1–300 |
| `AURORA_MAX_ROWS` | `int` | `100000` | positive |
| `AURORA_MAX_BYTES` | `int` | `104857600` | positive |
| `AURORA_ADAPTER_BINDINGS` | `str` | unset | deployment binding reference, not credentials |

Credentials are supplied by the deployment environment or adapter runtime,
never by request JSON or documentation.

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `DF-HASH-EXACT` | Canonical feature-set hash equals the supplied lowercase SHA-256 before adapter access. |
| `DF-IDEMPOTENCY-EXACT` | A repeated key returns the original response only for the same request digest. |
| `DF-READ-ONLY-QUERY` | The adapter accepts only the configured read-only query grammar. |
| `DF-ROW-CAP` | Returned rows never exceed `max_rows`. |
| `DF-BYTE-CAP` | Returned bytes never exceed `max_bytes`. |
| `DF-TIME-CAP` | Query execution is cancelled at the configured timeout. |
| `DF-FEATURE-DECLARED` | Only features in the approved version are built. |
| `DF-POINT-IN-TIME-OBSERVED` | Missing or forward-looking availability is reported as `UNKNOWN`. |
| `DF-LEAKAGE-OBSERVED` | Target or post-as-of columns are reported as leakage observations. |
| `DF-QUALITY-MEASURED` | Quality thresholds are returned as measured inputs; Java decides PASS, FAIL or UNKNOWN. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | ---: |
| Hash differs | `FEATURE_SET_HASH_MISMATCH` | Java V18 refusal; no adapter attempt | 409 |
| Missing adapter binding | `ADAPTER_UNAVAILABLE` | V18 refusal | 424 |
| Probe lacks read capability | `ADAPTER_CAPABILITY_REFUSED` | V18 refusal with capability report | 424 |
| Query exceeds a limit | `QUERY_LIMIT_EXCEEDED` | V18 failed attempt and measured limit | 422 |
| Query has unsafe operation | `QUERY_REFUSED` | V18 refusal | 422 |
| Adapter times out | `ADAPTER_TIMEOUT` | V18 failed attempt | 504 |
| Unknown history or as-of column | `UNKNOWN` | V18 completed observation set | 200 |
| Same idempotent request | replay | Original V18-linked response | 200 |
| Same key, different content | `IDEMPOTENCY_KEY_REUSE` | Existing row unchanged; refusal record | 409 |
| Unhandled exception | `EXECUTION_FAILED` | V18 failed attempt with bounded error code | 500 |

## 10. Tests to write

Pytest unit tests:

- `test_hash_mismatch_is_rejected_before_adapter_probe`: assert no
  `probe()` or query call and `FEATURE_SET_HASH_MISMATCH`.
- `test_canonical_hash_fixture_parity`: load every shared fixture and compare
  the Python digest with its expected hash.
- `test_duplicate_request_replays_original_response`: assert the adapter runs
  once and the second call is byte-equivalent.
- `test_idempotency_key_reuse_with_new_hash_is_conflict`: assert 409.
- `test_feature_build_never_reads_undeclared_feature`: assert the executor
  rejects a feature absent from the approved version.
- `test_row_byte_and_timeout_limits_are_forwarded`: assert all three limits
  reach the adapter.
- `test_missing_history_returns_unknown_observation`: assert no fabricated
  PASS input.
- `test_quality_thresholds_are_not_decided_in_python`: assert response carries
  measured values and Java-facing rule inputs only.

FastAPI tests:

- `test_execute_requires_service_token_and_client_scope`: assert 401.
- `test_execute_returns_424_for_missing_adapter`: assert refusal body.
- `test_execute_returns_422_for_unsafe_query`: assert stable code.
- `test_execute_returns_200_for_replay`: assert identical response JSON.

The shared fixture tests are mandatory in both Java and Python; any mismatch
fails the build rather than being treated as a warning.

## 11. Acceptance criteria

- [ ] No adapter or client-data access occurs before hash verification.
- [ ] The request and response models contain no untyped execution payload.
- [ ] Every query has row, byte, timeout and allow-list enforcement.
- [ ] Replays are idempotent and a changed request under the same key is 409.
- [ ] Leakage, point-in-time and quality results are observations, not Python
      acceptance decisions.
- [ ] Java receives citations, measurements and artifact references and owns
      the stage transition.
- [ ] The shared hash fixtures pass in Java and Python.
- [ ] No credential is accepted in request JSON or written to logs.

## 12. Open decisions

- **Durable Python idempotency store:** recommendation: use V18 as the system
  of record and retain only a short-lived response cache in Python.
- **Feature materialisation format:** recommendation: return a versioned
  columnar artifact reference rather than embedding feature rows in HTTP.
- **Adapter query dialect:** recommendation: keep the service dialect-neutral
  and make each adapter's allow-list explicit.
