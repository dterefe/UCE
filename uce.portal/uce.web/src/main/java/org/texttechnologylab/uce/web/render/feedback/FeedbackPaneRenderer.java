package org.texttechnologylab.uce.web.render.feedback;

import org.texttechnologylab.uce.web.render.PaneRenderer;
import org.texttechnologylab.uce.web.render.RenderContext;
import org.texttechnologylab.uce.web.render.RenderException;
import org.texttechnologylab.uce.web.render.RenderResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renderer that transforms {@link FeedbackDocument} instances into the
 * presentation model expected by the dedicated feedback template.
 */
public final class FeedbackPaneRenderer implements PaneRenderer {

    public static final String HANDLER_KEY = "document_reader_feedback_view";
    private static final String TEMPLATE = "feedback/middlePane.ftl";

    @Override
    public RenderResult render(RenderContext context) throws RenderException {
        var feedback = context.requirePayload(FeedbackDocument.class);
        var model = new HashMap<String, Object>();

        model.put("title", feedback.documentTitle());
        model.put("subtitle",
                "%s • %s".formatted(feedback.assessmentPhase(), feedback.assessmentName()));
        model.put("metaBadges", buildMetaBadges(feedback));
        model.put("effectivePermission", feedback.effectivePermission());
        model.put("overviewCards", buildOverviewCards(feedback));
        model.put("overviewHighlights", feedback.engagement().highlights());
        model.put("topUrls", buildTopUrls(feedback));
        model.put("contentCards", buildContentCards(feedback));

        return RenderResult.middleOnly(TEMPLATE, model);
    }

    private List<Map<String, Object>> buildMetaBadges(FeedbackDocument feedback) {
        var badges = new ArrayList<Map<String, Object>>();
        badges.add(badge("Teilnehmer", feedback.overview().participantCount()));
        badges.add(badge("Dokument", feedback.documentId()));
        badges.add(badge("User-Hash", feedback.userHash()));
        return badges;
    }

    private Map<String, Object> badge(String label, Object value) {
        return Map.of(
                "label", label,
                "value", value
        );
    }

    private List<Map<String, Object>> buildOverviewCards(FeedbackDocument feedback) {
        var cards = new ArrayList<Map<String, Object>>();
        cards.add(metricCard("Seiten gesamt", feedback.engagement().pagesTotal()));
        cards.add(metricCard("Seiten einzigartig", feedback.engagement().pagesUnique()));
        cards.add(metricCard("Bearbeitungszeit", feedback.processingTime().duration()));
        return cards;
    }

    private Map<String, Object> metricCard(String title, FeedbackDocument.Metric metric) {
        var card = new LinkedHashMap<String, Object>();
        card.put("title", title);
        card.put("value", metric.userValue());
        card.put("min", metric.min());
        card.put("max", metric.max());
        card.put("mean", metric.mean());
        card.put("stdDev", metric.stdDev());
        card.put("diffPercent", metric.diffPercent());
        return card;
    }

    private List<Map<String, Object>> buildTopUrls(FeedbackDocument feedback) {
        var rows = new ArrayList<Map<String, Object>>();
        int rank = 1;
        for (var url : feedback.topUrls()) {
            rows.add(Map.of(
                    "rank", rank++,
                    "url", url.url()
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> buildContentCards(FeedbackDocument feedback) {
        var cards = new ArrayList<Map<String, Object>>();
        for (var section : feedback.sections()) {
            cards.add(Map.of(
                    "type", "section-header",
                    "title", section.name()
            ));

            for (var card : section.cards()) {
                if (card instanceof FeedbackDocument.TextCard text) {
                    cards.add(Map.of(
                            "type", "text",
                            "title", text.title(),
                            "subtitle", text.subtitle(),
                            "body", text.body()
                    ));
                } else if (card instanceof FeedbackDocument.ChartCard chart) {
                    var spec = chart.spec();
                    cards.add(Map.of(
                            "type", "chart",
                            "title", chart.title(),
                            "subtitle", chart.subtitle(),
                            "chartType", spec.type(),
                            "series", spec.series(),
                            "labels", spec.labels()
                    ));
                } else if (card instanceof FeedbackDocument.TableCard table) {
                    List<Map<String, Object>> tableRows = new ArrayList<>();
                    for (var row : table.rows()) {
                        tableRows.add(Map.of("cells", row.cells()));
                    }
                    cards.add(Map.of(
                            "type", "table",
                            "title", table.title(),
                            "subtitle", table.subtitle(),
                            "rows", tableRows
                    ));
                }
            }
        }
        return cards;
    }
}
