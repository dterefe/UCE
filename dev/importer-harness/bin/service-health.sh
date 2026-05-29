#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

ENV_FILE="${UCE_IMPORTER_ENV_FILE:-.env.importer}"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

DB_NAME="${DB_NAME:-uce_import_test}"
DB_USER="${DB_USER:-postgres}"

podman exec -i uce-postgresql-db psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" < dev/importer-harness/sql/postgres-age-health.sql

echo "PostgreSQL/AGE health check passed"

FUSEKI_PORT="${FUSEKI_PORT:-8030}"
TDB2_ENDPOINT="${TDB2_ENDPOINT:-biofid-search}"
FUSEKI_URL="http://127.0.0.1:${FUSEKI_PORT}/${TDB2_ENDPOINT}/sparql"

for _ in $(seq 1 60); do
  if curl -fsS --get "$FUSEKI_URL" --data-urlencode "query=ASK { }" >/tmp/uce-fuseki-health.out; then
    echo "Fuseki/SPARQL health check passed"
    exit 0
  fi
  sleep 2
done

echo "Fuseki/SPARQL health check failed at $FUSEKI_URL" >&2
podman logs --tail=80 uce-fuseki-sparql >&2 || true
exit 1
