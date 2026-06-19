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
    private final Map<String, DomainGraphModels.DomainNode> nodesByUid = new ConcurrentHashMap<>();
    private final Map<String, DomainGraphModels.AssociationEdge> edgesByUid = new ConcurrentHashMap<>();

    @Override
    public void ensureGraph() {
    }

    @Override
    public void upsertDomainNode(DomainGraphModels.DomainNode node) {
        upsertDomainNodes(List.of(node));
    }

    @Override
    public void upsertDomainNodes(List<DomainGraphModels.DomainNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (DomainGraphModels.DomainNode node : nodes) {
            if (node != null && node.uid() != null) {
                nodesByUid.put(node.uid(), node);
            }
        }
    }

    @Override
    public void upsertAssociationEdge(DomainGraphModels.AssociationEdge edge) {
        upsertAssociationEdges(List.of(edge));
    }

    @Override
    public void upsertAssociationEdges(List<DomainGraphModels.AssociationEdge> edges) {
        if (edges == null) {
            return;
        }
        for (DomainGraphModels.AssociationEdge edge : edges) {
            if (edge != null && edge.uid() != null) {
                edgesByUid.put(edge.uid(), edge);
            }
        }
    }

    @Override
    public List<DomainGraphModels.DomainTypeSummary> listDomainTypes(long corpusId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> associationCounts = new HashMap<>();
        for (DomainGraphModels.DomainNode node : nodesByUid.values()) {
            if (corpusId > 0 && node.corpusId() != corpusId) {
                continue;
            }
            counts.merge(node.uimaType(), 1L, Long::sum);
        }
        for (DomainGraphModels.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            DomainGraphModels.DomainNode left = nodesByUid.get(edge.leftUid());
            if (left == null) {
                continue;
            }
            associationCounts
                    .computeIfAbsent(left.uimaType(), ignored -> new LinkedHashMap<>())
                    .merge(edge.uimaType(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new DomainGraphModels.DomainTypeSummary(
                        entry.getKey(),
                        entry.getKey(),
                        entry.getValue(),
                        associationCounts.getOrDefault(entry.getKey(), Map.of())))
                .toList();
    }

    @Override
    public DomainGraphModels.DomainNodePage listDomainNodes(long corpusId,
                                                          String uimaType,
                                                          String query,
                                                          int skip,
                                                          int take) {
        List<DomainGraphModels.DomainNodeView> matches = nodesByUid.values().stream()
                .filter(node -> corpusId <= 0 || node.corpusId() == corpusId)
                .filter(node -> uimaType == null || uimaType.isBlank() || uimaType.equals(node.uimaType()))
                .filter(node -> matchesQuery(node, query))
                .map(this::view)
                .toList();
        return new DomainGraphModels.DomainNodePage(page(matches, skip, take), matches.size(), skip, take);
    }

    @Override
    public DomainGraphModels.AssociationPage listAssociatedNodes(long corpusId,
                                                               List<String> sourceUids,
                                                               String associationType,
                                                               String direction,
                                                               String targetType,
                                                               int skip,
                                                               int take) {
        Set<String> sources = new HashSet<>(sourceUids == null ? List.of() : sourceUids);
        String normalizedDirection = direction == null ? "BOTH" : direction.toUpperCase();
        List<DomainGraphModels.AssociationTraversal> matches = new ArrayList<>();
        for (DomainGraphModels.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            if (associationType != null && !associationType.isBlank() && !associationType.equals(edge.uimaType())) {
                continue;
            }
            addTraversal(matches, edge, sources, normalizedDirection, "OUT", edge.leftUid(), edge.rightUid(), targetType);
            addTraversal(matches, edge, sources, normalizedDirection, "IN", edge.rightUid(), edge.leftUid(), targetType);
        }
        return new DomainGraphModels.AssociationPage(page(matches, skip, take), matches.size(), skip, take);
    }

    @Override
    public DomainGraphModels.ScopePreview previewScope(long corpusId, List<String> selectedUids) {
        Set<String> selected = new HashSet<>(selectedUids == null ? List.of() : selectedUids);
        List<DomainGraphModels.DomainNode> nodes = nodesByUid.values().stream()
                .filter(node -> selected.isEmpty() || selected.contains(node.uid()))
                .filter(node -> corpusId <= 0 || node.corpusId() == corpusId)
                .toList();
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        Set<Long> documents = new HashSet<>();
        for (DomainGraphModels.DomainNode node : nodes) {
            typeCounts.merge(node.uimaType(), 1L, Long::sum);
            documents.add(node.documentId());
        }
        return new DomainGraphModels.ScopePreview(nodes.size(), documents.size(), 0, typeCounts, List.of("dua-memory"));
    }

    @Override
    public DomainGraphModels.EgoGraph egoGraph(long corpusId,
                                             List<String> selectedUids,
                                             List<String> associationTypes,
                                             int depth)
            throws DatabaseOperationException, DocumentAccessDeniedException {
        Set<String> selected = new HashSet<>(selectedUids == null ? List.of() : selectedUids);
        Set<String> acceptedAssociations = new HashSet<>(associationTypes == null ? List.of() : associationTypes);
        Map<String, DomainGraphModels.DomainNodeView> graphNodes = new LinkedHashMap<>();
        List<DomainGraphModels.EgoEdge> graphEdges = new ArrayList<>();
        for (DomainGraphModels.AssociationEdge edge : edgesByUid.values()) {
            if (corpusId > 0 && edge.corpusId() != corpusId) {
                continue;
            }
            if (!acceptedAssociations.isEmpty() && !acceptedAssociations.contains(edge.uimaType())) {
                continue;
            }
            if (!selected.isEmpty() && !selected.contains(edge.leftUid()) && !selected.contains(edge.rightUid())) {
                continue;
            }
            DomainGraphModels.DomainNode left = nodesByUid.get(edge.leftUid());
            DomainGraphModels.DomainNode right = nodesByUid.get(edge.rightUid());
            if (left != null) {
                graphNodes.put(left.uid(), view(left));
            }
            if (right != null) {
                graphNodes.put(right.uid(), view(right));
            }
            graphEdges.add(new DomainGraphModels.EgoEdge(edgeView(edge, "BOTH"), edge.rightUid(), "OUT"));
        }
        return new DomainGraphModels.EgoGraph(new ArrayList<>(graphNodes.values()), graphEdges);
    }

    private void addTraversal(List<DomainGraphModels.AssociationTraversal> matches,
                              DomainGraphModels.AssociationEdge edge,
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
        DomainGraphModels.DomainNode target = nodesByUid.get(targetUid);
        if (target == null) {
            return;
        }
        if (targetType != null && !targetType.isBlank() && !targetType.equals(target.uimaType())) {
            return;
        }
        matches.add(new DomainGraphModels.AssociationTraversal(edgeView(edge, candidateDirection), view(target)));
    }

    private boolean matchesQuery(DomainGraphModels.DomainNode node, String query) {
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

    private DomainGraphModels.DomainNodeView view(DomainGraphModels.DomainNode node) {
        return new DomainGraphModels.DomainNodeView(
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

    private DomainGraphModels.AssociationEdgeView edgeView(DomainGraphModels.AssociationEdge edge, String direction) {
        return new DomainGraphModels.AssociationEdgeView(
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
