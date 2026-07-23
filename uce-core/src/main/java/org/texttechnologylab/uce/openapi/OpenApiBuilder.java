package org.texttechnologylab.uce.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an OpenAPI 3.0.3 specification as a {@code Map<String, Object>} tree.
 *
 * <p>Pure builder — no dependencies, no interface. Use it however you want.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * String spec = OpenApiBuilder.create("UCE Search", "0.1.0", 4570)
 *     .standardPaths()
 *     .path("/api/query", Map.of("post", Map.of(...)))
 *     .buildJson();
 * }</pre>
 */
public final class OpenApiBuilder {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Map<String, Object> paths = new LinkedHashMap<>();

    private OpenApiBuilder(String title, String version, int port) {
        root.put("openapi", "3.0.3");
        root.put("info", Map.of(
                "title", title,
                "version", version,
                "description", "REST API for " + title
        ));
        root.put("servers", List.of(Map.of(
                "url", "http://localhost:" + port,
                "description", "Local development server"
        )));
        root.put("paths", paths);
    }

    public static OpenApiBuilder create(String title, String version, int port) {
        return new OpenApiBuilder(title, version, port);
    }

    /** Add standard operational paths: /health, /status, /api/schema, /openapi.json. */
    public OpenApiBuilder standardPaths() {
        paths.put("/health", path("get", "Service health", "health"));
        paths.put("/health/live", path("get", "Liveness probe (K8s standard)", "liveness"));
        paths.put("/health/ready", path("get", "Readiness probe (K8s standard)", "readiness"));
        paths.put("/status", path("get", "Runtime status", "status"));
        paths.put("/api/schema", path("get", "Service schema", "schema"));
        paths.put("/openapi.json", path("get", "OpenAPI specification", "openApiJson"));
        return this;
    }

    public OpenApiBuilder path(String path, Map<String, Object> definition) {
        paths.put(path, definition);
        return this;
    }

    @SuppressWarnings("unchecked")
    public OpenApiBuilder schema(String name, Map<String, Object> definition) {
        var components = (Map<String, Object>) root.computeIfAbsent("components",
                k -> new LinkedHashMap<>());
        var schemas = (Map<String, Object>) components.computeIfAbsent("schemas",
                k -> new LinkedHashMap<>());
        schemas.put(name, definition);
        return this;
    }

    public Map<String, Object> build() {
        return root;
    }

    public String buildJson() {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAPI spec", e);
        }
    }

    private static Map<String, Object> path(String method, String summary, String operationId) {
        return Map.of(method, Map.of(
                "summary", summary,
                "operationId", operationId,
                "responses", Map.of("200", Map.of(
                        "description", "OK",
                        "content", Map.of("application/json", Map.of(
                                "schema", Map.of("type", "object", "additionalProperties", true)
                        ))
                ))
        ));
    }
}
