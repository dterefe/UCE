package org.texttechnologylab.uce.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.*;

@Configuration
@Import({DocumentAccessConfig.class})
public class SpringConfig {

    @Bean
    public DataInterface databaseService() {
        return switch (UceStorageBackend.configured()) {
            case POSTGRES -> new PostgresqlDataInterface_Impl();
            case DUA -> throw new IllegalStateException("DUA storage is exposed through the DUA service; UCE web search/RAG requires the Postgres data interface.");
        };
    }

    @Bean
    public StorageMaintenanceService storageMaintenanceService(DataInterface databaseService) {
        if (databaseService instanceof PostgresqlDataInterface_Impl postgres) {
            return new PostgresStorageMaintenanceService(postgres);
        }
        return new DuaStorageMaintenanceService(databaseService);
    }

    @Bean
    public UCEBackend uceBackend(DataInterface databaseService, StorageMaintenanceService storageMaintenanceService) {
        return new UCEBackend(databaseService, storageMaintenanceService);
    }

    @Bean
    public LexiconService lexiconService(DataInterface databaseService) {
        return new LexiconService(databaseService);
    }

    @Bean
    public AuthenticationService authenticationService() {return new AuthenticationService();}

    @Bean
    public MapService mapService(DataInterface databaseService) {
        return new MapService(databaseService);
    }

    @Bean
    public WikiService wikiService(DataInterface databaseService, RAGService ragService, JenaSparqlService jenaSparqlService) {
        return new WikiService(databaseService, ragService, jenaSparqlService);
    }

    @Bean
    public GoetheUniversityService goetheUniversityService() {
        return new GoetheUniversityService();
    }

    @Bean
    public GbifService gbifService() {
        return new GbifService(jenaSparqlService());
    }

    @Bean
    public JenaSparqlService jenaSparqlService() {
        return new JenaSparqlService();
    }

    @Bean
    public RAGService ragService(DataInterface databaseService, DocumentAccessManager accessManager) {
        return new RAGService(databaseService, accessManager);
    }

    @Bean
    public EmbeddingService embeddingService(DataInterface databaseService, DocumentAccessManager accessManager) {
        return new EmbeddingService(databaseService, accessManager);
    }

    @Bean
    public S3StorageService s3Storage() {
        return new S3StorageService();
    }

}
