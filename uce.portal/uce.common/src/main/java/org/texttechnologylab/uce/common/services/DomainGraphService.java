package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;

import java.util.List;

public interface DomainGraphService {
    void ensureGraph() throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertDomainNode(AgeGraphService.DomainNode node) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertDomainNodes(List<AgeGraphService.DomainNode> nodes) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertAssociationEdge(AgeGraphService.AssociationEdge edge) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertAssociationEdges(List<AgeGraphService.AssociationEdge> edges) throws DatabaseOperationException, DocumentAccessDeniedException;

    List<AgeGraphService.DomainTypeSummary> listDomainTypes(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    AgeGraphService.DomainNodePage listDomainNodes(long corpusId,
                                                   String uimaType,
                                                   String query,
                                                   int skip,
                                                   int take)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    AgeGraphService.AssociationPage listAssociatedNodes(long corpusId,
                                                        List<String> sourceUids,
                                                        String associationType,
                                                        String direction,
                                                        String targetType,
                                                        int skip,
                                                        int take)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    AgeGraphService.ScopePreview previewScope(long corpusId, List<String> selectedUids)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    AgeGraphService.EgoGraph egoGraph(long corpusId,
                                      List<String> selectedUids,
                                      List<String> associationTypes,
                                      int depth)
            throws DatabaseOperationException, DocumentAccessDeniedException;
}
