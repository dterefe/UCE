# UCE Core

Unified Corpus Explorer — core service framework with standardized health, OpenAPI, and Swagger.

Pick and choose standalone utilities — no central interface, no base class required.

| Endpoint            | Purpose                                          |
| ------------------- | ------------------------------------------------ |
| `GET /health/live`  | K8s liveness probe (is the JVM alive?)           |
| `GET /health/ready` | K8s readiness probe (can we accept requests?)    |
| `GET /health`       | Simple health (backwards compatible)             |
| `GET /status`       | Runtime metadata (version, uptime, doc count)    |
| `GET /api/schema`   | Field conventions, accepted FQCNs, limits        |
| `GET /openapi.json` | OpenAPI 3.0.3 specification                      |
| `GET /swagger`      | Embedded Swagger UI                              |

## Quick Start

```bash
mvn compile exec:java -Dexec.mainClass=org.texttechnologylab.uce.UceApplication
```

Then open `http://localhost:8088/swagger` in your browser.

## Building

```bash
mvn compile                        # Compile
mvn test                           # Run tests
mvn package -DskipTests            # Package
mvn install -DskipTests            # Install to local ~/.m2
```

## Creating a New UCE Service

```java
import static spark.Spark.*;
import org.texttechnologylab.uce.health.*;
import org.texttechnologylab.uce.openapi.*;
import org.texttechnologylab.uce.spark.*;

public class SearchApp {
    public static void main(String[] args) {
        port(4570);

        // Standard middleware: logging, 500 → JSON, 404 → JSON
        UceSparkHealth.wireMiddleware();

        // K8s health: liveness + readiness with component checks
        UceSparkHealth.wireK8sHealth(
            () -> HealthCheck.up(),
            () -> HealthCheck.builder()
                .component("lucene", HealthStatus.up(Map.of("docCount", 142_000)))
                .component("database", HealthStatus.up(Map.of("poolSize", 8)))
                .build()
        );

        // Simple health for non-K8s consumers
        UceSparkHealth.wireHealth(() -> Map.of("status", "UP"));
        UceSparkHealth.wireStatus(() -> Map.of("version", "0.1.0"));
        UceSparkHealth.wireSchema(() -> Map.of("fields", List.of("sofa.text.all")));

        // OpenAPI spec
        get("/openapi.json", (req, res) ->
            OpenApiBuilder.create("UCE Search", "0.1.0", 4570)
                .standardPaths()
                .path("/api/query", Map.of("post", Map.of(...)))
                .buildJson()
        );

        // Swagger UI
        UceSparkSwagger.wire("UCE Search", "/openapi.json");

        // Your routes
        post("/api/query", (req, res) -> search(req.body()));
    }
}
```

## Package Structure

```text
org.texttechnologylab.uce/
├── health/           HealthCheck — K8s health model
├── openapi/          OpenApiBuilder — fluent OpenAPI 3 builder
└── spark/            UceSparkHealth, UceSparkSwagger — Spark Java wiring
```

## Dependencies

- **Spark Java** 2.9.4 — HTTP framework (NOT Apache Spark)
- **Jackson** 2.17.1 — JSON serialization
- **Jackson JSR310** 2.17.1 — Java 8 date/time support
- **Swagger UI** 5.17.14 — embedded API docs
- Java 21+

## License

See the repository LICENSE file.
