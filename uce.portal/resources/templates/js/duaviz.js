<#noparse>
(function installDuaviz() {
    const state = {
        types: [],
        children: new Map(),
        instancesByType: new Map(),
        typePageOffsets: new Map(),
        expandedSections: new Set(['artifact', 'annotation']),
        expandedTypes: new Set(),
        loadingTypes: new Set(),
        selectedArtifact: null,
        corpusDocuments: [],
        document: null,
        selectedView: '_InitialView',
        checkedAnnotationTypes: new Set(),
        selectedAnnotationFsIds: new Set(),
        spanColorOverrides: new Map(),
        fsCache: new Map(),
        spanCache: new Map(),
        colors: new Map(),
        typeColorOverrides: new Map(),
        selectedVizComponent: 'highlighter',
        selectedVizInstance: 'highlighter-1',
        vizDetailOpen: false,
        activeVizAnchor: 'fs.type',
        vizBindings: {
            highlighter: {}
        },
        vizInstances: [{
            id: 'highlighter-1',
            componentId: 'highlighter',
            label: 'Annotation Highlighter',
            bindings: {},
            config: {
                opacity: 52,
                color: '#16a34a'
            }
        }],
        mode: 'reader',
        documentStructureTypes: [],
        documentStructureTypeCodes: [],
        documentMetadataRows: null,
        uceMetadataRows: null,
        staticRequestSummary: [],
        artifactExpansionNormalized: false,
        typeDetailsNormalized: false,
        documentFallbackDocuments: [],
        documentFallbackLoading: false,
        documentFallbackLoaded: false,
        documentFallbackOffset: 0
    };
    const vizComponents = [{
        id: 'highlighter',
        label: 'Annotation Highlighter',
        description: 'Text span overlay driven by annotation feature slots.',
        icon: 'fa-highlighter',
        bindingScope: 'single-fstype',
        attributeSchema: {
            scope: 'annotation',
            anchors: {
                'fs.type': {select: 'type', restricts: ['range.begin', 'range.end']},
                'range.begin': {select: 'feature', excludeInherited: true, exclude: ['begin', 'end']},
                'range.end': {select: 'feature', excludeInherited: true, exclude: ['begin', 'end']}
            }
        },
        anchors: [
            {id: 'fs.type', label: 'Type', accepts: ['fs-reference'], bindTarget: 'type', required: true, compactGroup: 'core'},
            {id: 'range.begin', label: 'Begin', accepts: ['integer', 'long', 'numeric'], bindTarget: 'feature', required: true, compactGroup: 'core'},
            {id: 'range.end', label: 'End', accepts: ['integer', 'long', 'numeric'], bindTarget: 'feature', required: true, compactGroup: 'core'},
            {id: 'style.opacity', label: 'Opacity', accepts: ['integer', 'float', 'double', 'numeric'], bindTarget: 'config', required: false}
        ]
    }, {
        id: 'pointer',
        label: 'Pointer',
        description: 'Draws curved SVG pointers from a span to referenced feature structures within a bounded text distance.',
        icon: 'fa-route',
        bindingScope: 'multi-fstype',
        attributeSchema: {
            scope: 'annotation',
            anchors: {
                'source.type': {select: 'type'},
                'reference.feature': {select: 'feature', accepts: ['fs-reference']},
                'target.type': {select: 'type'}
            }
        },
        anchors: [
            {id: 'source.type', label: 'Source', accepts: ['fs-reference'], bindTarget: 'type', required: true, compactGroup: 'core'},
            {id: 'reference.feature', label: 'Ref', accepts: ['fs-reference'], bindTarget: 'feature', required: true, compactGroup: 'core'},
            {id: 'target.type', label: 'Target', accepts: ['fs-reference'], bindTarget: 'type', required: false, compactGroup: 'core'},
            {id: 'style.limit', label: 'Span Limit', accepts: ['integer', 'numeric'], bindTarget: 'config', required: false}
        ],
        defaults: {
            spanLimit: 300,
            color: '#2563eb'
        }
    }];
    const palette = ['#f59e0b', '#2563eb', '#16a34a', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#4d7c0f'];
    const harmonicColors = ['#2563eb', '#0891b2', '#16a34a', '#84cc16', '#f59e0b', '#dc2626', '#db2777', '#7c3aed'];
    const instancePageSize = 5;
    let socket = null;
    let socketReady = null;
    let requestSeq = 0;
    const pendingRequests = new Map();
    let pendingRequestQueue = [];
    let directCasEndpointFromTelemetry = null;
    const DUA_ACTIVE_HTTP = 17883;
    const DUA_ACTIVE_WS = 17884;
    const DUA_ACTIVE_WS_PATH = '/';
    const DUA_STATIC_DATA_URL = '';
    let staticCorpusLoadPromise = null;
    let staticCorpus = null;
    let staticTypeIndex = null;
    let staticDocumentContext = null;

    function isStaticCorpusEnabled() {
        return false;
    }

    function duavizStaticDataUrl() {
        return '';
    }

    function staticTypeIndexInit(payload) {
        const index = {
            payload,
            typesByCode: new Map(),
            typesByName: new Map(),
            typesByShortName: new Map(),
            typeChildrenByCode: new Map(),
            instancesByType: new Map(),
            fsById: new Map(),
            documentsById: new Map(),
            documentTypeCodes: new Set(),
            typeNameList: new Set(),
            loaded: true
        };
        const types = Array.isArray(payload && payload.types) ? payload.types : [];
        types.forEach(type => {
            if (!type || typeof type !== 'object') return;
            const name = String(type.name || '').trim();
            const code = Number(type.typeCode || casTypeCode(name));
            if (!name || !Number.isFinite(code)) return;
            const normalizedType = Object.assign({}, type, {
                typeCode: code,
                superTypeCode: Number(type.superTypeCode || casSuperTypeCode(name))
            });
            const shortName = parseTypeNameShort(name).toLowerCase();
            index.typesByCode.set(code, normalizedType);
            index.typesByName.set(name.toLowerCase(), normalizedType);
            index.typesByShortName.set(shortName, normalizedType);
            index.typeNameList.add(name);
            if (Array.isArray(payload && payload.documentTypeNames) && payload.documentTypeNames.includes(name)) {
                index.documentTypeCodes.add(code);
            }
            if (!type.children && type.superTypeCode > 0) {
                const parent = Number(type.superTypeCode);
                if (!index.typeChildrenByCode.has(parent)) index.typeChildrenByCode.set(parent, new Set());
                index.typeChildrenByCode.get(parent).add(code);
            }
        });
        const ensureType = (name, codeFallback) => {
            const short = parseTypeNameShort(String(name || '')).toLowerCase();
            if (!name) return null;
            if (index.typesByName.has(String(name).toLowerCase())) return index.typesByName.get(String(name).toLowerCase());
            if (index.typesByShortName.has(short)) return index.typesByShortName.get(short);
            const byCode = index.typesByCode.get(Number(codeFallback) || 0);
            if (byCode) return byCode;
            return null;
        };
        const loadInstances = (entries, targetCode) => {
            if (!Array.isArray(entries)) return;
            entries.forEach(instance => {
                if (!instance || typeof instance !== 'object') return;
                const typeName = String(instance.typeName || instance.type || '').trim() || String(ensureType(targetCode, targetCode) && ensureType(targetCode, targetCode).name || '');
                const typeCode = Number(instance.typeCode || targetCode || (parseTypeNameCode(typeName) || 0));
                const fsId = Number(instance.fsId || instance._id || instance.id || (instance.fs && instance.fs._id) || 0);
                if (!Number.isFinite(fsId) || fsId <= 0) return;
                if (typeCode) {
                    if (!index.instancesByType.has(typeCode)) index.instancesByType.set(typeCode, []);
                    index.instancesByType.get(typeCode).push(instance);
                    if (isLikelyDocumentTypeName(typeName)) {
                        index.documentTypeCodes.add(typeCode);
                    }
                }
                if (!index.fsById.has(String(fsId))) index.fsById.set(String(fsId), instance);
            });
        };
        const rawInstancesByType = payload && payload.instancesByType;
        if (rawInstancesByType && typeof rawInstancesByType === 'object') {
            Object.entries(rawInstancesByType).forEach(([codeText, instances]) => {
                loadInstances(instances, Number(codeText));
            });
        }
        if (Array.isArray(payload && payload.instances)) {
            loadInstances(payload.instances, 0);
        }
        if (payload && Array.isArray(payload.documents)) {
            payload.documents.forEach(document => {
                if (!document || typeof document !== 'object') return;
                const fsId = Number(document.fsId || 0);
                if (Number.isFinite(fsId) && fsId > 0) index.documentsById.set(String(fsId), document);
            });
        }
        if (!index.documentTypeCodes.size && Array.isArray(payload && payload.documentTypeNames) && payload.documentTypeNames.length) {
            payload.documentTypeNames.forEach(documentTypeName => {
                const type = index.typesByName.get(String(documentTypeName || '').toLowerCase());
                if (type) index.documentTypeCodes.add(Number(type.typeCode));
            });
        }
        return index;
    }

    function parseStaticArticleIdFromSourceFile(sourceFile) {
        const match = String(sourceFile || '').match(/(\d+)(?:\.xmi(?:\.gz)?)?$/);
        return match && match[1] ? match[1] : '';
    }

    function staticAttributeValue(row) {
        return row && row.features ? row.features : {};
    }

    function normalizeStaticMetadataRow(row) {
        if (!row || typeof row !== 'object') return null;
        const normalizedFeatures = {};
        const features = staticAttributeValue(row);
        Object.entries(features).forEach(([key, value]) => {
            if (key == null) return;
            if (value === undefined || value === null) return;
            normalizedFeatures[String(key)] = String(value);
        });
        return {
            fsId: Number(row._id || row.fsId || row.id || 0) || Number(row._id || 0),
            typeCode: Number(row.typeCode || 0),
            typeName: String(row._type || row.type || ''),
            features: normalizedFeatures
        };
    }

    function buildStaticDocumentContext() {
        if (!staticCorpus || !staticTypeIndex) return null;
        if (staticDocumentContext && staticDocumentContext.generatedAt === staticCorpus.generatedAt) return staticDocumentContext;

        const documents = Array.isArray(staticCorpus.documents) ? staticCorpus.documents.slice() : [];
        const documentsById = new Map();
        const documentByIndex = [];
        const documentBySourceArticleId = new Map();
        documents.forEach(document => {
            const fsId = Number(document && document.fsId || 0);
            const sourceArticleId = parseStaticArticleIdFromSourceFile(document && document.sourceFile || '');
            if (Number.isFinite(fsId) && fsId > 0) {
                documentsById.set(fsId, document);
                documentByIndex.push(document);
            }
            if (sourceArticleId) documentBySourceArticleId.set(sourceArticleId, document);
        });
        documentByIndex.sort((a, b) => Number(a.fsId || 0) - Number(b.fsId || 0));

        const sofaTypeCode = casTypeCode('cas:Sofa');
        const sofaInstances = Array.from(staticTypeIndex.instancesByType.get(sofaTypeCode) || [])
            .map(item => normalizeStaticMetadataRow(item))
            .filter(row => Number.isFinite(row.fsId) && row.fsId > 0)
            .sort((a, b) => a.fsId - b.fsId);

        const metadataByArticleId = new Map();
        const docMetaByArticleId = new Map();
        const articleByArticleId = new Map();
        let currentArticleId = '';

        Array.from(staticTypeIndex.fsById.values()).forEach(row => {
            const type = String(row && row._type || row && row.type || '').trim();
            const features = staticAttributeValue(row);
            const normalized = normalizeStaticMetadataRow(row);

            if (type === 'Metadata') {
                const key = String(features.key || '').trim();
                const value = String(features.value || '').trim();
                const articleId = String(features.article_id || currentArticleId || '').trim();
                if (key === 'article_id' && value) {
                    currentArticleId = value;
                    if (!metadataByArticleId.has(value)) metadataByArticleId.set(value, {});
                }
                if (currentArticleId && key) {
                    if (!metadataByArticleId.has(currentArticleId)) metadataByArticleId.set(currentArticleId, {});
                    metadataByArticleId.get(currentArticleId)[key] = value;
                }
                return;
            }

            if (type === 'DocumentMetaData') {
                const articleId = String(features.documentId || '').trim();
                if (articleId) docMetaByArticleId.set(articleId, normalized);
                return;
            }

            if (type === 'Article') {
                const articleIdFromFeature = String(features.id || features.uri || '').split(':').pop().replace(/\D+$/, '');
                const articleId = String(features.uri || '').trim().split('/').pop().split('.')[0];
                const key = articleIdFromFeature || articleId;
                if (key) {
                    articleByArticleId.set(key, normalized);
                }
            }
        });

        const sofaByIndex = new Map();
        const documentForSofaId = new Map();
        documentByIndex.forEach((document, index) => {
            if (!sofaInstances[index]) return;
            const docFsId = Number(document.fsId || 0);
            const sofaFsId = Number(sofaInstances[index].fsId || 0);
            if (Number.isFinite(docFsId) && Number.isFinite(sofaFsId) && docFsId > 0 && sofaFsId > 0) {
                sofaByIndex.set(sofaFsId, docFsId);
                documentForSofaId.set(docFsId, sofaFsId);
            }
        });

        staticDocumentContext = {
            generatedAt: staticCorpus.generatedAt,
            documentsById,
            documentsByIndex: documentByIndex,
            documentBySourceArticleId,
            sofaInstances,
            sofaByIndex,
            metadataByArticleId,
            docMetaByArticleId,
            articleByArticleId,
            documentForSofaId
        };
        return staticDocumentContext;
    }

    function parseTypeNameCode(typeName) {
        const resolved = stateTypeCodeByName(typeName);
        return Number.isFinite(resolved) ? resolved : casTypeCode(typeName);
    }

    function parseTypeNameShort(typeName) {
        const normalized = String(typeName || '').trim();
        if (normalized.indexOf(':') >= 0) return normalized.split(':').pop();
        if (normalized.indexOf('.') >= 0) return normalized.split('.').pop();
        return normalized;
    }

    function stateTypeCodeByName(typeName) {
        if (!staticTypeIndex || !typeName) return 0;
        const direct = staticTypeIndex.typesByName.get(String(typeName || '').toLowerCase());
        if (direct) return Number(direct.typeCode);
        const short = parseTypeNameShort(typeName).toLowerCase();
        if (staticTypeIndex.typesByShortName.has(short)) {
            return Number(staticTypeIndex.typesByShortName.get(short).typeCode);
        }
        return 0;
    }

    async function loadStaticCorpus() {
        return null;
    }

    function isLikelyDocumentTypeName(typeName) {
        const normalized = String(typeName || '').trim().toLowerCase();
        return isDocumentTypeName(normalized) || normalized === 'document' || normalized === 'article';
    }

    function getStaticDocumentTypeCodesSync() {
        if (!staticTypeIndex || !staticTypeIndex.documentTypeCodes.size) return null;
        return new Set(staticTypeIndex.documentTypeCodes);
    }

    function root() {
        return $('.duaviz-shell');
    }

    function normalizeDirectCasWsUrl(url) {
        if (!url) return url;
        const value = String(url);
        if (value.endsWith(DUA_ACTIVE_WS_PATH)) {
            return value.slice(0, -DUA_ACTIVE_WS_PATH.length);
        }
        return value;
    }

    function duavizWsEndpoint() {
        const http = new URL(duavizHttpEndpoint());
        http.protocol = http.protocol === 'https:' ? 'wss:' : 'ws:';
        http.port = String(Number(http.port || DUA_ACTIVE_HTTP) === DUA_ACTIVE_HTTP ? DUA_ACTIVE_WS : Number(http.port || DUA_ACTIVE_HTTP) + 1);
        http.pathname = DUA_ACTIVE_WS_PATH;
        http.search = '';
        http.hash = '';
        return http.toString().replace(/\/+$/, '');
    }

    function socketUrl() {
        return duavizWsEndpoint();
    }

    function duavizHttpEndpoint() {
        const configured = window.uceDuavizHttpUrl || window.uceDuavizEndpoint;
        if (typeof configured !== 'string' || !configured.trim().length) {
            return 'http://127.0.0.1:' + DUA_ACTIVE_HTTP;
        }
        const trimmed = configured.trim().replace(/\/+$/, '');
        try {
            const url = new URL(trimmed, window.location.origin);
            if (url.port === '17875') {
                url.hostname = '127.0.0.1';
                url.port = String(DUA_ACTIVE_HTTP);
            }
            return url.toString().replace(/\/+$/, '');
        } catch (_) {
            return trimmed.replace('17875', String(DUA_ACTIVE_HTTP));
        }
    }

    function resolveDuavizWsUrl(raw) {
        if (!raw) return null;
        if (/^wss?:\/\//i.test(raw)) return raw;

        const normalized = String(raw).trim();
        if (!normalized) return null;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        if (normalized.startsWith('/')) {
            return protocol + '//' + window.location.host + normalized;
        }

        if (!/^localhost[:/]/i.test(normalized) && !/^[a-zA-Z0-9.-]+:\d+(?:\/|$)/.test(normalized)) {
            return null;
        }

        return protocol + '//' + normalized;
    }

    function nextDuavizRequestId() {
        return 'duaviz-' + (++requestSeq);
    }

    function requestIdFromMessage(message) {
        return message && message.requestId != null ? String(message.requestId) : null;
    }

    function isReadyFrame(message) {
        return !!(message && !requestIdFromMessage(message) && message.type === 'ready');
    }

    function isErrorFrame(message) {
        if (!message || typeof message !== 'object') return false;
        if (message.error) return true;
        const status = Number(message.status);
        return Number.isFinite(status) && status >= 400;
    }

    function buildDuavizFrame(action, payload, requestId) {
        const source = payload && typeof payload === 'object' ? payload : {};
        const resolvedAction = String(action || source.action || source.op || '').trim();
        const framePayload = Object.assign({}, source);
        const frame = {
            requestId: requestId || nextDuavizRequestId(),
            action: resolvedAction,
            op: resolvedAction,
            payload: framePayload
        };
        Object.keys(framePayload).forEach(key => {
            if (key === 'action' || key === 'op' || key === 'payload' || key === 'requestId') return;
            frame[key] = framePayload[key];
        });
        if (!frame.action) {
            frame.action = String(source.action || source.op || '').trim();
            frame.op = frame.action;
        }
        return frame;
    }

    function ensureSocket() {
        if (socket && socket.readyState === WebSocket.OPEN) return Promise.resolve(socket);
        if (socketReady) return socketReady;
        socketReady = new Promise((resolve, reject) => {
            socket = new WebSocket(socketUrl());
            socket.onopen = () => resolve(socket);
            socket.onerror = () => reject(new Error('DUAViz websocket connection failed.'));
            socket.onclose = () => {
                socketReady = null;
                socket = null;
                pendingRequests.forEach(request => request.reject(new Error('DUAViz websocket closed.')));
                pendingRequests.clear();
                pendingRequestQueue = [];
            };
            socket.onmessage = event => {
                let message = {};
                try {
                    message = JSON.parse(event.data || '{}');
                } catch (error) {
                    if (pendingRequestQueue.length) {
                        const requestId = pendingRequestQueue.shift();
                        const request = pendingRequests.get(String(requestId));
                        if (request) {
                            pendingRequests.delete(String(requestId));
                            request.reject(error);
                        }
                    }
                    return;
                }
                if (isReadyFrame(message)) {
                    return;
                }
                if (isErrorFrame(message)) {
                    const requestId = requestIdFromMessage(message) || (pendingRequestQueue.length ? pendingRequestQueue.shift() : null);
                    if (!requestId) return;
                    const request = pendingRequests.get(String(requestId));
                    if (!request) return;
                    pendingRequests.delete(String(requestId));
                    request.reject(new Error(message.message || message.error || 'DUAViz websocket query failed.'));
                    return;
                }
                let requestId = requestIdFromMessage(message);
                if (!requestId || !pendingRequests.has(requestId)) {
                    requestId = pendingRequestQueue.length ? pendingRequestQueue.shift() : null;
                }
                if (!requestId) return;
                const request = pendingRequests.get(String(requestId));
                if (!request) return;
                pendingRequests.delete(String(requestId));
                request.resolve(message);
            };
        });
        return socketReady;
    }

    async function wsQuery(action, payload) {
        const httpMapped = ['types', 'telemetry', 'select', 'instancesByType', 'fs', 'span'].includes(String(action || ''));
        try {
            return await duaHttpRequest(action, payload || {});
        } catch (httpError) {
            if (httpMapped && duavizHttpEndpoint()) throw httpError;
        }
        if (window.DUAClient && typeof window.DUAClient.request === 'function') {
            try {
                return await window.DUAClient.request(action, payload || {});
            } catch (clientError) {
            }
        }
        const requestId = nextDuavizRequestId();
        const frame = buildDuavizFrame(action, payload, requestId);
        try {
            const ws = await ensureSocket();
            return await new Promise((resolve, reject) => {
                const timer = window.setTimeout(() => {
                    pendingRequests.delete(requestId);
                    pendingRequestQueue = pendingRequestQueue.filter(item => String(item) !== String(requestId));
                    reject(new Error('DUAViz CAS request timed out.'));
                }, 2500);
                pendingRequests.set(requestId, {
                    resolve: value => {
                        window.clearTimeout(timer);
                        resolve(value);
                    },
                    reject: error => {
                        window.clearTimeout(timer);
                        reject(error);
                    }
                });
                pendingRequestQueue.push(String(requestId));
                ws.send(JSON.stringify(frame));
            });
        } catch (error) {
            if (['types', 'instancesByType', 'select', 'fs', 'span', 'graph'].includes(action)) {
                const fallbackPayload = Object.assign({}, payload || {});
                return await directCasQuery(Object.assign({}, fallbackPayload, {action, op: action}));
            }
            throw error;
        }
    }

    async function duaHttpRequest(action, payload) {
        const op = String(action || payload && (payload.op || payload.action) || '').trim();
        const request = Object.assign({}, payload || {});
        let path = '';
        const params = {};
        if (op === 'types') {
            path = '/types';
        } else if (op === 'telemetry') {
            path = '/stats';
        } else if (op === 'select' || op === 'instancesByType') {
            path = '/query/fsrefs';
            const typeName = request.type || request.fallbackType || casTypeName(request.typeCode) || '';
            if (!typeName) throw new Error('DUA HTTP select requires type or typeCode.');
            params.type = typeName;
            params.limit = request.limit || instancePageSize;
            params.includeSubtypes = request.includeSubtypes !== false;
        } else if (op === 'fs') {
            path = '/fs';
            params.fsRef = request.fsRef;
        } else if (op === 'span') {
            if (request.begin !== undefined || request.end !== undefined || request.predicate) {
                path = '/span/query';
                params.type = request.type || '';
                params.predicate = request.predicate || 'overlapping';
                params.sofaFsRef = request.sofaFsRef || 0;
                params.begin = request.begin || 0;
                params.end = request.end || Number.MAX_SAFE_INTEGER;
                params.limit = request.limit || 100;
                params.includeSubtypes = request.includeSubtypes !== false;
            } else {
                path = '/span';
                params.fsRef = request.fsRef;
            }
        } else {
            throw new Error('No DUA HTTP mapping for op ' + op);
        }
        const query = new URLSearchParams();
        Object.entries(params).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
        });
        query.set('t', String(Date.now()));
        const url = duavizHttpEndpoint() + path + '?' + query.toString();
        const response = await fetch(url, {cache: 'no-store', mode: 'cors'});
        if (!response.ok) throw new Error('DUA HTTP ' + response.status + ' for ' + path);
        const result = await response.json();
        if (op === 'types') return {op: 'types', types: result.types || []};
        if (op === 'telemetry') return {op: 'telemetry', stats: result};
        if (op === 'select' || op === 'instancesByType') return {op: op, fsRefs: result.fsRefs || []};
        return Object.assign({op: op}, result);
    }

    async function cachedFsQuery(fsRef) {
        const resolvedRef = Number(fsRef);
        if (!Number.isFinite(resolvedRef) || resolvedRef <= 0) return null;
        const key = String(resolvedRef);
        if (state.fsCache.has(key)) return state.fsCache.get(key);
        const staticPayload = await loadStaticCorpus();
        if (staticPayload && staticPayload.useStaticFallback && staticTypeIndex) {
            const staticFs = staticTypeIndex.fsById.get(key);
            if (staticFs && typeof staticFs === 'object') {
                const normalized = Object.assign({}, staticFs);
                normalized._id = Number(normalized._id || staticFs.fsId || resolvedRef);
                normalized.features = Object.assign({}, staticFs.features || {});
                if (!normalized._type) {
                    normalized._type = String(staticFs.typeName || staticFs.type || staticFs._type || '');
                }
                if (!normalized.typeCode) {
                    normalized.typeCode = Number(staticFs.typeCode || stateTypeCodeByName(normalized._type) || 0);
                }
                state.fsCache.set(key, normalized);
                return normalized;
            }
        }
        const result = await wsQuery('fs', {fsRef: resolvedRef});
        if (result && typeof result === 'object') state.fsCache.set(key, result);
        return result;
    }

    async function cachedSpanQuery(fsRef) {
        const resolvedRef = Number(fsRef);
        if (!Number.isFinite(resolvedRef) || resolvedRef <= 0) return null;
        const key = String(resolvedRef);
        if (state.spanCache.has(key)) return state.spanCache.get(key);
        const staticPayload = await loadStaticCorpus();
        if (staticPayload && staticPayload.useStaticFallback && staticTypeIndex) {
            const staticFs = staticTypeIndex.fsById.get(key);
            if (staticFs && typeof staticFs === 'object') {
                const directBegin = Number(staticFs.begin ?? (staticFs.features && staticFs.features.begin));
                const directEnd = Number(staticFs.end ?? (staticFs.features && staticFs.features.end));
                const begin = Number.isFinite(directBegin) ? directBegin : 0;
                const end = Number.isFinite(directEnd) ? directEnd : 0;
                const cached = {
                    fsRef: resolvedRef,
                    fsId: resolvedRef,
                    begin,
                    end,
                    coveredText: String(staticFs.coveredText || staticFs.features && staticFs.features.coveredText || staticFs.text || '')
                };
                state.spanCache.set(key, cached);
                return cached;
            }
        }
        let result = null;
        try {
            result = await wsQuery('span', {fsRef: resolvedRef});
        } catch (wsError) {
            result = null;
        }
        if (result && typeof result === 'object') state.spanCache.set(key, result);
        return result;
    }

    async function loadCasTypes() {
        const staticPayload = await loadStaticCorpus();
        if (staticPayload && staticPayload.useStaticFallback && staticPayload.types && Array.isArray(staticPayload.types) && staticPayload.types.length) {
            return {schema: {types: staticPayload.types}};
        }
        try {
            const message = await wsQuery('types', {});
            const schema = message && (message.types || (message.schema && message.schema.types));
            if (Array.isArray(schema) && schema.length) return {schema: {types: schema}};
            return message;
        } catch (error) {
            return directCasTypes();
        }
    }

    async function resolveDirectCasWsEndpoint() {
        if (directCasEndpointFromTelemetry) return directCasEndpointFromTelemetry;
        directCasEndpointFromTelemetry = duavizWsEndpoint();
        window.uceDuavizCasWsUrl = directCasEndpointFromTelemetry;
        return directCasEndpointFromTelemetry;
    }

    function directCasQueryOnce(url, payload) {
        return new Promise((resolve, reject) => {
            const ws = new WebSocket(url);
            const frame = buildDuavizFrame(null, payload);
            const timer = window.setTimeout(() => {
                try { ws.close(); } catch (error) {}
                reject(new Error('CAS websocket timed out.'));
            }, 2000);
            ws.onopen = () => ws.send(JSON.stringify(frame));
            ws.onerror = () => {
                window.clearTimeout(timer);
                reject(new Error('CAS websocket failed.'));
            };
            ws.onmessage = event => {
                try {
                    const message = JSON.parse(event.data || '{}');
                    if (isReadyFrame(message)) return;
                    window.clearTimeout(timer);
                    try { ws.close(); } catch (error) {}
                    if (isErrorFrame(message)) {
                        reject(new Error(message.message || message.error || 'DUAViz CAS request failed.'));
                        return;
                    }
                    resolve(message);
                } catch (parseError) {
                    window.clearTimeout(timer);
                    try { ws.close(); } catch (error) {}
                    reject(parseError);
                }
            };
        });
    }

    function directCasQuery(payload) {
        if (!payload || typeof payload !== 'object') return Promise.reject(new Error('Invalid CAS query payload.'));
        return new Promise(async (resolve, reject) => {
            const action = String(payload.action || payload.op || '').trim();
            const httpMapped = ['types', 'telemetry', 'select', 'instancesByType', 'fs', 'span'].includes(action);
            if (action) {
                try {
                    resolve(await duaHttpRequest(action, payload));
                    return;
                } catch (httpError) {
                    if (httpMapped && duavizHttpEndpoint()) {
                        reject(httpError);
                        return;
                    }
                }
            }
            const endpoint = await resolveDirectCasWsEndpoint();
            try {
                const response = await directCasQueryOnce(endpoint, payload);
                resolve(response);
            } catch (error) {
                reject(error || new Error('DUA websocket endpoint did not respond.'));
            }
        });
    }

    async function directCasTypes() {
        let lastError = null;
        try {
            const response = await fetch(duavizHttpEndpoint() + '/duaviz/schema', {
                cache: 'no-store',
                headers: {'Accept': 'application/json'}
            });
            if (!response.ok) throw new Error('DUA schema HTTP ' + response.status);
            const schema = await response.json();
            const types = Array.isArray(schema && schema.types) ? schema.types : [];
            if (types.length) return {schema: {types: directCasTypeRows(types)}};
        } catch (error) {
            lastError = error;
        }
        try {
            const message = await directCasQuery({op: 'types'});
            const typeNames = normalizeWsTypePayload(message);
            if (typeNames.length) return {schema: {types: directCasTypeRows(typeNames)}};
        } catch (error) {
            lastError = error;
        }
        throw lastError || new Error('CAS type loading failed.');
    }

    function normalizeWsTypePayload(message) {
        if (Array.isArray(message)) return message;
        if (message && message.schema && Array.isArray(message.schema.types)) return message.schema.types;
        if (message && message.selection && message.selection.types && Array.isArray(message.selection.types)) return message.selection.types;
        if (message && message.data && Array.isArray(message.data.types)) return message.data.types;
        if (message && message.data && message.data.schema && Array.isArray(message.data.schema.types)) return message.data.schema.types;
        if (message && message.payload && Array.isArray(message.payload.types)) return message.payload.types;
        if (message && Array.isArray(message.types)) return message.types;
        if (message && Array.isArray(message.selection)) return message.selection;
        if (message && message.selection && message.selection.selection && Array.isArray(message.selection.selection.types)) return message.selection.selection.types;
        if (message && Array.isArray(message.data)) return message.data;
        return [];
    }

    function directCasTypeRows(typeNames) {
        const seen = new Set();
        return typeNames
            .map(entry => {
                if (typeof entry === 'string') return casTypeRow(entry);
                if (entry && typeof entry === 'object') {
                    const name = entry.name || entry.typeName || entry.type || null;
                    if (!name) return null;
                    return Object.assign(casTypeRow(name) || {}, entry, {
                        name,
                        label: entry.label || shortLabel(name),
                        typeCode: Number(entry.typeCode || casTypeCode(name)),
                        superTypeCode: Number(entry.superTypeCode || casSuperTypeCode(name)),
                        superTypeName: entry.superTypeName || casSuperTypeName(name),
                        artifact: entry.artifact === true || casArtifactType(name),
                        annotation: entry.annotation === true || casAnnotationType(name),
                        features: Array.isArray(entry.features) ? entry.features : casDisplayFeatures(Number(entry.typeCode || casTypeCode(name)), name, entry.annotation === true || casAnnotationType(name), entry.artifact === true || casArtifactType(name))
                    });
                }
                return null;
            })
            .filter(row => {
                const text = String(row && row.name || '');
                if (!text || seen.has(text)) return false;
                seen.add(text);
                return true;
            })
            .filter(Boolean);
    }

    function casTypeRow(typeName) {
        const code = casTypeCode(typeName);
        if (!code) return null;
        const annotation = casAnnotationType(typeName);
        const artifact = casArtifactType(typeName);
        return {
            typeCode: code,
            name: typeName,
            label: shortLabel(typeName),
            superTypeCode: casSuperTypeCode(typeName),
            superTypeName: '',
            annotation,
            artifact,
            instanceCount: 0,
            features: casDisplayFeatures(code, typeName, annotation, artifact)
        };
    }

    const fallbackTypeCodeCache = new Map();
    function casTypeCode(typeName) {
        const mapped = ({
        })[String(typeName || '')];
        if (mapped) return mapped;
        const name = String(typeName || '').trim();
        if (!name) return 0;
        const cached = fallbackTypeCodeCache.get(name);
        if (cached) return cached;
        let hash = 0x811c9dc5;
        for (let i = 0; i < name.length; i++) {
            hash ^= name.charCodeAt(i);
            hash = (hash * 0x01000193) >>> 0;
        }
        const generated = 5000 + (hash % 60000);
        fallbackTypeCodeCache.set(name, Number(generated));
        return generated;
    }

    function casSuperTypeCode(typeName) {
        const name = String(typeName || '');
        if (name === 'uima.cas.TOP') return 0;
        const parentName = casSuperTypeName(name);
        return parentName ? casTypeCode(parentName) : 0;
    }

    function casSuperTypeName(typeName) {
        const name = String(typeName || '');
        if (name === 'uima.cas.TOP') return '';
        if (name === 'org.texttechnologylab.annotations.dua.Artifact') return 'uima.cas.TOP';
        if (name === 'org.texttechnologylab.annotations.dua.Corpus' || name === 'org.texttechnologylab.annotations.dua.Document') return 'org.texttechnologylab.annotations.dua.Artifact';
        if ([
            'org.texttechnologylab.annotations.dua.biofid.BIOfidCollection',
            'org.texttechnologylab.annotations.dua.biofid.BIOfidJournal',
            'org.texttechnologylab.annotations.dua.biofid.BIOfidVolume',
            'org.texttechnologylab.annotations.dua.biofid.BIOfidIssue'
        ].includes(name)) return 'org.texttechnologylab.annotations.dua.Corpus';
        if (name === 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle') return 'org.texttechnologylab.annotations.dua.Document';
        if (name === 'org.texttechnologylab.annotations.dua.View' || name === 'org.texttechnologylab.annotations.dua.DUASofa') return 'uima.cas.TOP';
        if (name === 'uima.tcas.Annotation' || name === 'cas:NULL' || name === 'cas:FSArray' || name === 'cas:Sofa') return 'uima.cas.TOP';
        if (name === 'abbyy:Page') return 'abbyy:Document';
        if (name === 'abbyy:Block') return 'abbyy:Page';
        if (name === 'abbyy:Paragraph') return 'abbyy:Block';
        if (name === 'abbyy:Line') return 'abbyy:Paragraph';
        if (name === 'abbyy:Token') return 'abbyy:Line';
        if (['type4:Lemma', 'pos:POS', 'morph:MorphologicalFeatures', 'dependency:Dependency'].includes(name)) return 'type4:Token';
        if (name === 'dependency:ROOT') return 'dependency:Dependency';
        if (['type3:Date', 'type3:Location', 'type3:Person', 'type3:Organization', 'type3:Event', 'type3:Product'].includes(name)) return 'type3:NamedEntity';
        if (name === 'gnfinder:VerifiedTaxon') return 'gnfinder:Taxon';
        if (casAnnotationType(name)) return 'uima.tcas.Annotation';
        return 'uima.cas.TOP';
    }

    function casArtifactType(typeName) {
        const name = String(typeName || '');
        return name === 'org.texttechnologylab.annotations.dua.Artifact'
            || name === 'org.texttechnologylab.annotations.dua.Corpus'
            || name === 'org.texttechnologylab.annotations.dua.Document'
            || name.startsWith('org.texttechnologylab.annotations.dua.biofid.');
    }

    function isArtifactTypeName(typeName) {
        return /\bArtifact$/.test(String(typeName || ''));
    }

    function isCorpusTypeName(typeName) {
        return /[:.]Corpus$/.test(String(typeName || ''));
    }

    function isDocumentTypeName(typeName) {
        return /[:.]Document$/.test(String(typeName || '')) && !/[:.]DocumentMetaData$/.test(String(typeName || ''));
    }

    function isLikelyDocumentTypeName(typeName) {
        return isDocumentTypeName(typeName) || /(^|[:.])Document$/.test(String(typeName || ''));
    }

    function casAnnotationType(typeName) {
        const name = String(typeName || '');
        if (!name || casArtifactType(name)) return false;
        if (name === 'uima.cas.TOP' || name === 'cas:NULL' || name === 'cas:FSArray' || name === 'cas:Sofa') return false;
        return !name.startsWith('uima.cas.');
    }

    function casDisplayFeatures(typeCode, typeName, annotation, artifact) {
        if (annotation) return [{featureCode: typeCode * 1000 + 1, name: 'begin'}, {featureCode: typeCode * 1000 + 2, name: 'end'}];
        if (!artifact) return [];
        const features = [{featureCode: typeCode * 1000 + 1, name: 'artifactId'}, {featureCode: typeCode * 1000 + 2, name: 'title'}, {featureCode: typeCode * 1000 + 3, name: 'uri'}];
        if (isCorpusTypeName(typeName)) features.push({featureCode: typeCode * 1000 + 4, name: 'documents'});
        if (isLikelyDocumentTypeName(typeName)) features.push({featureCode: typeCode * 1000 + 5, name: 'text'});
        return features;
    }

    async function initializeDuaviz() {
        if (!root().length) return;
        if (root().data('initialized')) {
            if (!state.types.length) {
                await refresh();
            }
            await preloadTopLevelInstances();
            renderAll();
            return;
        }
        root().data('initialized', true);
        ensureHighlighterPlacement();
        renderVizTools();
        bind();
        setSidebarLoadingState('Loading CAS type system...');
        await refresh();
        await preloadTopLevelInstances();
        applyRouteSelection();
    }

    async function preloadTopLevelInstances() {
        const candidates = state.types
            .filter(type => type && type.artifact)
            .filter(type => type.name !== 'org.texttechnologylab.annotations.dua.Artifact')
            .filter(type => shouldDeriveDocumentInstances(type) || isDocumentTypeName(type.name) || isCorpusTypeName(type.name))
            .slice(0, 8);
        const concurrency = 4;
        for (let index = 0; index < candidates.length; index += concurrency) {
            const batch = candidates.slice(index, index + concurrency);
            await Promise.allSettled(batch.map(type => loadInstancePage(type.typeCode, 0)));
        }
        renderAll();
    }

    function ensureVisibleSurface() {
        if (!root().length || state.types.length) return;
        if ($('.duaviz-artifact-tree').text().includes('Loading') || $('.duaviz-annotation-type-tree').text().includes('Loading')) return;
        refresh();
    }

    async function openFirstReadableDocument() {
        if (state.document) return;
        try {
            const sofaRefs = await prioritizeTextSofaRefs(await directCasSelectRefs('cas:Sofa', 160));
            for (const fsId of sofaRefs) {
                const document = await documentForArtifact(fsId).catch(() => null);
                const text = String(document && document.document && document.document.text || '').trim();
                if (!document || !text || text === 'No document text available.') continue;
                state.selectedArtifact = {
                    fsId,
                    typeCode: casTypeCode('cas:Sofa'),
                    typeName: 'cas:Sofa',
                    artifactKind: 'document',
                    name: document.document.title,
                    title: document.document.title
                };
                $('.duaviz-selection-title').text(document.document.title || 'Document reader');
                state.document = document;
                state.mode = 'reader';
                state.selectedView = state.document && state.document.selectedView || '_InitialView';
                state.checkedAnnotationTypes = new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
                syncDocumentStructureTypes(state.document);
                expandDocumentAnnotationTypes();
                renderReader();
                renderAccordions();
                return;
            }
        } catch (error) {
        }
        const staticTypeCodes = getStaticDocumentTypeCodesSync();
        const candidateTypes = (state.types || []).filter(type => isLikelyDocumentTypeName(type.name) || type.name === 'cas:Sofa');
        if (staticTypeCodes && staticTypeCodes.size) {
            (state.types || []).forEach(type => {
                if (staticTypeCodes.has(type.typeCode) && candidateTypes.indexOf(type) < 0) {
                    candidateTypes.push(type);
                }
            });
        }
        for (const type of candidateTypes) {
            let page = state.instancesByType.get(type.typeCode);
            if (!page || !Array.isArray(page.instances) || !page.instances.length) {
                try {
                    page = await duavizInstancesByTypePage(type, 0, instancePageSize);
                    state.instancesByType.set(type.typeCode, page);
                } catch (error) {
                    continue;
                }
            }
            const instance = page && Array.isArray(page.instances) ? page.instances.find(item => Number(item.fsId || 0) > 0) : null;
            if (!instance) continue;
            try {
                await selectArtifact(Object.assign({}, instance, {artifactKind: 'document'}));
                return;
            } catch (error) {
                continue;
            }
        }
    }

    async function refresh() {
        setStatus('loading');
        setSidebarStatus('Loading Corpus CAS');
        if (!state.types.length) {
            setSidebarLoadingState('Loading type hierarchy...');
        }
        try {
            const response = await loadCasTypes();
            const loadedTypes = normalizeTypes(response);
            if (!loadedTypes.length) throw new Error('No types returned by CAS service.');
            state.types = loadedTypes;
            state.children = buildChildren(state.types);
            state.instancesByType.clear();
            state.typePageOffsets.clear();
            if (!state.expandedTypes.size) defaultExpandedTypes().forEach(typeCode => state.expandedTypes.add(typeCode));
            state.loadingTypes.clear();
            state.checkedAnnotationTypes.clear();
            state.mode = 'reader';
            renderAll();
            setSidebarStatus('Corpus CAS loaded');
            setStatus('ready');
        } catch (error) {
            state.types = [];
            state.children = new Map();
            state.instancesByType.clear();
            state.typePageOffsets.clear();
            state.expandedTypes.clear();
            state.loadingTypes.clear();
            const message = error && error.message ? error.message : 'Failed to load live CAS data.';
            setStatus(message);
            setSidebarStatus('Corpus CAS unavailable');
            renderError(message);
            showToast(message);
            state.mode = 'reader';
            setStatus('ready');
        }
    }

    function setSidebarLoadingState(message) {
        const normalizedMessage = String(message || 'Loading...');
        $('.duaviz-artifact-tree').html('<div class="duaviz-empty-line">' + escapeHtml(normalizedMessage) + '</div>');
        $('.duaviz-annotation-type-tree').html('<div class="duaviz-empty-line">' + escapeHtml(normalizedMessage) + '</div>');
    }

    function setSidebarStatus(message) {
        $('.duaviz-sidebar .duaviz-panel-header .duaviz-kicker').text(String(message || 'Corpus CAS'));
    }

    function bind() {
        $('body').on('click', '.duaviz-refresh-btn', refresh);
        $('body').on('input', '.duaviz-type-filter', renderAll);
        $('body').on('click', '.duaviz-accordion-toggle', function () {
            const section = String($(this).data('section') || '');
            if (state.expandedSections.has(section)) state.expandedSections.delete(section);
            else state.expandedSections.add(section);
            renderAll();
        });
        $('body').on('click', '.duaviz-type-row', async function (event) {
            event.preventDefault();
            event.stopPropagation();
            if ($(event.target).is('input')) return;
            const typeCode = Number($(this).data('type-code'));
            const section = String($(this).data('section') || '');
            if (section === 'artifact') {
                if (state.expandedTypes.has(typeCode) && shouldDeriveDocumentInstances(findType(typeCode)) && !state.instancesByType.has(typeCode)) {
                    await ensureDerivedDocumentInstances(typeCode);
                    renderAll();
                    return;
                }
                if (state.expandedTypes.has(typeCode)) state.expandedTypes.delete(typeCode);
                else {
                    state.expandedTypes.add(typeCode);
                    renderAll();
                    if (shouldDeriveDocumentInstances(findType(typeCode))) {
                        await ensureDerivedDocumentInstances(typeCode);
                    }
                }
                renderAll();
                return;
            }
            if (activeAnchorIsTypeReference()) bindTypeSlot(typeCode);
            if (section === 'annotation' && annotationFeatureSelectMode()) {
                state.expandedTypes.add(typeCode);
                renderAll();
                return;
            }
            if (state.expandedTypes.has(typeCode)) {
                if (section === 'annotation' && !state.instancesByType.has(typeCode)) {
                    await ensureInstances(typeCode);
                    renderAll();
                    return;
                }
                state.expandedTypes.delete(typeCode);
                renderAll();
            } else {
                state.expandedTypes.add(typeCode);
                renderAll();
                if (section === 'annotation' && !annotationFeatureSelectMode()) {
                    await ensureInstances(typeCode);
                }
                renderAll();
            }
        });
        $('body').on('click', '.duaviz-instance-row', async function (event) {
            event.stopPropagation();
            if ($(this).data('page-dir')) {
                const typeCode = Number($(this).data('type-code'));
                if ($(this).hasClass('duaviz-instance-more')) {
                    const page = state.instancesByType.get(typeCode);
                    if (page && Array.isArray(page.allInstances)) {
                        const current = Number(page.visibleLimit || page.limit || treeDropdownPageSize());
                        page.visibleLimit = Math.min(page.allInstances.length, current + treeDropdownPageSize());
                        page.instances = page.allInstances.slice(0, page.visibleLimit);
                        page.hasMore = page.visibleLimit < page.allInstances.length;
                        renderAll();
                        return;
                    }
                }
                await loadInstancePage(typeCode, Number($(this).data('page-dir')));
                renderAll();
                return;
            }
            const artifact = findInstance(Number($(this).data('fs-id')));
            if (!artifact) return;
            if (artifact.artifactKind === 'annotation') {
                bindSourceAnchorFromAnnotationInstance(artifact);
                toggleAnnotationInstance(artifact);
                return;
            }
            await selectArtifact(artifact);
        });
        $('body').on('click', '.duaviz-doc-structure-row', function () {
            const fsId = Number($(this).data('fs-id'));
            const structure = findDocumentStructureInstance(fsId);
            if (structure) {
                renderFeaturePanel(structure);
                renderDocumentText();
            }
        });
        $('body').on('click', '.duaviz-document-card', async function () {
            const fsId = Number($(this).data('fs-id'));
            if (!fsId) return;
            const derived = state.documentFallbackDocuments.find(item => Number(item.fsId) === fsId);
            if (derived && derived.document) {
                state.selectedArtifact = derived.artifact;
                state.document = derived.document;
                state.mode = 'reader';
                state.selectedView = state.document && state.document.selectedView || '_InitialView';
                state.checkedAnnotationTypes = new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
                renderFeaturePanel(derived.artifact);
                syncDocumentStructureTypes(state.document);
                expandDocumentAnnotationTypes();
                renderReader();
                renderAccordions();
                return;
            }
            await selectArtifact(derived && derived.artifact ? derived.artifact : {
                fsId,
                typeCode: casTypeCode('cas:Sofa'),
                typeName: 'cas:Sofa',
                artifactKind: 'document',
                name: 'Document ' + fsId,
                title: 'Document ' + fsId
            });
        });
        $('body').on('click', '.duaviz-document-gallery-page', async function () {
            const direction = Number($(this).data('page-dir') || 0);
            state.documentFallbackOffset = Math.max(0, state.documentFallbackOffset + direction * documentFallbackPageSize());
            renderDocumentFallbackGallery();
        });
        $('body').on('click', '.duaviz-span-color-swatch', function (event) {
            event.stopPropagation();
            const fsId = Number($(this).closest('.duaviz-span-color-popover').data('fs-id') || 0);
            const color = String($(this).data('color') || '');
            if (!fsId || !color) return;
            state.spanColorOverrides.set(fsId, color);
            renderDocumentText();
        });
        $('body').on('click', '.duaviz-view-chip', function () {
            state.selectedView = String($(this).data('view') || '_InitialView');
            renderReader();
            renderAccordions();
        });
        $('body').on('click', '.duaviz-viz-card', function () {
            const componentId = String($(this).data('component') || 'highlighter');
            state.selectedVizComponent = componentId;
            state.selectedVizInstance = ensureVizInstance(componentId).id;
            state.vizDetailOpen = true;
            const component = selectedVizComponent();
            state.activeVizAnchor = component && component.anchors.length ? component.anchors[0].id : null;
            renderVizTools();
        });
        $('body').on('click', '.duaviz-viz-instance-row', function () {
            state.selectedVizInstance = String($(this).data('instance') || state.selectedVizInstance);
            const instance = selectedVizInstance();
            state.selectedVizComponent = instance ? instance.componentId : state.selectedVizComponent;
            state.vizDetailOpen = false;
            renderVizTools();
            renderAccordions();
        });
        $('body').on('click', '.duaviz-anchor-row', function () {
            state.selectedVizComponent = String($(this).data('component') || state.selectedVizComponent);
            state.activeVizAnchor = String($(this).data('anchor') || '');
            renderVizTools();
            renderAccordions();
        });
        $('body').on('click', '.duaviz-feature-slot-row', function (event) {
            event.stopPropagation();
            bindFeatureSlot({
                typeCode: Number($(this).data('type-code')),
                typeName: String($(this).data('type-name') || ''),
                featureCode: Number($(this).data('feature-code')),
                featureName: String($(this).data('feature-name') || ''),
                featureRange: String($(this).data('feature-range') || ''),
                primitiveKind: String($(this).data('primitive-kind') || 'primitive')
            });
        });
        $('body').on('input', '.duaviz-viz-opacity', function () {
            const instance = selectedVizInstance();
            if (!instance) return;
            instance.config.opacity = Number($(this).val() || 52);
            renderDocumentText();
            renderVizTools();
        });
        $('body').on('click', '.duaviz-color-swatch', function () {
            const color = String($(this).data('color') || '');
            const instance = selectedVizInstance();
            if (!instance || !color) return;
            instance.config.color = color;
            renderDocumentAnnotationList();
            renderDocumentText();
            renderVizTools();
        });
        $('body').on('input', '.duaviz-pointer-limit', function () {
            const instance = selectedVizInstance();
            if (!instance) return;
            instance.config.spanLimit = Math.max(4, Math.min(300, Number($(this).val() || 300)));
            renderVizTools();
        });
        $('body').on('click', '.duaviz-mode-btn', function () {
            state.mode = String($(this).data('mode') || 'artifact');
            renderMode();
        });
        $('body').on('change', '.duaviz-annotation-checkbox', function () {
            const typeCode = Number($(this).data('type-code'));
            if (this.checked) state.checkedAnnotationTypes.add(typeCode);
            else state.checkedAnnotationTypes.delete(typeCode);
            renderAccordions();
            renderDocumentAnnotationList();
        });
        $('body').on('input', '.duaviz-annotation-opacity', renderVizTools);
        $('body').on('click', '.duaviz-span', function () {
            const fsId = Number($(this).data('fs-id'));
            const span = (state.document && state.document.spans || []).find(item => Number(item.fsId) === fsId);
            if (span) renderFeaturePanel(span);
        });
    }

    async function ensureInstances(typeCode) {
        if (state.instancesByType.has(typeCode) || state.loadingTypes.has(typeCode)) return;
        await loadInstancePage(typeCode, 0);
    }

    async function loadInstancePage(typeCode, direction) {
        if (state.loadingTypes.has(typeCode)) return;
        const currentOffset = Number(state.typePageOffsets.get(typeCode) || 0);
        const nextOffset = direction === 0 ? currentOffset : Math.max(0, currentOffset + (Number(direction) * instancePageSize));
        state.loadingTypes.add(typeCode);
        renderAll();
        try {
            const page = await loadInstances(typeCode, nextOffset, instancePageSize);
            if (isErrorPayload(page)) throw new Error(page.message || 'Corpus select query failed.');
            state.typePageOffsets.set(typeCode, Number(page.offset || nextOffset));
            state.instancesByType.set(typeCode, page);
        } catch (error) {
            setStatus('error');
            state.instancesByType.set(typeCode, emptyPage());
        } finally {
            state.loadingTypes.delete(typeCode);
        }
    }

    async function loadInstances(typeCode, offset, limit) {
        const type = findType(Number(typeCode));
        try {
            const direct = await directCasInstances(type, offset, limit);
            if (direct && Array.isArray(direct.instances) && (direct.instances.length || Number(direct.total || 0) > 0)) return direct;
        } catch (error) {
        }
        try {
            const uiPage = await duavizInstancesByTypePage(type, Number(offset || 0), Number(limit || instancePageSize));
            if (uiPage && Array.isArray(uiPage.instances)) return uiPage;
        } catch (error) {
            return emptyPage();
        }
        return emptyPage();
    }

    async function duavizInstancesByTypePage(type, offset, limit) {
        if (!type || !type.typeCode) return null;
        const boundedOffset = Math.max(0, Number(offset || 0));
        const boundedLimit = Math.max(1, Math.min(Number(limit || instancePageSize), instancePageSize));
        const message = await typeInstancesBySelector({
            typeCode: Number(type.typeCode),
            type: type.name,
            includeSubtypes: true,
            offset: boundedOffset,
            limit: boundedLimit
        }, type);
        const pagePayload = message && message.page ? message.page
            : message && message.payload && message.payload.page ? message.payload.page
            : message && message.data && message.data.page ? message.data.page
            : (message && Array.isArray(message.instances) ? message : null);
        if (!pagePayload || !Array.isArray(pagePayload.instances)) return null;
        const cas = pagePayload.cas instanceof Map ? pagePayload.cas : buildCasIndex(pagePayload.cas);
        return {
            instances: pagePayload.instances.map(instance => duavizInstanceRow(type, instance, cas)).filter(Boolean),
            offset: Number(pagePayload.offset || boundedOffset),
            limit: Number(pagePayload.limit || boundedLimit),
            total: Number(pagePayload.total || pagePayload.instances.length || 0),
            hasMore: !!pagePayload.hasMore
        };
    }

    async function typeInstancesBySelector(payload, type) {
        try {
            const message = await wsQuery('instancesByType', {
                type: type ? type.name : String(payload.type || ''),
                typeCode: Number(payload.typeCode || 0),
                includeSubtypes: true,
                limit: Number(payload.limit || instancePageSize),
                offset: Number(payload.offset || 0)
            });
            const direct = parseTypePage(message);
            if (direct) return {
                page: {
                    instances: direct.instances,
                    cas: direct.cas,
                    total: direct.total,
                    hasMore: direct.hasMore,
                    offset: Number(payload.offset || 0),
                    limit: Number(payload.limit || instancePageSize)
                }
            };
            const refs = parseSelectionRefs(message);
            const rowsPage = await rowsForTypeRefs(type, refs, Number(payload.offset || 0), Number(payload.limit || instancePageSize));
            return rowsPage ? {page: rowsPage} : null;
        } catch (error) {
            return null;
        }
    }

    function boundedNumeric(value, fallback) {
        const numeric = Number(value);
        return Number.isFinite(numeric) ? Math.max(0, Math.floor(numeric)) : fallback;
    }

    async function rowsForTypeRefs(type, refs, offset, limit) {
        const boundedOffset = boundedNumeric(offset, 0);
        const boundedLimit = Math.max(1, Math.min(Math.floor(boundedNumeric(limit, instancePageSize)), instancePageSize));
        const refList = Array.isArray(refs) ? refs : [];
        const windowed = refList.slice(boundedOffset, boundedOffset + boundedLimit);
        const rows = [];
        const concurrency = 16;
        for (let index = 0; index < windowed.length; index += concurrency) {
            const batch = windowed.slice(index, index + concurrency);
            const loaded = await Promise.allSettled(batch.map(fsRef => hydrateInstanceRowFromRef(type, fsRef)));
            loaded.forEach(result => {
                if (result.status === 'fulfilled' && result.value) rows.push(result.value);
            });
        }
        return {
            instances: rows,
            total: refList.length,
            hasMore: refList.length > boundedOffset + boundedLimit,
            offset: boundedOffset,
            limit: boundedLimit
        };
    }

    async function hydrateInstanceRowFromRef(type, fsRef) {
        const resolvedType = type || {};
        const normalizedRef = Number(fsRef);
        if (!Number.isFinite(normalizedRef) || normalizedRef <= 0) return null;
        const fsPayload = await cachedFsQuery(normalizedRef);
        const spanPayload = resolvedType.annotation ? await cachedSpanQueryFast(normalizedRef).catch(() => null) : null;
        const resolvedTypeCode = Number(fsPayload && fsPayload.typeCode || resolvedType.typeCode || 0);
        const resolvedTypeName = String(fsPayload && (fsPayload.type || resolvedType.name || '') || '');
        const features = Object.assign({}, fsPayload && fsPayload.features || {});
        const begin = numericFeatureValue(spanPayload && spanPayload.begin, features.begin);
        const end = numericFeatureValue(spanPayload && spanPayload.end, features.end);
        const valueLabel = instanceFeatureValueLabel(features);
        const typeLabel = shortLabel(resolvedTypeName || resolvedType.name || 'Feature structure');
        return {
            fsId: normalizedRef,
            typeCode: resolvedTypeCode,
            typeName: resolvedTypeName,
            artifactKind: resolveInstanceArtifactKind(resolvedType, resolvedTypeName || resolvedType.name),
            name: valueLabel ? typeLabel + ' ' + valueLabel : typeLabel,
            title: valueLabel || typeLabel,
            begin: Number.isFinite(begin) ? begin : undefined,
            end: Number.isFinite(end) ? end : undefined,
            fs: {_id: normalizedRef, _type: resolvedTypeName || resolvedType.name, typeCode: resolvedTypeCode, features}
        };
    }

    function cachedSpanQueryFast(fsRef) {
        return Promise.race([
            cachedSpanQuery(fsRef),
            new Promise(resolve => window.setTimeout(() => resolve(null), 450))
        ]);
    }

    function parseTypePage(message) {
        const nestedSelection = message && message.selection && !Array.isArray(message.selection) && typeof message.selection === 'object'
            ? (message.selection.page && typeof message.selection.page === 'object' ? message.selection.page : message.selection)
            : null;
        const pagePayload = message && message.page ? message.page
            : message && message.selection && Array.isArray(message.selection.instances) ? message.selection
            : nestedSelection && nestedSelection.page ? nestedSelection.page
            : message && message.payload && message.payload.page ? message.payload.page
            : message && message.data && message.data.page ? message.data.page
            : message && message.result && message.result.page ? message.result.page
            : (Array.isArray(message && message.instances) ? message : null);
        if (!pagePayload || !Array.isArray(pagePayload.instances)) return null;
        return {
            instances: Array.isArray(pagePayload.instances) ? pagePayload.instances : [],
            total: Number(pagePayload.total || pagePayload.instances.length || 0),
            hasMore: !!pagePayload.hasMore,
            cas: buildCasIndex(pagePayload.cas)
        };
    }

    function parseSelectionRefs(message) {
        if (!message || typeof message !== 'object') return [];
        const coerceRefs = value => {
            if (Array.isArray(value)) {
                return value;
            }
            if (value && typeof value === 'object') {
                if (Array.isArray(value.instances)) return value.instances.map(item => {
                    if (Number.isFinite(Number(item))) return Number(item);
                    if (!item || typeof item !== 'object') return NaN;
                    return Number(item.fsId || item.id || item._id || item.rootFsId || 0);
                }).filter(Number.isFinite);
            }
            return [];
        };

        const direct = coerceRefs(message.fsRefs)
            .concat(coerceRefs(message.instances))
            .concat(coerceRefs(message.selection))
            .concat(coerceRefs(message.page && message.page.instances))
            .concat(coerceRefs(message.page && message.page.selection))
            .concat(coerceRefs(message.payload && message.payload.fsRefs))
            .concat(coerceRefs(message.payload && message.payload.selection))
            .concat(coerceRefs(message.data && message.data.fsRefs))
            .concat(coerceRefs(message.data && message.data.selection));
        if (direct.length) return direct.map(Number).filter(Number.isFinite);

        const nestedSelection = message.selection && !Array.isArray(message.selection) && typeof message.selection === 'object' ? message.selection : null;
        const nestedSelectionRefs = nestedSelection ? coerceRefs(nestedSelection.fsRefs).concat(coerceRefs(nestedSelection.selection)) : [];
        if (nestedSelectionRefs.length) return nestedSelectionRefs.map(Number).filter(Number.isFinite);

        const nestedSelectionPage = nestedSelection && nestedSelection.page && typeof nestedSelection.page === 'object' ? nestedSelection.page : null;
        if (nestedSelectionPage) {
            const nestedPageRefs = coerceRefs(nestedSelectionPage.fsRefs).concat(coerceRefs(nestedSelectionPage.selection));
            if (nestedPageRefs.length) return nestedPageRefs.map(Number).filter(Number.isFinite);
        }

        const nestedMessagePage = message.page && !Array.isArray(message.page) ? message.page : null;
        const nestedPageRefs = nestedMessagePage ? coerceRefs(nestedMessagePage.fsRefs)
            .concat(coerceRefs(nestedMessagePage.selection))
            .concat(coerceRefs(nestedMessagePage.instances)) : [];
        if (nestedPageRefs.length) return nestedPageRefs.map(Number).filter(Number.isFinite);

        if (Array.isArray(message.entries)) return message.entries;
        return [];
    }

    function buildCasIndex(casCollection) {
        if (!Array.isArray(casCollection)) return new Map();
        const index = new Map();
        casCollection.forEach(entry => {
            if (!entry || typeof entry !== 'object') return;
            const id = Number(entry._id || entry.fsId || entry.id || 0);
            if (id) index.set(id, entry);
        });
        return index;
    }

    function resolveInstanceArtifactKind(type, typeName, artifactKind) {
        if (artifactKind) return artifactKind;
        const mappedTypeName = String(typeName || '');
        const isDocumentType = isDocumentTypeName(mappedTypeName);
        const isCorpusType = isCorpusTypeName(mappedTypeName);
        if (isCorpusType) return 'corpus';
        if (isDocumentType) return 'document';
        if (type && type.artifact) return isCorpusTypeName(type.name || '') ? 'corpus' : 'document';
        return type && type.annotation ? 'annotation' : 'feature-structure';
    }

    function duavizInstanceRow(type, instance, casIndex) {
        if (!instance || typeof instance !== 'object') return null;
        const mappedTypeCode = Number(instance.typeCode || type.typeCode || 0);
        const mappedTypeName = String(instance.typeName || type.name || casTypeName(mappedTypeCode) || '');
        const fsId = Number(instance.fsId || instance.fsRef || 0);
        const cas = casIndex && casIndex.get(fsId);
        const artifactKind = resolveInstanceArtifactKind(type, mappedTypeName, instance.artifactKind);
        return {
            fsId,
            typeCode: mappedTypeCode,
            typeName: mappedTypeName,
            artifactKind: String(artifactKind),
            name: String(instance.name || instance.title || instance.label || ''),
            title: String(instance.name || instance.title || instance.label || ''),
            fs: Object.assign({
                _id: fsId,
                _type: mappedTypeName,
                typeCode: mappedTypeCode,
                features: {}
            }, cas || {}, instance.fs ? {features: Object.assign({}, instance.fs.features || cas && cas.features || {})} : {})
        };
    }

    async function directCasInstances(type, offset, limit) {
        if (!type || !type.name) return emptyPage();
        const staticPayload = await loadStaticCorpus();
        if (staticPayload && staticPayload.useStaticFallback && staticTypeIndex && type.typeCode) {
            const staticRefs = (staticTypeIndex.instancesByType.get(Number(type.typeCode)) || []).map(instance =>
                Number(instance && (instance.fsId || instance._id || (instance.fs && instance.fs._id) || 0) || 0)
            ).filter(fsId => Number.isFinite(fsId) && fsId > 0);
            const ordered = Array.from(new Set(staticRefs)).sort((a, b) => a - b);
            if (ordered.length) {
                const pageRefs = ordered.slice(Math.max(0, Number(offset || 0)), Math.max(0, Number(offset || 0)) + Math.max(1, Math.min(Number(limit || instancePageSize), instancePageSize)));
                const instances = [];
                const concurrency = 16;
                for (let index = 0; index < pageRefs.length; index += concurrency) {
                    const batch = pageRefs.slice(index, index + concurrency);
                    const loaded = await Promise.allSettled(batch.map(fsRef => hydrateInstanceRowFromRef(type, fsRef)));
                    loaded.forEach((result, batchIndex) => {
                        if (result.status === 'fulfilled' && result.value) instances.push(result.value);
                        else instances.push(directCasInstanceRow(type, batch[batchIndex], selectType, boundedOffset + index + batchIndex));
                    });
                }
                return {
                    instances,
                    offset: Number(offset || 0),
                    limit: Math.max(1, Math.min(Number(limit || instancePageSize), instancePageSize)),
                    total: ordered.length,
                    hasMore: ordered.length > (Number(offset || 0) + Math.max(1, Math.min(Number(limit || instancePageSize), instancePageSize)))
                };
            }
        }
        const boundedOffset = Math.max(0, Number(offset || 0));
        const boundedLimit = Math.max(1, Math.min(Number(limit || instancePageSize), instancePageSize));
        let selectType = type.name;
        let refs = [];
        refs = await directCasSelectRefs(selectType, Math.max(boundedOffset + boundedLimit + 1, selectType === 'cas:Sofa' ? 160 : boundedLimit + 1));
        if (!refs.length && type.typeCode) {
            refs = await directCasSelectRefs(Number(type.typeCode), Math.max(boundedOffset + boundedLimit + 1, type.name === 'cas:Sofa' ? 160 : boundedLimit + 1), type.name);
        }
        if (selectType === 'cas:Sofa') {
            refs = await prioritizeTextSofaRefs(refs);
        }
        const pageRefs = refs.slice(boundedOffset, boundedOffset + boundedLimit);
        const instances = [];
        const concurrency = 16;
        for (let index = 0; index < pageRefs.length; index += concurrency) {
            const batch = pageRefs.slice(index, index + concurrency);
            const loaded = await Promise.allSettled(batch.map(fsRef => hydrateInstanceRowFromRef(type, fsRef)));
            loaded.forEach((result, batchIndex) => {
                if (result.status === 'fulfilled' && result.value) instances.push(result.value);
                else instances.push(directCasInstanceRow(type, batch[batchIndex], selectType, boundedOffset + index + batchIndex));
            });
        }
        return {
            instances,
            offset: boundedOffset,
            limit: boundedLimit,
            total: refs.length,
            hasMore: refs.length > boundedOffset + boundedLimit
        };
    }

    async function directCasSelectRefs(typeNameOrCode, limit, fallbackTypeName, includeSubtypes) {
        const baseLimit = Math.max(1, Math.min(Number(limit || instancePageSize), 10000));
        const request = {
            op: 'select',
            limit: baseLimit
        };
        if (Number.isFinite(Number(typeNameOrCode))) {
            request.typeCode = Number(typeNameOrCode);
        } else {
            request.type = String(typeNameOrCode || '');
        }
        if (fallbackTypeName && request.type !== fallbackTypeName) {
            request.fallbackType = String(fallbackTypeName);
        }
        if (!request.type && request.typeCode) {
            request.fallbackType = casTypeName(request.typeCode) || fallbackTypeName || '';
        }
        const messages = includeSubtypes
            ? [Object.assign({}, request, {includeSubtypes: true}), Object.assign({}, request, {includeSubtypes: false})]
            : [Object.assign({}, request, {includeSubtypes: false})];
        let message = null;
        for (const payload of messages) {
            try {
                message = await directCasQuery(payload);
            } catch (error) {
                message = null;
                continue;
            }
            const refs = parseSelectionRefs(message);
            if (refs.length) return refs.map(Number).filter(Number.isFinite);
        }
        const refs = message ? parseSelectionRefs(message) : [];
        const safeRefs = Array.isArray(refs) ? refs : [];
        return safeRefs.map(Number).filter(Number.isFinite);
    }

    async function prioritizeTextSofaRefs(refs) {
        const safeRefs = Array.from(new Set((refs || []).map(Number).filter(Number.isFinite)));
        if (!safeRefs.length) return [];
        const sample = safeRefs.slice(0, 160);
        const rows = [];
        const concurrency = 16;
        for (let index = 0; index < sample.length; index += concurrency) {
            const batch = sample.slice(index, index + concurrency);
            const settled = await Promise.allSettled(batch.map(fsRef => cachedFsQuery(fsRef)));
            settled.forEach((result, batchIndex) => {
                const fsRef = batch[batchIndex];
                const features = result.status === 'fulfilled' && result.value ? (result.value.features || {}) : {};
                const text = String(features.sofaString || features.text || '').trim();
                rows.push({fsRef, hasText: text.length > 0, textLength: text.length, sofaID: String(features.sofaID || '')});
            });
        }
        const ranked = rows.sort((a, b) => Number(b.hasText) - Number(a.hasText) || b.textLength - a.textLength || a.fsRef - b.fsRef).map(row => row.fsRef);
        const rankedSet = new Set(ranked);
        return ranked.concat(safeRefs.filter(fsRef => !rankedSet.has(fsRef)));
    }

    function firstDirectCasWsUrl() {
        return duavizWsEndpoint();
    }

    function directCasInstanceRow(type, fsRef, selectedTypeName, index) {
        const selectedSofa = selectedTypeName === 'cas:Sofa';
        const artifactKind = selectedSofa ? 'document' : resolveInstanceArtifactKind(type, type.name);
        const typeName = selectedSofa ? type.name : type.name;
        const label = selectedSofa
            ? 'Document view ' + (index + 1)
            : shortLabel(typeName) + ' fs ' + fsRef;
        return {
            fsId: fsRef,
            typeCode: type.typeCode,
            typeName,
            artifactKind,
            name: label,
            title: label,
            fs: {_id: fsRef, _type: typeName, typeCode: type.typeCode, features: {}}
        };
    }

    async function selectArtifact(artifact) {
        state.selectedArtifact = artifact;
        $('.duaviz-selection-title').text(instanceLabel(artifact));
        renderFeaturePanel(artifact);
        if (artifact.artifactKind === 'document') {
            state.mode = 'reader';
            state.corpusDocuments = [];
            state.document = await documentForArtifact(artifact.fsId);
            state.selectedView = state.document && state.document.selectedView || '_InitialView';
            state.checkedAnnotationTypes = new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
            syncDocumentStructureTypes(state.document);
            expandDocumentAnnotationTypes();
            renderReader();
            renderAccordions();
        } else if (artifact.artifactKind === 'corpus') {
            state.mode = 'reader';
            state.document = null;
            state.documentStructureTypes = [];
            state.documentStructureTypeCodes = [];
            state.checkedAnnotationTypes.clear();
            state.corpusDocuments = [];
            renderDocumentFallbackGallery();
            renderAccordions();
        } else {
            state.mode = 'reader';
            state.document = null;
            state.documentStructureTypes = [];
            state.documentStructureTypeCodes = [];
            state.corpusDocuments = [];
            state.checkedAnnotationTypes.clear();
            renderMode();
            renderAccordions();
        }
    }

    function renderAll() {
        renderAccordions();
        renderVizTools();
        renderMode();
    }

    function renderAccordions() {
        const filter = String($('.duaviz-type-filter').val() || '').toLowerCase();
        const sections = {
            artifact: artifactSectionRoots(),
            annotation: annotationSectionRoots()
        };
        expandTopTypeContainers(sections);
        normalizeTypeDetailsOnce(sections);
        renderAccordionSection('artifact', sections.artifact, '', '.duaviz-artifact-tree');
        renderAccordionSection('annotation', sections.annotation, filter, '.duaviz-annotation-type-tree', pointerAnnotationTreeFilterCodes());
        Object.entries(sections).forEach(([section, roots]) => {
            const open = state.expandedSections.has(section);
            const count = roots.reduce((sum, type) => sum + (state.children.get(type.typeCode) || []).reduce((childSum, child) => childSum + subtreeCount(child.typeCode), 0), 0);
            $('.duaviz-accordion[data-section="' + section + '"] .duaviz-count').text(count);
            $('.duaviz-accordion[data-section="' + section + '"] .duaviz-accordion-toggle i')
                .attr('class', 'fas ' + (open ? 'fa-chevron-down' : 'fa-chevron-right'));
        });
    }

    function renderAccordionSection(section, roots, filter, selector, allowedCodes) {
        if (!state.expandedSections.has(section) && !filter) {
            $(selector).empty();
            return;
        }
        const covered = new Set();
        const rows = [];
        roots.forEach(type => {
            if (!type) return;
            covered.add(type.typeCode);
            const children = state.children.get(type.typeCode) || [];
            children.forEach(child => appendTypeRows(section, child, 0, filter, covered, rows, allowedCodes));
        });
        $(selector).html(rows.join('') || '<div class="duaviz-empty-line">No matching types.</div>');
    }

    function appendTypeRows(section, type, depth, filter, covered, rows, allowedCodes) {
        const normalizedAllowedCodes = (allowedCodes instanceof Set) ? allowedCodes : null;
        if (section === 'annotation' && !branchHasBeginEndAnnotationType(type)) return;
        if (normalizedAllowedCodes && !type.synthetic && !branchHasAllowedType(type, normalizedAllowedCodes)) return;
        if (!type || covered.has(type.typeCode) || !branchMatches(type, filter)) return;
        covered.add(type.typeCode);
        const childTypes = state.children.get(type.typeCode) || [];
        const detailsExpanded = state.expandedTypes.has(type.typeCode);
        const hierarchyExpanded = true;
        const indent = 10 + Math.min(depth, 6) * 22;
        const expandIcon = '<i class="fas ' + (detailsExpanded ? 'fa-chevron-down' : 'fa-chevron-right') + '"></i>';
        const selectModeClass = section === 'annotation' && annotationFeatureSelectMode() ? ' feature-select-mode' : '';
        rows.push('<button class="duaviz-type-row ' + (detailsExpanded ? 'expanded' : '') + selectModeClass + '" type="button" data-section="' + escapeAttr(section) + '" data-type-code="' + type.typeCode + '" title="' + escapeAttr(type.name) + '" style="padding-left:' + indent + 'px">' +
            '<span>' + expandIcon + ' ' + escapeHtml(type.label || shortLabel(type.name)) + activeBeginEndBadge(section, type) + '</span>' +
        '</button>');
        if (detailsExpanded && section === 'annotation') {
            if (!annotationFeatureSelectMode()) {
                ensureAnnotationInstancesQueued(type.typeCode);
                rows.push(...annotationInstanceRows(type, depth + 1));
            }
            if (!type.synthetic) {
                rows.push(...featureSlotRows(type, depth + 1));
            }
        }
        if (detailsExpanded && section === 'artifact' && shouldDeriveDocumentInstances(type)) {
            ensureDerivedDocumentInstancesQueued(type.typeCode);
            rows.push(...artifactInstanceRows(type, depth + 1));
        }
        if (hierarchyExpanded || filter) {
            childTypes.forEach(child => appendTypeRows(section, child, depth + 1, filter, covered, rows, allowedCodes));
        }
    }

    function featureSlotRows(type, depth) {
        const features = ownSelectableFeatures(type);
        const indent = 32 + Math.min(depth, 6) * 22;
        const header = '<div class="duaviz-tree-subheading" style="padding-left:' + indent + 'px">FEATURES</div>';
        if (!features.length) {
            return [header, '<div class="duaviz-feature-slot-empty" style="padding-left:' + indent + 'px">No local features</div>'];
        }
        return [header].concat(features.map((feature, index) => {
            const featureName = feature.name || ('feature:' + index);
            const featureCode = Number(feature.featureCode || (type.typeCode * 1000 + index));
            const primitiveKind = primitiveKindForFeature(feature);
            const featureRange = feature.range || feature.type || feature.valueType || '';
            const bindable = activeAnchorAccepts(primitiveKind) && bindingFitsComponentScope(selectedVizComponent(), type.typeCode);
            const bound = isFeatureBound(type, featureName, featureCode);
            const focused = anchorFocusAllowsType(type.typeCode);
            return '<button class="duaviz-feature-slot-row ' + (bindable ? 'bindable' : '') + ' ' + (bound ? 'bound' : '') + ' ' + (!focused ? 'dimmed' : '') + '" type="button" data-type-code="' + type.typeCode + '" data-type-name="' + escapeAttr(type.name) + '" data-feature-code="' + featureCode + '" data-feature-name="' + escapeAttr(featureName) + '" data-feature-range="' + escapeAttr(featureRange) + '" data-primitive-kind="' + primitiveKind + '" style="padding-left:' + indent + 'px">' +
                '<span><i class="fas fa-code-branch"></i> ' + escapeHtml(shortLabel(featureName)) + '</span>' +
                '<span class="duaviz-primitive-kind">' + escapeHtml(primitiveKind) + '</span>' +
            '</button>';
        }));
    }

    function annotationInstanceRows(type, depth) {
        const indent = 32 + Math.min(depth, 6) * 22;
        return ['<div class="duaviz-tree-subheading instances" style="padding-left:' + indent + 'px">INSTANCES</div>'].concat(instanceRows(type, depth));
    }

    function artifactInstanceRows(type, depth) {
        const indent = 32 + Math.min(depth, 6) * 22;
        return ['<div class="duaviz-tree-subheading instances" style="padding-left:' + indent + 'px">DOCUMENTS</div>'].concat(instanceRows(type, depth, {moreLabel: '...'}));
    }

    function shouldDeriveDocumentInstances(type) {
        const name = String(type && type.name || '');
        return name === 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle' || isDocumentTypeName(name);
    }

    function ensureAnnotationInstancesQueued(typeCode) {
        const normalized = Number(typeCode);
        if (!Number.isFinite(normalized) || state.instancesByType.has(normalized) || state.loadingTypes.has(normalized)) return;
        window.setTimeout(() => {
            if (state.instancesByType.has(normalized) || state.loadingTypes.has(normalized)) return;
            ensureInstances(normalized).then(renderAll).catch(() => renderAll());
        }, 0);
    }

    function ensureDerivedDocumentInstancesQueued(typeCode) {
        const normalized = Number(typeCode);
        if (!Number.isFinite(normalized) || state.instancesByType.has(normalized) || state.loadingTypes.has(normalized)) return;
        window.setTimeout(() => ensureDerivedDocumentInstances(normalized).then(renderAll).catch(() => renderAll()), 0);
    }

    async function ensureDerivedDocumentInstances(typeCode) {
        const normalized = Number(typeCode);
        if (!Number.isFinite(normalized) || state.instancesByType.has(normalized) || state.loadingTypes.has(normalized)) return;
        state.loadingTypes.add(normalized);
        renderAll();
        try {
            await loadDocumentFallbackDocuments(false);
            const allInstances = state.documentFallbackDocuments.map(item => Object.assign({}, item.artifact, {
                    fsId: item.fsId,
                    typeCode: normalized,
                    typeName: casTypeName(normalized) || 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle',
                    name: item.title,
                    title: item.title,
                    fs: {
                        _id: item.fsId,
                        _type: casTypeName(normalized) || 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle',
                        typeCode: normalized,
                        features: Object.assign({title: item.title, textLength: item.textLength}, item.document && item.document.document || {})
                    }
                }));
            const visibleLimit = Math.min(treeDropdownPageSize(), allInstances.length);
            state.instancesByType.set(normalized, {
                allInstances,
                instances: allInstances.slice(0, visibleLimit),
                visibleLimit,
                offset: 0,
                limit: visibleLimit,
                total: allInstances.length,
                hasMore: visibleLimit < allInstances.length
            });
        } finally {
            state.loadingTypes.delete(normalized);
        }
    }

    function instanceRows(type, depth, options = {}) {
        const indent = 32 + Math.min(depth, 6) * 22;
        if (state.loadingTypes.has(type.typeCode)) {
            return ['<div class="duaviz-instance-row muted" style="padding-left:' + indent + 'px"><i class="fas fa-spinner fa-spin"></i> Loading instances</div>'];
        }
        const page = state.instancesByType.get(type.typeCode) || emptyPage();
        if (!state.instancesByType.has(type.typeCode)) {
            return [];
        }
        const offset = Number(page.offset || state.typePageOffsets.get(type.typeCode) || 0);
        const pageControls = [];
        if (offset > 0) {
            pageControls.push('<button class="duaviz-instance-row muted" type="button" data-page-dir="-1" data-type-code="' + type.typeCode + '" style="padding-left:' + indent + 'px"><i class="fas fa-chevron-left"></i> Previous page</button>');
        }
        const rows = (page.instances || []).slice(0, treeDropdownPageSize()).map(instance => {
            const active = state.selectedArtifact && Number(state.selectedArtifact.fsId) === Number(instance.fsId);
            const icon = instance.artifactKind === 'document' ? 'fa-file-alt' : (instance.artifactKind === 'corpus' ? 'fa-layer-group' : 'fa-cube');
            return '<button class="duaviz-instance-row ' + (active ? 'active' : '') + '" type="button" data-type-code="' + type.typeCode + '" data-fs-id="' + instance.fsId + '" style="padding-left:' + indent + 'px">' +
                '<span><i class="fas ' + icon + '"></i> ' + escapeHtml(instanceLabel(instance)) + beginEndValueBadge(instance) + '</span>' +
                '<span class="duaviz-count">fs ' + instance.fsId + '</span>' +
            '</button>';
        });
        if (page.hasMore) {
            const label = options.moreLabel || '...';
            rows.push('<button class="duaviz-instance-row muted duaviz-instance-more" type="button" data-page-dir="1" data-type-code="' + type.typeCode + '" style="padding-left:' + indent + 'px">' + escapeHtml(label) + '</button>');
        }
        return pageControls.concat(rows);
    }

    function renderMode() {
        $('.duaviz-mode-btn').removeClass('active');
        $('.duaviz-mode-btn[data-mode="' + state.mode + '"]').addClass('active');
        if (state.mode === 'reader' && state.document) {
            renderReader();
            return;
        }
        renderDocumentFallbackGallery();
    }

    function documentFallbackPageSize() {
        return 24;
    }

    function treeDropdownPageSize() {
        return 5;
    }

    function renderDocumentFallbackGallery() {
        $('.duaviz-view-chipbar').empty();
        $('.duaviz-selection-title').text('Select a document');
        highlighterList().html('<div class="duaviz-empty-line">Open a document card to inspect the reader.</div>');
        $('.duaviz-reader-layout').hide();
        $('.duaviz-document-gallery-container').show();
        if (!state.documentFallbackDocuments.length && !state.documentFallbackLoading && !state.documentFallbackLoaded) {
            loadDocumentFallbackDocuments();
        }
        const pageSize = documentFallbackPageSize();
        const offset = Math.max(0, Number(state.documentFallbackOffset || 0));
        const documents = state.documentFallbackDocuments.slice(offset, offset + pageSize);
        const total = state.documentFallbackDocuments.length;
        const cards = documents.map((item, index) =>
            '<button class="duaviz-viz-card duaviz-document-card" type="button" data-fs-id="' + Number(item.fsId) + '">' +
                '<span><i class="fas fa-file-alt"></i></span>' +
                '<strong>' + escapeHtml(item.title || ('Document ' + (offset + index + 1))) + '</strong>' +
                '<small>fs ' + escapeHtml(item.fsId) + (item.textLength ? ' · ' + escapeHtml(item.textLength) + ' chars' : '') + '</small>' +
            '</button>'
        ).join('');
        const requestSummary = renderStaticRequestSummary();
        const controls = '<div class="duaviz-document-gallery-controls">' +
            '<button class="duaviz-icon-btn duaviz-document-gallery-page" type="button" data-page-dir="-1" ' + (offset <= 0 ? 'disabled' : '') + ' title="Previous"><i class="fas fa-chevron-left"></i></button>' +
            '<span class="duaviz-count">' + (total ? (offset + 1) + '-' + Math.min(total, offset + documents.length) + ' / ' + total : (state.documentFallbackLoading ? 'Loading' : '0 / 0')) + '</span>' +
            '<button class="duaviz-icon-btn duaviz-document-gallery-page" type="button" data-page-dir="1" ' + (offset + pageSize >= total ? 'disabled' : '') + ' title="Next"><i class="fas fa-chevron-right"></i></button>' +
        '</div>';
        $('.duaviz-document-gallery-container').html('<section class="duaviz-document-gallery-shell">' +
            controls +
            requestSummary +
            '<div class="duaviz-document-card-gallery">' + (cards || renderDocumentRequestPlaceholder()) + '</div>' +
        '</section>');
    }

    function renderDocumentRequestPlaceholder() {
        if (state.documentFallbackLoading) {
            return '<div class="duaviz-reader-placeholder"><i class="fas fa-spinner fa-spin"></i> Requesting documents through DUA websocket/API...</div>';
        }
        return '<div class="duaviz-reader-placeholder">No document FeatureStructures returned by the configured DUA endpoint.</div>';
    }

    function renderStaticRequestSummary() {
        const rows = Array.isArray(state.staticRequestSummary) ? state.staticRequestSummary : [];
        if (state.documentFallbackLoading && !rows.length) {
            return '<div class="duaviz-viz-instance-list compact">' + [
                ['BIOfid articles', 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle'],
                ['DUA documents', 'org.texttechnologylab.annotations.dua.Document'],
                ['CAS sofas', 'cas:Sofa'],
                ['DKPro metadata', 'type2:DocumentMetaData'],
                ['UCE metadata', 'uce:Metadata']
            ].map(row =>
                '<div class="duaviz-viz-instance-row"><div><strong>' + escapeHtml(row[0]) + '</strong><small>' + escapeHtml(row[1]) + '</small></div><div class="duaviz-viz-chip-row"><span class="duaviz-anchor-chip"><i class="fas fa-spinner fa-spin"></i></span></div></div>'
            ).join('') + '</div>';
        }
        if (!rows.length) return '';
        return '<div class="duaviz-viz-instance-list compact">' + rows.map(row =>
            '<div class="duaviz-viz-instance-row">' +
                '<div><strong>' + escapeHtml(row.label || row.type || 'Request') + '</strong>' +
                '<small>' + escapeHtml(row.type || '') + '</small></div>' +
                '<div class="duaviz-viz-chip-row"><span class="duaviz-anchor-chip">' + escapeHtml(row.count || 0) + '</span>' +
                (row.error ? '<span class="duaviz-anchor-chip">' + escapeHtml(row.error) + '</span>' : '') + '</div>' +
            '</div>'
        ).join('') + '</div>';
    }

    async function loadDocumentFallbackDocuments(renderGallery = true) {
        if (state.documentFallbackLoaded && state.documentFallbackDocuments.length) return;
        state.documentFallbackLoading = true;
        state.staticRequestSummary = [];
        if (renderGallery) renderDocumentFallbackGallery();
        try {
            const requestResult = await loadDocumentsFromStaticCasRequests();
            state.staticRequestSummary = requestResult.summary;
            state.documentFallbackDocuments = requestResult.documents;
            state.documentFallbackOffset = Math.min(Number(state.documentFallbackOffset || 0), Math.max(0, state.documentFallbackDocuments.length - 1));
        } finally {
            state.documentFallbackLoaded = true;
            state.documentFallbackLoading = false;
            if (renderGallery) renderDocumentFallbackGallery();
        }
    }

    async function loadDocumentsFromStaticCasRequests() {
        const requests = [
            {label: 'BIOfid articles', type: 'org.texttechnologylab.annotations.dua.biofid.BIOfidArticle', includeSubtypes: true, limit: 90},
            {label: 'DUA documents', type: 'org.texttechnologylab.annotations.dua.Document', includeSubtypes: true, limit: 90},
            {label: 'CAS sofas', type: 'cas:Sofa', includeSubtypes: false, limit: 90},
            {label: 'DKPro metadata', type: 'type2:DocumentMetaData', includeSubtypes: false, limit: 250},
            {label: 'UCE metadata', type: 'uce:Metadata', includeSubtypes: false, limit: 1000}
        ];
        const seen = new Set();
        const documentRefs = [];
        const summary = [];
        for (const request of requests) {
            let refs = [];
            let error = '';
            try {
                refs = await directCasSelectRefs(request.type, request.limit, request.type, request.includeSubtypes);
                if (request.type === 'cas:Sofa') refs = await prioritizeTextSofaRefs(refs);
            } catch (failure) {
                error = failure && failure.message ? failure.message : String(failure || 'request failed');
                refs = [];
            }
            summary.push({label: request.label, type: request.type, count: refs.length, error});
            if (!['type2:DocumentMetaData', 'uce:Metadata'].includes(request.type)) {
                refs.map(Number)
                    .filter(fsId => Number.isFinite(fsId) && fsId > 0 && !seen.has(fsId) && seen.add(fsId))
                    .forEach(fsId => documentRefs.push(fsId));
            }
        }
        const documents = await hydrateDocumentFallbackDocuments(documentRefs);
        if (!documents.length) {
            showToast('DUA websocket/API requests returned no document FeatureStructures.');
        }
        return {documents, summary};
    }

    async function hydrateDocumentFallbackDocuments(refs) {
        const rows = [];
        const batchSize = 8;
        for (let index = 0; index < refs.length; index += batchSize) {
            const batch = refs.slice(index, index + batchSize);
            const loaded = await Promise.allSettled(batch.map(fsId => documentForArtifact(fsId)));
            loaded.forEach((result, batchIndex) => {
                if (result.status !== 'fulfilled' || !result.value) return;
                const fsId = batch[batchIndex];
                const document = result.value;
                const title = documentMetadataTitle(document, fsId);
                const text = String(document.document && document.document.text || '');
                rows.push({
                    fsId,
                    title,
                    textLength: text.length,
                    document,
                    artifact: {
                        fsId,
                        typeCode: casTypeCode('org.texttechnologylab.annotations.dua.Document'),
                        typeName: 'org.texttechnologylab.annotations.dua.Document',
                        artifactKind: 'document',
                        name: title,
                        title
                    }
                });
            });
        }
        return rows;
    }

    function documentMetadataTitle(document, fsId) {
        const metadata = document && document.metadataSummary || {};
        const candidates = [metadata.title, metadata.articleTitle];
        const doc = document && document.document || {};
        const pushFeatures = features => {
            if (!features || typeof features !== 'object') return;
            ['articleTitle', 'title', 'documentTitle', 'name', 'docTitle', 'headline', 'subtitle', 'sofaID', 'id'].forEach(key => {
                if (features[key] !== undefined && features[key] !== null) candidates.push(features[key]);
            });
        };
        candidates.push(doc.title, doc.name);
        (document && document.sofas || []).forEach(row => pushFeatures(row.features));
        (document && document.featureStructures || []).forEach(row => {
            const typeName = String(row.typeName || row.type || '').toLowerCase();
            if (typeName.includes('metadata') || typeName.includes('document')) pushFeatures(row.features);
        });
        const title = candidates.map(value => String(value || '').trim()).find(value => value && value !== '_InitialView' && value !== 'No document text available.');
        return title || ('Document ' + fsId);
    }

    async function resolveDocumentMetadataSummary(documentFsId, sofas, featureStructures, documentPayload) {
        const local = metadataSummaryFromRows(documentFsId, sofas, featureStructures, documentPayload);
        const text = normalizeDocumentText(sofas, []);
        const docRows = await globalMetadataRows('type2:DocumentMetaData', 500);
        const uceRows = await globalMetadataRows('uce:Metadata', 2500);
        const matchedDocumentMeta = matchDocumentMetadataRow(docRows, text, documentPayload);
        const uceValues = matchUceMetadataValues(uceRows, matchedDocumentMeta, text);
        return normalizeMetadataSummary(Object.assign({}, uceValues, matchedDocumentMeta && matchedDocumentMeta.features || {}, local));
    }

    function metadataSummaryFromRows(documentFsId, sofas, featureStructures, documentPayload) {
        const rows = []
            .concat(Array.isArray(sofas) ? sofas : [])
            .concat(Array.isArray(featureStructures) ? featureStructures : []);
        const values = {};
        rows.forEach(row => {
            const typeName = String(row && (row.typeName || row.type) || '');
            const features = row && row.features || {};
            if (isUceMetadataType(typeName)) {
                const key = String(features.key || '').trim();
                const value = metadataValue(features.value);
                if (key && value && values[key] === undefined) values[key] = value;
            } else if (isDocumentMetadataType(typeName)) {
                Object.assign(values, compactFeatureValues(features));
            }
        });
        const doc = documentPayload && documentPayload.document || documentPayload || {};
        if (doc.title) values.documentTitle = doc.title;
        return normalizeMetadataSummary(values);
    }

    async function globalMetadataRows(typeName, limit) {
        const cacheKey = typeName === 'uce:Metadata' ? 'uceMetadataRows' : 'documentMetadataRows';
        if (Array.isArray(state[cacheKey])) return state[cacheKey];
        const refs = await directCasSelectRefs(typeName, limit, typeName, false).catch(() => []);
        const seen = new Set();
        const unique = (refs || []).map(Number).filter(ref => Number.isFinite(ref) && ref > 0 && !seen.has(ref) && seen.add(ref));
        const rows = [];
        const concurrency = 20;
        for (let index = 0; index < unique.length; index += concurrency) {
            const batch = unique.slice(index, index + concurrency);
            const settled = await Promise.allSettled(batch.map(ref => cachedFsQuery(ref)));
            settled.forEach((result, batchIndex) => {
                if (result.status !== 'fulfilled' || !result.value) return;
                const payload = result.value;
                rows.push({
                    fsId: batch[batchIndex],
                    typeCode: Number(payload.typeCode || casTypeCode(typeName) || 0),
                    typeName: String(payload.type || payload.typeName || typeName),
                    features: Object.assign({}, payload.features || {})
                });
            });
        }
        state[cacheKey] = rows;
        return rows;
    }

    function matchDocumentMetadataRow(rows, text, documentPayload) {
        const haystack = String(text || '').toLowerCase();
        const payloadTitle = String(documentPayload && documentPayload.title || '').trim().toLowerCase();
        const scored = (rows || []).map(row => {
            const features = row && row.features || {};
            const title = String(features.documentTitle || features.title || '').trim();
            const uriTail = String(features.documentUri || '').split('/').filter(Boolean).pop() || '';
            let score = 0;
            if (title && haystack.includes(title.toLowerCase())) score += 1000 + title.length;
            if (payloadTitle && title.toLowerCase() === payloadTitle) score += 800;
            if (uriTail && haystack.includes(uriTail.toLowerCase())) score += 300;
            return {row, score};
        }).filter(item => item.score > 0).sort((a, b) => b.score - a.score);
        return scored.length ? scored[0].row : null;
    }

    function matchUceMetadataValues(rows, documentMetaRow, text) {
        const groups = uceMetadataGroups(rows);
        const documentFeatures = documentMetaRow && documentMetaRow.features || {};
        const uriTail = String(documentFeatures.documentUri || '').split('/').filter(Boolean).pop() || '';
        const title = String(documentFeatures.documentTitle || '').trim().toLowerCase();
        const haystack = String(text || '').toLowerCase();
        const scored = groups.map(values => {
            const joined = Object.values(values).map(value => String(value || '')).join(' ').toLowerCase();
            let score = 0;
            if (uriTail && joined.includes(uriTail.toLowerCase())) score += 1000;
            if (title && (joined.includes(title) || haystack.includes(title))) score += 300;
            if (values.title && haystack.includes(String(values.title).toLowerCase())) score += 200;
            if (values.publication_year && haystack.includes(String(values.publication_year))) score += 50;
            return {values, score};
        }).filter(item => item.score > 0).sort((a, b) => b.score - a.score);
        return scored.length ? scored[0].values : {};
    }

    function uceMetadataGroups(rows) {
        const groups = [];
        let current = {};
        (rows || []).forEach(row => {
            if (!isUceMetadataType(row && row.typeName)) return;
            const features = row.features || {};
            const key = String(features.key || '').trim();
            const value = metadataValue(features.value);
            if (!key || !value) return;
            if (key === 'collection' && Object.keys(current).length) {
                groups.push(current);
                current = {};
            }
            if (current[key] === undefined) current[key] = value;
        });
        if (Object.keys(current).length) groups.push(current);
        return groups;
    }

    function normalizeMetadataSummary(values) {
        const safe = compactFeatureValues(values || {});
        const title = firstMetadataValue(safe, ['documentTitle', 'articleTitle', 'title', 'name', 'headline', 'journalLabel', 'journal_label']);
        const year = firstMetadataValue(safe, ['publication_year', 'publicationYear', 'year', 'date']);
        const journal = firstMetadataValue(safe, ['journal_label', 'journalLabel', 'journal', 'journalName', 'journal_id']);
        const volume = firstMetadataValue(safe, ['volume_number', 'volume', 'volumeNumber']);
        const issue = firstMetadataValue(safe, ['issue_number', 'issue', 'issueNumber']);
        const uri = firstMetadataValue(safe, ['documentUri', 'item_url', 'uri', 'url', 'pdfUrl']);
        return Object.assign({}, safe, {title, articleTitle: title, year, journal, volume, issue, uri});
    }

    function compactFeatureValues(features) {
        const values = {};
        Object.entries(features || {}).forEach(([key, value]) => {
            const normalized = metadataValue(value);
            if (normalized) values[key] = normalized;
        });
        return values;
    }

    function metadataValue(value) {
        if (value === undefined || value === null) return '';
        return String(value).trim();
    }

    function firstMetadataValue(values, keys) {
        for (const key of keys) {
            const value = metadataValue(values && values[key]);
            if (value) return value;
        }
        return '';
    }

    function isUceMetadataType(typeName) {
        const name = String(typeName || '');
        return name === 'uce:Metadata' || name === 'org.texttechnologylab.annotation.uce.Metadata';
    }

    function isDocumentMetadataType(typeName) {
        const name = String(typeName || '');
        return name === 'type2:DocumentMetaData' || name === 'de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData';
    }

    function renderReader() {
        if (!state.document) {
            renderMode();
            return;
        }
        const doc = state.document.document || {};
        $('.duaviz-document-gallery-container').hide().empty();
        $('.duaviz-reader-layout').show();
        $('.duaviz-selection-title').text(doc.title || 'Document reader');
        renderViewChips();
        renderDocumentAnnotationList();
        $('.duaviz-document-meta').html('<h5>' + escapeHtml(doc.title || 'Untitled document') + '</h5>' +
            '<div class="duaviz-kicker">fs ' + escapeHtml(doc.fsId || '-') + ' / view ' + escapeHtml(state.selectedView) + '</div>');
        renderDocumentText();
    }

    function renderDocumentStructurePanel() {
        const sofas = Array.isArray(state.document && state.document.sofas) ? state.document.sofas : [];
        const featureStructures = Array.isArray(state.document && state.document.featureStructures) ? state.document.featureStructures : [];
        const structureRows = []
            .concat(renderDocumentStructureList('Sofas', sofas))
            .concat(renderDocumentStructureList('Feature Structures', featureStructures));
        return structureRows;
    }

    function renderDocumentStructureList(title, rows) {
        if (!Array.isArray(rows) || !rows.length) return [];
        return ['<div class="duaviz-tree-subheading instances" style="padding-left:10px">' + escapeHtml(title) + '</div>'].concat(rows.map(structure => {
            const label = structure.name || structure.title || structureLabel(structure);
            return '<button class="duaviz-doc-structure-row" type="button" data-fs-id="' + Number(structure.fsId || 0) + '" style="padding-left:24px">' +
                '<span><i class="fas fa-cube"></i> ' + escapeHtml(label) + '</span>' +
                '<span class="duaviz-count">fs ' + Number(structure.fsId || 0) + '</span>' +
            '</button>';
        }));
    }

    function renderViewChips() {
        const views = state.document.views || [{name: '_InitialView', defaultView: true, spanCount: 0}];
        $('.duaviz-view-chipbar').html(views.map(view =>
            '<button class="duaviz-view-chip duaviz-view-sofa-chip ' + (view.name === state.selectedView ? 'active' : '') + '" type="button" data-view="' + escapeAttr(view.name) + '">' +
                '<span class="duaviz-view-side"><i class="fas fa-eye"></i> ' + escapeHtml(view.name) + '<em>' + Number(view.spanCount || 0) + '</em></span>' +
                '<span class="duaviz-sofa-side"><i class="fas fa-file-alt"></i> ' + escapeHtml(sofaBadgeLabel(view)) + '</span>' +
            '</button>'
        ).join(''));
    }

    function sofaBadgeLabel(view) {
        const metadata = state.document && state.document.metadataSummary || {};
        const metadataTitle = String(metadata.articleTitle || metadata.title || '').trim();
        if (metadataTitle) {
            const parts = [metadataTitle];
            const detail = [metadata.year, metadata.journal, metadata.volume ? 'Vol. ' + metadata.volume : '', metadata.issue ? 'No. ' + metadata.issue : '']
                .map(value => String(value || '').trim())
                .filter(Boolean);
            if (detail.length) parts.push(detail.join(' / '));
            return parts.join(' · ');
        }
        const sofas = Array.isArray(state.document && state.document.sofas) ? state.document.sofas : [];
        const viewName = String(view && view.name || state.selectedView || '_InitialView');
        const sofa = sofas.find(row => {
            const features = row && row.features || {};
            return String(features.sofaID || row.name || '') === viewName;
        }) || sofas.find(row => {
            const features = row && row.features || {};
            return String(features.sofaString || features.text || '').trim().length > 0;
        }) || sofas[0] || null;
        const features = sofa && sofa.features || {};
        const title = String(features.title || features.documentTitle || features.sofaID || (state.document.document && state.document.document.title) || viewName);
        const year = String(features.year || features.date || features.publicationYear || '').trim();
        return year ? title + ' · ' + year : title;
    }

    function renderDocumentAnnotationList() {
        ensureHighlighterPlacement();
        renderVizTools();
        const structureRows = renderDocumentStructurePanel();
        const annotationRows = (state.document.annotationTypes || []).map((type, index) => {
            const typeCode = Number(type.typeCode);
            const checked = state.checkedAnnotationTypes.has(typeCode);
            return '<label class="duaviz-doc-annotation-row">' +
                '<input class="duaviz-annotation-checkbox" type="checkbox" data-type-code="' + typeCode + '" ' + (checked ? 'checked' : '') + '>' +
                '<span class="duaviz-color" style="background:' + colorFor(typeCode, index) + '"></span>' +
                '<span title="' + escapeAttr(type.typeName) + '">' + escapeHtml(type.label || shortLabel(type.typeName)) + '</span>' +
                '<span class="duaviz-count">' + Number(type.count || 0) + '</span>' +
                '<input class="duaviz-annotation-opacity" type="range" min="20" max="90" value="52" title="Highlight opacity">' +
            '</label>';
        });
        const rows = structureRows.concat(annotationRows);
        highlighterList().html(rows.length ? rows.join('') : '<div class="duaviz-empty-line">No annotation spans in this view.</div>');
    }

    function ensureHighlighterPlacement() {
        if (!document || typeof document.querySelector !== 'function') return;
        const featurePanel = document.querySelector('.duaviz-feature-panel');
        const featureTable = document.querySelector('.duaviz-feature-table');
        if (!featurePanel || !featureTable) return;
        let tools = featurePanel.querySelector('.duaviz-viztools');
        if (!tools) {
            tools = document.createElement('section');
            tools.className = 'duaviz-viztools';
            tools.setAttribute('aria-label', 'Visualization tools');
            tools.innerHTML = '<div class="duaviz-panel-header compact"><div><p class="duaviz-kicker mb-1">VizTools</p></div></div><div class="duaviz-viz-gallery"></div><div class="duaviz-viz-component-detail"></div>';
            featureTable.insertAdjacentElement('afterend', tools);
        }
        const staleAside = document.querySelector('.duaviz-reader-layout .duaviz-document-annotations');
        const staleList = staleAside && staleAside.querySelector('.duaviz-document-annotation-list');
        const targetList = featurePanel.querySelector('.duaviz-viz-component-detail .duaviz-document-annotation-list');
        if (staleList && targetList && staleList !== targetList) {
            targetList.replaceWith(staleList);
        }
        if (staleAside) staleAside.remove();
    }

    function highlighterList() {
        return $('.duaviz-feature-panel .duaviz-viz-component-detail .duaviz-document-annotation-list').first();
    }

    function renderDocumentText() {
        const doc = state.document.document || {};
        const text = String(doc.text || '');
        const spans = (state.document.spans || [])
            .filter(span => span.viewName === state.selectedView)
            .filter(span => state.checkedAnnotationTypes.has(Number(span.typeCode)))
            .sort((a, b) => Number(a.begin) - Number(b.begin) || Number(b.end) - Number(a.end));
        let cursor = 0;
        const parts = [];
        spans.forEach((span, index) => {
            const begin = Math.max(0, Math.min(text.length, Number(span.begin)));
            const end = Math.max(begin, Math.min(text.length, Number(span.end)));
            if (begin < cursor || begin === end) return;
            parts.push(escapeHtml(text.slice(cursor, begin)));
            const selected = state.selectedAnnotationFsIds.has(Number(span.fsId));
            const color = colorForSpan(span, index);
            parts.push('<mark class="duaviz-span ' + (selected ? 'selected-instance' : '') + '" data-fs-id="' + span.fsId + '" title="' + escapeAttr((span.label || span.typeName) + ' fs ' + span.fsId) + '" style="--duaviz-span-color:' + escapeAttr(color) + '; background:' + rgba(color, opacityForSpan(span)) + '">' +
                (selected ? spanColorPopover(span, color) : '') +
                escapeHtml(text.slice(begin, end)) +
            '</mark>');
            cursor = end;
        });
        parts.push(escapeHtml(text.slice(cursor)));
        $('.duaviz-document-text').html(parts.join('') || '<span class="duaviz-empty-line">No document text available.</span>');
        window.requestAnimationFrame(drawPointerCurves);
    }

    function renderFeaturePanel(item) {
        const fs = item.fs || {};
        const features = item.features || fs.features || {};
        $('.duaviz-feature-summary').html('<strong>' + escapeHtml(item.name || item.label || item.typeName || fs._type || 'Feature structure') + '</strong>' +
            '<div class="duaviz-kicker">fs ' + escapeHtml(item.fsId || fs._id || '-') + '</div>');
        $('.duaviz-feature-table').html(Object.entries(features).map(([key, value]) =>
            '<div class="duaviz-feature-row"><span>' + escapeHtml(shortLabel(key)) + '</span><strong>' + escapeHtml(displayValue(value)) + '</strong></div>'
        ).join('') || '<div class="duaviz-empty-line">No feature values exposed.</div>');
        renderVizTools();
    }

    function renderVizTools() {
        ensureHighlighterPlacement();
        const gallery = $('.duaviz-feature-panel .duaviz-viz-gallery');
        const detail = $('.duaviz-feature-panel .duaviz-viz-component-detail');
        if (!gallery.length || !detail.length) return;
        gallery.html(
            '<div class="duaviz-viz-long-card">' +
                '<div class="duaviz-viz-section-title"><span>Gallery</span><span class="duaviz-count">' + vizComponents.length + '</span></div>' +
                '<div class="duaviz-viz-gallery-body">' + renderVizComponentGallery() + '</div>' +
                '<div class="duaviz-viz-section-title lower"><span>Components</span><span class="duaviz-count">' + state.vizInstances.length + '</span></div>' +
                '<div class="duaviz-viz-components-body">' + renderVizInstanceList() + '</div>' +
            '</div>'
        );
        const component = selectedVizComponent();
        if (!state.vizDetailOpen) {
            detail.empty();
            return;
        }
        if (!component) {
            detail.empty();
            return;
        }
        const instance = selectedVizInstance() || ensureVizInstance(component.id);
        const bindings = instance.bindings || {};
        detail.html(
            '<section class="duaviz-viz-component open" data-component="' + escapeAttr(component.id) + '">' +
                '<div class="duaviz-viz-title"><div><p class="duaviz-kicker mb-1">Selected Instance</p><h6 class="mb-0">' + escapeHtml(instance.label || component.label) + '</h6></div><span class="duaviz-count">' + Object.keys(bindings).length + '</span></div>' +
                '<p class="duaviz-viz-description">' + escapeHtml(component.description) + '</p>' +
                '<div class="duaviz-anchor-list">' + renderAnchorRows(component, bindings) + '</div>' +
                renderHighlighterControls(instance) +
                renderPointerControls(instance) +
            '</section>'
        );
    }

    function renderVizComponentGallery() {
        return '<div class="duaviz-viz-card-row">' + vizComponents.map(component =>
            '<button class="duaviz-viz-card ' + (component.id === state.selectedVizComponent ? 'active' : '') + '" type="button" data-component="' + escapeAttr(component.id) + '">' +
                '<span><i class="fas ' + escapeAttr(component.icon) + '"></i></span>' +
                '<strong>' + escapeHtml(component.label) + '</strong>' +
                '<small>' + escapeHtml(component.bindingScope === 'single-fstype' ? 'single FS type' : 'multi type') + '</small>' +
            '</button>'
        ).join('') + '</div>';
    }

    function renderVizInstanceList() {
        const rows = state.vizInstances.map(instance => {
            const component = vizComponents.find(item => item.id === instance.componentId) || vizComponents[0];
            const color = String(instance.config && instance.config.color || harmonicColors[2]);
            const chips = renderVizInstanceAnchorChips(component, instance);
            const boundCount = Object.keys(instance.bindings || {}).length;
            return '<button class="duaviz-viz-instance-row ' + (instance.id === state.selectedVizInstance ? 'active' : '') + '" type="button" data-instance="' + escapeAttr(instance.id) + '">' +
                '<span class="duaviz-viz-instance-spec">' +
                    '<span class="duaviz-viz-instance-main"><span class="duaviz-color" style="background:' + escapeAttr(color) + '"></span><strong>' + escapeHtml(instance.label || component.label) + '</strong></span>' +
                    '<small>' + escapeHtml(component.bindingScope === 'single-fstype' ? 'single FS type' : 'multi type') + ' · ' + boundCount + ' slots</small>' +
                '</span>' +
                '<span class="duaviz-viz-slot-chip-row">' + (chips || '<span class="duaviz-viz-slot-placeholder">No anchors bound</span>') + '</span>' +
            '</button>';
        }).join('');
        return '<div class="duaviz-viz-instance-list">' + (rows || '<div class="duaviz-empty-line">No active components.</div>') + '</div>';
    }

    function renderVizInstanceAnchorChips(component, instance) {
        const bindings = instance && instance.bindings || {};
        return (component.anchors || [])
            .filter(anchor => anchor.bindTarget !== 'config')
            .filter(anchor => bindings[anchor.id])
            .map(anchor => {
                const binding = bindings[anchor.id];
                return '<span class="duaviz-viz-slot-chip bound">' +
                    '<b>' + escapeHtml(anchor.label) + '</b>' +
                    '<em>' + escapeHtml(bindingLabel(binding)) + '</em>' +
                '</span>';
            }).join('');
    }

    function renderAnchorRows(component, bindings) {
        const rangeAnchors = component.anchors.filter(anchor => anchor.compactGroup === 'core');
        const otherAnchors = component.anchors.filter(anchor => anchor.compactGroup !== 'core' && anchor.bindTarget !== 'config');
        return otherAnchors.map(anchor => anchorRow(component, anchor, bindings[anchor.id])).join('') +
            (rangeAnchors.length ? '<div class="duaviz-anchor-pair">' + rangeAnchors.map(anchor => anchorRow(component, anchor, bindings[anchor.id])).join('') + '</div>' : '');
    }

    function renderHighlighterControls(instance) {
        if (!instance || instance.componentId !== 'highlighter') return '';
        const opacity = Number(instance.config && instance.config.opacity || 52);
        const color = String(instance.config && instance.config.color || harmonicColors[2]);
        return '<div class="duaviz-highlighter-style-row">' +
            '<span class="duaviz-color duaviz-selected-color" style="background:' + escapeAttr(color) + '"></span>' +
            '<input class="duaviz-viz-opacity" type="range" min="20" max="90" value="' + opacity + '" title="Highlight opacity">' +
            '<div class="duaviz-color-swatches">' + harmonicColors.map(swatch =>
                '<button class="duaviz-color-swatch ' + (swatch === color ? 'active' : '') + '" type="button" data-color="' + escapeAttr(swatch) + '" title="' + escapeAttr(swatch) + '" style="background:' + escapeAttr(swatch) + '"></button>'
            ).join('') + '</div>' +
        '</div>';
    }

    function renderPointerControls(instance) {
        if (!instance || instance.componentId !== 'pointer') return '';
        const limit = Math.max(4, Math.min(300, Number(instance.config && instance.config.spanLimit || 300)));
        return '<div class="duaviz-pointer-config">' +
            '<span class="duaviz-kicker">Span limit</span>' +
            '<input class="duaviz-pointer-limit" type="range" min="4" max="300" value="' + limit + '" title="Pointer span limit">' +
            '<strong>' + limit + '</strong>' +
            '<svg class="duaviz-pointer-preview" viewBox="0 0 120 32" aria-hidden="true"><path d="M8 25 C38 2, 82 2, 112 25"></path><circle cx="60" cy="8" r="3"></circle></svg>' +
        '</div>';
    }

    function drawPointerCurves() {
        const reader = document.querySelector('.duaviz-document-reader');
        const textNode = document.querySelector('.duaviz-document-text');
        if (!reader || !textNode) return;
        let layer = reader.querySelector('.duaviz-pointer-layer');
        if (!layer) {
            layer = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            layer.classList.add('duaviz-pointer-layer');
            reader.appendChild(layer);
        }
        layer.innerHTML = '';
        const activePointers = state.vizInstances.filter(instance => instance.componentId === 'pointer');
        if (!activePointers.length) return;
        const readerBox = reader.getBoundingClientRect();
        layer.setAttribute('viewBox', '0 0 ' + Math.max(1, readerBox.width) + ' ' + Math.max(1, readerBox.height));
        layer.setAttribute('width', String(Math.max(1, readerBox.width)));
        layer.setAttribute('height', String(Math.max(1, readerBox.height)));
        const marks = Array.from(textNode.querySelectorAll('.duaviz-span')).map(mark => {
            const fsId = Number(mark.dataset.fsId || 0);
            const span = (state.document && state.document.spans || []).find(item => Number(item.fsId) === fsId);
            return {mark, span};
        }).filter(item => item.span);
        activePointers.forEach(instance => {
            const sourceType = instance.bindings && instance.bindings['source.type'];
            const targetType = instance.bindings && instance.bindings['target.type'];
            if (!sourceType || !targetType) return;
            const limit = Math.max(4, Math.min(300, Number(instance.config && instance.config.spanLimit || 300)));
            const sources = marks.filter(item => Number(item.span.typeCode) === Number(sourceType.typeCode));
            const targets = marks.filter(item => Number(item.span.typeCode) === Number(targetType.typeCode));
            sources.forEach(source => {
                targets.forEach(target => {
                    const distance = Math.abs(Number(source.span.begin) - Number(target.span.begin));
                    if (!distance || distance > limit) return;
                    drawPointerCurve(layer, readerBox, source, target, instance, distance);
                });
            });
        });
    }

    function drawPointerCurve(layer, readerBox, source, target, instance, distance) {
        const from = source.mark.getBoundingClientRect();
        const to = target.mark.getBoundingClientRect();
        const x1 = from.left + from.width / 2 - readerBox.left;
        const y1 = from.top - readerBox.top;
        const x2 = to.left + to.width / 2 - readerBox.left;
        const y2 = to.top - readerBox.top;
        const peakY = Math.min(y1, y2) - 28;
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M ' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + peakY + ', ' + x2 + ' ' + peakY + ', ' + x2 + ' ' + y2);
        path.setAttribute('class', 'duaviz-pointer-curve');
        const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
        title.textContent = (instance.label || 'Pointer') + ' distance ' + distance;
        path.appendChild(title);
        layer.appendChild(path);
        const peak = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        peak.setAttribute('class', 'duaviz-pointer-peak');
        peak.setAttribute('cx', String((x1 + x2) / 2));
        peak.setAttribute('cy', String(peakY));
        peak.setAttribute('r', '3');
        const peakTitle = document.createElementNS('http://www.w3.org/2000/svg', 'title');
        peakTitle.textContent = (instance.label || 'Pointer') + ' distance ' + distance;
        peak.appendChild(peakTitle);
        layer.appendChild(peak);
    }

    function anchorRow(component, anchor, binding) {
        const active = component.id === state.selectedVizComponent && anchor.id === state.activeVizAnchor;
        return '<button class="duaviz-anchor-row ' + (active ? 'active' : '') + (binding ? ' bound' : '') + ' bind-' + escapeAttr(anchor.bindTarget || 'feature') + '" type="button" data-component="' + escapeAttr(component.id) + '" data-anchor="' + escapeAttr(anchor.id) + '">' +
            '<span><strong>' + escapeHtml(anchor.label) + '</strong><small>' + escapeHtml(anchor.required ? 'required' : 'optional') + '</small></span>' +
            '<span class="duaviz-anchor-meta">' + escapeHtml(anchor.accepts.join(' / ')) + '</span>' +
            '<em>' + escapeHtml(binding ? bindingLabel(binding) : (anchor.bindTarget === 'type' ? 'Click type' : 'Select feature')) + '</em>' +
        '</button>';
    }

    function selectedVizComponent() {
        return vizComponents.find(component => component.id === state.selectedVizComponent) || vizComponents[0];
    }

    function selectedAnchor() {
        const component = selectedVizComponent();
        return component && component.anchors.find(anchor => anchor.id === state.activeVizAnchor);
    }

    function selectedVizInstance() {
        return state.vizInstances.find(instance => instance.id === state.selectedVizInstance) || state.vizInstances[0] || null;
    }

    function ensureVizInstance(componentId) {
        let instance = state.vizInstances.find(item => item.componentId === componentId);
        if (!instance) {
            const component = vizComponents.find(item => item.id === componentId) || vizComponents[0];
            instance = {
                id: componentId + '-' + (state.vizInstances.length + 1),
                componentId,
                label: component.label,
                bindings: {},
                config: Object.assign({opacity: 52, color: harmonicColors[2]}, component.defaults || {})
            };
            state.vizInstances.push(instance);
        }
        return instance;
    }

    function componentBindings(componentId) {
        const instance = selectedVizInstance();
        if (instance && instance.componentId === componentId) {
            if (!instance.bindings) instance.bindings = {};
            return instance.bindings;
        }
        if (!state.vizBindings[componentId]) state.vizBindings[componentId] = {};
        return state.vizBindings[componentId];
    }

    function activeAnchorAccepts(primitiveKind) {
        const anchor = selectedAnchor();
        return !!(anchor && (anchor.accepts.includes(primitiveKind) || (anchor.accepts.includes('numeric') && ['integer', 'long', 'float', 'double'].includes(primitiveKind))));
    }

    function activeAnchorIsTypeReference() {
        const anchor = selectedAnchor();
        return !!(anchor && anchor.bindTarget === 'type' && anchor.accepts.includes('fs-reference'));
    }

    function annotationFeatureSelectMode() {
        const anchor = selectedAnchor();
        return !!(anchor && anchor.bindTarget === 'feature');
    }

    function bindFeatureSlot(slot) {
        const component = selectedVizComponent();
        const anchor = selectedAnchor();
        if (!component || !anchor || anchor.bindTarget === 'type' || !activeAnchorAccepts(slot.primitiveKind)) return;
        if (!bindingFitsComponentScope(component, slot.typeCode)) return;
        componentBindings(component.id)[anchor.id] = slot;
        if (component.bindingScope === 'single-fstype' && !componentBindings(component.id)['fs.type']) {
            componentBindings(component.id)['fs.type'] = {
                typeCode: slot.typeCode,
                typeName: slot.typeName,
                featureCode: 0,
                featureName: slot.typeName,
                primitiveKind: 'fs-reference',
                target: 'type'
            };
        }
        renderVizTools();
        renderAccordions();
    }

    function bindTypeSlot(typeCode) {
        const component = selectedVizComponent();
        const anchor = selectedAnchor();
        if (!component || !anchor || anchor.bindTarget !== 'type' || !anchor.accepts.includes('fs-reference')) return false;
        const type = findType(typeCode);
        if (!type) return false;
        componentBindings(component.id)[anchor.id] = {
            typeCode: type.typeCode,
            typeName: type.name,
            featureCode: 0,
            featureName: type.name,
            primitiveKind: 'fs-reference',
            target: 'type'
        };
        if (component.bindingScope === 'single-fstype') {
            Object.entries(componentBindings(component.id)).forEach(([anchorId, binding]) => {
                if (anchorId !== anchor.id && Number(binding.typeCode) !== Number(type.typeCode)) {
                    delete componentBindings(component.id)[anchorId];
                }
            });
        }
        renderVizTools();
        renderAccordions();
        return true;
    }

    function bindSourceAnchorFromAnnotationInstance(instance) {
        const typeCode = Number(instance && instance.typeCode || instance && instance.fs && instance.fs.typeCode || 0);
        const type = findType(typeCode);
        if (!type) return false;
        const selectedInstance = selectedVizInstance() || ensureVizInstance(state.selectedVizComponent || 'highlighter');
        const component = vizComponents.find(item => item.id === selectedInstance.componentId) || selectedVizComponent();
        const anchor = sourceAnchorForComponent(component);
        if (!component || !anchor) return false;
        state.selectedVizComponent = component.id;
        state.selectedVizInstance = selectedInstance.id;
        state.activeVizAnchor = anchor.id;
        if (!selectedInstance.bindings) selectedInstance.bindings = {};
        selectedInstance.bindings[anchor.id] = {
            typeCode: type.typeCode,
            typeName: type.name,
            featureCode: 0,
            featureName: type.name,
            primitiveKind: 'fs-reference',
            target: 'type'
        };
        if (component.bindingScope === 'single-fstype') {
            Object.entries(selectedInstance.bindings).forEach(([anchorId, binding]) => {
                if (anchorId !== anchor.id && Number(binding.typeCode) !== Number(type.typeCode)) {
                    delete selectedInstance.bindings[anchorId];
                }
            });
        }
        renderVizTools();
        return true;
    }

    function sourceAnchorForComponent(component) {
        if (!component || !Array.isArray(component.anchors)) return null;
        return component.anchors.find(anchor => anchor.id === 'source.type')
            || component.anchors.find(anchor => anchor.id === 'fs.type')
            || component.anchors.find(anchor => anchor.bindTarget === 'type' && anchor.accepts.includes('fs-reference'))
            || null;
    }

    function bindingFitsComponentScope(component, typeCode) {
        if (!component || component.bindingScope !== 'single-fstype') return true;
        const bindings = componentBindings(component.id);
        const currentType = Object.values(bindings).find(binding => Number(binding.typeCode) > 0);
        return !currentType || Number(currentType.typeCode) === Number(typeCode);
    }

    function isFeatureBound(type, featureName, featureCode) {
        const bindings = componentBindings(state.selectedVizComponent);
        return Object.values(bindings).some(binding =>
            Number(binding.typeCode) === Number(type.typeCode)
            && (Number(binding.featureCode) === Number(featureCode) || String(binding.featureName) === String(featureName))
        );
    }

    function isTypeBound(type) {
        const bindings = componentBindings(state.selectedVizComponent);
        return Object.values(bindings).some(binding => binding.target === 'type' && Number(binding.typeCode) === Number(type.typeCode));
    }

    function bindingLabel(binding) {
        return binding.target === 'type' ? shortLabel(binding.typeName) : shortLabel(binding.typeName) + '.' + shortLabel(binding.featureName);
    }

    function primitiveKindForFeature(feature) {
        const raw = String(feature.range || feature.type || feature.kind || feature.valueType || feature.name || '').toLowerCase();
        if (raw.includes('fs') || raw.includes('ref') || raw.includes('uima.tcas') || raw.includes('org.')) return 'fs-reference';
        if (raw.includes('double')) return 'double';
        if (raw.includes('float')) return 'float';
        if (raw.includes('long')) return 'long';
        if (raw.includes('int') || raw === 'begin' || raw === 'end' || raw.endsWith(':begin') || raw.endsWith(':end')) return 'integer';
        if (raw.includes('bool')) return 'boolean';
        if (raw.includes('string') || raw.includes('text') || raw.includes('label') || raw.includes('value') || raw.includes('id')) return 'string';
        return 'primitive';
    }

    function renderError(message) {
        $('.duaviz-artifact-tree, .duaviz-annotation-type-tree').html('<div class="duaviz-empty-line">' + escapeHtml(message) + '</div>');
        $('.duaviz-document-text').html('<div class="duaviz-reader-placeholder">' + escapeHtml(message) + '</div>');
    }

    function showToast(message) {
        const container = root();
        if (!container.length) return;
        const toast = $('<div class="duaviz-toast"></div>')
            .text(String(message || 'An error occurred.'))
            .css({
                position: 'fixed',
                top: '12px',
                right: '12px',
                zIndex: 1200,
                padding: '10px 12px',
                borderRadius: '8px',
                background: 'rgba(17, 24, 39, 0.95)',
                color: '#fff',
                fontSize: '13px',
                maxWidth: 'min(420px, 90vw)',
                boxShadow: '0 4px 12px rgba(0, 0, 0, 0.25)',
                border: '1px solid rgba(255,255,255,0.15)'
            });
        container.prepend(toast);
        window.setTimeout(() => toast.fadeOut(250, () => toast.remove()), 2500);
    }

    function applyRouteSelection() {
        if (!window.uceUiState) return;
        const documentFsId = Number(window.uceUiState.get('documentFsId') || 0);
        if (!documentFsId) return;
        documentForArtifact(documentFsId).then(document => {
            state.document = document;
            state.mode = 'reader';
            state.selectedView = state.document && state.document.selectedView || '_InitialView';
            state.checkedAnnotationTypes = new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
            syncDocumentStructureTypes(state.document);
            expandDocumentAnnotationTypes();
            renderReader();
            renderAccordions();
        }).catch(error => renderError(error.message || String(error)));
    }

    function normalizeTypes(message) {
        const schema = message && (message.schema || message.selection || {}) || {};
        if (!Array.isArray(schema.types) && Array.isArray(message && message.types)) {
            schema.types = message.types;
        }
        const raw = Array.isArray(schema.types) ? schema.types : [];
        const seen = new Set();
        return raw
            .map(entry => {
                if (typeof entry === 'string') {
                    const typeName = String(entry || '').trim();
                    if (!typeName || /^\d+$/.test(typeName)) return null;
                    if (seen.has(typeName)) return null;
                    seen.add(typeName);
                    return casTypeRow(typeName);
                }
                if (!entry || typeof entry !== 'object') return null;
                const typeName = String(entry.name || '').trim();
                const key = typeName ? String(typeName) : '';
                if (key && seen.has(key)) return null;
                if (key) seen.add(key);
                const inferredArtifact = casArtifactType(typeName);
                const explicitArtifact = entry.artifact === true;
                const explicitAnnotation = entry.annotation === true;
                const inferredAnnotation = !inferredArtifact && !/Sofa$/.test(typeName) && !/FSArray$/.test(typeName) && !/Array$/.test(typeName);
                return Object.assign({}, entry, {
                    typeCode: Number(entry.typeCode || casTypeCode(typeName)),
                    superTypeCode: Number(entry.superTypeCode || (entry.superTypeName ? casTypeCode(entry.superTypeName) : 0) || casSuperTypeCode(typeName)),
                    superTypeName: String(entry.superTypeName || casSuperTypeName(typeName) || ''),
                    label: entry.label || shortLabel(typeName),
                    annotation: explicitAnnotation ? true : inferredAnnotation,
                    artifact: explicitArtifact ? true : inferredArtifact,
                    features: Array.isArray(entry.features) ? entry.features : []
                });
            })
            .filter(Boolean)
            .sort((a, b) => String(a.name).localeCompare(String(b.name)));
    }

    async function documentForArtifact(documentFsId) {
        const fsId = Number(documentFsId);
        if (!Number.isFinite(fsId) || fsId <= 0) throw new Error('Invalid document reference.');
        try {
            const documentResponse = await wsQuery('document', {documentFsId: fsId, limit: 2500});
            const normalized = normalizeDocumentPayload(documentResponse);
            if (normalized && normalized.document && normalized.document.text) {
                return normalized;
            }
        } catch (error) {
        }
        try {
            const documentType = state.types.find(item => item.artifact && isDocumentTypeName(item.name));
            const documentName = documentType ? documentType.name : 'org.texttechnologylab.annotations.dua.Document';
            const fsMeta = await wsQuery('fs', {fsRef: fsId});
            const typeCode = Number(fsMeta && fsMeta.typeCode || casTypeCode(documentName));
            const typeName = casTypeName(typeCode) || documentName;
            const selectedSofa = typeName === 'cas:Sofa' || String(fsMeta && (fsMeta.type || fsMeta.typeName || '')).trim() === 'cas:Sofa';
            const selectedFeatures = Object.assign({}, fsMeta && fsMeta.features || {});
            const selectedSofaRow = selectedSofa ? {
                fsId,
                typeCode,
                typeName: 'cas:Sofa',
                name: String(selectedFeatures.sofaID || '_InitialView'),
                documentFsId: fsId,
                sofaFsId: fsId,
                features: selectedFeatures
            } : null;
            const selectedSofaHasText = selectedSofaRow && String(selectedSofaRow.features && (selectedSofaRow.features.sofaString || selectedSofaRow.features.text || '') || '').trim().length;
            const loadedSofas = (!selectedSofaRow || !selectedSofaHasText) ? await loadFeatureStructuresByType('cas:Sofa', 48).catch(() => []) : [];
            const sofas = selectedSofaRow ? [selectedSofaRow].concat(loadedSofas.filter(row => Number(row.fsId || 0) !== fsId)) : loadedSofas;
            const textSofa = sofas.find(row => {
                const features = row && row.features || {};
                return String(features.sofaString || features.text || '').trim().length > 0;
            }) || selectedSofaRow || sofas[0] || null;
            const textSofaFeatures = Object.assign({}, textSofa && textSofa.features || {});
            const text = normalizeDocumentText(sofas, []);
            const selectedDocumentRow = {
                fsId,
                typeCode,
                typeName: String(fsMeta && (fsMeta.type || fsMeta.typeName) || typeName),
                name: String(fsMeta && (fsMeta.name || fsMeta.label) || ''),
                documentFsId: fsId,
                sofaFsId: Number(textSofa && textSofa.fsId || fsId),
                features: selectedFeatures
            };
            const featureStructures = [selectedDocumentRow].concat(selectedSofaRow ? [selectedSofaRow] : []);
            const metadataSummary = await resolveDocumentMetadataSummary(fsId, sofas, featureStructures, {
                title: String(textSofaFeatures.sofaID || selectedFeatures.sofaID || selectedFeatures.journalLabel || (fsMeta && (fsMeta.title || fsMeta.name)) || '')
            }).catch(() => metadataSummaryFromRows(fsId, sofas, featureStructures, {}));
            const fallbackText = articleMetadataText(metadataSummary, selectedFeatures, fsId);
            const readableText = text && text !== 'No document text available.' ? text : fallbackText;

            const viewName = String(textSofaFeatures.sofaID || selectedFeatures.sofaID || '_InitialView');
            return {
                fsId,
                document: {
                    fsId,
                    typeCode,
                    typeName,
                    artifactKind: 'document',
                    title: String(metadataSummary.title || textSofaFeatures.sofaID || selectedFeatures.sofaID || (fsMeta && (fsMeta.title || fsMeta.name)) || (typeName + ' ' + fsId)),
                    text: readableText
                },
                views: [{name: viewName, defaultView: true, spanCount: 0}],
                selectedView: viewName,
                annotationTypes: [],
                spans: [],
                sofas,
                featureStructures,
                metadataSummary
            };
        } catch (error) {
            throw error;
        }
    }

    function articleMetadataText(metadataSummary, features, fsId) {
        const safe = Object.assign({}, features || {}, metadataSummary || {});
        const lines = [];
        const title = firstMetadataValue(safe, ['title', 'articleTitle', 'journalLabel', 'journal']);
        if (title) lines.push(title);
        const details = [
            ['Kind', firstMetadataValue(safe, ['kind'])],
            ['Publication year', firstMetadataValue(safe, ['publicationYear', 'year'])],
            ['Volume', firstMetadataValue(safe, ['volumeNumber', 'volume'])],
            ['Issue', firstMetadataValue(safe, ['issueNumber', 'issue'])],
            ['Collection', firstMetadataValue(safe, ['collectionName', 'collection'])],
            ['Vendor', firstMetadataValue(safe, ['vendor'])],
            ['Source article', firstMetadataValue(safe, ['sourceArticleId'])],
            ['Source document', firstMetadataValue(safe, ['sourceDocumentId'])],
            ['License', firstMetadataValue(safe, ['license'])],
            ['PDF', firstMetadataValue(safe, ['pdfUrl', 'uri'])]
        ].filter(row => row[1]);
        details.forEach(row => lines.push(row[0] + ': ' + row[1]));
        if (!lines.length) lines.push('Document ' + fsId);
        return lines.join('\\n');
    }

    function normalizeDocumentPayload(message) {
        if (!message || typeof message !== 'object') return null;
        const hasFlatAnnotations = Array.isArray(message.annotations);
        const hasFlatCas = message.cas && typeof message.cas === 'object';
        const isFlatDocument = hasFlatAnnotations || (message.text !== undefined && hasFlatCas);
        const payload = isFlatDocument
            ? message
            : (message.payload && typeof message.payload === 'object'
                ? message.payload
                : (message.result && typeof message.result === 'object' ? message.result : message.document || message));
        const nested = payload && typeof payload.data === 'object' ? payload.data : {};
        const documentPayload = isFlatDocument ? (payload.document || payload) : (payload.document || payload.doc || payload);
        if (!documentPayload || typeof documentPayload !== 'object') return null;

        const casPayload = payload.cas && typeof payload.cas === 'object' ? payload.cas : null;
        const casFeatureStructures = Array.isArray(casPayload && casPayload.featureStructures)
            ? casPayload.featureStructures
            : Array.isArray(payload.cas)
                ? payload.cas
                : [];
        const sofaTypeCode = casTypeCode('cas:Sofa');
        const docId = Number(documentPayload.fsId || payload.fsId || 0);
        const normalizeContextRows = rows => (Array.isArray(rows) ? rows : [])
            .map(row => {
                if (!row || typeof row !== 'object') return null;
                return {
                    fsId: Number(row._id || row.fsId || row.id || 0),
                    typeCode: Number(row.typeCode || casTypeCode(row._type || row.type || '')),
                    typeName: String(row._type || row.typeName || row.type || ''),
                    name: String(row.name || row.label || ''),
                    documentFsId: Number(row.documentFsId || row.docId || docId || 0),
                    sofaFsId: Number(row.sofaFsId || row.documentFsId || docId || 0),
                    features: Object.assign({}, row.features || {})
                };
            })
            .filter(row => row && Number.isFinite(row.fsId) && row.fsId > 0);

        const rawContextRows = normalizeContextRows(casFeatureStructures);
        const sofaRows = rawContextRows.filter(entry => Number(entry.typeCode) === sofaTypeCode || entry.typeName === 'cas:Sofa');
        const fsRows = rawContextRows.filter(entry => Number(entry.typeCode) !== sofaTypeCode && entry.typeName !== 'cas:Sofa');

        const sofaTextFeatureKeys = ['sofaString', 'text', 'sofaText', 'coveredText', 'value'];
        const text = (() => {
            if (isFlatDocument && payload.text) return String(payload.text);
            if (documentPayload.text) return String(documentPayload.text);
            const textCandidate = sofaRows.find(row => {
                if (!row || !row.features) return false;
                return sofaTextFeatureKeys.some(key => row.features && String(row.features[key] || '').trim().length);
            });
            if (textCandidate && textCandidate.features) {
                for (const key of sofaTextFeatureKeys) {
                    const candidate = textCandidate.features[key];
                    if (typeof candidate === 'string' && candidate.trim().length) return candidate;
                }
            }
            return '';
        })();
        const fsId = Number(documentPayload.fsId || message.fsId || payload.fsId || 0);
        const types = Array.isArray(payload.annotationTypes) ? payload.annotationTypes
            : (Array.isArray(nested.annotationTypes) ? nested.annotationTypes
                : (Array.isArray(payload.types) ? payload.types : (Array.isArray(nested.types) ? nested.types : [])));
        const views = Array.isArray(payload.views) ? payload.views : (Array.isArray(nested.views) ? nested.views : []);
        const spans = Array.isArray(payload.spans) ? payload.spans
            : (Array.isArray(nested.spans) ? nested.spans
                : (Array.isArray(nested.annotations) ? nested.annotations : []));
        const normalizedSpans = spans
            .map(span => {
                if (!span || typeof span !== 'object') return null;
                const typeName = String(span.typeName || span.type || '');
                const typeCode = Number.isFinite(Number(span.typeCode)) ? Number(span.typeCode) : casTypeCode(typeName);
                const begin = Number(span.begin);
                const end = Number(span.end);
                return {
                    fsId: Number(span.fsId || span.id),
                    typeCode,
                    typeName,
                    artifactKind: 'annotation',
                    name: String(span.name || span.coveredText || ''),
                    label: shortLabel(typeName),
                    documentFsId: fsId,
                    sofaFsId: fsId,
                    viewName: String(span.viewName || '_InitialView'),
                    begin: Number.isFinite(begin) ? Math.max(0, begin) : 0,
                    end: Number.isFinite(end) ? Math.max(0, end) : 0,
                    coveredText: span.coveredText || '',
                    features: Object.assign({}, span.features || {}),
                    fs: {_id: Number(span.fsId || span.id || 0), _type: typeName, typeCode, features: Object.assign({}, span.features || {})}
                };
            })
            .filter(item => item && item.end >= item.begin);

        const normalizedViews = views.length ? views.map(view => ({
            name: String(view.name || '_InitialView'),
            defaultView: !!(view.defaultView || view.default),
            spanCount: Number(view.spanCount || 0)
        })) : [{name: '_InitialView', defaultView: true, spanCount: spans.length}];

        const normalizedTypes = types
            .map(type => {
                if (typeof type === 'string') {
                    return {typeCode: casTypeCode(type), typeName: type, label: shortLabel(type), count: 0};
                }
                if (!type || typeof type !== 'object') return null;
                const typeName = String(type.typeName || type.name || type.type || '');
                const typeCode = Number.isFinite(Number(type.typeCode)) ? Number(type.typeCode) : casTypeCode(typeName);
                const instanceCount = Number(type.count || 0);
                return Object.assign({}, type, {
                    typeCode,
                    typeName,
                    label: type.label || shortLabel(typeName),
                    count: type.count === undefined ? instanceCount : Number(type.count || 0)
                });
            })
            .filter(Boolean);

        const fallbackTypes = new Map();
        normalizedSpans.forEach(span => {
            const code = Number(span.typeCode || 0);
            if (!code) return;
            const nextCount = fallbackTypes.has(code) ? fallbackTypes.get(code).count + 1 : 1;
            fallbackTypes.set(code, {
                typeCode: code,
                typeName: String(span.typeName || ''),
                label: shortLabel(String(span.typeName || '')),
                count: nextCount
            });
        });
        const finalTypes = normalizedTypes.length ? normalizedTypes : Array.from(fallbackTypes.values());
        const metadataSummary = metadataSummaryFromRows(fsId, sofaRows, fsRows, {
            document: {
                title: String(documentPayload.title || '')
            }
        });

        return {
            fsId,
            document: {
                fsId,
                typeCode: Number(documentPayload.typeCode || casTypeCode('org.texttechnologylab.annotations.dua.Document')),
                typeName: String(documentPayload.typeName || 'org.texttechnologylab.annotations.dua.Document'),
                artifactKind: 'document',
                title: String(metadataSummary.title || documentPayload.title || ('Document ' + fsId)),
                text
            },
            views: normalizedViews,
            selectedView: normalizedViews[0].name,
            annotationTypes: finalTypes,
            spans: normalizedSpans,
            sofas: sofaRows,
            featureStructures: fsRows,
            metadataSummary,
            cas: casPayload
        };
    }

    function casTypeName(typeCode) {
        return (state.types || []).find(item => Number(item.typeCode) === Number(typeCode))?.name || '';
    }

    async function loadDocumentAnnotationRefs(annotationTypeCodes, maxRefs, documentFsId) {
        const perTypeLimit = Math.max(1, Math.floor(Number(maxRefs || 1200) / Math.max(1, Math.max(annotationTypeCodes.length, 1))));
        const refs = [];
        const seen = new Set();
        const typedFilter = Array.isArray(annotationTypeCodes) && annotationTypeCodes.length
            ? (state.types || []).filter(type => annotationTypeCodes.includes(Number(type.typeCode)) && Number.isFinite(Number(type.typeCode)))
            : (state.types || []);
        const annotationTypes = typedFilter.length
            ? typedFilter.filter(type => type.annotation || !type.artifact)
            : (state.types || []).filter(type => type.annotation || !type.artifact);
        const scan = annotationTypes.length ? annotationTypes : (state.types || []);
        for (const type of scan) {
            const entries = await queryRefs(type.typeCode, type.name, perTypeLimit + 20).catch(() => []);
            if (!Array.isArray(entries) || !entries.length) continue;
            for (const fsRef of entries) {
                const id = Number(fsRef);
                if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue;
                seen.add(id);
                refs.push({fsId: id, typeCode: Number(type.typeCode), typeName: type.name});
            }
            if (refs.length >= maxRefs) break;
        }
        return refs;
    }

    async function queryRefs(typeCode, typeName, limit) {
        const boundedLimit = Math.max(1, Math.min(500, Number(limit || 500)));
        const resolvedTypeCode = Number.isFinite(Number(typeCode)) ? Number(typeCode) : null;
        const resolvedTypeName = (typeof typeName === 'string' && typeName.trim()) ? typeName : (typeof typeCode === 'string' ? typeCode : '');
        const message = await wsQuery('instancesByType', {
            type: resolvedTypeName || undefined,
            typeCode: resolvedTypeCode,
            includeSubtypes: true,
            limit: boundedLimit,
            offset: 0
        });
        return parseSelectionRefs(message);
    }

    async function loadAnnotationsByFsRefs(annotationRefs, documentFsId) {
        const rows = [];
        const unique = Array.from(new Map(annotationRefs.map(item => [Number(item.fsId), item])).values()).slice(0, 1500);
        const concurrency = 16;
        for (let index = 0; index < unique.length; index += concurrency) {
            const batch = unique.slice(index, index + concurrency);
            const results = await Promise.allSettled(batch.map(item => buildAnnotationRow(item, documentFsId)));
            results.forEach(result => {
                if (result.status === 'fulfilled' && result.value) rows.push(result.value);
            });
        }
        return rows.filter(item => item);
    }

    async function buildAnnotationRow(item, documentFsId) {
        const fsPayload = await cachedFsQuery(item.fsId).catch(() => null);
        const spanPayload = await cachedSpanQuery(item.fsId).catch(() => null);
        const typeCode = Number(fsPayload && fsPayload.typeCode || item.typeCode || 0);
        const typeName = String(fsPayload && (fsPayload.type || fsPayload.typeName || item.typeName || casTypeName(typeCode) || '')).trim();
        const begin = Number(spanPayload && spanPayload.begin);
        const end = Number(spanPayload && spanPayload.end);
        const hasBegin = Number.isFinite(begin);
        const hasEnd = Number.isFinite(end);
        const beginPos = hasBegin ? Math.max(0, begin) : 0;
        const endPos = hasEnd ? Math.max(beginPos, end) : beginPos;
        if (!hasBegin || !hasEnd || endPos <= beginPos) {
            return null;
        }
        return {
            fsId: Number(item.fsId),
            typeCode,
            typeName,
            artifactKind: 'annotation',
            name: shortLabel(typeName || item.typeName || 'Annotation'),
            label: shortLabel(typeName || item.typeName || 'Annotation'),
            documentFsId: Number(documentFsId || 0),
            sofaFsId: Number(documentFsId || 0),
            viewName: '_InitialView',
            begin: beginPos,
            end: endPos,
            coveredText: String(spanPayload && spanPayload.coveredText || ''),
            features: Object.assign({}, fsPayload && fsPayload.features || {}),
            fs: {_id: Number(item.fsId), _type: typeName, typeCode, features: Object.assign({}, fsPayload && fsPayload.features || {})}
        };
    }

    async function loadFeatureStructuresByType(typeName, limit) {
        let refs = [];
        try {
            refs = await queryRefs(typeName, null, Math.max(1, Number(limit || 240)));
        } catch (error) {
            return [];
        }
        const rows = [];
        const unique = Array.from(new Set(refs.map(item => Number(item)).filter(id => Number.isFinite(id) && id > 0)));
        const concurrency = 16;
        for (let index = 0; index < unique.length; index += concurrency) {
            const batch = unique.slice(index, index + concurrency);
            const settled = await Promise.allSettled(batch.map(fsRef => cachedFsQuery(fsRef)));
            settled.forEach((result, batchIndex) => {
                const fsRef = batch[batchIndex];
                const fsPayload = result.status === 'fulfilled' ? result.value : null;
                if (!fsPayload) return;
                const typeCode = Number(fsPayload.typeCode || casTypeCode(typeName) || 0);
                const itemTypeName = String(fsPayload.type || fsPayload.typeName || typeName || '');
                rows.push({
                    fsId: fsRef,
                    typeCode,
                    typeName: itemTypeName,
                    name: String(fsPayload.name || fsPayload.label || ''),
                    documentFsId: Number(fsPayload.documentFsId || fsRef || 0),
                    sofaFsId: Number(fsPayload.sofaFsId || fsRef || 0),
                    features: fsPayload.features || {}
                });
            });
        }
        return rows;
    }

    function normalizeDocumentText(sofas, spans) {
        const sofaTextFeatureKeys = ['sofaString', 'text', 'sofaText', 'coveredText', 'value'];
        const row = Array.isArray(sofas) ? sofas.find(item => item && item.features && sofaTextFeatureKeys.some(key => String(item.features[key] || '').trim().length)) : null;
        if (row && row.features) {
            for (const key of sofaTextFeatureKeys) {
                const candidate = String(row.features[key] || '').trim();
                if (candidate.length) return candidate;
            }
        }
        return (Array.isArray(spans) ? spans : [])
            .map(item => String(item.coveredText || ''))
            .filter(Boolean)
            .join(' ')
            .slice(0, 16000) || 'No document text available.';
    }

    async function documentsForCorpus(corpus) {
        const documentType = state.types.find(item => item.annotation === false && item.artifact && isDocumentTypeName(item.name));
        const documentTypeCode = documentType ? Number(documentType.typeCode) : null;
        if (documentTypeCode) {
            try {
                const message = await typeInstancesBySelector({typeCode: documentTypeCode, type: documentType.name, offset: 0, limit: 1000}, documentType);
                const pagePayload = message && message.page ? message.page : null;
                const instances = pagePayload && Array.isArray(pagePayload.instances) ? pagePayload.instances : [];
                if (instances.length) {
                    return instances.map(instance => ({
                        fsId: Number(instance.fsId || 0),
                        typeCode: documentTypeCode,
                        artifactKind: 'document',
                        name: String(instance.name || instance.title || ''),
                        title: String(instance.title || instance.name || ''),
                        fs: {_id: Number(instance.fsId || 0), _type: documentType.name || 'org.texttechnologylab.annotations.dua.Document', typeCode: documentTypeCode, features: {}}
                    }));
                }
            } catch (error) {
                // fallback to direct CAS select below
            }
        }
        try {
            const response = await directCasSelectRefs('org.texttechnologylab.annotations.dua.Document', 100);
            let refs = Array.isArray(response) ? response : [];
            if (!refs.length) {
                refs = await directCasSelectRefs('cas:Sofa', 100);
            }
            const page = emptyPage();
            page.instances = refs.map(fsRef => ({fsId: fsRef, typeCode: casTypeCode('org.texttechnologylab.annotations.dua.Document'), artifactKind: 'document', name: 'Document fs ' + fsRef, title: 'Document ' + fsRef, fs: {_id: fsRef, _type: 'org.texttechnologylab.annotations.dua.Document', features: {}}}));
            return page.instances;
        } catch (error) {
            return [];
        }
    }

    async function fetchSofasAndFeatureStructures(documentFsId) {
        const featureStructureTypeCode = casTypeCode('uima.cas.TOP');
        const sofaTypeCode = casTypeCode('cas:Sofa');
        const requestWindow = {offset: 0, limit: 500};
        const responses = await Promise.allSettled([
            typeInstancesBySelector(Object.assign({}, requestWindow, {typeCode: featureStructureTypeCode, type: 'uima.cas.TOP'})),
            typeInstancesBySelector(Object.assign({}, requestWindow, {typeCode: sofaTypeCode, type: 'cas:Sofa'}))
        ]);
        const asRows = message => {
            const page = message && message.page ? message.page : null;
            return page && Array.isArray(page.instances) ? page.instances : [];
        };
        const featureStructures = responses[0].status === 'fulfilled' ? asRows(responses[0].value) : [];
        const sofas = responses[1].status === 'fulfilled' ? asRows(responses[1].value) : [];
        const targetDocument = Number(documentFsId || 0);
        return {
            sofas: sofas.filter(entry => Number(entry.documentFsId || entry.fsId || 0) === targetDocument || Number(entry.sofaFsId || entry.fsId || 0) === targetDocument),
            featureStructures: featureStructures.filter(entry => targetDocument === 0 || Number(entry.documentFsId || entry.sofaFsId || 0) === targetDocument)
        };
    }

    function defaultExpandedTypes() {
        return [
            casTypeCode('org.texttechnologylab.annotations.dua.Artifact'),
            casTypeCode('uima.tcas.Annotation')
        ].filter(Boolean);
    }

    function typeRow(typeCode, superTypeCode, name, label, artifact, annotation, instanceCount, features) {
        return {
            typeCode,
            superTypeCode,
            name,
            label,
            artifact,
            annotation,
            instanceCount,
            features: features.map((feature, index) => ({featureCode: typeCode * 100 + index, name: feature}))
        };
    }

    function buildChildren(types) {
        const codes = new Set(types.map(type => type.typeCode));
        const children = new Map();
        types.forEach(type => {
            const parent = codes.has(type.superTypeCode) ? type.superTypeCode : 0;
            if (!children.has(parent)) children.set(parent, []);
            children.get(parent).push(type);
        });
        children.forEach(list => list.sort((a, b) => String(a.label).localeCompare(String(b.label))));
        return children;
    }

    function findType(typeCode) {
        return state.types.find(item => Number(item.typeCode) === Number(typeCode)) ||
            state.documentStructureTypes.find(item => Number(item.typeCode) === Number(typeCode)) || null;
    }

    function normalizeDocumentStructureTypeCode(typeCode, typeName) {
        return -900000 - Math.abs(Number(typeCode || 0)) - Math.abs(casTypeCode(typeName || ''));
    }

    function documentStructureTypeRoots() {
        return state.documentStructureTypes || [];
    }

    function syncDocumentStructureTypes(document) {
        const previousCodes = Array.isArray(state.documentStructureTypeCodes) ? state.documentStructureTypeCodes.slice() : [];
        previousCodes.forEach(typeCode => state.expandedTypes.delete(typeCode));
        previousCodes.forEach(typeCode => state.instancesByType.delete(typeCode));
        state.documentStructureTypes = [];
        state.documentStructureTypeCodes = [];
        const sofas = Array.isArray(document && document.sofas) ? document.sofas : [];
        const featureStructures = Array.isArray(document && document.featureStructures) ? document.featureStructures : [];
        const all = [];
        if (sofas.length) {
            const sofaTypeCode = casTypeCode('cas:Sofa');
            const sofaCode = normalizeDocumentStructureTypeCode(sofaTypeCode, 'cas:Sofa');
            const page = {
                instances: sofas.map(structure => normalizeDocumentStructureInstance(structure, sofaTypeCode, 'cas:Sofa')),
                total: sofas.length,
                hasMore: false
            };
            state.instancesByType.set(sofaCode, page);
            state.documentStructureTypeCodes.push(sofaCode);
            all.push({
                typeCode: sofaCode,
                name: 'cas:Sofa',
                label: 'cas:Sofa',
                superTypeCode: 0,
                annotation: true,
                artifact: false,
                features: [],
                instanceCount: sofas.length,
                synthetic: true
            });
        }
        const grouped = new Map();
        featureStructures.forEach(structure => {
            const typeCode = Number(structure.typeCode || casTypeCode(structure.typeName || 'uima.cas.TOP'));
            const typeName = String(structure.typeName || structure.type || 'uima.cas.TOP');
            const key = String(typeCode) + '|' + typeName;
            if (!grouped.has(key)) grouped.set(key, {typeCode, typeName, instances: []});
            grouped.get(key).instances.push(structure);
        });
        grouped.forEach(group => {
            const syntheticCode = normalizeDocumentStructureTypeCode(group.typeCode, group.typeName);
            const instances = group.instances.map(structure => normalizeDocumentStructureInstance(structure, group.typeCode, group.typeName));
            state.instancesByType.set(syntheticCode, {
                instances,
                total: instances.length,
                hasMore: false
            });
            state.documentStructureTypeCodes.push(syntheticCode);
            all.push({
                typeCode: syntheticCode,
                name: group.typeName,
                label: shortLabel(group.typeName) + ' (Document)',
                superTypeCode: 0,
                annotation: true,
                artifact: false,
                features: [],
                instanceCount: instances.length,
                synthetic: true
            });
        });
        state.documentStructureTypes = all;
        state.documentStructureTypeCodes = all.length ? all.map(type => type.typeCode) : [];
    }

    function normalizeDocumentStructureInstance(structure, fallbackTypeCode, fallbackTypeName) {
        return {
            fsId: Number(structure.fsId || structure._id || structure.id || 0),
            typeCode: Number(structure.typeCode || fallbackTypeCode || 0),
            typeName: String(structure.typeName || structure.type || fallbackTypeName || ''),
            artifactKind: String(structure.artifactKind || 'feature-structure'),
            name: String(structure.coveredText || structure.label || structure.name || shortLabel(structure.typeName || fallbackTypeName || '') + ' fs ' + Number(structure.fsId || 0)),
            title: String(structure.title || structure.name || structure.label || shortLabel(structure.typeName || fallbackTypeName || '') + ' fs ' + Number(structure.fsId || 0)),
            fs: {
                _id: Number(structure.fsId || structure._id || 0),
                _type: String(structure.typeName || structure.type || fallbackTypeName || ''),
                typeCode: Number(structure.typeCode || fallbackTypeCode || 0),
                features: Object.assign({}, structure.features || {})
            }
        };
    }

    function sectionRoots(predicate) {
        const selected = new Set(state.types.filter(predicate).map(type => type.typeCode));
        return state.types.filter(type => selected.has(type.typeCode) && !selected.has(type.superTypeCode));
    }

    function artifactSectionRoots() {
        const root = state.types.find(type => type.name === 'org.texttechnologylab.annotations.dua.Artifact');
        return root ? [root] : sectionRoots(type => type.artifact);
    }

    function expandArtifactHierarchy() {
        const visit = type => {
            if (!type || !type.artifact) return;
            state.expandedTypes.add(Number(type.typeCode));
            (state.children.get(type.typeCode) || []).forEach(visit);
        };
        artifactSectionRoots().forEach(visit);
    }

    function expandTopTypeContainers(sections) {
        Object.values(sections || {}).forEach(roots => {
            (roots || []).forEach(type => {
                if (type && Number.isFinite(Number(type.typeCode))) {
                    state.expandedTypes.add(Number(type.typeCode));
                }
            });
        });
    }

    function normalizeTypeDetailsOnce(sections) {
        if (state.typeDetailsNormalized || !sections || !Array.isArray(sections.artifact) || !Array.isArray(sections.annotation)) return;
        const rootCodes = new Set([].concat(sections.artifact || [], sections.annotation || []).map(type => Number(type.typeCode)));
        (state.types || []).forEach(type => {
            if (type && !rootCodes.has(Number(type.typeCode))) {
                state.expandedTypes.delete(Number(type.typeCode));
            }
        });
        state.artifactExpansionNormalized = true;
        state.typeDetailsNormalized = true;
    }

    function annotationSectionRoots() {
        const root = state.types.find(type => type.name === 'uima.tcas.Annotation');
        return root ? [root] : sectionRoots(visibleAnnotationType);
    }

    function documentAnnotationLeafCodes() {
        return new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
    }

    function expandTypePath(typeCode) {
        let current = state.types.find(type => Number(type.typeCode) === Number(typeCode));
        while (current) {
            state.expandedTypes.add(current.typeCode);
            current = state.types.find(type => Number(type.typeCode) === Number(current.superTypeCode));
        }
    }

    function documentAnnotationTreeCodes() {
        const codes = documentAnnotationLeafCodes();
        if (!state.document) return null;
        let changed;
        do {
            changed = false;
            state.types.forEach(type => {
                if (codes.has(type.typeCode) && type.superTypeCode && !codes.has(type.superTypeCode)) {
                    codes.add(type.superTypeCode);
                    changed = true;
                }
            });
        } while (changed);
        return codes;
    }

    function pointerAnnotationTreeFilterCodes() {
        const instance = selectedVizInstance();
        if (!instance || instance.componentId !== 'pointer') return null;
        const anchor = selectedAnchor();
        const bindings = instance.bindings || {};
        let allowed = null;
        if (anchor && anchor.id === 'reference.feature' && bindings['source.type']) {
            allowed = new Set([Number(bindings['source.type'].typeCode)]);
        } else if (anchor && anchor.id === 'target.type' && bindings['reference.feature']) {
            const rangeCode = typeCodeFromFeatureRange(bindings['reference.feature']);
            if (rangeCode) allowed = descendantTypeCodes(rangeCode);
        }
        if (!allowed || !allowed.size) return null;
        const documentCodes = documentAnnotationTreeCodes();
        if (documentCodes && documentCodes.size) {
            allowed = new Set(Array.from(allowed).filter(typeCode => documentCodes.has(typeCode)));
        }
        return allowed.size ? allowed : null;
    }

    function typeCodeFromFeatureRange(binding) {
        const range = String(binding && (binding.featureRange || binding.range || '') || '').trim();
        if (!range) return 0;
        const found = state.types.find(type => type.name === range || shortLabel(type.name) === shortLabel(range));
        return found ? Number(found.typeCode) : 0;
    }

    function descendantTypeCodes(typeCode) {
        const codes = new Set();
        const visit = code => {
            const normalized = Number(code);
            if (!Number.isFinite(normalized) || codes.has(normalized)) return;
            codes.add(normalized);
            (state.children.get(normalized) || []).forEach(child => visit(child.typeCode));
        };
        visit(typeCode);
        return codes;
    }

    function branchHasAllowedType(type, allowedCodes) {
        if (!allowedCodes || allowedCodes.has(Number(type && type.typeCode))) return true;
        return (state.children.get(Number(type && type.typeCode)) || []).some(child => branchHasAllowedType(child, allowedCodes));
    }

    function visibleAnnotationType(type, allowedCodes) {
        const normalizedAllowedCodes = (allowedCodes instanceof Set) ? allowedCodes : null;
        if (!type || !type.annotation) return false;
        if (!isUimaAnnotationType(type)) return false;
        return !normalizedAllowedCodes || normalizedAllowedCodes.has(type.typeCode);
    }

    function branchHasBeginEndAnnotationType(type) {
        if (!type || !type.annotation) return false;
        if (isUimaAnnotationType(type)) return true;
        return (state.children.get(Number(type.typeCode)) || []).some(branchHasBeginEndAnnotationType);
    }

    function isUimaAnnotationType(type) {
        if (!type || !type.annotation) return false;
        if (hasBeginEndFeaturePair(type)) return true;
        let current = type;
        while (current) {
            if (current.name === 'uima.tcas.Annotation') return true;
            current = state.types.find(item => Number(item.typeCode) === Number(current.superTypeCode));
        }
        return false;
    }

    function hasBeginEndFeaturePair(type) {
        const labels = new Set();
        let current = type;
        while (current) {
            (current.features || []).forEach(feature => labels.add(shortLabel(feature.name || '').toLowerCase()));
            current = state.types.find(item => Number(item.typeCode) === Number(current.superTypeCode));
        }
        return labels.has('begin') && labels.has('end');
    }

    function ownSelectableFeatures(type) {
        const inherited = new Set();
        let parent = state.types.find(item => Number(item.typeCode) === Number(type.superTypeCode));
        while (parent) {
            (parent.features || []).forEach(feature => inherited.add(shortLabel(feature.name || '').toLowerCase()));
            parent = state.types.find(item => Number(item.typeCode) === Number(parent.superTypeCode));
        }
        return (type.features || []).filter(feature => {
            const label = shortLabel(feature.name || '').toLowerCase();
            return label !== 'begin' && label !== 'end' && !inherited.has(label);
        });
    }

    function activeBeginEndBadge(section, type) {
        if (section !== 'annotation') return '';
        return '<span class="duaviz-begin-end-badge"><b>begin</b><b>end</b></span>';
    }

    function beginEndValueBadge(instance) {
        const features = instance && instance.fs && instance.fs.features || instance && instance.features || {};
        const begin = numericFeatureValue(instance && instance.begin, features.begin);
        const end = numericFeatureValue(instance && instance.end, features.end);
        if (!Number.isFinite(begin) || !Number.isFinite(end)) return '';
        return '<span class="duaviz-begin-end-value-badge"><b>' + begin + '</b><b>' + end + '</b></span>';
    }

    function anchorFocusAllowsType(typeCode) {
        const component = selectedVizComponent();
        if (!component || component.bindingScope !== 'single-fstype') return true;
        const typeBinding = componentBindings(component.id)['fs.type'];
        return !typeBinding || Number(typeBinding.typeCode) === Number(typeCode);
    }

    function expandDocumentAnnotationTypes() {
        documentAnnotationTreeCodes().forEach(typeCode => state.expandedTypes.add(typeCode));
        state.expandedSections.add('annotation');
    }

    function typeCount(type) {
        const page = state.instancesByType.get(Number(type.typeCode));
        if (page && Number.isFinite(Number(page.total))) return Number(page.total || 0);
        const docType = (state.document && state.document.annotationTypes || []).find(item => Number(item.typeCode) === Number(type.typeCode));
        return Number(docType && docType.count || type.instanceCount || 0);
    }

    function branchMatches(type, filter) {
        if (!filter) return true;
        if (String(type.name || '').toLowerCase().includes(filter) || String(type.label || '').toLowerCase().includes(filter)) return true;
        return (state.children.get(type.typeCode) || []).some(child => branchMatches(child, filter));
    }

    function subtreeCount(typeCode) {
        return 1 + (state.children.get(typeCode) || []).reduce((sum, child) => sum + subtreeCount(child.typeCode), 0);
    }

    function findInstance(fsId) {
        for (const page of state.instancesByType.values()) {
            const found = (page.instances || []).find(instance => Number(instance.fsId) === fsId);
            if (found) return found;
        }
        return null;
    }

    function findDocumentStructureInstance(fsId) {
        const candidate = Number(fsId);
        const candidateInSofas = (state.document && state.document.sofas || []).find(entry => Number(entry.fsId || entry._id || 0) === candidate);
        if (candidateInSofas) return normalizeDocumentStructureInstance(candidateInSofas, casTypeCode('cas:Sofa'), 'cas:Sofa');
        const candidateInFeatureStructures = (state.document && state.document.featureStructures || []).find(entry => Number(entry.fsId || entry._id || 0) === candidate);
        if (candidateInFeatureStructures) return normalizeDocumentStructureInstance(candidateInFeatureStructures, casTypeCode('uima.cas.TOP'), 'uima.cas.TOP');
        const fromCache = findInstance(candidate);
        if (fromCache) return fromCache;
        return null;
    }

    function featureBadges(type) {
        return '';
    }

    function toggleAnnotationInstance(instance) {
        const fsId = Number(instance.fsId || 0);
        if (!fsId) return;
        if (state.selectedAnnotationFsIds.has(fsId)) state.selectedAnnotationFsIds.delete(fsId);
        else state.selectedAnnotationFsIds.add(fsId);
        renderFeaturePanel(instance);
        renderAccordions();
    }

    function isArtifactRoot(type) {
        return isArtifactTypeName(type && type.name);
    }

    function isArtifactTypeCode(typeCode) {
        const type = state.types.find(item => Number(item.typeCode) === Number(typeCode));
        return !!(type && type.artifact);
    }

    function isErrorPayload(value) {
        return !!(value && typeof value === 'object' && String(value.status || '').toLowerCase() === 'error');
    }

    function emptyPage() {
        return {instances: [], total: 0, hasMore: false};
    }

    function colorFor(typeCode, index) {
        typeCode = Number(typeCode);
        if (state.typeColorOverrides.has(typeCode)) return state.typeColorOverrides.get(typeCode);
        const instance = selectedVizInstance();
        if (instance && instance.componentId === 'highlighter' && instance.config && instance.config.color) return instance.config.color;
        if (!state.colors.has(typeCode)) state.colors.set(typeCode, palette[Math.abs(index || typeCode) % palette.length]);
        return state.colors.get(typeCode);
    }

    function colorForSpan(span, index) {
        const fsId = Number(span && span.fsId || 0);
        if (state.spanColorOverrides.has(fsId)) return state.spanColorOverrides.get(fsId);
        return colorFor(span.typeCode, index);
    }

    function opacityFor(typeCode) {
        const instance = selectedVizInstance();
        if (instance && instance.componentId === 'highlighter' && instance.config && instance.config.opacity) {
            return Math.max(0.2, Math.min(0.9, Number(instance.config.opacity) / 100));
        }
        const selector = '.duaviz-annotation-checkbox[data-type-code="' + Number(typeCode) + '"]';
        let row = $('.duaviz-doc-annotation-row').has(selector).first();
        const value = Number(row.find('.duaviz-annotation-opacity').val() || 52);
        return Math.max(0.2, Math.min(0.9, value / 100));
    }

    function opacityForSpan(span) {
        return opacityFor(span.typeCode);
    }

    function spanColorPopover(span, color) {
        return '<span class="duaviz-span-color-popover" data-fs-id="' + Number(span.fsId) + '">' +
            '<span class="duaviz-span-color-dot" style="background:' + escapeAttr(color) + '"></span>' +
            '<span class="duaviz-span-color-menu">' + harmonicColors.map(swatch =>
                '<button class="duaviz-span-color-swatch" type="button" data-color="' + escapeAttr(swatch) + '" style="background:' + escapeAttr(swatch) + '" title="' + escapeAttr(swatch) + '"></button>'
            ).join('') + '</span>' +
        '</span>';
    }

    function rgba(hex, opacity) {
        const clean = String(hex || '#f59e0b').replace('#', '');
        const intValue = parseInt(clean.length === 3 ? clean.split('').map(ch => ch + ch).join('') : clean, 16);
        return 'rgba(' + ((intValue >> 16) & 255) + ',' + ((intValue >> 8) & 255) + ',' + (intValue & 255) + ',' + opacity + ')';
    }

    function instanceLabel(instance) {
        return shortLabel(stripFsRefSuffix(instance.name || instance.title || instance.typeName || 'Feature structure'));
    }

    function numericFeatureValue(primary, fallback) {
        for (const value of [primary, fallback]) {
            if (value === undefined || value === null || value === '') continue;
            const numeric = Number(value);
            if (Number.isFinite(numeric)) return numeric;
        }
        return NaN;
    }

    function instanceFeatureValueLabel(features) {
        const keys = ['coveredText', 'value', 'lemma', 'text', 'name', 'label', 'identifier', 'id'];
        for (const key of keys) {
            const value = features && features[key];
            if (value === undefined || value === null) continue;
            const text = String(value).trim();
            if (text && text !== '_InitialView') return text.length > 32 ? text.slice(0, 29) + '...' : text;
        }
        return '';
    }

    function stripFsRefSuffix(value) {
        return String(value || '').replace(/\s+fs\s+\d+$/i, '').trim();
    }

    function structureLabel(structure) {
        if (!structure) return '';
        if (structure.typeName) return shortLabel(structure.typeName) + ' ' + Number(structure.fsId || 0);
        if (structure.name) return structure.name;
        return 'fs ' + Number(structure.fsId || 0);
    }

    function displayValue(value) {
        if (value == null) return '';
        if (Array.isArray(value)) return value.join(', ');
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
    }

    function setStatus(status) {
        root().attr('data-state', status);
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, char => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[char]));
    }

    function escapeAttr(value) {
        return escapeHtml(value).replace(/`/g, '&#96;');
    }

    function shortLabel(value) {
        const text = String(value || '');
        const colonIndex = text.lastIndexOf(':');
        const withoutColonPrefix = colonIndex >= 0 && colonIndex < text.length - 1 ? text.slice(colonIndex + 1) : text;
        const index = Math.max(withoutColonPrefix.lastIndexOf('.'), withoutColonPrefix.lastIndexOf('#'), withoutColonPrefix.lastIndexOf('/'));
        return index >= 0 && index < withoutColonPrefix.length - 1 ? withoutColonPrefix.slice(index + 1) : withoutColonPrefix;
    }

    window.initializeDuaviz = initializeDuaviz;
    window.duavizOpenCorpus = function (rootFsId) {
        return initializeDuaviz().then(async () => {
            if (!rootFsId) return;
            let corpus = findInstance(Number(rootFsId));
            if (corpus) await selectArtifact(corpus);
        });
    };
    window.duavizOpenDocument = function (documentFsId) {
        if (!documentFsId) return Promise.resolve();
        return initializeDuaviz().then(() => documentForArtifact(Number(documentFsId))).then(document => {
            state.document = document;
            state.mode = 'reader';
            state.selectedView = state.document && state.document.selectedView || '_InitialView';
            state.checkedAnnotationTypes = new Set((state.document && state.document.annotationTypes || []).map(type => Number(type.typeCode)));
            syncDocumentStructureTypes(state.document);
            expandDocumentAnnotationTypes();
            renderReader();
            renderAccordions();
        });
    };

    function bootVisibleDuaviz() {
        if (!root().length) {
            return;
        }
        const duavizView = $('.main-content-container .view[data-id="duaviz"]');
        const routeView = window.uceUiState && window.uceUiState.get ? window.uceUiState.get('view') : '';
        if (!duavizView.is(':visible') && routeView !== 'duaviz') {
            return;
        }
        window.setTimeout(() => initializeDuaviz(), 0);
        window.setTimeout(ensureVisibleSurface, 900);
        window.setTimeout(ensureVisibleSurface, 2200);
    }
    bootVisibleDuaviz();
    $(bootVisibleDuaviz);
})();
</#noparse>
