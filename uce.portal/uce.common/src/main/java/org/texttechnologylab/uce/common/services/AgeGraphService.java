package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.metrics.UCEProfileRecorder;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgeGraphService implements DomainGraphService {
    public static final String GRAPH_NAME = "uce_domain_graph";
    public static final String NODE_LABEL = "FeatureStructure";
    public static final String EDGE_LABEL = "Association";
    private static final int DEFAULT_DOMAIN_BATCH_SIZE = 20_000;
    private static final int DEFAULT_ASSOCIATION_BATCH_SIZE = 20_000;
    private static final String DOMAIN_BATCH_SIZE_PROPERTY = "uce.age.domain.batch.size";
    private static final String ASSOCIATION_BATCH_SIZE_PROPERTY = "uce.age.association.batch.size";
    private static final String DOMAIN_BATCH_SIZE_ENV = "UCE_AGE_DOMAIN_BATCH_SIZE";
    private static final String ASSOCIATION_BATCH_SIZE_ENV = "UCE_AGE_ASSOCIATION_BATCH_SIZE";

    private final PostgresqlDataInterface_Impl db;
    private final Object graphInitializationLock = new Object();
    private volatile boolean graphInitialized;
    private volatile boolean graphUnavailable;

    public AgeGraphService(PostgresqlDataInterface_Impl db) {
        this.db = db;
    }

    public void ensureGraph() throws DatabaseOperationException, DocumentAccessDeniedException {
        if (graphInitialized) {
            return;
        }
        if (graphUnavailable) {
            throw new DatabaseOperationException("Apache AGE graph support is unavailable in the configured PostgreSQL database.");
        }
        synchronized (graphInitializationLock) {
            if (graphInitialized) {
                return;
            }
            if (graphUnavailable) {
                throw new DatabaseOperationException("Apache AGE graph support is unavailable in the configured PostgreSQL database.");
            }
            initializeGraph();
            graphInitialized = true;
        }
    }

    private boolean ensureGraphForRead() throws DatabaseOperationException, DocumentAccessDeniedException {
        try {
            ensureGraph();
            return true;
        } catch (DatabaseOperationException ex) {
            if (isAgeUnavailable(ex)) {
                graphUnavailable = true;
                return false;
            }
            throw ex;
        }
    }

    private static boolean isAgeUnavailable(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("could not access file \"age\"")
                    || message.contains("schema \"ag_catalog\" does not exist")
                    || message.contains("type \"ag_catalog.agtype\" does not exist")
                    || message.contains("relation \"ag_catalog.ag_graph\" does not exist"))) {
                return true;
            }
        }
        return false;
    }

    private void initializeGraph() throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.graph.ensure", GRAPH_NAME, "service_execution")) {
            db.executeNativeStatements(List.of(
                    "LOAD 'age'",
                    "SET search_path = ag_catalog, \"$user\", public",
                    """
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM ag_catalog.ag_graph
                            WHERE name = 'uce_domain_graph'
                        ) THEN
                            PERFORM ag_catalog.create_graph('uce_domain_graph');
                        END IF;
                    END
                    $$;
                    """,
                    """
                    DO $$
                    DECLARE
                        graph_oid oid;
                    BEGIN
                        SELECT graphid INTO graph_oid
                        FROM ag_catalog.ag_graph
                        WHERE name = 'uce_domain_graph';

                        IF NOT EXISTS (
                            SELECT 1
                            FROM ag_catalog.ag_label
                            WHERE graph = graph_oid
                              AND name = 'FeatureStructure'
                        ) THEN
                            PERFORM ag_catalog.create_vlabel('uce_domain_graph', 'FeatureStructure');
                        END IF;

                        IF NOT EXISTS (
                            SELECT 1
                            FROM ag_catalog.ag_label
                            WHERE graph = graph_oid
                              AND name = 'Association'
                        ) THEN
                            PERFORM ag_catalog.create_elabel('uce_domain_graph', 'Association');
                        END IF;
                    END
                    $$;
                    """,
                    """
                    DROP INDEX IF EXISTS uce_domain_graph."FeatureStructure_uid_idx";
                    """,
                    """
                    DROP INDEX IF EXISTS uce_domain_graph."FeatureStructure_uid_text_idx";
                    """,
                    """
                    DROP INDEX IF EXISTS uce_domain_graph."Association_uid_idx";
                    """,
                    """
                    DROP INDEX IF EXISTS uce_domain_graph."Association_uid_text_idx";
                    """,
                    """
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'public'
                              AND table_name = 'uce_age_domain_node_registry'
                              AND column_name = 'age_id'
                              AND data_type <> 'text'
                        ) THEN
                            DROP TABLE public.uce_age_domain_node_registry;
                        END IF;

                        IF EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'public'
                              AND table_name = 'uce_age_association_edge_registry'
                              AND column_name = 'age_id'
                              AND data_type <> 'text'
                        ) THEN
                            DROP TABLE public.uce_age_association_edge_registry;
                        END IF;
                    END
                    $$;
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS public.uce_age_domain_node_registry (
                        uid text PRIMARY KEY,
                        age_id text NOT NULL
                    );
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS public.uce_age_association_edge_registry (
                        uid text PRIMARY KEY,
                        age_id text NOT NULL
                    );
                    """,
                    """
                    INSERT INTO public.uce_age_domain_node_registry(uid, age_id)
                    SELECT trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text), id::text
                      FROM uce_domain_graph."FeatureStructure"
                     WHERE ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype) IS NOT NULL
                    ON CONFLICT (uid) DO NOTHING;
                    """,
                    """
                    INSERT INTO public.uce_age_association_edge_registry(uid, age_id)
                    SELECT trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text), id::text
                      FROM uce_domain_graph."Association"
                     WHERE ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype) IS NOT NULL
                    ON CONFLICT (uid) DO NOTHING;
                    """
            ));
        }
    }

    public void upsertDomainNode(DomainNode node) throws DatabaseOperationException, DocumentAccessDeniedException {
        upsertDomainNodes(List.of(node));
    }

    public void upsertDomainNodes(List<DomainNode> nodes) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<DomainNode> uniqueNodes = deduplicateDomains(nodes);
        try (var ignored = UCEProfileRecorder.scope("age.domain.upsert", batchDomain(nodes, uniqueNodes), "service_execution")) {
            int batchSize = configuredBatchSize(DOMAIN_BATCH_SIZE_PROPERTY, DOMAIN_BATCH_SIZE_ENV, DEFAULT_DOMAIN_BATCH_SIZE);
            for (List<DomainNode> batch : partition(uniqueNodes, batchSize)) {
                executeStagedDomainBatch(batch);
            }
        }
    }

    public void upsertAssociationEdge(AssociationEdge edge) throws DatabaseOperationException, DocumentAccessDeniedException {
        upsertAssociationEdges(List.of(edge));
    }

    public void upsertAssociationEdges(List<AssociationEdge> edges) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        List<AssociationEdge> uniqueEdges = deduplicateAssociations(edges);
        try (var ignored = UCEProfileRecorder.scope("age.association.upsert", batchDomain(edges, uniqueEdges), "service_execution")) {
            int batchSize = configuredBatchSize(ASSOCIATION_BATCH_SIZE_PROPERTY, ASSOCIATION_BATCH_SIZE_ENV, DEFAULT_ASSOCIATION_BATCH_SIZE);
            for (List<AssociationEdge> batch : partition(uniqueEdges, batchSize)) {
                executeStagedAssociationBatch(batch);
            }
        }
    }

    public List<DomainTypeSummary> listDomainTypes(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (!ensureGraphForRead()) {
            return List.of();
        }
        Map<String, MutableDomainTypeSummary> types = new LinkedHashMap<>();
        query("""
                SELECT %s AS uima_type,
                       count(*) AS count
                  FROM %s."%s" node
                 WHERE %s
                 GROUP BY uima_type
                 ORDER BY uima_type;
                """.formatted(
                        property("node", "uimaType"),
                        GRAPH_NAME,
                        NODE_LABEL,
                        corpusPredicate("node", corpusId)
                ), resultSet -> {
            while (resultSet.next()) {
                String uimaType = nullIfBlank(resultSet.getString("uima_type"));
                if (uimaType == null) {
                    continue;
                }
                types.put(uimaType, new MutableDomainTypeSummary(uimaType, resultSet.getLong("count")));
            }
        });

        query("""
                SELECT %s AS source_type,
                       %s AS association_type,
                       count(*) AS count
                  FROM %s."%s" edge
                  JOIN %s."%s" source
                    ON source.id = edge.start_id
                 WHERE %s
                 GROUP BY source_type, association_type
                 ORDER BY source_type, association_type;
                """.formatted(
                        property("source", "uimaType"),
                        property("edge", "uimaType"),
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        NODE_LABEL,
                        corpusPredicate("edge", corpusId)
                ), resultSet -> {
            while (resultSet.next()) {
                String sourceType = nullIfBlank(resultSet.getString("source_type"));
                String associationType = simpleTypeName(nullIfBlank(resultSet.getString("association_type")));
                if (sourceType == null || associationType == null) {
                    continue;
                }
                MutableDomainTypeSummary summary = types.computeIfAbsent(sourceType, type -> new MutableDomainTypeSummary(type, 0));
                summary.associationCounts.put(associationType, resultSet.getLong("count"));
            }
        });

        return types.values().stream()
                .map(summary -> new DomainTypeSummary(summary.uimaType, simpleTypeName(summary.uimaType), summary.count, Map.copyOf(summary.associationCounts)))
                .toList();
    }

    public DomainNodePage listDomainNodes(long corpusId,
                                          String uimaType,
                                          String queryText,
                                          int skip,
                                          int take) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (!ensureGraphForRead()) {
            return new DomainNodePage(List.of(), 0, Math.max(0, skip), Math.max(1, Math.min(take, 250)));
        }
        int safeSkip = Math.max(0, skip);
        int safeTake = Math.max(1, Math.min(take, 250));
        String where = domainNodeWhere(corpusId, uimaType, queryText);
        long total = queryLong("""
                SELECT count(*)
                  FROM %s."%s" node
                 WHERE %s;
                """.formatted(GRAPH_NAME, NODE_LABEL, where));
        List<DomainNodeView> nodes = new ArrayList<>();
        query("""
                SELECT node.id::text AS age_id,
                       %s AS uid,
                       %s AS uima_type,
                       %s AS corpus_id,
                       %s AS document_id,
                       %s AS name,
                       %s AS uri,
                       %s AS metadata,
                       %s AS features,
                       (SELECT count(*) FROM %s."%s" edge WHERE edge.start_id = node.id) AS outgoing_count,
                       (SELECT count(*) FROM %s."%s" edge WHERE edge.end_id = node.id) AS incoming_count
                  FROM %s."%s" node
                 WHERE %s
                 ORDER BY coalesce(nullif(%s, ''), %s)
                 LIMIT %d OFFSET %d;
                """.formatted(
                        property("node", "uid"),
                        property("node", "uimaType"),
                        property("node", "corpusId"),
                        property("node", "documentId"),
                        property("node", "name"),
                        property("node", "uri"),
                        property("node", "metadata"),
                        property("node", "features"),
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        NODE_LABEL,
                        where,
                        property("node", "name"),
                        property("node", "uid"),
                        safeTake,
                        safeSkip
                ), resultSet -> {
            while (resultSet.next()) {
                nodes.add(domainNodeView(resultSet));
            }
        });
        return new DomainNodePage(nodes, total, safeSkip, safeTake);
    }

    public AssociationPage listAssociatedNodes(long corpusId,
                                               List<String> sourceUids,
                                               String associationType,
                                               String direction,
                                               String targetType,
                                               int skip,
                                               int take) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (!ensureGraphForRead()) {
            return new AssociationPage(List.of(), 0, Math.max(0, skip), Math.max(1, take));
        }
        if (sourceUids == null || sourceUids.isEmpty()) {
            return new AssociationPage(List.of(), 0, Math.max(0, skip), Math.max(1, take));
        }
        int safeSkip = Math.max(0, skip);
        int safeTake = Math.max(1, Math.min(take, 250));
        String where = associationWhere(corpusId, sourceUids, associationType, direction, targetType);
        String fromJoin = associationFromJoin(direction);
        long total = queryLong("""
                SELECT count(*)
                  FROM %s
                 WHERE %s;
                """.formatted(fromJoin, where));
        List<AssociationTraversal> traversals = new ArrayList<>();
        query("""
                SELECT edge.id::text AS edge_age_id,
                       %s AS edge_uid,
                       %s AS association_type,
                       %s AS edge_name,
                       %s AS edge_metadata,
                       %s AS edge_features,
                       %s AS direction,
                       target.id::text AS age_id,
                       %s AS uid,
                       %s AS uima_type,
                       %s AS corpus_id,
                       %s AS document_id,
                       %s AS name,
                       %s AS uri,
                       %s AS metadata,
                       %s AS features,
                       (SELECT count(*) FROM %s."%s" e2 WHERE e2.start_id = target.id) AS outgoing_count,
                       (SELECT count(*) FROM %s."%s" e3 WHERE e3.end_id = target.id) AS incoming_count
                  FROM %s
                 WHERE %s
                 ORDER BY coalesce(nullif(%s, ''), %s)
                 LIMIT %d OFFSET %d;
                """.formatted(
                        property("edge", "uid"),
                        property("edge", "uimaType"),
                        property("edge", "name"),
                        property("edge", "metadata"),
                        property("edge", "features"),
                        associationDirectionSelect(direction),
                        property("target", "uid"),
                        property("target", "uimaType"),
                        property("target", "corpusId"),
                        property("target", "documentId"),
                        property("target", "name"),
                        property("target", "uri"),
                        property("target", "metadata"),
                        property("target", "features"),
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        EDGE_LABEL,
                        fromJoin,
                        where,
                        property("target", "name"),
                        property("target", "uid"),
                        safeTake,
                        safeSkip
                ), resultSet -> {
            while (resultSet.next()) {
                DomainNodeView target = domainNodeView(resultSet);
                AssociationEdgeView edge = new AssociationEdgeView(
                        resultSet.getString("edge_age_id"),
                        nullIfBlank(resultSet.getString("edge_uid")),
                        nullIfBlank(resultSet.getString("association_type")),
                        simpleTypeName(nullIfBlank(resultSet.getString("association_type"))),
                        nullIfBlank(resultSet.getString("edge_name")),
                        nullIfBlank(resultSet.getString("edge_metadata")),
                        nullIfBlank(resultSet.getString("edge_features")),
                        nullIfBlank(resultSet.getString("direction"))
                );
                traversals.add(new AssociationTraversal(edge, target));
            }
        });
        return new AssociationPage(traversals, total, safeSkip, safeTake);
    }

    public ScopePreview previewScope(long corpusId, List<String> selectedUids) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (!ensureGraphForRead()) {
            return new ScopePreview(0, 0, 0, Map.of(), List.of("Documents", "Pages", "Annotations", "Timeline", "Network", "Table", "Reader"));
        }
        String where = selectedUids == null || selectedUids.isEmpty()
                ? corpusPredicate("node", corpusId)
                : corpusPredicate("node", corpusId) + " AND " + property("node", "uid") + " IN " + sqlStringList(selectedUids);
        long nodeCount = queryLong("SELECT count(*) FROM %s.\"%s\" node WHERE %s;".formatted(GRAPH_NAME, NODE_LABEL, where));
        long documentCount = queryLong("""
                SELECT count(DISTINCT nullif(%s, '')::bigint)
                  FROM %s."%s" node
                 WHERE %s
                   AND nullif(%s, '') IS NOT NULL
                   AND nullif(%s, '')::bigint > 0;
                """.formatted(
                        property("node", "documentId"),
                        GRAPH_NAME,
                        NODE_LABEL,
                        where,
                        property("node", "documentId"),
                        property("node", "documentId")
                ));
        long pageCount = queryLong("""
                SELECT count(*)
                  FROM %s."%s" node
                 WHERE %s
                   AND lower(%s) LIKE '%%page%%';
                """.formatted(GRAPH_NAME, NODE_LABEL, where, property("node", "uimaType")));
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        query("""
                SELECT %s AS uima_type,
                       count(*) AS count
                  FROM %s."%s" node
                 WHERE %s
                 GROUP BY uima_type
                 ORDER BY count DESC, uima_type
                 LIMIT 32;
                """.formatted(property("node", "uimaType"), GRAPH_NAME, NODE_LABEL, where), resultSet -> {
            while (resultSet.next()) {
                String type = nullIfBlank(resultSet.getString("uima_type"));
                if (type != null) {
                    typeCounts.put(type, resultSet.getLong("count"));
                }
            }
        });
        return new ScopePreview(nodeCount, documentCount, pageCount, typeCounts,
                List.of("Documents", "Pages", "Annotations", "Timeline", "Network", "Table", "Reader"));
    }

    public EgoGraph egoGraph(long corpusId,
                             List<String> seedUids,
                             List<String> associationTypes,
                             int depth) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (!ensureGraphForRead()) {
            return new EgoGraph(List.of(), List.of());
        }
        if (seedUids == null || seedUids.isEmpty()) {
            return new EgoGraph(List.of(), List.of());
        }
        int safeDepth = Math.max(1, Math.min(depth, 2));
        Map<String, DomainNodeView> nodes = new LinkedHashMap<>();
        Map<String, EgoEdge> edges = new LinkedHashMap<>();
        Set<String> frontier = new HashSet<>(seedUids);
        for (int i = 0; i < safeDepth && !frontier.isEmpty(); i++) {
            AssociationPage page = listAssociatedNodes(corpusId, List.copyOf(frontier), null, "BOTH", null, 0, 500);
            Set<String> nextFrontier = new HashSet<>();
            for (AssociationTraversal traversal : page.items()) {
                String edgeType = traversal.edge().uimaType();
                if (associationTypes != null && !associationTypes.isEmpty()
                        && associationTypes.stream().noneMatch(type -> type.equals(edgeType) || type.equals(simpleTypeName(edgeType)))) {
                    continue;
                }
                DomainNodeView target = traversal.target();
                nodes.put(target.uid(), target);
                nextFrontier.add(target.uid());
                edges.put(traversal.edge().uid(), new EgoEdge(traversal.edge(), target.uid(), traversal.edge().direction()));
            }
            frontier = nextFrontier;
        }
        DomainNodePage seeds = listDomainNodesByUid(corpusId, seedUids);
        for (DomainNodeView seed : seeds.items()) {
            nodes.put(seed.uid(), seed);
        }
        return new EgoGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private String domainNodeBatchCypher(List<DomainNode> nodes) {
        StringBuilder cypher = new StringBuilder(nodes.size() * 256);
        for (int i = 0; i < nodes.size(); i++) {
            DomainNode node = nodes.get(i);
            cypher.append("""
                    MERGE (n%d:%s {uid: %s})
                    SET n%d.uimaType = %s,
                        n%d.corpusId = %d,
                        n%d.documentId = %d,
                        n%d.name = %s,
                        n%d.uri = %s,
                        n%d.metadata = %s,
                        n%d.features = %s
                    WITH 1 AS _
                    """.formatted(
                    i,
                    NODE_LABEL,
                    cypherString(node.uid()),
                    i,
                    cypherString(node.uimaType()),
                    i,
                    node.corpusId(),
                    i,
                    node.documentId(),
                    i,
                    cypherNullableString(node.name()),
                    i,
                    cypherNullableString(node.uri()),
                    i,
                    cypherNullableString(node.metadata()),
                    i,
                    cypherNullableString(node.featuresJson())
            ));
        }
        cypher.append("RETURN 1");
        return cypher.toString();
    }

    private String domainNodeBatchUpdateSql(List<DomainNode> nodes) {
        return """
                WITH input(uid, uima_type, corpus_id, document_id, name, uri, metadata, features) AS (
                    VALUES
                %s
                ),
                resolved AS (
                    SELECT DISTINCT ON (i.uid)
                           i.uid,
                           ag_catalog.agtype_build_map(
                               'uid', i.uid,
                               'uimaType', i.uima_type,
                               'corpusId', i.corpus_id,
                               'documentId', i.document_id,
                               'name', i.name,
                               'uri', i.uri,
                               'metadata', i.metadata,
                               'features', i.features
                           ) AS properties
                    FROM input i
                    ORDER BY i.uid
                )
                UPDATE uce_domain_graph."FeatureStructure" node
                   SET properties = resolved.properties
                  FROM resolved
                  JOIN public.uce_age_domain_node_registry registry
                    ON registry.uid = resolved.uid
                 WHERE node.id::text = registry.age_id;
                """.formatted(domainNodeValues(nodes));
    }

    private String domainNodeBatchInsertSql(List<DomainNode> nodes) {
        return """
                WITH input(uid, uima_type, corpus_id, document_id, name, uri, metadata, features) AS (
                    VALUES
                %s
                ),
                resolved AS (
                    SELECT DISTINCT ON (i.uid)
                           i.uid,
                           ag_catalog.agtype_build_map(
                               'uid', i.uid,
                               'uimaType', i.uima_type,
                               'corpusId', i.corpus_id,
                               'documentId', i.document_id,
                               'name', i.name,
                               'uri', i.uri,
                               'metadata', i.metadata,
                               'features', i.features
                           ) AS properties
                    FROM input i
                    ORDER BY i.uid
                ),
                inserted AS (
                    INSERT INTO uce_domain_graph."FeatureStructure" (properties)
                    SELECT resolved.properties
                      FROM resolved
                      LEFT JOIN public.uce_age_domain_node_registry registry
                        ON registry.uid = resolved.uid
                     WHERE registry.uid IS NULL
                    RETURNING id, trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text) AS uid
                )
                INSERT INTO public.uce_age_domain_node_registry(uid, age_id)
                SELECT inserted.uid, inserted.id::text
                  FROM inserted
                ON CONFLICT (uid) DO UPDATE
                    SET age_id = EXCLUDED.age_id;
                """.formatted(domainNodeValues(nodes));
    }

    private String domainNodeValues(List<DomainNode> nodes) {
        StringBuilder values = new StringBuilder(nodes.size() * 220);
        for (int i = 0; i < nodes.size(); i++) {
            DomainNode node = nodes.get(i);
            if (i > 0) {
                values.append(",\n");
            }
            values.append("(")
                    .append(sqlString(node.uid())).append(", ")
                    .append(sqlString(node.uimaType())).append(", ")
                    .append(node.corpusId()).append(", ")
                    .append(node.documentId()).append(", ")
                    .append(sqlNullableString(node.name())).append(", ")
                    .append(sqlNullableString(node.uri())).append(", ")
                    .append(sqlNullableString(node.metadata())).append(", ")
                    .append(sqlNullableString(node.featuresJson()))
                    .append(")");
        }
        return values.toString();
    }

    private String associationEdgeBatchCypher(List<AssociationEdge> edges) {
        StringBuilder cypher = new StringBuilder(edges.size() * 320);
        for (int i = 0; i < edges.size(); i++) {
            AssociationEdge edge = edges.get(i);
            cypher.append("""
                    MATCH (a%d:%s {uid: %s}), (b%d:%s {uid: %s})
                    MERGE (a%d)-[r%d:%s {uid: %s}]->(b%d)
                    SET r%d.uimaType = %s,
                        r%d.corpusId = %d,
                        r%d.documentId = %d,
                        r%d.name = %s,
                        r%d.metadata = %s,
                        r%d.features = %s
                    WITH 1 AS _
                    """.formatted(
                    i,
                    NODE_LABEL,
                    cypherString(edge.leftUid()),
                    i,
                    NODE_LABEL,
                    cypherString(edge.rightUid()),
                    i,
                    i,
                    EDGE_LABEL,
                    cypherString(edge.uid()),
                    i,
                    i,
                    cypherString(edge.uimaType()),
                    i,
                    edge.corpusId(),
                    i,
                    edge.documentId(),
                    i,
                    cypherNullableString(edge.name()),
                    i,
                    cypherNullableString(edge.metadata()),
                    i,
                    cypherNullableString(edge.featuresJson())
            ));
        }
        cypher.append("RETURN 1");
        return cypher.toString();
    }

    private String associationEdgeBatchUpdateSql(List<AssociationEdge> edges) {
        return """
                WITH input(uid, uima_type, corpus_id, document_id, left_uid, right_uid, name, metadata, features) AS (
                    VALUES
                %s
                ),
                resolved AS (
                    SELECT DISTINCT ON (i.uid)
                           i.*,
                           left_registry.age_id::ag_catalog.graphid AS start_id,
                           right_registry.age_id::ag_catalog.graphid AS end_id,
                           ag_catalog.agtype_build_map(
                               'uid', i.uid,
                               'uimaType', i.uima_type,
                               'corpusId', i.corpus_id,
                               'documentId', i.document_id,
                               'name', i.name,
                               'metadata', i.metadata,
                               'features', i.features
                           ) AS properties
                    FROM input i
                    JOIN public.uce_age_domain_node_registry left_registry
                      ON left_registry.uid = i.left_uid
                    JOIN public.uce_age_domain_node_registry right_registry
                      ON right_registry.uid = i.right_uid
                    ORDER BY i.uid
                )
                UPDATE uce_domain_graph."Association" edge
                   SET start_id = resolved.start_id,
                       end_id = resolved.end_id,
                       properties = resolved.properties
                  FROM resolved
                  JOIN public.uce_age_association_edge_registry registry
                    ON registry.uid = resolved.uid
                 WHERE edge.id::text = registry.age_id;
                """.formatted(associationEdgeValues(edges));
    }

    private String associationEdgeBatchInsertSql(List<AssociationEdge> edges) {
        return """
                WITH input(uid, uima_type, corpus_id, document_id, left_uid, right_uid, name, metadata, features) AS (
                    VALUES
                %s
                ),
                resolved AS (
                    SELECT DISTINCT ON (i.uid)
                           i.*,
                           left_registry.age_id::ag_catalog.graphid AS start_id,
                           right_registry.age_id::ag_catalog.graphid AS end_id,
                           ag_catalog.agtype_build_map(
                               'uid', i.uid,
                               'uimaType', i.uima_type,
                               'corpusId', i.corpus_id,
                               'documentId', i.document_id,
                               'name', i.name,
                               'metadata', i.metadata,
                               'features', i.features
                           ) AS properties
                    FROM input i
                    JOIN public.uce_age_domain_node_registry left_registry
                      ON left_registry.uid = i.left_uid
                    JOIN public.uce_age_domain_node_registry right_registry
                      ON right_registry.uid = i.right_uid
                    ORDER BY i.uid
                ),
                inserted AS (
                    INSERT INTO uce_domain_graph."Association" (start_id, end_id, properties)
                    SELECT resolved.start_id, resolved.end_id, resolved.properties
                      FROM resolved
                      LEFT JOIN public.uce_age_association_edge_registry registry
                        ON registry.uid = resolved.uid
                     WHERE registry.uid IS NULL
                    RETURNING id, trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text) AS uid
                )
                INSERT INTO public.uce_age_association_edge_registry(uid, age_id)
                SELECT inserted.uid, inserted.id::text
                  FROM inserted
                ON CONFLICT (uid) DO UPDATE
                    SET age_id = EXCLUDED.age_id;
                """.formatted(associationEdgeValues(edges));
    }

    private String associationEdgeValues(List<AssociationEdge> edges) {
        StringBuilder values = new StringBuilder(edges.size() * 256);
        for (int i = 0; i < edges.size(); i++) {
            AssociationEdge edge = edges.get(i);
            if (i > 0) {
                values.append(",\n");
            }
            values.append("(")
                    .append(sqlString(edge.uid())).append(", ")
                    .append(sqlString(edge.uimaType())).append(", ")
                    .append(edge.corpusId()).append(", ")
                    .append(edge.documentId()).append(", ")
                    .append(sqlString(edge.leftUid())).append(", ")
                    .append(sqlString(edge.rightUid())).append(", ")
                    .append(sqlNullableString(edge.name())).append(", ")
                    .append(sqlNullableString(edge.metadata())).append(", ")
                    .append(sqlNullableString(edge.featuresJson()))
                    .append(")");
        }
        return values.toString();
    }

    private void executeStagedDomainBatch(List<DomainNode> nodes) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.sql.execute", GRAPH_NAME, "service_execution")) {
            db.executeNativeWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOAD 'age'");
                    statement.execute("SET search_path = ag_catalog, \"$user\", public");
                    statement.execute("SET LOCAL synchronous_commit = off");
                    statement.execute("SET LOCAL jit = off");
                    statement.execute("DROP TABLE IF EXISTS uce_age_domain_stage");
                    statement.execute("""
                            CREATE TEMP TABLE uce_age_domain_stage (
                                uid text PRIMARY KEY,
                                properties ag_catalog.agtype NOT NULL
                            ) ON COMMIT DROP;
                            """);
                }
                try (var ignoredStage = UCEProfileRecorder.scope("age.domain.stage.fill", String.valueOf(nodes.size()), "service_execution")) {
                    try {
                        connection.unwrap(PGConnection.class)
                                .getCopyAPI()
                                .copyIn(
                                        "COPY uce_age_domain_stage (uid, properties) FROM STDIN WITH (FORMAT csv)",
                                        new StringReader(domainNodeCopyRows(nodes))
                                );
                    } catch (IOException ex) {
                        throw new java.sql.SQLException("Unable to COPY staged AGE domain nodes", ex);
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    StagePresence presence;
                    try (var ignoredPresence = UCEProfileRecorder.scope("age.domain.stage.presence", String.valueOf(nodes.size()), "service_execution")) {
                        presence = stagePresence(statement, "uce_age_domain_stage", "public.uce_age_domain_node_registry");
                    }
                    if (presence.existing() > 0) {
                        try (var ignoredUpdate = UCEProfileRecorder.scope("age.domain.stage.update", String.valueOf(presence.existing()), "service_execution")) {
                            statement.execute(domainNodeStageUpdateSql());
                        }
                    }
                    if (presence.missing() > 0) {
                        try (var ignoredInsert = UCEProfileRecorder.scope("age.domain.stage.insert", String.valueOf(presence.missing()), "service_execution")) {
                            statement.execute(domainNodeStageInsertSql());
                        }
                    }
                }
            });
        }
    }

    private void executeStagedAssociationBatch(List<AssociationEdge> edges) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.sql.execute", GRAPH_NAME, "service_execution")) {
            db.executeNativeWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOAD 'age'");
                    statement.execute("SET search_path = ag_catalog, \"$user\", public");
                    statement.execute("SET LOCAL synchronous_commit = off");
                    statement.execute("SET LOCAL jit = off");
                    statement.execute("DROP TABLE IF EXISTS uce_age_association_stage");
                    statement.execute("""
                            CREATE TEMP TABLE uce_age_association_stage (
                                uid text PRIMARY KEY,
                                left_uid text NOT NULL,
                                right_uid text NOT NULL,
                                properties ag_catalog.agtype NOT NULL
                            ) ON COMMIT DROP;
                            """);
                }
                try (var ignoredStage = UCEProfileRecorder.scope("age.association.stage.fill", String.valueOf(edges.size()), "service_execution")) {
                    try {
                        connection.unwrap(PGConnection.class)
                                .getCopyAPI()
                                .copyIn(
                                        "COPY uce_age_association_stage (uid, left_uid, right_uid, properties) FROM STDIN WITH (FORMAT csv)",
                                        new StringReader(associationEdgeCopyRows(edges))
                                );
                    } catch (IOException ex) {
                        throw new java.sql.SQLException("Unable to COPY staged AGE association edges", ex);
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    StagePresence presence;
                    try (var ignoredPresence = UCEProfileRecorder.scope("age.association.stage.presence", String.valueOf(edges.size()), "service_execution")) {
                        presence = stagePresence(statement, "uce_age_association_stage", "public.uce_age_association_edge_registry");
                    }
                    if (presence.existing() > 0) {
                        try (var ignoredUpdate = UCEProfileRecorder.scope("age.association.stage.update", String.valueOf(presence.existing()), "service_execution")) {
                            statement.execute(associationEdgeStageUpdateSql());
                        }
                    }
                    if (presence.missing() > 0) {
                        try (var ignoredInsert = UCEProfileRecorder.scope("age.association.stage.insert", String.valueOf(presence.missing()), "service_execution")) {
                            statement.execute(associationEdgeStageInsertSql());
                        }
                    }
                }
            });
        }
    }

    private static StagePresence stagePresence(Statement statement, String stageTable, String registryTable)
            throws java.sql.SQLException {
        String sql = """
                SELECT count(*) AS total,
                       count(registry.uid) AS existing
                  FROM %s stage
                  LEFT JOIN %s registry
                    ON registry.uid = stage.uid;
                """.formatted(stageTable, registryTable);
        try (var result = statement.executeQuery(sql)) {
            result.next();
            long total = result.getLong("total");
            long existing = result.getLong("existing");
            return new StagePresence(total, existing);
        }
    }

    private String domainNodeStageUpdateSql() {
        return """
                UPDATE uce_domain_graph."FeatureStructure" node
                   SET properties = stage.properties
                  FROM uce_age_domain_stage stage
                  JOIN public.uce_age_domain_node_registry registry
                    ON registry.uid = stage.uid
                 WHERE node.id::text = registry.age_id;
                """;
    }

    private String domainNodeStageInsertSql() {
        return """
                WITH inserted AS (
                    INSERT INTO uce_domain_graph."FeatureStructure" (properties)
                    SELECT stage.properties
                      FROM uce_age_domain_stage stage
                      LEFT JOIN public.uce_age_domain_node_registry registry
                        ON registry.uid = stage.uid
                     WHERE registry.uid IS NULL
                    RETURNING id, trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text) AS uid
                )
                INSERT INTO public.uce_age_domain_node_registry(uid, age_id)
                SELECT inserted.uid, inserted.id::text
                  FROM inserted
                ON CONFLICT (uid) DO UPDATE
                    SET age_id = EXCLUDED.age_id;
                """;
    }

    private String associationEdgeStageUpdateSql() {
        return """
                WITH resolved AS (
                    SELECT stage.uid,
                           left_registry.age_id::ag_catalog.graphid AS start_id,
                           right_registry.age_id::ag_catalog.graphid AS end_id,
                           stage.properties
                      FROM uce_age_association_stage stage
                      JOIN public.uce_age_association_edge_registry edge_registry
                        ON edge_registry.uid = stage.uid
                      JOIN public.uce_age_domain_node_registry left_registry
                        ON left_registry.uid = stage.left_uid
                      JOIN public.uce_age_domain_node_registry right_registry
                        ON right_registry.uid = stage.right_uid
                )
                UPDATE uce_domain_graph."Association" edge
                   SET start_id = resolved.start_id,
                       end_id = resolved.end_id,
                       properties = resolved.properties
                  FROM resolved
                  JOIN public.uce_age_association_edge_registry registry
                    ON registry.uid = resolved.uid
                 WHERE edge.id::text = registry.age_id;
                """;
    }

    private String associationEdgeStageInsertSql() {
        return """
                WITH resolved AS (
                    SELECT stage.uid,
                           left_registry.age_id::ag_catalog.graphid AS start_id,
                           right_registry.age_id::ag_catalog.graphid AS end_id,
                           stage.properties
                      FROM uce_age_association_stage stage
                      LEFT JOIN public.uce_age_association_edge_registry edge_registry
                        ON edge_registry.uid = stage.uid
                      JOIN public.uce_age_domain_node_registry left_registry
                        ON left_registry.uid = stage.left_uid
                      JOIN public.uce_age_domain_node_registry right_registry
                        ON right_registry.uid = stage.right_uid
                     WHERE edge_registry.uid IS NULL
                ),
                inserted AS (
                    INSERT INTO uce_domain_graph."Association" (start_id, end_id, properties)
                    SELECT resolved.start_id, resolved.end_id, resolved.properties
                      FROM resolved
                    RETURNING id, trim(both '"' from ag_catalog.agtype_access_operator(properties, '"uid"'::ag_catalog.agtype)::text) AS uid
                )
                INSERT INTO public.uce_age_association_edge_registry(uid, age_id)
                SELECT inserted.uid, inserted.id::text
                  FROM inserted
                ON CONFLICT (uid) DO UPDATE
                    SET age_id = EXCLUDED.age_id;
                """;
    }

    private void executeCypher(String cypher) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.cypher.execute", GRAPH_NAME, "service_execution")) {
            db.executeNativeStatements(List.of(
                    "LOAD 'age'",
                    "SET search_path = ag_catalog, \"$user\", public",
                    """
                    SELECT *
                    FROM cypher('%s', $cypher$
                    %s
                    $cypher$) AS (result agtype)
                    """.formatted(GRAPH_NAME, cypher)
            ));
        }
    }

    private void executeSql(String sql) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.sql.execute", GRAPH_NAME, "service_execution")) {
            db.executeNativeStatement(sql);
        }
    }

    private void executeSqlStatements(List<String> sqlStatements) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.sql.execute", GRAPH_NAME, "service_execution")) {
            db.executeNativeStatements(sqlStatements);
        }
    }

    private DomainNodePage listDomainNodesByUid(long corpusId, List<String> uids) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (uids == null || uids.isEmpty()) {
            return new DomainNodePage(List.of(), 0, 0, 0);
        }
        String where = corpusPredicate("node", corpusId) + " AND " + property("node", "uid") + " IN " + sqlStringList(uids);
        List<DomainNodeView> nodes = new ArrayList<>();
        query("""
                SELECT node.id::text AS age_id,
                       %s AS uid,
                       %s AS uima_type,
                       %s AS corpus_id,
                       %s AS document_id,
                       %s AS name,
                       %s AS uri,
                       %s AS metadata,
                       %s AS features,
                       (SELECT count(*) FROM %s."%s" edge WHERE edge.start_id = node.id) AS outgoing_count,
                       (SELECT count(*) FROM %s."%s" edge WHERE edge.end_id = node.id) AS incoming_count
                  FROM %s."%s" node
                 WHERE %s;
                """.formatted(
                        property("node", "uid"),
                        property("node", "uimaType"),
                        property("node", "corpusId"),
                        property("node", "documentId"),
                        property("node", "name"),
                        property("node", "uri"),
                        property("node", "metadata"),
                        property("node", "features"),
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        EDGE_LABEL,
                        GRAPH_NAME,
                        NODE_LABEL,
                        where
                ), resultSet -> {
            while (resultSet.next()) {
                nodes.add(domainNodeView(resultSet));
            }
        });
        return new DomainNodePage(nodes, nodes.size(), 0, nodes.size());
    }

    private void query(String sql, ResultSetConsumer consumer) throws DatabaseOperationException, DocumentAccessDeniedException {
        try (var ignored = UCEProfileRecorder.scope("age.query", GRAPH_NAME, "service_execution")) {
            db.executeNativeWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOAD 'age'");
                    statement.execute("SET search_path = ag_catalog, \"$user\", public");
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        consumer.accept(resultSet);
                    }
                }
            });
        }
    }

    private long queryLong(String sql) throws DatabaseOperationException, DocumentAccessDeniedException {
        final long[] value = new long[]{0};
        query(sql, resultSet -> {
            if (resultSet.next()) {
                value[0] = resultSet.getLong(1);
            }
        });
        return value[0];
    }

    private DomainNodeView domainNodeView(ResultSet resultSet) throws SQLException {
        String uimaType = nullIfBlank(resultSet.getString("uima_type"));
        String uid = nullIfBlank(resultSet.getString("uid"));
        return new DomainNodeView(
                resultSet.getString("age_id"),
                uid,
                uimaType,
                simpleTypeName(uimaType),
                parseLongOrMinusOne(resultSet.getString("corpus_id")),
                parseLongOrMinusOne(resultSet.getString("document_id")),
                displayName(nullIfBlank(resultSet.getString("name")), uid),
                nullIfBlank(resultSet.getString("uri")),
                nullIfBlank(resultSet.getString("metadata")),
                nullIfBlank(resultSet.getString("features")),
                resultSet.getLong("outgoing_count"),
                resultSet.getLong("incoming_count")
        );
    }

    private String domainNodeWhere(long corpusId, String uimaType, String queryText) {
        List<String> predicates = new ArrayList<>();
        predicates.add(corpusPredicate("node", corpusId));
        if (uimaType != null && !uimaType.isBlank()) {
            predicates.add(property("node", "uimaType") + " = " + sqlString(uimaType.trim()));
        }
        if (queryText != null && !queryText.isBlank()) {
            String like = "%" + queryText.trim().toLowerCase() + "%";
            predicates.add("("
                    + "lower(coalesce(" + property("node", "name") + ", '')) LIKE " + sqlString(like)
                    + " OR lower(coalesce(" + property("node", "uid") + ", '')) LIKE " + sqlString(like)
                    + " OR lower(coalesce(" + property("node", "uri") + ", '')) LIKE " + sqlString(like)
                    + ")");
        }
        return String.join(" AND ", predicates);
    }

    private String associationWhere(long corpusId,
                                    List<String> sourceUids,
                                    String associationType,
                                    String direction,
                                    String targetType) {
        List<String> predicates = new ArrayList<>();
        predicates.add(corpusPredicate("edge", corpusId));
        String normalizedDirection = normalizeDirection(direction);
        if ("INCOMING".equals(normalizedDirection)) {
            predicates.add(property("source", "uid") + " IN " + sqlStringList(sourceUids));
        } else if ("BOTH".equals(normalizedDirection)) {
            predicates.add(property("source", "uid") + " IN " + sqlStringList(sourceUids));
        } else {
            predicates.add(property("source", "uid") + " IN " + sqlStringList(sourceUids));
        }
        if (associationType != null && !associationType.isBlank()) {
            String trimmed = associationType.trim();
            predicates.add("(" + property("edge", "uimaType") + " = " + sqlString(trimmed)
                    + " OR " + property("edge", "uimaType") + " LIKE " + sqlString("%." + trimmed) + ")");
        }
        if (targetType != null && !targetType.isBlank()) {
            String trimmed = targetType.trim();
            predicates.add("(" + property("target", "uimaType") + " = " + sqlString(trimmed)
                    + " OR " + property("target", "uimaType") + " LIKE " + sqlString("%." + trimmed) + ")");
        }
        return String.join(" AND ", predicates);
    }

    private String associationFromJoin(String direction) {
        return switch (normalizeDirection(direction)) {
            case "INCOMING" -> """
                    %s."%s" edge
                    JOIN %s."%s" source ON source.id = edge.end_id
                    JOIN %s."%s" target ON target.id = edge.start_id
                    """.formatted(GRAPH_NAME, EDGE_LABEL, GRAPH_NAME, NODE_LABEL, GRAPH_NAME, NODE_LABEL);
            case "BOTH" -> """
                    (
                        SELECT edge.*, source.id AS source_node_id, target.id AS target_node_id, 'OUTGOING' AS traversal_direction
                          FROM %s."%s" edge
                          JOIN %s."%s" source ON source.id = edge.start_id
                          JOIN %s."%s" target ON target.id = edge.end_id
                        UNION ALL
                        SELECT edge.*, source.id AS source_node_id, target.id AS target_node_id, 'INCOMING' AS traversal_direction
                          FROM %s."%s" edge
                          JOIN %s."%s" source ON source.id = edge.end_id
                          JOIN %s."%s" target ON target.id = edge.start_id
                    ) edge
                    JOIN %s."%s" source ON source.id = edge.source_node_id
                    JOIN %s."%s" target ON target.id = edge.target_node_id
                    """.formatted(
                    GRAPH_NAME, EDGE_LABEL, GRAPH_NAME, NODE_LABEL, GRAPH_NAME, NODE_LABEL,
                    GRAPH_NAME, EDGE_LABEL, GRAPH_NAME, NODE_LABEL, GRAPH_NAME, NODE_LABEL,
                    GRAPH_NAME, NODE_LABEL, GRAPH_NAME, NODE_LABEL);
            default -> """
                    %s."%s" edge
                    JOIN %s."%s" source ON source.id = edge.start_id
                    JOIN %s."%s" target ON target.id = edge.end_id
                    """.formatted(GRAPH_NAME, EDGE_LABEL, GRAPH_NAME, NODE_LABEL, GRAPH_NAME, NODE_LABEL);
        };
    }

    private String associationDirectionSelect(String direction) {
        return "BOTH".equals(normalizeDirection(direction)) ? "edge.traversal_direction" : sqlString(normalizeDirection(direction));
    }

    private static String normalizeDirection(String direction) {
        if (direction == null) {
            return "OUTGOING";
        }
        String normalized = direction.trim().toUpperCase();
        if (normalized.equals("INCOMING") || normalized.equals("BOTH")) {
            return normalized;
        }
        return "OUTGOING";
    }

    private static String corpusPredicate(String alias, long corpusId) {
        if (corpusId <= 0) {
            return "true";
        }
        return "nullif(%s, '')::bigint = %d".formatted(property(alias, "corpusId"), corpusId);
    }

    private static String property(String alias, String key) {
        return "nullif(trim(both '\"' from ag_catalog.agtype_access_operator(%s.properties, '\"%s\"'::ag_catalog.agtype)::text), 'null')"
                .formatted(alias, key);
    }

    private static String sqlStringList(List<String> values) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(sqlString(values.get(i)));
        }
        builder.append(")");
        return builder.toString();
    }

    private Set<String> existingRegistryUids(String registryTable, List<String> uids) throws DatabaseOperationException, DocumentAccessDeniedException {
        if (uids.isEmpty()) {
            return Set.of();
        }
        String sql = """
                WITH input(uid) AS (
                    VALUES
                %s
                )
                SELECT registry.uid
                  FROM input
                  JOIN %s registry
                    ON registry.uid = input.uid;
                """.formatted(uidValues(uids), registryTable);
        try (var ignored = UCEProfileRecorder.scope("age.registry.lookup", registryTable, "service_execution")) {
            List<?> rows = db.executeNativeQuery(sql);
            Set<String> result = new HashSet<>(rows.size());
            for (Object row : rows) {
                result.add(String.valueOf(row));
            }
            return result;
        }
    }

    private static String cypherNullableString(String value) {
        return value == null ? "null" : cypherString(value);
    }

    private static String cypherString(String value) {
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
    }

    private static String sqlNullableString(String value) {
        return value == null ? "null" : sqlString(value);
    }

    private static String sqlString(String value) {
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
    }

    private static String domainNodeProperties(DomainNode node) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendJsonField(json, "uid", node.uid());
        appendJsonField(json, "uimaType", node.uimaType());
        appendJsonField(json, "corpusId", node.corpusId());
        appendJsonField(json, "documentId", node.documentId());
        appendJsonField(json, "name", node.name());
        appendJsonField(json, "uri", node.uri());
        appendJsonField(json, "metadata", node.metadata());
        appendJsonField(json, "features", node.featuresJson());
        json.append('}');
        return json.toString();
    }

    private static String associationEdgeProperties(AssociationEdge edge) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendJsonField(json, "uid", edge.uid());
        appendJsonField(json, "uimaType", edge.uimaType());
        appendJsonField(json, "corpusId", edge.corpusId());
        appendJsonField(json, "documentId", edge.documentId());
        appendJsonField(json, "name", edge.name());
        appendJsonField(json, "metadata", edge.metadata());
        appendJsonField(json, "features", edge.featuresJson());
        json.append('}');
        return json.toString();
    }

    private static String domainNodeCopyRows(List<DomainNode> nodes) {
        StringBuilder rows = new StringBuilder(nodes.size() * 260);
        for (DomainNode node : nodes) {
            rows.append(csvField(node.uid()))
                    .append(',')
                    .append(csvField(domainNodeProperties(node)))
                    .append('\n');
        }
        return rows.toString();
    }

    private static String associationEdgeCopyRows(List<AssociationEdge> edges) {
        StringBuilder rows = new StringBuilder(edges.size() * 320);
        for (AssociationEdge edge : edges) {
            rows.append(csvField(edge.uid()))
                    .append(',')
                    .append(csvField(edge.leftUid()))
                    .append(',')
                    .append(csvField(edge.rightUid()))
                    .append(',')
                    .append(csvField(associationEdgeProperties(edge)))
                    .append('\n');
        }
        return rows.toString();
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void appendJsonField(StringBuilder json, String key, String value) {
        appendJsonSeparator(json);
        json.append('"').append(key).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(jsonEscape(value)).append('"');
        }
    }

    private static void appendJsonField(StringBuilder json, String key, long value) {
        appendJsonSeparator(json);
        json.append('"').append(key).append("\":").append(value);
    }

    private static void appendJsonSeparator(StringBuilder json) {
        if (json.length() > 1 && json.charAt(json.length() - 1) != '{') {
            json.append(',');
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static List<String> domainUids(List<DomainNode> nodes) {
        List<String> uids = new ArrayList<>(nodes.size());
        for (DomainNode node : nodes) {
            uids.add(node.uid());
        }
        return uids;
    }

    private static List<String> associationUids(List<AssociationEdge> edges) {
        List<String> uids = new ArrayList<>(edges.size());
        for (AssociationEdge edge : edges) {
            uids.add(edge.uid());
        }
        return uids;
    }

    private static String uidValues(List<String> uids) {
        StringBuilder values = new StringBuilder(uids.size() * 80);
        for (int i = 0; i < uids.size(); i++) {
            if (i > 0) {
                values.append(",\n");
            }
            values.append("(").append(sqlString(uids.get(i))).append(")");
        }
        return values.toString();
    }

    private static <T> List<List<T>> partition(List<T> values, int size) {
        if (values.size() <= size) {
            return List.of(values);
        }
        java.util.ArrayList<List<T>> partitions = new java.util.ArrayList<>();
        for (int i = 0; i < values.size(); i += size) {
            partitions.add(values.subList(i, Math.min(i + size, values.size())));
        }
        return partitions;
    }

    private static List<DomainNode> deduplicateDomains(List<DomainNode> nodes) {
        Map<String, DomainNode> unique = new LinkedHashMap<>();
        for (DomainNode node : nodes) {
            unique.put(node.uid(), node);
        }
        return List.copyOf(unique.values());
    }

    private static List<AssociationEdge> deduplicateAssociations(List<AssociationEdge> edges) {
        Map<String, AssociationEdge> unique = new LinkedHashMap<>();
        for (AssociationEdge edge : edges) {
            unique.put(edge.uid(), edge);
        }
        return List.copyOf(unique.values());
    }

    private static String batchDomain(List<?> rawValues, List<?> persistedValues) {
        if (rawValues.size() == 1) {
            return rawValues.getFirst().getClass().getSimpleName();
        }
        if (rawValues.size() == persistedValues.size()) {
            return "batch:" + persistedValues.size();
        }
        return "batch:" + persistedValues.size() + "/raw:" + rawValues.size();
    }

    private static int configuredBatchSize(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        int index = typeName.lastIndexOf('.');
        return index >= 0 ? typeName.substring(index + 1) : typeName;
    }

    private static String displayName(String name, String uid) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (uid == null || uid.isBlank()) {
            return "-";
        }
        int index = uid.lastIndexOf(':');
        return index >= 0 && index + 1 < uid.length() ? uid.substring(index + 1) : uid;
    }

    private static String nullIfBlank(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) {
            return null;
        }
        return value;
    }

    private static long parseLongOrMinusOne(String value) {
        String normalized = nullIfBlank(value);
        if (normalized == null) {
            return -1;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record StagePresence(long total, long existing) {
        long missing() {
            return total - existing;
        }
    }

    @FunctionalInterface
    private interface ResultSetConsumer {
        void accept(ResultSet resultSet) throws SQLException;
    }

    private static class MutableDomainTypeSummary {
        final String uimaType;
        final long count;
        final Map<String, Long> associationCounts = new LinkedHashMap<>();

        MutableDomainTypeSummary(String uimaType, long count) {
            this.uimaType = uimaType;
            this.count = count;
        }
    }

    public record DomainNode(
            String uid,
            String uimaType,
            long corpusId,
            long documentId,
            String name,
            String uri,
            String metadata,
            String featuresJson
    ) {
    }

    public record AssociationEdge(
            String uid,
            String uimaType,
            long corpusId,
            long documentId,
            String leftUid,
            String rightUid,
            String name,
            String metadata,
            String featuresJson
    ) {
    }

    public record DomainTypeSummary(
            String uimaType,
            String label,
            long count,
            Map<String, Long> associationCounts
    ) {
    }

    public record DomainNodeView(
            String ageId,
            String uid,
            String uimaType,
            String label,
            long corpusId,
            long documentId,
            String name,
            String uri,
            String metadata,
            String features,
            long outgoingCount,
            long incomingCount
    ) {
    }

    public record DomainNodePage(
            List<DomainNodeView> items,
            long total,
            int skip,
            int take
    ) {
    }

    public record AssociationEdgeView(
            String ageId,
            String uid,
            String uimaType,
            String label,
            String name,
            String metadata,
            String features,
            String direction
    ) {
    }

    public record AssociationTraversal(
            AssociationEdgeView edge,
            DomainNodeView target
    ) {
    }

    public record AssociationPage(
            List<AssociationTraversal> items,
            long total,
            int skip,
            int take
    ) {
    }

    public record ScopePreview(
            long nodeCount,
            long documentCount,
            long pageCount,
            Map<String, Long> typeCounts,
            List<String> availableModes
    ) {
    }

    public record EgoEdge(
            AssociationEdgeView edge,
            String targetUid,
            String direction
    ) {
    }

    public record EgoGraph(
            List<DomainNodeView> nodes,
            List<EgoEdge> edges
    ) {
    }
}
