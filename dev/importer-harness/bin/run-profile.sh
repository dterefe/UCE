#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

RUN_ID="${1:?usage: run-profile.sh <run-id> <input-path> <threads> <db-pool> [reset|reuse]}"
INPUT_PATH="${2:?usage: run-profile.sh <run-id> <input-path> <threads> <db-pool> [reset|reuse]}"
THREADS="${3:?usage: run-profile.sh <run-id> <input-path> <threads> <db-pool> [reset|reuse]}"
DB_POOL="${4:?usage: run-profile.sh <run-id> <input-path> <threads> <db-pool> [reset|reuse]}"
RESET_MODE="${5:-reset}"

BASE_ENV="${UCE_IMPORTER_BASE_ENV:-.env.importer}"
RUN_DIR="$ROOT/.dev/importer-harness/runs/$RUN_ID"
RUN_ENV="$RUN_DIR/env.importer"
mkdir -p "$RUN_DIR/metrics" "$RUN_DIR/jfr" "$RUN_DIR/queries"

cp "$BASE_ENV" "$RUN_ENV"
cat >> "$RUN_ENV" <<EOF

# Generated for run $RUN_ID
IMPORTER_CORPORA_HOST_PATH=$INPUT_PATH
IMPORTER_THREADS=$THREADS
POSTGRESQL_POOL_MAXIMUM_SIZE=$DB_POOL
IMPORTER_PROFILE_ENABLED=true
IMPORTER_PROFILE_SINK=/app/run/metrics/uce-profile.jsonl
IMPORTER_JFR_ENABLED=true
IMPORTER_JFR_FILENAME=/app/run/jfr/importer.jfr
IMPORTER_RUN_ID=$RUN_ID
EOF

cp "$RUN_ENV" "$RUN_DIR/env.snapshot"

if [[ "$RESET_MODE" == "reset" ]]; then
  podman rm -f uce-postgresql-db uce-fuseki-sparql >/dev/null 2>&1 || true
  podman volume rm -f uce_importer_postgres_data uce_importer_tdb_data >/dev/null 2>&1 || true
fi

export UCE_IMPORTER_ENV_FILE="${RUN_ENV#"$ROOT"/}"
export IMPORTER_RUN_DIR="$RUN_DIR"

{
  echo "run_id=$RUN_ID"
  echo "input_path=$INPUT_PATH"
  echo "threads=$THREADS"
  echo "db_pool=$DB_POOL"
  echo "reset_mode=$RESET_MODE"
  dev/importer-harness/bin/services-up.sh
  dev/importer-harness/bin/service-health.sh
} 2>&1 | tee "$RUN_DIR/service-health.log"

start_epoch="$(date +%s)"
set +e
dev/importer-harness/bin/run-importer.sh 2>&1 | tee "$RUN_DIR/importer.log"
status="${PIPESTATUS[0]}"
set -e
end_epoch="$(date +%s)"

cat > "$RUN_DIR/run-summary.json" <<EOF
{
  "run_id": "$RUN_ID",
  "input_path": "$INPUT_PATH",
  "threads": $THREADS,
  "db_pool": $DB_POOL,
  "reset_mode": "$RESET_MODE",
  "start_epoch": $start_epoch,
  "end_epoch": $end_epoch,
  "duration_seconds": $((end_epoch - start_epoch)),
  "exit_code": $status
}
EOF

dev/importer-harness/bin/collect-run-metrics.sh "$RUN_DIR" "$RUN_ENV" 2>&1 | tee "$RUN_DIR/collect.log" || true
dev/importer-harness/bin/service-health.sh > "$RUN_DIR/post-import-service-health.log" 2>&1 || true

exit "$status"
