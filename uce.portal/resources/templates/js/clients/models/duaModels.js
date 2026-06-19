<#noparse>
(function installDuaModels(global) {
    global.UCE = global.UCE || {};
    const models = {
        primitives: {
            FsRef: 'integer',
            TypeCode: 'integer',
            FeatureCode: 'integer',
            ViewRef: 'integer',
            SofaRef: 'integer',
            CharOffset: 'integer',
            Offset: 'integer',
            Limit: 'integer'
        },
        operations: {
            'types': {handle: 'DUA', action: 'types', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'typesystem.get': {handle: 'DUA', action: 'types', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'type.children': {handle: 'DUA', action: 'type.children', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'instancesByType': {handle: 'DUA', action: 'instancesByType', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'select': {handle: 'DUA', action: 'instancesByType', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'fs.select': {handle: 'DUA', action: 'instancesByType', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'fs': {handle: 'DUA', action: 'fs', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'fs.get': {handle: 'DUA', action: 'fs', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'span': {handle: 'DUA', action: 'span', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'span.get': {handle: 'DUA', action: 'span', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'graph': {handle: 'DUA', action: 'graph', responseKind: 'json', cachePolicy: 'none'},
            'graph.get': {handle: 'DUA', action: 'graph', responseKind: 'json', cachePolicy: 'none'},
            'sofa.text': {handle: 'DUA', action: 'sofa.text', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'document': {handle: 'DUA', action: 'document', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'document.reader': {handle: 'DUA', action: 'document', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
            'search.query': {handle: 'DUA', action: 'search', responseKind: 'json', cachePolicy: 'none'},
	            'lexicon.entries': {handle: 'DUA', action: 'lexicon.entries', responseKind: 'json', cachePolicy: 'none'},
	            'geo.occurrences': {handle: 'DUA', action: 'geo.occurrences', responseKind: 'json', cachePolicy: 'none'},
	            'http.health': {handle: 'DUA_HTTP', method: 'GET', path: '/health', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.stats': {handle: 'DUA_HTTP', method: 'GET', path: '/stats', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.schema': {handle: 'DUA_HTTP', method: 'GET', path: '/duaviz/schema', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.select': {handle: 'DUA_HTTP', method: 'GET', path: '/query/fsrefs', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.fs': {handle: 'DUA_HTTP', method: 'GET', path: '/fs', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.span': {handle: 'DUA_HTTP', method: 'GET', path: '/span', responseKind: 'json', cachePolicy: 'dedupe_inflight'},
	            'http.spanQuery': {handle: 'DUA_HTTP', method: 'GET', path: '/span/query', responseKind: 'json', cachePolicy: 'dedupe_inflight'}
	        },
        schemas: {
            Type: ['typeCode', 'name', 'superTypeCode', 'kind', 'primitiveKind', 'componentTypeCode', 'abstract'],
            Feature: ['featureCode', 'domainTypeCode', 'rangeTypeCode', 'name', 'shortName', 'multipleReferencesAllowed'],
            FeatureStructure: ['fsRef', 'typeCode', 'viewRef', 'features'],
            Span: ['fsRef', 'sofaFsRef', 'begin', 'end'],
            View: ['viewRef', 'sofaFsRef', 'name'],
            SearchQuery: ['corpusFsRef', 'queryText', 'queryMode', 'layers', 'constraints'],
            SearchHit: ['documentFsRef', 'sofaFsRef', 'score', 'matchedFsRefs', 'snippets'],
            LexiconEntry: ['key', 'label', 'typeCode', 'representativeFsRef', 'count', 'features'],
            GeoPoint: ['fsRef', 'latitude', 'longitude']
        }
    };
    global.UCE.DUAModels = Object.freeze(models);
})(window);
</#noparse>
