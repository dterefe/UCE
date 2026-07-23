package org.texttechnologylab.uce.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.texttechnologylab.uce.health.HealthCheck;

import java.util.Map;
import java.util.function.Supplier;

import spark.Service;

import static spark.Spark.*;

/**
 * Spark Java wiring for standard operational endpoints.
 *
 * <p>Each method is a standalone one-liner — use only what you need.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * port(4570);
 * UceSparkHealth.wireMiddleware();
 * UceSparkHealth.wireK8sHealth(() -> HealthCheck.up(), () -> myReadiness());
 * UceSparkHealth.wireHealth(() -> Map.of("status", "UP"));
 * UceSparkHealth.wireStatus(() -> Map.of("version", "0.1.0"));
 * UceSparkHealth.wireSchema(() -> Map.of("fields", ...));
 * }</pre>
 */
public final class UceSparkHealth {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private UceSparkHealth() {}

    // ── K8s standard health ──────────────────────────────────────────────

    /**
     * Wire {@code GET /health/live} and {@code GET /health/ready}.
     *
     * <p>Liveness: HTTP 200 if JVM is alive. Readiness: HTTP 200 if all
     * components UP, HTTP 503 if any component DOWN.</p>
     */
    public static void wireK8sHealth(Supplier<HealthCheck> liveness, Supplier<HealthCheck> readiness) {
        get("/health/live", (req, res) -> {
            var check = liveness.get();
            res.status("DOWN".equals(check.getStatus()) ? 503 : 200);
            return check;
        }, JSON::writeValueAsString);

        get("/health/ready", (req, res) -> {
            var check = readiness.get();
            res.status("DOWN".equals(check.getStatus()) ? 503 : 200);
            return check;
        }, JSON::writeValueAsString);
    }

    public static void wireK8sHealth(Service service, Supplier<HealthCheck> liveness,
                                     Supplier<HealthCheck> readiness) {
        service.get("/health/live", (req, res) -> {
            var check = liveness.get();
            res.status("DOWN".equals(check.getStatus()) ? 503 : 200);
            return check;
        }, JSON::writeValueAsString);
        service.get("/health/ready", (req, res) -> {
            var check = readiness.get();
            res.status("DOWN".equals(check.getStatus()) ? 503 : 200);
            return check;
        }, JSON::writeValueAsString);
    }

    // ── Simple health ────────────────────────────────────────────────────

    /** Wire {@code GET /health} with a simple map supplier. HTTP 503 if status is DOWN. */
    public static void wireHealth(Supplier<Map<String, Object>> healthSupplier) {
        get("/health", (req, res) -> {
            var h = healthSupplier.get();
            res.status("DOWN".equals(h.get("status")) ? 503 : 200);
            return h;
        }, JSON::writeValueAsString);
    }

    public static void wireHealth(Service service, Supplier<Map<String, Object>> healthSupplier) {
        service.get("/health", (req, res) -> {
            var h = healthSupplier.get();
            res.status("DOWN".equals(h.get("status")) ? 503 : 200);
            return h;
        }, JSON::writeValueAsString);
    }

    // ── Operational ──────────────────────────────────────────────────────

    /** Wire {@code GET /status}. */
    public static void wireStatus(Supplier<Map<String, Object>> statusSupplier) {
        get("/status", (req, res) -> statusSupplier.get(), JSON::writeValueAsString);
    }

    public static void wireStatus(Service service, Supplier<Map<String, Object>> statusSupplier) {
        service.get("/status", (req, res) -> statusSupplier.get(), JSON::writeValueAsString);
    }

    /** Wire {@code GET /api/schema}. */
    public static void wireSchema(Supplier<Map<String, Object>> schemaSupplier) {
        get("/api/schema", (req, res) -> schemaSupplier.get(), JSON::writeValueAsString);
    }

    public static void wireSchema(Service service, Supplier<Map<String, Object>> schemaSupplier) {
        service.get("/api/schema", (req, res) -> schemaSupplier.get(), JSON::writeValueAsString);
    }

    // ── Middleware ───────────────────────────────────────────────────────

    /** Global middleware: request logging, 500 → JSON, 404 → JSON. */
    public static void wireMiddleware() {
        before((req, res) ->
                System.out.printf("[%s] %s %s%n",
                        java.time.LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                        req.requestMethod(), req.uri()));

        exception(Exception.class, (e, req, res) -> {
            System.err.println("ERROR " + req.requestMethod() + " " + req.uri() + ": " + e.getMessage());
            e.printStackTrace();
            res.status(500);
            res.type("application/json");
            res.body(toJson(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Internal server error"
            )));
        });

        notFound((req, res) -> {
            res.type("application/json");
            return toJson(Map.of(
                    "error", "Not found: " + req.requestMethod() + " " + req.uri()
            ));
        });
    }

    public static void wireMiddleware(Service service) {
        service.before((req, res) ->
                System.out.printf("[%s] %s %s%n",
                        java.time.LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                        req.requestMethod(), req.uri()));
        service.exception(Exception.class, (e, req, res) -> {
            System.err.println("ERROR " + req.requestMethod() + " " + req.uri() + ": " + e.getMessage());
            res.status(500);
            res.type("application/json");
            res.body(toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error")));
        });
        service.notFound((req, res) -> {
            res.type("application/json");
            return toJson(Map.of("error", "Not found: " + req.requestMethod() + " " + req.uri()));
        });
    }

    private static String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"JSON serialization failed\"}";
        }
    }
}
