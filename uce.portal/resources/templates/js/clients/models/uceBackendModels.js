<#noparse>
(function installUceBackendModels(global) {
    global.UCE = global.UCE || {};
    const html = responseKind => responseKind || 'html';
    const json = responseKind => responseKind || 'json';
    const text = responseKind => responseKind || 'text';
    const models = {
        operations: {
            'auth.ping': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/auth/ping', responseKind: json()},

            'corpus.inspector': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/corpus/inspector', responseKind: html()},
            'corpus.documentsList': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/corpus/documentsList', responseKind: html()},
            'corpus.map.linkedOccurrences': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/corpus/map/linkedOccurrences', responseKind: json()},
            'corpus.map.linkedOccurrenceClusters': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/corpus/map/linkedOccurrenceClusters', responseKind: json()},

            'search.default': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/search/default', responseKind: html()},
            'search.records': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/search/records', responseKind: json()},
            'search.layered': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/search/layered', responseKind: html()},
            'search.semanticRole': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/search/semanticRole', responseKind: html()},
            'search.activePage': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/search/active/page', responseKind: json()},
            'search.activeSort': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/search/active/sort', responseKind: html()},
            'search.semanticRoleBuilder': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/search/semanticRole/builder', responseKind: html()},

            'document.pagesList': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/reader/pagesList', responseKind: html()},
            'document.metadata': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/uceMetadata', responseKind: html()},
            'document.topics': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/topics', responseKind: json()},
            'document.pageTaxon': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/taxon', responseKind: json()},
            'document.pageTopics': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/topics', responseKind: json()},
            'document.pageTopicEntityRelation': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/topicEntityRelation', responseKind: json()},
            'document.pageTopicWords': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/topicWords', responseKind: json()},
            'document.unifiedTopicSentenceMap': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/unifiedTopicSentenceMap', responseKind: json()},
            'document.pageNamedEntities': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/namedEntities', responseKind: json()},
            'document.pageLemma': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/lemma', responseKind: json()},
            'document.pageGeoname': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/document/page/geoname', responseKind: json()},

            'wiki.page': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/wiki/page', responseKind: html()},
            'wiki.annotation': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/wiki/annotation', responseKind: html()},
            'wiki.linkableNode': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/wiki/linkable/node', responseKind: html()},
            'wiki.lexiconEntries': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/wiki/lexicon/entries', responseKind: json()},
            'wiki.lexiconOccurrences': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/wiki/lexicon/occurrences', responseKind: html()},
            'wiki.queryOntology': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/wiki/queryOntology', responseKind: html()},

            'analysis.runPipeline': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/analysis/runPipeline', responseKind: html()},
            'analysis.setHistory': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/analysis/setHistory', responseKind: html()},
            'analysis.callHistory': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/analysis/callHistory', responseKind: html()},
            'analysis.callHistoryText': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/analysis/callHistoryText', responseKind: html()},

            'rag.new': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/rag/new', responseKind: html()},
            'rag.messages': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/rag/messages', responseKind: html()},
            'rag.postUserMessage': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/rag/postUserMessage', responseKind: html()},
            'rag.plotTsne': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/rag/plotTsne', responseKind: html()},
            'rag.sentenceEmbeddings': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/rag/sentenceEmbeddings', responseKind: json()},

            'importExport.uploadUima': {handle: 'UCE_BACKEND', method: 'POST', path: '/api/ie/upload/uima', responseKind: text()},
            'importExport.downloadUima': {handle: 'UCE_BACKEND', method: 'GET', path: '/api/ie/download/uima', responseKind: text()}
        }
    };
    global.UCE.UCEBackendModels = Object.freeze(models);
})(window);
</#noparse>
