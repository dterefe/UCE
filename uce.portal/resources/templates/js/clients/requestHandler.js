<#noparse>
(function installRequestHandler(global) {
    global.UCE = global.UCE || {};
    if (global.UCE.RequestHandler && global.UCE.RequestHandler.__uceRequestHandler) return;

    const HandleKind = Object.freeze({
        HTTP_JSON: 'HTTP_JSON',
        HTTP_HTML: 'HTTP_HTML',
        HTTP_TEXT: 'HTTP_TEXT',
        WS_RPC: 'WS_RPC',
        WS_STREAM: 'WS_STREAM'
    });
	    const HandleName = Object.freeze({
	        DUA: 'DUA',
	        DUA_HTTP: 'DUA_HTTP',
	        UCE_BACKEND: 'UCE_BACKEND',
	        DUAVIZ_LEGACY_WS: 'DUAVIZ_LEGACY_WS'
	    });
    const HandleState = Object.freeze({
        IDLE: 'idle',
        CONNECTING: 'connecting',
        OPEN: 'open',
        CLOSING: 'closing',
        CLOSED: 'closed',
        FAILED: 'failed'
    });
    const CachePolicy = Object.freeze({
        NONE: 'none',
        MEMORY: 'memory',
        DEDUPE_INFLIGHT: 'dedupe_inflight'
    });

    const DEFAULT_TIMEOUT_MS = 5000;
    const handles = new Map();
    const memoryCache = new Map();
    const inflight = new Map();
    const listeners = new Map();
    const payloadEntries = [];
    let sequence = 0;

    function payloadShape(value, depth) {
        depth = depth || 0;
        if (depth > 3) return typeof value;
        if (Array.isArray(value)) return {kind: 'array', length: value.length, item: value.length ? payloadShape(value[0], depth + 1) : 'empty'};
        if (!value || typeof value !== 'object') return typeof value;
        const entries = Object.entries(value);
        return {
            kind: 'object',
            keys: Object.keys(value).slice(0, 24),
            fields: Object.fromEntries(entries.slice(0, 12).map(([key, entry]) => [key, payloadShape(entry, depth + 1)]))
        };
    }

    function tracePayload(entry) {
        payloadEntries.unshift(Object.assign({
            id: nextRequestId('payload'),
            at: new Date().toISOString(),
            shape: payloadShape(entry.response)
        }, entry));
        if (payloadEntries.length > 120) payloadEntries.splice(120);
    }

    function nextRequestId(prefix) {
        sequence += 1;
        return String(prefix || 'uce') + '-' + Date.now() + '-' + sequence;
    }

    function emit(eventName, payload) {
        const callbacks = listeners.get(eventName);
        if (!callbacks) return;
        callbacks.forEach(callback => {
            try {
                callback(payload);
            } catch (error) {
                if (global.console && console.warn) console.warn('[UCE][RequestHandler] listener failed', error);
            }
        });
    }

    function subscribe(eventName, listener) {
        const key = String(eventName || '');
        if (!listeners.has(key)) listeners.set(key, new Set());
        listeners.get(key).add(listener);
        return () => listeners.get(key) && listeners.get(key).delete(listener);
    }

    function state(handleName) {
        const handle = handles.get(String(handleName || ''));
        return handle ? handle.state : HandleState.CLOSED;
    }

    function registerHandle(name, config) {
        const handleName = String(name || '');
        if (!handleName) throw new Error('RequestHandler handle name is required.');
        const existing = handles.get(handleName) || {};
        const next = Object.assign(existing, {
            name: handleName,
            kind: String(config && config.kind || existing.kind || HandleKind.HTTP_JSON),
            baseUrl: config && config.baseUrl !== undefined ? String(config.baseUrl || '') : String(existing.baseUrl || ''),
            url: config && config.url !== undefined ? String(config.url || '') : String(existing.url || ''),
            operations: Object.assign({}, existing.operations || {}, config && config.operations || {}),
            defaultTimeoutMs: Number(config && config.defaultTimeoutMs || existing.defaultTimeoutMs || DEFAULT_TIMEOUT_MS),
            state: existing.state || HandleState.IDLE,
            socket: existing.socket || null,
            ready: existing.ready || null,
            pending: existing.pending || new Map(),
            order: existing.order || []
        });
        handles.set(handleName, next);
        emit('handle:registered', {handleName, config: next});
        return next;
    }

    function operationConfig(handle, operation, options) {
        const op = String(operation || '');
        return Object.assign({}, handle && handle.operations && handle.operations[op] || {}, options && options.operation || {});
    }

    function cacheKey(handleName, operation, payload, options) {
        if (options && options.cacheKey) return String(options.cacheKey);
        return [handleName, operation, JSON.stringify(payload || {})].join('::');
    }

    function invalidate(cacheKeyOrPrefix) {
        const prefix = String(cacheKeyOrPrefix || '');
        if (!prefix) {
            memoryCache.clear();
            return;
        }
        Array.from(memoryCache.keys()).forEach(key => {
            if (String(key).startsWith(prefix)) memoryCache.delete(key);
        });
    }

    function normalizeWsUrl(value) {
        let url = String(value || '').trim();
        if (!url) return '';
        const protocol = global.location && global.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = global.location && global.location.host ? global.location.host : '127.0.0.1';
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
        if (url.startsWith('//')) return protocol + url;
        if (url.startsWith('/')) return protocol + '//' + host + url;
        if (/^wss?:\/\//i.test(url)) return url;
        if (url.includes(':')) return protocol + '//' + url;
        return protocol + '//' + host + '/' + url.replace(/^\/+/, '');
    }

    function normalizeHttpUrl(baseUrl, path, query) {
        const base = String(baseUrl || '').replace(/\/+$/, '');
        const rawPath = String(path || '');
        let url = rawPath;
        if (!/^https?:\/\//i.test(rawPath)) {
            url = (base || '') + (rawPath.startsWith('/') ? rawPath : '/' + rawPath);
        }
        const params = new URLSearchParams();
        Object.entries(query || {}).forEach(([key, value]) => {
            if (value === undefined || value === null || value === '') return;
            params.set(key, String(value));
        });
        const suffix = params.toString();
        return suffix ? url + (url.includes('?') ? '&' : '?') + suffix : url;
    }

    function frameFor(operation, payload, requestId, opConfig) {
        const action = String(opConfig.action || operation || payload && (payload.action || payload.op) || '').trim();
        const body = payload && typeof payload === 'object' ? Object.assign({}, payload) : {};
        const frame = {
            requestId,
            id: requestId,
            action,
            op: action,
            payload: body
        };
        Object.keys(body).forEach(key => {
            if (key === 'requestId' || key === 'id' || key === 'action' || key === 'op' || key === 'payload') return;
            frame[key] = body[key];
        });
        return frame;
    }

    function messageRequestId(message) {
        return String(message && (message.requestId || message.id || message.correlationId) || '');
    }

    function isReadyMessage(message) {
        const name = String(message && (message.event || message.type || message.op || '') || '').toLowerCase();
        return !messageRequestId(message) && (name === 'ready' || name === 'connected' || name === 'hello');
    }

    function isErrorMessage(message) {
        return !!(message && (message.error || message.ok === false || Number(message.status || 0) >= 400));
    }

    function rejectWsPending(handle, error) {
        handle.pending.forEach(entry => {
            global.clearTimeout(entry.timer);
            entry.reject(error);
        });
        handle.pending.clear();
        handle.order.splice(0, handle.order.length);
    }

    function connectWs(handle) {
        if (handle.socket && handle.socket.readyState === WebSocket.OPEN) return Promise.resolve(handle.socket);
        if (handle.ready) return handle.ready;
        const url = normalizeWsUrl(handle.url || handle.baseUrl);
        if (!url) return Promise.reject(new Error('Missing websocket URL for handle ' + handle.name));
        handle.state = HandleState.CONNECTING;
        emit('handle:state', {handleName: handle.name, state: handle.state});
        handle.ready = new Promise((resolve, reject) => {
            handle.socket = new WebSocket(url);
            handle.socket.onopen = () => {
                handle.state = HandleState.OPEN;
                emit('handle:state', {handleName: handle.name, state: handle.state});
                resolve(handle.socket);
            };
            handle.socket.onerror = () => {
                handle.state = HandleState.FAILED;
                emit('handle:state', {handleName: handle.name, state: handle.state});
                const error = new Error('WebSocket failed for handle ' + handle.name);
                rejectWsPending(handle, error);
                reject(error);
            };
            handle.socket.onclose = () => {
                handle.state = HandleState.CLOSED;
                handle.ready = null;
                handle.socket = null;
                emit('handle:state', {handleName: handle.name, state: handle.state});
                rejectWsPending(handle, new Error('WebSocket closed for handle ' + handle.name));
            };
            handle.socket.onmessage = event => {
                let message;
                try {
                    message = JSON.parse(event.data || '{}');
                } catch (error) {
                    const fallbackId = handle.order.shift() || '';
                    const fallback = handle.pending.get(fallbackId);
                    if (fallback) {
                        global.clearTimeout(fallback.timer);
                        handle.pending.delete(fallbackId);
                        fallback.reject(error);
                    }
                    return;
                }
                if (isReadyMessage(message)) return;
                const explicitId = messageRequestId(message);
                const requestId = explicitId && handle.pending.has(explicitId) ? explicitId : (handle.order.shift() || '');
                const entry = requestId ? handle.pending.get(requestId) : null;
                if (!entry) {
                    emit('message:unmatched', {handleName: handle.name, message});
                    return;
                }
                handle.pending.delete(requestId);
                handle.order = handle.order.filter(id => id !== requestId);
                global.clearTimeout(entry.timer);
                if (isErrorMessage(message)) {
                    entry.reject(new Error(String(message.message || message.error || 'Request failed.')));
                } else {
                    entry.resolve(message);
                }
            };
        });
        return handle.ready;
    }

    async function wsRpc(handle, operation, payload, options, opConfig) {
        const requestId = nextRequestId(handle.name.toLowerCase());
        const socket = await connectWs(handle);
        return new Promise((resolve, reject) => {
            const timer = global.setTimeout(() => {
                handle.pending.delete(requestId);
                handle.order = handle.order.filter(id => id !== requestId);
                reject(new Error('Request timed out: ' + operation));
            }, Number(options && options.timeoutMs || opConfig.timeoutMs || handle.defaultTimeoutMs));
            handle.pending.set(requestId, {resolve, reject, timer});
            handle.order.push(requestId);
            socket.send(JSON.stringify(frameFor(operation, payload || {}, requestId, opConfig)));
        });
    }

	    async function httpRequest(handle, operation, payload, options, opConfig) {
        const method = String(options && options.method || opConfig.method || 'GET').toUpperCase();
        const responseKind = String(options && options.responseKind || opConfig.responseKind || 'json').toLowerCase();
        const path = String(options && options.path || opConfig.path || operation || '');
        const headers = Object.assign({}, options && options.headers || {});
        const init = Object.assign({method, cache: 'no-store'}, options && options.fetchOptions || {});
        let query = Object.assign({}, options && options.query || {});
        if (method === 'GET' || method === 'DELETE') {
            query = Object.assign(query, payload || {});
        } else {
            headers['Content-Type'] = headers['Content-Type'] || 'application/json';
            init.body = options && options.body !== undefined ? options.body : JSON.stringify(payload || {});
        }
        init.headers = headers;
	        const response = await fetch(normalizeHttpUrl(handle.baseUrl, path, query), init);
	        if (response && response.status === 401) {
	            emit('http:unauthorized', {handleName: handle.name, operation, response});
	            if (global.UCE && typeof global.UCE.onUnauthorizedResponse === 'function') {
	                global.UCE.onUnauthorizedResponse({handleName: handle.name, operation, response});
	            }
	        }
	        if (!response.ok) {
	            const text = await response.text().catch(() => '');
	            throw new Error(text || ('HTTP ' + response.status + ' for ' + path));
	        }
	        if (response.status === 204) return {ok: true, status: 204};
	        if (responseKind === 'html' || responseKind === 'text') return response.text();
	        if (responseKind === 'raw') return response;
	        return response.json();
	    }

    function normalizeResult(requestId, operation, raw) {
        return {
            requestId,
            op: operation,
            status: Number(raw && raw.status || 200),
            data: raw && raw.data !== undefined ? raw.data : raw,
            raw
        };
    }

    async function request(handleName, operation, payload, options) {
        const handle = handles.get(String(handleName || ''));
        if (!handle) throw new Error('Unknown request handle: ' + handleName);
        const opConfig = operationConfig(handle, operation, options);
        const policy = String(options && options.cachePolicy || opConfig.cachePolicy || CachePolicy.NONE);
        const key = cacheKey(handleName, operation, payload, options);
        if (policy === CachePolicy.MEMORY && memoryCache.has(key)) return memoryCache.get(key);
        if ((policy === CachePolicy.DEDUPE_INFLIGHT || policy === CachePolicy.MEMORY) && inflight.has(key)) return inflight.get(key);
        const promise = (async () => {
            emit('request:start', {handleName, operation, payload});
            tracePayload({status: 'start', handleName, operation, request: payload || {}});
            const raw = handle.kind === HandleKind.WS_RPC || handle.kind === HandleKind.WS_STREAM
                    ? await wsRpc(handle, operation, payload || {}, options || {}, opConfig)
                    : await httpRequest(handle, operation, payload || {}, options || {}, opConfig);
            const normalized = normalizeResult(raw && raw.requestId || '', operation, raw);
            tracePayload({status: 'ok', handleName, operation, request: payload || {}, response: raw});
            emit('request:success', {handleName, operation, response: normalized});
            return options && options.normalized ? normalized : raw;
        })().catch(error => {
            tracePayload({status: 'error', handleName, operation, request: payload || {}, error: error && error.message || String(error)});
            emit('request:error', {handleName, operation, error});
            throw error;
        }).finally(() => inflight.delete(key));
        if (policy === CachePolicy.MEMORY || policy === CachePolicy.DEDUPE_INFLIGHT) inflight.set(key, promise);
        if (policy === CachePolicy.MEMORY) {
            promise.then(value => memoryCache.set(key, value)).catch(() => {});
        }
        return promise;
    }

    function stream(handleName, operation, payload, callbacks, options) {
        const cancelled = {value: false};
        request(handleName, operation, payload, options)
                .then(value => {
                    if (!cancelled.value && callbacks && callbacks.message) callbacks.message(value);
                    if (!cancelled.value && callbacks && callbacks.close) callbacks.close();
                })
                .catch(error => {
                    if (!cancelled.value && callbacks && callbacks.error) callbacks.error(error);
                });
        return () => { cancelled.value = true; };
    }

    const api = {
        __uceRequestHandler: true,
        HandleKind,
        HandleName,
        HandleState,
        CachePolicy,
        Payloads: {entries: payloadEntries},
        registerHandle,
        request,
        stream,
        invalidate,
        subscribe,
        state,
        normalizeWsUrl
    };
    global.UCE.RequestHandler = api;
    global.UCE.Payloads = api.Payloads;
})(window);
</#noparse>
