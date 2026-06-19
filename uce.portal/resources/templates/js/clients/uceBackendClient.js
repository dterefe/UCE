<#noparse>
(function installUceBackendClient(global) {
    global.UCE = global.UCE || {};
    if (global.UCEBackendClient && global.UCEBackendClient.__uceBackendClient) return;
    if (!global.UCE.RequestHandler) throw new Error('UCEBackendClient requires UCE.RequestHandler.');

    const RequestHandler = global.UCE.RequestHandler;
    const models = global.UCE.UCEBackendModels || {operations: {}};

    RequestHandler.registerHandle(RequestHandler.HandleName.UCE_BACKEND, {
        kind: RequestHandler.HandleKind.HTTP_JSON,
        baseUrl: '',
        operations: models.operations || {},
        defaultTimeoutMs: 10000
    });

    function request(operation, payload, options) {
        return RequestHandler.request(RequestHandler.HandleName.UCE_BACKEND, operation, payload || {}, options || {});
    }

    function withPayload(base, extra) {
        return Object.assign({}, base || {}, extra || {});
    }

    const api = {
        __uceBackendClient: true,
        request,
        auth: {
            ping: () => request('auth.ping')
        },
        corpus: {
            inspector: corpusId => request('corpus.inspector', {id: corpusId}, {responseKind: 'html'}),
            documentsList: (corpusId, page) => request('corpus.documentsList', {corpusId, page: page || 1}, {responseKind: 'html'}),
            map: {
                linkedOccurrences: payload => request('corpus.map.linkedOccurrences', payload || {}),
                linkedOccurrenceClusters: payload => request('corpus.map.linkedOccurrenceClusters', payload || {})
            }
        },
        search: {
            default: payload => request('search.default', payload || {}, {responseKind: 'html'}),
            records: payload => request('search.records', payload || {}),
            layered: payload => request('search.layered', payload || {}, {responseKind: 'html'}),
            semanticRole: payload => request('search.semanticRole', payload || {}, {responseKind: 'html'}),
            activePage: (searchId, page) => request('search.activePage', {searchId, page}),
            activeSort: (searchId, orderBy, order) => request('search.activeSort', {searchId, orderBy, order}, {responseKind: 'html'}),
            semanticRoleBuilder: () => request('search.semanticRoleBuilder', {}, {responseKind: 'html'})
        },
        document: {
            pagesList: (documentId, skip) => request('document.pagesList', {id: documentId, skip: skip || 0}, {responseKind: 'html'}),
            metadata: documentId => request('document.metadata', {documentId}, {responseKind: 'html'}),
            topics: documentId => request('document.topics', {documentId}),
            pageTaxon: documentId => request('document.pageTaxon', {documentId}),
            pageTopics: documentId => request('document.pageTopics', {documentId}),
            pageTopicEntityRelation: documentId => request('document.pageTopicEntityRelation', {documentId}),
            pageTopicWords: documentId => request('document.pageTopicWords', {documentId}),
            unifiedTopicSentenceMap: documentId => request('document.unifiedTopicSentenceMap', {documentId}),
            pageNamedEntities: documentId => request('document.pageNamedEntities', {documentId}),
            pageLemma: documentId => request('document.pageLemma', {documentId}),
            pageGeoname: documentId => request('document.pageGeoname', {documentId})
        },
        wiki: {
            page: payload => request('wiki.page', payload || {}, {responseKind: 'html'}),
            annotation: payload => request('wiki.annotation', payload || {}, {responseKind: 'html'}),
            linkableNode: payload => request('wiki.linkableNode', payload || {}, {responseKind: 'html'}),
            lexiconEntries: payload => request('wiki.lexiconEntries', payload || {}),
            lexiconOccurrences: payload => request('wiki.lexiconOccurrences', payload || {}, {responseKind: 'html'}),
            queryOntology: payload => request('wiki.queryOntology', payload || {}, {responseKind: 'html'})
        },
        analysis: {
            runPipeline: payload => request('analysis.runPipeline', payload || {}, {responseKind: 'html'}),
            setHistory: payload => request('analysis.setHistory', payload || {}, {responseKind: 'html'}),
            callHistory: payload => request('analysis.callHistory', payload || {}, {responseKind: 'html'}),
            callHistoryText: payload => request('analysis.callHistoryText', payload || {}, {responseKind: 'html'})
        },
        rag: {
            new: payload => request('rag.new', payload || {}, {responseKind: 'html'}),
            postUserMessage: payload => request('rag.postUserMessage', payload || {}, {responseKind: 'html'}),
            messages: payload => request('rag.messages', payload || {}, {responseKind: 'html'}),
            plotTsne: payload => request('rag.plotTsne', payload || {}, {responseKind: 'html'}),
            sentenceEmbeddings: payload => request('rag.sentenceEmbeddings', payload || {})
        },
        importExport: {
            uploadUima: payload => request('importExport.uploadUima', payload || {}, {responseKind: 'text'}),
            downloadUima: payload => request('importExport.downloadUima', payload || {}, {responseKind: 'text'})
        },
        withPayload
    };

    global.UCEBackendClient = api;
    global.UCE.BackendClient = api;
})(window);
</#noparse>
