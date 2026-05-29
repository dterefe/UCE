#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

RUN_DIR="${1:?usage: collect-run-metrics.sh <run-dir>}"
ENV_FILE="${2:-${UCE_IMPORTER_ENV_FILE:-.env.importer}}"
mkdir -p "$RUN_DIR/queries"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

PSQL=(podman exec -i uce-postgresql-db psql -U "${DB_USER:-postgres}" -d "${DB_NAME:-uce_import_test}" -v ON_ERROR_STOP=1 --csv)

run_csv() {
  local name="$1"
  local sql="$2"
  printf '%s\n' "$sql" | "${PSQL[@]}" > "$RUN_DIR/queries/$name.csv"
}

run_csv table_counts "
CREATE OR REPLACE FUNCTION pg_temp.count_table_if_exists(table_identifier text)
RETURNS bigint
LANGUAGE plpgsql
AS \$\$
DECLARE
  row_count bigint;
BEGIN
  IF to_regclass(table_identifier) IS NULL THEN
    RETURN NULL;
  END IF;
  EXECUTE 'SELECT count(*) FROM ' || table_identifier INTO row_count;
  RETURN row_count;
END;
\$\$;

SELECT table_name, row_count
FROM (
  SELECT 'corpus' AS table_name, pg_temp.count_table_if_exists('corpus') AS row_count
  UNION ALL SELECT 'document', pg_temp.count_table_if_exists('document')
  UNION ALL SELECT 'page', pg_temp.count_table_if_exists('page')
  UNION ALL SELECT 'sentence', pg_temp.count_table_if_exists('sentence')
  UNION ALL SELECT 'namedEntity', pg_temp.count_table_if_exists('\"namedEntity\"')
  UNION ALL SELECT 'geoname', pg_temp.count_table_if_exists('geoname')
  UNION ALL SELECT 'biofidtaxon', pg_temp.count_table_if_exists('biofidtaxon')
  UNION ALL SELECT 'importlog', pg_temp.count_table_if_exists('importlog')
) counts
ORDER BY table_name;
"

run_csv postgresql_activity "
SELECT state, count(*) AS connections
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY state
ORDER BY state;
"

run_csv age_domain_counts "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH (n:FeatureStructure)
  RETURN n.uimaType, count(n)
  ORDER BY n.uimaType
\$\$) AS (uima_type agtype, count agtype);
"

run_csv age_association_counts "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH ()-[r:Association]->()
  RETURN r.uimaType, count(r)
  ORDER BY r.uimaType
\$\$) AS (uima_type agtype, count agtype);
"

run_csv age_association_name_counts "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH ()-[r:Association]->()
  RETURN r.name, r.uimaType, count(r)
  ORDER BY r.name, r.uimaType
\$\$) AS (name agtype, uima_type agtype, count agtype);
"

run_csv age_stable_association_counts "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH ()-[r:Association]->()
  WHERE NOT r.name =~ '^(import|operation)-.*'
  RETURN r.name, r.uimaType, count(r)
  ORDER BY r.name, r.uimaType
\$\$) AS (name agtype, uima_type agtype, count agtype);
"

run_csv age_duplicate_domains "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH (n:FeatureStructure)
  WITH n.uid AS uid, count(n) AS c
  WHERE c > 1
  RETURN uid, c
  ORDER BY c DESC, uid
  LIMIT 100
\$\$) AS (uid agtype, count agtype);
"

run_csv age_bare_page_ids "
LOAD 'age';
SET search_path = ag_catalog, \"\$user\", public;
SELECT *
FROM cypher('uce_domain_graph', \$\$
  MATCH (n:FeatureStructure)
  WHERE n.uimaType =~ '.*Page.*' AND n.uid =~ '^[0-9]+$'
  RETURN n.uid, n.uimaType
  LIMIT 100
\$\$) AS (uid agtype, uima_type agtype);
"

printf 'Collected query metrics in %s/queries\n' "$RUN_DIR"
