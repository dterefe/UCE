package org.texttechnologylab.uce.web.render.feedback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical, presentation-friendly view of the feedback CAS/XMI document.
 * This keeps renderers decoupled from UIMA-specific APIs.
 */
public final class FeedbackDocument {
    private final String documentId;
    private final String documentTitle;
    private final String userHash;
    private final String assessmentPhase;
    private final String assessmentName;
    private final Overview overview;
    private final Engagement engagement;
    private final List<UrlUsage> topUrls;
    private final ProcessingTime processingTime;
    private final List<CardSection> sections;
    private final List<Permission> permissions;
    private final Permission effectivePermission;

    private FeedbackDocument(Builder builder) {
        this.documentId = builder.documentId;
        this.documentTitle = builder.documentTitle;
        this.userHash = builder.userHash;
        this.assessmentPhase = builder.assessmentPhase;
        this.assessmentName = builder.assessmentName;
        this.overview = builder.overview;
        this.engagement = builder.engagement;
        this.topUrls = Collections.unmodifiableList(new ArrayList<>(builder.topUrls));
        this.processingTime = builder.processingTime;
        this.sections = Collections.unmodifiableList(new ArrayList<>(builder.sections));
        this.permissions = Collections.unmodifiableList(new ArrayList<>(builder.permissions));
        this.effectivePermission = builder.effectivePermission;
    }

    public String documentId() {
        return documentId;
    }

    public String documentTitle() {
        return documentTitle;
    }

    public String userHash() {
        return userHash;
    }

    public String assessmentPhase() {
        return assessmentPhase;
    }

    public String assessmentName() {
        return assessmentName;
    }

    public Overview overview() {
        return overview;
    }

    public Engagement engagement() {
        return engagement;
    }

    public List<UrlUsage> topUrls() {
        return topUrls;
    }

    public ProcessingTime processingTime() {
        return processingTime;
    }

    public List<CardSection> sections() {
        return sections;
    }

    public List<Permission> permissions() {
        return permissions;
    }

    public Permission effectivePermission() {
        return effectivePermission;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String documentId;
        private String documentTitle;
        private String userHash;
        private String assessmentPhase;
        private String assessmentName;
        private Overview overview = Overview.empty();
        private Engagement engagement = Engagement.empty();
        private List<UrlUsage> topUrls = List.of();
        private ProcessingTime processingTime = ProcessingTime.empty();
        private List<CardSection> sections = List.of();
        private List<Permission> permissions = List.of();
        private Permission effectivePermission;

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder documentTitle(String documentTitle) {
            this.documentTitle = documentTitle;
            return this;
        }

        public Builder userHash(String userHash) {
            this.userHash = userHash;
            return this;
        }

        public Builder assessmentPhase(String assessmentPhase) {
            this.assessmentPhase = assessmentPhase;
            return this;
        }

        public Builder assessmentName(String assessmentName) {
            this.assessmentName = assessmentName;
            return this;
        }

        public Builder overview(Overview overview) {
            this.overview = overview;
            return this;
        }

        public Builder engagement(Engagement engagement) {
            this.engagement = engagement;
            return this;
        }

        public Builder topUrls(List<UrlUsage> topUrls) {
            this.topUrls = topUrls;
            return this;
        }

        public Builder processingTime(ProcessingTime processingTime) {
            this.processingTime = processingTime;
            return this;
        }

        public Builder sections(List<CardSection> sections) {
            this.sections = sections;
            return this;
        }

        public Builder permissions(List<Permission> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder effectivePermission(Permission effectivePermission) {
            this.effectivePermission = effectivePermission;
            return this;
        }

        public FeedbackDocument build() {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(documentTitle, "documentTitle");
            Objects.requireNonNull(userHash, "userHash");
            return new FeedbackDocument(this);
        }
    }

    // ----- Value objects -----

    public record Permission(PermissionType type, PermissionLevel level, String principal) {}

    public enum PermissionType { USER, GROUP }
    public enum PermissionLevel { NONE, READ, WRITE, OWNER, ADMIN }

    public record Overview(int participantCount, Metric pagesMetric, Metric uniquePagesMetric) {
        public static Overview empty() {
            return new Overview(0, Metric.empty(), Metric.empty());
        }
    }

    public record Engagement(Metric pagesTotal, Metric pagesUnique, List<String> highlights) {
        public static Engagement empty() {
            return new Engagement(Metric.empty(), Metric.empty(), List.of());
        }
    }

    public record Metric(double userValue, double min, double max, double mean, double stdDev, double diffPercent) {
        public static Metric empty() {
            return new Metric(0, 0, 0, 0, 0, 0);
        }
    }

    public record UrlUsage(String url, int hits) {}

    public record ProcessingTime(Metric duration, double diffPercent) {
        public static ProcessingTime empty() {
            return new ProcessingTime(Metric.empty(), 0);
        }
    }

    public record CardSection(String name, List<Card> cards) {
        public CardSection {
            cards = cards != null ? List.copyOf(cards) : List.of();
        }
    }

    public sealed interface Card permits TextCard, ChartCard, TableCard {}

    public record TextCard(String title, String subtitle, String body) implements Card {}

    public record ChartCard(String title, String subtitle, ChartSpec spec) implements Card {}

    public record ChartSpec(String type, List<Double> series, List<String> labels) {}

    public record TableCard(String title, String subtitle, List<TableRow> rows) implements Card {
        public TableCard {
            rows = rows != null ? List.copyOf(rows) : List.of();
        }
    }

    public record TableRow(List<String> cells) {
        public TableRow {
            cells = cells != null ? List.copyOf(cells) : List.of();
        }
    }
}
