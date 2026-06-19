package org.texttechnologylab.uce.common.services;

import java.util.List;
import java.util.Map;

import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.models.Linkable;
import org.texttechnologylab.uce.common.models.ModelBase;
import org.texttechnologylab.uce.common.models.UIMAAnnotation;
import org.texttechnologylab.uce.common.models.biofid.BiofidTaxon;
import org.texttechnologylab.uce.common.models.biofid.GazetteerTaxon;
import org.texttechnologylab.uce.common.models.biofid.GnFinderTaxon;
import org.texttechnologylab.uce.common.models.corpus.Corpus;
import org.texttechnologylab.uce.common.models.corpus.CorpusTsnePlot;
import org.texttechnologylab.uce.common.models.corpus.Document;
import org.texttechnologylab.uce.common.models.corpus.DocumentTopThreeTopics;
import org.texttechnologylab.uce.common.models.corpus.GeoNameFeatureClass;
import org.texttechnologylab.uce.common.models.corpus.GeoName;
import org.texttechnologylab.uce.common.models.corpus.KeywordDistribution;
import org.texttechnologylab.uce.common.models.corpus.Lemma;
import org.texttechnologylab.uce.common.models.corpus.LexiconEntry;
import org.texttechnologylab.uce.common.models.corpus.LexiconEntryId;
import org.texttechnologylab.uce.common.models.corpus.NamedEntity;
import org.texttechnologylab.uce.common.models.corpus.Page;
import org.texttechnologylab.uce.common.models.corpus.Sentence;
import org.texttechnologylab.uce.common.models.corpus.Time;
import org.texttechnologylab.uce.common.models.corpus.UCELog;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadata;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadataFilter;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadataValueType;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationLink;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationToDocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentToAnnotationLink;
import org.texttechnologylab.uce.common.models.corpus.links.Link;
import org.texttechnologylab.uce.common.models.dto.map.MapClusterDto;
import org.texttechnologylab.uce.common.models.dto.map.PointDto;
import org.texttechnologylab.uce.common.models.dto.UCEMetadataFilterDto;
import org.texttechnologylab.uce.common.models.gbif.GbifOccurrence;
import org.texttechnologylab.uce.common.models.globe.GlobeTaxon;
import org.texttechnologylab.uce.common.models.imp.ImportLog;
import org.texttechnologylab.uce.common.models.imp.UCEImport;
import org.texttechnologylab.uce.common.models.negation.CompleteNegation;
import org.texttechnologylab.uce.common.models.search.AnnotationSearchResult;
import org.texttechnologylab.uce.common.models.search.DocumentSearchResult;
import org.texttechnologylab.uce.common.models.search.OrderByColumn;
import org.texttechnologylab.uce.common.models.search.SearchLayer;
import org.texttechnologylab.uce.common.models.search.SearchOrder;
import org.texttechnologylab.uce.common.models.topic.TopicWord;
import org.texttechnologylab.uce.common.models.topic.UnifiedTopic;

public interface DataInterface {

    public default String backendName() {
        return getClass().getSimpleName();
    }

