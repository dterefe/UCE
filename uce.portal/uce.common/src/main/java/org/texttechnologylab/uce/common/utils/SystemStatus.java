package org.texttechnologylab.uce.common.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.texttechnologylab.uce.common.config.UceConfig;
import org.texttechnologylab.uce.common.cronjobs.SystemJob;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.models.util.HealthStatus;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.StorageMaintenanceService;

import java.io.IOException;

public final class SystemStatus {
    public static HealthStatus GbifServiceStatus = new HealthStatus();
    public static HealthStatus GoetheUniversityServiceStatus = new HealthStatus();
    public static HealthStatus JenaSparqlStatus = new HealthStatus();
    public static HealthStatus PostgresqlDbStatus = new HealthStatus();
    public static HealthStatus RagServiceStatus = new HealthStatus();
    public static HealthStatus AuthenticationService = new HealthStatus();
    public static HealthStatus S3StorageStatus = new HealthStatus();
    public static boolean LexiconIsCalculating = false;
    public static UceConfig UceConfig = null;
    private static final Logger logger = LogManager.getLogger(SystemStatus.class);

    private SystemStatus() {}

    public static void initSystemStatus(long cleanupInterval, ApplicationContext serviceContext) {
        var accessManager = serviceContext.getBean(DocumentAccessManager.class);
        
        Runnable runnable = accessManager.wrapAdmin(new SystemJob(cleanupInterval, serviceContext));

        var sessionJob = new Thread(runnable);
        sessionJob.start();
    }

    /**
     * Executes the external database scripts for triggers, procedures and such.
     */
    public static void executeExternalDatabaseScripts(String path, StorageMaintenanceService storageMaintenanceService) throws IOException {
        storageMaintenanceService.executeExternalStorageScripts(path);
    }
}
