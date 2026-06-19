package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.config.CommonConfig;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.utils.SystemStatus;

import java.io.IOException;

public class UCEBackend {
    private final DataInterface data;
    private final StorageMaintenanceService storage;

    public UCEBackend(DataInterface data, StorageMaintenanceService storage) {
        this.data = data;
        this.storage = storage;
    }

    public DataInterface data() {
        return data;
    }

    public StorageMaintenanceService storage() {
        return storage;
    }

    public String name() {
        return data.backendName();
    }

    public void initialize(CommonConfig config) throws IOException {
        if (storage.supportsStorageStatements()) {
            SystemStatus.executeExternalDatabaseScripts(config.getDatabaseScriptsLocation(), storage);
            return;
        }
        storage.testConnection();
    }

    public void cleanupLayeredSearch() throws DatabaseOperationException, DocumentAccessDeniedException {
        storage.cleanupLayeredSearch();
    }

    public int refreshLogicalLinks() throws DatabaseOperationException, DocumentAccessDeniedException {
        return storage.refreshLogicalLinks();
    }

    public int refreshGeonameLocations() throws DatabaseOperationException, DocumentAccessDeniedException {
        return storage.refreshGeonameLocations();
    }
}
