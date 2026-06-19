package org.texttechnologylab.uce.common.services;

import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;

import java.util.List;

public interface DomainGraphService {
    void ensureGraph() throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertDomainNode(DomainGraphModels.DomainNode node) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertDomainNodes(List<DomainGraphModels.DomainNode> nodes) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertAssociationEdge(DomainGraphModels.AssociationEdge edge) throws DatabaseOperationException, DocumentAccessDeniedException;

    void upsertAssociationEdges(List<DomainGraphModels.AssociationEdge> edges) throws DatabaseOperationException, DocumentAccessDeniedException;

    List<DomainGraphModels.DomainTypeSummary> listDomainTypes(long corpusId) throws DatabaseOperationException, DocumentAccessDeniedException;

    DomainGraphModels.DomainNodePage listDomainNodes(long corpusId,
                                                   String uimaType,
                                                   String query,
                                                   int skip,
                                                   int take)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    DomainGraphModels.AssociationPage listAssociatedNodes(long corpusId,
                                                        List<String> sourceUids,
                                                        String associationType,
                                                        String direction,
                                                        String targetType,
                                                        int skip,
                                                        int take)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    DomainGraphModels.ScopePreview previewScope(long corpusId, List<String> selectedUids)
            throws DatabaseOperationException, DocumentAccessDeniedException;

    DomainGraphModels.EgoGraph egoGraph(long corpusId,
                                      List<String> selectedUids,
                                      List<String> associationTypes,
                                      int depth)
            throws DatabaseOperationException, DocumentAccessDeniedException;
}
