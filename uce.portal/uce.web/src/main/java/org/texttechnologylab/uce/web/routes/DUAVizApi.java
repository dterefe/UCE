package org.texttechnologylab.uce.web.routes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import freemarker.template.Configuration;
import io.javalin.http.Context;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.texttechnologylab.uce.common.services.DataInterface;
import org.texttechnologylab.uce.web.freeMarker.AccessDeniedRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.channels.ClosedChannelException;

public class DUAVizApi implements UceApi {
    private static final Logger logger = LogManager.getLogger(DUAVizApi.class);

    private final DataInterface data;
    private final Gson gson = new Gson();

    public DUAVizApi(ApplicationContext context, Configuration configuration) {
        this.data = context.getBean(DataInterface.class);
    }

    public void view(Context ctx) {
        try {
            ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            ctx.header("Pragma", "no-cache");
            ctx.header("Expires", "0");
            ctx.redirect("/#view=duaviz");
        } catch (Exception ex) {
            logger.error("Error rendering DUAViz.", ex);
            ctx.render("defaultError.ftl");
        }
    }

    public void websocket(WsConfig ws) {
        ws.onConnect(ctx -> {
            ctx.enableAutomaticPings();
            ctx.send(gson.toJson(Map.of("type", "ready", "status", 200)));
        });
        ws.onMessage(ctx -> {
            Map<String, Object> query = message(ctx);
            String action = action(query);
            Object requestId = query.get("requestId");
            try {
                ctx.send(gson.toJson(queryResponse(requestId, action, query)));
            } catch (Exception ex) {
                logger.error("Error handling DUAViz websocket query '{}'.", action, ex);
                ctx.send(gson.toJson(Map.of(
                        "requestId", Objects.toString(requestId, ""),
                        "action", action,
                        "status", 500,
                        "message", "Error handling DUAViz websocket query."
                )));
            }
        });
        ws.onError(ctx -> {
            if (ctx.error() instanceof ClosedChannelException) {
                logger.debug("DUAViz websocket closed by client.", ctx.error());
                return;
            }
            logger.warn("DUAViz websocket error.", ctx.error());
        });
    }

    public void dummyWebsocket(WsConfig ws) {
        ws.onConnect(ctx -> {
            ctx.enableAutomaticPings();
            ctx.send(gson.toJson(Map.of("type", "ready", "status", 200, "dummy", true)));
        });
        ws.onMessage(ctx -> {
            Map<String, Object> query = message(ctx);
            String action = action(query);
            Object requestId = query.get("requestId");
            ctx.send(gson.toJson(dummyResponse(requestId, action, payload(query))));
        });
        ws.onError(ctx -> {
            if (ctx.error() instanceof ClosedChannelException) {
                logger.debug("Dummy DUAViz websocket closed by client.", ctx.error());
                return;
            }
            logger.warn("Dummy DUAViz websocket error.", ctx.error());
        });
    }

