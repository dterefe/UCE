package org.texttechnologylab.uce.spark;

import static spark.Spark.*;

/**
 * One-liner for wiring embedded Swagger UI.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * get("/openapi.json", (req, res) -> mySpecJson);
 * UceSparkSwagger.wire("My Service", "/openapi.json");
 * }</pre>
 *
 * <p>Registers:</p>
 * <ul>
 *   <li>{@code GET /swagger} → redirect to /swagger/</li>
 *   <li>{@code GET /swagger/} → Swagger UI HTML</li>
 *   <li>{@code GET /swagger/*} → webjar assets (CSS, JS, PNG)</li>
 * </ul>
 */
public final class UceSparkSwagger {

    private UceSparkSwagger() {}

    public static void wire(String serviceName, String openApiPath) {
        get("/swagger", (req, res) -> {
            res.redirect("/swagger/");
            return null;
        });

        get("/swagger/", (req, res) -> {
            res.type("text/html; charset=utf-8");
            return """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1" />
                      <title>%s — Swagger UI</title>
                      <link rel="stylesheet" href="/swagger/swagger-ui.css" />
                    </head>
                    <body>
                      <div id="swagger-ui"></div>
                      <script src="/swagger/swagger-ui-bundle.js" crossorigin></script>
                      <script>
                        SwaggerUIBundle({
                          url: "%s",
                          dom_id: "#swagger-ui",
                          deepLinking: true,
                          layout: "BaseLayout"
                        });
                      </script>
                    </body>
                    </html>
                    """.formatted(serviceName, openApiPath);
        });

        get("/swagger/*", (req, res) -> {
            String path = req.splat()[0];
            String resourcePath = "/META-INF/resources/webjars/swagger-ui/5.17.14/" + path;
            var stream = UceSparkSwagger.class.getResourceAsStream(resourcePath);
            if (stream == null) {
                res.status(404);
                return "Not found: " + path;
            }
            if (path.endsWith(".css")) res.type("text/css");
            else if (path.endsWith(".js")) res.type("application/javascript");
            else if (path.endsWith(".png")) res.type("image/png");
            return stream;
        });
    }
}
