package org.texttechnologylab.uce.common.config;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.models.biofid.BiofidTaxon;
import org.texttechnologylab.uce.common.models.biofid.GazetteerTaxon;
import org.texttechnologylab.uce.common.models.biofid.GnFinderTaxon;
import org.texttechnologylab.uce.common.models.corpus.*;
import org.texttechnologylab.uce.common.models.corpus.emotion.Emotion;
import org.texttechnologylab.uce.common.models.corpus.emotion.Feeling;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationLink;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationToDocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentToAnnotationLink;
import org.texttechnologylab.uce.common.models.gbif.GbifOccurrence;
import org.texttechnologylab.uce.common.models.imp.ImportLog;
import org.texttechnologylab.uce.common.models.imp.UCEImport;
import org.texttechnologylab.uce.common.models.negation.*;
import org.texttechnologylab.uce.common.models.topic.TopicValueBase;
import org.texttechnologylab.uce.common.models.topic.TopicValueBaseWithScore;
import org.texttechnologylab.uce.common.models.topic.TopicWord;
import org.texttechnologylab.uce.common.models.topic.UnifiedTopic;

import java.util.HashMap;

@Configuration
@EnableTransactionManagement
public class HibernateConf {

    public static SessionFactory buildSessionFactory() {
        var settings = getSettings();

        var serviceRegistry = new StandardServiceRegistryBuilder()
                .applySettings(settings).build();

        var metadataSources = new MetadataSources(serviceRegistry);
        metadataSources.addAnnotatedClass(Block.class);
        metadataSources.addAnnotatedClass(MetadataTitleInfo.class);
        metadataSources.addAnnotatedClass(UCEMetadata.class);
        metadataSources.addAnnotatedClass(UCEMetadataFilter.class);
        // Links
        metadataSources.addAnnotatedClass(DocumentLink.class);
        metadataSources.addAnnotatedClass(AnnotationLink.class);
        metadataSources.addAnnotatedClass(DocumentToAnnotationLink.class);
        metadataSources.addAnnotatedClass(AnnotationToDocumentLink.class);
        metadataSources.addAnnotatedClass(Line.class);
        metadataSources.addAnnotatedClass(SrLink.class);
        metadataSources.addAnnotatedClass(Lemma.class);
        metadataSources.addAnnotatedClass(PageKeywordDistribution.class);
        metadataSources.addAnnotatedClass(DocumentKeywordDistribution.class);
        metadataSources.addAnnotatedClass(NamedEntity.class);
        metadataSources.addAnnotatedClass(Sentiment.class);
        metadataSources.addAnnotatedClass(Emotion.class);
        metadataSources.addAnnotatedClass(Feeling.class);
        metadataSources.addAnnotatedClass(GeoName.class);
        metadataSources.addAnnotatedClass(Paragraph.class);
        metadataSources.addAnnotatedClass(Sentence.class);
        metadataSources.addAnnotatedClass(GbifOccurrence.class);
        metadataSources.addAnnotatedClass(GazetteerTaxon.class);
        metadataSources.addAnnotatedClass(GnFinderTaxon.class);
        metadataSources.addAnnotatedClass(BiofidTaxon.class);
        metadataSources.addAnnotatedClass(Time.class);
        metadataSources.addAnnotatedClass(WikiDataHyponym.class);
        metadataSources.addAnnotatedClass(WikipediaLink.class);
        metadataSources.addAnnotatedClass(LexiconEntry.class);
        metadataSources.addAnnotatedClass(Page.class);
        metadataSources.addAnnotatedClass(Document.class);
        metadataSources.addAnnotatedClass(DocumentPermission.class);
        metadataSources.addAnnotatedClass(Corpus.class);
        metadataSources.addAnnotatedClass(CorpusTsnePlot.class);
        metadataSources.addAnnotatedClass(UCELog.class);
        metadataSources.addAnnotatedClass(UCEImport.class);
        metadataSources.addAnnotatedClass(ImportLog.class);
        metadataSources.addAnnotatedClass(Image.class);
        //negations
        metadataSources.addAnnotatedClass(CompleteNegation.class);
        metadataSources.addAnnotatedClass(Cue.class);
        metadataSources.addAnnotatedClass(Event.class);
        metadataSources.addAnnotatedClass(Focus.class);
        metadataSources.addAnnotatedClass(Scope.class);
        metadataSources.addAnnotatedClass(XScope.class);
        //topics
        metadataSources.addAnnotatedClass(UnifiedTopic.class);
        metadataSources.addAnnotatedClass(TopicWord.class);
        metadataSources.addAnnotatedClass(TopicValueBase.class);
        metadataSources.addAnnotatedClass(TopicValueBaseWithScore.class);

        metadataSources.addAnnotatedClass(DocumentTopThreeTopics.class);
        var metadata = metadataSources.buildMetadata();

        return metadata.getSessionFactoryBuilder().build();
    }

