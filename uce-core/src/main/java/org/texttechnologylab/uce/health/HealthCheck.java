package org.texttechnologylab.uce.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kubernetes-style health check contract.
 *
 * <p>Two standard endpoints every UCE service can expose:</p>
 * <ul>
 *   <li>{@code GET /health/live} — <b>Liveness</b>: "Is the process alive?"</li>
 *   <li>{@code GET /health/ready} — <b>Readiness</b>: "Can the process accept requests?"</li>
 * </ul>
 *
 * <p>Any load balancer, orchestrator, or monitoring tool that speaks the
 * standard K8s health protocol can consume these without UCE-specific knowledge.</p>
 *
 * <h3>JSON shape</h3>
 * <pre>{@code
 * // Liveness (always cheap, no dependency checks)
 * {"status":"UP","timestamp":"..."}
 *
 * // Readiness (includes component-level health)
 * {
 *   "status":"UP",
 *   "components":{
 *     "database":{"status":"UP","details":{"poolSize":8}},
 *     "lucene":{"status":"UP","details":{"docCount":142000}}
 *   },
 *   "timestamp":"..."
 * }
 * }</pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var live = HealthCheck.up();
 *
 * var ready = HealthCheck.builder()
 *     .component("db", HealthStatus.up(Map.of("poolSize", 8)))
 *     .component("idx", HealthStatus.down("corrupt segment"))
 *     .build();
 * // ready.status() == "DOWN" because idx is DOWN
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"status", "components", "timestamp"})
public final class HealthCheck {

    private final String status;
    private final Map<String, HealthStatus> components;
    private final Instant timestamp;

    private HealthCheck(String status, Map<String, HealthStatus> components) {
        this.status = Objects.requireNonNull(status);
        this.components = Map.copyOf(components);
        this.timestamp = Instant.now();
    }

    // ── Factory ──────────────────────────────────────────────────────────

    public static HealthCheck up() {
        return new HealthCheck("UP", Map.of());
    }

    public static HealthCheck down(String reason) {
        return new HealthCheck("DOWN", Map.of(
                "error", new HealthStatus("DOWN", Map.of("reason", reason))
        ));
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public String getStatus() { return status; }
    public Map<String, HealthStatus> getComponents() { return components; }
    public Instant getTimestamp() { return timestamp; }

    // ── Builder ──────────────────────────────────────────────────────────

    public static final class Builder {
        private final Map<String, HealthStatus> components = new LinkedHashMap<>();

        public Builder component(String name, HealthStatus status) {
            components.put(name, status);
            return this;
        }

        public HealthCheck build() {
            boolean allUp = components.values().stream()
                    .allMatch(c -> "UP".equals(c.getStatus()));
            return new HealthCheck(allUp ? "UP" : "DOWN", components);
        }
    }

    // ── Component status ─────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"status", "details"})
    public static final class HealthStatus {
        private final String status;
        private final Map<String, Object> details;

        public HealthStatus(String status, Map<String, Object> details) {
            this.status = Objects.requireNonNull(status);
            this.details = Map.copyOf(details);
        }

        public static HealthStatus up() { return new HealthStatus("UP", Map.of()); }
        public static HealthStatus up(Map<String, Object> details) { return new HealthStatus("UP", details); }
        public static HealthStatus down(String reason) { return new HealthStatus("DOWN", Map.of("reason", reason)); }

        public String getStatus() { return status; }
        public Map<String, Object> getDetails() { return details; }
    }
}
