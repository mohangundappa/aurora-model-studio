# Layer 3: ML Execution Service

**Status: TO BUILD.** This Python service is absent. It consumes only a
hash-verified approved feature-set artifact and returns training observations,
recommendations and artifact references. Java remains the authority for
thresholds, sample-size mathematics, statistical tests, promotion criteria and
every state transition.

Realises [Layer 3 execution capabilities](../layer-3-execution-capabilities.md), part of [the implementation specification index](README.md); prerequisite: [human-gate feature-set binding](human-gate-feature-set-binding.md), [client adapters](layer-4-client-adapters.md) and [the data and feature service](layer-3-data-feature-service.md).

## 1. Scope

Build the bounded ML execution service for candidate algorithm runs, tuning,
evaluation and explanation. It must verify the approved feature-set identity
before loading data, train only on that exact version, record every run and
register artifacts through `ClientMlAdapter`. It does not approve a model,
select a production model, alter acceptance or promotion criteria, or advance
an initiative stage.

## 2. Module and package layout

Create this TO BUILD project:

```text
python/ml_service/
  pyproject.toml
  aurora_ml_service/__init__.py
  aurora_ml_service/main.py
  aurora_ml_service/config.py
  aurora_ml_service/models.py
  aurora_ml_service/routes.py
  aurora_ml_service/training.py
  aurora_ml_service/evaluation.py
  aurora_ml_service/explanations.py
  aurora_ml_service/mlflow_store.py
  tests/test_routes.py
  tests/test_training_contract.py
  tests/test_fixture_parity.py
```

Use FastAPI, uvicorn and pydantic v2. Use scikit-learn for preprocessing,
cross-validation and the baseline estimator because its pipeline and metric
interfaces are stable and explicit. Use XGBoost for the primary tree
candidate because it supports the required tabular objective and controlled
hyperparameter search. Use SHAP for feature attribution and MLflow for
run/parameter/metric/artifact tracking. Use the shared `aurora_common`
package; do not duplicate hash code.

Dependencies are declared with exact `==` pins chosen at implementation time
to releases at least seven days old. No version number is invented here.

Required dependency names are `fastapi`, `uvicorn[standard]`, `pydantic`,
`pydantic-settings`, `numpy`, `polars`, `scikit-learn`, `xgboost`, `shap`,
`mlflow`, `httpx`, `pytest` and `pytest-asyncio`. Each uses the constraint
`== <implementation-selected release>` and must satisfy the seven-day release
age rule.

## 3. Types

```python
class AlgorithmRecommendation(BaseModel):
    algorithm_id: Literal["sklearn-baseline", "xgboost-tabular"]
    rationale: str
    hyperparameters: dict[str, float | int | str | bool]

class EvaluationMetric(BaseModel):
    name: str
    value: float
    split: Literal["TRAIN", "VALIDATION", "TEST"]
    citation: str

class ApprovedFeatureSetRef(BaseModel):
    feature_set_id: UUID
    feature_set_version: int
    feature_set_hash: str
    artifact_uri: str
    columns: list[str]

class MlExecutionRequest(BaseModel):
    execution_attempt_id: UUID
    initiative_id: UUID
    stage_attempt_id: UUID
    idempotency_key: str
    content_hash: str
    approved_feature_set: ApprovedFeatureSetRef
    training_data_uri: str
    target_column: str
    task_type: Literal["BINARY_CLASSIFICATION", "REGRESSION"]
    candidate_algorithms: list[AlgorithmRecommendation]
    parameter_budget: int
    evaluation_metrics: list[str]
    register_artifact: bool

class ModelArtifactReference(BaseModel):
    artifact_id: str
    uri: str
    algorithm_id: str
    feature_set_hash: str
    mlflow_run_id: str

class MlRunRecord(BaseModel):
    run_id: UUID
    algorithm_id: str
    feature_set_hash: str
    parameters: dict[str, float | int | str | bool]
    metrics: list[EvaluationMetric]
    mlflow_run_id: str
    artifact_references: list[ModelArtifactReference]
    status: Literal["COMPLETED", "UNKNOWN", "FAILED"]
    started_at: datetime
    completed_at: datetime

class ExplanationObservation(BaseModel):
    feature_name: str
    mean_abs_shap: float
    direction: Literal["POSITIVE", "NEGATIVE", "MIXED", "UNKNOWN"]
    citation: str

class MlExecutionResponse(BaseModel):
    execution_attempt_id: UUID
    idempotency_key: str
    feature_set_hash: str
    status: Literal["COMPLETED", "UNKNOWN", "REFUSED", "FAILED"]
    recommendations: list[AlgorithmRecommendation]
    metrics: list[EvaluationMetric]
    explanations: list[ExplanationObservation]
    artifacts: list[ModelArtifactReference]
    warnings: list[str]
    response_hash: str
    completed_at: datetime
```

