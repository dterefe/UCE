package org.texttechnologylab.uce.common.services;

import java.util.List;
import java.util.Map;

/**
 * Domain graph DTO namespace retained for existing controller/search payloads.
 * Graph storage is implemented by {@link DuaDomainGraphService}.
 */
public final class DomainGraphModels {
    private DomainGraphModels() {
    }

    public record DomainNode(
            String uid,
            String uimaType,
            long corpusId,
            long documentId,
            String name,
            String uri,
            String metadata,
            String featuresJson
    ) {
    }

    public record AssociationEdge(
            String uid,
            String uimaType,
            long corpusId,
            long documentId,
            String leftUid,
            String rightUid,
            String name,
            String metadata,
            String featuresJson
    ) {
    }

    public record DomainTypeSummary(
            String uimaType,
            String label,
            long count,
            Map<String, Long> associationCounts
    ) {
    }

    public record DomainNodeView(
            String ageId,
            String uid,
            String uimaType,
            String label,
            long corpusId,
            long documentId,
            String name,
            String uri,
            String metadata,
            String features,
            long outgoingCount,
            long incomingCount
    ) {
    }

    public record DomainNodePage(
            List<DomainNodeView> items,
            long total,
            int skip,
            int take
    ) {
    }

    public record AssociationEdgeView(
            String ageId,
            String uid,
            String uimaType,
            String label,
            String name,
            String metadata,
            String features,
            String direction
    ) {
    }

    public record AssociationTraversal(
            AssociationEdgeView edge,
            DomainNodeView target
    ) {
    }

    public record AssociationPage(
            List<AssociationTraversal> items,
            long total,
            int skip,
            int take
    ) {
    }

    public record ScopePreview(
            long nodeCount,
            long documentCount,
            long pageCount,
            Map<String, Long> typeCounts,
            List<String> availableModes
    ) {
    }

    public record EgoEdge(
            AssociationEdgeView edge,
            String targetUid,
            String direction
    ) {
    }

    public record EgoGraph(
            List<DomainNodeView> nodes,
            List<EgoEdge> edges
    ) {
    }
}
