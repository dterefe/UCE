package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.models.util.HealthStatus;
import org.texttechnologylab.uce.common.utils.SystemStatus;

import java.io.IOException;

public class DuaStorageMaintenanceService implements StorageMaintenanceService {
    private final DataInterface dataInterface;

    public DuaStorageMaintenanceService(DataInterface dataInterface) {
        this.dataInterface = dataInterface;
    }

    @Override
    public void testConnection() {
        SystemStatus.PostgresqlDbStatus = new HealthStatus(true, "DUA storage backend active: " + dataInterface.backendName(), null);
    }

    @Override
    public void executeExternalStorageScripts(String path) throws IOException {
        testConnection();
    }

    @Override
    public void executeStorageStatement(String statement) {
    }

    @Override
    public void cleanupLayeredSearch() {
    }

    @Override
    public int refreshLogicalLinks() throws DatabaseOperationException, DocumentAccessDeniedException {
        return dataInterface.refreshLogicalLinks();
    }

    @Override
    public int refreshGeonameLocations() throws DatabaseOperationException, DocumentAccessDeniedException {
        return dataInterface.refreshGeonameLocations();
    }
}
