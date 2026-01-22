package org.texttechnologylab.uce.web.render.feedback;

import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.models.corpus.Document;
import org.texttechnologylab.uce.common.models.corpus.Image;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight mapper that derives a {@link FeedbackDocument} projection from the
 * existing {@link Document} entity. The mapper only relies on metadata already
 * shipped with the document so it can operate without parsing the raw XMI again.
 */
public class FeedbackDocumentMapper {

    public FeedbackDocument map(Document document, String principal) {
        var builder = FeedbackDocument.builder()
                .documentId(defaultString(document.getDocumentId(), String.valueOf(document.getId())))
                .documentTitle(defaultString(document.getDocumentTitle(), ""))
                .userHash(metadataValue(document, "user_hash").orElse("unknown"))
                .assessmentPhase(metadataValue(document, "assessment_phase_name").orElse(""))
                .assessmentName(metadataValue(document, "assessment_name").orElse(""));

        var pagesMetric = metricFromMetadata(
                document,
                "pages_count",
                "pages_all_min",
                "pages_all_max",
                "pages_all_mean",
                "pages_all_std",
                "pages_percentage_diff");

        var uniquePagesMetric = metricFromMetadata(
                document,
                "pages_count_unique",
                "pages_all_min_unique",
                "pages_all_max_unique",
                "pages_all_mean_unique",
                "pages_all_std_unique",
                "pages_percentage_diff_unique");

        var processingMetric = metricFromMetadata(
                document,
                "time_count",
                "time_all_min",
                "time_all_max",
                "time_all_mean",
                "time_all_std",
                "time_percentage_diff");

        var participantCount = extractParticipantCount(document);
        builder.overview(new FeedbackDocument.Overview(participantCount, pagesMetric, uniquePagesMetric));
        builder.engagement(new FeedbackDocument.Engagement(
                pagesMetric,
                uniquePagesMetric,
                buildHighlights(pagesMetric, uniquePagesMetric)));
        builder.processingTime(new FeedbackDocument.ProcessingTime(processingMetric, processingMetric.diffPercent()));
        builder.topUrls(extractTopUrls(document));
        builder.sections(buildSections(document));
        builder.permissions(mapPermissions(document));
        builder.effectivePermission(resolveEffectivePermission(document, principal).orElse(null));

        return builder.build();
    }

    private FeedbackDocument.Metric metricFromMetadata(Document document,
                                                       String valueKey,
                                                       String minKey,
                                                       String maxKey,
                                                       String meanKey,
                                                       String stdKey,
                                                       String diffKey) {
        return new FeedbackDocument.Metric(
                metadataNumber(document, valueKey).orElse(0d),
                metadataNumber(document, minKey).orElse(0d),
                metadataNumber(document, maxKey).orElse(0d),
                metadataNumber(document, meanKey).orElse(0d),
                metadataNumber(document, stdKey).orElse(0d),
                metadataNumber(document, diffKey).orElse(0d));
    }

    private Optional<String> metadataValue(Document document, String key) {
        return document.getUceMetadata()
                .stream()
                .filter(meta -> key.equalsIgnoreCase(meta.getKey()))
                .map(UCEMetadata::getValue)
                .findFirst();
    }

    private Optional<Double> metadataNumber(Document document, String key) {
        return metadataValue(document, key).map(value -> {
            try {
                return Double.parseDouble(value.replace(",", "."));
            } catch (NumberFormatException ex) {
                return 0d;
            }
        });
    }

    private String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private List<FeedbackDocument.UrlUsage> extractTopUrls(Document document) {
        var urls = new ArrayList<FeedbackDocument.UrlUsage>();
        var text = Optional.ofNullable(document.getFullText()).orElse("");
        Pattern pattern = Pattern.compile("-\\s*(\\d+):\\s*(https?://\\S+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            var rank = Integer.parseInt(matcher.group(1));
            var url = matcher.group(2);
            urls.add(new FeedbackDocument.UrlUsage(url, rank));
        }

        if (!urls.isEmpty()) {
            return urls;
        }

        return document.getUceMetadata()
                .stream()
                .filter(meta -> meta.getKey() != null && meta.getKey().startsWith("url_"))
                .map(meta -> new FeedbackDocument.UrlUsage(meta.getValue(), 1))
                .toList();
    }

    private List<FeedbackDocument.CardSection> buildSections(Document document) {
        var sections = new ArrayList<FeedbackDocument.CardSection>();

        var imageCards = buildImageCards(document);
        if (!imageCards.isEmpty()) {
            sections.add(new FeedbackDocument.CardSection("Diagramme", imageCards));
        }

        return sections;
    }

    private List<FeedbackDocument.Card> buildImageCards(Document document) {
        var cards = new ArrayList<FeedbackDocument.Card>();
        var images = Optional.ofNullable(document.getImages()).orElse(List.of());
        int index = 1;
        for (Image image : images) {
            if (image.getSrc() == null || image.getMimeType() == null) {
                continue;
            }
            String dataUri = image.getHTMLImgSrc();
            var spec = new FeedbackDocument.ChartSpec("image", List.of(), List.of(dataUri));
            cards.add(new FeedbackDocument.ChartCard(" ", "", spec));
            index++;
        }
        return cards;
    }