    private static HashMap<Object, Object> getSettings() {
        var settings = new HashMap<>();
        var config = new CommonConfig();
        // Hibernate expects the fully-qualified keys here (hibernate.*). If these are wrong,
        // Hibernate falls back and the logs show "using driver [null]".
        putSetting(settings, "hibernate.connection.driver_class",
                value(config, "connection.driver_class", "POSTGRESQL_CONNECTION_DRIVER_CLASS", "org.postgresql.Driver"));
        putSetting(settings, "hibernate.dialect",
                value(config, "dialect", "POSTGRESQL_DIALECT", "org.hibernate.dialect.PostgreSQLDialect"));
        putSetting(settings, "hibernate.connection.url",
                value(config, "hibernate.connection.url", "POSTGRESQL_HIBERNATE_CONNECTION_URL", null));
        putSetting(settings, "hibernate.connection.username",
                value(config, "hibernate.connection.username", "POSTGRESQL_HIBERNATE_CONNECTION_USERNAME", "POSTGRES_USER", null));
        putSetting(settings, "hibernate.connection.password",
                value(config, "hibernate.connection.password", "POSTGRESQL_HIBERNATE_CONNECTION_PASSWORD", "POSTGRES_PASSWORD", null));
        putSetting(settings, "hibernate.current_session_context_class",
                value(config, "hibernate.current_session_context_class", "POSTGRESQL_HIBERNATE_CURRENT_SESSION_CONTEXT_CLASS", "thread"));
        putSetting(settings, "hibernate.show_sql",
                value(config, "hibernate.show_sql", "POSTGRESQL_HIBERNATE_SHOW_SQL", "false"));
        putSetting(settings, "hibernate.format_sql",
                value(config, "hibernate.format_sql", "POSTGRESQL_HIBERNATE_FORMAT_SQL", "true"));
        putSetting(settings, "hibernate.hbm2ddl.auto",
                value(config, "hibernate.hbm2ddl.auto", "POSTGRESQL_HIBERNATE_HBM2DDL_AUTO", "update"));
        
        // Keep pool implementation internal to the PostgreSQL adapter boundary.
        settings.put("hibernate.hikari.connectionTimeout", String.valueOf(config.getPostgresqlPoolConnectionTimeoutMs()));
        settings.put("hibernate.hikari.minimumIdle", String.valueOf(config.getPostgresqlPoolMinimumIdle()));
        settings.put("hibernate.hikari.maximumPoolSize", String.valueOf(config.getPostgresqlPoolMaximumSize()));
        settings.put("hibernate.hikari.idleTimeout", String.valueOf(config.getPostgresqlPoolIdleTimeoutMs()));
        settings.put("hibernate.hikari.maxLifetime", String.valueOf(config.getPostgresqlPoolMaxLifetimeMs()));
        settings.put("hibernate.hikari.autoCommit", "false");
        settings.put("hibernate.hikari.poolName", "UCEHikariPool");
        settings.put("hibernate.hikari.leakDetectionThreshold", String.valueOf(config.getPostgresqlPoolLeakDetectionThresholdMs()));
        
        // Enable HikariCP as connection provider
        settings.put("hibernate.connection.provider_class", "com.zaxxer.hikari.hibernate.HikariConnectionProvider");
        
        return settings;
    }

    private static void putSetting(HashMap<Object, Object> settings, String key, String value) {
        if (value != null && !value.isBlank()) {
            settings.put(key, value);
        }
    }

    private static String value(CommonConfig config, String property, String envName, String defaultValue) {
        return value(config, property, envName, null, defaultValue);
    }

    private static String value(CommonConfig config, String property, String envName, String aliasEnvName, String defaultValue) {
        String value = config.getPostgresqlProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if ((value == null || value.isBlank()) && aliasEnvName != null) {
            value = System.getenv(aliasEnvName);
        }
        if (value == null || value.isBlank()) {
            value = defaultValue;
        }
        return value;
    }
}