`dict` is used only for algorithm hyperparameters whose keys are algorithm
specific; all transport, metric, feature-set and artifact fields are typed.

## 4. Behaviour

The TO BUILD route performs the following:

1. Authenticate service and client scope, validate the idempotency key and
   require a content hash matching the approved feature-set hash.
2. Canonicalise the approved feature-set contract and verify its lowercase
   SHA-256 before opening the training-data URI or calling the ML adapter.
3. Replay an identical completed attempt. Refuse reuse of an idempotency key
   with a different request digest.
4. Ask `ClientMlAdapter.probe()` whether training and artifact registration are
   available. Refuse if the capability report is insufficient.
5. Load the referenced feature artifact and assert that its schema is exactly
   the approved column set and hash. Extra, missing or reordered columns are
   recorded as a contract refusal according to the canonical column order.
6. Build the configured scikit-learn baseline and XGBoost candidates only from
   the approved columns. Enforce the parameter and wall-clock budgets.
7. Track each candidate as an MLflow run, compute the requested metrics and
   generate SHAP observations. Return all metrics and observations, including
   failures; do not label a model accepted or promoted.
8. Register artifacts only when `register_artifact` is true and the adapter
   reports that capability. Store each returned artifact reference with the
   feature-set hash.
9. Canonicalise the response envelope excluding `response_hash`, calculate
   lowercase SHA-256 and populate `response_hash`.
10. Return the response to Java. Java applies acceptance thresholds, statistical
   comparisons and promotion criteria and then owns the workflow transition.

Python may recommend the next algorithm or experiment as a record, but it
cannot choose the next initiative stage.

## 5. Schema

V18 is the Java-side append-only execution ledger. MLflow is the run-tracking
system and stores parameters, metrics, logs and artifact metadata; it is not
the Model Studio workflow record. `ModelArtifactReference` must include the
approved feature-set hash so Java can reject an artifact from another version.

No model is registered as promoted by this service. Registration through a
client adapter means only that an artifact was written to the configured
client platform.

## 6. HTTP contract

Route: `POST /internal/v1/ml/execute`.

Example request:

```json
{
  "execution_attempt_id": "55555555-5555-5555-5555-555555555555",
  "initiative_id": "22222222-2222-2222-2222-222222222222",
  "stage_attempt_id": "33333333-3333-3333-3333-333333333333",
  "idempotency_key": "ml-dispatch-1",
  "content_hash": "1094e9abfb53f8a426d0868532c74f2192c0d07f8c860d3a50a136c0221f96b5",
  "approved_feature_set": {
    "feature_set_id": "44444444-4444-4444-4444-444444444444",
    "feature_set_version": 1,
    "feature_set_hash": "1094e9abfb53f8a426d0868532c74f2192c0d07f8c860d3a50a136c0221f96b5",
    "artifact_uri": "artifact://approved-features/1",
    "columns": ["feature_a", "feature_b"]
  },
  "training_data_uri": "artifact://feature-table/1",
  "target_column": "target",
  "task_type": "BINARY_CLASSIFICATION",
  "candidate_algorithms": [
    {
      "algorithm_id": "sklearn-baseline",
      "rationale": "calibrated baseline",
      "hyperparameters": {}
    }
  ],
  "parameter_budget": 12,
  "evaluation_metrics": ["ROC_AUC", "BrierScore"],
  "register_artifact": true
}
```

| Condition | Status | Body code |
| --- | ---: | --- |
| Runs completed | 200 | `COMPLETED` |
| Evaluation input unavailable or indeterminate | 200 | `UNKNOWN` |
| Missing service authentication | 401 | `SERVICE_UNAUTHENTICATED` |
| Invalid request | 400 | `REQUEST_INVALID` |
| Feature-set hash mismatch | 409 | `FEATURE_SET_HASH_MISMATCH` |
| Feature artifact schema differs | 409 | `FEATURE_SCHEMA_MISMATCH` |
| Idempotency key conflict | 409 | `IDEMPOTENCY_KEY_REUSE` |
| ML adapter unavailable | 424 | `ADAPTER_UNAVAILABLE` |
| Training or parameter budget exceeded | 422 | `TRAINING_BUDGET_EXCEEDED` |
| Adapter refuses registration | 422 | `ARTIFACT_REGISTRATION_REFUSED` |
| Unexpected run failure | 500 | `EXECUTION_FAILED` |

## 7. Configuration

