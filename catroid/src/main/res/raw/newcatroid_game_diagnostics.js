/*
 * NewCatroid opt-in game diagnostics and experimental pipeline-mode switch.
 * Loaded only for ?newcatroid_debug=1 or ?newcatroid_adreno_fix=1.
 * Diagnostics remain in this WebView; this file does not send requests.
 */
(function () {
    'use strict';

    var params;
    try {
        params = new URLSearchParams(window.location.search || '');
    } catch (ignored) {
        params = { get: function () { return null; } };
    }

    var DEBUG_ENABLED = params.get('newcatroid_debug') === '1';
    var ADRENO_FIX_ENABLED = params.get('newcatroid_adreno_fix') === '1';
    if (!DEBUG_ENABLED && !ADRENO_FIX_ENABLED) return;
    if (window.__newcatroidGameDiagnostics) return;

    var startedAt = Date.now();
    var events = [];
    var workerStates = Object.create(null);
    var idSequence = 0;
    var lastHeartbeat = monotonicNow();
    var ui = null;
    var state = {
        flags: { debug: DEBUG_ENABLED, adrenoFix: ADRENO_FIX_ENABLED },
        stage: 'Waiting for page initialization events',
        stageChangedAt: startedAt,
        progress: null,
        targetProgress: null,
        progressLabel: '',
        progressChangedAt: startedAt,
        lastProgressEventAt: null,
        lastGameMessageAt: null,
        gameWorldReady: false,
        documentReadyState: document.readyState || 'unknown',
        mainHeartbeat: { lastGapMs: 0, maxGapMs: 0, delayedCount: 0 },
        wasm: { state: 'not observed', lastOperation: '', lastDurationMs: null },
        gpu: {
            webGpuApi: (navigator && navigator.gpu) ? 'present' : 'absent',
            webGlApi: (typeof WebGLRenderingContext === 'function') ? 'present' : 'absent',
            webGl2Api: (typeof WebGL2RenderingContext === 'function') ? 'present' : 'absent',
            adapter: 'not probed',
            adapterDetails: '',
            adapterFeatures: '',
            adapterLimits: '',
            workerDevice: '',
            features: '',
            limits: '',
            pendingPipelines: null,
            lastGpuError: '',
            lastShaderError: '',
            gpuWorkerHeartbeatAt: null,
            gpuWorkerHeartbeatGapMs: null
        },
        fix: {
            requested: ADRENO_FIX_ENABLED,
            launchMessageObserved: false,
            appliedToLaunchMessage: false,
            gpuWorkerObserved: false,
            workerSyncPipelines: null,
            workerOptionConfirmed: false,
            status: ADRENO_FIX_ENABLED ? 'Requested; waiting for game worker launch' : 'Disabled'
        },
        workers: workerStates
    };

    function monotonicNow() {
        try {
            if (window.performance && typeof window.performance.now === 'function') return window.performance.now();
        } catch (ignored) { }
        return Date.now();
    }

    function isoTime(value) {
        if (value == null || !isFinite(Number(value))) return '';
        try { return new Date(Number(value)).toISOString(); } catch (ignored) { return ''; }
    }

    function safeUrl(value) {
        try {
            var url = new URL(String(value), window.location.href);
            if (url.protocol === 'http:' || url.protocol === 'https:') {
                var local = url.origin === window.location.origin;
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
                } else if (typeof value.reason === 'string') {
                    text = value.reason;
                } else {
                    text = String(value);
                }
            } else {
                text = String(value == null ? '' : value);
            }
        } catch (ignored) {
            text = '[unreadable diagnostic value]';
        }
        text = text
            .replace(/\b(authorization|cookie|set-cookie)\s*[:=]\s*[^\r\n]*/gi, function (match) {
                return match.split(/[:=]/)[0] + '=[REDACTED]';
            })
            .replace(/(["']?(?:access[_-]?(?:token|key)|refresh[_-]?token|id[_-]?token|token|api[_-]?key|client[_-]?secret|private[_-]?key|password|passwd|secret|authorization|cookie|set-cookie|auth(?:entication)?|signature|sig|jwt|csrf|sid|user(?:name|id)?|player(?:name|id)?|account(?:id)?|profile|save(?:game)?|session(?:id|[_-]?token)?|credential|key|name|path|file(?:name)?|email)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^,;\s}\]]+)/gi, '$1[REDACTED]')
            .replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [REDACTED]')
            .replace(/([?&](?:access_token|access_key|refresh_token|id_token|token|api_key|key|client_secret|password|auth|signature|sig|jwt|csrf|sid)=)[^&#\s]*/gi, '$1[REDACTED]')
            .replace(/(?:\/data\/user\/\d+\/|\/storage\/emulated\/\d+\/|\/sdcard\/|\/home\/[^/\s]+\/|[A-Z]:\\Users\\[^\\\s]+\\)[^\s]*/gi, '[LOCAL_PATH]')
            .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[EMAIL]')
            .replace(/https?:\/\/[^\s"'<>]+/gi, function (match) { return safeUrl(match); });
        return text.slice(0, 2000);
    }

    function nowIso() {
        return isoTime(Date.now());
    }

    function recordEvent(category, message) {
        var cleaned = safeText(message).trim();
        if (!cleaned) return;
        var now = Date.now();
        var previous = events.length ? events[events.length - 1] : null;
        if (previous && previous.category === category && previous.message === cleaned && now - previous.atMs < 1500) {
            previous.repeat = (previous.repeat || 1) + 1;
            previous.atMs = now;
        } else {
            events.push({ at: isoTime(now), atMs: now, category: safeText(category), message: cleaned, repeat: 1 });
            if (events.length > 100) events.splice(0, events.length - 100);
        }
        render();
    }

    function setStage(stage) {
        var cleaned = safeText(stage).trim();
        if (!cleaned || cleaned === state.stage) return;
        state.stage = cleaned;
        state.stageChangedAt = Date.now();
        state.progressChangedAt = state.stageChangedAt;
    }

    function setProgress(percent, label) {
        var numeric = percent == null || percent === '' ? NaN : Number(percent);
        if (isFinite(numeric)) {
            numeric = Math.max(0, Math.min(100, numeric));
            if (state.targetProgress !== numeric) {
                state.targetProgress = numeric;
                state.progressChangedAt = Date.now();
            }
        }
        if (typeof label === 'string' && label.trim() && state.progressLabel !== safeText(label.trim())) {
            state.progressLabel = safeText(label.trim());
            state.progressChangedAt = Date.now();
            setStage(state.progressLabel);
        }
        state.lastProgressEventAt = Date.now();
    }

    function elapsedSeconds(at) {
        if (!at) return 'n/a';
        return Math.max(0, Math.floor((Date.now() - at) / 1000)) + 's ago';
    }

    function summarizeMessage(data) {
        if (!data || typeof data !== 'object') return { valueType: typeof data };
        var summary = { keys: Object.keys(data).slice(0, 18) };
        var copyKeys = ['loaded', 'init', 'pct', 'label', 'world', 'compiling', 'gpuLost', 'error', 'hud', 'input', 'audio'];
        for (var i = 0; i < copyKeys.length; i++) {
            var key = copyKeys[i];
            if (Object.prototype.hasOwnProperty.call(data, key)) {
                var value = data[key];
                if (typeof value === 'string') summary[key] = safeText(value);
                else if (typeof value === 'number' || typeof value === 'boolean') summary[key] = value;
                else if (value != null) summary[key] = '[' + typeof value + ']';
            }
        }
        return summary;
    }

    function isLaunchMessage(message) {
        return !!message && typeof message === 'object' &&
            Object.prototype.hasOwnProperty.call(message, 'canvas') &&
            Array.isArray(message.args);
    }

    function workerRole(urlString) {
        try {
            var url = new URL(String(urlString), window.location.href);
            if (url.origin !== window.location.origin) return 'other worker';
            if (/\/loader\.js$/i.test(url.pathname)) return 'loader worker';
            return 'game worker';
        } catch (ignored) {
            return 'worker';
        }
    }

    function addWorkerDebugQuery(urlString) {
        if (!DEBUG_ENABLED && !ADRENO_FIX_ENABLED) return urlString;
        try {
            var url = new URL(String(urlString), window.location.href);
            if (url.origin === window.location.origin && /\/loader\.js$/i.test(url.pathname)) {
                if (DEBUG_ENABLED) {
                    url.searchParams.delete('newcatroid_pipeline_probe');
                    url.searchParams.set('newcatroid_worker_debug', '1');
                } else {
                    url.searchParams.delete('newcatroid_worker_debug');
                    url.searchParams.set('newcatroid_pipeline_probe', '1');
                }
                return url.href;
            }
        } catch (ignored) { }
        return urlString;
    }

    function observeWorker(worker, role, urlString) {
        var workerState = null;
        if (DEBUG_ENABLED) {
            var id = 'page-worker-' + (++idSequence);
            workerState = {
                id: id,
                role: role,
                url: safeUrl(urlString),
                state: 'created',
                createdAt: nowIso(),
                lastMessageAt: null,
                lastMessage: ''
            };
            workerStates[id] = workerState;
            recordEvent('worker', role + ' created (' + workerState.url + ')');

            function onMessage(event) {
                var summary = summarizeMessage(event && event.data);
                workerState.lastMessageAt = nowIso();
                workerState.lastMessage = safeText(JSON.stringify(summary));
                if (event && event.data && event.data.loaded) workerState.state = 'loaded';
                if (event && event.data && event.data.error) workerState.state = 'error';
                recordEvent('worker', role + ' message: ' + JSON.stringify(summary));
            }
            function onError(event) {
                workerState.state = 'error';
                recordEvent('worker error', role + ': ' + safeText(event && (event.message || event.error || 'worker error')));
            }
            try {
                if (typeof worker.addEventListener === 'function') {
                    worker.addEventListener('message', onMessage);
                    worker.addEventListener('error', onError);
                    worker.addEventListener('messageerror', function () {
                        recordEvent('worker error', role + ': message deserialization failed');
                    });
                }
            } catch (ignored) { }
        }

        try {
            var originalPostMessage = worker.postMessage;
            worker.postMessage = function () {
                var args = Array.prototype.slice.call(arguments);
                if (role === 'loader worker' && isLaunchMessage(args[0])) {
                    var launch = Object.assign({}, args[0]);
                    state.fix.launchMessageObserved = true;
                    if (ADRENO_FIX_ENABLED) {
                        launch.syncPipelines = true;
                        state.fix.appliedToLaunchMessage = true;
                    }
                    // Deliberately do not set remoteLog/fullConsole or change
                    // page input state; the opt-in graphics mode changes one
                    // launch flag only.
                    args[0] = launch;
                    state.fix.status = ADRENO_FIX_ENABLED
                        ? 'syncPipelines=true sent to the game loader; worker confirmation pending'
                        : 'Disabled';
                    if (DEBUG_ENABLED) {
                        recordEvent('initialization', 'Game worker launch message observed; graphics option ' +
                            (ADRENO_FIX_ENABLED ? 'syncPipelines=true' : 'unchanged'));
                    } else {
                        render();
                    }
                }
                return Reflect.apply(originalPostMessage, this, args);
            };
        } catch (error) {
            if (ADRENO_FIX_ENABLED) state.fix.status = 'Not applied: loader postMessage hook is unavailable';
            if (DEBUG_ENABLED) recordEvent('worker instrumentation', role + ' postMessage hook unavailable: ' + safeText(error));
        }
    }

    function installWorkerAndLaunchHooks() {
        if (typeof window.Worker !== 'function' || typeof Proxy !== 'function' || typeof Reflect !== 'object') {
            if (ADRENO_FIX_ENABLED) state.fix.status = 'Not applied: Worker interception is unavailable';
            if (DEBUG_ENABLED) recordEvent('worker instrumentation', 'Worker constructor interception is unavailable');
            return;
        }
        var NativeWorker = window.Worker;
        try {
            window.Worker = new Proxy(NativeWorker, {
                construct: function (target, args, newTarget) {
                    var originalUrl = args[0];
                    var changedUrl = addWorkerDebugQuery(originalUrl);
                    args[0] = changedUrl;
                    var worker = Reflect.construct(target, args, newTarget);
                    observeWorker(worker, workerRole(changedUrl), changedUrl);
                    return worker;
                }
            });
        } catch (error) {
            if (ADRENO_FIX_ENABLED) state.fix.status = 'Not applied: Worker constructor hook failed';
            if (DEBUG_ENABLED) recordEvent('worker instrumentation', 'Worker constructor hook failed: ' + safeText(error));
        }
    }

    function relevantLog(text) {
        return /(error|failed|exception|reject|warning|warn|shader|pipeline|webgpu|webgl|gpu|wasm|worker|wait|stall|timeout|fence|ring|framebuffer|context|initiali[sz]|loaded|world|readback|memory|device lost|validation|adapter|feature|limit|texture|format|attachment)/i.test(text);
    }

    function recordLog(value, category) {
        var text = safeText(value);
        var lines = text.split(/\r?\n/);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line || !relevantLog(line)) continue;
            recordEvent(category || 'console', line);
            if (/shader|pipeline|validation/i.test(line)) state.gpu.lastShaderError = line;
            if (/device ready|adapter/i.test(line)) state.gpu.workerDevice = line;
            if (/features?/i.test(line)) state.gpu.features = line;
            if (/limits?/i.test(line)) state.gpu.limits = line;
            if (/gpu|webgpu|device lost|framebuffer|context/i.test(line) && /error|failed|lost|unsupported|invalid/i.test(line)) {
                state.gpu.lastGpuError = line;
            }
            if (/wasm|instantiate|compile/i.test(line)) state.wasm.lastOperation = line;
        }
    }

    function installConsoleHooks() {
        if (!window.console) return;
        ['error', 'warn', 'log'].forEach(function (method) {
            var original = window.console[method];
            if (typeof original !== 'function') return;
            try {
                window.console[method] = function () {
                    var text = Array.prototype.map.call(arguments, function (value) { return safeText(value); }).join(' ');
                    if (relevantLog(text)) recordLog(text, 'page console.' + method);
                    return Reflect.apply(original, this, arguments);
                };
            } catch (ignored) { }
        });
    }

    function listenChannel(name, handler) {
        if (typeof BroadcastChannel !== 'function') return;
        try {
            var channel = new BroadcastChannel(name);
            channel.addEventListener('message', function (event) {
                try { handler(event.data); } catch (error) { recordEvent('diagnostics', name + ' observer error: ' + safeText(error)); }
            });
        } catch (error) {
            recordEvent('diagnostics', name + ' channel unavailable: ' + safeText(error));
        }
    }

    function observeGameProgress(data) {
        if (!data || typeof data !== 'object') return;
        state.lastGameMessageAt = Date.now();
        state.lastProgressEventAt = Date.now();
        if (typeof data.pct === 'number') setProgress(data.pct, typeof data.label === 'string' ? data.label : '');
        else if (typeof data.label === 'string') setProgress(state.targetProgress, data.label);
        if (data.world === true || data.ready === true) {
            state.gameWorldReady = true;
            setStage('World ready signal received');
        }
        if (typeof data.compiling === 'number') {
            state.gpu.pendingPipelines = data.compiling;
            if (data.compiling > 0) setStage('Preparing shaders (' + data.compiling + ' pending)');
        } else if (data.compiling === true) {
            state.gpu.pendingPipelines = 'reported';
            setStage('Preparing shaders');
        }
        if (data.gpuLost) {
            state.gpu.lastGpuError = safeText(data.gpuLost);
            recordEvent('GPU device lost', data.gpuLost);
        }
        if (data.error) {
            setStage('Initialization error');
            recordEvent('initialization error', data.error);
        }
        recordEvent('game-progress', JSON.stringify(summarizeMessage(data)));
    }

    function observeGameLog(data) {
        if (typeof data === 'string') recordLog(data, 'game-log channel');
        else if (data && typeof data.message === 'string') recordLog(data.message, 'game-log channel');
    }

    function observeWorkerDebug(data) {
        if (!data || typeof data !== 'object') return;
        if (!DEBUG_ENABLED) {
            if (ADRENO_FIX_ENABLED && data.type === 'pipeline-mode') {
                var pipelineDetails = data.details && typeof data.details === 'object' ? data.details : {};
                state.fix.gpuWorkerObserved = pipelineDetails.gpuWorkerCreated === true;
                state.fix.workerSyncPipelines = pipelineDetails.syncPipelines === true;
                state.fix.workerOptionConfirmed = state.fix.gpuWorkerObserved && state.fix.workerSyncPipelines;
                state.fix.status = state.fix.workerOptionConfirmed
                    ? 'GPU worker URL carried syncpipelines=1 (experimental, unvalidated)'
                    : !state.fix.gpuWorkerObserved
                        ? 'GPU worker URL was not confirmed; mode remains unverified'
                        : 'Not applied: GPU worker URL did not contain syncpipelines=1';
                render();
            }
            return;
        }
        var source = safeText(data.source || 'worker');
        var type = safeText(data.type || 'event');
        var details = data.details && typeof data.details === 'object' ? data.details : {};
        if (type === 'loader-init') {
            var childWorkerObserved = Object.keys(workerStates).some(function (key) {
                return /gpu|i\/o|io-worker/i.test(workerStates[key].role || '');
            });
            if (!childWorkerObserved) setStage('Loader received game launch; waiting for GPU and I/O workers');
            state.fix.launchMessageObserved = true;
            if (ADRENO_FIX_ENABLED) {
                state.fix.appliedToLaunchMessage = details.syncPipelinesRequested === true;
                state.fix.status = details.syncPipelinesRequested === true
                    ? 'syncPipelines=true received by the game loader; worker confirmation pending'
                    : 'Not applied: loader did not receive syncPipelines=true';
            }
            recordEvent('initialization', 'Loader initialization: ' +
                (details.hasWgpuArgument ? 'WebGPU argument present' : 'WebGPU argument not present') +
                ', syncPipelines=' + !!details.syncPipelinesRequested);
        } else if (type === 'wasm-start') {
            state.wasm.state = 'instantiating';
            state.wasm.lastOperation = safeText(details.operation || 'WebAssembly operation');
            setStage('Fetching/instantiating WebAssembly (' + state.wasm.lastOperation + ')');
        } else if (type === 'wasm-end') {
            state.wasm.state = 'ready';
            state.wasm.lastOperation = safeText(details.operation || 'WebAssembly operation');
            state.wasm.lastDurationMs = Number(details.durationMs) || 0;
            setStage('WebAssembly instantiated; waiting for engine progress');
        } else if (type === 'wasm-error') {
            state.wasm.state = 'error';
            state.wasm.lastOperation = safeText(details.operation || 'WebAssembly operation failed');
            setStage('WebAssembly initialization failed');
            recordEvent('WASM error', details.message || state.wasm.lastOperation);
        } else if (type === 'worker-heartbeat' && source === 'gpu-worker') {
            state.gpu.gpuWorkerHeartbeatAt = Number(data.at) || Date.now();
            state.gpu.gpuWorkerHeartbeatGapMs = Number(details.gapMs) || 0;
        } else if (type === 'gpu-context') {
            recordEvent('GPU context', details.message || 'GPU context obtained');
        } else if (type === 'worker-created' && (details.role === 'gpu-worker' || details.role === 'io-worker')) {
            setStage('Starting ' + (details.role === 'gpu-worker' ? 'GPU' : 'I/O') + ' worker');
            workerStates[data.id || source + '-' + details.role] = {
                id: safeText(data.id || source + '-' + details.role),
                role: details.role === 'gpu-worker' ? 'GPU worker' : 'I/O worker',
                url: safeText(details.url || details.role),
                state: 'created',
                createdAt: isoTime(Number(data.at) || Date.now()),
                lastMessageAt: null,
                lastMessage: ''
            };
            if (details.role === 'gpu-worker') {
                state.fix.gpuWorkerObserved = true;
                state.fix.workerSyncPipelines = details.syncPipelines === true;
                state.fix.workerOptionConfirmed = state.fix.workerSyncPipelines;
                if (ADRENO_FIX_ENABLED) {
                    state.fix.status = state.fix.workerOptionConfirmed
                        ? 'GPU worker URL carried syncpipelines=1 (experimental, unvalidated)'
                        : 'Not applied: GPU worker URL did not contain syncpipelines=1';
                }
                if (state.fix.workerOptionConfirmed) recordEvent('graphics experiment', 'GPU worker URL confirmed syncpipelines=1');
            }
        } else if (type === 'worker-state' && data.id) {
            var existing = workerStates[data.id] || { id: safeText(data.id), role: safeText(details.role || 'worker') };
            existing.state = safeText(details.state || existing.state || 'observed');
            existing.lastMessageAt = isoTime(Number(data.at) || Date.now());
            existing.lastMessage = safeText(details.message || '');
            workerStates[data.id] = existing;
            var gpuReady = Object.keys(workerStates).some(function (key) {
                return /gpu/i.test(workerStates[key].role || '') && workerStates[key].state === 'loaded';
            });
            var ioReady = Object.keys(workerStates).some(function (key) {
                return /i\/o|io-worker/i.test(workerStates[key].role || '') && workerStates[key].state === 'loaded';
            });
            if (gpuReady && ioReady) setStage('GPU and I/O workers reported loaded; engine initialization follows');
        } else if (type === 'worker-timeout') {
            setStage('Waiting for ' + safeText(details.role || 'worker') + ' worker');
            recordEvent('worker wait', source + ': ' + safeText(details.message || 'worker startup timeout observed'));
        } else if (type === 'worker-error' || type === 'unhandled-rejection' || type === 'exception') {
            recordEvent(type, source + ': ' + safeText(details.message || details.reason || 'worker error'));
        } else if (type === 'console') {
            recordLog(details.message || '', source + ' console');
        } else if (type === 'worker-heartbeat-gap') {
            recordEvent('worker heartbeat', source + ': delayed heartbeat (' + (Number(details.gapMs) || 0) + ' ms)');
        } else if (type === 'worker-heartbeat') {
            // Keep routine heartbeats in state rather than flooding the event list.
            var heartbeatState = workerStates[source] || { id: source, role: source, state: 'running' };
            heartbeatState.lastMessageAt = isoTime(Number(data.at) || Date.now());
            heartbeatState.heartbeatGapMs = Number(details.gapMs) || 0;
            workerStates[source] = heartbeatState;
        } else {
            var summary = {};
            Object.keys(details).slice(0, 12).forEach(function (key) {
                var value = details[key];
                if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') summary[key] = safeText(value);
            });
            recordEvent(source + ' ' + type, JSON.stringify(summary));
        }
        if (/adapter|device ready/i.test(JSON.stringify(details))) {
            state.gpu.workerDevice = safeText(JSON.stringify(details));
        }
        render();
    }

    function sampleDisplayedProgress() {
        try {
            if (!document.getElementById) return;
            var pctElement = document.getElementById('pct');
            var labelElement = document.getElementById('label');
            var pctText = pctElement && pctElement.textContent ? pctElement.textContent : '';
            var match = pctText.match(/(\d+(?:\.\d+)?)\s*%/);
            var label = labelElement && labelElement.textContent ? safeText(labelElement.textContent.trim()) : '';
            if (match) {
                var pct = Number(match[1]);
                if (pct !== state.progress) {
                    state.progress = pct;
                    state.progressChangedAt = Date.now();
                }
            }
            if (label && label !== state.stage) {
                state.stage = label;
                state.stageChangedAt = Date.now();
                state.progressChangedAt = state.stageChangedAt;
            }
        } catch (ignored) { }
    }

    function probeWebGpuAdapter() {
        try {
            if (!navigator || !navigator.gpu || typeof navigator.gpu.requestAdapter !== 'function') {
                state.gpu.adapter = 'unavailable';
                return;
            }
            state.gpu.adapter = 'request pending';
            var finished = false;
            var timeout = setTimeout(function () {
                if (!finished) {
                    state.gpu.adapter = 'request timed out (diagnostic probe only)';
                    recordEvent('WebGPU adapter', 'Nonblocking adapter probe timed out; game initialization was not blocked');
                }
            }, 12000);
            Promise.resolve(navigator.gpu.requestAdapter({ powerPreference: 'high-performance' })).then(function (adapter) {
                finished = true;
                clearTimeout(timeout);
                if (!adapter) {
                    state.gpu.adapter = 'no adapter returned';
                    recordEvent('WebGPU adapter', 'No adapter returned to the page diagnostic probe');
                } else {
                    state.gpu.adapter = 'available';
                    var info = {};
                    try { info = adapter.info || {}; } catch (ignored) { }
                    var parts = [];
                    ['vendor', 'architecture', 'device', 'description'].forEach(function (key) {
                        try {
                            if (info[key]) parts.push(key + '=' + safeText(info[key]));
                        } catch (ignored) { }
                    });
                    if (!parts.length && typeof adapter.requestAdapterInfo === 'function') {
                        try {
                            Promise.resolve(adapter.requestAdapterInfo()).then(function (legacyInfo) {
                                var legacyParts = [];
                                ['vendor', 'architecture', 'device', 'description'].forEach(function (key) {
                                    try { if (legacyInfo && legacyInfo[key]) legacyParts.push(key + '=' + safeText(legacyInfo[key])); } catch (ignored) { }
                                });
                                if (legacyParts.length) state.gpu.adapterDetails = legacyParts.join(', ');
                                render();
                            }).catch(function () { });
                        } catch (ignored) { }
                    }
                    state.gpu.adapterDetails = parts.join(', ') || 'adapter information not exposed by this WebView';
                    var adapterFeatures = [];
                    try {
                        if (adapter.features && typeof adapter.features.forEach === 'function') {
                            adapter.features.forEach(function (feature) { adapterFeatures.push(safeText(feature)); });
                        }
                    } catch (ignored) { }
                    state.gpu.adapterFeatures = adapterFeatures.slice(0, 80).join(', ') || 'not exposed';
                    var adapterLimitPairs = [];
                    var limitNames = ['maxTextureDimension2D', 'maxTextureArrayLayers', 'maxBindGroups',
                        'maxSampledTexturesPerShaderStage', 'maxSamplersPerShaderStage', 'maxStorageBuffersPerShaderStage',
                        'maxStorageTexturesPerShaderStage', 'maxUniformBuffersPerShaderStage', 'maxBufferSize',
                        'maxUniformBufferBindingSize', 'maxStorageBufferBindingSize', 'maxColorAttachments',
                        'maxComputeInvocationsPerWorkgroup', 'maxComputeWorkgroupSizeX', 'maxComputeWorkgroupsPerDimension'];
                    try {
                        if (adapter.limits) limitNames.forEach(function (key) {
                            var value = adapter.limits[key];
                            if (typeof value === 'number') adapterLimitPairs.push(key + '=' + value);
                        });
                    } catch (ignored) { }
                    state.gpu.adapterLimits = adapterLimitPairs.join(', ') || 'not exposed';
                    recordEvent('WebGPU adapter', state.gpu.adapterDetails);
                }
                render();
            }).catch(function (error) {
                finished = true;
                clearTimeout(timeout);
                state.gpu.adapter = 'request rejected';
                recordEvent('WebGPU adapter probe', safeText(error));
            });
        } catch (error) {
            state.gpu.adapter = 'probe failed';
            recordEvent('WebGPU adapter probe', safeText(error));
        }
    }

    function installContextErrorObservers() {
        try {
            document.addEventListener('webglcontextlost', function () {
                recordEvent('WebGL context', 'webglcontextlost event observed (not prevented by diagnostics)');
            }, true);
            document.addEventListener('webglcontextrestored', function () {
                recordEvent('WebGL context', 'webglcontextrestored event observed');
            }, true);
        } catch (ignored) { }
        try {
            window.addEventListener('error', function (event) {
                if (event && event.target && event.target !== window) {
                    var resource = event.target.src || event.target.href || '';
                    recordEvent('resource error', (event.target.tagName || 'resource') + (resource ? ' ' + safeUrl(resource) : ' failed to load'));
                    return;
                }
                recordEvent('page error', safeText(event && (event.error || event.message || 'uncaught page error')));
            }, true);
            window.addEventListener('unhandledrejection', function (event) {
                recordEvent('unhandled promise rejection', safeText(event && event.reason));
            });
            window.addEventListener('DOMContentLoaded', function () {
                state.documentReadyState = document.readyState || 'interactive';
                setStage('Document content loaded; waiting for game initialization');
                render();
            });
            window.addEventListener('load', function () {
                state.documentReadyState = document.readyState || 'complete';
                recordEvent('initialization', 'Page load event received');
                render();
            });
        } catch (ignored) { }
    }

    function buildReportObject() {
        var last = events.slice(-100).map(function (event) {
            return { at: event.at, category: event.category, message: event.message, repeat: event.repeat };
        });
        var workers = Object.keys(workerStates).map(function (key) {
            var worker = workerStates[key];
            return {
                id: safeText(worker.id),
                role: safeText(worker.role),
                url: safeText(worker.url || ''),
                state: safeText(worker.state || 'observed'),
                createdAt: safeText(worker.createdAt || ''),
                lastMessageAt: safeText(worker.lastMessageAt || ''),
                lastMessage: safeText(worker.lastMessage || ''),
                heartbeatGapMs: Number(worker.heartbeatGapMs) || null
            };
        });
        var now = Date.now();
        return {
            schema: 'NewCatroid local game diagnostics v1',
            capturedAt: isoTime(now),
            page: safeUrl(window.location.href),
            privacy: 'Collected in this WebView only. URL query values, cookies, headers, credentials, and arbitrary page data are not exported.',
            optIns: { newcatroid_debug: DEBUG_ENABLED, newcatroid_adreno_fix: ADRENO_FIX_ENABLED },
            initialization: {
                stage: safeText(state.stage),
                stageChangedAt: isoTime(state.stageChangedAt),
                displayedPercent: state.progress,
                lastEngineProgressTarget: state.targetProgress,
                progressLabel: safeText(state.progressLabel),
                lastProgressChangeAt: isoTime(state.progressChangedAt),
                secondsSinceProgressChange: Math.floor((now - state.progressChangedAt) / 1000),
                lastProgressEventAt: isoTime(state.lastProgressEventAt),
                lastGameMessageAt: isoTime(state.lastGameMessageAt),
                gameWorldReadySignal: state.gameWorldReady,
                documentReadyState: safeText(state.documentReadyState)
            },
            mainPageHeartbeat: state.mainHeartbeat,
            webAssembly: state.wasm,
            graphics: {
                webGpuApi: state.gpu.webGpuApi,
                webGlApi: state.gpu.webGlApi,
                webGl2Api: state.gpu.webGl2Api,
                pageAdapterProbe: state.gpu.adapter,
                adapterInformation: safeText(state.gpu.adapterDetails),
                adapterFeatures: safeText(state.gpu.adapterFeatures),
                adapterLimits: safeText(state.gpu.adapterLimits),
                gpuWorkerDeviceLog: safeText(state.gpu.workerDevice),
                gpuWorkerFeaturesLog: safeText(state.gpu.features),
                gpuLimitsLog: safeText(state.gpu.limits),
                pendingPipelineReport: state.gpu.pendingPipelines,
                lastGpuError: safeText(state.gpu.lastGpuError),
                lastShaderOrPipelineError: safeText(state.gpu.lastShaderError),
                gpuWorkerLastHeartbeatAt: isoTime(state.gpu.gpuWorkerHeartbeatAt),
                gpuWorkerHeartbeatGapMs: state.gpu.gpuWorkerHeartbeatGapMs
            },
            experimentalGraphicsMode: {
                requested: state.fix.requested,
                launchMessageObserved: state.fix.launchMessageObserved,
                syncPipelinesSetOnLaunchMessage: state.fix.appliedToLaunchMessage,
                gpuWorkerUrlObserved: state.fix.gpuWorkerObserved,
                syncPipelinesOnGpuWorkerUrl: state.fix.workerSyncPipelines,
                loaderGpuWorkerUrlConfirmedSyncPipelines: state.fix.workerOptionConfirmed,
                status: safeText(state.fix.status)
            },
            workers: workers,
            recentMessages: last
        };
    }

    function getReport() {
        try { return JSON.stringify(buildReportObject(), null, 2); }
        catch (error) { return 'Diagnostics export failed: ' + safeText(error); }
    }

    function formatWorkerList() {
        var keys = Object.keys(workerStates);
        if (!keys.length) return 'No worker creation observed yet.';
        return keys.slice(-8).map(function (key) {
            var worker = workerStates[key];
            return worker.role + ': ' + (worker.state || 'observed') +
                (worker.lastMessageAt ? ' · last event ' + elapsedSeconds(Date.parse(worker.lastMessageAt)) : '') +
                (worker.heartbeatGapMs ? ' · heartbeat gap ' + worker.heartbeatGapMs + ' ms' : '');
        }).join('\n');
    }

    function renderSummary() {
        var displayed = state.progress == null ? 'not reported' : state.progress + '% (page display)';
        var target = state.targetProgress == null ? 'not reported' : state.targetProgress + '% (engine message)';
        var lines = [
            'Stage: ' + safeText(state.stage),
            'Progress: ' + displayed + ' · latest target ' + target,
            'Last stage/progress change: ' + elapsedSeconds(state.progressChangedAt) + ' (' + isoTime(state.progressChangedAt) + ')',
            'Last game progress message: ' + elapsedSeconds(state.lastProgressEventAt),
            'Workers: ' + Object.keys(workerStates).length + ' observed · WASM: ' + state.wasm.state,
            'GPU: WebGPU ' + state.gpu.webGpuApi + ' · adapter ' + state.gpu.adapter +
                (state.gpu.adapterDetails ? ' (' + safeText(state.gpu.adapterDetails) + ')' : ''),
            'Adapter features: ' + safeText(state.gpu.adapterFeatures || state.gpu.features || 'not observed'),
            'Adapter limits: ' + safeText(state.gpu.adapterLimits || state.gpu.limits || 'not observed'),
            'WebGL context is not created by this probe; WebGL API ' + state.gpu.webGlApi + ', WebGL2 ' + state.gpu.webGl2Api,
            'Pipelines: ' + (state.gpu.pendingPipelines == null ? 'no progress report' : safeText(state.gpu.pendingPipelines)),
            'GPU worker heartbeat: ' + elapsedSeconds(state.gpu.gpuWorkerHeartbeatAt) +
                (state.gpu.gpuWorkerHeartbeatGapMs == null ? '' : ' · gap ' + state.gpu.gpuWorkerHeartbeatGapMs + ' ms'),
            'Graphics experiment: ' + safeText(state.fix.status),
            'Privacy: local-only; diagnostic code makes no network requests'
        ];
        return lines.join('\n');
    }

    function renderEvents() {
        var recent = events.slice(-8).reverse();
        if (!recent.length) return 'Waiting for relevant errors, worker state, shader/GPU messages, and heartbeats.';
        return recent.map(function (event) {
            var repeat = event.repeat > 1 ? ' (x' + event.repeat + ')' : '';
            return '[' + new Date(event.atMs).toLocaleTimeString() + '] ' + event.category + ': ' + event.message + repeat;
        }).join('\n');
    }

    function render() {
        if (!ui) return;
        try {
            ui.summary.textContent = renderSummary();
            if (ui.workers) ui.workers.textContent = formatWorkerList();
            ui.events.textContent = renderEvents();
            if (ui.fixBadge) ui.fixBadge.textContent = 'Experimental sync-pipeline mode: ' + state.fix.status;
        } catch (ignored) { }
    }

    function showSelectableReport() {
        if (!ui || !ui.report) return;
        ui.report.value = getReport();
        ui.report.hidden = false;
        ui.report.focus();
        ui.report.select();
        ui.exportStatus.textContent = 'Report selected. Long-press/copy the text; it contains sanitized local diagnostics only.';
    }

    function mountUi() {
        if (!document.createElement || !document.documentElement) return;
        var host = document.createElement('div');
        host.setAttribute('aria-label', 'NewCatroid local diagnostics');
        host.style.position = 'fixed';
        host.style.right = '4px';
        host.style.bottom = 'calc(env(safe-area-inset-bottom, 0px) + 4px)';
        host.style.zIndex = '2147483647';
        host.style.maxWidth = 'min(96vw, 460px)';
        host.style.pointerEvents = 'auto';
        host.id = 'newcatroid-game-diagnostics';
        var root = typeof host.attachShadow === 'function' ? host.attachShadow({ mode: 'open' }) : host;

        var style = document.createElement('style');
        style.textContent = '\
            :host { all: initial; font-family: system-ui, sans-serif; color: #f4f5f7; }\
            .panel { box-sizing: border-box; width: min(96vw, 460px); max-height: 42vh; overflow: auto; background: rgba(15,18,24,.96); border: 1px solid #8aa4c8; border-radius: 8px; box-shadow: 0 2px 14px #0009; font: 12px/1.35 system-ui,sans-serif; }\
            .head { display: flex; align-items: center; gap: 6px; padding: 6px 8px; background: #25344a; position: sticky; top: 0; }\
            .title { font-weight: 700; flex: 1; }\
            button { color: #fff; background: #344967; border: 1px solid #9bb0cb; border-radius: 5px; padding: 6px 8px; min-height: 40px; font: inherit; }\
            button:focus, textarea:focus { outline: 2px solid #9cc9ff; }\
            .content { padding: 7px 8px 9px; }\
            pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 7px; font: 11px/1.35 ui-monospace,monospace; }\
            .events { border-top: 1px solid #50617a; padding-top: 6px; color: #dbe5f2; max-height: 12vh; overflow: auto; }\
            .actions { display: flex; flex-wrap: wrap; gap: 5px; }\
            textarea { box-sizing: border-box; display: block; width: 100%; min-height: 150px; margin-top: 6px; padding: 6px; color: #f4f5f7; background: #0a0c10; border: 1px solid #71839d; border-radius: 4px; font: 10px/1.3 ui-monospace,monospace; }\
            .status { min-height: 1.3em; margin-top: 4px; color: #cbd8e8; }\
            .collapsed .content { display: none; }\
            .fixbadge { margin-bottom: 5px; padding: 5px 7px; background: #594521; border: 1px solid #d2ac67; border-radius: 6px; font-size: 11px; }\
        ';
        var panel = document.createElement('section');
        panel.className = 'panel';
        var header = document.createElement('div');
        header.className = 'head';
        var title = document.createElement('span');
        title.className = 'title';
        title.textContent = 'Local game diagnostics';
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.textContent = 'Hide';
        toggle.setAttribute('aria-expanded', 'true');
        header.appendChild(title);
        header.appendChild(toggle);
        var content = document.createElement('div');
        content.className = 'content';
        var fixBadge = null;
        if (ADRENO_FIX_ENABLED) {
            fixBadge = document.createElement('div');
            fixBadge.className = 'fixbadge';
            content.appendChild(fixBadge);
        }
        var summary = document.createElement('pre');
        var workers = document.createElement('pre');
        var eventsElement = document.createElement('pre');
        eventsElement.className = 'events';
        var actions = document.createElement('div');
        actions.className = 'actions';
        var copyButton = document.createElement('button');
        copyButton.type = 'button';
        copyButton.textContent = 'Copy report';
        var selectButton = document.createElement('button');
        selectButton.type = 'button';
        selectButton.textContent = 'Show/select text';
        var refreshButton = document.createElement('button');
        refreshButton.type = 'button';
        refreshButton.textContent = 'Refresh';
        var exportStatus = document.createElement('div');
        exportStatus.className = 'status';
        var report = document.createElement('textarea');
        report.readOnly = true;
        report.hidden = true;
        report.setAttribute('aria-label', 'Sanitized diagnostics report, selectable text');
        report.setAttribute('spellcheck', 'false');
        actions.appendChild(copyButton);
        actions.appendChild(selectButton);
        actions.appendChild(refreshButton);
        content.appendChild(summary);
        content.appendChild(workers);
        content.appendChild(eventsElement);
        content.appendChild(actions);
        content.appendChild(exportStatus);
        content.appendChild(report);
        panel.appendChild(header);
        panel.appendChild(content);
        root.appendChild(style);
        root.appendChild(panel);
        document.documentElement.appendChild(host);

        ui = { host: host, panel: panel, summary: summary, workers: workers, events: eventsElement,
            report: report, exportStatus: exportStatus, fixBadge: fixBadge };
        toggle.addEventListener('click', function () {
            var collapsed = panel.classList.toggle('collapsed');
            toggle.textContent = collapsed ? 'Show' : 'Hide';
            toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        });
        selectButton.addEventListener('click', showSelectableReport);
        refreshButton.addEventListener('click', function () {
            sampleDisplayedProgress();
            report.value = getReport();
            exportStatus.textContent = 'Report refreshed. Select text to copy or use Copy report.';
            render();
        });
        copyButton.addEventListener('click', function () {
            var text = getReport();
            if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                Promise.resolve(navigator.clipboard.writeText(text)).then(function () {
                    exportStatus.textContent = 'Sanitized report copied to clipboard.';
                }).catch(function () { showSelectableReport(); });
            } else {
                showSelectableReport();
            }
        });
        render();
    }

    function mountFixBadgeOnly() {
        if (DEBUG_ENABLED || !ADRENO_FIX_ENABLED || !document.createElement || !document.documentElement) return;
        var host = document.createElement('div');
        host.style.position = 'fixed';
        host.style.right = '4px';
        host.style.top = 'calc(env(safe-area-inset-top, 0px) + 4px)';
        host.style.zIndex = '2147483647';
        host.style.pointerEvents = 'none';
        host.style.maxWidth = '94vw';
        var root = typeof host.attachShadow === 'function' ? host.attachShadow({ mode: 'open' }) : host;
        var style = document.createElement('style');
        style.textContent = '.badge{font:11px/1.3 system-ui,sans-serif;color:#fff;background:#594521;border:1px solid #d2ac67;border-radius:6px;padding:5px 7px;max-width:92vw;overflow-wrap:anywhere}';
        var badge = document.createElement('div');
        badge.className = 'badge';
        badge.textContent = 'Experimental sync-pipeline mode requested; waiting for game worker launch';
        root.appendChild(style);
        root.appendChild(badge);
        document.documentElement.appendChild(host);
        ui = { host: host, fixBadge: badge, summary: { set textContent(value) { badge.textContent = value; } }, events: { set textContent(ignored) { } } };
        render();
    }

    function installPeriodicObservers() {
        setInterval(function () {
            var now = monotonicNow();
            var gap = Math.max(0, Math.round(now - lastHeartbeat));
            lastHeartbeat = now;
            state.mainHeartbeat.lastGapMs = gap;
            state.mainHeartbeat.maxGapMs = Math.max(state.mainHeartbeat.maxGapMs, gap);
            if (gap > 2500) {
                state.mainHeartbeat.delayedCount++;
                recordEvent('main page heartbeat', 'timer delayed by ' + gap + ' ms; this observer did not block the page');
            }
            sampleDisplayedProgress();
            if (state.gpu.gpuWorkerHeartbeatAt) {
                state.gpu.gpuWorkerHeartbeatAgeSeconds = Math.floor((Date.now() - state.gpu.gpuWorkerHeartbeatAt) / 1000);
            }
            render();
        }, 1000);
    }

    function installProbeApi() {
        window.__newcatroidGameDiagnostics = {
            flags: { debug: DEBUG_ENABLED, adrenoFix: ADRENO_FIX_ENABLED },
            state: state,
            getReport: getReport,
            recordForTest: function (category, message) { recordEvent(category, message); }
        };
    }

    if (DEBUG_ENABLED) installProbeApi();
    installWorkerAndLaunchHooks();
    if (DEBUG_ENABLED) {
        installConsoleHooks();
        installContextErrorObservers();
        listenChannel('game-progress', observeGameProgress);
        listenChannel('game-log', observeGameLog);
        listenChannel('newcatroid-debug', observeWorkerDebug);
        probeWebGpuAdapter();
        installPeriodicObservers();
    } else if (ADRENO_FIX_ENABLED && typeof BroadcastChannel === 'function') {
        // Fix-only mode consumes one narrowly scoped confirmation event from
        // the loader. It does not enable the diagnostic overlay, log capture,
        // adapter probe, or worker heartbeats.
        try {
            var pipelineChannel = new BroadcastChannel('newcatroid-debug');
            pipelineChannel.addEventListener('message', function (event) { observeWorkerDebug(event.data); });
        } catch (ignored) {
            state.fix.status = 'Mode requested; GPU worker selection could not be observed';
        }
    }

    function mountWhenReady() {
        if (document.readyState === 'loading' || !document.documentElement) {
            window.addEventListener('DOMContentLoaded', mountWhenReady, { once: true });
            return;
        }
        if (DEBUG_ENABLED) mountUi();
        else mountFixBadgeOnly();
    }
    mountWhenReady();
})();
