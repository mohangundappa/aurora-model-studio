#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AURORA_REPO="${AURORA_REPO:-/home/ubuntu/repos/aurora-intelligence}"
JAR="$ROOT/app/target/app-0.1.0-SNAPSHOT.jar"
CLIENT="00000000-0000-0000-0000-000000000001"
API="http://localhost:8081"
CURATED_KEYS=(
  "data-asset:raw_events"
  "implementation:source:signals/src/main/java/com/aurora/signals/SignalEngine.java"
)

if [[ ! -d "$AURORA_REPO" ]]; then
  echo "Aurora repository does not exist: $AURORA_REPO" >&2
  exit 1
fi

cd "$ROOT"
mvn -B -DskipTests package
docker compose down -v
docker compose up --build -d

for attempt in $(seq 1 60); do
  if curl -fsS -H "X-Aurora-Client: $CLIENT" \
      http://localhost:8081/actuator/health >/dev/null; then
    break
  fi
  if [[ "$attempt" == "60" ]]; then
    echo "Model Studio did not become healthy" >&2
    exit 1
  fi
  sleep 2
done

run_cli() {
  timeout 240 java -jar "$JAR" --server.port=0 --spring.main.web-application-type=none "$@"
}

run_cli --import --extract --extract-synthetic --aurora-repo "$AURORA_REPO"
run_cli --seed-curated

curated_keys="$(IFS=,; echo "${CURATED_KEYS[*]}")"

run_cli --approve-curated "$curated_keys"
run_cli --backfill-embeddings --seed-initiatives

api_get() {
  local path="$1"
  if ! curl -fsS -H "X-Aurora-Client: $CLIENT" "$API$path"; then
    echo "Reset assertion failed: API request failed for $path" >&2
    exit 1
  fi
}

initiatives="$(api_get /api/initiatives)"
if [[ "$(jq 'length' <<<"$initiatives")" != "2" ]]; then
  echo "Reset assertion failed: expected exactly two initiatives" >&2
  exit 1
fi

reuse_count="$(
  jq '[.[] | select(((.requirement.constraints.requiredFeatures // []) | index("booking-intent")) != null)] | length' \
    <<<"$initiatives"
)"
if [[ "$reuse_count" != "1" ]]; then
  echo "Reset assertion failed: expected exactly one reuse initiative" >&2
  exit 1
fi

cancellation_count="$(
  jq '[.[] | select(((.requirement.requiredObservables // []) | index("BOOKING_CANCELLED")) != null)] | length' \
    <<<"$initiatives"
)"
if [[ "$cancellation_count" != "1" ]]; then
  echo "Reset assertion failed: expected exactly one cancellation initiative" >&2
  exit 1
fi

reuse="$(
  jq -c '[.[] | select(((.requirement.constraints.requiredFeatures // []) | index("booking-intent")) != null)][0]' \
    <<<"$initiatives"
)"
reuse_feasibility="$(jq -c '[.stages[] | select(.stage == "DATA_FEASIBILITY")][0]' <<<"$reuse")"
if [[ "$(jq -r '.status' <<<"$reuse")" != "ACTIVE" \
    || "$(jq -r '.status' <<<"$reuse_feasibility")" != "AWAITING_APPROVAL" ]]; then
  echo "Reset assertion failed: reuse initiative is not awaiting feasibility approval" >&2
  exit 1
fi
if [[ "$(jq -c '.attempts[-1].blockers' <<<"$reuse_feasibility")" != "[]" ]]; then
  echo "Reset assertion failed: reuse feasibility has blockers" >&2
  exit 1
fi
expected_unknowns='["data-grain:raw_events","data-history:raw_events","data-refresh-cadence:raw_events","point-in-time-reconstruction:raw_events"]'
actual_unknowns="$(
  jq -c '[.attempts[-1].feasibilityChecks[] | select(.status == "UNKNOWN") | .name] | sort' \
    <<<"$reuse_feasibility"
)"
if [[ "$actual_unknowns" != "$expected_unknowns" ]]; then
  echo "Reset assertion failed: reuse feasibility UNKNOWN checks were $actual_unknowns" >&2
  exit 1
fi

cancellation="$(
  jq -c '[.[] | select(((.requirement.requiredObservables // []) | index("BOOKING_CANCELLED")) != null)][0]' \
    <<<"$initiatives"
)"
cancellation_feasibility="$(
  jq -c '[.stages[] | select(.stage == "DATA_FEASIBILITY")][0]' <<<"$cancellation"
)"
if [[ "$(jq -r '.status' <<<"$cancellation")" != "BLOCKED" \
    || "$(jq -r '.status' <<<"$cancellation_feasibility")" != "BLOCKED" ]]; then
  echo "Reset assertion failed: cancellation initiative is not blocked" >&2
  exit 1
fi
if [[ "$(jq -c '.blockers | sort' <<<"$cancellation")" \
    != '["MISSING_TARGET_OBSERVABLE:BOOKING_CANCELLED"]' ]]; then
  echo "Reset assertion failed: cancellation blocker is incorrect" >&2
  exit 1
fi

knowledge="$(api_get '/api/knowledge?type=IMPLEMENTATION&includeCandidates=true')"
open_blocking_conflicts='[]'
while IFS= read -r object; do
  object_id="$(jq -r '.id' <<<"$object")"
  object_key="$(jq -r '.knowledgeKey' <<<"$object")"
  package="$(api_get "/api/knowledge/$object_id?includeCandidates=true")"
  open_blocking_conflicts="$(
    jq -c \
      --arg key "$object_key" \
      --argjson existing "$open_blocking_conflicts" \
      '$existing + [
        .conflicts[]?
        | select(.status == "OPEN" and .conflictClass == "BLOCKING")
        | {
            key: $key,
            field: .field,
            conflictClass: .conflictClass,
            status: .status,
            values: [.values.current.value, .values.other.value] | sort
          }
      ]' <<<"$package"
  )"
done < <(jq -c '.[]' <<<"$knowledge")

expected_conflicts='[{"key":"implementation:legacy/implementations/loyalty-tenure.java","field":"measurementUnit","conflictClass":"BLOCKING","status":"OPEN","values":["months","years"]}]'
if [[ "$(jq -c 'sort_by(.key, .field)' <<<"$open_blocking_conflicts")" \
    != "$expected_conflicts" ]]; then
  echo "Reset assertion failed: open blocking conflict audit was $open_blocking_conflicts" >&2
  exit 1
fi

echo "Model Studio demo reset and seeded successfully."
echo "Reset assertions passed: two initiatives, documented feasibility states, and loyalty-tenure conflict."