| Environment variable | Type | Default | Validation |
| --- | --- | --- | --- |
| `AURORA_ML_HOST` | `str` | `0.0.0.0` | non-blank |
| `AURORA_ML_PORT` | `int` | `8092` | 1–65535 |
| `AURORA_SERVICE_TOKEN` | `str` | unset | required outside local development; never logged |
| `AURORA_MLFLOW_TRACKING_URI` | `str` | unset | required for non-local runs |
| `AURORA_MLFLOW_EXPERIMENT_PREFIX` | `str` | `aurora-model-studio` | non-blank |
| `AURORA_MAX_PARAMETER_TRIALS` | `int` | `12` | positive and bounded |
| `AURORA_TRAINING_TIMEOUT_SECONDS` | `int` | `300` | 1–3600 |
| `AURORA_ADAPTER_BINDINGS` | `str` | unset | deployment binding reference |

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `ML-HASH-EXACT` | The approved feature-set content hash is verified before data access. |
| `ML-SCHEMA-EXACT` | Training columns equal the approved feature-set columns in declared order. |
| `ML-APPROVED-SET-ONLY` | No unapproved column enters preprocessing, training or evaluation. |
| `ML-IDEMPOTENCY-EXACT` | An identical key and request replay the original response only. |
| `ML-TRIAL-BOUND` | Candidate and tuning runs do not exceed `parameter_budget`. |
| `ML-TIME-BOUND` | Training stops at the configured timeout. |
| `ML-METRIC-CITED` | Each returned metric identifies its split and run citation. |
| `ML-ARTIFACT-HASHED` | Every artifact reference carries the approved feature-set hash. |
| `ML-NO-PROMOTION` | Python emits observations and recommendations; Java evaluates acceptance and promotion. |

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | ---: |
| Hash mismatch | `FEATURE_SET_HASH_MISMATCH` | V18 refusal; no data access | 409 |
| Extra or missing feature column | `FEATURE_SCHEMA_MISMATCH` | V18 refusal | 409 |
| ML adapter missing | `ADAPTER_UNAVAILABLE` | V18 refusal | 424 |
| Training timeout | `TRAINING_TIMEOUT` | V18 failed attempt and MLflow run | 504 |
| Trial budget exhausted | `TRAINING_BUDGET_EXCEEDED` | V18 completed observations with warning | 422 |
| Metric cannot be computed | `UNKNOWN` | V18 response with metric warning | 200 |
| Artifact registration refused | `ARTIFACT_REGISTRATION_REFUSED` | V18 response and adapter refusal | 422 |
| Identical replay | replay | Original V18-linked response | 200 |
| Changed request under same key | `IDEMPOTENCY_KEY_REUSE` | Existing attempt unchanged | 409 |
| Unhandled exception | `EXECUTION_FAILED` | V18 failed attempt | 500 |

## 10. Tests to write

Pytest unit tests:

- `test_training_rejects_feature_set_hash_mismatch_before_adapter_access`.
- `test_training_rejects_extra_feature_column`.
- `test_training_uses_approved_columns_only`.
- `test_candidate_algorithms_are_recommendations_not_acceptance`.
- `test_metrics_include_split_and_mlflow_citation`.
- `test_artifact_reference_contains_feature_set_hash`.
- `test_parameter_budget_stops_tuning`.
- `test_unknown_metric_is_returned_without_python_promotion`.
- `test_hash_fixture_parity_with_java_contract`.

FastAPI tests:

- `test_ml_route_requires_authentication`.
- `test_ml_route_replays_same_idempotency_key`.
- `test_ml_route_returns_409_for_hash_mismatch`.
- `test_ml_route_returns_422_when_registration_is_refused`.

Adapter contract tests:

- `test_ml_adapter_probe_gates_training`.
- `test_ml_adapter_registration_receives_only_verified_hash`.

## 11. Acceptance criteria

- [ ] Training cannot begin until the approved feature-set hash and schema pass.
- [ ] Every candidate run is represented in the response and MLflow.
- [ ] No response uses `accepted`, `promoted` or equivalent Python-owned verdicts.
- [ ] Every artifact reference includes the exact feature-set hash.
- [ ] Java can evaluate all returned metrics and observations without parsing
      prose.
- [ ] Replays are idempotent and failures are recorded in V18.
- [ ] Shared canonicalisation fixtures pass in Java and Python.

## 12. Open decisions

- **MLflow deployment mode:** recommendation: use a centrally configured
  tracking URI and keep artifact URIs immutable.
- **Training-data format:** recommendation: use the columnar artifact emitted
  by the data/feature service, with schema metadata beside the data.
- **Candidate tuning strategy:** recommendation: begin with bounded explicit
  grids and let Java's experiment planner request later runs.