    private Map<String, Object> queryResponse(Object requestId, String action, Map<String, Object> query) throws Exception {
        Map<String, Object> payload = payload(query);
        return switch (action) {
            case "types" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "schema", data.duavizSchema()
            );
            case "instancesByType" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "page", data.duavizInstancesByType(
                            (int) number(payload.get("typeCode"), -1),
                            (int) number(payload.get("offset"), 0),
                            (int) number(payload.get("limit"), 50)
                    )
            );
            case "graph" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "graph", data.duavizGraph(
                            number(payload.get("rootFsId"), -1),
                            (int) number(payload.get("depth"), 3),
                            (int) number(payload.get("limit"), 400)
                    )
            );
            case "select" -> selectResponse(requestId, action, payload);
            case "span" -> spanResponse(requestId, action, query, payload);
            case "fs" -> fsResponse(requestId, action, payload);
            case "document" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "document", data.duavizDocument(
                            number(payload.get("documentFsId"), -1),
                            ints(payload.get("typeCodes")),
                            (int) number(payload.get("limit"), 2500)
                    )
            );
            case "annotation" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "annotation", data.duavizUpsertAnnotation(
                            number(payload.get("fsId"), -1),
                            number(payload.get("sofaFsId"), -1),
                            number(payload.get("documentFsId"), -1),
                            string(payload.get("viewName"), "_InitialView"),
                            (int) number(payload.get("typeCode"), -1),
                            number(payload.get("begin"), -1),
                            number(payload.get("end"), -1),
                            map(payload.get("features")))
            );
            case "delete" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "result", data.duavizDelete(number(payload.get("fsId"), -1))
            );
            case "restore" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "result", data.duavizRestore(number(payload.get("fsId"), -1))
            );
            case "telemetrySchema" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "schema", data.duavizTelemetrySchema()
            );
            case "telemetrySummaries" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "summaries", data.duavizTelemetrySummaries(
                            string(payload.get("component"), null),
                            string(payload.get("operation"), null)
                    )
            );
            case "telemetryObservations" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "observations", data.duavizTelemetryObservations(
                            string(payload.get("component"), null),
                            string(payload.get("operation"), null),
                            bool(payload.get("failed")),
                            (int) number(payload.get("limit"), 250)
                    )
            );
            case "telemetryHealth" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "health", data.duavizTelemetryHealth()
            );
            case "telemetryDiagnostics" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "diagnostics", data.duavizTelemetryDiagnostics()
            );
            case "telemetryOperations" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "operations", data.duavizTelemetryOperations(
                            string(payload.get("component"), null)
                    )
            );
            case "telemetryProgress" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "progress", data.duavizTelemetryProgress(
                            string(payload.get("component"), null),
                            string(payload.get("operation"), null),
                            payload.get("windowMinutes") == null ? null : (int) number(payload.get("windowMinutes"), 60),
                            (int) number(payload.get("limit"), 200)
                    )
            );
            case "telemetryOverview" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "overview", data.duavizTelemetryOverview()
            );
            case "telemetryAttributes" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "attributes", data.duavizTelemetryAttributes(
                            string(payload.get("component"), null),
                            string(payload.get("operation"), null),
                            payload.get("topValues") == null ? null : (int) number(payload.get("topValues"), 25)
                    )
            );
            default -> Map.of(
                    "requestId", Objects.toString(requestId, ""),
                    "action", action,
                    "status", 400,
                    "message", "Unknown DUAViz query action."
            );
        };
    }

    private Map<String, Object> dummyResponse(Object requestId, String action, Map<String, Object> payload) {
        return switch (action) {
            case "types" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "dummy", true,
                    "schema", dummySchema()
            );
            case "instancesByType" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "dummy", true,
                    "page", dummyPage((int) number(payload.get("typeCode"), -1))
            );
            case "select" -> {
                Map<String, Object> selection = dummySelect(payload);
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("requestId", requestId);
                response.put("action", action);
                response.put("op", action);
                response.put("status", 200);
                response.put("dummy", true);
                response.put("selection", selection);
                List<Long> fsRefs = fsRefs(selection);
                if (!fsRefs.isEmpty()) {
                    response.put("fsRefs", fsRefs);
                }
                yield response;
            }
            case "document", "span" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "op", action,
                    "status", 200,
                    "dummy", true,
                    "spans", dummyDocument(number(payload.get("documentFsId"), 200))
            );
            case "fs" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "op", action,
                    "status", 200,
                    "dummy", true,
                    "fs", dummyFs(number(payload.get("fsId"), number(payload.get("fsRef"), -1)))
            );
            case "graph" -> Map.of(
                    "requestId", requestId,
                    "action", action,
                    "status", 200,
                    "dummy", true,
                    "graph", Map.of("nodes", dummyPage(12).get("instances"), "edges", List.of())
            );
            default -> Map.of(
                    "requestId", Objects.toString(requestId, ""),
                    "action", action,
                    "status", 400,
                    "dummy", true,
                    "message", "Unknown dummy DUAViz query action."
            );
        };
    }

    private Map<String, Object> dummySelect(Map<String, Object> payload) {
        String select = string(payload.get("select"), "");
        if ("types".equals(select) || "schema".equals(select) || "*".equals(string(payload.get("type"), ""))) {
            return dummySchema();
        }
        int typeCode = (int) number(payload.get("typeCode"), -1);
        if (typeCode <= 0) {
            typeCode = dummyTypeCodeForName(string(payload.get("type"), ""));
        }
        return dummyPage(typeCode);
    }

    private Map<String, Object> dummySchema() {
        List<Map<String, Object>> types = new ArrayList<>();
        types.add(dummyType(10, 0, "org.texttechnologylab.annotations.dua.Artifact", "Artifact", true, false, 0, List.of("artifactId", "title", "uri")));
        types.add(dummyType(11, 10, "org.texttechnologylab.annotations.dua.Corpus", "Corpus", true, false, 3, List.of("artifactId", "title")));
        types.add(dummyType(12, 10, "org.texttechnologylab.annotations.dua.Document", "Document", true, false, 25, List.of("artifactId", "title", "text")));
        types.addAll(dummyAnnotationTypes());
        return Map.of(
                "format", "UIMA-CAS-JSON",
                "types", types,
                "featureStructures", List.of(),
                "views", Map.of("_InitialView", Map.of("members", java.util.stream.LongStream.rangeClosed(200, 224).boxed().toList()))
        );
    }

    private List<Map<String, Object>> dummyAnnotationTypes() {
        return List.of(
                dummyType(20, 0, "uima.tcas.Annotation", "Annotation", false, true, 34, List.of("begin", "end")),
                dummyType(21, 20, "org.apache.uima.jcas.tcas.Sentence", "Sentence", false, true, 4, List.of("begin", "end", "sentenceNo")),
                dummyType(22, 20, "org.apache.uima.jcas.tcas.Token", "Token", false, true, 10, List.of("begin", "end", "lemma", "pos")),
                dummyType(23, 22, "org.texttechnologylab.annotation.Lemma", "Lemma", false, true, 3, List.of("begin", "end", "value")),
                dummyType(24, 22, "org.texttechnologylab.annotation.POS", "POS", false, true, 3, List.of("begin", "end", "posValue")),
                dummyType(25, 20, "org.texttechnologylab.annotation.NamedEntity", "NamedEntity", false, true, 3, List.of("begin", "end", "value", "entityType")),
                dummyType(26, 25, "org.texttechnologylab.annotation.Person", "Person", false, true, 1, List.of("begin", "end", "value")),
                dummyType(27, 25, "org.texttechnologylab.annotation.Location", "Location", false, true, 1, List.of("begin", "end", "value")),
                dummyType(28, 25, "org.texttechnologylab.annotation.Organization", "Organization", false, true, 1, List.of("begin", "end", "value")),
                dummyType(29, 25, "org.texttechnologylab.annotation.biofid.gnfinder.Taxon", "Taxon", false, true, 1, List.of("begin", "end", "identifier", "value")),
                dummyType(30, 29, "org.texttechnologylab.annotation.biofid.gnfinder.VerifiedTaxon", "VerifiedTaxon", false, true, 1, List.of("begin", "end", "identifier", "score")),
                dummyType(31, 20, "org.texttechnologylab.annotation.Date", "Date", false, true, 1, List.of("begin", "end", "value")),
                dummyType(32, 20, "org.texttechnologylab.annotation.Time", "Time", false, true, 1, List.of("begin", "end", "value")),
                dummyType(33, 20, "org.texttechnologylab.annotation.Quantity", "Quantity", false, true, 1, List.of("begin", "end", "value", "unit")),
                dummyType(34, 33, "org.texttechnologylab.annotation.Measurement", "Measurement", false, true, 1, List.of("begin", "end", "value", "unit")),
                dummyType(35, 20, "org.texttechnologylab.annotation.Citation", "Citation", false, true, 1, List.of("begin", "end", "target")),
                dummyType(36, 35, "org.texttechnologylab.annotation.Reference", "Reference", false, true, 1, List.of("begin", "end", "target")),
                dummyType(37, 20, "org.texttechnologylab.annotation.structure.Paragraph", "Paragraph", false, true, 4, List.of("begin", "end", "index")),
                dummyType(38, 37, "org.texttechnologylab.annotation.structure.Heading", "Heading", false, true, 1, List.of("begin", "end", "level")),
                dummyType(39, 37, "org.texttechnologylab.annotation.structure.Section", "Section", false, true, 1, List.of("begin", "end", "title")),
                dummyType(40, 20, "org.texttechnologylab.annotation.structure.Table", "Table", false, true, 1, List.of("begin", "end", "caption")),
                dummyType(41, 20, "org.texttechnologylab.annotation.structure.Figure", "Figure", false, true, 1, List.of("begin", "end", "caption")),
                dummyType(42, 20, "org.texttechnologylab.annotation.structure.Caption", "Caption", false, true, 1, List.of("begin", "end", "target")),
                dummyType(43, 20, "org.texttechnologylab.annotation.structure.Footnote", "Footnote", false, true, 1, List.of("begin", "end", "marker")),
                dummyType(44, 20, "org.texttechnologylab.annotation.Keyword", "Keyword", false, true, 1, List.of("begin", "end", "value")),
                dummyType(45, 20, "org.texttechnologylab.annotation.Topic", "Topic", false, true, 1, List.of("begin", "end", "value", "score")),
                dummyType(46, 20, "org.texttechnologylab.annotation.Sentiment", "Sentiment", false, true, 1, List.of("begin", "end", "polarity", "score")),
                dummyType(47, 20, "org.texttechnologylab.annotation.Language", "Language", false, true, 1, List.of("begin", "end", "language")),
                dummyType(48, 20, "org.texttechnologylab.annotation.DocumentModification", "DocumentModification", false, true, 1, List.of("begin", "end", "user", "operation")),
                dummyType(49, 20, "org.texttechnologylab.annotation.Metadata", "Metadata", false, true, 1, List.of("begin", "end", "key", "value")),
                dummyType(50, 20, "org.texttechnologylab.annotation.AnnotationComment", "AnnotationComment", false, true, 1, List.of("begin", "end", "comment")),
                dummyType(51, 20, "org.texttechnologylab.annotation.Event", "Event", false, true, 1, List.of("begin", "end", "eventType")),
                dummyType(52, 20, "org.texttechnologylab.annotation.Relation", "Relation", false, true, 1, List.of("begin", "end", "source", "target")),
                dummyType(53, 20, "org.texttechnologylab.annotation.Dependency", "Dependency", false, true, 1, List.of("begin", "end", "governor", "dependent"))
        );
    }

    private Map<String, Object> dummyType(int typeCode, int superTypeCode, String name, String label,
                                          boolean artifact, boolean annotation, int instanceCount, List<String> features) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("typeCode", typeCode);
        row.put("superTypeCode", superTypeCode);
        row.put("superTypeName", "");
        row.put("name", name);
        row.put("label", label);
        row.put("artifact", artifact);
        row.put("annotation", annotation);
        row.put("instanceCount", instanceCount);
        row.put("features", java.util.stream.IntStream.range(0, features.size())
                .mapToObj(index -> Map.of("featureCode", typeCode * 1000 + index + 1, "name", features.get(index)))
                .toList());
        return row;
    }

    private Map<String, Object> dummyPage(int typeCode) {
        if (typeCode == 20 || (typeCode >= 21 && typeCode <= 53)) {
            List<Map<String, Object>> spans = dummyDocumentSpans(200);
            List<Map<String, Object>> instances = typeCode == 20
                    ? spans
                    : spans.stream().filter(row -> ((Number) row.get("typeCode")).intValue() == typeCode).toList();
            return Map.of("instances", instances, "offset", 0, "limit", 50, "total", instances.size(), "hasMore", false, "cas", casJson(instances.stream().map(row -> row.get("fs")).toList()));
        }
        List<Map<String, Object>> instances = switch (typeCode) {
            case 11 -> dummyCorpora();
            case 12 -> dummyDocuments();
            default -> List.of();
        };
        return Map.of("instances", instances, "offset", 0, "limit", 50, "total", instances.size(), "hasMore", false, "cas", casJson(instances.stream().map(row -> row.get("fs")).toList()));
    }

    private List<Map<String, Object>> dummyCorpora() {
        return List.of(
                dummyInstance(100, 11, "org.texttechnologylab.annotations.dua.Corpus", "corpus", "Wetland Survey Corpus", Map.of("artifactId", "artifact:corpus:100", "title", "Wetland Survey Corpus", "documentCount", 9)),
                dummyInstance(101, 11, "org.texttechnologylab.annotations.dua.Corpus", "corpus", "Herbarium Index Corpus", Map.of("artifactId", "artifact:corpus:101", "title", "Herbarium Index Corpus", "documentCount", 8)),
                dummyInstance(102, 11, "org.texttechnologylab.annotations.dua.Corpus", "corpus", "Urban Biodiversity Corpus", Map.of("artifactId", "artifact:corpus:102", "title", "Urban Biodiversity Corpus", "documentCount", 8))
        );
    }

    private List<Map<String, Object>> dummyDocuments() {
        return java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> {
                    long fsId = 200L + index;
                    String title = dummyDocumentTitle(fsId);
                    long corpusId = index < 9 ? 100L : (index < 17 ? 101L : 102L);
                    return dummyInstance(fsId, 12, "org.texttechnologylab.annotations.dua.Document", "document", title,
                            Map.of("artifactId", "artifact:document:" + fsId, "title", title, "uri", "dummy://document/" + fsId, "corpusId", corpusId));
                })
                .toList();
    }

    private Map<String, Object> dummyInstance(long fsId, int typeCode, String typeName, String kind, String name, Map<String, Object> features) {
        Map<String, Object> fs = new java.util.LinkedHashMap<>();
        fs.put("_id", fsId);
        fs.put("_type", typeName);
        fs.put("typeCode", typeCode);
        fs.put("features", features);
        return Map.of("fsId", fsId, "typeCode", typeCode, "typeName", typeName, "artifactKind", kind, "name", name, "count", 1, "fs", fs, "cas", casJson(List.of(fs)));
    }

    private Map<String, Object> dummyDocument(long documentFsId) {
        int index = Math.max(0, Math.min(24, (int) documentFsId - 200));
        String taxon = dummyTaxa().get(index);
        String place = dummyPlaces().get(index);
        String curator = List.of("Curator Mueller", "Dr. Weber", "Archive Team North", "Field Unit 7", "BioFID reviewer").get(index % 5);
        String text = String.join("\n\n",
                "The " + taxon + " record came from the " + place + " during a late spring survey of the BioFID demonstration corpus. The entry was copied from a field notebook, normalized into a CAS-shaped document artifact, and linked to the stable dummy document identifier " + documentFsId + ".",
                "The observation describes a moist edge habitat with several companion species, repeated collection notes, and a short curator comment by " + curator + ". The wording is intentionally long enough to exercise scrolling, span overlays, annotation instance selection, and feature-panel inspection without relying on the external DUA service.",
                "A second paragraph repeats the relevant biological signal in prose: " + taxon + " was recorded near the " + place + ", the locality label was preserved as a named entity, and the taxonomic mention is available as both a NamedEntity and a Taxon feature structure. This gives the highlighter and pointer components enough text distance to display overlays across multiple sentences.",
                "The final paragraph acts as filler text for reader layout testing. It includes archive context, collection provenance, dummy quality-control wording, and a closing note that the record should remain visible as a full document when the dummy endpoint is selected."
        );
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("fsId", documentFsId);
        document.put("typeCode", 12);
        document.put("typeName", "org.texttechnologylab.annotations.dua.Document");
        document.put("artifactKind", "document");
        document.put("title", dummyDocumentTitle(documentFsId));
        document.put("viewName", "_InitialView");
        document.put("text", text);
        List<Map<String, Object>> spans = dummyDocumentSpans(documentFsId, text, taxon, place, curator, index);
        Map<Integer, Long> typeCounts = spans.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> ((Number) row.get("typeCode")).intValue(),
                java.util.stream.Collectors.counting()
        ));
        List<Map<String, Object>> annotationTypes = dummyAnnotationTypes().stream()
                .filter(type -> ((Number) type.get("typeCode")).intValue() != 20)
                .map(type -> Map.of(
                        "typeCode", type.get("typeCode"),
                        "typeName", type.get("name"),
                        "label", type.get("label"),
                        "count", typeCounts.getOrDefault(((Number) type.get("typeCode")).intValue(), 0L),
                        "features", type.get("features")
                ))
                .toList();
        return Map.of(
                "document", document,
                "views", List.of(Map.of("name", "_InitialView", "defaultView", true, "spanCount", spans.size())),
                "selectedView", "_InitialView",
                "annotationTypes", annotationTypes,
                "spans", spans,
                "cas", casJson(spans.stream().map(row -> row.get("fs")).toList())
        );
    }

    private List<Map<String, Object>> dummyDocumentSpans(long documentFsId) {
        int index = Math.max(0, Math.min(24, (int) documentFsId - 200));
        String taxon = dummyTaxa().get(index);
        String place = dummyPlaces().get(index);
        String curator = List.of("Curator Mueller", "Dr. Weber", "Archive Team North", "Field Unit 7", "BioFID reviewer").get(index % 5);
        String text = String.join("\n\n",
                "The " + taxon + " record came from the " + place + " during a late spring survey of the BioFID demonstration corpus. The entry was copied from a field notebook, normalized into a CAS-shaped document artifact, and linked to the stable dummy document identifier " + documentFsId + ".",
                "The observation describes a moist edge habitat with several companion species, repeated collection notes, and a short curator comment by " + curator + ". The wording is intentionally long enough to exercise scrolling, span overlays, annotation instance selection, and feature-panel inspection without relying on the external DUA service.",
                "A second paragraph repeats the relevant biological signal in prose: " + taxon + " was recorded near the " + place + ", the locality label was preserved as a named entity, and the taxonomic mention is available as both a NamedEntity and a Taxon feature structure. This gives the highlighter and pointer components enough text distance to display overlays across multiple sentences.",
                "The final paragraph acts as filler text for reader layout testing. It includes archive context, collection provenance, dummy quality-control wording, and a closing note that the record should remain visible as a full document when the dummy endpoint is selected."
        );
        return dummyDocumentSpans(documentFsId, text, taxon, place, curator, index);
    }

    private List<Map<String, Object>> dummyDocumentSpans(long documentFsId, String text, String taxon, String place, String curator, int index) {
        List<Map<String, Object>> spans = new ArrayList<>();
        int sentenceEnd = text.indexOf('.') + 1;
        spans.add(dummySpan(300, documentFsId, 21, "org.apache.uima.jcas.tcas.Sentence", 0, sentenceEnd, text.substring(0, sentenceEnd), Map.of()));
        spans.add(dummySpan(301, documentFsId, 21, "org.apache.uima.jcas.tcas.Sentence", sentenceEnd + 1, text.length(), text.substring(sentenceEnd + 1), Map.of()));
        spans.add(namedSpan(302, documentFsId, 37, "org.texttechnologylab.annotation.structure.Paragraph", text, text.split("\\n\\n")[0], Map.of("index", 1)));
        spans.add(namedSpan(303, documentFsId, 37, "org.texttechnologylab.annotation.structure.Paragraph", text, text.split("\\n\\n")[1], Map.of("index", 2)));
        spans.add(namedSpan(400, documentFsId, 22, "org.apache.uima.jcas.tcas.Token", text, taxon.split(" ")[0], Map.of("lemma", taxon.split(" ")[0].toLowerCase(), "pos", "NN")));
        spans.add(namedSpan(401, documentFsId, 23, "org.texttechnologylab.annotation.Lemma", text, "record", Map.of("value", "record")));
        spans.add(namedSpan(402, documentFsId, 24, "org.texttechnologylab.annotation.POS", text, "record", Map.of("posValue", "NN")));
        spans.add(namedSpan(410, documentFsId, 25, "org.texttechnologylab.annotation.NamedEntity", text, taxon, Map.of("value", taxon, "entityType", "taxon")));
        spans.add(namedSpan(411, documentFsId, 25, "org.texttechnologylab.annotation.NamedEntity", text, place, Map.of("value", place, "entityType", "location")));
        spans.add(namedSpan(412, documentFsId, 25, "org.texttechnologylab.annotation.NamedEntity", text, curator, Map.of("value", curator, "entityType", "person")));
        spans.add(namedSpan(413, documentFsId, 26, "org.texttechnologylab.annotation.Person", text, curator, Map.of("value", curator)));
        spans.add(namedSpan(414, documentFsId, 27, "org.texttechnologylab.annotation.Location", text, place, Map.of("value", place)));
        spans.add(namedSpan(415, documentFsId, 28, "org.texttechnologylab.annotation.Organization", text, "BioFID", Map.of("value", "BioFID")));
        spans.add(namedSpan(500, documentFsId, 29, "org.texttechnologylab.annotation.biofid.gnfinder.Taxon", text, taxon, Map.of("identifier", "gbif:" + (2725000 + index), "value", taxon)));
        spans.add(namedSpan(501, documentFsId, 30, "org.texttechnologylab.annotation.biofid.gnfinder.VerifiedTaxon", text, taxon, Map.of("identifier", "gbif:" + (2725000 + index), "score", 0.98)));
        spans.add(namedSpan(520, documentFsId, 31, "org.texttechnologylab.annotation.Date", text, "late spring", Map.of("value", "late spring")));
        spans.add(namedSpan(521, documentFsId, 32, "org.texttechnologylab.annotation.Time", text, "late spring", Map.of("value", "seasonal")));
        spans.add(namedSpan(522, documentFsId, 33, "org.texttechnologylab.annotation.Quantity", text, "identifier " + documentFsId, Map.of("value", documentFsId, "unit", "fsId")));
        spans.add(namedSpan(523, documentFsId, 34, "org.texttechnologylab.annotation.Measurement", text, "multiple sentences", Map.of("value", 4, "unit", "sentences")));
        spans.add(namedSpan(524, documentFsId, 35, "org.texttechnologylab.annotation.Citation", text, "field notebook", Map.of("target", "dummy-field-notebook")));
        spans.add(namedSpan(525, documentFsId, 36, "org.texttechnologylab.annotation.Reference", text, "stable dummy document identifier", Map.of("target", documentFsId)));
        spans.add(namedSpan(526, documentFsId, 40, "org.texttechnologylab.annotation.structure.Table", text, "feature-panel inspection", Map.of("caption", "Feature panel check")));
        spans.add(namedSpan(527, documentFsId, 41, "org.texttechnologylab.annotation.structure.Figure", text, "span overlays", Map.of("caption", "Overlay example")));
        spans.add(namedSpan(528, documentFsId, 42, "org.texttechnologylab.annotation.structure.Caption", text, "reader layout testing", Map.of("target", "reader")));
        spans.add(namedSpan(529, documentFsId, 43, "org.texttechnologylab.annotation.structure.Footnote", text, "dummy endpoint", Map.of("marker", "*")));
        spans.add(namedSpan(530, documentFsId, 44, "org.texttechnologylab.annotation.Keyword", text, "CAS-shaped", Map.of("value", "CAS")));
        spans.add(namedSpan(531, documentFsId, 45, "org.texttechnologylab.annotation.Topic", text, "biological signal", Map.of("value", "biodiversity", "score", 0.86)));
        spans.add(namedSpan(532, documentFsId, 46, "org.texttechnologylab.annotation.Sentiment", text, "quality-control", Map.of("polarity", "neutral", "score", 0.51)));
        spans.add(namedSpan(533, documentFsId, 47, "org.texttechnologylab.annotation.Language", text, "document artifact", Map.of("language", "en")));
        spans.add(namedSpan(534, documentFsId, 48, "org.texttechnologylab.annotation.DocumentModification", text, "normalized", Map.of("user", "dummy-importer", "operation", "normalize")));
        spans.add(namedSpan(535, documentFsId, 49, "org.texttechnologylab.annotation.Metadata", text, "provenance", Map.of("key", "source", "value", "dterefe-example")));
        spans.add(namedSpan(536, documentFsId, 50, "org.texttechnologylab.annotation.AnnotationComment", text, "should remain visible", Map.of("comment", "dummy view check")));
        spans.add(namedSpan(537, documentFsId, 51, "org.texttechnologylab.annotation.Event", text, "survey", Map.of("eventType", "collection")));
        spans.add(namedSpan(538, documentFsId, 52, "org.texttechnologylab.annotation.Relation", text, "linked", Map.of("source", 410, "target", 414)));
        spans.add(namedSpan(539, documentFsId, 53, "org.texttechnologylab.annotation.Dependency", text, "recorded", Map.of("governor", "recorded", "dependent", taxon)));
        spans.removeIf(Map::isEmpty);
        return spans;
    }

    private Map<String, Object> namedSpan(long fsId, long documentFsId, int typeCode, String typeName, String text, String coveredText, Map<String, Object> extraFeatures) {
        int begin = text.indexOf(coveredText);
        if (begin < 0) {
            return Map.of();
        }
        return dummySpan(fsId, documentFsId, typeCode, typeName, begin, begin + coveredText.length(), coveredText, extraFeatures);
    }

    private String dummyDocumentTitle(long documentFsId) {
        int index = Math.max(0, Math.min(24, (int) documentFsId - 200));
        return "Document " + documentFsId + " - " + List.of(
                "Marsh sample with Carex riparia",
                "Herbarium note for Salix alba",
                "Fen transect with Iris pseudacorus",
                "Riverbank survey near Berlin",
                "Peat meadow record with Juncus effusus",
                "Pond edge observation of Lemna minor",
                "Dune grassland sample with Festuca rubra",
                "Woodland margin note for Quercus robur",
                "Orchard biodiversity log with Malus domestica",
                "Historic sheet for Betula pendula",
                "Collection card for Pinus sylvestris",
                "Specimen label for Fagus sylvatica",
                "Taxon review note for Taraxacum officinale",
                "Archive record for Achillea millefolium",
                "Morphology note for Phragmites australis",
                "Field card for Urtica dioica",
                "Collection note for Trifolium pratense",
                "Urban park survey with Tilia cordata",
                "Roadside verge list with Plantago major",
                "Cemetery wall flora with Hedera helix",
                "Canal embankment record with Alnus glutinosa",
                "Brownfield inventory with Artemisia vulgaris",
                "Courtyard moss note with Bryum argenteum",
                "School garden list with Bellis perennis",
                "Railway habitat note with Robinia pseudoacacia"
        ).get(index);
    }

    private List<String> dummyTaxa() {
        return List.of("Carex riparia", "Salix alba", "Iris pseudacorus", "Juncus effusus", "Lemna minor", "Festuca rubra", "Quercus robur", "Malus domestica", "Betula pendula", "Pinus sylvestris", "Fagus sylvatica", "Taraxacum officinale", "Achillea millefolium", "Phragmites australis", "Urtica dioica", "Trifolium pratense", "Tilia cordata", "Plantago major", "Hedera helix", "Alnus glutinosa", "Artemisia vulgaris", "Bryum argenteum", "Bellis perennis", "Robinia pseudoacacia", "Poa annua");
    }

    private List<String> dummyPlaces() {
        return List.of("Danube marsh", "riverside stand", "fen transect", "Berlin park", "peat meadow", "pond edge", "dune grassland", "woodland margin", "orchard row", "historic herbarium", "pine ridge", "beech stand", "review cabinet", "archive drawer", "reed belt", "field margin", "meadow plot", "urban park", "roadside verge", "cemetery wall", "canal embankment", "brownfield lot", "courtyard wall", "school garden", "railway habitat");
    }

    private Map<String, Object> dummySpan(long fsId, long documentFsId, int typeCode, String typeName, int begin, int end, String coveredText, Map<String, Object> extraFeatures) {
        Map<String, Object> features = new java.util.LinkedHashMap<>(extraFeatures);
        features.put("begin", begin);
        features.put("end", end);
        Map<String, Object> fs = new java.util.LinkedHashMap<>();
        fs.put("_id", fsId);
        fs.put("_type", typeName);
        fs.put("typeCode", typeCode);
        fs.put("features", features);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("fsId", fsId);
        row.put("documentFsId", documentFsId);
        row.put("sofaFsId", 900);
        row.put("viewName", "_InitialView");
        row.put("typeCode", typeCode);
        row.put("typeName", typeName);
        row.put("artifactKind", "annotation");
        row.put("name", coveredText);
        row.put("label", typeName.substring(typeName.lastIndexOf('.') + 1));
        row.put("begin", begin);
        row.put("end", end);
        row.put("coveredText", coveredText);
        row.put("features", features);
        row.put("fs", fs);
        return row;
    }

    private Map<String, Object> dummyFs(long fsId) {
        return Map.of("fs", Map.of("_id", fsId, "_type", "dummy:FeatureStructure", "features", Map.of()), "cas", casJson(List.of()));
    }

    private Map<String, Object> casJson(List<?> featureStructures) {
        List<Long> members = featureStructures.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(fs -> number(fs.get("_id"), -1))
                .filter(fsId -> fsId > 0)
                .toList();
        return Map.of(
                "format", "UIMA-CAS-JSON",
                "views", Map.of("_InitialView", Map.of("members", members)),
                "types", List.of(),
                "featureStructures", featureStructures
        );
    }

    private int dummyTypeCodeForName(String typeName) {
        return switch (typeName) {
            case "org.texttechnologylab.annotations.dua.Corpus" -> 11;
            case "org.texttechnologylab.annotations.dua.Document" -> 12;
            case "org.apache.uima.jcas.tcas.Sentence" -> 21;
            case "org.texttechnologylab.annotation.NamedEntity" -> 22;
            case "org.texttechnologylab.annotation.Taxon" -> 23;
            default -> -1;
        };
    }

    private Map<String, Object> message(WsContext ctx) {
        Map<String, Object> parsed = gson.fromJson(((io.javalin.websocket.WsMessageContext) ctx).message(), new TypeToken<Map<String, Object>>() {}.getType());
        return parsed == null ? Map.of() : parsed;
    }

    private String action(Map<String, Object> query) {
        String op = string(query.get("op"), "");
        if (!op.isBlank()) {
            return op;
        }
        return string(query.get("action"), "");
    }

    private Map<String, Object> payload(Map<String, Object> query) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(map(query.get("payload")));
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            String key = entry.getKey();
            if ("requestId".equals(key) || "action".equals(key) || "op".equals(key) || "payload".equals(key)) {
                continue;
            }
            payload.putIfAbsent(key, entry.getValue());
        }
        if (payload.containsKey("fsRef")) {
            payload.putIfAbsent("fsId", payload.get("fsRef"));
            payload.putIfAbsent("rootFsId", payload.get("fsRef"));
            payload.putIfAbsent("documentFsId", payload.get("fsRef"));
        }
        if (payload.containsKey("type") && !payload.containsKey("select") && !payload.containsKey("typeCode")) {
            String type = string(payload.get("type"), "");
            if ("*".equals(type)) {
                payload.put("select", "types");
            }
        }
        return payload;
    }

    private Map<String, Object> select(Map<String, Object> payload) throws Exception {
        int typeCode = (int) number(payload.get("typeCode"), -1);
        String select = string(payload.get("select"), "");
        if (typeCode <= 0 || "types".equals(select) || "schema".equals(select)) {
            String typeName = string(payload.get("type"), "");
            if (!typeName.isBlank() && !"*".equals(typeName)) {
                typeCode = typeCodeForName(typeName);
                if (typeCode > 0) {
                    return data.duavizInstancesByType(
                            typeCode,
                            (int) number(payload.get("offset"), 0),
                            (int) number(payload.get("limit"), 50)
                    );
                }
            }
            return data.duavizSchema();
        }
        return data.duavizInstancesByType(
                typeCode,
                (int) number(payload.get("offset"), 0),
                (int) number(payload.get("limit"), 50)
        );
    }

    private Map<String, Object> selectResponse(Object requestId, String action, Map<String, Object> payload) throws Exception {
        Map<String, Object> selection = select(payload);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("action", action);
        response.put("op", action);
        response.put("status", 200);
        response.put("selection", selection);
        String type = string(payload.get("type"), "");
        if (!type.isBlank()) {
            response.put("type", type);
        }
        List<Long> fsRefs = fsRefs(selection);
        if (!fsRefs.isEmpty()) {
            response.put("fsRefs", fsRefs);
        }
        return response;
    }

    private Map<String, Object> fsResponse(Object requestId, String action, Map<String, Object> payload) throws Exception {
        long fsRef = number(payload.get("fsId"), number(payload.get("rootFsId"), -1));
        Map<String, Object> fsPayload = data.duavizFs(fsRef);
        Map<String, Object> fs = fs(fsPayload);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("action", action);
        response.put("op", action);
        response.put("status", 200);
        response.put("fs", fsPayload);
        response.put("fsRef", fsRef);
        int typeCode = (int) number(fs.get("typeCode"), -1);
        if (typeCode > 0) {
            response.put("typeCode", typeCode);
        }
        String typeName = string(fs.get("_type"), "");
        if (!typeName.isBlank()) {
            response.put("type", typeName);
        }
        if (fs.get("viewId") != null) {
            response.put("viewId", number(fs.get("viewId"), 0));
        }
        return response;
    }

    private Map<String, Object> span(Map<String, Object> query, Map<String, Object> payload) throws Exception {
        if (query.containsKey("fsRef") && !query.containsKey("documentFsId")) {
            return data.duavizFs(number(payload.get("fsId"), number(payload.get("rootFsId"), -1)));
        }
        return data.duavizDocument(
                number(payload.get("documentFsId"), -1),
                ints(payload.get("typeCodes")),
                (int) number(payload.get("limit"), 2500)
        );
    }

    private Map<String, Object> spanResponse(Object requestId, String action, Map<String, Object> query, Map<String, Object> payload) throws Exception {
        Map<String, Object> spans = span(query, payload);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("action", action);
        response.put("op", action);
        response.put("status", 200);
        response.put("spans", spans);
        if (query.containsKey("fsRef") && !query.containsKey("documentFsId")) {
            long fsRef = number(payload.get("fsId"), number(payload.get("rootFsId"), -1));
            Map<String, Object> fs = fs(spans);
            Map<String, Object> features = map(fs.get("features"));
            response.put("fsRef", fsRef);
            if (features.get("begin") != null) {
                response.put("begin", number(features.get("begin"), -1));
            }
            if (features.get("end") != null) {
                response.put("end", number(features.get("end"), -1));
            }
        }
        return response;
    }

    private int typeCodeForName(String typeName) throws Exception {
        Object rawTypes = data.duavizSchema().get("types");
        if (!(rawTypes instanceof List<?> types)) {
            return -1;
        }
        return types.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(type -> typeName.equals(string(type.get("name"), "")))
                .mapToInt(type -> (int) number(type.get("typeCode"), -1))
                .findFirst()
                .orElse(-1);
    }

    private List<Long> fsRefs(Map<String, Object> page) {
        Object rawInstances = page.get("instances");
        if (!(rawInstances instanceof List<?> instances)) {
            return List.of();
        }
        return instances.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(instance -> number(instance.get("fsId"), -1))
                .filter(fsId -> fsId > 0)
                .toList();
    }

    private Map<String, Object> fs(Map<String, Object> payload) {
        Object raw = payload.get("fs");
        if (raw instanceof Map<?, ?> fs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) fs;
            return typed;
        }
        return Map.of();
    }

    private void fail(Context ctx, String message, Exception ex) {
        if (ex instanceof org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
            return;
        }
        logger.error(message, ex);
        ctx.status(500).json(Map.of("status", 500, "message", message));
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        String normalized = value.toString().trim();
        if (normalized.isBlank()) return null;
        return Boolean.parseBoolean(normalized);
    }

    private static List<Integer> ints(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object item : raw) {
            long parsed = number(item, -1);
            if (parsed > 0) result.add((int) parsed);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
}
