package org.texttechnologylab.uce.corpusimporter;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.uima.fit.testing.util.DisableLogging;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.util.CasIOUtils;
import org.apache.uima.util.CasLoadMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.texttechnologylab.duui.artifact.DUUIArtifact;
import org.texttechnologylab.duui.artifact.DUUIArtifactEmitter;
import org.texttechnologylab.duui.artifact.DUUIArtifactType;
import org.texttechnologylab.duui.pipeline.DUUIComponent;
import org.texttechnologylab.duui.pipeline.DUUIAdapter;
import org.texttechnologylab.duui.pipeline.DUUIFork;
import org.texttechnologylab.duui.pipeline.DUUIGenerator;
import org.texttechnologylab.duui.runtime.DUUI;
import org.texttechnologylab.duui.runtime.DUUIAdapterScope;
import org.texttechnologylab.duui.runtime.DUUIFlowScope;
import org.texttechnologylab.duui.runtime.DUUIForkScope;
import org.texttechnologylab.duui.runtime.DUUIGeneratorScope;
import org.texttechnologylab.duui.runtime.DUUIPipelineScope;
import org.texttechnologylab.duui.runtime.DUUIStageScope;
import org.texttechnologylab.uce.common.config.CommonConfig;
import org.texttechnologylab.uce.common.config.CorpusConfig;
import org.texttechnologylab.uce.common.config.SpringConfig;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.exceptions.ExceptionUtils;
import org.texttechnologylab.uce.common.models.corpus.Corpus;
import org.texttechnologylab.uce.common.models.corpus.Document;
import org.texttechnologylab.uce.common.models.imp.ImportStatus;
import org.texttechnologylab.uce.common.models.imp.UCEImport;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.AgeGraphService;
import org.texttechnologylab.uce.common.services.EmbeddingService;
import org.texttechnologylab.uce.common.services.LexiconService;
import org.texttechnologylab.uce.common.services.PostgresqlDataInterface_Impl;
import org.texttechnologylab.uce.common.utils.StringUtils;
import org.texttechnologylab.uce.common.utils.SystemStatus;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.security.InvalidParameterException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class DUUIImporter {
    private static final Logger logger = LogManager.getLogger(DUUIImporter.class);
    private static final Gson gson = new Gson();
    private static final int BATCH_SIZE = 2000;
    private static final String[] COMPATIBLE_CAS_FILE_ENDINGS = List.of("xmi", "bz2", "zip", "gz").toArray(new String[0]);
    private static final DUUIArtifactType<UCEImportArtifact> UCE_IMPORT = DUUIArtifactType.of("uce/import");
    private static final DUUIArtifactType<UCECorpusArtifact> UCE_CORPUS = DUUIArtifactType.of("uce/corpus");
    private static final DUUIArtifactType<UCEDocumentArtifact> UCE_DOCUMENT = DUUIArtifactType.of("uce/document");
    private static final DUUIArtifactType<JCasArtifact> JCAS_ARTIFACT = DUUIArtifactType.of("uima/jcas");
    private static final Path EXTERNAL_CORPUS_CONFIG_PATH = Path.of("/app/config/UCECorpusConfigEmpty.json");
    private static final Path LEGACY_CORPUS_CONFIG_PATH = Path.of("uce.corpus-importer/src/main/resources/UCECorpusConfigEmpty.json");

    public static void main(String[] args) throws Exception {
        DisableLogging.enableLogging(Level.SEVERE);

        var options = getOptions();
        var parser = new DefaultParser();
        var cmd = parser.parse(options, args);

        var importSrcPath = cmd.getOptionValue("importSrc");
        var importDirPath = cmd.getOptionValue("importDir");
        var importerNumber = Integer.parseInt(cmd.getOptionValue("importerNumber"));
        var numThreadsStr = cmd.getOptionValue("numThreads");
        var casView = cmd.getOptionValue("casView");
        var corpusConfigJson = cmd.getOptionValue("corpusConfigJson");
        var numThreads = numThreadsStr == null ? 1 : Integer.parseInt(numThreadsStr);

        if (importerNumber != 1) {
            throw new InvalidParameterException("For now, the -importerNumber must always be 1, since this will be the only instance. Canceling.");
        }

        var importablePaths = new ArrayList<String>();
        if (importDirPath == null) {
            importablePaths.add(importSrcPath);
        } else {
            var dir = new File(importDirPath);
            for (var file : Objects.requireNonNull(dir.listFiles())) {
                if (file.isDirectory()) {
                    importablePaths.add(file.getPath());
                }
            }
        }

        try (var springContext = new AnnotationConfigApplicationContext(SpringConfig.class)) {
            DUUIImporter importer = new DUUIImporter();
            for (String path : importablePaths) {
                importer.run(path, importerNumber, numThreads, casView, corpusConfigJson, null, springContext);
            }
        }
    }

    private static Options getOptions() {
        var options = new Options();
        options.addOption("srcDir", "importDir", true, "Directory containing multiple corpus import folders.");
        options.addOption("src", "importSrc", true, "Single corpus import folder with corpusConfig.json and input/.");
        options.addOption("num", "importerNumber", true, "Importer instance number. Currently only 1 is supported.");
        options.addOption("t", "numThreads", true, "Document processing parallelism.");
        options.addOption("view", "casView", true, "Name of the CAS view to import from.");
        options.addOption("config", "corpusConfigJson", true, "Inline corpusConfig.json content for CAS-only execution.");
        return options;
    }

    public void run(String sourcePath,
                    int importerNumber,
                    int numThreads,
                    String casView,
                    String corpusConfigJson,
                    JCas inputCas,
                    AnnotationConfigApplicationContext springContext) throws Exception {
        ImportRuntimeState runtime = new ImportRuntimeState(
                UUID.randomUUID().toString(),
                Math.max(1, importerNumber),
                Math.max(1, numThreads),
                casView,
                sourcePath,
                corpusConfigJson,
                inputCas,
                springContext
        );

        String pipelineId = "uce-importer-" + runtime.importId;
        try (var duui = DUUI.system(runtime.importId)) {
            try (DUUIPipelineScope pipeline = duui.pipeline(pipelineId)) {
                try (DUUIGeneratorScope<UCEImportArtifact> runtimeScope = new RuntimeSeedGenerator(runtime).open(pipeline)) {
                    try (DUUIStageScope<UCEImportArtifact> stage = runtimeScope.linear("runtime-prepare")) {
                        stage.component(component("prepare-import-environment", this::prepareImportEnvironment));
                    }
                    try (DUUIAdapterScope<UCEImportArtifact, UCECorpusArtifact> corpusScope = RuntimeToCorpus.builder().open(runtimeScope)) {
                        try (DUUIStageScope<UCECorpusArtifact> stage = corpusScope.linear("corpus-init")) {
                            stage.component(component("load-corpus-config-and-ensure-corpus", this::loadCorpusConfigAndEnsureCorpus));
                            stage.component(component("initialize-document-import-continuation", this::initializeContinuation));
                        }
                        try (DUUIForkScope<UCECorpusArtifact, UCEDocumentArtifact> documents = CorpusToDocuments.builder().open(corpusScope)) {
                            try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-read")) {
                                stage.component(component("wait-for-batch", this::waitForBatch));
                            }
                            try (DUUIAdapterScope<UCEDocumentArtifact, JCasArtifact> jcasScope = DocumentToJCas.builder(this).open(documents)) {
                                try (DUUIStageScope<JCasArtifact> stage = jcasScope.linear("document-analysis")) {
                                    // analysis engine / v1 components can be inserted here
                                }
                                try (DUUIAdapterScope<JCasArtifact, UCEDocumentArtifact> persistedDocScope = JCasToDocument.builder(this).open(jcasScope)) {
                                    try (DUUIStageScope<UCEDocumentArtifact> stage = persistedDocScope.linear("document-persist")) {
                                        stage.component(component("persist-document", this::persistDocument));
                                        stage.component(component("persist-domain-association-graph", this::persistDomainAssociationGraph));
                                    }
                                }
                            }
                            try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-post")) {
                                stage.component(component("post-process-document-and-batch", this::postProcessDocumentAndBatch));
                            }
                        }
                        try (DUUIStageScope<UCECorpusArtifact> stage = corpusScope.linear("corpus-finalize")) {
                            stage.component(component("finalize-corpus", this::finalizeCorpus));
                        }
                    }
                    try (DUUIStageScope<UCEImportArtifact> stage = runtimeScope.linear("runtime-finalize")) {
                        stage.component(component("finalize-import-environment", this::finalizeImportEnvironment));
                    }
                }
            }
            duui.run(pipelineId);
        }
    }

    private static <T> DUUIComponent<T> component(String id, StageProcessor<T> processor) {
        return DUUIComponent.processor(id, processor::process);
    }

    private DUUIArtifact<UCEImportArtifact> prepareImportEnvironment(DUUIArtifact<UCEImportArtifact> artifact) throws Exception {
        ImportRuntimeState runtime = artifact.payload().runtime;
        runtime.accessManager = runtime.springContext.getBean(DocumentAccessManager.class);
        runtime.adminGuard = runtime.accessManager.asAdmin();
        runtime.db = runtime.springContext.getBean(PostgresqlDataInterface_Impl.class);
        runtime.lexiconService = runtime.springContext.getBean(LexiconService.class);
        runtime.embeddingService = runtime.springContext.getBean(EmbeddingService.class);
        runtime.ageGraphService = runtime.springContext.getBean(AgeGraphService.class);
        var commonConfig = new CommonConfig();
        try {
            SystemStatus.executeExternalDatabaseScripts(commonConfig.getDatabaseScriptsLocation(), runtime.db);
        } catch (Exception ex) {
            logger.warn("Couldn't execute external DB scripts.", ex);
        }

        runtime.preparedImportPath = prepareImportPath(runtime.sourcePath, runtime.corpusConfigJson);
        var uceImport = new UCEImport(runtime.importId, "import from DUUIImporter", ImportStatus.STARTING);
        runtime.db.saveOrUpdateUceImport(uceImport);
        return artifact;
    }

    private DUUIArtifact<UCEImportArtifact> finalizeImportEnvironment(DUUIArtifact<UCEImportArtifact> artifact) {
        ImportRuntimeState runtime = artifact.payload().runtime;
        closeGuardQuietly(runtime);
        if (runtime.preparedImportPath != null && (runtime.sourcePath == null || runtime.sourcePath.isBlank())) {
            cleanupPreparedImportPath(runtime.preparedImportPath);
        }
        return artifact;
    }

    private DUUIArtifact<UCECorpusArtifact> loadCorpusConfigAndEnsureCorpus(DUUIArtifact<UCECorpusArtifact> artifact) throws Exception {
        UCECorpusArtifact work = artifact.payload();
        ImportRuntimeState runtime = work.runtime;
        if (!SystemStatus.PostgresqlDbStatus.isAlive()) {
            throw new DatabaseOperationException("Postgresql DB is not alive - cancelling import.");
        }
        try (var reader = new FileReader(runtime.preparedImportPath.resolve("corpusConfig.json").toFile(), StandardCharsets.UTF_8)) {
            work.corpusConfig = gson.fromJson(reader, CorpusConfig.class);
            try {
                Corpus corpus = new Corpus();
                Corpus existingCorpus = createDBCorpus(corpus, work.corpusConfig, runtime.db);
                work.corpus = existingCorpus == null ? corpus : existingCorpus;
                if (existingCorpus != null && work.corpusConfig.getAnnotations().isUceMetadata()) {
                    runtime.uceMetadataFilters = new CopyOnWriteArrayList<>(runtime.db.getUCEMetadataFiltersByCorpusId(existingCorpus.getId()));
                }
            } catch (DatabaseOperationException e) {
                throw new DatabaseOperationException("Error creating or fetching the corpus from the database - cancelling import.", e);
            }
        } catch (JsonIOException | JsonSyntaxException | IOException e) {
            throw new MissingResourceException("The corpus folder did not contain a properly formatted corpusConfig.json", CorpusConfig.class.toString(), "");
        }
        return artifact;
    }

    private DUUIArtifact<UCECorpusArtifact> initializeContinuation(DUUIArtifact<UCECorpusArtifact> artifact) throws Exception {
        UCECorpusArtifact work = artifact.payload();
        ImportRuntimeState runtime = work.runtime;
        if (runtime.importerNumber == 1 && !runtime.casOnlyRun) {
            var uceImport = runtime.db.getUceImportByImportId(runtime.importId);
            uceImport.setTargetCorpusName(work.corpus.getName());
            uceImport.setTargetCorpusId(work.corpus.getId());
            uceImport.setStatus(ImportStatus.RUNNING);
            runtime.db.saveOrUpdateUceImport(uceImport);
        }
        runtime.docInBatch = new AtomicInteger(0);
        runtime.lock = new Object();
        runtime.batchLatch = new AtomicReference<>(new CountDownLatch(0));
        runtime.continuation = new DocumentImportContinuation(
                runtime.db,
                runtime.lexiconService,
                logger,
                runtime.batchLatch,
                runtime.docInBatch,
                runtime.lock,
                BATCH_SIZE,
                work.corpus,
                work.corpusConfig,
                runtime.importerNumber,
                runtime.importId,
                runtime.embeddingService
        );
        return artifact;
    }

    private DUUIArtifact<UCECorpusArtifact> finalizeCorpus(DUUIArtifact<UCECorpusArtifact> artifact) {
        UCECorpusArtifact work = artifact.payload();
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.db.callLogicalLinksRefresh(),
                (ex) -> logger.error("Error in the final logical links update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.lexiconService.updateLexicon(false),
                (ex) -> logger.error("Error in the final lexicon update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.db.callGeonameLocationRefresh(),
                (ex) -> logger.error("Error in the final geoname location update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.continuation.postProccessCorpus(work.corpus, work.corpusConfig),
                (ex) -> logger.error("Error in the final postprocessing of the current corpus with id " + work.corpus.getId(), ex)
        );
        logger.info("\n\n=================================\n Done with the corpus import.");
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> waitForBatch(DUUIArtifact<UCEDocumentArtifact> artifact) {
        try {
            artifact.payload().corpus.runtime.batchLatch.get().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return artifact;
    }

    private JCas openJCas(String filename, String casView) {
        try (InputStream inputStream = openInputStreamBasedOnExtension(filename)) {
            if (inputStream == null) {
                return null;
            }
            JCas jCas = JCasFactory.createJCas();
            CasIOUtils.load(inputStream, null, jCas.getCas(), CasLoadMode.LENIENT);
            if (casView != null) {
                jCas = jCas.getView(casView);
            }
            return jCas;
        } catch (Exception ex) {
            logger.error("Error while reading annotated XMI file to JCas:", ex);
            return null;
        }
    }

    private Document xmiToDocument(JCas jCas, Corpus corpus, String filePath, String documentId, String casView, ImportRuntimeState runtime) {
        try {
            var metadata = JCasUtil.selectSingle(jCas, DocumentMetaData.class);
            String resolvedId = documentId == null || documentId.isBlank() ? metadata.getDocumentId() : documentId;
            String numericId = resolvedId != null ? resolvedId.replaceAll("\\D+", "") : "";
            if (numericId.isBlank()) {
                numericId = String.valueOf(System.currentTimeMillis());
            }
            metadata.setDocumentId(numericId);

            String language = metadata.getLanguage() == null ? "" : metadata.getLanguage();
            String title = metadata.getDocumentTitle() == null ? "" : metadata.getDocumentTitle();
            Document document = new Document(language, title, numericId, corpus.getId());

            if (runtime.db.documentExists(corpus.getId(), document.getDocumentId())) {
                Document existingDoc = runtime.db.getDocumentByCorpusAndDocumentId(corpus.getId(), document.getDocumentId());
                if (existingDoc != null && !existingDoc.isPostProcessed()) {
                    runtime.continuation.postProccessDocument(existingDoc, corpus, filePath);
                }
                return null;
            }

            document.setMimeType(jCas.getSofaMimeType());
            String mime = document.getMimeType();
            if (mime != null && (mime.equals("application/pdf") || mime.equals("pdf") || mime.startsWith("image/") || mime.equals("image/jpeg") || mime.equals("image/png"))) {
                document.setFullText("");
                if (jCas.getSofaDataStream() != null) {
                    document.setDocumentData(jCas.getSofaDataStream().readAllBytes());
                }
            } else {
                document.setFullText(jCas.getDocumentText());
            }
            return document;
        } catch (Exception ex) {
            logger.error("Error while transforming JCas into document:", ex);
            return null;
        }
    }

    private InputStream openInputStreamBasedOnExtension(String filename) throws IOException {
        if (filename.endsWith(".gz")) {
            return new GZIPInputStream(Files.newInputStream(Path.of(filename)));
        }
        if (filename.endsWith(".bz2")) {
            return new BZip2CompressorInputStream(Files.newInputStream(Path.of(filename)));
        }
        if (filename.endsWith(".zip")) {
            ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(Path.of(filename)));
            ZipEntry entry = zipInputStream.getNextEntry();
            if (entry == null) {
                zipInputStream.close();
                return null;
            }
            return zipInputStream;
        }
        return Files.newInputStream(Path.of(filename));
    }

    private DUUIArtifact<UCEDocumentArtifact> persistDocument(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        work.document = work.corpus.runtime.continuation.saveDocument(work.document, work.filePath);
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> postProcessDocumentAndBatch(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        work.corpus.runtime.continuation.postProcessDocumentAndBatch(work.document, work.filePath);
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> persistDomainAssociationGraph(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        if (work.document == null) {
            return artifact;
        }
        try {
            if (!work.corpus.runtime.domainGraphInitialized) {
                synchronized (work.corpus.runtime) {
                    if (!work.corpus.runtime.domainGraphInitialized) {
                        work.corpus.runtime.ageGraphService.ensureGraph();
                        work.corpus.runtime.domainGraphInitialized = true;
                    }
                }
            }
            var node = new AgeGraphService.DomainNode(
                    "doc:" + work.corpus.corpus.getId() + ":" + work.document.getDocumentId(),
                    Document.class.getName(),
                    work.corpus.corpus.getId(),
                    work.document.getId(),
                    work.document.getDocumentTitle(),
                    null,
                    null,
                    null
            );
            work.corpus.runtime.ageGraphService.upsertDomainNode(node);
        } catch (Exception ex) {
            logger.error("Error persisting domain-association graph data for " + work.filePath, ex);
        }
        return artifact;
    }

    private static void runCorpusPostProcessing(UCECorpusArtifact work) {
        if (work.corpusConfig.getAnnotations().isUnifiedTopic()) {
            logger.info("Inserting into Document and Corpus Topic word tables...");
            try {
                var commonConfig = new CommonConfig();

                Path insertDocumentTopicWordFilePath = Path.of(commonConfig.getDatabaseScriptsLocation(), "topic/3_updateDocumentTopicWord.sql");
                String insertDocumentTopicWordScript = Files.readString(insertDocumentTopicWordFilePath);
                try {
                    work.runtime.db.executeSqlWithoutReturn(insertDocumentTopicWordScript);
                } catch (Exception ex) {
                    logger.error("Error executing SQL script to populate documenttopicword table", ex);
                }

                Path insertCorpusTopicWordFilePath = Path.of(commonConfig.getDatabaseScriptsLocation(), "topic/4_updateCorpusTopicWord.sql");
                String insertCorpusTopicWordScript = Files.readString(insertCorpusTopicWordFilePath);
                try {
                    work.runtime.db.executeSqlWithoutReturn(insertCorpusTopicWordScript);
                } catch (Exception ex) {
                    logger.error("Error executing SQL script to populate corpustopicword table", ex);
                }

                logger.info("Successfully created and populated word based topic tables");
            } catch (Exception e) {
                logger.error("Error reading or executing SQL script for topic distribution", e);
            }
        }
        logger.info("Done with the corpus postprocessing.");
    }

    private static Corpus createDBCorpus(Corpus corpus, CorpusConfig corpusConfig, PostgresqlDataInterface_Impl db) throws DatabaseOperationException, DocumentAccessDeniedException {
        corpus.setName(corpusConfig.getName());
        corpus.setLanguage(corpusConfig.getLanguage());
        corpus.setAuthor(corpusConfig.getAuthor());
        corpus.setCorpusJsonConfig(gson.toJson(corpusConfig));
        if (corpusConfig.isAddToExistingCorpus()) {
            var existingCorpus = db.getCorpusByName(corpusConfig.getName());
            if (existingCorpus != null) {
                return existingCorpus;
            }
            db.saveCorpus(corpus);
            return corpus;
        }
        db.saveCorpus(corpus);
        return null;
    }

    private static Path prepareImportPath(String sourcePath, String corpusConfigJson) throws IOException {
        if (sourcePath != null && !sourcePath.isBlank()) {
            return Path.of(sourcePath);
        }
        Path tempRoot = Files.createTempDirectory("duui-ae-import-");
        Files.createDirectories(tempRoot.resolve("input"));
        Files.writeString(tempRoot.resolve("corpusConfig.json"), resolveCorpusConfigJson(corpusConfigJson), StandardCharsets.UTF_8);
        return tempRoot;
    }

    private static String resolveCorpusConfigJson(String corpusConfigJson) throws IOException {
        if (corpusConfigJson != null && !corpusConfigJson.isBlank()) {
            return corpusConfigJson;
        }
        if (Files.exists(EXTERNAL_CORPUS_CONFIG_PATH)) {
            return Files.readString(EXTERNAL_CORPUS_CONFIG_PATH);
        }
        return Files.readString(LEGACY_CORPUS_CONFIG_PATH);
    }

    private static void cleanupPreparedImportPath(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static void closeGuardQuietly(ImportRuntimeState runtime) {
        if (runtime.adminGuard != null) {
            try {
                runtime.adminGuard.close();
            } catch (Exception ignored) {
            }
            runtime.adminGuard = null;
        }
    }

    @FunctionalInterface
    private interface StageProcessor<T> {
        DUUIArtifact<T> process(DUUIArtifact<T> artifact) throws Exception;
    }

    private static final class RuntimeToCorpus implements DUUIAdapter<UCEImportArtifact, UCECorpusArtifact> {
        static Builder builder() { return new Builder(); }
        @Override
        public DUUIArtifactType<UCEImportArtifact> inputType() { return UCE_IMPORT; }

        @Override
        public DUUIArtifactType<UCECorpusArtifact> outputType() { return UCE_CORPUS; }

        @Override
        public DUUIArtifact<UCECorpusArtifact> adapt(DUUIArtifact<UCEImportArtifact> artifact) {
            return artifact.childArtifact(new UCECorpusArtifact(artifact.payload().runtime), UCE_CORPUS);
        }

        static final class Builder {
            DUUIAdapterScope<UCEImportArtifact, UCECorpusArtifact> open(DUUIFlowScope<UCEImportArtifact> parent) {
                return parent.pipeline().adapter(parent, new RuntimeToCorpus());
            }
        }
    }

    private static final class CorpusToDocuments implements DUUIFork<UCECorpusArtifact, UCEDocumentArtifact> {
        static Builder builder() { return new Builder(); }
        @Override
        public DUUIArtifactType<UCECorpusArtifact> inputType() { return UCE_CORPUS; }

        @Override
        public DUUIArtifactType<UCEDocumentArtifact> outputType() { return UCE_DOCUMENT; }

        @Override
        public void fork(DUUIArtifact<UCECorpusArtifact> artifact, DUUIArtifactEmitter<UCEDocumentArtifact> emitter) throws Exception {
            UCECorpusArtifact corpus = artifact.payload();
            ImportRuntimeState runtime = corpus.runtime;
            if (runtime.sourcePath == null || runtime.sourcePath.isBlank()) {
                var metadata = JCasUtil.selectSingle(runtime.inputCas, DocumentMetaData.class);
                String documentId = metadata.getDocumentId();
                emitter.emit(artifact.childArtifact(UCEDocumentArtifact.mainCas(corpus, documentId), UCE_DOCUMENT));
                return;
            }
            Path inputFolder = runtime.preparedImportPath.resolve("input");
            try (Stream<Path> fileStream = Files.walk(inputFolder)) {
                fileStream.filter(Files::isRegularFile)
                        .filter(path -> StringUtils.checkIfFileHasExtension(path.toString().toLowerCase(), COMPATIBLE_CAS_FILE_ENDINGS))
                        .forEach(path -> emitter.emit(artifact.childArtifact(UCEDocumentArtifact.file(corpus, path), UCE_DOCUMENT)));
            }
        }

        static final class Builder {
            DUUIForkScope<UCECorpusArtifact, UCEDocumentArtifact> open(DUUIFlowScope<UCECorpusArtifact> parent) {
                return parent.pipeline().fork(parent, new CorpusToDocuments());
            }
        }
    }

    private static final class DocumentToJCas implements DUUIAdapter<UCEDocumentArtifact, JCasArtifact> {
        private final DUUIImporter importer;
        private DocumentToJCas(DUUIImporter importer) { this.importer = importer; }
        static Builder builder(DUUIImporter importer) { return new Builder(importer); }
        @Override
        public DUUIArtifactType<UCEDocumentArtifact> inputType() { return UCE_DOCUMENT; }
        @Override
        public DUUIArtifactType<JCasArtifact> outputType() { return JCAS_ARTIFACT; }
        @Override
        public DUUIArtifact<JCasArtifact> adapt(DUUIArtifact<UCEDocumentArtifact> artifact) throws Exception {
            UCEDocumentArtifact source = artifact.payload();
            ImportRuntimeState runtime = source.corpus.runtime;
            JCas selectedCas;
            String resolvedId;
            if (source.mainCasDocument) {
                var metadata = JCasUtil.selectSingle(runtime.inputCas, DocumentMetaData.class);
                resolvedId = metadata.getDocumentId();
                selectedCas = runtime.casView == null ? runtime.inputCas : runtime.inputCas.getView(runtime.casView);
            } else {
                selectedCas = importer.openJCas(source.filePath.toString(), runtime.casView);
                var metadata = JCasUtil.selectSingle(selectedCas, DocumentMetaData.class);
                resolvedId = metadata.getDocumentId();
            }
            source.documentId = resolvedId;
            source.filePath = source.mainCasDocument ? Path.of("DUUI-CAS-Import-" + resolvedId + ".xmi") : source.filePath;
            return artifact.childArtifact(new JCasArtifact(source, selectedCas), JCAS_ARTIFACT);
        }
        static final class Builder {
            private final DUUIImporter importer;
            Builder(DUUIImporter importer) { this.importer = importer; }
            DUUIAdapterScope<UCEDocumentArtifact, JCasArtifact> open(DUUIFlowScope<UCEDocumentArtifact> parent) {
                return parent.pipeline().adapter(parent, new DocumentToJCas(importer));
            }
        }
    }

    private static final class JCasToDocument implements DUUIAdapter<JCasArtifact, UCEDocumentArtifact> {
        private final DUUIImporter importer;
        private JCasToDocument(DUUIImporter importer) { this.importer = importer; }
        static Builder builder(DUUIImporter importer) { return new Builder(importer); }
        @Override
        public DUUIArtifactType<JCasArtifact> inputType() { return JCAS_ARTIFACT; }
        @Override
        public DUUIArtifactType<UCEDocumentArtifact> outputType() { return UCE_DOCUMENT; }
        @Override
        public DUUIArtifact<UCEDocumentArtifact> adapt(DUUIArtifact<JCasArtifact> artifact) throws Exception {
            JCasArtifact source = artifact.payload();
            UCEDocumentArtifact documentArtifact = source.source;
            documentArtifact.document = importer.xmiToDocument(
                    source.jCas,
                    documentArtifact.corpus.corpus,
                    documentArtifact.filePath.toString(),
                    documentArtifact.documentId,
                    documentArtifact.corpus.runtime.casView,
                    documentArtifact.corpus.runtime
            );
            return artifact.childArtifact(documentArtifact, UCE_DOCUMENT);
        }
        static final class Builder {
            private final DUUIImporter importer;
            Builder(DUUIImporter importer) { this.importer = importer; }
            DUUIAdapterScope<JCasArtifact, UCEDocumentArtifact> open(DUUIFlowScope<JCasArtifact> parent) {
                return parent.pipeline().adapter(parent, new JCasToDocument(importer));
            }
        }
    }

    private static final class RuntimeSeedGenerator implements DUUIGenerator<UCEImportArtifact> {
        private final ImportRuntimeState runtime;

        private RuntimeSeedGenerator(ImportRuntimeState runtime) {
            this.runtime = runtime;
        }

        @Override
        public DUUIArtifactType<UCEImportArtifact> outputType() {
            return UCE_IMPORT;
        }

        @Override
        public void generate(DUUIArtifactEmitter<UCEImportArtifact> emitter) throws Exception {
            emitter.emit(DUUIArtifact.of(new UCEImportArtifact(runtime), UCE_IMPORT));
        }

        @Override
        public DUUIGeneratorScope<UCEImportArtifact> open(DUUIPipelineScope pipeline) {
            return pipeline.add(this);
        }
    }

    private static final class ImportRuntimeState {
        final String importId;
        final int importerNumber;
        final int numThreads;
        final String casView;
        final String sourcePath;
        final String corpusConfigJson;
        final JCas inputCas;
        final AnnotationConfigApplicationContext springContext;
        final boolean casOnlyRun;

        Path preparedImportPath;
        DocumentAccessManager accessManager;
        AutoCloseable adminGuard;
        PostgresqlDataInterface_Impl db;
        LexiconService lexiconService;
        EmbeddingService embeddingService;
        AgeGraphService ageGraphService;
        boolean domainGraphInitialized;
        DocumentImportContinuation continuation;
        CopyOnWriteArrayList<?> uceMetadataFilters;
        AtomicReference<CountDownLatch> batchLatch;
        AtomicInteger docInBatch;
        Object lock;

        ImportRuntimeState(String importId, int importerNumber, int numThreads, String casView, String sourcePath, String corpusConfigJson, JCas inputCas, AnnotationConfigApplicationContext springContext) {
            this.importId = importId;
            this.importerNumber = importerNumber;
            this.numThreads = numThreads;
            this.casView = casView;
            this.sourcePath = sourcePath;
            this.corpusConfigJson = corpusConfigJson;
            this.inputCas = inputCas;
            this.springContext = springContext;
            this.casOnlyRun = sourcePath == null || sourcePath.isBlank();
        }
    }

    private static final class UCEImportArtifact {
        final ImportRuntimeState runtime;
        UCEImportArtifact(ImportRuntimeState runtime) { this.runtime = runtime; }
    }

    private static final class UCECorpusArtifact {
        final ImportRuntimeState runtime;
        CorpusConfig corpusConfig;
        Corpus corpus;

        UCECorpusArtifact(ImportRuntimeState runtime) {
            this.runtime = runtime;
        }
    }

    private static final class UCEDocumentArtifact {
        final UCECorpusArtifact corpus;
        final boolean mainCasDocument;
        Path filePath;
        String documentId;
        Document document;

        private UCEDocumentArtifact(UCECorpusArtifact corpus, boolean mainCasDocument, Path filePath, String documentId) {
            this.corpus = corpus;
            this.mainCasDocument = mainCasDocument;
            this.filePath = filePath;
            this.documentId = documentId;
        }

        static UCEDocumentArtifact mainCas(UCECorpusArtifact corpus, String documentId) {
            return new UCEDocumentArtifact(corpus, true, Path.of("DUUI-CAS-Import-" + documentId + ".xmi"), documentId);
        }

        static UCEDocumentArtifact file(UCECorpusArtifact corpus, Path filePath) {
            return new UCEDocumentArtifact(corpus, false, filePath, null);
        }
    }

    private static final class JCasArtifact {
        final UCEDocumentArtifact source;
        final JCas jCas;
        JCasArtifact(UCEDocumentArtifact source, JCas jCas) {
            this.source = source;
            this.jCas = jCas;
        }
    }
}