    public default List<Map<String, Object>> duavizTypes() throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of(
                Map.of("typeCode", 1, "name", "Corpus", "superTypeCode", 0, "superTypeName", "", "annotation", false, "artifact", true, "instanceCount", getAllCorpora().size()),
                Map.of("typeCode", 2, "name", "Document", "superTypeCode", 1, "superTypeName", "Corpus", "annotation", false, "artifact", true, "instanceCount", countDocumentsInCorpus(0))
        );
    }

    public default Map<String, Object> duavizSchema() throws DatabaseOperationException, DocumentAccessDeniedException {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("format", "UIMA-CAS-JSON");
        schema.put("types", duavizTypes());
        return schema;
    }

    public default List<Map<String, Object>> duavizArtifacts() throws DatabaseOperationException, DocumentAccessDeniedException {
        List<Map<String, Object>> artifacts = new java.util.ArrayList<>();
        for (Corpus corpus : getAllCorpora()) {
            Map<String, Object> corpusArtifact = new java.util.LinkedHashMap<>();
            corpusArtifact.put("fsId", corpus.getId());
            corpusArtifact.put("typeCode", 1);
            corpusArtifact.put("typeName", "Corpus");
            corpusArtifact.put("artifactKind", "corpus");
            corpusArtifact.put("name", corpusTitle(corpus));
            corpusArtifact.put("count", countDocumentsInCorpus(corpus.getId()));
            corpusArtifact.put("cas", casJson(corpusFs(corpus)));
            corpusArtifact.put("fs", corpusFs(corpus));
            artifacts.add(corpusArtifact);
            for (Document document : getDocumentsByCorpusId(corpus.getId(), 0, 250)) {
                Map<String, Object> documentArtifact = new java.util.LinkedHashMap<>();
                documentArtifact.put("fsId", document.getId());
                documentArtifact.put("typeCode", 2);
                documentArtifact.put("typeName", "Document");
                documentArtifact.put("artifactKind", "document");
                documentArtifact.put("name", documentTitle(document));
                documentArtifact.put("corpusFsId", corpus.getId());
                documentArtifact.put("count", 1);
                documentArtifact.put("cas", casJson(documentFs(document, false)));
                documentArtifact.put("fs", documentFs(document, false));
                artifacts.add(documentArtifact);
            }
        }
        return artifacts;
    }

    public default Map<String, Object> duavizInstancesByType(int typeCode, int offset, int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        int boundedOffset = Math.max(0, offset);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> page = new java.util.ArrayList<>();
        int total = 0;
        boolean hasMore = false;
        if (typeCode == 1) {
            List<Corpus> corpora = getAllCorpora();
            total = corpora.size();
            for (Corpus corpus : corpora.stream().skip(boundedOffset).limit(boundedLimit).toList()) {
                Map<String, Object> corpusArtifact = new java.util.LinkedHashMap<>();
                corpusArtifact.put("fsId", corpus.getId());
                corpusArtifact.put("typeCode", 1);
                corpusArtifact.put("typeName", "Corpus");
                corpusArtifact.put("artifactKind", "corpus");
                corpusArtifact.put("name", corpusTitle(corpus));
                corpusArtifact.put("count", 0);
                page.add(corpusArtifact);
            }
            hasMore = boundedOffset + page.size() < total;
        } else if (typeCode == 2) {
            int skipped = 0;
            int requested = boundedOffset + boundedLimit + 1;
            for (Corpus corpus : getAllCorpora()) {
                for (Document document : getDocumentsByCorpusId(corpus.getId(), 0, requested)) {
                    total++;
                    if (skipped++ < boundedOffset) {
                        continue;
                    }
                    if (page.size() >= boundedLimit) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> documentArtifact = new java.util.LinkedHashMap<>();
                    documentArtifact.put("fsId", document.getId());
                    documentArtifact.put("typeCode", 2);
                    documentArtifact.put("typeName", "Document");
                    documentArtifact.put("artifactKind", "document");
                    documentArtifact.put("name", documentTitle(document));
                    documentArtifact.put("corpusFsId", corpus.getId());
                    documentArtifact.put("count", 1);
                    documentArtifact.put("cas", casJson(documentFs(document, false)));
                    documentArtifact.put("fs", documentFs(document, false));
                    page.add(documentArtifact);
                }
                if (hasMore) {
                    break;
                }
            }
            total = hasMore ? boundedOffset + page.size() + 1 : total;
        } else {
            List<Map<String, Object>> matching = duavizArtifacts().stream()
                    .filter(artifact -> Number.class.isAssignableFrom(artifact.get("typeCode").getClass()))
                    .filter(artifact -> ((Number) artifact.get("typeCode")).intValue() == typeCode)
                    .toList();
            page.addAll(matching.stream()
                    .skip(boundedOffset)
                    .limit(boundedLimit)
                    .toList());
            total = matching.size();
            hasMore = boundedOffset + page.size() < total;
        }
        return Map.of(
                "instances", page,
                "offset", boundedOffset,
                "limit", boundedLimit,
                "total", total,
                "hasMore", hasMore,
                "cas", casJson(page.stream()
                        .map(artifact -> artifact.get("fs"))
                        .filter(Map.class::isInstance)
                        .map(fs -> (Map<String, Object>) fs)
                        .toList())
        );
    }

    public default Map<String, Object> duavizGraph(long rootFsId, int depth, int limit) throws DatabaseOperationException, DocumentAccessDeniedException {
        Corpus corpus = getCorpusById(rootFsId);
        if (corpus == null) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        Map<String, Object> corpusNode = new java.util.LinkedHashMap<>(corpusFs(corpus));
        corpusNode.put("depth", 0);
        corpusNode.put("fsId", corpus.getId());
        corpusNode.put("typeCode", 1);
        corpusNode.put("typeName", "Corpus");
        corpusNode.put("artifactKind", "corpus");
        corpusNode.put("title", corpusTitle(corpus));
        corpusNode.put("uri", "corpus:" + corpus.getId());
        nodes.add(corpusNode);
        for (Document document : getDocumentsByCorpusId(corpus.getId(), 0, boundedLimit)) {
            Map<String, Object> documentNode = new java.util.LinkedHashMap<>(documentFs(document, false));
            documentNode.put("depth", 1);
            documentNode.put("fsId", document.getId());
            documentNode.put("typeCode", 2);
            documentNode.put("typeName", "Document");
            documentNode.put("artifactKind", "document");
            documentNode.put("title", documentTitle(document));
            documentNode.put("uri", document.getDocumentId() == null ? "" : document.getDocumentId());
            nodes.add(documentNode);
            Map<String, Object> edge = new java.util.LinkedHashMap<>();
            edge.put("sourceFsId", corpus.getId());
            edge.put("targetFsId", document.getId());
            edge.put("featureCode", 0);
            edge.put("featureName", "documents");
            edge.put("edgeKind", "CONTAINS");
            edge.put("ordinal", edges.size());
            edge.put("source", corpus.getId());
            edge.put("target", document.getId());
            edge.put("feature", "org.texttechnologylab.annotations.dua.Corpus:documents");
            edges.add(edge);
        }
        return Map.of("nodes", nodes, "edges", edges, "cas", casJson(nodes));
    }

    public default Map<String, Object> duavizFs(long fsId) throws DatabaseOperationException, DocumentAccessDeniedException {
        Corpus corpus = getCorpusById(fsId);
        if (corpus != null) {
            Map<String, Object> fs = corpusFs(corpus);
            return Map.of("fs", fs, "cas", casJson(fs));
        }
        Document document = getDocumentById(fsId);
        if (document != null) {
            Map<String, Object> fs = documentFs(document, true);
            return Map.of("fs", fs, "cas", casJson(fs));
        }
        return Map.of("fs", Map.of(), "cas", casJson(List.of()));
    }

    public default Map<String, Object> duavizDocument(long documentFsId, List<Integer> typeCodes, int limit) throws DatabaseOperationException, DocumentAccessDeniedException {
        Document document = getDocumentById(documentFsId);
        if (document == null) {
            return Map.of("document", Map.of(), "annotationTypes", List.of(), "spans", List.of());
        }
        Map<String, Object> header = documentFs(document, true);
        header.put("fsId", document.getId());
        header.put("artifactKind", "document");
        header.put("title", documentTitle(document));
        header.put("uri", document.getDocumentId() == null ? "" : document.getDocumentId());
        header.put("typeCode", 2);
        header.put("typeName", "Document");
        header.put("sofaFsId", document.getId());
        header.put("viewName", "_InitialView");
        header.put("mimeType", document.getMimeType() == null ? "" : document.getMimeType());
        header.put("text", document.getFullText() == null ? "" : document.getFullText());
        return Map.of("document", header, "annotationTypes", List.of(), "spans", List.of(), "cas", casJson(header));
    }

    public default Map<String, Object> casJson(Map<String, Object>... featureStructures) {
        return casJson(java.util.Arrays.asList(featureStructures));
    }

    public default Map<String, Object> casJson(List<Map<String, Object>> featureStructures) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("format", "UIMA-CAS-JSON");
        result.put("views", Map.of("_InitialView", Map.of("members", featureStructures.stream().map(fs -> fs.get("_id")).toList())));
        result.put("types", List.of(
                typeSchema("org.texttechnologylab.annotations.dua.Artifact", "uima.cas.TOP", List.of("title", "uri", "metadata")),
                typeSchema("org.texttechnologylab.annotations.dua.Corpus", "org.texttechnologylab.annotations.dua.Artifact", List.of("documents")),
                typeSchema("org.texttechnologylab.annotations.dua.Document", "org.texttechnologylab.annotations.dua.Artifact", List.of("origin", "mimeType", "text"))
        ));
        result.put("featureStructures", featureStructures);
        return result;
    }

    public default Map<String, Object> typeSchema(String name, String superType, List<String> features) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("name", name);
        schema.put("superType", superType);
        schema.put("features", features);
        return schema;
    }

    public default Map<String, Object> corpusFs(Corpus corpus) throws DatabaseOperationException, DocumentAccessDeniedException {
        Map<String, Object> fs = new java.util.LinkedHashMap<>();
        fs.put("_id", corpus.getId());
        fs.put("_type", "org.texttechnologylab.annotations.dua.Corpus");
        fs.put("features", Map.of(
                "title", corpusTitle(corpus),
                "uri", "corpus:" + corpus.getId(),
                "metadata", "",
                "documents", getDocumentsByCorpusId(corpus.getId(), 0, 1000).stream().map(Document::getId).toList()
        ));
        return fs;
    }

    public default Map<String, Object> documentFs(Document document, boolean includeText) {
        Map<String, Object> features = new java.util.LinkedHashMap<>();
        features.put("title", documentTitle(document));
        features.put("uri", document.getDocumentId() == null ? "" : document.getDocumentId());
        features.put("metadata", "");
        features.put("origin", document.getDocumentId() == null ? "" : document.getDocumentId());
        features.put("mimeType", document.getMimeType() == null ? "" : document.getMimeType());
        if (includeText) {
            features.put("text", document.getFullText() == null ? "" : document.getFullText());
        }
        Map<String, Object> fs = new java.util.LinkedHashMap<>();
        fs.put("_id", document.getId());
        fs.put("_type", "org.texttechnologylab.annotations.dua.Document");
        fs.put("features", features);
        return fs;
    }

    public default String corpusTitle(Corpus corpus) {
        return corpus.getName() == null || corpus.getName().isBlank() ? "Corpus " + corpus.getId() : corpus.getName();
    }

    public default String documentTitle(Document document) {
        return document.getDocumentTitle() == null || document.getDocumentTitle().isBlank()
                ? (document.getDocumentId() == null || document.getDocumentId().isBlank() ? "Document " + document.getId() : document.getDocumentId())
                : document.getDocumentTitle();
    }

    public default Map<String, Object> duavizUpsertAnnotation(long fsId, long sofaFsId, long documentFsId,
                                                              String viewName, int typeCode, long begin, long end,
                                                              Map<String, Object> features)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of("fsId", fsId, "status", "unavailable");
    }

    public default Map<String, Object> duavizDelete(long fsId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of("fsId", fsId, "status", "unavailable");
    }

    public default Map<String, Object> duavizRestore(long fsId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of("fsId", fsId, "status", "unavailable");
    }

    public default Map<String, Object> duavizTelemetrySchema() throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry schema not configured for this backend."
        );
    }

    public default Map<String, Object> duavizTelemetrySummaries(String component, String operation)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry summaries not configured for this backend.",
                "summaries", List.of()
        );
    }

    public default Map<String, Object> duavizTelemetryObservations(String component, String operation, Boolean failed, int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry observations not configured for this backend.",
                "observations", List.of()
        );
    }

    public default Map<String, Object> duavizTelemetryHealth()
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry health not configured for this backend."
        );
    }

    public default Map<String, Object> duavizTelemetryDiagnostics()
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry diagnostics not configured for this backend."
        );
    }

    public default Map<String, Object> duavizTelemetryOperations(String component)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry operations not configured for this backend.",
                "operations", List.of(),
                "components", List.of()
        );
    }

    public default Map<String, Object> duavizTelemetryProgress(String component, String operation, Integer windowMinutes, int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry progress not configured for this backend.",
                "points", List.of()
        );
    }

    public default Map<String, Object> duavizTelemetryOverview()
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry overview not configured for this backend."
        );
    }

    public default Map<String, Object> duavizTelemetryAttributes(String component, String operation)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return duavizTelemetryAttributes(component, operation, null);
    }

    public default Map<String, Object> duavizTelemetryAttributes(String component, String operation, Integer topValues)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of(
                "status", "unavailable",
                "message", "DUA telemetry attributes not configured for this backend.",
                "attributes", List.of()
        );
    }

    /**
     * Fetches annotations (NE, Taxon, Time,...) of a given corpus.
     * @throws DocumentAccessDeniedException 
     */
    public List<AnnotationSearchResult> getAnnotationsOfCorpus(long corpusId, int skip, int take) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns all biofidurls (if any) of biofidtaxon that match the given string values.
     * @throws DocumentAccessDeniedException 
     */
    public List<String> getIdentifiableTaxonsByValue(String token) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Counts all documents within a given corpus
     * @throws DocumentAccessDeniedException 
     */
    public int countDocumentsInCorpus(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public int countPagesInCorpus(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns true if the document with the given documentId exists in
     * the given corpus
     * @throws DocumentAccessDeniedException 
     */
    public boolean documentExists(long corpusId, String documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a single corpus by its id.
     * @throws DocumentAccessDeniedException 
     */
    public Corpus getCorpusById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores a page topic distribution by a page.
     * @throws DocumentAccessDeniedException 
     */
    public void savePageKeywordDistribution(Page page) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores a document topic distributions by a document.
     * @throws DocumentAccessDeniedException 
     */
    public void saveDocumentKeywordDistribution(Document document) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns a corpus by name. As they aren't unique, it returns the first match.
     * @throws DocumentAccessDeniedException 
     */
    public Corpus getCorpusByName(String name) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets all UCE filters of a corpus.
     * @throws DocumentAccessDeniedException 
     *
     */
    public List<UCEMetadataFilter> getUCEMetadataFiltersByCorpusId(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets all documents that belong to the given corpus
     * @throws DocumentAccessDeniedException 
     *
     */
    public List<Document> getDocumentsByCorpusId(long corpusId, int skip, int take) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets all DocumentLinks that belong to a document.
     * @throws DocumentAccessDeniedException 
     */
    public List<DocumentLink> getManyDocumentLinksOfDocument(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Get all DocumentLinks of a corpus that have either 'from' or 'to' as its documentId
     * @throws DocumentAccessDeniedException 
     */
    public List<DocumentLink> getManyDocumentLinksByDocumentId(String documentId, long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets all documents of a corpus which aren't post-processed yet.
     * @throws DocumentAccessDeniedException 
     */
    public List<Document> getNonePostprocessedDocumentsByCorpusId(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns a corpus tsne plot by the given corpusId
     * @throws DocumentAccessDeniedException 
     */
    public CorpusTsnePlot getCorpusTsnePlotByCorpusId(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets all corpora from the database
     * @throws DocumentAccessDeniedException 
     */
    public List<Corpus> getAllCorpora() throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets the data required for the world globus to render correctly.
     * @throws DocumentAccessDeniedException 
     */
    public List<GlobeTaxon> getGlobeDataForDocument(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<TopicWord> getNormalizedTopicWordsForCorpus(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public Map<String, Double> getTopNormalizedTopicsByCorpusId(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets many documents by their ids
     * @throws DocumentAccessDeniedException 
     */
    public List<Document> getManyDocumentsByIds(List<Long> documentIds) throws DatabaseOperationException, DocumentAccessDeniedException;

    public default List<Document> getManyDocumentsByIds(List<Long> documentIds, java.util.Set<String> hibernateInit) throws DatabaseOperationException, DocumentAccessDeniedException {
        return getManyDocumentsByIds(documentIds);
    }

    public boolean hasDocumentAccess(String principal,
                                     long documentId,
                                     DocumentPermission.DOCUMENT_PERMISSION_LEVEL level)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public Map<Long, Boolean> hasDocumentAccess(String principal,
                                                List<Long> documentIds,
                                                DocumentPermission.DOCUMENT_PERMISSION_LEVEL level)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public void calculateEffectivePermissions(String username, java.util.Set<String> groups)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns a list of lexicon entries depending on the parameters.
     * @throws DocumentAccessDeniedException 
     */
    public List<LexiconEntry> getManyLexiconEntries(int skip, int take, List<String> alphabet,
                                                    List<String> annotationFilters, String sortColumn,
                                                    String sortOrder, String searchInput) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Does a semantic role label search and returns document hits
     * @throws DocumentAccessDeniedException 
     */
    public DocumentSearchResult semanticRoleSearchForDocuments(
            int skip,
            int take,
            List<String> arg0,
            List<String> arg1,
            List<String> arg2,
            List<String> argm,
            String verb,
            boolean countAll,
            SearchOrder order,
            OrderByColumn orderedByColumn,
            long corpusId
    ) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Does a negation search and returns document hits
     * @throws DocumentAccessDeniedException 
     */
    public DocumentSearchResult completeNegationSearchForDocuments(int skip,
                                                                   int take,
                                                                   List<String> cue,
                                                                   List<String> event,
                                                                   List<String> focus,
                                                                   List<String> scope,
                                                                   List<String> xscope,
                                                                   boolean countAll,
                                                                   SearchOrder order,
                                                                   OrderByColumn orderedByColumn,
                                                                   long corpusId,
                                                                   List<UCEMetadataFilterDto> filters)
        throws DatabaseOperationException, DocumentAccessDeniedException;
    /**
     * Searches for documents with a variety of criterias. It's the main db search of the biofid portal
     * The function calls a variety of stored procedures in the database.
     *
     * @param skip
     * @param take
     * @return
     * @throws DocumentAccessDeniedException 
     */
    public DocumentSearchResult defaultSearchForDocuments(int skip,
                                                          int take,
                                                          String ogSearchQuery,
                                                          List<String> searchTokens,
                                                          SearchLayer layer,
                                                          boolean countAll,
                                                          SearchOrder order,
                                                          OrderByColumn orderedByColumn,
                                                          long corpusId,
                                                          List<UCEMetadataFilterDto> uceMetadataFilters,
                                                          boolean useTsVectorSearch,
                                                          String schema,
                                                          String sourceTable,
                                                          List<String> expandedTerms
                                                          ) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a Topic Distribution determined by the T generic inheritance.
     *
     * @param clazz
     * @param id
     * @param <T>
     * @return
     * @throws DatabaseOperationException
     * @throws DocumentAccessDeniedException 
     */
    public <T extends KeywordDistribution> T getKeywordDistributionById(Class<T> clazz, long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Get Keyword Distributions by a keyword. This is basically a search for annotated keywords.
     *
     * @param clazz
     * @param topic
     * @param <T>
     * @return
     * @throws DatabaseOperationException
     * @throws DocumentAccessDeniedException 
     */
    public <T extends KeywordDistribution> List<T> getKeywordDistributionsByString(Class<T> clazz, String topic, int limit) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a document by its corpusId and the documentId, which isn't its primary key identifier "id".
     * @throws DocumentAccessDeniedException 
     * @throws NumberFormatException 
     */
    public Document getDocumentByCorpusAndDocumentId(long corpusId, String documentId) throws DatabaseOperationException, NumberFormatException, DocumentAccessDeniedException;

    public List<UCEMetadata> getUCEMetadataByDocumentId(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a single UCEImport object from the database.
     * @throws DocumentAccessDeniedException 
     */
    public UCEImport getUceImportByImportId(String importId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Generic operation that fetches documents given the parameters
     * @throws DocumentAccessDeniedException 
     */
    public Document getDocumentById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public default Document getDocumentById(long id, java.util.Set<String> hibernateInit) throws DatabaseOperationException, DocumentAccessDeniedException {
        return getDocumentById(id);
    }

    public default Document getFirstDocumentByTitle(String title, boolean like) throws DatabaseOperationException, DocumentAccessDeniedException {
        return null;
    }

    public default List<Long> findDocumentIDsByTitle(String title, boolean like) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Long> findDocumentIdsByMetadata(String key, String value, UCEMetadataValueType valueType) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default void deleteDocumentById(long id) throws DatabaseOperationException, DocumentAccessDeniedException {
    }

    /**
     * Gets a fully initialized page by its id.
     * @throws DocumentAccessDeniedException 
     */
    public Page getPageById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a page by its documentid and whether the begin and end is in the page's begin and end.
     * @throws DocumentAccessDeniedException 
     */
    public Page getPageByDocumentIdAndBeginEnd(long documentId, int begin, int end, boolean initialize) throws DatabaseOperationException, DocumentAccessDeniedException;

    public Linkable getLinkable(long id, Class<? extends Linkable> clazz) throws DatabaseOperationException, DocumentAccessDeniedException;

    public Linkable getLinkable(long id, String className) throws ClassNotFoundException, DatabaseOperationException, DocumentAccessDeniedException;

    public List<Link> getAllLinksOfLinkable(long id,
                                            Class<? extends Linkable> linkableType,
                                            List<Class<? extends ModelBase>> possibleLinkTypes)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<UIMAAnnotation> getManyUIMAAnnotationsByCoveredText(String coveredText,
                                                                    Class<? extends UIMAAnnotation> clazz,
                                                                    int skip,
                                                                    int take)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<PointDto> getGeonameTimelineLinks(double minLng,
                                                  double minLat,
                                                  double maxLng,
                                                  double maxLat,
                                                  java.sql.Date fromDate,
                                                  java.sql.Date toDate,
                                                  long corpusId,
                                                  int skip,
                                                  int take,
                                                  String fromAnnotationTypeTable)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<MapClusterDto> getGeonameClustersFromTimelineMap(double minLng,
                                                                 double minLat,
                                                                 double maxLng,
                                                                 double maxLat,
                                                                 double gridSize,
                                                                 java.sql.Date fromDate,
                                                                 java.sql.Date toDate,
                                                                 long corpusId)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<String> getDistinctTimeCoveredTexts(String unitName,
                                                    String value,
                                                    Long fromYear,
                                                    Long toYear,
                                                    long corpusId,
                                                    int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<String> getDistinctGeonamesNamesByFeatureCode(GeoNameFeatureClass featureClass,
                                                              String featureCode,
                                                              long corpusId,
                                                              int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<String> getDistinctGeonamesNamesByRadius(double longitude,
                                                         double latitude,
                                                         double radius,
                                                         long corpusId,
                                                         int limit)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets the corresponding gbifOccurrences to a gbifTaxonId
     * @throws DocumentAccessDeniedException 
     */
    public List<GbifOccurrence> getGbifOccurrencesByGbifTaxonId(long gbifTaxonId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a list of distinct documents that contain a named entity with a given covered text.
     * @param annotationName Either "namedEntities", "times", "sentences". It's the **list name** of the annotations within a Document objects.
     * @throws DocumentAccessDeniedException 
     */
    public List<Document> getDocumentsByAnnotationCoveredText(String coveredText, int limit, String annotationName) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets lemmas from a specific document that are within a begin and end range
     * @throws DocumentAccessDeniedException 
     *
     */
    public List<Lemma> getLemmasWithinBeginAndEndOfDocument(int begin, int end, long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a GeoName annotation by its unique id.
     * @throws DocumentAccessDeniedException 
     */
    public GeoName getGeoNameAnnotationById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a time annotation by its id
     * @throws DocumentAccessDeniedException 
     */
    public Time getTimeAnnotationById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Returns a sentence annotation by its id.
     * @throws DocumentAccessDeniedException 
     */
    public Sentence getSentenceAnnotationById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public CompleteNegation getCompleteNegationByCueId(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public default CompleteNegation getCompleteNegationById(long id) throws DatabaseOperationException, DocumentAccessDeniedException {
        return null;
    }

    public UnifiedTopic getInitializedUnifiedTopicById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Counts the entries in the lexicon
     * @throws DocumentAccessDeniedException 
     */
    public long countLexiconEntries() throws DatabaseOperationException, DocumentAccessDeniedException;

    public int refreshLexicon(List<String> annotationTables, boolean forceRecalculate) throws DatabaseOperationException, DocumentAccessDeniedException;

    public int refreshLogicalLinks() throws DatabaseOperationException, DocumentAccessDeniedException;

    public int refreshGeonameLocations() throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a Lexicon entry by its composite id.
     * @throws DocumentAccessDeniedException 
     */
    public LexiconEntry getLexiconEntryId(LexiconEntryId id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a named entity by its id
     * @throws DocumentAccessDeniedException 
     */
    public NamedEntity getNamedEntityById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public GazetteerTaxon getGazetteerTaxonById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public GnFinderTaxon getGnFinderTaxonById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a single taxon by its id
     * @throws DocumentAccessDeniedException 
     */
    public BiofidTaxon getBiofidTaxonById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a lemma by its id
     * @throws DocumentAccessDeniedException 
     */
    public Lemma getLemmaById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Given a string value, return a list of lemmas that match that value.
     * @throws DocumentAccessDeniedException 
     */
    public List<Lemma> getLemmasByValue(String covered, int limit, long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public boolean checkIfGbifOccurrencesExist(long gbifTaxonId) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Gets a complete document, alongside its lists, from the database.
     * @throws DocumentAccessDeniedException 
     */
    public Document getCompleteDocumentById(long id, int skipPages, int pageLimit) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves or updates an ImportLog belonging to a UCEImport.
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateImportLog(ImportLog importLog) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves or updates a UCEImport object.
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateUceImport(UCEImport uceImport) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves and updates a filter.
     * @throws DocumentAccessDeniedException 
     *
     */
    public void saveOrUpdateUCEMetadataFilter(UCEMetadataFilter filter) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores a new UCEMetadataFilter
     * @throws DocumentAccessDeniedException 
     *
     */
    public void saveUCEMetadataFilter(UCEMetadataFilter filter) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores the complete document with all its lists in the database.
     * @throws DocumentAccessDeniedException 
     *
     */
    public void saveDocument(Document document) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Updates a document
     * @throws DocumentAccessDeniedException 
     */
    public void updateDocument(Document document) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves a UCELog to the database. In those, we log requests from the user and more.
     *
     * @param log
     * @throws DatabaseOperationException
     * @throws DocumentAccessDeniedException 
     */
    public void saveUceLog(UCELog log) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores an corpus tsne plot instance
     *
     * @param corpusTsnePlot
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateCorpusTsnePlot(CorpusTsnePlot corpusTsnePlot, Corpus corpus) throws DatabaseOperationException, DocumentAccessDeniedException;

    public DocumentTopThreeTopics getDocumentTopThreeTopicsById(long id) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<Object[]> getTopTopicsByDocument(long documentId, int limit) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<Object[]> getTopDocumentsByTopicLabel(String topicValue, long corpusId, int limit) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<TopicWord> getTopicWordsByTopicLabel(String topicValue, long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<Object[]> getSimilarTopicsbyTopicLabel(String topicValue, long corpusId, int minSharedWords, int resultLimit) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<TopicWord> getDocumentWordDistribution(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public List<Object[]> getSimilarDocumentbyDocumentId(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException;

    public default void saveDocumentTopThreeTopics(Document document) throws DatabaseOperationException, DocumentAccessDeniedException {
        updateDocument(document);
    }

    public default List<Object[]> getTaxonValuesAndCountByPageId(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getNamedEntityValuesAndCountByPage(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getLemmaByPage(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getGeonameByPage(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getTopicDistributionByPageForDocument(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getSentenceTopicsWithEntitiesByPageForDocument(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default List<Object[]> getTopicWordsByDocumentId(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    public default Map<Long, Long> getUnifiedTopicToSentenceMap(long documentId) throws DatabaseOperationException, DocumentAccessDeniedException {
        return Map.of();
    }

    /**
     *  Saves or updates a list of documentLinks.
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateManyDocumentToAnnotationLinks(List<DocumentToAnnotationLink> links) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves or updates a list of annotation links.
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateManyAnnotationLinks(List<AnnotationLink> links) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves or updates a list of DocumentToAnnotation Links
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateManyAnnotationToDocumentLinks(List<AnnotationToDocumentLink> links) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Saves or updates a list of documentLinks.
     * @throws DocumentAccessDeniedException 
     */
    public void saveOrUpdateManyDocumentLinks(List<DocumentLink> documentLinks) throws DatabaseOperationException, DocumentAccessDeniedException;

    /**
     * Stores a corpus in the database.
     *
     * @param corpus
     * @throws DocumentAccessDeniedException 
     */
    public void saveCorpus(Corpus corpus) throws DatabaseOperationException, DocumentAccessDeniedException;
}
