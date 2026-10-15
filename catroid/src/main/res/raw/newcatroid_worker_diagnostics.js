/*
 * NewCatroid opt-in worker diagnostics. Appended only when the page has
 * enabled newcatroid_debug=1 or newcatroid_adreno_fix=1.
 * Events are sent only over a same-origin BroadcastChannel, never over HTTP.
 */
(function () {
    'use strict';

    var channel = null;
    var childWorkers = [];
    var childSequence = 0;
    var component = 'worker';
    var lastBeat = workerNow();

    function workerNow() {
        try { return self.performance && typeof self.performance.now === 'function' ? self.performance.now() : Date.now(); }
        catch (ignored) { return Date.now(); }
    }

    function safeUrl(value) {
        try {
            var url = new URL(String(value), self.location.href);
            if (url.protocol === 'http:' || url.protocol === 'https:') {
                var local = url.origin === self.location.origin;
                var name = url.pathname.split('/').pop() || '';
                var known = /^(index\.html?|loader\.js|wgpu_worker\.js|io_worker\.js|game\.js|game\.wasm)$/i.test(name);
                return local ? '[local]/' + (known ? name : '[resource]') : '[remote resource]';
            }
            if (url.protocol === 'blob:') return 'blob:[local worker]';
            return '[resource]';
        } catch (ignored) {
            return '[resource]';
        }
    }

    function safeText(value) {
        var text;
        try {
            if (value && typeof value === 'object') {
                if (typeof value.message === 'string') {
                    text = (value.name ? String(value.name) + ': ' : '') + value.message;
                    if (typeof value.stack === 'string') text += '\n' + value.stack;
                } else {
                    text = String(value);
                }
            } else {
                text = String(value == null ? '' : value);
            }
        } catch (ignored) {
            text = '[unreadable diagnostic value]';
        }
        return text
            .replace(/\b(authorization|cookie|set-cookie)\s*[:=]\s*[^\r\n]*/gi, function (match) {
                return match.split(/[:=]/)[0] + '=[REDACTED]';
            })
            .replace(/(["']?(?:access[_-]?(?:token|key)|refresh[_-]?token|id[_-]?token|token|api[_-]?key|client[_-]?secret|private[_-]?key|password|passwd|secret|authorization|cookie|set-cookie|auth(?:entication)?|signature|sig|jwt|csrf|sid|user(?:name|id)?|player(?:name|id)?|account(?:id)?|profile|save(?:game)?|session(?:id|[_-]?token)?|credential|key|name|path|file(?:name)?|email)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^,;\s}\]]+)/gi, '$1[REDACTED]')
            .replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [REDACTED]')
            .replace(/([?&](?:access_token|access_key|refresh_token|id_token|token|api_key|key|client_secret|password|auth|signature|sig|jwt|csrf|sid)=)[^&#\s]*/gi, '$1[REDACTED]')
            .replace(/(?:\/data\/user\/\d+\/|\/storage\/emulated\/\d+\/|\/sdcard\/|\/home\/[^/\s]+\/|[A-Z]:\\Users\\[^\\\s]+\\)[^\s]*/gi, '[LOCAL_PATH]')
            .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[EMAIL]')
            .replace(/https?:\/\/[^\s"'<>]+/gi, function (match) { return safeUrl(match); })
            .slice(0, 2000);
    }

    function sourceName() {
        try {
            var path = new URL(self.location.href).pathname;
            if (/\/loader\.js$/i.test(path)) return 'loader-worker';
            if (/\/wgpu_worker\.js$/i.test(path)) return 'gpu-worker';
            if (/\/io_worker\.js$/i.test(path)) return 'io-worker';
        } catch (ignored) { }
        return component;
    }

    component = sourceName();

    try {
        if (typeof BroadcastChannel === 'function') channel = new BroadcastChannel('newcatroid-debug');
    } catch (ignored) { }

    function send(type, details, id) {
        if (!channel) return;
        try {
            channel.postMessage({
                source: component,
                type: type,
                at: Date.now(),
                id: id || undefined,
                details: details || {}
            });
        } catch (ignored) { }
    }

    function relevant(text) {
        return /(error|failed|exception|reject|warning|warn|shader|pipeline|webgpu|webgl|gpu|wasm|worker|wait|stall|timeout|fence|ring|framebuffer|context|initiali[sz]|loaded|world|readback|memory|device lost|validation|adapter|limits)/i.test(text);
    }

    function summarize(data) {
        if (!data || typeof data !== 'object') return { valueType: typeof data };
        var summary = { keys: Object.keys(data).slice(0, 18) };
        ['loaded', 'init', 'error', 'pct', 'label', 'world', 'compiling', 'gpuLost'].forEach(function (key) {
            if (!Object.prototype.hasOwnProperty.call(data, key)) return;
            var value = data[key];
            if (typeof value === 'string') summary[key] = safeText(value);
            else if (typeof value === 'number' || typeof value === 'boolean') summary[key] = value;
            else if (value != null) summary[key] = '[' + typeof value + ']';
        });
        return summary;
    }

    function workerRole(url) {
        try {
            var parsed = new URL(String(url), self.location.href);
            if (parsed.origin !== self.location.origin) return 'other-worker';
            if (/\/wgpu_worker\.js$/i.test(parsed.pathname)) return 'gpu-worker';
            if (/\/io_worker\.js$/i.test(parsed.pathname)) return 'io-worker';
            return 'other-worker';
        } catch (ignored) {
            return 'other-worker';
        }
    }

    function withWorkerDebugQuery(originalUrl) {
        try {
            var url = new URL(String(originalUrl), self.location.href);
            if (url.origin !== self.location.origin) return { url: originalUrl, role: 'other-worker', syncPipelines: false };
            var role = workerRole(url.href);
            if ((role === 'gpu-worker' || role === 'io-worker') && url.searchParams.get('newcatroid_worker_debug') !== '1') {
                url.searchParams.set('newcatroid_worker_debug', '1');
            }
            return {
                url: url.href,
                role: role,
                syncPipelines: role === 'gpu-worker' && url.searchParams.get('syncpipelines') === '1'
            };
        } catch (ignored) {
            return { url: originalUrl, role: 'other-worker', syncPipelines: false };
        }
    }

    function installPipelineSelectionProbe() {
        if (component !== 'loader-worker' || typeof self.Worker !== 'function' || typeof Proxy !== 'function') {
            send('pipeline-mode', { gpuWorkerCreated: false, syncPipelines: false, message: 'loader Worker hook unavailable' });
            return;
        }
        var NativeWorker = self.Worker;
        try {
            self.Worker = new Proxy(NativeWorker, {
                construct: function (target, args, newTarget) {
                    var info;
                    try {
                        var url = new URL(String(args[0]), self.location.href);
                        info = {
                            sameOrigin: url.origin === self.location.origin,
                            role: workerRole(url.href),
                            syncPipelines: url.searchParams.get('syncpipelines') === '1',
                            url: url.href
                        };
                    } catch (ignored) {
                        info = { sameOrigin: false, role: 'other-worker', syncPipelines: false, url: '' };
                    }
                    var worker;
                    try {
                        worker = Reflect.construct(target, args, newTarget);
                    } catch (error) {
                        if (info.sameOrigin && info.role === 'gpu-worker') {
                            send('pipeline-mode', {
                                gpuWorkerCreated: false,
                                syncPipelines: info.syncPipelines,
                                url: safeUrl(info.url),
                                message: 'GPU worker constructor failed: ' + safeText(error)
                            });
                        }
                        throw error;
                    }
                    if (info.sameOrigin && info.role === 'gpu-worker') {
                        send('pipeline-mode', {
                            gpuWorkerCreated: true,
                            syncPipelines: info.syncPipelines,
                            url: safeUrl(info.url)
                        });
                    }
                    return worker;
                }
            });
            send('pipeline-probe-ready', { message: 'GPU worker URL observer installed' });
        } catch (error) {
            send('pipeline-mode', { gpuWorkerCreated: false, syncPipelines: false, message: safeText(error) });
        }
    }

    function childMessage(worker, record, event) {
        var summary = summarize(event && event.data);
        record.lastMessageAt = Date.now();
        record.lastMessage = safeText(JSON.stringify(summary));
        if (event && event.data && event.data.loaded === true) record.state = 'loaded';
        if (event && event.data && event.data.error) record.state = 'error';
        send('worker-state', { role: record.role, state: record.state, message: record.lastMessage }, record.id);
    }

    function observeChildWorker(worker, role, workerUrl, syncPipelines) {
        var record = {
            id: component + '-child-' + (++childSequence),
            role: role,
            url: safeUrl(workerUrl),
            state: 'created',
            createdAt: Date.now(),
            lastMessageAt: null,
            timeoutReported: false,
            syncPipelines: syncPipelines
        };
        childWorkers.push(record);
        send('worker-created', {
            role: role,
            url: record.url,
            syncPipelines: syncPipelines
        }, record.id);

        try {
            worker.addEventListener('message', function (event) { childMessage(worker, record, event); });
            worker.addEventListener('error', function (event) {
                record.state = 'error';
                send('worker-error', { role: role, message: safeText(event && (event.message || event.error || 'worker error')) }, record.id);
            });
            worker.addEventListener('messageerror', function () {
                send('worker-error', { role: role, message: 'message deserialization failed' }, record.id);
            });
        } catch (error) {
            send('worker-error', { role: role, message: 'event hooks unavailable: ' + safeText(error) }, record.id);
        }

    }

    function installNestedWorkerHooks() {
        if (component !== 'loader-worker' || typeof self.Worker !== 'function' || typeof Proxy !== 'function') return;
        var NativeWorker = self.Worker;
        try {
            self.Worker = new Proxy(NativeWorker, {
                construct: function (target, args, newTarget) {
                    var info = withWorkerDebugQuery(args[0]);
                    if (info.role !== 'gpu-worker' && info.role !== 'io-worker') {
                        return Reflect.construct(target, args, newTarget);
                    }
                    args[0] = info.url;
                    var worker = Reflect.construct(target, args, newTarget);
                    observeChildWorker(worker, info.role, info.url, info.syncPipelines);
                    return worker;
                }
            });
        } catch (error) {
            send('worker-error', { message: 'nested Worker hook failed: ' + safeText(error) });
        }
    }

    function installWasmHooks() {
        if (component !== 'loader-worker' || typeof WebAssembly !== 'object') return;
        ['instantiateStreaming', 'instantiate', 'compileStreaming', 'compile'].forEach(function (operation) {
            var original = WebAssembly[operation];
            if (typeof original !== 'function') return;
            try {
                WebAssembly[operation] = function () {
                    var started = workerNow();
                    send('wasm-start', { operation: operation });
                    var result;
                    try {
                        result = Reflect.apply(original, this, arguments);
                    } catch (error) {
                        send('wasm-error', { operation: operation, message: safeText(error), durationMs: Math.round(workerNow() - started) });
                        throw error;
                    }
                    if (result && typeof result.then === 'function') {
                        try {
                            result.then(function () {
                                send('wasm-end', { operation: operation, durationMs: Math.round(workerNow() - started) });
                            }, function (error) {
                                send('wasm-error', { operation: operation, message: safeText(error), durationMs: Math.round(workerNow() - started) });
                            });
                        } catch (ignored) { }
                    } else {
                        send('wasm-end', { operation: operation, durationMs: Math.round(workerNow() - started) });
                    }
                    return result;
                };
            } catch (ignored) { }
        });
    }

    function installOffscreenContextObserver() {
        if (!self.OffscreenCanvas || !self.OffscreenCanvas.prototype) return;
        var prototype = self.OffscreenCanvas.prototype;
        var original = prototype.getContext;
        if (typeof original !== 'function') return;
        try {
            prototype.getContext = function (type) {
                var result;
                try {
                    result = Reflect.apply(original, this, arguments);
                } catch (error) {
                    if (/webgpu|webgl/i.test(String(type))) {
                        send('gpu-context', { message: type + ' OffscreenCanvas.getContext threw: ' + safeText(error) });
                    }
                    throw error;
                }
                if (/webgpu|webgl/i.test(String(type))) {
                    send('gpu-context', { message: type + ' OffscreenCanvas context ' + (result ? 'created' : 'unavailable') });
                }
                return result;
            };
        } catch (ignored) { }
    }

    function installConsoleHooks() {
        if (!self.console) return;
        ['error', 'warn', 'log'].forEach(function (method) {
            var original = self.console[method];
            if (typeof original !== 'function') return;
            try {
                self.console[method] = function () {
                    var text = Array.prototype.map.call(arguments, safeText).join(' ');
                    if (relevant(text)) send('console', { method: method, message: text });
                    return Reflect.apply(original, this, arguments);
                };
            } catch (ignored) { }
        });
    }

    function installErrorObservers() {
        try {
            self.addEventListener('error', function (event) {
                send('exception', {
                    message: safeText(event && (event.error || event.message || 'worker error')),
                    resource: safeUrl(event && event.filename || '')
                });
            });
            self.addEventListener('unhandledrejection', function (event) {
                send('unhandled-rejection', { reason: safeText(event && event.reason), message: safeText(event && event.reason) });
            });
            self.addEventListener('message', function (event) {
                var data = event && event.data;
                if (component === 'loader-worker' && data && typeof data === 'object' && Array.isArray(data.args)) {
                    send('loader-init', {
                        keys: Object.keys(data).slice(0, 20),
                        hasCanvas: !!data.canvas,
                        hasWgpuArgument: data.args.indexOf('-wgpu') !== -1,
                        syncPipelinesRequested: data.syncPipelines === true
                    });
                } else if (component === 'gpu-worker' && data && typeof data === 'object') {
                    send('gpu-worker-init', { keys: Object.keys(data).slice(0, 16), hasMemory: !!data.memory, hasRing: !!data.ring });
                }
            });
        } catch (ignored) { }
    }

    function installHeartbeat() {
        try {
            self.setInterval(function () {
                var now = workerNow();
                var gap = Math.max(0, Math.round(now - lastBeat));
                lastBeat = now;
                if (gap > 10000) send('worker-heartbeat-gap', { gapMs: gap });
                send('worker-heartbeat', { gapMs: gap });
                childWorkers.forEach(function (record) {
                    if (record.state !== 'created' || record.timeoutReported || Date.now() - record.createdAt < 15000) return;
                    record.timeoutReported = true;
                    send('worker-timeout', {
                        role: record.role,
                        message: record.role + ' has not reported loaded after 15 seconds'
                    }, record.id);
                });
            }, 5000);
        } catch (ignored) { }
    }

    var pageQuery;
    try { pageQuery = new URLSearchParams(self.location.search || ''); }
    catch (ignored) { pageQuery = { get: function () { return null; } }; }
    if (pageQuery.get('newcatroid_pipeline_probe') === '1' && pageQuery.get('newcatroid_worker_debug') !== '1') {
        // Fix-only mode: inspect exactly which syncPipelines URL option the
        // existing loader gives to wgpu_worker.js. No logs, heartbeat, WASM
        // hooks, or GPU instrumentation are enabled in this branch.
        installPipelineSelectionProbe();
        return;
    }

    installNestedWorkerHooks();
    installWasmHooks();
    installOffscreenContextObserver();
    installConsoleHooks();
    installErrorObservers();
    installHeartbeat();
    send('worker-bootstrap', { component: component, script: safeUrl(self.location.href) });
})();
