package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DuaDomainGraphService implements DomainGraphService {
    private final Map<String, AgeGraphService.DomainNode> nodesByUid = new ConcurrentHashMap<>();
    private final Map<String, AgeGraphService.AssociationEdge> edgesByUid = new ConcurrentHashMap<>();

    @Override
    public void ensureGraph() {
    }

    @Override
    public void upsertDomainNode(AgeGraphService.DomainNode node) {
        upsertDomainNodes(List.of(node));
    }

    @Override
    public void upsertDomainNodes(List<AgeGraphService.DomainNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (AgeGraphService.DomainNode node : nodes) {
            if (node != null && node.uid() != null) {
                nodesByUid.put(node.uid(), node);
            }
        }
    }

    @Override
    public void upsertAssociationEdge(AgeGraphService.AssociationEdge edge) {
        upsertAssociationEdges(List.of(edge));
    }

    @Override
    public void upsertAssociationEdges(List<AgeGraphService.AssociationEdge> edges) {
        if (edges == null) {
            return;
        }
        for (AgeGraphService.AssociationEdge edge : edges) {
            if (edge != null && edge.uid() != null) {
                edgesByUid.put(edge.uid(), edge);
            }
        }
    }

    @Override
    public List<AgeGraphService.DomainTypeSummary> listDomainTypes(long corpusId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> associationCounts = new HashMap<>();
        for (AgeGraphService.DomainNode node : nodesByUid.values()) {
            if (corpusId > 0 && node.corpusId() != corpusId) {
                continue;
            }
            counts.merge(node.uimaType(), 1L, Long::sum);
        }
        for (AgeGraphService.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            AgeGraphService.DomainNode left = nodesByUid.get(edge.leftUid());
            if (left == null) {
                continue;
            }
            associationCounts
                    .computeIfAbsent(left.uimaType(), ignored -> new LinkedHashMap<>())
                    .merge(edge.uimaType(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new AgeGraphService.DomainTypeSummary(
                        entry.getKey(),
                        entry.getKey(),
                        entry.getValue(),
                        associationCounts.getOrDefault(entry.getKey(), Map.of())))
                .toList();
    }

    @Override
    public AgeGraphService.DomainNodePage listDomainNodes(long corpusId,
                                                          String uimaType,
                                                          String query,
                                                          int skip,
                                                          int take) {
        List<AgeGraphService.DomainNodeView> matches = nodesByUid.values().stream()
                .filter(node -> corpusId <= 0 || node.corpusId() == corpusId)
                .filter(node -> uimaType == null || uimaType.isBlank() || uimaType.equals(node.uimaType()))
                .filter(node -> matchesQuery(node, query))
                .map(this::view)
                .toList();
        return new AgeGraphService.DomainNodePage(page(matches, skip, take), matches.size(), skip, take);
    }

    @Override
    public AgeGraphService.AssociationPage listAssociatedNodes(long corpusId,
                                                               List<String> sourceUids,
                                                               String associationType,
                                                               String direction,
                                                               String targetType,
                                                               int skip,
                                                               int take) {
        Set<String> sources = new HashSet<>(sourceUids == null ? List.of() : sourceUids);
        String normalizedDirection = direction == null ? "BOTH" : direction.toUpperCase();
        List<AgeGraphService.AssociationTraversal> matches = new ArrayList<>();
        for (AgeGraphService.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            if (associationType != null && !associationType.isBlank() && !associationType.equals(edge.uimaType())) {
                continue;
            }
            addTraversal(matches, edge, sources, normalizedDirection, "OUT", edge.leftUid(), edge.rightUid(), targetType);
            addTraversal(matches, edge, sources, normalizedDirection, "IN", edge.rightUid(), edge.leftUid(), targetType);
        }
        return new AgeGraphService.AssociationPage(page(matches, skip, take), matches.size(), skip, take);
    }

    @Override
    public AgeGraphService.ScopePreview previewScope(long corpusId, List<String> selectedUids) {
        Set<String> selected = new HashSet<>(selectedUids == null ? List.of() : selectedUids);
        List<AgeGraphService.DomainNode> nodes = nodesByUid.values().stream()
                .filter(node -> selected.isEmpty() || selected.contains(node.uid()))
                .filter(node -> corpusId <= 0 || node.corpusId() == corpusId)
                .toList();
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        Set<Long> documents = new HashSet<>();
        for (AgeGraphService.DomainNode node : nodes) {
            typeCounts.merge(node.uimaType(), 1L, Long::sum);
            documents.add(node.documentId());
        }
        return new AgeGraphService.ScopePreview(nodes.size(), documents.size(), 0, typeCounts, List.of("dua-memory"));
    }

    @Override
    public AgeGraphService.EgoGraph egoGraph(long corpusId,
                                             List<String> selectedUids,
                                             List<String> associationTypes,
                                             int depth)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        Set<String> selected = new HashSet<>(selectedUids == null ? List.of() : selectedUids);
        Set<String> acceptedAssociations = new HashSet<>(associationTypes == null ? List.of() : associationTypes);
        Map<String, AgeGraphService.DomainNodeView> graphNodes = new LinkedHashMap<>();
        List<AgeGraphService.EgoEdge> graphEdges = new ArrayList<>();
        for (AgeGraphService.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            if (!acceptedAssociations.isEmpty() && !acceptedAssociations.contains(edge.uimaType())) {
                continue;
            }
            if (!selected.isEmpty() && !selected.contains(edge.leftUid()) && !selected.contains(edge.rightUid())) {
                continue;
            }
            AgeGraphService.DomainNode left = nodesByUid.get(edge.leftUid());
            AgeGraphService.DomainNode right = nodesByUid.get(edge.rightUid());
            if (left != null) {
                graphNodes.put(left.uid(), view(left));
            }
            if (right != null) {
                graphNodes.put(right.uid(), view(right));
            }
            graphEdges.add(new AgeGraphService.EgoEdge(edgeView(edge, "BOTH"), edge.rightUid(), "OUT"));
        }
        return new AgeGraphService.EgoGraph(new ArrayList<>(graphNodes.values()), graphEdges);
    }

    private void addTraversal(List<AgeGraphService.AssociationTraversal> matches,
                              AgeGraphService.AssociationEdge edge,
                              Set<String> sources,
                              String requestedDirection,
                              String candidateDirection,
                              String sourceUid,
                              String targetUid,
                              String targetType) {
        if (!sources.isEmpty() && !sources.contains(sourceUid)) {
            return;
        }
        if (!"BOTH".equals(requestedDirection) && !candidateDirection.equals(requestedDirection)) {
            return;
        }
        AgeGraphService.DomainNode target = nodesByUid.get(targetUid);
        if (target == null) {
            return;
        }
        if (targetType != null && !targetType.isBlank() && !targetType.equals(target.uimaType())) {
            return;
        }
        matches.add(new AgeGraphService.AssociationTraversal(edgeView(edge, candidateDirection), view(target)));
    }

    private boolean matchesQuery(AgeGraphService.DomainNode node, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase();
        return contains(node.uid(), needle)
               || contains(node.name(), needle)
               || contains(node.uri(), needle)
               || contains(node.metadata(), needle)
               || contains(node.featuresJson(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private AgeGraphService.DomainNodeView view(AgeGraphService.DomainNode node) {
        return new AgeGraphService.DomainNodeView(
                node.uid(),
                node.uid(),
                node.uimaType(),
                node.name() == null || node.name().isBlank() ? node.uid() : node.name(),
                node.corpusId(),
                node.documentId(),
                node.name(),
                node.uri(),
                node.metadata(),
                node.featuresJson(),
                edgesByUid.values().stream().filter(edge -> node.uid().equals(edge.leftUid())).count(),
                edgesByUid.values().stream().filter(edge -> node.uid().equals(edge.rightUid())).count());
    }

    private AgeGraphService.AssociationEdgeView edgeView(AgeGraphService.AssociationEdge edge, String direction) {
        return new AgeGraphService.AssociationEdgeView(
                edge.uid(),
                edge.uid(),
                edge.uimaType(),
                edge.name() == null || edge.name().isBlank() ? edge.uimaType() : edge.name(),
                edge.name(),
                edge.metadata(),
                edge.featuresJson(),
                direction);
    }

    private <T> List<T> page(List<T> values, int skip, int take) {
        int safeSkip = Math.max(0, skip);
        int safeTake = Math.max(0, take);
        return values.stream().skip(safeSkip).limit(safeTake).toList();
    }
}
