package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;

import java.io.IOException;
import java.util.List;

public interface StorageMaintenanceService {
    void testConnection();

    default boolean supportsStorageStatements() {
        return false;
    }

    void executeExternalStorageScripts(String path) throws IOException;

    void executeStorageStatement(String statement) throws DatabaseOperationException, DocumentAccessDeniedException;

    default List<?> executeStorageQuery(String statement) throws DatabaseOperationException, DocumentAccessDeniedException {
        return List.of();
    }

    void cleanupLayeredSearch() throws DatabaseOperationException, DocumentAccessDeniedException;

    int refreshLogicalLinks() throws DatabaseOperationException, DocumentAccessDeniedException;

    int refreshGeonameLocations() throws DatabaseOperationException, DocumentAccessDeniedException;
}
