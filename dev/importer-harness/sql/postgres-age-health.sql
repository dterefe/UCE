SELECT current_database() AS database_name;
SELECT current_user AS user_name;

CREATE EXTENSION IF NOT EXISTS age;
CREATE EXTENSION IF NOT EXISTS vector;

SELECT extname FROM pg_extension WHERE extname IN ('age', 'vector') ORDER BY extname;

LOAD 'age';
SET search_path = ag_catalog, "$user", public;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM ag_catalog.ag_graph
        WHERE name = 'uce_import_harness_health'
    ) THEN
        PERFORM ag_catalog.create_graph('uce_import_harness_health');
    END IF;
END $$;

SELECT *
FROM ag_catalog.cypher(
    'uce_import_harness_health',
    $$
    MERGE (n:HarnessHealth {id: 'service-health'})
    SET n.checked = true
    RETURN n.id
    $$
) AS (id agtype);
