# Layer 4: Client adapters

**Status: TO BUILD.** These Python protocols are swappable pass-throughs to a
client's data and ML estate. They are foundational dependencies read by the
execution services, but none of them decides a requirement, threshold,
promotion outcome or initiative transition. Java remains the authority for
thresholds, sample-size mathematics, statistical tests, promotion criteria and
every state transition.

Realises [Layer 4 foundation](../layer-4-foundation.md), part of [the implementation specification index](README.md); prerequisite: [human-gate feature-set binding](human-gate-feature-set-binding.md).

## 1. Scope

Define `ClientDataAdapter` and `ClientMlAdapter`, capability probing,
configuration-driven per-client binding and hard limits for queries and
artifact operations. Data adapters may execute only bounded read-only queries;
ML adapters may register artifacts only through an explicitly allowed
capability. Missing or misconfigured adapters produce refusal codes rather
than fabricated observations. This does not implement vendor integrations or
store credentials in Model Studio.

## 2. Module and package layout

Create these TO BUILD files:

```text
python/common/
  aurora_common/adapters.py
  aurora_common/limits.py
  tests/test_adapter_contract.py
python/data_feature_service/
  aurora_data_feature_service/adapters/
    __init__.py
    factory.py
    athena.py
    snowflake.py
    databricks.py
    spark.py
python/ml_service/
  aurora_ml_service/adapters/
    __init__.py
    factory.py
    sagemaker.py
    databricks.py
    azure_ml.py
    vertex.py
contracts/client-adapter-capability-report.json
```

Use FastAPI, uvicorn and pydantic v2 in the services. Vendor SDK dependencies
are isolated in adapter projects and pinned at implementation time with exact
`==` pins to releases at least seven days old. Credentials are injected by
deployment identity, workload identity or secret manager; no credential value
belongs in configuration examples or request JSON.

Required package names are `fastapi`, `uvicorn[standard]`, `pydantic`,
`pydantic-settings`, `pytest` and `pytest-asyncio` in the shared/service
projects, plus `boto3`, `snowflake-connector-python`,
`databricks-sql-connector`, `databricks-sdk`, `pyspark`, `azure-ai-ml` and
`google-cloud-aiplatform` in the provider-specific adapter projects. Each
dependency uses `== <implementation-selected release>`; select a release at
least seven days old when the implementation pins it.

## 3. Types

```python
class QueryLimits(BaseModel):
    max_rows: int
    max_bytes: int
    timeout_seconds: int
    allowed_schemas: list[str]
    allowed_operations: list[Literal["SELECT"]]

class CapabilityReport(BaseModel):
    adapter_type: Literal["DATA", "ML"]
    provider: str
    binding_id: str
    available: bool
    capabilities: list[str]
    limits: QueryLimits | None
    refusal_code: str | None
    checked_at: datetime

class QueryRequest(BaseModel):
    sql: str
    limits: QueryLimits
    query_id: UUID

class QueryResult(BaseModel):
    query_id: UUID
    columns: list[str]
    rows: list[dict[str, object]]
    row_count: int
    byte_count: int
    citation: str

class ArtifactRegistrationRequest(BaseModel):
    artifact_uri: str
    artifact_hash: str
    feature_set_hash: str
    metadata: dict[str, str]
    idempotency_key: str

class ArtifactRegistration(BaseModel):
    registered: bool
    artifact_id: str | None
    uri: str | None
    refusal_code: str | None
    citation: str | None

class ClientDataAdapter(Protocol):
    def probe(self) -> CapabilityReport: ...
    def execute_read_only(self, request: QueryRequest) -> QueryResult: ...

class ClientMlAdapter(Protocol):
    def probe(self) -> CapabilityReport: ...
    def register_artifact(
        self, request: ArtifactRegistrationRequest
    ) -> ArtifactRegistration: ...
```

## 4. Behaviour

Factory resolution uses `(client_id, adapter_type, binding_id)` from V19:

1. Load the binding and verify the client scope.
2. Resolve the named provider implementation and deployment credential
   reference. Never accept credentials from the caller.
3. Call `probe()` and cache only the bounded capability report for its stated
   lifetime. A failed probe is a refusal, not an empty successful report.
4. For `execute_read_only`, intersect request limits with the stricter binding
   limits and reject if the request asks for more. Parse and allow-list the
   statement before execution, requiring one read-only operation.
5. Apply server-side row, byte and timeout limits while streaming. Cancel the
   remote query when a cap is crossed and return a stable refusal.
6. For `register_artifact`, verify the content and feature-set hashes are
   present, enforce the idempotency key and require the ML capability.
7. Return citations naming the binding and remote operation. No adapter may
   invent a row, quality measurement or artifact identifier.

The adapter is the client's estate boundary; knowledge objects and governed
requirements remain in Model Studio and compound there, not in the adapter.

## 5. Schema

`V19__client_adapter_bindings.sql`:

