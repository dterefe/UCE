<#noparse>
(function installDuaClient(global) {
    global.UCE = global.UCE || {};
    if (global.DUAClient && global.DUAClient.__uceDuaClient) return;
    if (!global.UCE.RequestHandler) throw new Error('DUAClient requires UCE.RequestHandler.');

    const RequestHandler = global.UCE.RequestHandler;
    const models = global.UCE.DUAModels || {operations: {}};

    function wsBase() {
        const protocol = global.location && global.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = global.location && global.location.host ? global.location.host : '127.0.0.1';
        return protocol + '//' + host;
    }

    function normalizeWsUrl(value) {
        let url = String(value || '').trim();
        if (!url) url = '/ws/duaviz';
        if (url.startsWith('http://') || url.startsWith('https://')) {
            const parsed = new URL(url);
            if (global.location && parsed.host === global.location.host) {
                parsed.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
                parsed.pathname = '/ws/duaviz';
                parsed.search = '';
                parsed.hash = '';
                return parsed.toString().replace(/\/+$/, '');
            }
            parsed.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
            parsed.port = String(Number(parsed.port || (parsed.protocol === 'wss:' ? 443 : 80)) + 1);
            parsed.pathname = parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : '/';
            parsed.search = '';
            parsed.hash = '';
            return parsed.toString().replace(/\/+$/, '');
        }
        if (url.startsWith('/')) return wsBase() + url;
        if (!/^wss?:\/\//i.test(url)) return wsBase() + '/' + url.replace(/^\/+/, '');
        return url.replace(/\/+$/, '');
    }

    function configuredEndpoint() {
        return normalizeWsUrl(
            global.uceDuaWsUrl ||
            global.uceDuavizWsUrl ||
            global.uceDuavizWsPath ||
            global.uceDuavizHttpUrl ||
            global.uceDuavizEndpoint ||
            '/ws/duaviz'
        );
    }

	    function configuredHttpEndpoint() {
	        return String(
	                global.uceDuavizHttpUrl ||
	                global.uceDuavizEndpoint ||
	                ''
	        ).replace(/\/+$/, '');
	    }

    function asArray(value) {
        return Array.isArray(value) ? value : [];
    }

    function duaData(message) {
        if (message && typeof message === 'object' && message.result && typeof message.result === 'object') return message.result;
        if (message && typeof message === 'object' && message.payload && typeof message.payload === 'object') return message.payload;
        if (message && typeof message === 'object' && message.data && typeof message.data === 'object') return message.data;
        return message || {};
    }

    function shortName(value) {
        const text = String(value || '');
        const index = Math.max(text.lastIndexOf('.'), text.lastIndexOf(':'), text.lastIndexOf('/'), text.lastIndexOf('#'));
        return index >= 0 && index < text.length - 1 ? text.slice(index + 1) : text;
    }

    function typeRows(message) {
        const data = duaData(message);
        const source = data && (
                data.types ||
                data.schema && data.schema.types ||
                data.selection && data.selection.types
        );
        return asArray(source).map(entry => {
            if (typeof entry === 'string') {
                return {name: entry, typeName: entry, label: shortName(entry), features: []};
            }
            if (!entry || typeof entry !== 'object') return null;
            const name = String(entry.name || entry.typeName || entry.type || '').trim();
            if (!name) return null;
            return Object.assign({}, entry, {
                name,
                typeName: name,
                label: entry.label || shortName(name),
                typeCode: Number(entry.typeCode || entry.code || 0),
                superTypeCode: Number(entry.superTypeCode || entry.parentTypeCode || 0),
                features: asArray(entry.features)
            });
        }).filter(Boolean);
    }

    function pagePayload(message) {
        message = duaData(message);
        if (!message || typeof message !== 'object') return {};
        if (message.page && typeof message.page === 'object') return message.page;
        if (message.selection && typeof message.selection === 'object' && !Array.isArray(message.selection)) return message.selection.page || message.selection;
        if (message.payload && typeof message.payload === 'object') return message.payload.page || message.payload;
        if (message.data && typeof message.data === 'object') return message.data.page || message.data;
        return message;
    }

    function instancePage(message, request) {
        const page = pagePayload(message);
        const rawInstances = asArray(page.instances);
        const refs = asArray(page.fsRefs)
                .concat(asArray(page.selection))
                .concat(rawInstances.map(item => Number(item && (item.fsId || item.fsRef || item.id || item._id || 0))).filter(Number.isFinite));
        const fsRefs = Array.from(new Set(refs.map(Number).filter(Number.isFinite)));
        const typeCode = Number(request && request.typeCode || 0);
        const typeName = String(request && request.type || '');
        const source = rawInstances.length ? rawInstances : fsRefs;
        const instances = source.map((item, index) => {
            if (Number.isFinite(Number(item))) {
                const fsRef = Number(item);
                return {fsId: fsRef, fsRef, typeCode, typeName, label: typeName ? shortName(typeName) + ' ' + fsRef : 'FS ' + fsRef};
            }
            if (!item || typeof item !== 'object') return null;
            const fsRef = Number(item.fsId || item.fsRef || item.id || item._id || (item.fs && (item.fs._id || item.fs.fsId)) || fsRefs[index] || 0);
            return Object.assign({}, item, {
                fsId: fsRef,
                fsRef,
                typeCode: Number(item.typeCode || typeCode || 0),
                typeName: String(item.typeName || item.type || typeName || ''),
                label: item.label || item.title || item.name || (typeName ? shortName(typeName) + ' ' + fsRef : 'FS ' + fsRef)
            });
        }).filter(Boolean);
        return {
            page: {
                instances,
                fsRefs: fsRefs.length ? fsRefs : instances.map(item => Number(item.fsId)).filter(Number.isFinite),
                cas: page.cas || message && message.cas || [],
                offset: Number(page.offset || request && request.offset || 0),
                limit: Number(page.limit || request && request.limit || instances.length || 0),
                total: Number(page.total || instances.length || fsRefs.length || 0),
                hasMore: Boolean(page.hasMore)
            }
        };
    }

    function featureStructure(message) {
        const data = duaData(message);
        const source = data && data.fs && typeof data.fs === 'object' ? data.fs : data;
        if (!source || typeof source !== 'object') return source;
        const fsRef = Number(source.fsId || source.fsRef || source._id || 0);
        return Object.assign({}, source, {
            fsId: fsRef,
            fsRef,
            _id: Number(source._id || fsRef || 0),
            typeCode: Number(source.typeCode || 0),
            typeName: String(source.typeName || source.type || source._type || ''),
            type: String(source.type || source.typeName || source._type || ''),
            features: Object.assign({}, source.features || {})
        });
    }

    function documentPayload(message) {
        message = duaData(message);
        const document = Object.assign({}, message && message.document || {});
        const features = document.features || {};
        const text = String(message && message.text || document.text || document.sofaString || features.sofaString || features.text || '');
        document.text = text;
        return Object.assign({}, message || {}, {
            document,
            text,
            spans: asArray(message && (message.spans || message.annotations)),
            annotationTypes: asArray(message && message.annotationTypes),
            views: asArray(message && message.views).length ? message.views : [{name: '_InitialView', defaultView: true}],
            selectedView: message && (message.selectedView || message.defaultView) || '_InitialView'
        });
    }

	    RequestHandler.registerHandle(RequestHandler.HandleName.DUA, {
	        kind: RequestHandler.HandleKind.WS_RPC,
	        url: configuredEndpoint(),
	        operations: models.operations || {},
	        defaultTimeoutMs: 5000
	    });

	    RequestHandler.registerHandle(RequestHandler.HandleName.DUA_HTTP, {
	        kind: RequestHandler.HandleKind.HTTP_JSON,
	        baseUrl: configuredHttpEndpoint(),
	        operations: models.operations || {},
	        defaultTimeoutMs: 5000
	    });

	    async function raw(action, payload, options) {
	        return RequestHandler.request(RequestHandler.HandleName.DUA, action, payload || {}, options || {});
	    }

	    async function http(operation, payload, options) {
	        RequestHandler.registerHandle(RequestHandler.HandleName.DUA_HTTP, {
	            kind: RequestHandler.HandleKind.HTTP_JSON,
	            baseUrl: configuredHttpEndpoint(),
	            operations: models.operations || {},
	            defaultTimeoutMs: 5000
	        });
	        return RequestHandler.request(RequestHandler.HandleName.DUA_HTTP, operation, payload || {}, options || {});
	    }

	    async function directCas(url, payload, options) {
	        const action = String(payload && (payload.action || payload.op) || '').trim();
	        const handleName = 'DUAVIZ_DIRECT_CAS_WS';
	        RequestHandler.registerHandle(handleName, {
	            kind: RequestHandler.HandleKind.WS_RPC,
	            url,
	            operations: {
	                [action]: {action, responseKind: 'json'}
	            },
	            defaultTimeoutMs: Number(options && options.timeoutMs || 2000)
	        });
	        return RequestHandler.request(handleName, action, payload || {}, options || {});
	    }

	    function httpSelectPayload(request) {
	        const payload = Object.assign({}, request || {});
	        if (payload.type === undefined && payload.fallbackType !== undefined) payload.type = payload.fallbackType;
	        if (payload.limit === undefined) payload.limit = 100;
	        if (payload.includeSubtypes === undefined) payload.includeSubtypes = true;
	        delete payload.fallbackType;
	        delete payload.typeCode;
	        return payload;
	    }

	    async function httpCas(action, payload) {
	        const op = String(action || '');
	        const request = Object.assign({}, payload || {});
	        if (op === 'types') {
	            const result = await http('http.schema', {t: Date.now()});
	            return {op: 'types', types: result.types || [], schema: {types: result.types || []}};
	        }
	        if (op === 'telemetry') {
	            return {op: 'telemetry', stats: await http('http.stats', {t: Date.now()})};
	        }
	        if (op === 'select' || op === 'instancesByType') {
	            const result = await http('http.select', Object.assign(httpSelectPayload(request), {t: Date.now()}));
	            return {op, fsRefs: result.fsRefs || [], page: instancePage({fsRefs: result.fsRefs || []}, request).page};
	        }
	        if (op === 'fs') {
	            return Object.assign({op: 'fs'}, await http('http.fs', {fsRef: request.fsRef, t: Date.now()}));
	        }
	        if (op === 'span') {
	            if (request.begin !== undefined || request.end !== undefined || request.predicate) {
	                return Object.assign({op: 'span'}, await http('http.spanQuery', {
	                    type: request.type || '',
	                    predicate: request.predicate || 'overlapping',
	                    sofaFsRef: request.sofaFsRef || 0,
	                    begin: request.begin || 0,
	                    end: request.end || Number.MAX_SAFE_INTEGER,
	                    limit: request.limit || 100,
	                    includeSubtypes: request.includeSubtypes !== false,
	                    t: Date.now()
	                }));
	            }
	            return Object.assign({op: 'span'}, await http('http.span', {fsRef: request.fsRef, t: Date.now()}));
	        }
	        throw new Error('No DUA HTTP mapping for op ' + op);
	    }

    async function request(action, payload, options) {
        const op = String(action || '');
        if (op === 'types' || op === 'typesystem.get') {
            const message = await raw(op, payload || {}, options);
            const types = typeRows(message);
            return Object.assign({}, message, {op: 'types', types, schema: {types}});
        }
        if (op === 'instancesByType' || op === 'select' || op === 'fs.select') {
            const message = await raw(op, payload || {}, options);
            return Object.assign({}, message, {op: op}, instancePage(message, payload || {}));
        }
        if (op === 'fs' || op === 'fs.get') return Object.assign({op: 'fs'}, featureStructure(await raw(op, payload || {}, options)));
        if (op === 'span' || op === 'span.get') return Object.assign({op: 'span'}, await raw(op, payload || {}, options));
        if (op === 'document' || op === 'document.reader') return Object.assign({op: 'document'}, documentPayload(await raw(op, payload || {}, options)));
        return raw(op, payload || {}, options);
    }

    const api = {
        __uceDuaClient: true,
        endpoint: () => configuredEndpoint(),
	        raw,
	        http,
	        httpCas,
	        directCas,
	        request,
        typesystem: async payload => (await request('types', payload || {})).types,
        type: {
            children: typeCode => request('type.children', {typeCode})
        },
        fs: Object.assign(
                async fsRef => featureStructure(await raw('fs', {fsRef})),
                {
                    select: async payload => (await request('fs.select', payload || {})).page,
                    get: fsRef => request('fs.get', {fsRef})
                }
        ),
        selectFs: async payload => (await request('instancesByType', payload || {})).page,
        span: Object.assign(
                fsRef => raw('span', {fsRef}),
                {get: fsRef => request('span.get', {fsRef})}
        ),
        graph: Object.assign(
                payload => raw('graph', payload || {}),
                {get: payload => request('graph.get', payload || {})}
        ),
        sofa: {
            text: payload => request('sofa.text', payload || {})
        },
        document: Object.assign(
                async (documentFsRef, payload) => documentPayload(await raw('document', Object.assign({}, payload || {}, {documentFsRef}))),
                {
                    reader: (documentFsRef, payload) => request('document.reader', Object.assign({}, payload || {}, {documentFsRef}))
                }
        ),
        search: Object.assign(
                payload => raw('search', payload || {}),
                {query: payload => request('search.query', payload || {})}
        ),
        lexicon: {
            entries: payload => request('lexicon.entries', payload || {})
        },
	        geo: {
	            occurrences: payload => request('geo.occurrences', payload || {})
	        },
	        schema: {
	            get: () => http('http.schema', {t: Date.now()})
	        }
	    };

    global.DUAClient = api;
    global.uceDuaClient = api;
})(window);
</#noparse>
