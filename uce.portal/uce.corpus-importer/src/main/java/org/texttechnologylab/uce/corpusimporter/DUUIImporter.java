package org.texttechnologylab.uce.corpusimporter;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import de.tudarmstadt.ukp.dkpro.core.api.anomaly.type.Anomaly;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.morph.MorphologicalFeatures;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.uima.fit.testing.util.DisableLogging;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.cas.Feature;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.AnnotationBase;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.util.CasIOUtils;
import org.apache.uima.util.CasLoadMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIPipelineComponent;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIPodmanDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.LuaConsts;
import org.texttechnologylab.annotation.AnnotationComment;
import org.texttechnologylab.annotation.DocumentAnnotation;
import org.texttechnologylab.annotation.Emotion;
import org.texttechnologylab.annotation.SentimentModel;
import org.texttechnologylab.annotation.geonames.GeoNamesEntity;
import org.texttechnologylab.duui.clients.http.DUUIHttpEndpoint;
import org.texttechnologylab.annotation.link.ADLink;
import org.texttechnologylab.annotation.link.DALink;
import org.texttechnologylab.annotation.link.DLink;
import org.texttechnologylab.annotation.ocr.OCRBlock;
import org.texttechnologylab.annotation.ocr.OCRLine;
import org.texttechnologylab.annotation.ocr.OCRPage;
import org.texttechnologylab.annotation.ocr.OCRToken;
import org.texttechnologylab.annotation.uce.Permission;
import org.texttechnologylab.duui.artifact.DUUIArtifact;
import org.texttechnologylab.duui.artifact.DUUIArtifactEmitter;
import org.texttechnologylab.duui.orchestration.scheduling.DUUIDispatchMode;
import org.texttechnologylab.duui.orchestration.scheduling.DUUIDispatchPolicy;
import org.texttechnologylab.duui.pipeline.DUUIAdapter;
import org.texttechnologylab.duui.pipeline.DUUIFork;
import org.texttechnologylab.duui.pipeline.DUUIGenerator;
import org.texttechnologylab.duui.pipeline.DUUILambda;
import org.texttechnologylab.duui.runtime.DUUI;
import org.texttechnologylab.duui.runtime.DUUIAdapterScope;
import org.texttechnologylab.duui.runtime.DUUIFlowScope;
import org.texttechnologylab.duui.runtime.DUUIForkScope;
import org.texttechnologylab.duui.runtime.DUUIGeneratorScope;
import org.texttechnologylab.duui.runtime.DUUIPipelineScope;
import org.texttechnologylab.duui.runtime.DUUIStageScope;
import org.texttechnologylab.duui.pipeline.component.DUUIComponent;
import org.texttechnologylab.duui.pipeline.component.DUUINode;
import org.texttechnologylab.duui.protocol.v1.DUUIV1Annotator;
import org.texttechnologylab.duui.protocol.v1.DUUIV1Config;
import org.texttechnologylab.duui.protocol.v1.DUUIV1TelemetryConfig;
import org.texttechnologylab.duui.dua.archive.DUAArchiveReader;
import org.texttechnologylab.duui.dua.cas.DUAXmiBridge;
import org.texttechnologylab.uce.common.config.CommonConfig;
import org.texttechnologylab.uce.common.config.CorpusConfig;
import org.texttechnologylab.uce.common.config.SpringConfig;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.exceptions.ExceptionUtils;
import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.models.UIMAAnnotation;
import org.texttechnologylab.uce.common.models.biofid.BiofidTaxon;
import org.texttechnologylab.uce.common.models.biofid.GazetteerTaxon;
import org.texttechnologylab.uce.common.models.biofid.GnFinderTaxon;
import org.texttechnologylab.uce.common.models.corpus.Block;
import org.texttechnologylab.uce.common.models.corpus.Corpus;
import org.texttechnologylab.uce.common.models.corpus.GeoName;
import org.texttechnologylab.uce.common.models.corpus.GeoNameFeatureClass;
import org.texttechnologylab.uce.common.models.corpus.Image;
import org.texttechnologylab.uce.common.models.corpus.Lemma;
import org.texttechnologylab.uce.common.models.corpus.Line;
import org.texttechnologylab.uce.common.models.corpus.MetadataTitleInfo;
import org.texttechnologylab.uce.common.models.corpus.NamedEntity;
import org.texttechnologylab.uce.common.models.corpus.Page;
import org.texttechnologylab.uce.common.models.corpus.Paragraph;
import org.texttechnologylab.uce.common.models.corpus.Sentence;
import org.texttechnologylab.uce.common.models.corpus.Sentiment;
import org.texttechnologylab.uce.common.models.corpus.SrLink;
import org.texttechnologylab.uce.common.models.corpus.Time;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadata;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadataFilter;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadataValueType;
import org.texttechnologylab.uce.common.models.corpus.WikiDataHyponym;
import org.texttechnologylab.uce.common.models.corpus.WikipediaLink;
import org.texttechnologylab.uce.common.models.corpus.Document;
import org.texttechnologylab.uce.common.models.corpus.emotion.Feeling;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationLink;
import org.texttechnologylab.uce.common.models.corpus.links.AnnotationToDocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentLink;
import org.texttechnologylab.uce.common.models.corpus.links.DocumentToAnnotationLink;
import org.texttechnologylab.uce.common.models.corpus.ocr.OCRPageAdapterImpl;
import org.texttechnologylab.uce.common.models.corpus.ocr.PageAdapter;
import org.texttechnologylab.uce.common.models.corpus.ocr.PageAdapterImpl;
import org.texttechnologylab.uce.common.models.imp.ImportLog;
import org.texttechnologylab.uce.common.models.imp.ImportStatus;
import org.texttechnologylab.uce.common.models.imp.LogStatus;
import org.texttechnologylab.uce.common.models.imp.UCEImport;
import org.texttechnologylab.uce.common.models.negation.*;
import org.texttechnologylab.uce.common.models.topic.TopicValueBase;
import org.texttechnologylab.uce.common.models.topic.TopicValueBaseWithScore;
import org.texttechnologylab.uce.common.models.topic.TopicWord;
import org.texttechnologylab.uce.common.models.topic.UnifiedTopic;
import org.texttechnologylab.uce.common.security.DocumentAccessManager;
import org.texttechnologylab.uce.common.services.AgeGraphService;
import org.texttechnologylab.uce.common.services.DataInterface;
import org.texttechnologylab.uce.common.services.DomainGraphService;
import org.texttechnologylab.uce.common.services.DuaDataInterface_Impl;
import org.texttechnologylab.uce.common.services.EmbeddingService;
import org.texttechnologylab.uce.common.services.GoetheUniversityService;
import org.texttechnologylab.uce.common.services.JenaSparqlService;
import org.texttechnologylab.uce.common.services.LexiconService;
import org.texttechnologylab.uce.common.services.S3StorageService;
import org.texttechnologylab.uce.common.services.StorageMaintenanceService;
import org.texttechnologylab.uce.common.utils.StringUtils;
import org.texttechnologylab.uce.common.utils.SystemStatus;
import org.texttechnologylab.uce.common.utils.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.security.InvalidParameterException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Comparator;

public class DUUIImporter {
    private static final Logger logger = LogManager.getLogger(DUUIImporter.class);
    private static final Gson gson = new Gson();
    private static final int BATCH_SIZE = 2000;
    private static final String[] COMPATIBLE_CAS_FILE_ENDINGS = List.of("xmi", "bz2", "zip", "gz", "dua").toArray(new String[0]);
    private static final Set<String> WANTED_NE_TYPES = Set.of("LOCATION", "MISC", "PERSON", "ORGANIZATION");
    private static final Set<String> MIME_TYPES_PDF = Set.of("application/pdf", "pdf");
    private static final String MIME_TYPE_IMAGE_PREFIX = "image/";
    private static final Set<String> MIME_TYPES_IMAGES = Set.of("image/jpeg", "image/png");
    private static final String UCE_IMPORT_TYPE = "org.texttechnologylab.annotation.uce.UCEImport";
    private static final String UCE_CORPUS_TYPE = "org.texttechnologylab.annotation.uce.UCECorpus";
    private static final String UCE_DOCUMENT_TYPE = "org.texttechnologylab.annotation.uce.UCEDocument";
    private static final String UCE_PAGE_TYPE = "org.texttechnologylab.annotation.uce.UCEPage";
    private static final String UCE_VIEW_TYPE = "org.texttechnologylab.annotation.uce.UCEView";
    private static final String UCE_TYPE_TYPE = "org.texttechnologylab.annotation.uce.UCEType";
    private static final String UCE_ANNOTATION_TYPE = "org.texttechnologylab.annotation.uce.UCEAnnotation";
    private static final String UCE_OPERATION_TYPE = "org.texttechnologylab.annotation.uce.UCEOperation";
    private static final String ASSOCIATION_TYPE = "org.texttechnologylab.annotation.artifact.Association";
    private static final String MEMBERSHIP_TYPE = "org.texttechnologylab.annotation.artifact.Membership";
    private static final String UCE_IMPORT = "uce/import";
    private static final String UCE_CORPUS = "uce/corpus";
    private static final String UCE_DOCUMENT = "uce/document";
    private static final String JCAS_ARTIFACT = "uima/jcas";
    private static final String GNFINDER_ENABLED_ENV = "UCE_DUUI_GNFINDER_ENABLED";
    private static final String GNFINDER_IMAGE_ENV = "UCE_DUUI_GNFINDER_IMAGE";
    private static final String GNFINDER_IMAGE_PROPERTY = "uce.duui.gnfinder.image";
    private static final String GNFINDER_DEFAULT_IMAGE = "localhost/duui-py-gnfinder:latest";
    private static final Path EXTERNAL_CORPUS_CONFIG_PATH = Path.of("/app/config/UCECorpusConfigEmpty.json");
    private static final Path LEGACY_CORPUS_CONFIG_PATH = Path.of("uce.corpus-importer/src/main/resources/UCECorpusConfigEmpty.json");
    private GoetheUniversityService goetheUniversityService;
    private DataInterface db;
    private EmbeddingService embeddingService;
    private JenaSparqlService jenaSparqlService;
    private S3StorageService s3StorageService;
    private LexiconService lexiconService;
    private final CommonConfig commonConfig = new CommonConfig();
    private final DUAXmiBridge duaBridge = new DUAXmiBridge();
    private String importId;
    private Integer importerNumber;
    private List<UCEMetadataFilter> uceMetadataFilters = new CopyOnWriteArrayList<>();
    private DocumentImportContinuation continuation;

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

