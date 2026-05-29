package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.models.Linkable;
import org.texttechnologylab.uce.common.models.ModelBase;
import org.texttechnologylab.uce.common.models.UIMAAnnotation;
import org.texttechnologylab.uce.common.models.biofid.BiofidTaxon;
import org.texttechnologylab.uce.common.models.biofid.GazetteerTaxon;
import org.texttechnologylab.uce.common.models.biofid.GnFinderTaxon;
import org.texttechnologylab.uce.common.models.corpus.*;
import org.texttechnologylab.uce.common.models.corpus.emotion.Emotion;
import org.texttechnologylab.uce.common.models.corpus.links.*;
import org.texttechnologylab.uce.common.models.dto.UCEMetadataFilterDto;
import org.texttechnologylab.uce.common.models.dto.map.MapClusterDto;
import org.texttechnologylab.uce.common.models.dto.map.PointDto;
import org.texttechnologylab.uce.common.models.gbif.GbifOccurrence;
import org.texttechnologylab.uce.common.models.globe.GlobeTaxon;
import org.texttechnologylab.uce.common.models.imp.ImportLog;
import org.texttechnologylab.uce.common.models.imp.UCEImport;
import org.texttechnologylab.uce.common.models.negation.*;
import org.texttechnologylab.uce.common.models.search.AnnotationSearchResult;
import org.texttechnologylab.uce.common.models.search.DocumentSearchResult;
import org.texttechnologylab.uce.common.models.search.OrderByColumn;
import org.texttechnologylab.uce.common.models.search.PageSnippet;
import org.texttechnologylab.uce.common.models.search.SearchLayer;
import org.texttechnologylab.uce.common.models.search.SearchOrder;
import org.texttechnologylab.uce.common.models.topic.TopicWord;
import org.texttechnologylab.uce.common.models.topic.UnifiedTopic;
import org.texttechnologylab.uce.common.utils.ReflectionUtils;
import org.texttechnologylab.uce.common.utils.StringUtils;
import org.texttechnologylab.uce.common.utils.SystemStatus;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class DuaDataInterface_Impl implements DataInterface {
    public static final String STORE_ENV_NAME = "UCE_DUA_STORE";
    public static final String STORE_PROPERTY_NAME = "uce.dua.store";
    public static final String FLUSH_EVERY_MUTATIONS_ENV_NAME = "UCE_DUA_FLUSH_EVERY_MUTATIONS";
    public static final String FLUSH_EVERY_MUTATIONS_PROPERTY_NAME = "uce.dua.flushEveryMutations";

    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, Corpus> corporaById = new ConcurrentHashMap<>();
    private final Map<Long, Document> documentsById = new ConcurrentHashMap<>();
    private final Map<String, Document> documentsByCorpusAndDocumentId = new ConcurrentHashMap<>();
    private final Map<Long, Page> pagesById = new ConcurrentHashMap<>();
    private final Map<Long, UIMAAnnotation> annotationsById = new ConcurrentHashMap<>();
    private final Map<LexiconEntryId, LexiconEntry> lexiconById = new ConcurrentHashMap<>();
    private final Map<String, UCEImport> importsById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> taxonIdentifierLookupCache = new ConcurrentHashMap<>();
    private final List<DocumentLink> documentLinks = synchronizedList();
    private final List<DocumentToAnnotationLink> documentToAnnotationLinks = synchronizedList();
    private final List<AnnotationToDocumentLink> annotationToDocumentLinks = synchronizedList();
    private final List<AnnotationLink> annotationLinks = synchronizedList();
    private final List<ImportLog> importLogs = synchronizedList();
    private final List<UCELog> uceLogs = synchronizedList();
    private final Object persistenceLock = new Object();
    private final Object lexiconLock = new Object();
    private final Path storePath;
    private final int storeFlushEveryMutations;
    private int pendingStoreMutations = 0;
    private volatile boolean lexiconDirty = true;

    public DuaDataInterface_Impl() {
        this.storePath = configuredStorePath();
        this.storeFlushEveryMutations = configuredPositiveInt(FLUSH_EVERY_MUTATIONS_PROPERTY_NAME, FLUSH_EVERY_MUTATIONS_ENV_NAME, 1);
        loadStore();
        if (storePath != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushStoreQuietly, "dua-store-flush"));
        }
    }

    @Override
    public String backendName() {
        return "dua";
    }

    @Override
    public List<AnnotationSearchResult> getAnnotationsOfCorpus(long corpusId, int skip, int take) {
        return page(documentsInCorpus(corpusId)
                .flatMap(document -> annotationsOf(document).stream())
                .map(annotation -> annotationSearchResult(annotation, label(annotation), 1))
                .toList(), skip, take);
    }

    @Override
    public List<String> getIdentifiableTaxonsByValue(String token) {
        String needle = normalize(token);
        if (needle.isBlank()) {
            return List.of();
        }
        return taxonIdentifierLookupCache.computeIfAbsent(needle, this::lookupIdentifiableTaxonsByValue);
    }

    private List<String> lookupIdentifiableTaxonsByValue(String needle) {
        Map<String, Integer> rankedIdentifiers = new LinkedHashMap<>();
        Map<String, Integer> rankedNamesWithoutIdentifiers = new LinkedHashMap<>();
        int resultLimit = needle.length() <= 5 ? 30 : 60;

        for (Document document : documentsById.values()) {
            for (BiofidTaxon taxon : safe(document.getBiofidTaxons())) {
                int rank = taxonMatchRank(needle,
                        taxon.getPrimaryName(),
                        taxon.getScientificName(),
                        taxon.getCleanedScientificName(),
                        taxon.getVernacularName(),
                        taxon.getCoveredText());
                addRankedTaxonIdentifier(rankedIdentifiers, taxon.getBiofidUrl(), rank);
                addRankedTaxonNames(rankedNamesWithoutIdentifiers, rank,
                        taxon.getPrimaryName(),
                        taxon.getScientificName(),
                        taxon.getCleanedScientificName(),
                        taxon.getCoveredText());
            }

            for (Taxon taxon : safe(document.getAllTaxa())) {
                int rank = taxonMatchRank(needle,
                        taxon.getValue(),
                        taxon.getCoveredText(),
                        taxon instanceof GnFinderTaxon gnFinderTaxon ? gnFinderTaxon.getMatchedName() : null,
                        taxon instanceof GnFinderTaxon gnFinderTaxon ? gnFinderTaxon.getMatchedCanonical() : null,
                        taxon instanceof GazetteerTaxon gazetteerTaxon ? gazetteerTaxon.getPrimaryIdentifier() : null);
                if (rank == Integer.MAX_VALUE) {
                    continue;
                }
                for (String identifier : taxonIdentifiers(taxon)) {
                    addRankedTaxonIdentifier(rankedIdentifiers, identifier, rank);
                }
                addRankedTaxonNames(rankedNamesWithoutIdentifiers, rank,
                        taxon.getValue(),
                        taxon.getCoveredText(),
                        taxon instanceof GnFinderTaxon gnFinderTaxon ? gnFinderTaxon.getMatchedName() : null,
                        taxon instanceof GnFinderTaxon gnFinderTaxon ? gnFinderTaxon.getMatchedCanonical() : null);
            }
        }

        List<String> identifiers = rankedIdentifiers.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .limit(resultLimit)
                .map(Map.Entry::getKey)
                .toList();
        if (!identifiers.isEmpty()) {
            return identifiers;
        }

        return resolveTaxonNamesThroughSparql(needle, rankedNamesWithoutIdentifiers, resultLimit);
    }

    @Override
    public int countDocumentsInCorpus(long id) {
        return (int) documentsInCorpus(id).count();
    }

    @Override
    public int countPagesInCorpus(long corpusId) {
        return (int) documentsInCorpus(corpusId).mapToLong(document -> pages(document).size()).sum();
    }

    @Override
    public boolean documentExists(long corpusId, String documentId) {
        return documentsByCorpusAndDocumentId.containsKey(corpusDocumentKey(corpusId, documentId));
    }

    @Override
    public Corpus getCorpusById(long id) {
        return corporaById.get(id);
    }

    @Override
    public void savePageKeywordDistribution(Page page) {
        if (page != null) {
            pagesById.put(page.getId(), page);
            persistStore();
        }
    }

    @Override
    public void saveDocumentKeywordDistribution(Document document) {
        updateDocument(document);
    }

    @Override
    public Corpus getCorpusByName(String name) {
        return corporaById.values().stream()
                .filter(corpus -> name != null && name.equals(corpus.getName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<UCEMetadataFilter> getUCEMetadataFiltersByCorpusId(long corpusId) {
        Corpus corpus = corporaById.get(corpusId);
        return corpus == null ? List.of() : corpus.getUceMetadataFilters();
    }

    @Override
    public List<Document> getDocumentsByCorpusId(long corpusId, int skip, int take) {
        return page(documentsInCorpus(corpusId)
                .sorted(Comparator.comparingLong(Document::getId))
                .toList(), skip, take);
    }

    @Override
    public List<DocumentLink> getManyDocumentLinksOfDocument(long id) {
        return documentLinks.stream()
                .filter(link -> link.getFromId() == id || link.getToId() == id)
                .toList();
    }

    @Override
    public List<DocumentLink> getManyDocumentLinksByDocumentId(String documentId, long corpusId) {
        return documentLinks.stream()
                .filter(link -> link.getCorpusId() == corpusId)
                .filter(link -> documentId.equals(link.getFrom()) || documentId.equals(link.getTo()))
                .toList();
    }

    @Override
    public List<Document> getNonePostprocessedDocumentsByCorpusId(long corpusId) {
        return documentsInCorpus(corpusId)
                .filter(document -> !document.isPostProcessed())
                .toList();
    }

    @Override
    public CorpusTsnePlot getCorpusTsnePlotByCorpusId(long corpusId) {
        Corpus corpus = corporaById.get(corpusId);
        return corpus == null ? null : corpus.getCorpusTsnePlot();
    }

    @Override
    public List<Corpus> getAllCorpora() {
        return corporaById.values().stream()
                .sorted(Comparator.comparingLong(Corpus::getId))
                .toList();
    }

    @Override
    public List<GlobeTaxon> getGlobeDataForDocument(long documentId) {
        return List.of();
    }

    @Override
    public List<TopicWord> getNormalizedTopicWordsForCorpus(long corpusId) {
        return List.of();
    }

    @Override
    public Map<String, Double> getTopNormalizedTopicsByCorpusId(long corpusId) {
        return Map.of();
    }

    @Override
    public List<Document> getManyDocumentsByIds(List<Long> documentIds) {
        return safe(documentIds).stream()
                .map(documentsById::get)
                .filter(document -> document != null)
                .toList();
    }

    @Override
    public boolean hasDocumentAccess(String principal, long documentId, DocumentPermission.DOCUMENT_PERMISSION_LEVEL level) {
        return true;
    }

    @Override
    public Map<Long, Boolean> hasDocumentAccess(String principal, List<Long> documentIds, DocumentPermission.DOCUMENT_PERMISSION_LEVEL level) {
        Map<Long, Boolean> result = new HashMap<>();
        for (Long id : safe(documentIds)) {
            result.put(id, true);
        }
        return result;
    }

    @Override
    public void calculateEffectivePermissions(String username, Set<String> groups) {
    }

    @Override
    public List<LexiconEntry> getManyLexiconEntries(int skip,
                                                    int take,
                                                    List<String> alphabet,
                                                    List<String> annotationFilters,
                                                    String sortColumn,
                                                    String sortOrder,
                                                    String searchInput) {
        ensureLexicon();
        String search = normalize(searchInput);
        Set<String> filters = Set.copyOf(annotationFilters == null ? List.of() : annotationFilters);
        return page(lexiconById.values().stream()
                .filter(entry -> filters.isEmpty() || filters.contains(entry.getId().getType()))
                .filter(entry -> search.isBlank() || contains(entry.getId().getCoveredText(), search))
                .sorted(Comparator.comparing(entry -> entry.getId().getCoveredText()))
                .toList(), skip, take);
    }

    @Override
    public DocumentSearchResult semanticRoleSearchForDocuments(int skip,
                                                               int take,
                                                               List<String> arg0,
                                                               List<String> arg1,
                                                               List<String> arg2,
                                                               List<String> argm,
                                                               String verb,
                                                               boolean countAll,
                                                               SearchOrder order,
                                                               OrderByColumn orderedByColumn,
                                                               long corpusId) {
        return searchDocuments(corpusId, skip, take, tokens(arg0, arg1, arg2, argm, verb == null ? List.of() : List.of(verb)), SearchLayer.FULLTEXT);
    }

    @Override
    public DocumentSearchResult completeNegationSearchForDocuments(int skip,
                                                                   int take,
                                                                   List<String> cue,
                                                                   List<String> event,
                                                                   List<String> focus,
                                                                   List<String> scope,
                                                                   List<String> xscope,
                                                                   boolean countAll,
                                                                   SearchOrder order,
                                                                   OrderByColumn orderedByColumn,
                                                                   long corpusId,
                                                                   List<UCEMetadataFilterDto> filters) {
        return searchDocuments(corpusId, skip, take, tokens(cue, event, focus, scope, xscope), SearchLayer.NAMED_ENTITIES);
    }

    @Override
    public DocumentSearchResult defaultSearchForDocuments(int skip,
                                                          int take,
                                                          String ogSearchQuery,
                                                          List<String> searchTokens,
                                                          SearchLayer layer,
                                                          boolean countAll,
                                                          SearchOrder order,
                                                          OrderByColumn orderedByColumn,
                                                          long corpusId,
                                                          List<UCEMetadataFilterDto> uceMetadataFilters,
                                                          boolean useTsVectorSearch,
                                                          String schema,
                                                          String sourceTable,
                                                          List<String> expandedTerms) {
        List<String> terms = searchTermsForDua(ogSearchQuery, searchTokens, expandedTerms, useTsVectorSearch);
        return searchDocuments(corpusId, skip, take, terms, layer, order, orderedByColumn);
    }

    @Override
    public <T extends KeywordDistribution> T getKeywordDistributionById(Class<T> clazz, long id) {
        return null;
    }

    @Override
    public <T extends KeywordDistribution> List<T> getKeywordDistributionsByString(Class<T> clazz, String topic, int limit) {
        return List.of();
    }

    @Override
    public Document getDocumentByCorpusAndDocumentId(long corpusId, String documentId) {
        return documentsByCorpusAndDocumentId.get(corpusDocumentKey(corpusId, documentId));
    }

    @Override
    public List<UCEMetadata> getUCEMetadataByDocumentId(long documentId) {
        Document document = documentsById.get(documentId);
        return document == null ? List.of() : document.getUceMetadata();
    }

    @Override
    public UCEImport getUceImportByImportId(String importId) {
        return importsById.get(importId);
    }

    @Override
    public Document getDocumentById(long id) {
        return documentsById.get(id);
    }

    @Override
    public Page getPageById(long id) {
        return pagesById.get(id);
    }

    @Override
    public Page getPageByDocumentIdAndBeginEnd(long documentId, int begin, int end, boolean initialize) {
        Document document = documentsById.get(documentId);
        if (document == null) {
            return null;
        }
        return pages(document).stream()
                .filter(page -> begin >= page.getBegin() && end <= page.getEnd())
                .findFirst()
                .orElse(null);
    }

    @Override
    public Linkable getLinkable(long id, Class<? extends Linkable> clazz) {
        Linkable linkable = getLinkableById(id, clazz);
        if (linkable != null) {
            linkable.initLinkableViewModel(this);
        }
        return linkable;
    }

    @Override
    public Linkable getLinkable(long id, String className) throws ClassNotFoundException {
        return getLinkable(id, ReflectionUtils.getClassFromClassName(className, Linkable.class));
    }

    @Override
    public List<Link> getAllLinksOfLinkable(long id,
                                            Class<? extends Linkable> linkableType,
                                            List<Class<? extends ModelBase>> possibleLinkTypes) {
        Set<Class<? extends ModelBase>> accepted = Set.copyOf(possibleLinkTypes == null ? List.of() : possibleLinkTypes);
        return allLinks().stream()
                .filter(link -> accepted.isEmpty() || accepted.stream().anyMatch(type -> type.isInstance(link)))
                .filter(link -> link.getFromId() == id || link.getToId() == id)
                .toList();
    }

    @Override
    public List<UIMAAnnotation> getManyUIMAAnnotationsByCoveredText(String coveredText,
                                                                    Class<? extends UIMAAnnotation> clazz,
                                                                    int skip,
                                                                    int take) {
        String needle = normalize(coveredText);
        return page(annotationsById.values().stream()
                .filter(clazz::isInstance)
                .filter(annotation -> normalize(annotation.getCoveredText()).equals(needle))
                .sorted(Comparator.comparingLong(ModelBase::getId))
                .toList(), skip, take);
    }

    @Override
    public List<PointDto> getGeonameTimelineLinks(double minLng,
                                                  double minLat,
                                                  double maxLng,
                                                  double maxLat,
                                                  Date fromDate,
                                                  Date toDate,
                                                  long corpusId,
                                                  int skip,
                                                  int take,
                                                  String fromAnnotationTypeTable) {
        List<PointDto> points = documentsInCorpus(corpusId)
                .flatMap(document -> safe(document.getGeoNames()).stream())
                .filter(geoName -> geoName.getLongitude() >= minLng && geoName.getLongitude() <= maxLng)
                .filter(geoName -> geoName.getLatitude() >= minLat && geoName.getLatitude() <= maxLat)
                .map(this::point)
                .toList();
        return page(points, skip, take);
    }

    @Override
    public List<MapClusterDto> getGeonameClustersFromTimelineMap(double minLng,
                                                                 double minLat,
                                                                 double maxLng,
                                                                 double maxLat,
                                                                 double gridSize,
                                                                 Date fromDate,
                                                                 Date toDate,
                                                                 long corpusId) {
        Map<String, MapClusterDto> clusters = new LinkedHashMap<>();
        documentsInCorpus(corpusId)
                .flatMap(document -> safe(document.getGeoNames()).stream())
                .filter(geoName -> geoName.getLongitude() >= minLng && geoName.getLongitude() <= maxLng)
                .filter(geoName -> geoName.getLatitude() >= minLat && geoName.getLatitude() <= maxLat)
                .forEach(geoName -> {
                    long lngBucket = Math.round(geoName.getLongitude() / Math.max(gridSize, 0.000001d));
                    long latBucket = Math.round(geoName.getLatitude() / Math.max(gridSize, 0.000001d));
                    String key = lngBucket + ":" + latBucket;
                    MapClusterDto cluster = clusters.computeIfAbsent(key, ignored -> {
                        MapClusterDto dto = new MapClusterDto();
                        dto.setLongitude(geoName.getLongitude());
                        dto.setLatitude(geoName.getLatitude());
                        return dto;
                    });
                    cluster.setCount(cluster.getCount() + 1);
                });
        return new ArrayList<>(clusters.values());
    }

    @Override
    public List<String> getDistinctTimeCoveredTexts(String unitName,
                                                    String value,
                                                    Long fromYear,
                                                    Long toYear,
                                                    long corpusId,
                                                    int limit) {
        String unit = normalize(unitName);
        String expected = normalize(value);
        return documentsInCorpus(corpusId)
                .flatMap(document -> safe(document.getTimes()).stream())
                .filter(time -> matchesTime(time, unit, expected, fromYear, toYear))
                .map(Time::getCoveredText)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    @Override
    public List<String> getDistinctGeonamesNamesByFeatureCode(GeoNameFeatureClass featureClass, String featureCode, long corpusId, int limit) {
        return documentsInCorpus(corpusId)
                .flatMap(document -> safe(document.getGeoNames()).stream())
                .filter(geoName -> featureClass == null || featureClass.equals(geoName.getFeatureClass()))
                .filter(geoName -> featureCode == null || featureCode.isBlank() || featureCode.equals(geoName.getFeatureCode()))
                .map(GeoName::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    @Override
    public List<String> getDistinctGeonamesNamesByRadius(double longitude, double latitude, double radius, long corpusId, int limit) {
        return documentsInCorpus(corpusId)
                .flatMap(document -> safe(document.getGeoNames()).stream())
                .filter(geoName -> distanceMeters(latitude, longitude, geoName.getLatitude(), geoName.getLongitude()) <= radius)
                .map(GeoName::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    @Override
    public List<GbifOccurrence> getGbifOccurrencesByGbifTaxonId(long gbifTaxonId) {
        return List.of();
    }

    @Override
    public List<Document> getDocumentsByAnnotationCoveredText(String coveredText, int limit, String annotationName) {
        String needle = normalize(coveredText);
        return documentsById.values().stream()
                .filter(document -> annotationsOf(document).stream().anyMatch(annotation -> normalize(annotation.getCoveredText()).equals(needle)))
                .limit(limit)
                .toList();
    }

    @Override
    public List<Lemma> getLemmasWithinBeginAndEndOfDocument(int begin, int end, long documentId) {
        Document document = documentsById.get(documentId);
        return document == null
                ? List.of()
                : safe(document.getLemmas()).stream()
                .filter(lemma -> lemma.getBegin() >= begin && lemma.getEnd() <= end)
                .toList();
    }

    @Override
    public GeoName getGeoNameAnnotationById(long id) {
        return castAnnotation(id, GeoName.class);
    }

    @Override
    public Time getTimeAnnotationById(long id) {
        return castAnnotation(id, Time.class);
    }

    @Override
    public Sentence getSentenceAnnotationById(long id) {
        return castAnnotation(id, Sentence.class);
    }

    @Override
    public CompleteNegation getCompleteNegationByCueId(long id) {
        return documentsById.values().stream()
                .flatMap(document -> safe(document.getCompleteNegations()).stream())
                .filter(negation -> negation.getCue() != null && negation.getCue().getId() == id)
                .findFirst()
                .orElse(null);
    }

    @Override
    public UnifiedTopic getInitializedUnifiedTopicById(long id) {
        return castAnnotation(id, UnifiedTopic.class);
    }

    @Override
    public long countLexiconEntries() {
        ensureLexicon();
        return lexiconById.size();
    }

    @Override
    public int refreshLexicon(List<String> annotationTables, boolean forceRecalculate) {
        int before;
        int after;
        synchronized (lexiconLock) {
            before = lexiconById.size();
            rebuildLexicon();
            lexiconDirty = false;
            after = lexiconById.size();
        }
        persistStore();
        return Math.max(0, after - before);
    }

    @Override
    public int refreshLogicalLinks() {
        return 0;
    }

    @Override
    public int refreshGeonameLocations() {
        return 0;
    }

    @Override
    public LexiconEntry getLexiconEntryId(LexiconEntryId id) {
        ensureLexicon();
        return lexiconById.get(id);
    }

    @Override
    public NamedEntity getNamedEntityById(long id) {
        return castAnnotation(id, NamedEntity.class);
    }

    @Override
    public GazetteerTaxon getGazetteerTaxonById(long id) {
        return castAnnotation(id, GazetteerTaxon.class);
    }

    @Override
    public GnFinderTaxon getGnFinderTaxonById(long id) {
        return castAnnotation(id, GnFinderTaxon.class);
    }

    @Override
    public BiofidTaxon getBiofidTaxonById(long id) {
        return castAnnotation(id, BiofidTaxon.class);
    }

    @Override
    public Lemma getLemmaById(long id) {
        return castAnnotation(id, Lemma.class);
    }

    @Override
    public List<Lemma> getLemmasByValue(String covered, int limit, long documentId) {
        String needle = normalize(covered);
        Document document = documentsById.get(documentId);
        return document == null
                ? List.of()
                : safe(document.getLemmas()).stream()
                .filter(lemma -> normalize(lemma.getCoveredText()).equals(needle) || normalize(lemma.getValue()).equals(needle))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean checkIfGbifOccurrencesExist(long gbifTaxonId) {
        return false;
    }

    @Override
    public Document getCompleteDocumentById(long id, int skipPages, int pageLimit) {
        return documentsById.get(id);
    }

    @Override
    public void saveOrUpdateImportLog(ImportLog importLog) {
        if (importLog != null) {
            assignId(importLog);
            importLogs.add(importLog);
            persistStore();
        }
    }

    @Override
    public void saveOrUpdateUceImport(UCEImport uceImport) {
        if (uceImport != null) {
            assignId(uceImport);
            importsById.put(uceImport.getImportId(), uceImport);
            persistStore();
        }
    }

    @Override
    public void saveOrUpdateUCEMetadataFilter(UCEMetadataFilter filter) {
        saveUCEMetadataFilter(filter);
    }

    @Override
    public void saveUCEMetadataFilter(UCEMetadataFilter filter) {
        if (filter == null) {
            return;
        }
        assignId(filter);
        Corpus corpus = corporaById.get(filter.getCorpusId());
        if (corpus == null) {
            return;
        }
        List<UCEMetadataFilter> filters = new ArrayList<>(corpus.getUceMetadataFilters());
        filters.removeIf(existing -> existing.getId() == filter.getId());
        filters.add(filter);
        corpus.setUceMetadataFilters(filters);
        persistStore();
    }

    @Override
    public void saveDocument(Document document) {
        if (document == null) {
            return;
        }
        assignId(document);
        indexDocument(document);
        persistStore();
    }

    @Override
    public void updateDocument(Document document) {
        saveDocument(document);
    }

    @Override
    public void saveUceLog(UCELog log) {
        if (log != null) {
            assignId(log);
            uceLogs.add(log);
            persistStore();
        }
    }

    @Override
    public void saveOrUpdateCorpusTsnePlot(CorpusTsnePlot corpusTsnePlot, Corpus corpus) {
        if (corpus != null) {
            corpus.setCorpusTsnePlot(corpusTsnePlot);
            saveCorpus(corpus);
        }
    }

    @Override
    public DocumentTopThreeTopics getDocumentTopThreeTopicsById(long id) {
        return documentsById.values().stream()
                .map(Document::getDocumentTopThreeTopics)
                .filter(topics -> topics != null && topics.getId() == id)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Object[]> getTopTopicsByDocument(long documentId, int limit) {
        return List.of();
    }

    @Override
    public List<Object[]> getTopDocumentsByTopicLabel(String topicValue, long corpusId, int limit) {
        return List.of();
    }

    @Override
    public List<TopicWord> getTopicWordsByTopicLabel(String topicValue, long corpusId) {
        return List.of();
    }

    @Override
    public List<Object[]> getSimilarTopicsbyTopicLabel(String topicValue, long corpusId, int minSharedWords, int resultLimit) {
        return List.of();
    }

    @Override
    public List<TopicWord> getDocumentWordDistribution(long documentId) {
        return List.of();
    }

    @Override
    public List<Object[]> getSimilarDocumentbyDocumentId(long documentId) {
        return List.of();
    }

    @Override
    public void saveOrUpdateManyDocumentToAnnotationLinks(List<DocumentToAnnotationLink> links) {
        addLinks(documentToAnnotationLinks, links);
        persistStore();
    }

    @Override
    public void saveOrUpdateManyAnnotationLinks(List<AnnotationLink> links) {
        addLinks(annotationLinks, links);
        persistStore();
    }

    @Override
    public void saveOrUpdateManyAnnotationToDocumentLinks(List<AnnotationToDocumentLink> links) {
        addLinks(annotationToDocumentLinks, links);
        persistStore();
    }

    @Override
    public void saveOrUpdateManyDocumentLinks(List<DocumentLink> links) {
        addLinks(documentLinks, links);
        persistStore();
    }

    @Override
    public void saveCorpus(Corpus corpus) {
        if (corpus == null) {
            return;
        }
        assignId(corpus);
        corporaById.put(corpus.getId(), corpus);
        for (Document document : safe(corpus.getDocuments())) {
            document.setCorpusId(corpus.getId());
            assignId(document);
            indexDocument(document);
        }
        persistStore();
    }

    private DocumentSearchResult searchDocuments(long corpusId, int skip, int take, List<String> terms, SearchLayer layer) {
        return searchDocuments(corpusId, skip, take, terms, layer, SearchOrder.DESC, OrderByColumn.RANK);
    }

    private DocumentSearchResult searchDocuments(long corpusId,
                                                 int skip,
                                                 int take,
                                                 List<String> terms,
                                                 SearchLayer layer,
                                                 SearchOrder order,
                                                 OrderByColumn orderedByColumn) {
        List<String> needles = safe(terms).stream()
                .flatMap(term -> Stream.of(term.split("\\s+")))
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
        Predicate<Document> matches = document -> needles.isEmpty() || needles.stream().anyMatch(term -> matchesDocument(document, term, layer));
        List<Document> allMatches = sortSearchMatches(documentsInCorpus(corpusId).filter(matches).toList(), needles, order, orderedByColumn);
        List<Document> page = page(allMatches, skip, take);
        DocumentSearchResult result = new DocumentSearchResult(allMatches.size(), new ArrayList<>(page.stream().map(Document::getId).toList()));
        result.setDocumentHits(new ArrayList<>(page.stream().map(document -> 1).toList()));
        HashMap<Integer, ArrayList<PageSnippet>> snippetsByDocumentIndex = new HashMap<>();
        HashMap<Long, ArrayList<PageSnippet>> snippetsByDocumentId = new HashMap<>();
        HashMap<Integer, Float> ranksByDocumentIndex = new HashMap<>();
        for (int i = 0; i < page.size(); i++) {
            Document document = page.get(i);
            ArrayList<PageSnippet> snippets = searchSnippets(document, needles);
            snippetsByDocumentIndex.put(i, snippets);
            snippetsByDocumentId.put(document.getId(), snippets);
            ranksByDocumentIndex.put(i, (float) rankDocument(document, needles, layer));
        }
        result.setSearchSnippets(snippetsByDocumentIndex);
        result.setSearchSnippetsDocIdToSnippet(snippetsByDocumentId);
        result.setSearchRanks(ranksByDocumentIndex);
        result.setFoundNamedEntities(new ArrayList<>(annotationResults(page, NamedEntity.class, "namedEntity")));
        result.setFoundTimes(new ArrayList<>(annotationResults(page, Time.class, "time")));
        result.setFoundTaxons(new ArrayList<>(annotationResults(page, BiofidTaxon.class, "taxon")));
        result.setFoundCues(new ArrayList<>(annotationResults(page, Cue.class, "cue")));
        result.setFoundFoci(new ArrayList<>(annotationResults(page, Focus.class, "focus")));
        result.setFoundScopes(new ArrayList<>(annotationResults(page, Scope.class, "scope")));
        result.setFoundXscopes(new ArrayList<>(annotationResults(page, XScope.class, "xscope")));
        result.setFoundEvents(new ArrayList<>(annotationResults(page, Event.class, "event")));
        return result;
    }

    private List<String> searchTermsForDua(String query,
                                           List<String> searchTokens,
                                           List<String> expandedTerms,
                                           boolean proMode) {
        if (expandedTerms != null && !expandedTerms.isEmpty()) {
            return expandedTerms;
        }
        if (searchTokens != null && !searchTokens.isEmpty()) {
            return searchTokens;
        }
        return query == null || query.isBlank() ? List.of() : List.of(query);
    }

    private List<Document> sortSearchMatches(List<Document> documents,
                                             List<String> needles,
                                             SearchOrder order,
                                             OrderByColumn orderedByColumn) {
        OrderByColumn column = orderedByColumn == null ? OrderByColumn.RANK : orderedByColumn;
        Comparator<Document> primary = switch (column) {
            case DOCUMENTTITLE -> Comparator.comparing(
                    document -> normalize(document.getDocumentTitle()),
                    Comparator.nullsLast(String::compareTo));
            case PUBLISHED -> Comparator.comparing(
                    this::publishedSortValue,
                    Comparator.nullsLast(String::compareTo));
            case RANK -> Comparator.comparingInt(document -> rankDocument(document, needles, SearchLayer.FULLTEXT));
        };
        if (order == SearchOrder.DESC) {
            primary = primary.reversed();
        }
        return documents.stream()
                .sorted(primary.thenComparingLong(Document::getId))
                .toList();
    }

    private String publishedSortValue(Document document) {
        if (document == null || document.getMetadataTitleInfo() == null) {
            return "";
        }
        return normalize(document.getMetadataTitleInfo().getPublished());
    }

    private int rankDocument(Document document, List<String> needles, SearchLayer layer) {
        if (needles == null || needles.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String term : needles) {
            if (contains(document.getDocumentTitle(), term)) score += 5;
            if (contains(document.getFullText(), term)) score += 2;
            if (contains(document.getFullTextCleaned(), term)) score += 2;
            for (UIMAAnnotation annotation : annotationsOf(document)) {
                if (contains(annotation.getCoveredText(), term)) score += 3;
                if (annotation instanceof Taxon taxon) {
                    if (contains(taxon.getValue(), term)) score += 3;
                    if (contains(taxon.getIdentifier(), term)) score += 2;
                }
                if (annotation instanceof BiofidTaxon taxon) {
                    if (contains(taxon.getPrimaryName(), term)) score += 4;
                    if (contains(taxon.getScientificName(), term)) score += 4;
                    if (contains(taxon.getCleanedScientificName(), term)) score += 4;
                    if (contains(taxon.getVernacularName(), term)) score += 3;
                    if (contains(taxon.getKingdom(), term)) score += 3;
                    if (contains(taxon.getPhylum(), term)) score += 3;
                    if (contains(taxon.getClazz(), term)) score += 3;
                    if (contains(taxon.getOrder(), term)) score += 3;
                    if (contains(taxon.getFamily(), term)) score += 3;
                    if (contains(taxon.getGenus(), term)) score += 3;
                }
            }
        }
        return score;
    }

    private boolean matchesDocument(Document document, String term, SearchLayer layer) {
        if (contains(document.getDocumentTitle(), term) || contains(document.getFullText(), term) || contains(document.getFullTextCleaned(), term)) {
            return true;
        }
        return annotationsOf(document).stream().anyMatch(annotation -> {
            if (contains(annotation.getCoveredText(), term)) {
                return true;
            }
            if (annotation instanceof Taxon taxon) {
                return contains(taxon.getValue(), term) || contains(taxon.getIdentifier(), term);
            }
            if (annotation instanceof BiofidTaxon taxon) {
                return contains(taxon.getPrimaryName(), term)
                        || contains(taxon.getScientificName(), term)
                        || contains(taxon.getCleanedScientificName(), term)
                        || contains(taxon.getVernacularName(), term)
                        || contains(taxon.getKingdom(), term)
                        || contains(taxon.getPhylum(), term)
                        || contains(taxon.getClazz(), term)
                        || contains(taxon.getOrder(), term)
                        || contains(taxon.getFamily(), term)
                        || contains(taxon.getGenus(), term);
            }
            return false;
        });
    }

    private ArrayList<PageSnippet> searchSnippets(Document document, List<String> needles) {
        ArrayList<PageSnippet> snippets = new ArrayList<>();
        String text = firstNonBlank(document.getFullText(), document.getFullTextCleaned(), document.getDocumentTitle());
        if (text.isBlank()) {
            return snippets;
        }

        int[] match = firstNeedleMatch(text, needles);
        int begin = match[0];
        int end = match[1];
        String snippetText;
        int pageOffset;
        if (begin >= 0 && end > begin) {
            snippetText = StringUtils.getHtmlText(StringUtils.buildContextSnippet(text, begin, end, 160));
            pageOffset = begin;
        } else {
            snippetText = StringUtils.getHtmlText(text.substring(0, Math.min(text.length(), 500)));
            pageOffset = 0;
        }

        PageSnippet snippet = new PageSnippet();
        snippet.setPageId(pageIdForOffset(document, pageOffset));
        snippet.setSnippet(snippetText);
        snippets.add(snippet);
        return snippets;
    }

    private int[] firstNeedleMatch(String text, List<String> needles) {
        String normalizedText = normalize(text);
        int bestBegin = -1;
        int bestEnd = -1;
        for (String needle : safe(needles)) {
            String normalizedNeedle = normalize(needle);
            if (normalizedNeedle.isBlank()) {
                continue;
            }
            int begin = normalizedText.indexOf(normalizedNeedle);
            if (begin >= 0 && (bestBegin < 0 || begin < bestBegin)) {
                bestBegin = begin;
                bestEnd = Math.min(text.length(), begin + normalizedNeedle.length());
            }
        }
        return new int[]{bestBegin, bestEnd};
    }

    private int pageIdForOffset(Document document, int offset) {
        return pages(document).stream()
                .filter(page -> offset >= page.getBegin() && offset <= page.getEnd())
                .findFirst()
                .map(page -> (int) page.getId())
                .orElse(0);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<AnnotationSearchResult> annotationResults(List<Document> documents, Class<? extends UIMAAnnotation> clazz, String info) {
        return documents.stream()
                .flatMap(document -> annotationsOf(document).stream())
                .filter(clazz::isInstance)
                .map(annotation -> annotationSearchResult(annotation, info, 1))
                .toList();
    }

    private AnnotationSearchResult annotationSearchResult(UIMAAnnotation annotation, String info, int occurrences) {
        long documentId = annotation.getDocumentId() == null ? 0 : annotation.getDocumentId();
        return new AnnotationSearchResult(
                annotation.getId(),
                annotation.getCoveredText(),
                occurrences,
                info,
                (int) documentId,
                null,
                annotation.getBegin(),
                annotation.getEnd(),
                annotation.getPageId());
    }

    private void indexDocument(Document document) {
        pagesById.values().removeIf(page -> Objects.equals(page.getDocumentId(), document.getId()));
        annotationsById.entrySet().removeIf(entry -> Objects.equals(entry.getValue().getDocumentId(), document.getId()));
        documentsById.put(document.getId(), document);
        documentsByCorpusAndDocumentId.put(corpusDocumentKey(document.getCorpusId(), document.getDocumentId()), document);
        for (Page page : pages(document)) {
            assignId(page);
            page.setDocument(document);
            page.setDocumentId(document.getId());
            pagesById.put(page.getId(), page);
            annotationsById.put(page.getId(), page);
        }
        for (UIMAAnnotation annotation : annotationsOf(document)) {
            assignId(annotation);
            annotation.setDocumentId(document.getId());
            annotationsById.put(annotation.getId(), annotation);
        }
        markLexiconDirty();
    }

    private List<UIMAAnnotation> annotationsOf(Document document) {
        if (document == null) {
            return List.of();
        }
        List<UIMAAnnotation> annotations = new ArrayList<>();
        annotations.addAll(pages(document));
        annotations.addAll(safe(document.getSentences()));
        annotations.addAll(safe(document.getNamedEntities()));
        annotations.addAll(safe(document.getGeoNames()));
        annotations.addAll(safe(document.getSentiments()));
        annotations.addAll(safe(document.getEmotions()));
        annotations.addAll(safe(document.getLemmas()));
        annotations.addAll(safe(document.getTimes()));
        annotations.addAll(safe(document.getGazetteerTaxons()));
        annotations.addAll(safe(document.getGnFinderTaxons()));
        annotations.addAll(safe(document.getBiofidTaxons()));
        annotations.addAll(safe(document.getWikipediaLinks()));
        annotations.addAll(safe(document.getCompleteNegations()));
        annotations.addAll(safe(document.getCues()));
        annotations.addAll(safe(document.getEvents()));
        annotations.addAll(safe(document.getFocuses()));
        annotations.addAll(safe(document.getScopes()));
        annotations.addAll(safe(document.getXscopes()));
        annotations.addAll(safe(document.getUnifiedTopics()));
        annotations.addAll(safe(document.getImages()));
        return annotations;
    }

    private List<Page> pages(Document document) {
        try {
            return document == null || document.getPages() == null ? List.of() : document.getPages();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void ensureLexicon() {
        if (!lexiconDirty) {
            return;
        }
        synchronized (lexiconLock) {
            if (lexiconDirty) {
                rebuildLexicon();
                lexiconDirty = false;
            }
        }
    }

    private void rebuildLexicon() {
        Map<LexiconEntryId, Integer> counts = new HashMap<>();
        for (UIMAAnnotation annotation : annotationsById.values()) {
            String covered = annotation.getCoveredText();
            if (covered == null || covered.isBlank()) {
                continue;
            }
            String type = annotation.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            LexiconEntryId id = new LexiconEntryId(covered, type);
            counts.merge(id, 1, Integer::sum);
        }
        lexiconById.clear();
        counts.forEach((id, count) -> lexiconById.put(id, new LexiconEntry(id, count)));
    }

    private void markLexiconDirty() {
        lexiconDirty = true;
    }

    private Linkable getLinkableById(long id, Class<? extends Linkable> clazz) {
        if (clazz == null) {
            return null;
        }
        if (Document.class.isAssignableFrom(clazz)) {
            return documentsById.get(id);
        }
        if (Page.class.isAssignableFrom(clazz)) {
            return pagesById.get(id);
        }
        UIMAAnnotation annotation = annotationsById.get(id);
        return clazz.isInstance(annotation) ? annotation : null;
    }

    @SuppressWarnings("unchecked")
    private <T extends UIMAAnnotation> T castAnnotation(long id, Class<T> clazz) {
        UIMAAnnotation annotation = annotationsById.get(id);
        return clazz.isInstance(annotation) ? (T) annotation : null;
    }

    private Stream<Document> documentsInCorpus(long corpusId) {
        return documentsById.values().stream()
                .filter(document -> corpusId <= 0 || document.getCorpusId() == corpusId);
    }

    private List<Link> allLinks() {
        List<Link> links = new ArrayList<>();
        links.addAll(documentLinks);
        links.addAll(documentToAnnotationLinks);
        links.addAll(annotationToDocumentLinks);
        links.addAll(annotationLinks);
        return links;
    }

    private <T extends Link> void addLinks(List<T> target, List<T> links) {
        for (T link : safe(links)) {
            assignId(link);
            target.removeIf(existing -> existing.getId() == link.getId());
            target.add(link);
        }
    }

    private PointDto point(GeoName geoName) {
        PointDto dto = new PointDto();
        dto.setId(geoName.getId());
        dto.setAnnotationId(geoName.getId());
        dto.setAnnotationType(GeoName.class.getName());
        dto.setLocationCoveredText(geoName.getCoveredText());
        dto.setLocation(geoName.getName());
        dto.setLabel(geoName.getName() == null ? geoName.getCoveredText() : geoName.getName());
        dto.setLatitude(geoName.getLatitude());
        dto.setLongitude(geoName.getLongitude());
        return dto;
    }

    private boolean matchesTime(Time time, String unit, String value, Long fromYear, Long toYear) {
        if ("range".equals(unit)) {
            Integer year = timeYear(time);
            return year != null && fromYear != null && toYear != null && year >= fromYear && year <= toYear;
        }
        return switch (unit) {
            case "year" -> String.valueOf(timeYear(time)).equals(value);
            case "month" -> normalize(time.getMonth()).equals(value);
            case "day" -> normalize(time.getDay()).equals(value);
            case "season" -> normalize(time.getSeason()).equals(value);
            default -> false;
        };
    }

    private Integer timeYear(Time time) {
        try {
            return time.getYear();
        } catch (Exception ignored) {
            return null;
        }
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                   + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                     * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @SafeVarargs
    private List<String> tokens(List<String>... parts) {
        List<String> result = new ArrayList<>();
        for (List<String> part : parts) {
            result.addAll(safe(part));
        }
        return result;
    }

    private String label(UIMAAnnotation annotation) {
        if (annotation instanceof NamedEntity namedEntity && namedEntity.getType() != null) {
            return namedEntity.getType();
        }
        if (annotation instanceof Time) {
            return "time";
        }
        if (annotation instanceof BiofidTaxon || annotation instanceof GazetteerTaxon || annotation instanceof GnFinderTaxon) {
            return "taxon";
        }
        return annotation.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private boolean contains(String value, String normalizedNeedle) {
        return value != null && normalize(value).contains(normalizedNeedle);
    }

    private int taxonMatchRank(String normalizedNeedle, String... values) {
        boolean allowContains = normalizedNeedle.length() >= 7;
        int bestRank = Integer.MAX_VALUE;
        for (String value : values) {
            String normalizedValue = normalize(value);
            if (normalizedValue.isBlank()) {
                continue;
            }
            if (normalizedValue.equals(normalizedNeedle)) {
                bestRank = Math.min(bestRank, 0);
            } else if (containsNormalizedWord(normalizedValue, normalizedNeedle)) {
                bestRank = Math.min(bestRank, 1);
            } else if (allowContains && normalizedValue.contains(normalizedNeedle)) {
                bestRank = Math.min(bestRank, 3);
            }
        }
        return bestRank;
    }

    private boolean containsNormalizedWord(String normalizedValue, String normalizedNeedle) {
        for (String part : normalizedValue.split("[^\\p{Alnum}]+")) {
            if (part.equals(normalizedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private List<String> taxonIdentifiers(Taxon taxon) {
        List<String> identifiers = new ArrayList<>();
        if (taxon == null) {
            return identifiers;
        }
        identifiers.addAll(splitTaxonIdentifierValues(taxon.getIdentifier()));
        if (taxon.getRecordId() > 0) {
            identifiers.add("https://www.biofid.de/bio-ontologies/gbif/" + taxon.getRecordId());
        }
        if (taxon instanceof GazetteerTaxon gazetteerTaxon) {
            identifiers.addAll(splitTaxonIdentifierValues(gazetteerTaxon.getPrimaryIdentifier()));
        }
        return identifiers.stream()
                .map(this::normalizeBiofidIdentifier)
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .distinct()
                .toList();
    }

    private void addRankedTaxonNames(Map<String, Integer> rankedNames, int rank, String... values) {
        if (rank == Integer.MAX_VALUE) {
            return;
        }
        for (String value : values) {
            String normalized = normalizeTaxonNameCandidate(value);
            if (normalized.isBlank()) {
                continue;
            }
            rankedNames.merge(normalized, rank, Math::min);
        }
    }

    private String normalizeTaxonNameCandidate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return StringUtils.removeSpecialCharactersAtEdges(value)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> resolveTaxonNamesThroughSparql(String normalizedNeedle, Map<String, Integer> rankedNames, int resultLimit) {
        if (rankedNames.isEmpty()) {
            return List.of();
        }
        JenaSparqlService sparqlService = new JenaSparqlService();
        if (!SystemStatus.JenaSparqlStatus.isAlive()) {
            return List.of();
        }
        List<String> ranks = List.of("genus", "species", "family", "order", "class", "phylum", "kingdom");
        Map<String, Integer> resolved = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> sortedCandidates = rankedNames.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<Map.Entry<String, Integer>> candidates = sortedCandidates.stream()
                .filter(entry -> normalize(entry.getKey()).equals(normalizedNeedle))
                .toList();
        if (candidates.isEmpty()) {
            candidates = sortedCandidates.stream()
                    .filter(entry -> containsNormalizedWord(normalize(entry.getKey()), normalizedNeedle))
                    .toList();
        }
        if (candidates.isEmpty()) {
            candidates = sortedCandidates;
        }
        for (var entry : candidates.stream().limit(5).toList()) {
            for (String rank : ranks) {
                try {
                    for (String id : sparqlService.getIdsOfTaxonRank(rank, entry.getKey())) {
                        addRankedTaxonIdentifier(resolved, id, entry.getValue());
                    }
                } catch (IOException ignored) {
                    return resolved.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                            .limit(resultLimit)
                            .map(Map.Entry::getKey)
                            .toList();
                }
            }
            if (resolved.size() >= resultLimit) {
                break;
            }
            if (!resolved.isEmpty() && entry.getValue() == 0) {
                break;
            }
        }
        return resolved.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .limit(resultLimit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> splitTaxonIdentifierValues(String identifiers) {
        if (identifiers == null || identifiers.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String pipePart : identifiers.split("\\|")) {
            for (String part : pipePart.split("\\s+")) {
                if (part != null && !part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private String normalizeBiofidIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String cleaned = identifier.trim();
        if (cleaned.contains("gbif.org")) {
            return StringUtils.gbifToBIOfidUrl(cleaned);
        }
        if (cleaned.matches("\\d+")) {
            return "https://www.biofid.de/bio-ontologies/gbif/" + cleaned;
        }
        return cleaned;
    }

    private void addRankedTaxonIdentifier(Map<String, Integer> rankedIdentifiers, String identifier, int rank) {
        String normalizedIdentifier = normalizeBiofidIdentifier(identifier);
        if (normalizedIdentifier == null || normalizedIdentifier.isBlank() || rank == Integer.MAX_VALUE) {
            return;
        }
        rankedIdentifiers.merge(normalizedIdentifier, rank, Math::min);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String corpusDocumentKey(long corpusId, String documentId) {
        return corpusId + ":" + (documentId == null ? "" : documentId);
    }

    private Path configuredStorePath() {
        String raw = System.getProperty(STORE_PROPERTY_NAME);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(STORE_ENV_NAME);
        }
        return raw == null || raw.isBlank() ? null : Path.of(raw.trim()).toAbsolutePath();
    }

    private int configuredPositiveInt(String propertyName, String envName, int defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envName);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private void loadStore() {
        if (storePath == null || !Files.exists(storePath)) {
            return;
        }
        synchronized (persistenceLock) {
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(storePath))) {
                applyStoreSnapshot(readStoreSnapshot(input, storePath));
            } catch (IOException | ClassNotFoundException ex) {
                Path backup = backupStorePath();
                if (Files.exists(backup)) {
                    try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(backup))) {
                        applyStoreSnapshot(readStoreSnapshot(input, backup));
                        return;
                    } catch (IOException | ClassNotFoundException backupEx) {
                        ex.addSuppressed(backupEx);
                    }
                }
                throw new IllegalStateException("Failed to load DUA store from " + storePath, ex);
            }
        }
    }

    private DuaStoreSnapshot readStoreSnapshot(ObjectInputStream input, Path source) throws IOException, ClassNotFoundException {
        Object value = input.readObject();
        if (!(value instanceof DuaStoreSnapshot snapshot)) {
            throw new IllegalStateException("Unexpected DUA store payload in " + source);
        }
        return snapshot;
    }

    private void applyStoreSnapshot(DuaStoreSnapshot snapshot) {
        corporaById.clear();
        documentsById.clear();
        documentLinks.clear();
        documentToAnnotationLinks.clear();
        annotationToDocumentLinks.clear();
        annotationLinks.clear();
        importLogs.clear();
        uceLogs.clear();
        importsById.clear();

        corporaById.putAll(snapshot.corporaById());
        documentsById.putAll(snapshot.documentsById());
        documentLinks.addAll(snapshot.documentLinks());
        documentToAnnotationLinks.addAll(snapshot.documentToAnnotationLinks());
        annotationToDocumentLinks.addAll(snapshot.annotationToDocumentLinks());
        annotationLinks.addAll(snapshot.annotationLinks());
        importLogs.addAll(snapshot.importLogs());
        uceLogs.addAll(snapshot.uceLogs());
        importsById.putAll(snapshot.importsById());

        rebuildDerivedIndexes();
        ids.set(Math.max(snapshot.nextId(), maxStoredId() + 1));
    }

    private void persistStore() {
        if (storePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            taxonIdentifierLookupCache.clear();
            pendingStoreMutations++;
            if (storeFlushEveryMutations <= 1 || pendingStoreMutations >= storeFlushEveryMutations) {
                persistStoreLocked();
            }
        }
    }

    private void flushStoreQuietly() {
        try {
            flushPendingStore();
        } catch (RuntimeException ignored) {
            // Shutdown hooks must not mask the original process exit reason.
        }
    }

    public void flushPendingStore() {
        if (storePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            if (pendingStoreMutations > 0) {
                persistStoreLocked();
            }
        }
    }

    private void persistStoreLocked() {
        Path tmp = null;
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = uniqueStoreTempPath();
            DuaStoreSnapshot snapshot = new DuaStoreSnapshot(
                    ids.get(),
                    new LinkedHashMap<>(corporaById),
                    new LinkedHashMap<>(documentsById),
                    copyList(documentLinks),
                    copyList(documentToAnnotationLinks),
                    copyList(annotationToDocumentLinks),
                    copyList(annotationLinks),
                    copyList(importLogs),
                    copyList(uceLogs),
                    new LinkedHashMap<>(importsById)
            );
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                output.writeObject(snapshot);
            }
            validateStoreSnapshot(tmp);
            if (Files.exists(storePath) && isReadableStoreSnapshot(storePath)) {
                Files.copy(storePath, backupStorePath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moveStoreAtomically(tmp, storePath);
            pendingStoreMutations = 0;
        } catch (IOException | ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to persist DUA store to " + storePath, ex);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // A failed cleanup must not hide the persistence failure above.
                }
            }
        }
    }

    private Path uniqueStoreTempPath() {
        String fileName = storePath.getFileName().toString();
        String suffix = "." + ProcessHandle.current().pid() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp";
        return storePath.resolveSibling(fileName + suffix);
    }

    private Path backupStorePath() {
        return storePath.resolveSibling(storePath.getFileName() + ".bak");
    }

    private boolean isReadableStoreSnapshot(Path source) {
        try {
            validateStoreSnapshot(source);
            return true;
        } catch (IOException | ClassNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    private void validateStoreSnapshot(Path source) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(source))) {
            readStoreSnapshot(input, source);
        }
    }

    private void moveStoreAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rebuildDerivedIndexes() {
        documentsByCorpusAndDocumentId.clear();
        pagesById.clear();
        annotationsById.clear();
        lexiconById.clear();
        for (Corpus corpus : corporaById.values()) {
            for (Document document : safe(corpus.getDocuments())) {
                if (document.getCorpusId() == 0) {
                    document.setCorpusId(corpus.getId());
                }
                documentsById.putIfAbsent(document.getId(), document);
            }
        }
        for (Document document : documentsById.values()) {
            indexDocument(document);
        }
        ensureLexicon();
    }

    private long maxStoredId() {
        long max = 0;
        for (Corpus corpus : corporaById.values()) {
            max = Math.max(max, modelId(corpus));
            max = Math.max(max, modelId(corpus.getCorpusTsnePlot()));
            for (UCEMetadataFilter filter : safe(corpus.getUceMetadataFilters())) {
                max = Math.max(max, modelId(filter));
            }
        }
        for (Document document : documentsById.values()) {
            max = Math.max(max, modelId(document));
            max = Math.max(max, modelId(document.getDocumentTopThreeTopics()));
            for (Page page : pages(document)) {
                max = Math.max(max, modelId(page));
            }
            for (UIMAAnnotation annotation : annotationsOf(document)) {
                max = Math.max(max, modelId(annotation));
            }
        }
        max = Math.max(max, maxModelId(documentLinks));
        max = Math.max(max, maxModelId(documentToAnnotationLinks));
        max = Math.max(max, maxModelId(annotationToDocumentLinks));
        max = Math.max(max, maxModelId(annotationLinks));
        max = Math.max(max, maxModelId(importLogs));
        max = Math.max(max, maxModelId(uceLogs));
        max = Math.max(max, maxModelId(importsById.values().stream().toList()));
        return max;
    }

    private long maxModelId(List<? extends ModelBase> values) {
        return safe(values).stream().mapToLong(this::modelId).max().orElse(0);
    }

    private long modelId(ModelBase model) {
        return model == null ? 0 : model.getId();
    }

    private void assignId(ModelBase model) {
        if (model != null && model.getId() == 0) {
            model.setId(ids.getAndIncrement());
        }
    }

    private <T> List<T> page(List<T> values, int skip, int take) {
        return values.stream()
                .skip(Math.max(0, skip))
                .limit(Math.max(0, take))
                .toList();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <T> List<T> copyList(List<T> values) {
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    private <T> List<T> synchronizedList() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    private record DuaStoreSnapshot(
            long nextId,
            Map<Long, Corpus> corporaById,
            Map<Long, Document> documentsById,
            List<DocumentLink> documentLinks,
            List<DocumentToAnnotationLink> documentToAnnotationLinks,
            List<AnnotationToDocumentLink> annotationToDocumentLinks,
            List<AnnotationLink> annotationLinks,
            List<ImportLog> importLogs,
            List<UCELog> uceLogs,
            Map<String, UCEImport> importsById
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
