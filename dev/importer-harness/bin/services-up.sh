#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

ENV_FILE="${UCE_IMPORTER_ENV_FILE:-.env.importer}"
COMPOSE_FILE="${UCE_IMPORTER_COMPOSE_FILE:-dev/importer-harness/podman-compose.importer.yaml}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

mkdir -p .dev/importer-harness/postgres
mkdir -p .dev/importer-harness/tdb/biofid-search

export UCE_COMPOSE_ENV_FILE="$ENV_FILE"

podman compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile fuseki up -d uce-postgresql-db uce-fuseki-sparql

echo "Waiting for uce-postgresql-db health..."
for _ in $(seq 1 60); do
  status="$(podman inspect -f '{{.State.Health.Status}}' uce-postgresql-db 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then
    echo "uce-postgresql-db is healthy"
    exit 0
  fi
  sleep 2
done

echo "uce-postgresql-db did not become healthy" >&2
podman logs --tail=80 uce-postgresql-db >&2 || true
exit 1
