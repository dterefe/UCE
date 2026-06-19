<#noparse>
(function installDuaClient(global) {
    if (global.DUAClient && global.DUAClient.__uceDuaClient) return;

    const DEFAULT_TIMEOUT_MS = 5000;

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
            parsed.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
            parsed.port = String(Number(parsed.port || (parsed.protocol === 'wss:' ? 443 : 80)) + 1);
            parsed.pathname = '/';
            parsed.search = '';
            parsed.hash = '';
            url = parsed.toString();
        }
        if (url.startsWith('/')) url = wsBase() + url;
        if (!/^wss?:\/\//i.test(url)) url = wsBase() + '/' + url.replace(/^\/+/, '');
        return url.replace(/\/+$/, '');
    }

    function configuredEndpoint() {
        return normalizeWsUrl(
            global.uceDuaWsUrl ||
            global.uceDuavizWsUrl ||
            global.uceDuavizHttpUrl ||
            global.uceDuavizEndpoint ||
            global.uceDuavizWsPath ||
            '/ws/duaviz'
        );
    }

    class DuaSocket {
        constructor(url) {
            this.url = url;
            this.sequence = 0;
            this.socket = null;
            this.ready = null;
            this.pending = new Map();
            this.order = [];
            this.connect();
        }

        nextId() {
            this.sequence += 1;
            return 'dua-' + Date.now() + '-' + this.sequence;
        }

        connect() {
            if (this.socket && this.socket.readyState === WebSocket.OPEN) return Promise.resolve(this.socket);
            if (this.ready) return this.ready;
            this.ready = new Promise((resolve, reject) => {
                this.socket = new WebSocket(this.url);
                this.socket.onopen = () => {
                    console.log(123421313)
                    resolve(this.socket);
                }
                this.socket.onerror = () => {
                    const error = new Error('DUA websocket failed: ' + this.url);
                    this.rejectAll(error);
                    reject(error);
                };
                this.socket.onclose = () => {
                    this.socket = null;
                    this.ready = null;
                    this.rejectAll(new Error('DUA websocket closed.'));
                };
                this.socket.onmessage = event => this.receive(event);
            });
            return this.ready;
        }

        receive(event) {
            let message;
            try {
                message = JSON.parse(event.data || '{}');
            } catch (error) {
                this.settle('', false, error);
                return;
            }
            const eventName = String(message.event || message.type || message.op || '').toLowerCase();
            if (eventName === 'ready' || eventName === 'connected' || eventName === 'hello') return;
            if (message.error || message.ok === false || Number(message.status || 0) >= 400) {
                this.settle(this.messageId(message), false, new Error(String(message.message || message.error || 'DUA request failed.')));
                return;
            }
            this.settle(this.messageId(message), true, message);
        }

        messageId(message) {
            return String(message && (message.requestId || message.id || message.correlationId) || '');
        }

        frame(action, payload, requestId) {
            const body = payload && typeof payload === 'object' ? Object.assign({}, payload) : {};
            const message = {
                requestId,
                id: requestId,
                action,
                op: action,
                payload: body
            };
            Object.keys(body).forEach(key => {
                if (key === 'requestId' || key === 'id' || key === 'action' || key === 'op' || key === 'payload') return;
                message[key] = body[key];
            });
            return message;
        }

        async send(action, payload, options) {
            const requestId = this.nextId();
            const socket = await this.connect();
            return new Promise((resolve, reject) => {
                const timer = global.setTimeout(() => {
                    this.pending.delete(requestId);
                    const index = this.order.indexOf(requestId);
                    if (index >= 0) this.order.splice(index, 1);
                    reject(new Error('DUA request timed out: ' + action));
                }, Number(options && options.timeoutMs) || DEFAULT_TIMEOUT_MS);
                this.pending.set(requestId, {resolve, reject, timer});
                this.order.push(requestId);
                socket.send(JSON.stringify(this.frame(action, payload || {}, requestId)));
            });
        }

        settle(requestId, ok, value) {
            let id = String(requestId || '');
            if (!id || !this.pending.has(id)) id = this.order.shift() || '';
            if (!id || !this.pending.has(id)) return;
            const entry = this.pending.get(id);
            this.pending.delete(id);
            const index = this.order.indexOf(id);
            if (index >= 0) this.order.splice(index, 1);
            global.clearTimeout(entry.timer);
            if (ok) entry.resolve(value);
            else entry.reject(value);
        }

        rejectAll(error) {
            this.pending.forEach(entry => {
                global.clearTimeout(entry.timer);
                entry.reject(error);
            });
            this.pending.clear();
            this.order.splice(0, this.order.length);
        }
    }

    function asArray(value) {
        return Array.isArray(value) ? value : [];
    }

    function shortName(value) {
        const text = String(value || '');
        const index = Math.max(text.lastIndexOf('.'), text.lastIndexOf(':'), text.lastIndexOf('/'), text.lastIndexOf('#'));
        return index >= 0 && index < text.length - 1 ? text.slice(index + 1) : text;
    }

    function typeRows(message) {
        const source = message && (
            message.types ||
            message.schema && message.schema.types ||
            message.selection && message.selection.types ||
            message.payload && message.payload.types ||
            message.data && message.data.types
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
        const source = message && message.fs && typeof message.fs === 'object' ? message.fs : message;
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

    const socket = new DuaSocket(configuredEndpoint());

    async function request(action, payload, options) {
        if (action === 'types') {
            const message = await socket.send('types', payload || {}, options);
            const types = typeRows(message);
            return Object.assign({}, message, {op: 'types', types, schema: {types}});
        }
        if (action === 'instancesByType' || action === 'select') {
            const message = await socket.send('instancesByType', payload || {}, options);
            return Object.assign({}, message, {op: action}, instancePage(message, payload || {}));
        }
        if (action === 'fs') return Object.assign({op: 'fs'}, featureStructure(await socket.send('fs', payload || {}, options)));
        if (action === 'span') return Object.assign({op: 'span'}, await socket.send('span', payload || {}, options));
        if (action === 'graph') return Object.assign({op: 'graph'}, await socket.send('graph', payload || {}, options));
        if (action === 'document') return Object.assign({op: 'document'}, documentPayload(await socket.send('document', payload || {}, options)));
        return socket.send(action, payload || {}, options);
    }

    const api = {
        __uceDuaClient: true,
        endpoint: () => socket.url,
        raw: (action, payload, options) => socket.send(action, payload || {}, options),
        request,
        typesystem: async payload => (await request('types', payload || {})).types,
        selectFs: async payload => (await request('instancesByType', payload || {})).page,
        fs: async fsRef => featureStructure(await socket.send('fs', {fsRef})),
        span: fsRef => socket.send('span', {fsRef}),
        graph: payload => socket.send('graph', payload || {}),
        document: async (documentFsRef, payload) => documentPayload(await socket.send('document', Object.assign({}, payload || {}, {documentFsRef}))),
        search: payload => socket.send('search', payload || {})
    };

    global.DUAClient = api;
    global.uceDuaClient = api;
})(window);
</#noparse>
