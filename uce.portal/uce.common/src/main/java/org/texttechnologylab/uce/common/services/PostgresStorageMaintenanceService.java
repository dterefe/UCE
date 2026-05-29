package org.texttechnologylab.uce.common.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class PostgresStorageMaintenanceService implements StorageMaintenanceService {
    private static final Logger logger = LogManager.getLogger(PostgresStorageMaintenanceService.class);
    private final PostgresqlDataInterface_Impl db;

    public PostgresStorageMaintenanceService(PostgresqlDataInterface_Impl db) {
        this.db = db;
    }

    @Override
    public void testConnection() {
        db.TestConnection();
    }

    @Override
    public boolean supportsStorageStatements() {
        return true;
    }

    @Override
    public void executeExternalStorageScripts(String path) throws IOException {
        try (var fileStream = Files.list(Paths.get(path))) {
            fileStream
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".sql"))
                    .sorted((f1, f2) -> {
                        try {
                            int n1 = Integer.parseInt(f1.getFileName().toString().split("_", 2)[0]);
                            int n2 = Integer.parseInt(f2.getFileName().toString().split("_", 2)[0]);
                            return Integer.compare(n1, n2);
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .forEach(file -> {
                        try {
                            db.executeSqlWithoutReturn(Files.readString(file));
                            logger.info("*--> Successfully executed: {}", file.getFileName());
                        } catch (IOException | DatabaseOperationException | DocumentAccessDeniedException ex) {
                            logger.error("Error trying to execute the database script {}", file.getFileName(), ex);
                        }
                    });
        }
    }

    @Override
    public void executeStorageStatement(String statement) throws DatabaseOperationException, DocumentAccessDeniedException {
        db.executeSqlWithoutReturn(statement);
    }

    @Override
    public List<?> executeStorageQuery(String statement) throws DatabaseOperationException, DocumentAccessDeniedException {
        return db.executeSqlWithReturn(statement);
    }

    @Override
    public void cleanupLayeredSearch() throws DatabaseOperationException, DocumentAccessDeniedException {
        String query = """
                DO $$ DECLARE
                    r RECORD;
                BEGIN
                    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'search') LOOP
                        EXECUTE 'DROP TABLE IF EXISTS search.' || quote_ident(r.tablename) || ' CASCADE';
                    END LOOP;
                END $$;
                """;
        db.executeSqlWithoutReturn(query);
    }

    @Override
    public int refreshLogicalLinks() throws DatabaseOperationException, DocumentAccessDeniedException {
        return db.refreshLogicalLinks();
    }

    @Override
    public int refreshGeonameLocations() throws DatabaseOperationException, DocumentAccessDeniedException {
        return db.refreshGeonameLocations();
    }
}
