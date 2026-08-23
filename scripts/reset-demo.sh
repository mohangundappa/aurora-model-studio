#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AURORA_REPO="${AURORA_REPO:-/home/ubuntu/repos/aurora-intelligence}"
JAR="$ROOT/app/target/app-0.1.0-SNAPSHOT.jar"
CLIENT="00000000-0000-0000-0000-000000000001"

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
  set +e
  timeout 240 java -jar "$JAR" --server.port=0 --spring.main.web-application-type=none "$@"
  local status=$?
  set -e
  if [[ "$status" != "0" && "$status" != "124" ]]; then
    return "$status"
  fi
}

run_cli --import --extract --extract-synthetic --aurora-repo "$AURORA_REPO"
run_cli --seed-curated

curated_keys="$(
  docker compose exec -T postgres psql -U aurora -d aurora_studio -Atc "
    select knowledge_key
    from knowledge_objects
    where client_id = '$CLIENT'
      and lifecycle_status = 'EXTRACTED'
      and (
        knowledge_key in ('data-asset:raw_events')
        or attributes->>'sourceTraceability' like '%SignalEngine.java'
      )
    order by knowledge_key
  " | paste -sd, -
)"
if [[ -z "$curated_keys" ]]; then
  echo "No curated seed artifacts were extracted" >&2
  exit 1
fi

run_cli --approve-curated "$curated_keys"
run_cli --backfill-embeddings --seed-initiatives

echo "Model Studio demo reset and seeded successfully."
