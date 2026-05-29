#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

ENV_FILE="${UCE_IMPORTER_ENV_FILE:-.env.importer}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

JVM_ARGS=("${IMPORTER_JVM_HEAP:--Xmx8g}")
if [[ "${IMPORTER_PROFILE_ENABLED:-false}" == "true" ]]; then
  JVM_ARGS+=("-Duce.profile.enabled=true" "-Duce.profile.sink=${IMPORTER_PROFILE_SINK:-stdout}")
fi
if [[ "${IMPORTER_JFR_ENABLED:-false}" == "true" ]]; then
  JVM_ARGS+=("-XX:StartFlightRecording=filename=${IMPORTER_JFR_FILENAME:-/app/importer.jfr},settings=${IMPORTER_JFR_SETTINGS:-profile},dumponexit=true")
fi

RUN_ARGS=()
if [[ -n "${IMPORTER_RUN_DIR:-}" ]]; then
  mkdir -p "$IMPORTER_RUN_DIR"
  RUN_ARGS+=("-v" "$(realpath "$IMPORTER_RUN_DIR"):/app/run")
fi

IMPORTER_CORPORA_HOST_PATH="${IMPORTER_CORPORA_HOST_PATH:?IMPORTER_CORPORA_HOST_PATH is required}"
IMPORTER_DATABASE_HOST_PATH="${IMPORTER_DATABASE_HOST_PATH:-./database}"
UCE_CONFIG_HOST_PATH="${UCE_CONFIG_HOST_PATH:-./.dev/importer-harness/commonEmpty.conf}"
RUN_ID_SAFE="${IMPORTER_RUN_ID:-manual-$(date +%Y%m%d-%H%M%S)}"

podman run --rm \
  --name "uce-importer-${RUN_ID_SAFE}" \
  --env-file "$ENV_FILE" \
  --network uce_importer_net \
  -e POSTGRESQL_HIBERNATE_CONNECTION_URL="jdbc:postgresql://${DB_HOST:-uce-postgresql-db}:${DB_RUNTIME_PORT:-5432}/${DB_NAME:-uce_import_test}?connectTimeout=${POSTGRESQL_CONNECT_TIMEOUT_SECONDS:-10}&socketTimeout=${POSTGRESQL_SOCKET_TIMEOUT_SECONDS:-60}&tcpKeepAlive=true" \
  -e POSTGRESQL_HIBERNATE_CONNECTION_USERNAME="${DB_USER:-postgres}" \
  -e POSTGRESQL_HIBERNATE_CONNECTION_PASSWORD="${DB_PASSWORD:-postgres}" \
  -v "$(realpath "$IMPORTER_DATABASE_HOST_PATH"):/app/database:ro" \
  -v "$(realpath "$IMPORTER_CORPORA_HOST_PATH"):/app/input:ro" \
  -v "$(realpath "$UCE_CONFIG_HOST_PATH"):${UCE_CONFIG_CONTAINER_PATH:-/app/config/commonEmpty.conf}:ro" \
  "${RUN_ARGS[@]}" \
  uce-importer:local-fast \
  java \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  "${JVM_ARGS[@]}" \
  -cp ./target/importer.jar \
  org.texttechnologylab.uce.corpusimporter.DUUIImporter \
  -src "${IMPORTER_IMPORT_SRC:-/app/input}" \
  -num "${IMPORTER_NUMBER:-1}" \
  -t "${IMPORTER_THREADS:-1}"
