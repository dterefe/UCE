package org.texttechnologylab.uce.web.routes;

import com.google.gson.Gson;
import freemarker.template.Configuration;
import io.javalin.http.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.services.AgeGraphService;
import org.texttechnologylab.uce.common.services.PostgresqlDataInterface_Impl;
import org.texttechnologylab.uce.web.freeMarker.AccessDeniedRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DomainApi implements UceApi {
    private static final Logger logger = LogManager.getLogger(DomainApi.class);

    private final AgeGraphService ageGraphService;
    private final PostgresqlDataInterface_Impl db;
    private final Configuration freemarkerConfig;
    private final Gson gson = new Gson();

    public DomainApi(ApplicationContext context, Configuration freemarkerConfig) {
        this.ageGraphService = context.getBean(AgeGraphService.class);
        this.db = context.getBean(PostgresqlDataInterface_Impl.class);
        this.freemarkerConfig = freemarkerConfig;
    }

    public void getPlaygroundView(Context ctx) {
        var model = new HashMap<String, Object>();
        try {
            String corpusIdParam = ctx.queryParam("corpusId");
            long corpusId = corpusIdParam == null || corpusIdParam.isBlank()
                    ? -1
                    : Long.parseLong(corpusIdParam);
            if (corpusId > 0) {
                model.put("corpus", db.getCorpusById(corpusId).getViewModel());
            }
            model.put("corpusId", corpusId);
            ctx.render("domain/domainPlayground.ftl", model);
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error rendering domain playground.", ex);
            ctx.render("defaultError.ftl");
        }
    }

    public void types(Context ctx) {
        try {
            long corpusId = parseLongParam(ctx.queryParam("corpusId"), -1);
            ctx.json(Map.of(
                    "status", 200,
                    "types", ageGraphService.listDomainTypes(corpusId)
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error loading domain types.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error loading domain types."));
        }
    }

    public void nodes(Context ctx) {
        try {
            Map<String, Object> body = requestBody(ctx);
            long corpusId = number(body.get("corpusId"), -1);
            String uimaType = string(body.get("uimaType"));
            String query = string(body.get("query"));
            int skip = (int) number(body.get("skip"), 0);
            int take = (int) number(body.get("take"), 50);
            ctx.json(Map.of(
                    "status", 200,
                    "page", ageGraphService.listDomainNodes(corpusId, uimaType, query, skip, take)
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error loading domain nodes.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error loading domain nodes."));
        }
    }

    public void associations(Context ctx) {
        try {
            Map<String, Object> body = requestBody(ctx);
            long corpusId = number(body.get("corpusId"), -1);
            List<String> sourceUids = stringList(body.get("sourceUids"));
            if (sourceUids.isEmpty()) {
                String sourceUid = string(body.get("sourceUid"));
                if (sourceUid != null) {
                    sourceUids = List.of(sourceUid);
                }
            }
            String associationType = string(body.get("associationType"));
            String direction = string(body.get("direction"));
            String targetType = string(body.get("targetType"));
            int skip = (int) number(body.get("skip"), 0);
            int take = (int) number(body.get("take"), 50);
            ctx.json(Map.of(
                    "status", 200,
                    "page", ageGraphService.listAssociatedNodes(corpusId, sourceUids, associationType, direction, targetType, skip, take)
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error loading domain associations.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error loading domain associations."));
        }
    }

    public void scopePreview(Context ctx) {
        try {
            Map<String, Object> body = requestBody(ctx);
            long corpusId = number(body.get("corpusId"), -1);
            List<String> selectedUids = stringList(body.get("selectedUids"));
            ctx.json(Map.of(
                    "status", 200,
                    "preview", ageGraphService.previewScope(corpusId, selectedUids)
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error previewing domain scope.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error previewing domain scope."));
        }
    }

    public void scopeSearch(Context ctx) {
        try {
            Map<String, Object> body = requestBody(ctx);
            long corpusId = number(body.get("corpusId"), -1);
            List<String> selectedUids = stringList(body.get("selectedUids"));
            var preview = ageGraphService.previewScope(corpusId, selectedUids);
            ctx.json(Map.of(
                    "status", 200,
                    "preview", preview,
                    "selectedUids", selectedUids
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error building domain search scope.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error building domain search scope."));
        }
    }

    public void ego(Context ctx) {
        try {
            Map<String, Object> body = requestBody(ctx);
            long corpusId = number(body.get("corpusId"), -1);
            List<String> selectedUids = stringList(body.get("selectedUids"));
            List<String> associationTypes = stringList(body.get("associationTypes"));
            int depth = (int) number(body.get("depth"), 1);
            ctx.json(Map.of(
                    "status", 200,
                    "graph", ageGraphService.egoGraph(corpusId, selectedUids, associationTypes, depth)
            ));
        } catch (DocumentAccessDeniedException dade) {
            AccessDeniedRenderer.render(ctx, dade, logger);
        } catch (Exception ex) {
            logger.error("Error loading domain ego graph.", ex);
            ctx.status(500).json(Map.of("status", 500, "message", "Error loading domain ego graph."));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestBody(Context ctx) {
        if (ctx.body() == null || ctx.body().isBlank()) {
            return Map.of();
        }
        Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
        return body == null ? Map.of() : body;
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value);
        return result.isBlank() || result.equalsIgnoreCase("null") ? null : result;
    }

    private static long number(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLongParam(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String string = string(item);
                if (string != null) {
                    result.add(string);
                }
            }
            return result;
        }
        String single = string(value);
        return single == null ? List.of() : List.of(single);
    }
}