        boolean forceCliExit = gnFinderPodmanEnabled();
        int exitCode = 0;
        try {
            try (var springContext = new AnnotationConfigApplicationContext(SpringConfig.class)) {
                DUUIImporter importer = new DUUIImporter();
                for (String path : importablePaths) {
                    importer.run(path, importerNumber, numThreads, casView, corpusConfigJson, null, springContext);
                }
            }
        } catch (Exception ex) {
            exitCode = 1;
            logger.error("DUUIImporter failed.", ex);
            throw ex;
        } finally {
            if (forceCliExit) {
                logger.info("Forcing DUUIImporter CLI exit after Podman driver shutdown.");
                System.exit(exitCode);
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
        DUUIDispatchPolicy importerIo = DUUIDispatchPolicy.of(DUUIDispatchMode.IO, runtime.numThreads);
        try (var duui = DUUI.system(runtime.importId)) {
            try (DUUIPipelineScope pipeline = duui.pipeline(pipelineId)) {
                    try (DUUIGeneratorScope<UCEImportArtifact> runtimeScope = new RuntimeSeedGenerator(runtime).open(pipeline)) {
                        try (DUUIStageScope<UCEImportArtifact> stage = runtimeScope.linear("runtime-prepare")) {
                        stage.dispatchPolicy(importerIo);
                        stage.lambda(lambda(UCE_IMPORT, "prepare-import-environment", this::prepareImportEnvironment));
                    }
                    try (DUUIAdapterScope<UCEImportArtifact, UCECorpusArtifact> corpusScope = RuntimeToCorpus.builder().open(runtimeScope)) {
                        try (DUUIStageScope<UCECorpusArtifact> stage = corpusScope.linear("corpus-init")) {
                            stage.dispatchPolicy(importerIo);
                            stage.lambda(lambda(UCE_CORPUS, "load-corpus-config-and-ensure-corpus", this::loadCorpusConfigAndEnsureCorpus));
                            stage.lambda(lambda(UCE_CORPUS, "initialize-document-import-continuation", this::initializeContinuation));
                        }
                        try (DUUIForkScope<UCECorpusArtifact, UCEDocumentArtifact> documents = CorpusToDocuments.builder().open(corpusScope)) {
                            try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-read")) {
                                stage.dispatchPolicy(importerIo);
                                stage.lambda(lambda(UCE_DOCUMENT, "wait-for-batch", this::waitForBatch));
                            }
                            try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-jcas")) {
                                stage.dispatchPolicy(importerIo);
                                stage.lambda(lambda(UCE_DOCUMENT, "open-document-jcas", this::openDocumentJCas));
                                stage.lambda(lambda(UCE_DOCUMENT, "annotate-gnfinder-podman", this::annotateGnfinderPodman));
                                stage.lambda(lambda(UCE_DOCUMENT, "initialize-document-shell", this::initializeDocumentShell));
                            }
                                    try (DUUIStageScope<UCEDocumentArtifact> stage = documents.parallel("document-independent-extraction")) {
                                        stage.dispatchPolicy(importerIo);
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-uce-metadata", this::extractUceMetadata));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-sentences", this::extractSentences));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-named-entities", this::extractNamedEntities));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-sentiments", this::extractSentiments));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-emotions", this::extractEmotions));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-lemmata", this::extractLemmata));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-srl", this::extractSemanticRoleLabels));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-times", this::extractTimes));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-taxonomy", this::extractTaxonomy));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-wikilinks", this::extractWikiLinks));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-negation", this::extractCompleteNegations));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-topics", this::extractUnifiedTopics));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-images", this::extractImages));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-permissions", this::extractPermissions));
                                    }
                                    try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-dependent-extraction")) {
                                        stage.dispatchPolicy(importerIo);
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-geonames", this::extractGeoNames));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-pages", this::extractPages));
                                        stage.lambda(lambda(UCE_DOCUMENT, "extract-logical-links", this::extractLogicLinks));
                                    }
                                    try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-domain-capture")) {
                                        stage.dispatchPolicy(importerIo);
                                        stage.lambda(lambda(UCE_DOCUMENT, "capture-uce-document-domains", this::captureUceDocumentDomains));
                                    }
                                    try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-persist")) {
                                        stage.dispatchPolicy(importerIo);
                                        stage.lambda(lambda(UCE_DOCUMENT, "persist-document", this::persistDocument));
                                        stage.lambda(lambda(UCE_DOCUMENT, "persist-domain-association-graph", this::persistDomainAssociationGraph));
                                    }
                            try (DUUIStageScope<UCEDocumentArtifact> stage = documents.linear("document-post")) {
                                stage.dispatchPolicy(importerIo);
                                stage.lambda(lambda(UCE_DOCUMENT, "post-process-document-and-batch", this::postProcessDocumentAndBatch));
                            }
                        }
                        try (DUUIStageScope<UCECorpusArtifact> stage = corpusScope.linear("corpus-finalize")) {
                            stage.dispatchPolicy(importerIo);
                            stage.lambda(lambda(UCE_CORPUS, "finalize-corpus", this::finalizeCorpus));
                        }
                    }
                }
            }
            var result = duui.run(pipelineId);
            logger.info("DUUIImporter pipeline completed results=" + result.results().size()
                    + " failures=" + result.hasFailures()
                    + " unroutable=" + result.unroutableArtifacts().size());
            result.results().stream()
                    .filter(item -> item.failure() != null)
                    .forEach(item -> logger.error("DUUIImporter stage failure status=" + item.status()
                            + " stage=" + item.failure().stageId()
                            + " checkpoint=" + item.failure().checkpointId()
                            + " artifact=" + item.failure().artifactId()
                            + " message=" + item.failure().message(), item.failure().cause()));
        } finally {
            flushDuaStoreQuietly(runtime);
            closeGuardQuietly(runtime);
            flushDuaStoreQuietly(runtime);
            if (runtime.preparedImportPath != null && (runtime.sourcePath == null || runtime.sourcePath.isBlank())) {
                cleanupPreparedImportPath(runtime.preparedImportPath);
            }
        }
    }

    private static <T> DUUILambda<T> lambda(String inputType, String operationName, StageProcessor<T> processor) {
        return DUUILambda.<T>builder(inputType + ":" + operationName)
                .processor(artifact -> {
                Object payload = artifact.payload();
                ImportRuntimeState runtime = runtimeFromPayload(payload);
                long started = System.currentTimeMillis();
                try {
                    DUUIArtifact<T> result = processor.process(artifact);
                    recordOperation(runtime, payload, operationName, "COMPLETED", 0, null, started);
                    return result;
                } catch (Exception ex) {
                    recordOperation(runtime, payload, operationName, "FAILED", 0, ex.getMessage(), started);
                    throw ex;
                }
                })
                .build();
    }

    private static ImportRuntimeState runtimeFromPayload(Object payload) {
        if (payload instanceof UCEImportArtifact importArtifact) return importArtifact.runtime;
        if (payload instanceof UCECorpusArtifact corpusArtifact) return corpusArtifact.runtime;
        if (payload instanceof UCEDocumentArtifact documentArtifact) return documentArtifact.corpus.runtime;
        if (payload instanceof JCasArtifact jCasArtifact) return jCasArtifact.source.corpus.runtime;
        return null;
    }

    private static void recordOperation(ImportRuntimeState runtime, Object payload, String operationName, String status, int retries, String error, long started) {
        if (runtime == null) {
            return;
        }
        String corpusDomainId = null;
        String documentDomainId = null;
        if (payload instanceof UCECorpusArtifact corpusArtifact && corpusArtifact.corpus != null) {
            corpusDomainId = "corpus:" + corpusArtifact.corpus.getId();
        } else if (payload instanceof UCEDocumentArtifact documentArtifact && documentArtifact.document != null) {
            corpusDomainId = "corpus:" + documentArtifact.corpus.corpus.getId();
            documentDomainId = corpusDomainId + ":document:" + documentArtifact.document.getDocumentId();
        }
        runtime.operations.add(new ImportOperationRecord(operationName, status, retries, error, started, System.currentTimeMillis(), corpusDomainId, documentDomainId));
    }

    private DUUIArtifact<UCEImportArtifact> prepareImportEnvironment(DUUIArtifact<UCEImportArtifact> artifact) throws Exception {
        ImportRuntimeState runtime = artifact.payload().runtime;
        runtime.accessManager = runtime.springContext.getBean(DocumentAccessManager.class);
        runtime.adminGuard = runtime.accessManager.asAdmin();
        runtime.db = runtime.springContext.getBean(DataInterface.class);
        runtime.storageMaintenance = runtime.springContext.getBean(StorageMaintenanceService.class);
        runtime.lexiconService = runtime.springContext.getBean(LexiconService.class);
        runtime.embeddingService = runtime.springContext.getBean(EmbeddingService.class);
        runtime.ageGraphService = runtime.springContext.getBean(DomainGraphService.class);
        this.db = runtime.db;
        this.lexiconService = runtime.lexiconService;
        this.embeddingService = runtime.embeddingService;
        this.goetheUniversityService = runtime.springContext.getBean(GoetheUniversityService.class);
        this.jenaSparqlService = runtime.springContext.getBean(JenaSparqlService.class);
        this.s3StorageService = runtime.springContext.getBean(S3StorageService.class);
        this.importId = runtime.importId;
        this.importerNumber = runtime.importerNumber;
        try {
            SystemStatus.executeExternalDatabaseScripts(commonConfig.getDatabaseScriptsLocation(), runtime.storageMaintenance);
        } catch (Exception ex) {
            logger.warn("Couldn't execute external DB scripts.", ex);
        }

        runtime.preparedImportPath = prepareImportPath(runtime.sourcePath, runtime.corpusConfigJson);
        var uceImport = new UCEImport(runtime.importId, "import from DUUIImporter", ImportStatus.STARTING);
        runtime.db.saveOrUpdateUceImport(uceImport);
        recordImportDomain(runtime);
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
                    this.uceMetadataFilters = runtime.uceMetadataFilters;
                }
            } catch (DatabaseOperationException e) {
                throw new DatabaseOperationException("Error creating or fetching the corpus from the database - cancelling import.", e);
            }
        } catch (JsonIOException | JsonSyntaxException | IOException e) {
            throw new MissingResourceException("The corpus folder did not contain a properly formatted corpusConfig.json", CorpusConfig.class.toString(), "");
        }
        recordCorpusDomain(work);
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
                runtime.storageMaintenance,
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
        this.continuation = runtime.continuation;
        return artifact;
    }

    private DUUIArtifact<UCECorpusArtifact> finalizeCorpus(DUUIArtifact<UCECorpusArtifact> artifact) {
        UCECorpusArtifact work = artifact.payload();
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.storageMaintenance.refreshLogicalLinks(),
                (ex) -> logger.error("Error in the final logical links update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.lexiconService.updateLexicon(false),
                (ex) -> logger.error("Error in the final lexicon update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.storageMaintenance.refreshGeonameLocations(),
                (ex) -> logger.error("Error in the final geoname location update of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> work.runtime.continuation.postProccessCorpus(work.corpus, work.corpusConfig),
                (ex) -> logger.error("Error in the final postprocessing of the current corpus with id " + work.corpus.getId(), ex)
        );
        ExceptionUtils.tryCatchLog(
                () -> {
                    persistCorpusLevelUceGraph(work);
                    persistCorpusOperationGraph(work);
                },
                (ex) -> logger.error("Error persisting import operation graph for corpus with id " + work.corpus.getId(), ex)
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

    private DUUIArtifact<UCEDocumentArtifact> openDocumentJCas(DUUIArtifact<UCEDocumentArtifact> artifact) throws Exception {
        UCEDocumentArtifact work = artifact.payload();
        ImportRuntimeState runtime = work.corpus.runtime;
        if (work.mainCasDocument) {
            var metadata = JCasUtil.selectSingle(runtime.inputCas, DocumentMetaData.class);
            work.documentId = metadata.getDocumentId();
            work.filePath = Path.of("DUUI-CAS-Import-" + work.documentId + ".xmi");
            work.jCas = runtime.casView == null ? runtime.inputCas : runtime.inputCas.getView(runtime.casView);
        } else {
            work.jCas = openJCas(work.filePath.toString(), runtime.casView);
            if (work.jCas == null) {
                logImportError("Unable to read XMI into JCas.", null, work.filePath.toString());
                return artifact;
            }
            var metadata = JCasUtil.selectSingle(work.jCas, DocumentMetaData.class);
            work.documentId = metadata.getDocumentId();
        }
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> annotateGnfinderPodman(DUUIArtifact<UCEDocumentArtifact> artifact) throws Exception {
        UCEDocumentArtifact work = artifact.payload();
        if (work.jCas == null || !gnFinderPodmanEnabled()) {
            return artifact;
        }
        work.corpus.runtime.gnfinderPodman().process(work.jCas);
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> initializeDocumentShell(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        if (work.jCas == null) {
            return artifact;
        }
        work.document = xmiToDocument(
                work.jCas,
                work.corpus.corpus,
                work.filePath.toString(),
                work.documentId,
                work.corpus.runtime.casView,
                work.corpus.runtime
        );
        return artifact;
    }

    private JCas openJCas(String filename, String casView) {
        if (filename.endsWith(".dua")) {
            return openDuaJCas(Path.of(filename), casView);
        }
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

    private JCas openDuaJCas(Path path, String casView) {
        try (DUAArchiveReader reader = DUAArchiveReader.open(path)) {
            String documentId = reader.manifest().getArtifacts().stream()
                    .filter(entry -> "cas-xmi".equals(entry.kind()))
                    .map(entry -> entry.id())
                    .findFirst()
                    .orElseThrow(() -> new IOException("DUA archive has no cas-xmi artifact: " + path));
            JCas jCas = duaBridge.materialize(reader, documentId);
            return casView == null ? jCas : jCas.getView(casView);
        } catch (Exception ex) {
            logger.error("Error while reading annotated DUA file to JCas:", ex);
            return null;
        }
    }

    private Document xmiToDocument(JCas jCas, Corpus corpus, String filePath, String documentId, String casView, ImportRuntimeState runtime) {
        logger.info("=============================== Importing a new CAS as a Document. ===============================");

        var unique = new HashSet<String>();
        JCasUtil.select(jCas, AnnotationBase.class).forEach(a -> unique.add(a.getType().getName()));

        try {
            var corpusConfig = gson.fromJson(corpus.getCorpusJsonConfig(), CorpusConfig.class);
            var metadata = JCasUtil.selectSingle(jCas, DocumentMetaData.class);
            if (metadata == null) {
                return null;
            }
            if (documentId != null) {
                logger.info("Setting document id from \"" + metadata.getDocumentId() + "\" to \"" + documentId + "\"");
                metadata.setDocumentId(documentId);
            }
            var rawDocId = metadata.getDocumentId();
            var numericDocId = rawDocId != null ? rawDocId.replaceAll("\\D+", "") : "";
            if (numericDocId.isBlank()) {
                numericDocId = String.valueOf(System.currentTimeMillis());
                logger.warn("DocumentId \"" + rawDocId + "\" is non-numeric; falling back to " + numericDocId);
            } else if (!numericDocId.equals(rawDocId)) {
                logger.info("Coerced non-numeric documentId \"" + rawDocId + "\" to \"" + numericDocId + "\"");
            }
            metadata.setDocumentId(numericDocId);

            var document = new Document(metadata.getLanguage(),
                    metadata.getDocumentTitle(),
                    metadata.getDocumentId(),
                    corpus.getId());
            logger.info("Setting Metadata done.");
            logImportInfo("Importing " + document.getDocumentId(), LogStatus.CAS_IMPORT, filePath, 0);
            var start = System.currentTimeMillis();

            var exists = db.documentExists(corpus.getId(), document.getDocumentId());
            if (exists) {
                logger.info("Document with id " + document.getDocumentId()
                        + " already exists in the corpus " + corpus.getId() + ".");
                logger.info("Checking if that document was also post-processed yet...");
                var existingDoc = db.getDocumentByCorpusAndDocumentId(corpus.getId(), document.getDocumentId());
                if (!existingDoc.isPostProcessed()) {
                    logger.info("Not yet post-processed. Doing that now.");
                    this.continuation.postProccessDocument(existingDoc, corpus, filePath);
                } else {
                    logger.info("Document was already post-processed.");
                }
                logger.info("Done.");
                return null;
            }

            document.setMimeType(jCas.getSofaMimeType());
            if (MIME_TYPES_PDF.contains(document.getMimeType())) {
                document.setFullText("");
                byte[] pdfBytes = jCas.getSofaDataStream().readAllBytes();
                document.setDocumentData(pdfBytes);
                logger.info("Document is a PDF: " + document.getMimeType() + " of length " + pdfBytes.length);
            } else if (document.getMimeType().startsWith(MIME_TYPE_IMAGE_PREFIX) || MIME_TYPES_IMAGES.contains(document.getMimeType())) {
                document.setFullText("");
                byte[] imageBytes = jCas.getSofaDataStream().readAllBytes();
                document.setDocumentData(imageBytes);
                logger.info("Document is an image: " + document.getMimeType() + " of length " + imageBytes.length);
            } else {
                document.setFullText(jCas.getDocumentText());
                logger.info("Setting full text done.");
            }

            setMetadataTitleInfo(document, jCas, corpusConfig);

            if (corpusConfig.getOther() != null && corpusConfig.getOther().isEnableS3Storage()) {
                var fileExtension = StringUtils.getFileExtension(filePath);
                var contentType = StringUtils.getContentTypeByExtension(fileExtension);
                var minioObjectName = this.s3StorageService.buildCasXmiObjectName(corpus.getId(), document.getDocumentId());
                ExceptionUtils.tryCatchLog(
                        () -> this.s3StorageService.uploadCasInputStream(
                                Objects.requireNonNull(openInputStreamBasedOnExtension(filePath)), minioObjectName, contentType, new HashMap<>()),
                        (ex) -> logImportWarn("Was not able to upload XMI to S3 Storage!", ex, filePath));
            }

            var duration = System.currentTimeMillis() - start;
            logImportInfo("Successfully initialized document shell from " + filePath, LogStatus.FINISHED, filePath, duration);

            return document;
        } catch (Exception ex) {
            logImportError("Unknown error while importing a CAS into a document. This shouldn't happen, as each operation has its own error handling.", ex, filePath);
            return null;
        } finally {
            logger.info("Finished with importing that CAS.\n\n\n");
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

    private DUUIArtifact<UCEDocumentArtifact> extractUceMetadata(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "UceMetadata", config -> config.getAnnotations().isUceMetadata(),
                work -> setUceMetadata(work.document, work.jCas, work.corpus.corpus.getId()));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractSentences(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "sentence", config -> config.getAnnotations().isSentence(),
                work -> setSentences(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractNamedEntities(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "ner", config -> config.getAnnotations().isNamedEntity(),
                work -> setNamedEntities(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractGeoNames(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "GeoNames", config -> config.getAnnotations().isNamedEntity() && config.getAnnotations().isGeoNames(),
                work -> setGeoNames(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractSentiments(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "Sentiment", config -> config.getAnnotations().isSentiment(),
                work -> setSentiments(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractEmotions(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "Emotion", config -> config.getAnnotations().isEmotion(),
                work -> setEmotions(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractLemmata(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "lemmata", config -> config.getAnnotations().isLemma(),
                work -> setLemmata(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractSemanticRoleLabels(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "SRL", config -> config.getAnnotations().isSrLink(),
                work -> setSemanticRoleLabels(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractTimes(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "time", config -> config.getAnnotations().isTime(),
                work -> setTimes(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractTaxonomy(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "taxon", config -> config.getAnnotations().getTaxon().isAnnotated(),
                work -> setTaxonomy(work.document, work.jCas, work.corpus.corpusConfig));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractWikiLinks(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "wiki links", config -> config.getAnnotations().isWikipediaLink(),
                work -> setWikiLinks(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractCompleteNegations(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "negation", config -> config.getAnnotations().isCompleteNegation(),
                work -> setCompleteNegations(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractUnifiedTopics(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "UnifiedTopic", config -> config.getAnnotations().isUnifiedTopic(),
                work -> setUnifiedTopic(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractLogicLinks(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "LinkTypesystem", config -> config.getAnnotations().isLogicalLinks(),
                work -> setLogicLinks(work.document, work.jCas, work.corpus.corpus.getId(), work.filePath.toString()));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractPages(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "OCRPage", config -> true,
                work -> {
                    setPages(work.document, work.jCas, work.corpus.corpusConfig);
                    recordPageDomains(work);
                });
    }

    private DUUIArtifact<UCEDocumentArtifact> extractImages(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "image", config -> config.getAnnotations().isImage(),
                work -> setImages(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractPermissions(DUUIArtifact<UCEDocumentArtifact> artifact) {
        return extractDocumentAnnotation(artifact, "document permissions", config -> true,
                work -> setPermissions(work.document, work.jCas));
    }

    private DUUIArtifact<UCEDocumentArtifact> extractDocumentAnnotation(DUUIArtifact<UCEDocumentArtifact> artifact,
                                                                       String label,
                                                                       java.util.function.Predicate<CorpusConfig> enabled,
                                                                       DocumentExtraction extraction) {
        UCEDocumentArtifact work = artifact.payload();
        if (work.document == null || work.jCas == null || !enabled.test(work.corpus.corpusConfig)) {
            return artifact;
        }
        ExceptionUtils.tryCatchLog(
                () -> extraction.extract(work),
                (ex) -> logImportWarn("This file should have contained " + label + " annotations, but selecting them caused an error.", ex, work.filePath.toString()));
        return artifact;
    }

    private DUUIArtifact<UCEDocumentArtifact> captureUceDocumentDomains(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        if (work.document == null || work.jCas == null) {
            return artifact;
        }
        recordDocumentDomain(work);
        recordViewDomain(work);
        recordPageDomains(work);
        recordTypeAndAnnotationDomains(work);
        return artifact;
    }

    @FunctionalInterface
    private interface DocumentExtraction {
        void extract(UCEDocumentArtifact work) throws Exception;
    }

    private void setPermissions(Document document, JCas jCas) {
        List<Permission> permissions = new ArrayList<>(JCasUtil.select(jCas, Permission.class));
        logger.info("Setting " + permissions.size() + " permissions on the document.");
        for (Permission permission : permissions) {
            DocumentPermission docPermission = new DocumentPermission();
            docPermission.setType(DocumentPermission.DOCUMENT_PERMISSION_TYPE.valueOf(permission.getPermissionType()));
            docPermission.setLevel(DocumentPermission.DOCUMENT_PERMISSION_LEVEL.valueOf(permission.getPermissionLevel()));
            docPermission.setName(permission.getUser());
            document.addPermission(docPermission);
        }
        logger.info("Finished setting permissions.");
    }

    private void setEmotions(Document document, JCas jCas) {
        var emotions = new ArrayList<org.texttechnologylab.uce.common.models.corpus.emotion.Emotion>();
        JCasUtil.select(jCas, Emotion.class).forEach(e -> {
            var emotion = new org.texttechnologylab.uce.common.models.corpus.emotion.Emotion(e.getBegin(), e.getEnd());
            emotion.setCoveredText(e.getCoveredText());
            var meta = e.getModel();
            if (meta != null) emotion.setModel(meta.getModelName() + "__v::" + meta.getModelVersion());
            var feelings = new ArrayList<Feeling>();
            for (var annotationComment : e.getEmotions()) {
                var feeling = new Feeling();
                feeling.setEmotion(emotion);
                ExceptionUtils.tryCatchLog(() -> feeling.setValue(Double.parseDouble(annotationComment.getValue())), (ex) -> {});
                feeling.setFeeling(annotationComment.getKey());
                feelings.add(feeling);
            }
            emotion.setFeelings(feelings);
            emotions.add(emotion);
        });
        document.setEmotions(emotions);
        logger.info("Setting Emotions done.");
    }

    private void setSentiments(Document document, JCas jCas) {
        var sentiments = new ArrayList<Sentiment>();
        JCasUtil.select(jCas, SentimentModel.class).forEach(s -> {
            var sentiment = new Sentiment(s.getBegin(), s.getEnd());
            sentiment.setCoveredText(s.getCoveredText());
            sentiment.setPositive(s.getProbabilityPositive());
            sentiment.setNeutral(s.getProbabilityNeutral());
            sentiment.setNegative(s.getProbabilityNegative());
            var meta = s.getModel();
            if (meta != null) sentiment.setModel(meta.getModelName() + "__v::" + meta.getModelVersion());
            sentiments.add(sentiment);
        });
        document.setSentiments(sentiments);
        logger.info("Setting Sentiments done.");
    }

    private void setGeoNames(Document document, JCas jCas) {
        var geoNames = new ArrayList<GeoName>();
        JCasUtil.select(jCas, GeoNamesEntity.class).forEach(g -> {
            var geoName = new GeoName(g.getBegin(), g.getEnd());
            geoName.setCoveredText(g.getCoveredText());
            geoName.setName(g.getName());
            geoName.setFeatureClass(GeoNameFeatureClass.valueOf(g.getFeatureClass()));
            geoName.setFeatureCode(g.getFeatureCode());
            geoName.setCountryCode(g.getCountryCode());
            geoName.setAdm1(g.getAdm1());
            geoName.setAdm2(g.getAdm2());
            geoName.setAdm3(g.getAdm3());
            geoName.setAdm4(g.getAdm4());
            geoName.setLatitude(g.getLatitude());
            geoName.setLongitude(g.getLongitude());
            geoName.setElevation(g.getElevation());
            var referenceNE = g.getReferenceAnnotation();
            if (referenceNE != null) {
                var ne = document.getNamedEntities().stream().filter(
                        n -> n.getBegin() == referenceNE.getBegin()
                                && n.getEnd() == referenceNE.getEnd()
                                && n.getType().equals("LOCATION")).findFirst();
                if (ne.isPresent()) {
                    geoName.setRefNamedEntity(ne.get());
                    ne.get().setGeoName(geoName);
                }
            }
            geoNames.add(geoName);
        });
        document.setGeoNames(geoNames);
        logger.info("Setting GeoNames done.");
    }

    private void setLogicLinks(Document document, JCas jCas, long corpusId, String filePath) throws DatabaseOperationException, DocumentAccessDeniedException {
        var documentLinks = new ArrayList<DocumentLink>();
        JCasUtil.select(jCas, DLink.class).forEach(l -> {
            var docLink = new DocumentLink();
            docLink.setFrom(l.getFrom());
            docLink.setTo(l.getTo());
            docLink.setLinkId(String.valueOf(l.getLinkId()));
            docLink.setType(l.getLinkType());
            docLink.setCorpusId(corpusId);
            docLink.setFromAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(Document.class));
            docLink.setFromAnnotationType(Document.class.getName());
            docLink.setToAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(Document.class));
            docLink.setToAnnotationType(Document.class.getName());
            documentLinks.add(docLink);
        });
        db.saveOrUpdateManyDocumentLinks(documentLinks);

        var documentToAnnotationLinks = new ArrayList<DocumentToAnnotationLink>();
        JCasUtil.select(jCas, DALink.class).forEach(l -> {
            var docToAnnoLink = new DocumentToAnnotationLink();
            docToAnnoLink.setCorpusId(corpusId);
            docToAnnoLink.setFrom(l.getFrom());
            docToAnnoLink.setFromAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(Document.class));
            docToAnnoLink.setFromAnnotationType(Document.class.getName());
            docToAnnoLink.setLinkId(String.valueOf(l.getLinkId()));
            docToAnnoLink.setType(l.getLinkType());
            docToAnnoLink.setTo(document.getDocumentId());
            var toAnnotation = l.getTo();
            docToAnnoLink.setToBegin(toAnnotation.getBegin());
            docToAnnoLink.setToEnd(toAnnotation.getEnd());
            docToAnnoLink.setToCoveredText(toAnnotation.getCoveredText());
            Class<?> modelClass = ReflectionUtils.findModelClassForCASAnnotation(toAnnotation);
            if (modelClass == null) {
                logImportWarn("A logical Link annotation tried to point to an annotation that UCE doesn't support yet, hence skipped the link.",
                        new InvalidClassException(toAnnotation.getType().getName() + " annotation not supported by UCE."), filePath);
                return;
            }
            docToAnnoLink.setToAnnotationType(modelClass.getName());
            docToAnnoLink.setToAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(modelClass));
            documentToAnnotationLinks.add(docToAnnoLink);
        });
        db.saveOrUpdateManyDocumentToAnnotationLinks(documentToAnnotationLinks);

        var annotationToDocumentLinks = new ArrayList<AnnotationToDocumentLink>();
        JCasUtil.select(jCas, ADLink.class).forEach(l -> {
            var annoToDocLink = new AnnotationToDocumentLink();
            annoToDocLink.setCorpusId(corpusId);
            annoToDocLink.setTo(l.getTo());
            annoToDocLink.setToAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(Document.class));
            annoToDocLink.setToAnnotationType(Document.class.getName());
            annoToDocLink.setLinkId(String.valueOf(l.getLinkId()));
            annoToDocLink.setType(l.getLinkType());
            annoToDocLink.setFrom(document.getDocumentId());
            var fromAnnotation = l.getFrom();
            annoToDocLink.setFromBegin(fromAnnotation.getBegin());
            annoToDocLink.setFromEnd(fromAnnotation.getEnd());
            annoToDocLink.setFromCoveredText(fromAnnotation.getCoveredText());
            Class<?> modelClass = ReflectionUtils.findModelClassForCASAnnotation(fromAnnotation);
            if (modelClass == null) {
                logImportWarn("A logical Link annotation tried to point to an annotation that UCE doesn't support yet, hence skipped the link.",
                        new InvalidClassException(fromAnnotation.getType().getName() + " annotation not supported by UCE."), filePath);
                return;
            }
            annoToDocLink.setFromAnnotationType(modelClass.getName());
            annoToDocLink.setFromAnnotationTypeTable(ReflectionUtils.getTableAnnotationName(modelClass));
            annotationToDocumentLinks.add(annoToDocLink);
        });
        db.saveOrUpdateManyAnnotationToDocumentLinks(annotationToDocumentLinks);
    }

    private void setUceMetadata(Document document, JCas jCas, long corpusId) {
        var data = new ArrayList<UCEMetadata>();
        JCasUtil.select(jCas, org.texttechnologylab.annotation.uce.Metadata.class).forEach(t -> {
            var metadata = new UCEMetadata();
            metadata.setComment(t.getComment());
            metadata.setValue(t.getValue());
            metadata.setKey(t.getKey());
            metadata.setValueType(UCEMetadataValueType.valueOf(t.getValueType().toUpperCase()));
            data.add(metadata);
            if (metadata.getValueType() == UCEMetadataValueType.JSON) return;
            var possibleFilter = this.uceMetadataFilters.stream()
                    .filter(f -> f.getKey().equals(t.getKey()) && metadata.getValueType() == f.getValueType())
                    .findFirst();
            if (possibleFilter.isEmpty()) {
                var newFilter = new UCEMetadataFilter(corpusId, metadata.getKey(), metadata.getValueType());
                if (metadata.getValueType() == UCEMetadataValueType.NUMBER) {
                    var number = StringUtils.tryParseFloat(metadata.getValue());
                    if (!Float.isNaN(number)) {
                        newFilter.setMin(number);
                        newFilter.setMax(number);
                    }
                } else {
                    newFilter.addPossibleCategory(metadata.getValue());
                }
                synchronized (newFilter) {
                    this.uceMetadataFilters.add(newFilter);
                }
                ExceptionUtils.tryCatchLog(
                        () -> db.saveUCEMetadataFilter(newFilter),
                        (ex) -> logger.error("Tried saving a new UCEMetadataFilter, but got an error: ", ex));
            } else {
                var existingFilter = possibleFilter.get();
                if (existingFilter.getValueType() == UCEMetadataValueType.ENUM) {
                    synchronized (existingFilter) {
                        if (existingFilter.getPossibleCategories().stream().noneMatch(c -> c.equals(metadata.getValue()))) {
                            existingFilter.addPossibleCategory(metadata.getValue());
                            ExceptionUtils.tryCatchLog(
                                    () -> db.saveOrUpdateUCEMetadataFilter(existingFilter),
                                    (ex) -> logger.error("Tried updating an existing UCEMetadataFilter, but got an error: ", ex));
                        }
                    }
                } else if (existingFilter.getValueType() == UCEMetadataValueType.NUMBER) {
                    var number = StringUtils.tryParseFloat(metadata.getValue());
                    if (!Float.isNaN(number)) {
                        synchronized (existingFilter) {
                            if (existingFilter.getMin() == null || number < existingFilter.getMin()) existingFilter.setMin(number);
                            if (existingFilter.getMax() == null || number > existingFilter.getMax()) existingFilter.setMax(number);
                            ExceptionUtils.tryCatchLog(
                                    () -> db.saveOrUpdateUCEMetadataFilter(existingFilter),
                                    (ex) -> logger.error("Tried updating an existing UCEMetadataFilter for type NUMBER, but got an error: ", ex));
                        }
                    }
                }
            }
        });
        document.setUceMetadata(data);
        logger.info("Setting UCE Metadata done.");
    }

    private void setMetadataTitleInfo(Document document, JCas jCas, CorpusConfig corpusConfig) {
        var metadataTitleInfo = new MetadataTitleInfo();
        if (corpusConfig.getOther() != null && corpusConfig.getOther().isAvailableOnFrankfurtUniversityCollection()) {
            metadataTitleInfo = ExceptionUtils.tryCatchLog(
                    () -> goetheUniversityService.scrapeDocumentTitleInfo(document.getDocumentId()),
                    (ex) -> logger.error("Error scraping the metadata info of the document with id: " + document.getDocumentId(), ex));
            if (metadataTitleInfo != null) document.setMetadataTitleInfo(metadataTitleInfo);
            logger.info("Setting potential metadata title info done.");
        } else {
            var documentAnnotation = ExceptionUtils.tryCatchLog(
                    () -> JCasUtil.selectSingle(jCas, DocumentAnnotation.class),
                    (ex) -> logger.info("No DocumentAnnotation found. Skipping this annotation then."));
            if (documentAnnotation != null) {
                try {
                    metadataTitleInfo.setPublished(documentAnnotation.getDateDay() + "."
                            + documentAnnotation.getDateMonth() + "."
                            + documentAnnotation.getDateYear());
                    metadataTitleInfo.setAuthor(documentAnnotation.getAuthor());
                } catch (Exception ex) {
                    logger.warn("Tried extracting DocumentAnnotation type, it caused an error. Import will be continued as usual.");
                }
            }
        }
        document.setMetadataTitleInfo(metadataTitleInfo);
    }

    private void setPages(Document document, JCas jCas, CorpusConfig corpusConfig) {
        if (corpusConfig.getAnnotations().isOCRPage()) {
            var pages = new ArrayList<Page>();
            var pageAdapters = new ArrayList<PageAdapter>();
            JCasUtil.select(jCas, OCRPage.class).forEach(p -> pageAdapters.add(new OCRPageAdapterImpl(p)));
            var pageCount = new AtomicInteger(1);
            JCasUtil.select(jCas, org.texttechnologylab.annotation.ocr.abbyy.Page.class).forEach(p -> {
                pageAdapters.add(new PageAdapterImpl(p, pageCount.get()));
                pageCount.getAndIncrement();
            });
            java.util.Collection<AnnotationComment> annotationComments = selectOptional(jCas, AnnotationComment.class);
            for (var p : pageAdapters) {
                var page = new Page(p.getBegin(), p.getEnd(), p.getPageNumber(), p.getPageId());
                page.setDocument(document);
                page.setCoveredText(p.getCoveredText());
                if (corpusConfig.getAnnotations().isOCRParagraph() && p.getOriginal() instanceof org.texttechnologylab.annotation.ocr.abbyy.Page)
                    page.setParagraphs(getCoveredParagraphs((org.texttechnologylab.annotation.ocr.abbyy.Page) p.getOriginal(), page, annotationComments));
                if (corpusConfig.getAnnotations().isOCRParagraph() && p.getOriginal() instanceof org.texttechnologylab.annotation.ocr.abbyy.Page)
                    page.setDivId(((org.texttechnologylab.annotation.ocr.abbyy.Page) p.getOriginal()).getId());
                updateAnnotationsWithPageId(document, page, false);
                pages.add(page);
            }
            document.setPages(pages);
            logger.info("Setting OCRPages done.");
        } else {
            var fullText = document.getFullText();
            var pageSize = 7500;
            var pageNumber = 1;
            var pages = new ArrayList<Page>();
            for (var i = 0; i < fullText.length(); i += pageSize) {
                var pageEnd = Math.min(i + pageSize, fullText.length());
                var page = new Page(i, pageEnd, pageNumber, "");
                page.setCoveredText(fullText.substring(i, pageEnd));
                page.setDocument(document);
                pageNumber += 1;
                updateAnnotationsWithPageId(document, page, false);
                pages.add(page);
            }
            document.setPages(pages);
            logger.info("Setting synthetic pages done.");
        }
        if (document.getPages() != null && !document.getPages().isEmpty()) {
            updateAnnotationsWithPageId(document, document.getPages().getLast(), true);
        }
    }

    private <T extends org.apache.uima.jcas.cas.TOP> java.util.Collection<T> selectOptional(JCas jCas, Class<T> type) {
        try {
            return JCasUtil.select(jCas, type);
        } catch (IllegalArgumentException ex) {
            logger.debug("Optional UIMA type not present in CAS: {}", type.getName());
            return java.util.Collections.emptyList();
        }
    }

    private void updateAnnotationsWithPageId(Document document, Page page, boolean isLastPage) {
        assignPage(document.getSentences(), page, isLastPage);
        assignPage(document.getEmotions(), page, isLastPage);
        assignPage(document.getSentiments(), page, isLastPage);
        assignPage(document.getLemmas(), page, isLastPage);
        assignPage(document.getGazetteerTaxons(), page, isLastPage);
        assignPage(document.getGnFinderTaxons(), page, isLastPage);
        assignPage(document.getBiofidTaxons(), page, isLastPage);
        assignPage(document.getNamedEntities(), page, isLastPage);
        assignPage(document.getGeoNames(), page, isLastPage);
        assignPage(document.getTimes(), page, isLastPage);
        assignPage(document.getCues(), page, isLastPage);
        assignPage(document.getEvents(), page, isLastPage);
        assignPage(document.getFocuses(), page, isLastPage);
        assignPage(document.getScopes(), page, isLastPage);
        assignPage(document.getXscopes(), page, isLastPage);
        assignPage(document.getUnifiedTopics(), page, isLastPage);
    }

    private void assignPage(List<? extends UIMAAnnotation> annotations, Page page, boolean isLastPage) {
        if (annotations == null) return;
        for (var annotation : annotations.stream().filter(t ->
                (t.getBegin() >= page.getBegin() && t.getEnd() <= page.getEnd()) || (t.getPage() == null && isLastPage)).toList()) {
            annotation.setPage(page);
        }
    }

    private void setWikiLinks(Document document, JCas jCas) {
        var wikiDatas = new ArrayList<WikipediaLink>();
        JCasUtil.select(jCas, org.hucompute.textimager.uima.type.wikipedia.WikipediaLink.class).forEach(w -> {
            var data = new WikipediaLink(w.getBegin(), w.getEnd());
            data.setLinkType(w.getLinkType());
            data.setTarget(w.getTarget());
            data.setCoveredText(w.getCoveredText());
            data.setWikiData(w.getWikiData());
            data.setWikiDataHyponyms(
                    Arrays.stream(w.getWikiDataHyponyms().toArray()).filter(wd -> !wd.isEmpty()).map(WikiDataHyponym::new).toList()
            );
            wikiDatas.add(data);
        });
        document.setWikipediaLinks(wikiDatas);
        logger.info("Setting Wikipedia Links done.");
    }

    private void setTaxonomy(Document document, JCas jCas, CorpusConfig corpusConfig) {
        var biofidTaxa = new ArrayList<BiofidTaxon>();
        var gnFinderTaxa = new ArrayList<GnFinderTaxon>();
        JCasUtil.select(jCas, org.texttechnologylab.annotation.biofid.gnfinder.Taxon.class).forEach(t -> {
            var taxon = new GnFinderTaxon(t.getBegin(), t.getEnd());
            taxon.setValue(t.getValue());
            taxon.setDocument(document);
            taxon.setCoveredText(t.getCoveredText());
            taxon.setOddsLog10(t.getOddsLog10());
            taxon.setIdentifier(t.getIdentifier());
            gnFinderTaxa.add(taxon);
        });
        JCasUtil.select(jCas, org.texttechnologylab.annotation.biofid.gnfinder.VerifiedTaxon.class).forEach(t -> {
            var taxon = new GnFinderTaxon(t.getBegin(), t.getEnd());
            taxon.setValue(t.getValue());
            taxon.setDocument(document);
            taxon.setCoveredText(t.getCoveredText());
            taxon.setOddsLog10(t.getOddsLog10());
            taxon.setIdentifier(t.getIdentifier());
            if (taxon.getIdentifier() == null) {
                gnFinderTaxa.add(taxon);
                return;
            }
            taxon.setVerified(true);
            taxon.setMatchedName(t.getMatchedName());
            taxon.setMatchedCanonical(t.getMatchedCanonicalFull());
            var splitIds = splitTaxonIdentifiers(taxon.getIdentifier());
            if (splitIds.isEmpty()) {
                gnFinderTaxa.add(taxon);
                return;
            }
            var primaryIdentifier = splitIds.getFirst();
            var recordId = parseTrailingNumericIdentifier(primaryIdentifier);
            if (recordId != null) {
                taxon.setRecordId(recordId);
            }
            for (var potentialBiofidId : splitIds) {
                var biofidId = potentialBiofidId.contains("gbif.org")
                        ? StringUtils.gbifToBIOfidUrl(potentialBiofidId)
                        : potentialBiofidId;
                enrichBiofidTaxa(biofidTaxa, biofidId, t.getCoveredText(), t.getBegin(), t.getEnd(), document, GnFinderTaxon.class);
            }
            gnFinderTaxa.add(taxon);
        });
        document.setGnFinderTaxons(gnFinderTaxa);

        var gazetteerTaxa = new ArrayList<GazetteerTaxon>();
        JCasUtil.select(jCas, org.texttechnologylab.annotation.type.Taxon.class).forEach(t -> {
            var taxon = new GazetteerTaxon(t.getBegin(), t.getEnd());
            taxon.setDocument(document);
            taxon.setValue(t.getValue());
            taxon.setCoveredText(t.getCoveredText());
            taxon.setIdentifier(t.getIdentifier());
            if (taxon.getIdentifier() != null && !taxon.getIdentifier().isEmpty()) {
                var splitIds = splitTaxonIdentifiers(taxon.getIdentifier());
                if (!splitIds.isEmpty()) {
                    taxon.setPrimaryIdentifier(splitIds.getFirst());
                    var recordId = parseTrailingNumericIdentifier(taxon.getPrimaryIdentifier());
                    if (recordId != null) {
                        taxon.setRecordId(recordId);
                    }
                    for (var potentialBiofidId : splitIds) {
                        if (potentialBiofidId.isEmpty()) continue;
                        var biofidId = potentialBiofidId.contains("gbif.org")
                                ? StringUtils.gbifToBIOfidUrl(potentialBiofidId)
                                : potentialBiofidId;
                        enrichBiofidTaxa(biofidTaxa, biofidId, t.getCoveredText(), t.getBegin(), t.getEnd(), document, GazetteerTaxon.class);
                    }
                }
            }
            gazetteerTaxa.add(taxon);
        });
        document.setGazetteerTaxons(gazetteerTaxa);
        document.setBiofidTaxons(biofidTaxa);
        logger.info("Setting Taxa done.");
    }

    private Long parseTrailingNumericIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        int end = identifier.length() - 1;
        while (end >= 0 && !Character.isDigit(identifier.charAt(end))) {
            end--;
        }
        if (end < 0) {
            return null;
        }
        int start = end;
        while (start >= 0 && Character.isDigit(identifier.charAt(start))) {
            start--;
        }
        try {
            return Long.parseLong(identifier.substring(start + 1, end + 1));
        } catch (NumberFormatException ex) {
            logger.debug("Skipping non-numeric taxon record id from identifier: {}", identifier);
            return null;
        }
    }

    private ArrayList<String> splitTaxonIdentifiers(String identifiers) {
        var split = new ArrayList<String>();
        for (var part : identifiers.split("\\|")) {
            split.addAll(Arrays.asList(part.split(" ")));
        }
        return split.stream().filter(id -> id != null && !id.isBlank()).collect(Collectors.toCollection(ArrayList::new));
    }

    private void enrichBiofidTaxa(List<BiofidTaxon> biofidTaxa, String biofidId, String coveredText, int begin, int end, Document document, Class<?> sourceClass) {
        var newBiofidTaxons = ExceptionUtils.tryCatchLog(
                () -> jenaSparqlService.queryBiofidTaxon(biofidId),
                (ex) -> logger.error("Error building a BiofidTaxon object from a potential id.", ex));
        if (newBiofidTaxons != null) {
            for (var biofidTaxon : newBiofidTaxons) {
                biofidTaxon.setCoveredText(coveredText);
                biofidTaxon.setBegin(begin);
                biofidTaxon.setEnd(end);
                biofidTaxon.setDocument(document);
                biofidTaxon.setBiofidUrl(biofidId);
                biofidTaxon.setOriginalAnnotatedTaxonTable(ReflectionUtils.getTableAnnotationName(sourceClass));
                biofidTaxa.add(biofidTaxon);
            }
        }
    }

    private void setTimes(Document document, JCas jCas) {
        var times = new ArrayList<Time>();
        JCasUtil.select(jCas, de.unihd.dbs.uima.types.heideltime.Timex3.class).forEach(t -> {
            var time = new Time(t.getBegin(), t.getEnd());
            time.setValue(t.getTimexType());
            time.setCoveredText(t.getCoveredText());
            var units = RegexUtils.dissectTimeAnnotationString(time.getCoveredText());
            time.setYear(units.year);
            time.setMonth(units.month);
            time.setDay(units.day);
            time.setDate(units.fullDate);
            time.setSeason(units.season);
            times.add(time);
        });
        JCasUtil.select(jCas, org.texttechnologylab.annotation.type.Time.class).forEach(t -> {
            var time = new Time(t.getBegin(), t.getEnd());
            time.setValue(t.getValue());
            time.setCoveredText(t.getCoveredText());
            var units = RegexUtils.dissectTimeAnnotationString(time.getCoveredText());
            time.setYear(units.year);
            time.setMonth(units.month);
            time.setDay(units.day);
            time.setDate(units.fullDate);
            time.setSeason(units.season);
            times.add(time);
        });
        document.setTimes(times);
        logger.info("Setting Times done.");
    }

    private void setSemanticRoleLabels(Document document, JCas jCas) {
        var srLinks = new ArrayList<SrLink>();
        JCasUtil.select(jCas, org.texttechnologylab.annotation.semaf.semafsr.SrLink.class).forEach(a -> {
            var srLink = new SrLink();
            var figure = a.getFigure();
            var ground = a.getGround();
            srLink.setRelationType(a.getRel_type());
            srLink.setFigureBegin(figure.getBegin());
            srLink.setFigureEnd(figure.getEnd());
            srLink.setFigureCoveredText(figure.getCoveredText());
            srLink.setGroundBegin(ground.getBegin());
            srLink.setGroundEnd(ground.getEnd());
            srLink.setGroundCoveredText(ground.getCoveredText());
            srLinks.add(srLink);
        });
        document.setSrLinks(srLinks);
        logger.info("Setting Semantic-Roles done.");
    }

    private void setLemmata(Document document, JCas jCas) {
        var lemmas = new ArrayList<Lemma>();
        JCasUtil.select(jCas, de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Lemma.class).forEach(l -> {
            var lemma = new Lemma(l.getBegin(), l.getEnd());
            lemma.setDocument(document);
            lemma.setCoveredText(l.getCoveredText());
            lemma.setValue(l.getValue());
            var potentialPos = JCasUtil.selectCovered(POS.class, l).stream().findFirst();
            if (potentialPos.isPresent()) {
                var pos = potentialPos.get();
                lemma.setPosValue(pos.getPosValue());
                lemma.setCoarseValue(pos.getCoarseValue());
            }
            var potentialMorph = JCasUtil.selectCovered(MorphologicalFeatures.class, l).stream().findFirst();
            if (potentialMorph.isPresent()) {
                var morph = potentialMorph.get();
                lemma.setAnimacy(morph.getAnimacy());
                lemma.setAspect(morph.getAspect());
                lemma.setCasee(morph.getCase());
                lemma.setDefiniteness(morph.getDefiniteness());
                lemma.setDegree(morph.getDegree());
                lemma.setGender(morph.getGender());
                lemma.setMood(morph.getMood());
                lemma.setNegative(morph.getNegative());
                lemma.setNumber(morph.getNumber());
                lemma.setNumberType(morph.getNumType());
                lemma.setPerson(morph.getPerson());
                lemma.setPossessive(morph.getPossessive());
                lemma.setPronType(morph.getPronType());
                lemma.setReflex(morph.getReflex());
                lemma.setTense(morph.getTense());
                lemma.setVerbForm(morph.getVerbForm());
                lemma.setVoice(morph.getVoice());
            }
            lemmas.add(lemma);
        });
        document.setLemmas(lemmas);
        logger.info("Setting Lemmas done.");
    }

    private void setNamedEntities(Document document, JCas jCas) {
        var nes = new ArrayList<NamedEntity>();
        JCasUtil.select(jCas, de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity.class).forEach(ne -> {
            if (ne == null || ne.getValue() == null) return;
            var neType = "";
            for (var type : WANTED_NE_TYPES) {
                if (type.equals(ne.getValue()) || ne.getValue().equals(type.substring(0, 3))) neType = type;
            }
            if (neType.isEmpty()) return;
            var namedEntity = new NamedEntity(ne.getBegin(), ne.getEnd());
            namedEntity.setDocument(document);
            namedEntity.setType(neType);
            namedEntity.setCoveredText(ne.getCoveredText());
            nes.add(namedEntity);
        });
        document.setNamedEntities(nes);
        logger.info("Setting Named-Entities done.");
    }

    private void setSentences(Document document, JCas jCas) {
        document.setSentences(JCasUtil.select(jCas, de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence.class)
                .stream()
                .map(s -> new Sentence(s.getBegin(), s.getEnd(), s.getCoveredText()))
                .toList());
        logger.info("Setting sentences done.");
    }

    public void setCompleteNegations(Document document, JCas jCas) {
        ArrayList<CompleteNegation> cNegationsTotal = new ArrayList<>();
        ArrayList<Cue> cuesTotal = new ArrayList<>();
        ArrayList<Scope> scopesTotal = new ArrayList<>();
        ArrayList<XScope> xScopesTotal = new ArrayList<>();
        ArrayList<Focus> focusesTotal = new ArrayList<>();
        ArrayList<Event> eventsTotal = new ArrayList<>();
        for (org.texttechnologylab.annotation.negation.CompleteNegation negation : jCas.select(org.texttechnologylab.annotation.negation.CompleteNegation.class)) {
            Token cueT = negation.getCue();
            FSArray<Token> eventTL = negation.getEvent();
            FSArray<Token> scopeTL = negation.getScope();
            FSArray<Token> xscopeTL = negation.getXscope();
            FSArray<Token> focusTL = negation.getFocus();
            CompleteNegation cNegation = new CompleteNegation(cueT.getBegin(), cueT.getEnd());
            Cue cue = new Cue(cueT.getBegin(), cueT.getEnd());
            cue.setNegation(cNegation);
            cue.setDocument(document);
            cue.setCoveredText(cue.getCoveredText(jCas.getDocumentText()));
            ArrayList<Scope> scopes = new ArrayList<>();
            ArrayList<XScope> xScopes = new ArrayList<>();
            ArrayList<Focus> focuses = new ArrayList<>();
            ArrayList<Event> events = new ArrayList<>();
            if (eventTL != null) for (ArrayList<Token> span : TokenUtils.findMaximalSpans(eventTL.stream().collect(Collectors.toCollection(ArrayList::new)))) {
                Event event = new Event(span.getFirst().getBegin(), span.getLast().getEnd());
                event.setDocument(document);
                event.setNegation(cNegation);
                event.setCoveredText(event.getCoveredText(jCas.getDocumentText()));
                events.add(event);
            }
            if (scopeTL != null) for (ArrayList<Token> span : TokenUtils.findMaximalSpans(scopeTL.stream().collect(Collectors.toCollection(ArrayList::new)))) {
                Scope scope = new Scope(span.getFirst().getBegin(), span.getLast().getEnd());
                scope.setDocument(document);
                scope.setNegation(cNegation);
                scope.setCoveredText(scope.getCoveredText(jCas.getDocumentText()));
                scopes.add(scope);
            }
            if (xscopeTL != null) for (ArrayList<Token> span : TokenUtils.findMaximalSpans(xscopeTL.stream().collect(Collectors.toCollection(ArrayList::new)))) {
                XScope xscope = new XScope(span.getFirst().getBegin(), span.getLast().getEnd());
                xscope.setDocument(document);
                xscope.setNegation(cNegation);
                xscope.setCoveredText(xscope.getCoveredText(jCas.getDocumentText()));
                xScopes.add(xscope);
            }
            if (focusTL != null) for (ArrayList<Token> span : TokenUtils.findMaximalSpans(focusTL.stream().collect(Collectors.toCollection(ArrayList::new)))) {
                Focus focus = new Focus(span.getFirst().getBegin(), span.getLast().getEnd());
                focus.setDocument(document);
                focus.setNegation(cNegation);
                focus.setCoveredText(focus.getCoveredText(jCas.getDocumentText()));
                focuses.add(focus);
            }
            cNegation.setDocument(document);
            cNegation.setCue(cue);
            cNegation.setEventList(events);
            cNegation.setScopeList(scopes);
            cNegation.setXscopeList(xScopes);
            cNegation.setFocusList(focuses);
            cNegationsTotal.add(cNegation);
            cuesTotal.add(cue);
            scopesTotal.addAll(scopes);
            xScopesTotal.addAll(xScopes);
            focusesTotal.addAll(focuses);
            eventsTotal.addAll(events);
        }
        document.setCompleteNegations(cNegationsTotal);
        document.setCues(cuesTotal);
        document.setScopes(scopesTotal);
        document.setXscopes(xScopesTotal);
        document.setFocuses(focusesTotal);
        document.setEvents(eventsTotal);
    }

    private void setImages(Document document, JCas jCas) {
        List<Image> images = new ArrayList<>();
        for (org.texttechnologylab.annotation.type.Image imageAnno : JCasUtil.select(jCas, org.texttechnologylab.annotation.type.Image.class)) {
            Image image = new Image(imageAnno.getBegin(), imageAnno.getEnd());
            if (imageAnno.getWidth() == 0 && imageAnno.getHeight() == 0) {
                try {
                    byte[] imageBytes = Base64.getDecoder().decode(imageAnno.getSrc());
                    BufferedImage imageData = ImageIO.read(new ByteArrayInputStream(imageBytes));
                    image.setWidth(imageData.getWidth());
                    image.setHeight(imageData.getHeight());
                } catch (Exception e) {
                    logger.warn("Failed detecting image dimensions for image: " + e.getMessage(), e);
                }
            } else {
                image.setWidth(imageAnno.getWidth());
                image.setHeight(imageAnno.getHeight());
            }
            image.setMimeType(imageAnno.getMimetype());
            image.setSrc(imageAnno.getSrc());
            images.add(image);
        }
        document.setImages(images);
    }

    private void setUnifiedTopic(Document document, JCas jCas) {
        List<UnifiedTopic> unifiedTopics = new ArrayList<>();
        JCasUtil.select(jCas, org.texttechnologylab.annotation.UnifiedTopic.class).forEach(ut -> {
            UnifiedTopic unifiedTopic = new UnifiedTopic(ut.getBegin(), ut.getEnd());
            unifiedTopic.setDocument(document);
            if (ut.getTopics() != null) {
                List<TopicValueBase> topics = new ArrayList<>();
                for (org.texttechnologylab.annotation.TopicValueBase tvb : ut.getTopics().toArray(new org.texttechnologylab.annotation.TopicValueBase[0])) {
                    TopicValueBase topicValueBase;
                    if (tvb instanceof org.texttechnologylab.annotation.TopicValueBaseWithScore tvbWithScore) {
                        TopicValueBaseWithScore topicValueBaseWithScore = new TopicValueBaseWithScore(ut.getBegin(), ut.getEnd());
                        topicValueBaseWithScore.setDocument(document);
                        topicValueBaseWithScore.setScore(tvbWithScore.getScore());
                        topicValueBase = topicValueBaseWithScore;
                    } else {
                        topicValueBase = new TopicValueBase(ut.getBegin(), ut.getEnd());
                        topicValueBase.setDocument(document);
                    }
                    topicValueBase.setValue(tvb.getValue());
                    if (tvb.getWords() != null) {
                        List<TopicWord> words = new ArrayList<>();
                        for (org.texttechnologylab.annotation.TopicWord tw : tvb.getWords().toArray(new org.texttechnologylab.annotation.TopicWord[0])) {
                            TopicWord topicWord = new TopicWord(tw.getBegin(), tw.getEnd());
                            topicWord.setWord(tw.getWord());
                            topicWord.setProbability(tw.getProbability());
                            topicWord.setTopic(topicValueBase);
                            topicWord.setCoveredText(topicWord.getCoveredText(jCas.getDocumentText()));
                            words.add(topicWord);
                        }
                        topicValueBase.setWords(words);
                    }
                    topicValueBase.setCoveredText(topicValueBase.getCoveredText(jCas.getDocumentText()));
                    topicValueBase.setUnifiedTopic(unifiedTopic);
                    topics.add(topicValueBase);
                }
                unifiedTopic.setTopics(topics);
            }
            unifiedTopic.setCoveredText(unifiedTopic.getCoveredText(jCas.getDocumentText()));
            unifiedTopics.add(unifiedTopic);
        });
        document.setUnifiedTopics(unifiedTopics);
    }

    private void setCleanedFullText(Document document, JCas jCas) {
        var cleanedText = new StringJoiner(" ");
        JCasUtil.select(jCas, Token.class).forEach(t -> {
            if (t instanceof OCRToken ocr && ocr.getSuspiciousChars() > 0) return;
            var coveredAnomalies = JCasUtil.selectCovered(Anomaly.class, t).size();
            if (coveredAnomalies == 0) cleanedText.add(t.getCoveredText());
        });
        document.setFullTextCleaned(cleanedText.toString());
    }

    private List<Line> getCoveredLines(org.texttechnologylab.annotation.ocr.abbyy.Page page) {
        var lines = new ArrayList<Line>();
        JCasUtil.selectCovered(OCRLine.class, page).forEach(pg -> {
            var line = new Line(pg.getBegin(), pg.getEnd());
            line.setBaseline(pg.getBaseline());
            line.setBottom(pg.getBottom());
            line.setLeft(pg.getLeft());
            line.setTop(pg.getTop());
            line.setRight(pg.getRight());
            lines.add(line);
        });
        return lines;
    }

    private List<Block> getCoveredBlocks(org.texttechnologylab.annotation.ocr.abbyy.Page page) {
        var blocks = new ArrayList<Block>();
        JCasUtil.selectCovered(OCRBlock.class, page).forEach(pg -> {
            var block = new Block(pg.getBegin(), pg.getEnd());
            block.setBlockType(pg.getBlockType());
            blocks.add(block);
        });
        return blocks;
    }

    private List<Paragraph> getCoveredParagraphs(org.texttechnologylab.annotation.ocr.abbyy.Page page, Page ucePage, java.util.Collection<AnnotationComment> annotationComments) {
        var paragraphs = new ArrayList<Paragraph>();
        JCasUtil.selectCovered(org.texttechnologylab.annotation.ocr.abbyy.Paragraph.class, page).forEach(pg -> {
            var paragraph = new Paragraph(pg.getBegin(), pg.getEnd());
            paragraph.setLeftIndent(pg.getLeftIndent());
            paragraph.setLineSpacing(pg.getLineSpacing());
            paragraph.setRightIndent(pg.getRightIndent());
            paragraph.setStartIndent(pg.getStartIndent());
            paragraph.setCoveredText(pg.getCoveredText());
            paragraph.setPage(ucePage);
            for (AnnotationComment comment : annotationComments) {
                if (comment.getReference() == pg) {
                    if (comment.getKey().startsWith("_uce_paragraph_config_header")) {
                        paragraph.setHeader(comment.getValue());
                    } else if (comment.getKey().startsWith("_uce_paragraph_config_cssclass")) {
                        paragraph.setCssClass(comment.getValue());
                    }
                }
            }
            paragraphs.add(paragraph);
        });
        return paragraphs;
    }

    private void tryStoreUCEImportLog(ImportLog importLog) {
        ExceptionUtils.tryCatchLog(
                () -> db.saveOrUpdateImportLog(importLog),
                (ex) -> logger.warn("Couldn't store a UCEImport log... operation continues.", ex));
    }

    private void logImportInfo(String message, LogStatus status, String file, long duration) {
        var importLog = new ImportLog(this.importerNumber.toString(), message, status, file, this.importId, duration);
        tryStoreUCEImportLog(importLog);
        logger.info(message);
    }

    private void logImportWarn(String message, Exception ex, String file) {
        var importLog = new ImportLog(this.importerNumber.toString(), ex == null ? message : ex.getMessage(), LogStatus.WARN, file, this.importId, 0);
        tryStoreUCEImportLog(importLog);
        logger.warn(message, ex);
    }

    private void logImportError(String message, Exception ex, String file) {
        var importLog = new ImportLog(this.importerNumber.toString(), ex == null ? message : ex.getMessage(), LogStatus.ERROR, file, this.importId, 0);
        tryStoreUCEImportLog(importLog);
        logger.error(message, ex);
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

    private void recordImportDomain(ImportRuntimeState runtime) {
        String importDomainId = "import:" + runtime.importId;
        recordDomain(runtime, new UceDomainRecord(
                UCE_IMPORT_TYPE,
                importDomainId,
                runtime.importId,
                null,
                mapOf("status", "RUNNING", "importerNumber", String.valueOf(runtime.importerNumber)),
                null,
                null
        ));
    }

    private void recordCorpusDomain(UCECorpusArtifact work) {
        if (work.corpus == null) {
            return;
        }
        ImportRuntimeState runtime = work.runtime;
        String importDomainId = "import:" + runtime.importId;
        String corpusDomainId = corpusDomainId(work);
        recordDomain(runtime, new UceDomainRecord(
                UCE_CORPUS_TYPE,
                corpusDomainId,
                work.corpus.getName(),
                null,
                mapOf("configHash", stableHash(work.corpus.getCorpusJsonConfig()), "sourcePath", nullToEmpty(runtime.sourcePath)),
                corpusDomainId,
                null
        ));
        recordAssociation(runtime, new UceAssociationRecord(
                MEMBERSHIP_TYPE,
                "import-corpus:" + runtime.importId + ":" + corpusDomainId,
                uceUid(UCE_IMPORT_TYPE, importDomainId),
                uceUid(UCE_CORPUS_TYPE, corpusDomainId),
                "import-corpus",
                null,
                corpusDomainId,
                null
        ));
    }

    private void recordDocumentDomain(UCEDocumentArtifact work) {
        String corpusDomainId = corpusDomainId(work);
        String documentDomainId = documentDomainId(work);
        if (documentDomainId == null) {
            return;
        }
        recordDomain(work.corpus.runtime, new UceDomainRecord(
                UCE_DOCUMENT_TYPE,
                documentDomainId,
                work.document.getDocumentTitle(),
                null,
                mapOf("corpusId", String.valueOf(work.corpus.corpus.getId()),
                        "documentId", work.document.getDocumentId(),
                        "sourcePath", work.filePath.toString()),
                corpusDomainId,
                documentDomainId
        ));
        recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                MEMBERSHIP_TYPE,
                "corpus-document:" + corpusDomainId + ":" + documentDomainId,
                uceUid(UCE_CORPUS_TYPE, corpusDomainId),
                uceUid(UCE_DOCUMENT_TYPE, documentDomainId),
                "corpus-document",
                null,
                corpusDomainId,
                documentDomainId
        ));
    }

    private void recordViewDomain(UCEDocumentArtifact work) {
        String importDomainId = "import:" + work.corpus.runtime.importId;
        String corpusDomainId = corpusDomainId(work);
        String documentDomainId = documentDomainId(work);
        if (documentDomainId == null) {
            return;
        }
        String viewName = work.corpus.runtime.casView == null || work.corpus.runtime.casView.isBlank()
                ? "_InitialView"
                : work.corpus.runtime.casView;
        String viewDomainId = "view:" + viewName;
        recordDomain(work.corpus.runtime, new UceDomainRecord(
                UCE_VIEW_TYPE,
                viewDomainId,
                viewName,
                null,
                mapOf("viewName", viewName),
                corpusDomainId,
                documentDomainId
        ));
        recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                ASSOCIATION_TYPE,
                "document-view:" + documentDomainId + ":" + viewDomainId,
                uceUid(UCE_DOCUMENT_TYPE, documentDomainId),
                uceUid(UCE_VIEW_TYPE, viewDomainId),
                "document-view",
                mapOf("role", "cas-view"),
                corpusDomainId,
                documentDomainId
        ));
        recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                ASSOCIATION_TYPE,
                "import-view:" + work.corpus.runtime.importId + ":" + viewDomainId,
                uceUid(UCE_IMPORT_TYPE, importDomainId),
                uceUid(UCE_VIEW_TYPE, viewDomainId),
                "import-view",
                mapOf("role", "cas-view"),
                corpusDomainId,
                documentDomainId
        ));
    }

    private void recordPageDomains(UCEDocumentArtifact work) {
        String corpusDomainId = corpusDomainId(work);
        String documentDomainId = documentDomainId(work);
        if (documentDomainId == null || work.document.getPages() == null) {
            return;
        }
        for (Page page : work.document.getPages()) {
            String pageDomainId = pageDomainId(documentDomainId, page);
            recordDomain(work.corpus.runtime, new UceDomainRecord(
                    UCE_PAGE_TYPE,
                    pageDomainId,
                    "Page " + page.getPageNumber(),
                    null,
                    mapOf("corpusId", String.valueOf(work.corpus.corpus.getId()),
                            "documentId", work.document.getDocumentId(),
                            "pageNumber", String.valueOf(page.getPageNumber()),
                            "pageIdentifier", nullToEmpty(page.getPageIdentifier())),
                    corpusDomainId,
                    documentDomainId
            ));
            recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                    MEMBERSHIP_TYPE,
                    "document-page:" + documentDomainId + ":" + pageDomainId,
                    uceUid(UCE_DOCUMENT_TYPE, documentDomainId),
                    uceUid(UCE_PAGE_TYPE, pageDomainId),
                    "document-page",
                    mapOf("order", String.valueOf(page.getPageNumber())),
                    corpusDomainId,
                    documentDomainId
            ));
        }
    }

    private void recordTypeAndAnnotationDomains(UCEDocumentArtifact work) {
        String importDomainId = "import:" + work.corpus.runtime.importId;
        String corpusDomainId = corpusDomainId(work);
        String documentDomainId = documentDomainId(work);
        if (documentDomainId == null) {
            return;
        }
        var annotations = JCasUtil.select(work.jCas, Annotation.class).stream()
                .sorted(Comparator.comparing((Annotation a) -> a.getType().getName())
                        .thenComparingInt(Annotation::getBegin)
                        .thenComparingInt(Annotation::getEnd))
                .toList();
        var typeCounts = annotations.stream().collect(Collectors.groupingBy(
                annotation -> annotation.getType().getName(),
                LinkedHashMap::new,
                Collectors.counting()
        ));
        for (var entry : typeCounts.entrySet()) {
            String typeName = entry.getKey();
            String typeDomainId = "type:" + typeName;
            recordDomain(work.corpus.runtime, new UceDomainRecord(
                    UCE_TYPE_TYPE,
                    typeDomainId,
                    typeName,
                    null,
                    mapOf("typeName", typeName, "superTypeName", superTypeName(work.jCas, typeName)),
                    corpusDomainId,
                    documentDomainId
            ));
            recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                    ASSOCIATION_TYPE,
                    "import-type:" + work.corpus.runtime.importId + ":" + typeDomainId,
                    uceUid(UCE_IMPORT_TYPE, importDomainId),
                    uceUid(UCE_TYPE_TYPE, typeDomainId),
                    "import-type",
                    mapOf("count", String.valueOf(entry.getValue())),
                    corpusDomainId,
                    documentDomainId
            ));
            recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                    ASSOCIATION_TYPE,
                    "corpus-type:" + corpusDomainId + ":" + typeDomainId,
                    uceUid(UCE_CORPUS_TYPE, corpusDomainId),
                    uceUid(UCE_TYPE_TYPE, typeDomainId),
                    "corpus-type",
                    mapOf("count", String.valueOf(entry.getValue())),
                    corpusDomainId,
                    documentDomainId
            ));
            recordAssociation(work.corpus.runtime, new UceAssociationRecord(
                    ASSOCIATION_TYPE,
                    "document-type:" + documentDomainId + ":" + typeDomainId,
                    uceUid(UCE_DOCUMENT_TYPE, documentDomainId),
                    uceUid(UCE_TYPE_TYPE, typeDomainId),
                    "document-type",
                    mapOf("count", String.valueOf(entry.getValue())),
                    corpusDomainId,
                    documentDomainId
            ));
        }
    }

    private static void recordDomain(ImportRuntimeState runtime, UceDomainRecord record) {
        runtime.uceDomains.add(record);
    }

    private static void recordAssociation(ImportRuntimeState runtime, UceAssociationRecord record) {
        runtime.uceAssociations.add(record);
    }

    private DUUIArtifact<UCEDocumentArtifact> persistDomainAssociationGraph(DUUIArtifact<UCEDocumentArtifact> artifact) {
        UCEDocumentArtifact work = artifact.payload();
        if (work.document == null || work.jCas == null) {
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
            persistUceImportDomainGraph(work);
            persistAnnotatedDomainGraph(work);
        } catch (Exception ex) {
            logger.error("Error persisting domain-association graph data for " + work.filePath, ex);
        }
        return artifact;
    }

    private void persistUceImportDomainGraph(UCEDocumentArtifact work) throws DatabaseOperationException, DocumentAccessDeniedException {
        String importId = work.corpus.runtime.importId;
        String corpusDomainId = corpusDomainId(work);
        String documentDomainId = documentDomainId(work);
        persistRecordedUceDomains(work, corpusDomainId, documentDomainId);
        persistRecordedUceAssociations(work, corpusDomainId, documentDomainId);
        persistUceOperationDomains(work, corpusDomainId, documentDomainId, importId);
    }

    private void persistRecordedUceDomains(UCEDocumentArtifact work,
                                           String corpusDomainId,
                                           String documentDomainId) throws DatabaseOperationException, DocumentAccessDeniedException {
        List<AgeGraphService.DomainNode> nodes = new ArrayList<>();
        for (UceDomainRecord record : work.corpus.runtime.uceDomains) {
            if (!recordApplies(record.corpusDomainId(), record.documentDomainId(), corpusDomainId, documentDomainId)) {
                continue;
            }
            nodes.add(uceDomainNode(work, record.uimaType(), record.id(), record.name(), record.uri(), record.features()));
        }
        if (!nodes.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertDomainNodes(nodes);
        }
    }

    private void persistRecordedUceAssociations(UCEDocumentArtifact work,
                                                String corpusDomainId,
                                                String documentDomainId) throws DatabaseOperationException, DocumentAccessDeniedException {
        List<AgeGraphService.AssociationEdge> edges = new ArrayList<>();
        for (UceAssociationRecord record : work.corpus.runtime.uceAssociations) {
            if (!recordApplies(record.corpusDomainId(), record.documentDomainId(), corpusDomainId, documentDomainId)) {
                continue;
            }
            edges.add(uceAssociationEdge(work, record.uimaType(), record.id(), record.leftUid(), record.rightUid(), record.name(), record.features()));
        }
        if (!edges.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertAssociationEdges(edges);
        }
    }

    private boolean recordApplies(String recordCorpusDomainId,
                                  String recordDocumentDomainId,
                                  String corpusDomainId,
                                  String documentDomainId) {
        if (recordCorpusDomainId != null && !recordCorpusDomainId.equals(corpusDomainId)) {
            return false;
        }
        return recordDocumentDomainId == null || recordDocumentDomainId.equals(documentDomainId);
    }

    private void persistCorpusLevelUceGraph(UCECorpusArtifact work) throws DatabaseOperationException, DocumentAccessDeniedException {
        work.runtime.ageGraphService.ensureGraph();
        String corpusDomainId = corpusDomainId(work);
        List<AgeGraphService.DomainNode> nodes = new ArrayList<>();
        for (UceDomainRecord record : work.runtime.uceDomains) {
            if (record.documentDomainId() != null) {
                continue;
            }
            if (record.corpusDomainId() != null && !record.corpusDomainId().equals(corpusDomainId)) {
                continue;
            }
            nodes.add(new AgeGraphService.DomainNode(
                    uceUid(record.uimaType(), record.id()),
                    record.uimaType(),
                    work.corpus.getId(),
                    -1,
                    record.name(),
                    record.uri(),
                    null,
                    record.features() == null || record.features().isEmpty() ? null : gson.toJson(record.features())
            ));
        }
        if (!nodes.isEmpty()) {
            work.runtime.ageGraphService.upsertDomainNodes(nodes);
        }
        List<AgeGraphService.AssociationEdge> edges = new ArrayList<>();
        for (UceAssociationRecord record : work.runtime.uceAssociations) {
            if (record.documentDomainId() != null) {
                continue;
            }
            if (record.corpusDomainId() != null && !record.corpusDomainId().equals(corpusDomainId)) {
                continue;
            }
            edges.add(new AgeGraphService.AssociationEdge(
                    record.uimaType() + ":" + record.id(),
                    record.uimaType(),
                    work.corpus.getId(),
                    -1,
                    record.leftUid(),
                    record.rightUid(),
                    record.name(),
                    null,
                    record.features() == null || record.features().isEmpty() ? null : gson.toJson(record.features())
            ));
        }
        if (!edges.isEmpty()) {
            work.runtime.ageGraphService.upsertAssociationEdges(edges);
        }
    }

    private void persistUceOperationDomains(UCEDocumentArtifact work,
                                            String corpusDomainId,
                                            String documentDomainId,
                                            String importId) throws DatabaseOperationException, DocumentAccessDeniedException {
        List<AgeGraphService.DomainNode> nodes = new ArrayList<>();
        List<AgeGraphService.AssociationEdge> edges = new ArrayList<>();
        for (ImportOperationRecord operation : work.corpus.runtime.operations) {
            if (operation.documentDomainId() != null && !operation.documentDomainId().equals(documentDomainId)) {
                continue;
            }
            if (operation.corpusDomainId() != null && !operation.corpusDomainId().equals(corpusDomainId)) {
                continue;
            }
            String scope = operation.documentDomainId() == null ? "import" : operation.documentDomainId();
            String operationId = "operation:" + importId + ":" + scope + ":" + operation.name() + ":" + operation.startedAt();
            nodes.add(uceDomainNode(work, UCE_OPERATION_TYPE, operationId, operation.name(), null,
                    mapOf("operationName", operation.name(),
                            "status", operation.status(),
                            "retries", String.valueOf(operation.retries()),
                            "error", nullToEmpty(operation.error()),
                            "startedAt", String.valueOf(operation.startedAt()),
                            "finishedAt", String.valueOf(operation.finishedAt()))));
            edges.add(uceAssociationEdge(work, ASSOCIATION_TYPE, "operation-import:" + operationId,
                    uceUid(UCE_OPERATION_TYPE, operationId), uceUid(UCE_IMPORT_TYPE, "import:" + importId),
                    "operation-import", mapOf("role", "import")));
            if (operation.corpusDomainId() != null) {
                edges.add(uceAssociationEdge(work, ASSOCIATION_TYPE, "operation-corpus:" + operationId,
                        uceUid(UCE_OPERATION_TYPE, operationId), uceUid(UCE_CORPUS_TYPE, operation.corpusDomainId()),
                        "operation-corpus", mapOf("role", "corpus")));
            }
            if (operation.documentDomainId() != null) {
                edges.add(uceAssociationEdge(work, ASSOCIATION_TYPE, "operation-document:" + operationId,
                        uceUid(UCE_OPERATION_TYPE, operationId), uceUid(UCE_DOCUMENT_TYPE, operation.documentDomainId()),
                        "operation-document", mapOf("role", "document")));
            }
        }
        if (!nodes.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertDomainNodes(nodes);
        }
        if (!edges.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertAssociationEdges(edges);
        }
    }

    private void persistCorpusOperationGraph(UCECorpusArtifact work) throws DatabaseOperationException, DocumentAccessDeniedException {
        work.runtime.ageGraphService.ensureGraph();
        String importUid = uceUid(UCE_IMPORT_TYPE, "import:" + work.runtime.importId);
        String corpusDomainId = corpusDomainId(work);
        String corpusUid = uceUid(UCE_CORPUS_TYPE, corpusDomainId);
        List<AgeGraphService.DomainNode> nodes = new ArrayList<>();
        List<AgeGraphService.AssociationEdge> edges = new ArrayList<>();
        for (ImportOperationRecord operation : work.runtime.operations) {
            String scope = operation.documentDomainId() == null ? "import" : operation.documentDomainId();
            String operationId = "operation:" + work.runtime.importId + ":" + scope + ":" + operation.name() + ":" + operation.startedAt();
            String operationUid = uceUid(UCE_OPERATION_TYPE, operationId);
            nodes.add(new AgeGraphService.DomainNode(
                    operationUid,
                    UCE_OPERATION_TYPE,
                    work.corpus.getId(),
                    -1,
                    operation.name(),
                    null,
                    null,
                    gson.toJson(mapOf("operationName", operation.name(),
                            "status", operation.status(),
                            "retries", String.valueOf(operation.retries()),
                            "error", nullToEmpty(operation.error()),
                            "startedAt", String.valueOf(operation.startedAt()),
                            "finishedAt", String.valueOf(operation.finishedAt())))
            ));
            edges.add(new AgeGraphService.AssociationEdge(
                    ASSOCIATION_TYPE + ":operation-import:" + operationId,
                    ASSOCIATION_TYPE,
                    work.corpus.getId(),
                    -1,
                    operationUid,
                    importUid,
                    "operation-import",
                    null,
                    gson.toJson(mapOf("role", "import"))
            ));
            if (operation.corpusDomainId() != null || operation.documentDomainId() != null) {
                edges.add(new AgeGraphService.AssociationEdge(
                        ASSOCIATION_TYPE + ":operation-corpus:" + operationId,
                        ASSOCIATION_TYPE,
                        work.corpus.getId(),
                        -1,
                        operationUid,
                        corpusUid,
                        "operation-corpus",
                        null,
                        gson.toJson(mapOf("role", "corpus"))
                ));
            }
            if (operation.documentDomainId() != null) {
                edges.add(new AgeGraphService.AssociationEdge(
                        ASSOCIATION_TYPE + ":operation-document:" + operationId,
                        ASSOCIATION_TYPE,
                        work.corpus.getId(),
                        -1,
                        operationUid,
                        uceUid(UCE_DOCUMENT_TYPE, operation.documentDomainId()),
                        "operation-document",
                        null,
                        gson.toJson(mapOf("role", "document"))
                ));
            }
        }
        if (!nodes.isEmpty()) {
            work.runtime.ageGraphService.upsertDomainNodes(nodes);
        }
        if (!edges.isEmpty()) {
            work.runtime.ageGraphService.upsertAssociationEdges(edges);
        }
    }

    private void persistAnnotatedDomainGraph(UCEDocumentArtifact work) throws DatabaseOperationException, DocumentAccessDeniedException {
        Class<? extends org.apache.uima.jcas.cas.TOP> artifactClass = uimaClass("org.texttechnologylab.annotation.artifact.Artifact");
        Class<? extends org.apache.uima.jcas.cas.TOP> associationClass = uimaClass(ASSOCIATION_TYPE);
        if (artifactClass == null || associationClass == null) {
            return;
        }
        long corpusId = work.corpus.corpus.getId();
        long documentRowId = work.document.getId();
        var domains = JCasUtil.select(work.jCas, artifactClass);
        List<AgeGraphService.DomainNode> nodes = new ArrayList<>();
        for (org.apache.uima.jcas.cas.TOP domain : domains) {
            if (associationClass.isInstance(domain)) {
                continue;
            }
            String uid = domainUid(domain);
            if (uid == null) {
                continue;
            }
            nodes.add(new AgeGraphService.DomainNode(
                    uid,
                    domain.getType().getName(),
                    corpusId,
                    documentRowId,
                    featureString(domain, "name"),
                    featureString(domain, "uri"),
                    featureString(domain, "metadata"),
                    featureJson(domain, Set.of("id", "name", "uri", "metadata"))
            ));
        }
        if (!nodes.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertDomainNodes(nodes);
        }

        var associations = JCasUtil.select(work.jCas, associationClass);
        List<AgeGraphService.AssociationEdge> edges = new ArrayList<>();
        for (org.apache.uima.jcas.cas.TOP association : associations) {
            org.apache.uima.jcas.cas.TOP left = leftAssociationDomain(association);
            org.apache.uima.jcas.cas.TOP right = rightAssociationDomain(association);
            String leftUid = domainUid(left);
            String rightUid = domainUid(right);
            if (leftUid == null || rightUid == null) {
                continue;
            }
            String edgeUid = featureString(association, "id");
            if (edgeUid == null || edgeUid.isBlank()) {
                edgeUid = association.getType().getName() + ":" + leftUid + "->" + rightUid + ":" + nullToEmpty(featureString(association, "name"));
            }
            edges.add(new AgeGraphService.AssociationEdge(
                    edgeUid,
                    association.getType().getName(),
                    corpusId,
                    documentRowId,
                    leftUid,
                    rightUid,
                    featureString(association, "name"),
                    featureString(association, "metadata"),
                    featureJson(association, Set.of("id", "name", "metadata"))
            ));
        }
        if (!edges.isEmpty()) {
            work.corpus.runtime.ageGraphService.upsertAssociationEdges(edges);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends org.apache.uima.jcas.cas.TOP> uimaClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return org.apache.uima.jcas.cas.TOP.class.isAssignableFrom(clazz)
                    ? (Class<? extends org.apache.uima.jcas.cas.TOP>) clazz
                    : null;
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private String domainUid(org.apache.uima.jcas.cas.TOP domain) {
        String id = featureString(domain, "id");
        if (domain == null || id == null || id.isBlank()) {
            return null;
        }
        return domain.getType().getName() + ":" + id;
    }

    private org.apache.uima.jcas.cas.TOP leftAssociationDomain(org.apache.uima.jcas.cas.TOP association) {
        return firstFeatureValue(association, "owner");
    }

    private org.apache.uima.jcas.cas.TOP rightAssociationDomain(org.apache.uima.jcas.cas.TOP association) {
        return firstFeatureValue(association, "owned");
    }

    private org.apache.uima.jcas.cas.TOP firstFeatureValue(org.apache.uima.jcas.cas.TOP fs, String... featureNames) {
        for (String featureName : featureNames) {
            Feature feature = fs.getType().getFeatureByBaseName(featureName);
            if (feature == null) {
                continue;
            }
            try {
                var value = fs.getFeatureValue(feature);
                if (value instanceof org.apache.uima.jcas.cas.TOP top) {
                    return top;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String featureString(org.apache.uima.jcas.cas.TOP fs, String featureName) {
        if (fs == null) {
            return null;
        }
        Feature feature = fs.getType().getFeatureByBaseName(featureName);
        if (feature == null) {
            return null;
        }
        try {
            return fs.getFeatureValueAsString(feature);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String featureJson(org.apache.uima.jcas.cas.TOP fs, Set<String> excludedFeatures) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (Feature feature : fs.getType().getFeatures()) {
            String shortName = feature.getShortName();
            if (excludedFeatures.contains(shortName)) {
                continue;
            }
            try {
                values.put(shortName, fs.getFeatureValueAsString(feature));
            } catch (Exception ignored) {
            }
        }
        return values.isEmpty() ? null : gson.toJson(values);
    }

    private void upsertUceDomain(UCEDocumentArtifact work,
                                 String uimaType,
                                 String id,
                                 String name,
                                 String uri,
                                 java.util.Map<String, String> features) throws DatabaseOperationException, DocumentAccessDeniedException {
        work.corpus.runtime.ageGraphService.upsertDomainNode(uceDomainNode(work, uimaType, id, name, uri, features));
    }

    private AgeGraphService.DomainNode uceDomainNode(UCEDocumentArtifact work,
                                                     String uimaType,
                                                     String id,
                                                     String name,
                                                     String uri,
                                                     java.util.Map<String, String> features) {
        return new AgeGraphService.DomainNode(
                uceUid(uimaType, id),
                uimaType,
                work.corpus.corpus.getId(),
                work.document.getId(),
                name,
                uri,
                null,
                features == null || features.isEmpty() ? null : gson.toJson(features)
        );
    }

    private void upsertUceEdge(UCEDocumentArtifact work,
                               String uimaType,
                               String id,
                               String leftUid,
                               String rightUid,
                               String name,
                               java.util.Map<String, String> features) throws DatabaseOperationException, DocumentAccessDeniedException {
        work.corpus.runtime.ageGraphService.upsertAssociationEdge(uceAssociationEdge(work, uimaType, id, leftUid, rightUid, name, features));
    }

    private AgeGraphService.AssociationEdge uceAssociationEdge(UCEDocumentArtifact work,
                                                               String uimaType,
                                                               String id,
                                                               String leftUid,
                                                               String rightUid,
                                                               String name,
                                                               java.util.Map<String, String> features) {
        return new AgeGraphService.AssociationEdge(
                uimaType + ":" + id,
                uimaType,
                work.corpus.corpus.getId(),
                work.document.getId(),
                leftUid,
                rightUid,
                name,
                null,
                features == null || features.isEmpty() ? null : gson.toJson(features)
        );
    }

    private String uceUid(String typeName, String id) {
        return typeName + ":" + id;
    }

    private java.util.Map<String, String> mapOf(String... values) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private String stableHash(String value) {
        return UUID.nameUUIDFromBytes(nullToEmpty(value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String superTypeName(JCas jCas, String typeName) {
        var type = jCas.getTypeSystem().getType(typeName);
        var parent = type == null ? null : jCas.getTypeSystem().getParent(type);
        return parent == null ? "" : parent.getName();
    }

    private String shortTypeName(String typeName) {
        int idx = typeName.lastIndexOf('.');
        return idx < 0 ? typeName : typeName.substring(idx + 1);
    }

    private String containingPageDomainId(UCEDocumentArtifact work, String documentDomainId, int begin, int end) {
        if (work.document.getPages() == null) {
            return null;
        }
        for (Page page : work.document.getPages()) {
            if (begin >= page.getBegin() && end <= page.getEnd()) {
                return pageDomainId(documentDomainId, page);
            }
        }
        return null;
    }

    private String corpusDomainId(UCECorpusArtifact work) {
        String config = work.corpus == null ? null : work.corpus.getCorpusJsonConfig();
        return "corpus:" + stableHash(config);
    }

    private String corpusDomainId(UCEDocumentArtifact work) {
        return corpusDomainId(work.corpus);
    }

    private String documentDomainId(UCEDocumentArtifact work) {
        if (work.document == null || work.document.getDocumentId() == null || work.document.getDocumentId().isBlank()) {
            return null;
        }
        return corpusDomainId(work) + ":document:" + work.document.getDocumentId();
    }

    private String pageDomainId(String documentDomainId, Page page) {
        String pageKey = page.getPageIdentifier() == null || page.getPageIdentifier().isBlank()
                ? stableHash(documentDomainId + ":page:" + page.getPageNumber() + ":" + page.getBegin() + ":" + page.getEnd())
                : page.getPageIdentifier();
        return documentDomainId + ":page:" + pageKey;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void runCorpusPostProcessing(UCECorpusArtifact work) {
        if (work.corpusConfig.getAnnotations().isUnifiedTopic()) {
            logger.info("Inserting into Document and Corpus Topic word tables...");
            try {
                var commonConfig = new CommonConfig();

                Path insertDocumentTopicWordFilePath = Path.of(commonConfig.getDatabaseScriptsLocation(), "topic/3_updateDocumentTopicWord.sql");
                String insertDocumentTopicWordScript = Files.readString(insertDocumentTopicWordFilePath);
                try {
                    work.runtime.storageMaintenance.executeStorageStatement(insertDocumentTopicWordScript);
                } catch (Exception ex) {
                    logger.error("Error executing SQL script to populate documenttopicword table", ex);
                }

                Path insertCorpusTopicWordFilePath = Path.of(commonConfig.getDatabaseScriptsLocation(), "topic/4_updateCorpusTopicWord.sql");
                String insertCorpusTopicWordScript = Files.readString(insertCorpusTopicWordFilePath);
                try {
                    work.runtime.storageMaintenance.executeStorageStatement(insertCorpusTopicWordScript);
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

    private static Corpus createDBCorpus(Corpus corpus, CorpusConfig corpusConfig, DataInterface db) throws DatabaseOperationException, DocumentAccessDeniedException {
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
        if (runtime.gnfinderPodman != null) {
            try {
                runtime.gnfinderPodman.close();
            } catch (Exception ignored) {
            }
            runtime.gnfinderPodman = null;
        }
        if (runtime.adminGuard != null) {
            try {
                runtime.adminGuard.close();
            } catch (Exception ignored) {
            }
            runtime.adminGuard = null;
        }
    }

    private static void flushDuaStoreQuietly(ImportRuntimeState runtime) {
        if (runtime.db instanceof DuaDataInterface_Impl duaDataInterface) {
            try {
                duaDataInterface.flushPendingStore();
            } catch (RuntimeException ex) {
                logger.error("Failed to flush DUA store.", ex);
            }
        }
    }

    private static boolean gnFinderPodmanEnabled() {
        String raw = System.getenv(GNFINDER_ENABLED_ENV);
        if (raw != null && !raw.isBlank()) {
            return Boolean.parseBoolean(raw.trim());
        }
        return configuredGnFinderImage() != null;
    }

    private static String configuredGnFinderImage() {
        String raw = System.getProperty(GNFINDER_IMAGE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(GNFINDER_IMAGE_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String configuredGnFinderParameter(String name, String defaultValue) {
        String raw = System.getenv("UCE_DUUI_GNFINDER_" + name.toUpperCase(Locale.ROOT));
        return raw == null || raw.isBlank() ? defaultValue : raw.trim();
    }

    private static int configuredGnFinderInt(String name, int defaultValue) {
        String raw = configuredGnFinderParameter(name, Integer.toString(defaultValue));
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return Math.max(1, defaultValue);
        }
    }

    private static String initialViewName(String casView) {
        return casView == null || casView.isBlank() ? "_InitialView" : casView;
    }

    private static void addOptionalParameter(Map<String, String> parameters, String name) {
        String value = configuredGnFinderParameter(name, "");
        if (!value.isBlank()) {
            parameters.put(name, value);
        }
    }

    private static JCas healthCas() throws Exception {
        JCas cas = JCasFactory.createJCas();
        cas.setDocumentLanguage("en");
        cas.setDocumentText("DUUI GNFinder health check.");
        return cas;
    }

    @FunctionalInterface
    private interface StageProcessor<T> {
        DUUIArtifact<T> process(DUUIArtifact<T> artifact) throws Exception;
    }

    private static final class RuntimeToCorpus implements DUUIAdapter<UCEImportArtifact, UCECorpusArtifact> {
        static Builder builder() { return new Builder(); }

        @Override
        public DUUIArtifact<UCECorpusArtifact> adapt(DUUIArtifact<UCEImportArtifact> artifact) {
            return DUUIArtifact.of(new UCECorpusArtifact(artifact.payload().runtime));
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
        public void fork(DUUIArtifact<UCECorpusArtifact> artifact, DUUIArtifactEmitter<UCEDocumentArtifact> emitter) throws Exception {
            UCECorpusArtifact corpus = artifact.payload();
            ImportRuntimeState runtime = corpus.runtime;
            if (runtime.sourcePath == null || runtime.sourcePath.isBlank()) {
                var metadata = JCasUtil.selectSingle(runtime.inputCas, DocumentMetaData.class);
                String documentId = metadata.getDocumentId();
                emitter.emit(DUUIArtifact.of(UCEDocumentArtifact.mainCas(corpus, documentId)));
                return;
            }
            Path inputFolder = runtime.preparedImportPath.resolve("input");
            try (Stream<Path> fileStream = Files.walk(inputFolder)) {
                var files = fileStream.filter(Files::isRegularFile)
                        .filter(path -> StringUtils.checkIfFileHasExtension(path.toString().toLowerCase(), COMPATIBLE_CAS_FILE_ENDINGS))
                        .sorted()
                        .toList();
                logger.info("DUUIImporter discovered " + files.size() + " importable CAS/DUA files in " + inputFolder);
                files.forEach(path -> emitter.emit(DUUIArtifact.of(UCEDocumentArtifact.file(corpus, path))));
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
            return DUUIArtifact.of(new JCasArtifact(source, selectedCas));
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
            documentArtifact.jCas = source.jCas;
            return DUUIArtifact.of(documentArtifact);
        }
        static final class Builder {
            private final DUUIImporter importer;
            Builder(DUUIImporter importer) { this.importer = importer; }
            DUUIAdapterScope<JCasArtifact, UCEDocumentArtifact> open(DUUIFlowScope<JCasArtifact> parent) {
                return parent.pipeline().adapter(parent, new JCasToDocument(importer));
            }
        }
    }

    private static final class GnfinderPodmanRuntime implements AutoCloseable {
        private final DUUIComponent<JCas> component;

        private GnfinderPodmanRuntime(DUUIComponent<JCas> component) {
            this.component = component;
        }

        static GnfinderPodmanRuntime start(ImportRuntimeState runtime) throws Exception {
            String image = configuredGnFinderImage();
            if (image == null || image.isBlank()) {
                image = GNFINDER_DEFAULT_IMAGE;
            }
            String viewName = initialViewName(runtime.casView);
            int scale = configuredGnFinderInt("scale", Math.min(Math.max(1, runtime.numThreads), 4));
            int concurrency = configuredGnFinderInt("concurrency", 1);
            long timeoutSeconds = configuredGnFinderInt("timeout_seconds", 600);
            boolean imageFetching = Boolean.parseBoolean(configuredGnFinderParameter("image_fetching", "false"));
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("lang", configuredGnFinderParameter("lang", "detect"));
            parameters.put("verify", configuredGnFinderParameter("verify", "true"));
            parameters.put("timeout_seconds", configuredGnFinderParameter("process_timeout_seconds", "120"));
            addOptionalParameter(parameters, "gnfinder_binary");
            addOptionalParameter(parameters, "sources");
            addOptionalParameter(parameters, "words_around");
            addOptionalParameter(parameters, "utf8_input");
            addOptionalParameter(parameters, "no_bayes");
            addOptionalParameter(parameters, "adjust_odds");
            addOptionalParameter(parameters, "ambiguous_uninomials");
            addOptionalParameter(parameters, "all_matches");
            addOptionalParameter(parameters, "unique_names");

            DUUIPodmanDriver driver = new DUUIPodmanDriver();
            driver.setLuaContext(LuaConsts.getJSON());
            DUUIPipelineComponent podmanComponent = new DUUIPodmanDriver.Component(image)
                    .withScale(scale)
                    .withWorkers(1)
                    .withImageFetching(imageFetching)
                    .withSourceView(viewName)
                    .withTargetView(viewName)
                    .build()
                    .withTimeout(timeoutSeconds);
            parameters.forEach(podmanComponent::withParameter);
            String uuid = driver.instantiate(podmanComponent, healthCas(), true, new AtomicBoolean(false));
            List<String> endpoints = driver.getEndpointUrls(uuid);
            if (endpoints.isEmpty()) {
                driver.destroy(uuid);
                throw new IllegalStateException("GNFinder Podman component did not expose any DUUI v1 endpoint.");
            }

            DUUIV1Config config = new DUUIV1Config(
                    concurrency,
                    viewName,
                    viewName,
                    parameters,
                    DUUIV1TelemetryConfig.disabled(),
                    false,
                    "application/octet-stream"
            );
            List<DUUINode<JCas>> nodes = new ArrayList<>();
            int slot = 0;
            int replica = 0;
            for (String endpoint : endpoints) {
                DUUIV1Annotator annotator = new DUUIV1Annotator(
                        "gnfinder-podman-replica-" + replica++,
                        new DUUIHttpEndpoint(URI.create(endpoint), HttpClient.newHttpClient()),
                        config
                );
                for (int i = 0; i < concurrency; i++) {
                    nodes.add(DUUINode.v1("gnfinder-podman-slot-" + slot++, annotator));
                }
            }
            logger.info("Started DUUI GNFinder Podman component image=" + image
                    + " endpoints=" + endpoints.size()
                    + " scale=" + scale
                    + " concurrency=" + concurrency);
            return new GnfinderPodmanRuntime(new DUUIComponent<>("gnfinder-podman", nodes, () -> driver.destroy(uuid)));
        }

        void process(JCas jCas) throws Exception {
            component.process(DUUIArtifact.of(jCas));
        }

        @Override
        public void close() throws Exception {
            component.close();
        }
    }

    private static final class RuntimeSeedGenerator implements DUUIGenerator<UCEImportArtifact> {
        private final ImportRuntimeState runtime;

        private RuntimeSeedGenerator(ImportRuntimeState runtime) {
            this.runtime = runtime;
        }

        @Override
        public void generate(DUUIArtifactEmitter<UCEImportArtifact> emitter) throws Exception {
            emitter.emit(DUUIArtifact.of(new UCEImportArtifact(runtime)));
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
        final ConcurrentLinkedQueue<ImportOperationRecord> operations = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<UceDomainRecord> uceDomains = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<UceAssociationRecord> uceAssociations = new ConcurrentLinkedQueue<>();

        Path preparedImportPath;
        DocumentAccessManager accessManager;
        AutoCloseable adminGuard;
        DataInterface db;
        StorageMaintenanceService storageMaintenance;
        LexiconService lexiconService;
        EmbeddingService embeddingService;
        DomainGraphService ageGraphService;
        boolean domainGraphInitialized;
        DocumentImportContinuation continuation;
        GnfinderPodmanRuntime gnfinderPodman;
        CopyOnWriteArrayList<UCEMetadataFilter> uceMetadataFilters = new CopyOnWriteArrayList<>();
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

        synchronized GnfinderPodmanRuntime gnfinderPodman() throws Exception {
            if (gnfinderPodman == null) {
                gnfinderPodman = GnfinderPodmanRuntime.start(this);
            }
            return gnfinderPodman;
        }
    }

    private record ImportOperationRecord(
            String name,
            String status,
            int retries,
            String error,
            long startedAt,
            long finishedAt,
            String corpusDomainId,
            String documentDomainId
    ) {
    }

    private record UceDomainRecord(
            String uimaType,
            String id,
            String name,
            String uri,
            java.util.Map<String, String> features,
            String corpusDomainId,
            String documentDomainId
    ) {
    }

    private record UceAssociationRecord(
            String uimaType,
            String id,
            String leftUid,
            String rightUid,
            String name,
            java.util.Map<String, String> features,
            String corpusDomainId,
            String documentDomainId
    ) {
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
        JCas jCas;

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