    private List<String> buildHighlights(FeedbackDocument.Metric pagesMetric,
                                         FeedbackDocument.Metric uniquePagesMetric) {
        var highlights = new ArrayList<String>();
        highlights.add(String.format(Locale.GERMAN, "Gesamtseiten: %.0f (Δ %.2f%%)", pagesMetric.userValue(), pagesMetric.diffPercent()));
        highlights.add(String.format(Locale.GERMAN, "Einzigartige Seiten: %.0f (Δ %.2f%%)", uniquePagesMetric.userValue(), uniquePagesMetric.diffPercent()));
        return highlights;
    }

    private List<FeedbackDocument.Card> buildSummaryCards(Document document) {
        var cards = new ArrayList<FeedbackDocument.Card>();
        var pagesSummary = String.format(Locale.GERMAN,
                "<p>Sie haben insgesamt %.0f Seiten besucht (Min %.1f / Max %.1f / Mittelwert %.2f).</p>",
                metadataNumber(document, "pages_count").orElse(0d),
                metadataNumber(document, "pages_all_min").orElse(0d),
                metadataNumber(document, "pages_all_max").orElse(0d),
                metadataNumber(document, "pages_all_mean").orElse(0d));

        cards.add(new FeedbackDocument.TextCard("Seitenaufrufe", "", pagesSummary));

        var timeSummary = String.format(Locale.GERMAN,
                "<p>Bearbeitungszeit: %.0f Minuten (Min %.1f / Max %.1f / Mittelwert %.2f).</p>",
                metadataNumber(document, "time_count").orElse(0d),
                metadataNumber(document, "time_all_min").orElse(0d),
                metadataNumber(document, "time_all_max").orElse(0d),
                metadataNumber(document, "time_all_mean").orElse(0d));

        cards.add(new FeedbackDocument.TextCard("Bearbeitungszeit", "", timeSummary));
        return cards;
    }

    private List<FeedbackDocument.Permission> mapPermissions(Document document) {
        var perms = Optional.ofNullable(document.getPermissions()).orElse(java.util.Set.of());
        return perms.stream()
                .filter(permission -> permission.getType() != DocumentPermission.DOCUMENT_PERMISSION_TYPE.EFFECTIVE)
                .map(permission -> new FeedbackDocument.Permission(
                        mapPermissionType(permission.getType()),
                        mapPermissionLevel(permission.getLevel()),
                        permission.getName()))
                .toList();
    }

    private Optional<FeedbackDocument.Permission> resolveEffectivePermission(Document document, String principal) {
        var perms = Optional.ofNullable(document.getPermissions()).orElse(java.util.Set.of());

        if (perms.isEmpty()) {
            var principalName = (principal != null && !principal.isBlank())
                    ? principal
                    : DocumentPermission.PUBLIC_USERNAME;
            return Optional.of(new FeedbackDocument.Permission(
                    FeedbackDocument.PermissionType.USER,
                    FeedbackDocument.PermissionLevel.READ,
                    principalName));
        }

        if (principal == null || principal.isBlank()) {
            return Optional.empty();
        }

        return perms.stream()
                .filter(permission -> permission.getType() == DocumentPermission.DOCUMENT_PERMISSION_TYPE.EFFECTIVE)
                .filter(permission -> principal.equals(permission.getName()))
                .findFirst()
                .map(permission -> new FeedbackDocument.Permission(
                        FeedbackDocument.PermissionType.USER,
                        mapPermissionLevel(permission.getLevel()),
                        permission.getName()));
    }

    private FeedbackDocument.PermissionType mapPermissionType(DocumentPermission.DOCUMENT_PERMISSION_TYPE type) {
        return switch (type) {
            case USER -> FeedbackDocument.PermissionType.USER;
            case GROUP -> FeedbackDocument.PermissionType.GROUP;
            case EFFECTIVE -> FeedbackDocument.PermissionType.USER; // fallback, should be handled elsewhere
        };
    }

    private FeedbackDocument.PermissionLevel mapPermissionLevel(DocumentPermission.DOCUMENT_PERMISSION_LEVEL level) {
        return switch (level) {
            case NONE -> FeedbackDocument.PermissionLevel.NONE;
            case READ -> FeedbackDocument.PermissionLevel.READ;
            case WRITE -> FeedbackDocument.PermissionLevel.WRITE;
            case OWNER -> FeedbackDocument.PermissionLevel.OWNER;
            case ADMIN -> FeedbackDocument.PermissionLevel.ADMIN;
        };
    }

    private int extractParticipantCount(Document document) {
        var text = Optional.ofNullable(document.getFullText()).orElse("");
        Pattern pattern = Pattern.compile("Anzahl\\s+teilnehmender\\s+Probanden:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
}