```sql
create table client_adapter_bindings (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  version integer not null check (version > 0),
  supersedes_id uuid,
  adapter_type varchar(20) not null check (adapter_type in ('DATA', 'ML')),
  provider varchar(40) not null,
  binding_name varchar(160) not null,
  credential_reference varchar(240) not null,
  endpoint_reference varchar(500),
  enabled boolean not null default true,
  max_rows integer not null check (max_rows > 0),
  max_bytes bigint not null check (max_bytes > 0),
  timeout_seconds integer not null check (timeout_seconds > 0),
  allowed_schemas jsonb not null default '[]'::jsonb,
  capabilities jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  unique (client_id, adapter_type, binding_name, version),
  foreign key (client_id, supersedes_id)
    references client_adapter_bindings(client_id, id)
);

create index client_adapter_bindings_lookup_idx
  on client_adapter_bindings(client_id, adapter_type, binding_name, version desc);

create or replace function reject_client_adapter_binding_mutation()
returns trigger language plpgsql as $$
begin
  raise exception 'client adapter bindings are append-only';
end;
$$;

create trigger client_adapter_bindings_append_only
before update or delete on client_adapter_bindings
for each row execute function reject_client_adapter_binding_mutation();
```

The current binding is selected by the highest version for the client,
adapter type and binding name:

```sql
select distinct on (client_id, adapter_type, binding_name) *
from client_adapter_bindings
where client_id = :client_id
  and adapter_type = :adapter_type
  and binding_name = :binding_name
order by client_id, adapter_type, binding_name, version desc;
```

An implementation may use the equivalent scoped lookup below when resolving
one binding:

```sql
select *
from client_adapter_bindings
where client_id = :client_id
  and adapter_type = :adapter_type
  and binding_name = :binding_name
order by version desc
limit 1;
```

Disabling, repointing or changing limits inserts a new version with
`supersedes_id` set to the prior row and, for a disablement, `enabled = false`.
On insert, the binding repository verifies that the superseded row has the
same client, adapter type and binding name and that the new version is exactly
one greater than the superseded version. No binding row is updated or deleted.
V19 is intentionally versioned
append-only because bindings are mutable configuration history; V17 approved
feature sets and V18 execution attempts are immutable approval/audit records
whose original values must never be replaced.

## 6. HTTP contract

Adapters are in-process Python protocols, not public HTTP endpoints. The
execution services expose the authenticated routes specified in their
documents. Their adapter errors map to:

| Condition | Service status | Code |
| --- | ---: | --- |
| No enabled binding | 424 | `ADAPTER_NOT_CONFIGURED` |
| Probe cannot authenticate | 424 | `ADAPTER_AUTHENTICATION_FAILED` |
| Capability absent | 424 | `ADAPTER_CAPABILITY_REFUSED` |
| Non-SELECT operation | 422 | `QUERY_NOT_READ_ONLY` |
| Schema not allow-listed | 422 | `SCHEMA_NOT_ALLOWED` |
| Row cap crossed | 422 | `ROW_LIMIT_EXCEEDED` |
| Byte cap crossed | 422 | `BYTE_LIMIT_EXCEEDED` |
| Timeout reached | 504 | `ADAPTER_TIMEOUT` |
| Artifact registration not allowed | 422 | `ARTIFACT_REGISTRATION_REFUSED` |
| Remote provider failure | 502 | `ADAPTER_PROVIDER_FAILED` |

## 7. Configuration

Deployment configuration is environment-driven:

| Environment variable | Type | Default | Validation |
| --- | --- | --- | --- |
| `AURORA_ADAPTER_BINDINGS` | `str` | unset | required for client-data access |
| `AURORA_ADAPTER_PROBE_TTL_SECONDS` | `int` | `60` | positive and bounded |
| `AURORA_ADAPTER_MAX_ROWS` | `int` | `100000` | positive |
| `AURORA_ADAPTER_MAX_BYTES` | `int` | `104857600` | positive |
| `AURORA_ADAPTER_TIMEOUT_SECONDS` | `int` | `30` | positive and bounded |
| `AURORA_AWS_REGION` | `str` | unset | required by Athena/SageMaker when selected |
| `AURORA_SNOWFLAKE_ACCOUNT_REFERENCE` | `str` | unset | reference only |
| `AURORA_DATABRICKS_WORKSPACE_REFERENCE` | `str` | unset | reference only |
| `AURORA_AZURE_SUBSCRIPTION_REFERENCE` | `str` | unset | reference only |
| `AURORA_GCP_PROJECT_REFERENCE` | `str` | unset | reference only |

The deployment supplies AWS IAM role/workload identity for Athena and
SageMaker, Snowflake key-pair or workload authentication for Snowflake,
Databricks OAuth/workload identity for its SQL and ML endpoints, Azure
managed identity for Azure ML, and GCP service-account/workload identity for
Vertex. The implementation must use the provider's SDK and identity mechanism
without documenting a credential value.

Provider notes:

