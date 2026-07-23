package org.texttechnologylab.uce;

import org.texttechnologylab.uce.health.HealthCheck;
import org.texttechnologylab.uce.health.HealthCheck.HealthStatus;
import org.texttechnologylab.uce.openapi.OpenApiBuilder;
import org.texttechnologylab.uce.spark.UceSparkHealth;
import org.texttechnologylab.uce.spark.UceSparkSwagger;
import spark.Request;
import spark.Response;

import java.time.Instant;
import java.util.Map;

import static spark.Spark.*;

/**
 * Minimal UCE demo showing standalone utility usage — no central interface, no base class.
 *
 * <p>Each utility is called independently. A real service would replace the
 * demo suppliers with actual business logic.</p>
 */
public class UceApplication {

    private static final int PORT = 8088;
    private static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        port(PORT);

        // ── Global middleware (logging, error handling, 404) ─────────────
        UceSparkHealth.wireMiddleware();

        // ── K8s-standard health ──────────────────────────────────────────
        UceSparkHealth.wireK8sHealth(
                () -> HealthCheck.up(),                              // liveness
                () -> HealthCheck.builder()                          // readiness
                        .component("jvm", HealthStatus.up(Map.of(
                                "version", System.getProperty("java.version"),
                                "availableProcessors", Runtime.getRuntime().availableProcessors()
                        )))
                        .build()
        );

        // ── Simple health (for non-K8s consumers) ───────────────────────
        UceSparkHealth.wireHealth(() -> Map.of("status", "UP", "uptime", "see /status"));

        // ── Status ───────────────────────────────────────────────────────
        UceSparkHealth.wireStatus(() -> Map.of(
                "version", VERSION,
                "javaVersion", System.getProperty("java.version"),
                "availableProcessors", Runtime.getRuntime().availableProcessors()
        ));

        // ── Schema ───────────────────────────────────────────────────────
        UceSparkHealth.wireSchema(() -> Map.of(
                "description", "Demo service — no domain-specific schema",
                "endpoints", Map.of(
                        "health/live",  "GET /health/live",
                        "health/ready", "GET /health/ready",
                        "health",       "GET /health",
                        "status",       "GET /status",
                        "schema",       "GET /api/schema",
                        "openapi",      "GET /openapi.json",
                        "swagger",      "GET /swagger",
                        "echo",         "POST /api/echo"
                )
        ));

        // ── OpenAPI spec ─────────────────────────────────────────────────
        get("/openapi.json", (req, res) -> {
            res.type("application/json");
            return OpenApiBuilder.create("UCE Core Demo", VERSION, PORT)
                    .standardPaths()
                    .path("/api/echo", Map.of("post", Map.of(
                            "summary", "Echo back the request body",
                            "operationId", "echo",
                            "requestBody", Map.of("content", Map.of(
                                    "application/json", Map.of("schema", Map.of("type", "object"))
                            )),
                            "responses", Map.of("200", Map.of(
                                    "description", "Echo response",
                                    "content", Map.of("application/json", Map.of(
                                            "schema", Map.of("type", "object")
                                    ))
                            ))
                    )))
                    .buildJson();
        });

        // ── Swagger UI ───────────────────────────────────────────────────
        UceSparkSwagger.wire("UCE Core Demo", "/openapi.json");

        // ── Service-specific route ───────────────────────────────────────
        post("/api/echo", (Request req, Response res) -> {
            Map<?, ?> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(req.body(), Map.class);
            res.type("application/json");
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "echo", body,
                    "receivedAt", Instant.now().toString()));
        });

        System.out.println("UCE Core Demo running on http://localhost:" + PORT);
        System.out.println("  Swagger UI: http://localhost:" + PORT + "/swagger");
        System.out.println("  OpenAPI:    http://localhost:" + PORT + "/openapi.json");
    }
}