| Estate | SDK and auth mechanism |
| --- | --- |
| Athena | `boto3` Athena client; AWS IAM role or workload identity |
| Snowflake | `snowflake-connector-python`; key-pair or workload authentication |
| Databricks data | `databricks-sql-connector`; OAuth or workload identity |
| Spark | `pyspark`; deployment-managed cluster identity |
| SageMaker | `boto3` SageMaker client; AWS IAM role or workload identity |
| Databricks ML | Databricks SDK; OAuth or workload identity |
| Azure ML | `azure-ai-ml`; Azure managed identity |
| Vertex | `google-cloud-aiplatform`; GCP workload identity |

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `ADAPTER-BINDING-CLIENT` | A binding is resolved only for the current client. |
| `ADAPTER-PROBE-REQUIRED` | No query or registration occurs before a successful capability probe. |
| `ADAPTER-READ-ONLY` | Data execution accepts exactly one allow-listed read operation. |
| `ADAPTER-SCHEMA-ALLOWLIST` | Every referenced schema is in the binding allow-list. |
| `ADAPTER-ROW-CAP` | No response contains more than the effective row cap. |
| `ADAPTER-BYTE-CAP` | No response exceeds the effective byte cap. |
| `ADAPTER-TIME-CAP` | Remote execution is cancelled at the effective timeout. |
| `ADAPTER-VERSION-CHAIN` | A superseding row has the same scope and exactly the next version. |
| `ADAPTER-ARTIFACT-HASH` | Registration requires content and approved feature-set hashes. |
| `ADAPTER-REFUSE-NOT-FABRICATE` | Missing capability or provider failure produces refusal, never synthetic output. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | ---: |
| Missing binding | `ADAPTER_NOT_CONFIGURED` | V18 refusal | 424 |
| Wrong client binding | `ADAPTER_BINDING_CLIENT_MISMATCH` | V18 refusal | 403 |
| Probe authentication fails | `ADAPTER_AUTHENTICATION_FAILED` | V18 refusal | 424 |
| Query requests write operation | `QUERY_NOT_READ_ONLY` | V18 refusal | 422 |
| Query exceeds cap | `ROW_LIMIT_EXCEEDED` or `BYTE_LIMIT_EXCEEDED` | V18 failed attempt | 422 |
| Query times out | `ADAPTER_TIMEOUT` | V18 failed attempt | 504 |
| Artifact hash absent | `ARTIFACT_HASH_REQUIRED` | V18 refusal | 422 |
| Provider unavailable | `ADAPTER_PROVIDER_FAILED` | V18 failed attempt | 502 |
| Unsupported capability | `ADAPTER_CAPABILITY_REFUSED` | V18 refusal | 424 |

## 10. Tests to write

Pytest protocol tests:

- `test_probe_reports_capabilities_and_effective_limits`.
- `test_query_limits_are_stricter_than_request_limits`.
- `test_non_select_query_is_refused`.
- `test_schema_outside_allowlist_is_refused`.
- `test_row_byte_and_timeout_caps_cancel_execution`.
- `test_missing_binding_returns_refusal_not_empty_result`.
- `test_artifact_registration_requires_both_hashes`.
- `test_cross_client_binding_is_unreachable`.

Provider contract tests using mocked SDK clients:

- `test_athena_uses_read_only_workgroup_and_iam_identity`.
- `test_snowflake_applies_statement_timeout_and_role_scope`.
- `test_databricks_sql_uses_server_side_limits`.
- `test_spark_adapter_requires_deployment_cluster_identity`.
- `test_sagemaker_registration_preserves_feature_set_hash`.
- `test_databricks_ml_registration_is_idempotent`.
- `test_azure_ml_uses_managed_identity`.
- `test_vertex_registration_uses_workload_identity`.

Testcontainers/repository tests on Java V19:

- `ClientAdapterBindingIsTenantScoped`.
- `ClientAdapterBindingAppendOnlyTriggerRejectsUpdateAndDelete`.
- `DuplicateClientAdapterBindingNameIsRejected`.
- `DisabledBindingCannotBeResolved`.
- `SupersedingAdapterBindingWinsCurrentSelectionAndUpdateIsRejected`.

## 11. Acceptance criteria

- [ ] Both protocols have the complete typed signatures shown above.
- [ ] Every adapter has a probe and enforced effective limits.
- [ ] Data adapters cannot perform writes or return unbounded results.
- [ ] ML registration is hash-bound and idempotent.
- [ ] Provider failures and missing bindings are stable refusals.
- [ ] V19 has composite client keys, indexes and append-only protection.
- [ ] Credentials are deployment references only and never request fields.
- [ ] The adapters make no governance or workflow decision.

## 12. Open decisions

- **Binding rotation:** recommendation: insert immutable bindings with an
  explicit active version rather than mutating an existing row.
- **SQL parser:** recommendation: use a dialect-aware parser per adapter,
  followed by a common read-only AST policy.
- **Spark deployment mode:** recommendation: support only an authenticated
  managed cluster boundary in the first implementation.
