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
    var gpuErrorGroupIndex = Object.create(null);
    var exceptionGroupIndex = Object.create(null);
    var resourceFailureIndex = Object.create(null);
    var audioContextRecords = typeof WeakMap === 'function' ? new WeakMap() : null;
    var audioContextSequence = 0;
    var audioRingView = null;
    var audioRingSampleTimer = null;
    var recentGameLogs = [];
    var lastViewportSignature = '';
    var lastPageMemorySampleAt = 0;
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
        documentVisibilityState: document.visibilityState || 'unknown',
        mainHeartbeat: { lastGapMs: 0, maxGapMs: 0, delayedCount: 0 },
        wasm: { state: 'not observed', lastOperation: '', lastDurationMs: null },
        rendering: {
            firstWorkerFrameSignalAt: null,
            readySignalAt: null,
            worldReadySignalAt: null,
            firstGpuContextAt: null,
            firstCanvasConfigureAt: null,
            firstCanvasTextureAt: null,
            firstQueueSubmitAt: null,
            queueSubmitCount: 0,
            canvas: null,
            canvasConfiguration: null,
            firstQueueSubmitNote: 'Observed submission does not prove that validation succeeded or a frame was displayed.',
            displayPresentationObserved: false,
            gpuOperationCounts: {},
            recentGpuOperations: [],
            pipelineApiCalls: { createRenderPipeline: 0, createRenderPipelineAsync: 0, createComputePipeline: 0, createComputePipelineAsync: 0 },
            lastGraphicsSampleAt: null,
            sceneHints: [],
            note: 'A queue submission or worker first-frame message does not prove that a valid non-black frame reached the display.'
        },
        viewport: {
            initial: null,
            current: null,
            samples: [],
            engineResizeMessages: [],
            note: 'Viewport/canvas observations are measurements only; engine dimensions may be separate and are not forced to landscape or rewritten.'
        },
        gpu: {
            webGpuApi: (navigator && navigator.gpu) ? 'present' : 'absent',
            webGlApi: (typeof WebGLRenderingContext === 'function') ? 'present' : 'absent',
            webGl2Api: (typeof WebGL2RenderingContext === 'function') ? 'present' : 'absent',
            adapter: 'not probed',
            adapterDetails: '',
            adapterFeatures: '',
            adapterLimits: '',
            workerAdapterDetails: '',
            workerDevice: '',
            features: '',
            limits: '',
            pendingPipelines: null,
            lastGpuError: '',
            lastShaderError: '',
            errorGroups: [],
            errorOccurrences: 0,
            overflowErrorOccurrences: 0,
            gpuWorkerHeartbeatAt: null,
            gpuWorkerHeartbeatGapMs: null,
            deviceLost: null,
            operationObserverStatus: '',
            gpuWorkerConfig: null
        },
        fix: {
            requested: ADRENO_FIX_ENABLED,
            launchMessageObserved: false,
            appliedToLaunchMessage: false,
            loaderReceivedSyncPipelines: null,
            gpuWorkerObserved: null,
            workerSyncPipelines: null,
            gpuWorkerStarted: null,
            gpuWorkerSyncQueryObserved: null,
            syncPipelineMethodCallsObserved: 0,
            asyncPipelineMethodCallsObserved: 0,
            workerPipelineImplementationObserved: false,
            workerOptionConfirmed: false,
            behaviorConsistentWithRequest: null,
            status: ADRENO_FIX_ENABLED ? 'Requested; waiting for game worker launch' : 'Disabled'
        },
        audio: {
            audioDataMessageReceived: false,
            capabilities: [],
            memoryBufferBytes: null,
            ringCapacity: null,
            userGestureAt: null,
            contexts: [],
            workletModules: [],
            nodes: [],
            playAttempts: [],
            outputEvents: [],
            ringSamples: [],
            lastUserActivation: null,
            note: 'Running AudioContext/worklet and advancing ring counters do not prove audible output; autoplay policy and physical output are separate.'
        },
        memory: {
            latestWasmLinearMemory: null,
            wasmLinearMemorySnapshots: [],
            wasmGrowthEvents: [],
            crashHeapSamples: [],
            engineMemoryMessages: [],
            lastJsHeapSample: null,
            pageJsHeap: null,
            deviceMemoryGiB: (navigator && typeof navigator.deviceMemory === 'number') ? navigator.deviceMemory : null,
            note: 'Linear-memory capacity, allocator counters, JS heap estimates, GPU estimates, and physical RAM are different measurements.'
        },
        resources: {
            groups: [],
            failures: [],
            note: 'Resource kinds are classified only from observable request paths/extensions and worker fetch status. Packed archive contents are not attributed to audio without an explicit signal; a loaded asset does not prove playback.'
        },
        exceptions: [],
        observations: [],
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

    function safeText(value, maxLength) {
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
            .replace(/(\b(?:user\s*(?:name|id)?|player\s*(?:name|id)?|account\s*(?:id)?|profile(?:\s*id)?|display\s*name|email)\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^,;\s}\]]+)/gi, '$1[REDACTED]')
            .replace(/(?:\/data\/user\/\d+\/|\/storage\/emulated\/\d+\/|\/sdcard\/|\/home\/[^/\s]+\/|[A-Z]:\\Users\\[^\\\s]+\\)[^\s]*/gi, '[LOCAL_PATH]')
            .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[EMAIL]')
            .replace(/https?:\/\/[^\s"'<>]+/gi, function (match) { return safeUrl(match); });
        return text.slice(0, maxLength || 4000);
    }

    function nowIso() {
        return isoTime(Date.now());
    }

    function sanitizeContext(items) {
        if (!Array.isArray(items)) return [];
        return items.slice(-24).map(function (item) {
            return {
                at: isoTime(Number(item && item.at) || Date.now()),
                category: safeText(item && item.category || '', 160),
                message: safeText(item && item.message || '', 8000)
            };
        });
    }

    function captureWebGpuErrorGroup(data) {
        if (!data || !data.details) return;
        var details = data.details;
        var session = safeText(data.sessionId || data.source || 'gpu-worker', 160);
        var workerGroupId = safeText(details.groupId || 'unknown-error-group', 160);
        var key = session + '|' + workerGroupId;
        var group = gpuErrorGroupIndex[key];
        var count = Math.max(0, Number(details.count) || 0);
        if (!group) {
            group = {
                id: key,
                source: safeText(data.source || 'gpu-worker', 160),
                sessionId: session,
                workerGroupId: workerGroupId,
                type: safeText(details.type || 'WebGPUError', 200),
                message: safeText(details.message || '[WebGPU error message unavailable]', 12000),
                sourceKind: safeText(details.sourceKind || 'WebGPU error', 200),
                sourceKinds: Array.isArray(details.sourceKinds) ? details.sourceKinds.slice(0, 20).map(function (item) { return safeText(item, 200); }) : [safeText(details.sourceKind || 'WebGPU error', 200)],
                sourceKindCounts: copyNumberMap(details.sourceKindCounts),
                count: count,
                firstAt: isoTime(Number(details.firstAt) || Number(data.at) || Date.now()),
                lastAt: isoTime(Number(details.lastAt) || Number(data.at) || Date.now()),
                firstContext: sanitizeContext(details.firstContext),
                latestContext: sanitizeContext(details.latestContext),
                firstOperationCounts: details.firstOperationCounts || {},
                latestOperationCounts: details.latestOperationCounts || {}
            };
            gpuErrorGroupIndex[key] = group;
            state.gpu.errorGroups.push(group);
            if (state.gpu.errorGroups.length > 200) state.gpu.errorGroups.shift();
            recordEvent('WebGPU ' + group.type, group.message.slice(0, 900));
        } else {
            group.count = count;
            if (Array.isArray(details.sourceKinds)) group.sourceKinds = details.sourceKinds.slice(0, 20).map(function (item) { return safeText(item, 200); });
            if (details.sourceKindCounts) group.sourceKindCounts = copyNumberMap(details.sourceKindCounts);
            group.lastAt = isoTime(Number(details.lastAt) || Number(data.at) || Date.now());
            group.latestContext = sanitizeContext(details.latestContext);
            group.latestOperationCounts = details.latestOperationCounts || group.latestOperationCounts;
        }
        state.gpu.errorOccurrences = Object.keys(gpuErrorGroupIndex).reduce(function (total, itemKey) {
            return total + (Number(gpuErrorGroupIndex[itemKey].count) || 0);
        }, 0);
        if (workerGroupId === 'gpu-error-overflow') state.gpu.overflowErrorOccurrences = group.count;
        state.gpu.lastGpuError = group.type + ': ' + group.message;
        if (/shader|pipeline|validation|bind group|format/i.test(group.type + ' ' + group.message)) state.gpu.lastShaderError = group.type + ': ' + group.message;
    }

    function captureException(details, source, kind) {
        details = details && typeof details === 'object' ? details : { message: safeText(details, 12000) };
        var name = safeText(details.name || kind || 'Exception', 200);
        var message = safeText(details.message || details.reason || '[exception message unavailable]', 16000);
        var stack = safeText(details.stack || '', 20000);
        var thread = safeText(details.thread || details.workerName || source || 'page', 200);
        var safeSource = safeText(source || details.source || 'page', 160);
        var key = safeSource + '|' + thread + '|' + name + '|' + message + '|' + stack;
        var group = exceptionGroupIndex[key];
        var now = Date.now();
        if (!group) {
            group = {
                source: safeSource,
                thread: thread,
                name: name,
                message: message,
                stack: stack,
                originalText: safeText(details.originalText || message, 20000),
                kind: safeText(kind || details.kind || 'exception', 160),
                firstAt: isoTime(Number(details.at) || now),
                lastAt: isoTime(Number(details.at) || now),
                timestampSource: safeText(details.timestampSource || 'capture time supplied by observer', 240),
                receivedAt: isoTime(now),
                lastReceivedAt: isoTime(now),
                count: 1,
                resource: safeText(details.resource || details.filename || '', 500),
                line: Number(details.line) || null,
                column: Number(details.column) || null,
                context: sanitizeContext(details.recentContext || details.context),
                latestContext: sanitizeContext(details.recentContext || details.context),
                recentMessages: Array.isArray(details.recentMessages) ? details.recentMessages.slice(-60).map(function (line) { return safeText(line, 1000); }) : [],
                recentGpuOperations: safeText(details.recentGpuOperations ? JSON.stringify(details.recentGpuOperations) : '', 24000),
                wasmLinearMemoryBufferMiBAtCrash: typeof details.wasmLinearMemoryBufferMiBAtCrash === 'number' ? details.wasmLinearMemoryBufferMiBAtCrash : null,
                lastWasmLinearMemoryBufferMiBAtCrash: typeof details.wasmLinearMemoryBufferMiBAtCrash === 'number' ? details.wasmLinearMemoryBufferMiBAtCrash : null,
                wasmMemoryMetric: safeText(details.wasmMemoryMetric || '', 240)
            };
            exceptionGroupIndex[key] = group;
            state.exceptions.push(group);
            if (state.exceptions.length > 50) state.exceptions.shift();
            recordEvent(kind || 'exception', thread + ': ' + message.slice(0, 900) + (stack ? '\n' + stack.slice(0, 1000) : ''));
        } else {
            group.count++;
            group.lastAt = isoTime(Number(details.at) || now);
            group.lastReceivedAt = isoTime(now);
            if (typeof details.wasmLinearMemoryBufferMiBAtCrash === 'number') {
                group.lastWasmLinearMemoryBufferMiBAtCrash = details.wasmLinearMemoryBufferMiBAtCrash;
            }
            group.latestContext = sanitizeContext(details.recentContext || details.context);
            if (Array.isArray(details.recentMessages) && details.recentMessages.length) {
                group.recentMessages = details.recentMessages.slice(-24).map(function (line) { return safeText(line, 1000); });
            }
        }
        return group;
    }

    function recordEvent(category, message) {
        var cleaned = safeText(message, 8000).trim();
        if (!cleaned) return;
        var safeCategory = safeText(category, 160);
        var now = Date.now();
        var previous = events.length ? events[events.length - 1] : null;
        if (previous && previous.category === safeCategory && previous.message === cleaned && now - previous.lastAtMs < 5000) {
            previous.repeat = (previous.repeat || 1) + 1;
            previous.lastAt = isoTime(now);
            previous.lastAtMs = now;
        } else {
            events.push({ at: isoTime(now), atMs: now, firstAt: isoTime(now), lastAt: isoTime(now), lastAtMs: now,
                sequence: events.length ? events[events.length - 1].sequence + 1 : 1,
                category: safeCategory, message: cleaned, repeat: 1 });
            if (events.length > 250) events.splice(0, events.length - 250);
        }
        render();
    }

    function recentEventContext(limit) {
        return events.slice(-(limit || 30)).map(function (event) {
            return { at: event.atMs, category: event.category, message: event.message };
        });
    }

    function captureCrashText(text, thread, source, metadata) {
        metadata = metadata || {};
        var originalText = safeText(text, 20000);
        var lines = originalText.split(/\r?\n/);
        var firstLine = lines[0] || originalText;
        var crashMessage = firstLine;
        var threadMatch = firstLine.match(/THREAD (?:ERROR|REJECTION)\s*\(([^)]+)\)\s*:\s*(.*)/i);
        if (threadMatch) {
            thread = thread || threadMatch[1];
            crashMessage = threadMatch[2] || firstLine;
        }
        var nameMatch = crashMessage.match(/(?:^|\b)([A-Za-z_$][A-Za-z0-9_$]*(?:Error|Exception))\s*:\s*/);
        var exceptionName = nameMatch ? nameMatch[1] : safeText(metadata.kind || 'EngineException', 200);
        var stack = lines.length > 1 ? lines.slice(1).join('\n') : '';
        var recentMessages = Array.isArray(metadata.recentMessages) && metadata.recentMessages.length
            ? metadata.recentMessages.slice(-60)
            : recentGameLogs.slice(-60).map(function (item) { return item.message; });
        var details = {
            name: exceptionName,
            message: crashMessage,
            stack: stack,
            originalText: originalText,
            kind: safeText(metadata.kind || 'engine crash', 160),
            thread: safeText(thread || metadata.thread || 'engine thread', 200),
            at: Number(metadata.at) || Date.now(),
            timestampSource: metadata.timestampSource || 'page received/logged time; source payload did not supply a crash timestamp',
            recentContext: Array.isArray(metadata.recentContext) && metadata.recentContext.length
                ? metadata.recentContext : recentEventContext(30),
            recentMessages: recentMessages,
            recentGpuOperations: metadata.recentGpuOperations || null,
            resource: metadata.resource || '',
            line: metadata.line,
            column: metadata.column,
            wasmLinearMemoryBufferMiBAtCrash: typeof metadata.heapMiB === 'number' && metadata.heapMiB >= 0 ? metadata.heapMiB : null,
            wasmMemoryMetric: metadata.heapMetric || ''
        };
        if (typeof details.wasmLinearMemoryBufferMiBAtCrash === 'number') {
            state.memory.crashHeapSamples.push({
                at: isoTime(details.at),
                thread: details.thread,
                valueMiB: details.wasmLinearMemoryBufferMiBAtCrash,
                metric: details.wasmMemoryMetric
            });
            if (state.memory.crashHeapSamples.length > 50) state.memory.crashHeapSamples.shift();
        }
        var captured = captureException(details, source || 'PlayGTA5 crash text', 'engine crash (' + details.kind + ')');
        captured.recentMessages = recentMessages.map(function (line) { return safeText(line, 1000); });
        return captured;
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
        var copyKeys = ['loaded', 'init', 'pct', 'label', 'world', 'ready', 'compiling', 'gpuLost', 'error', 'hud', 'input', 'audio'];
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
                var data = event && event.data;
                var summary = summarizeMessage(data);
                workerState.lastMessageAt = nowIso();
                workerState.lastMessage = safeText(JSON.stringify(summary), 4000);
                if (data && data.loaded) workerState.state = 'loaded';
                if (data && data.error) {
                    workerState.state = 'error';
                    var workerError = data.error && typeof data.error === 'object' ? data.error : { message: data.error };
                    captureException({
                        name: workerError.name,
                        message: workerError.message || String(data.error),
                        stack: workerError.stack || '',
                        thread: role,
                        recentContext: []
                    }, role, 'worker message error');
                }
                if (data && data.audio) observeAudioWorkerMessage(data.audio, role);
                recordEvent('worker', role + ' message: ' + JSON.stringify(summary));
            }
            function onError(event) {
                workerState.state = 'error';
                var original = event && event.error;
                var details = {
                    name: original && original.name || 'WorkerError',
                    message: original && original.message || event && event.message || 'worker error',
                    stack: original && original.stack || '',
                    thread: role,
                    resource: safeUrl(event && event.filename || urlString),
                    line: Number(event && event.lineno) || null,
                    column: Number(event && event.colno) || null
                };
                captureException(details, role, 'worker error');
            }
            try {
                if (typeof worker.addEventListener === 'function') {
                    worker.addEventListener('message', onMessage);
                    worker.addEventListener('error', onError);
                    worker.addEventListener('messageerror', function (event) {
                        captureException({ message: 'message deserialization failed', thread: role, recentContext: [] }, role, 'worker messageerror');
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
        return /(error|failed|exception|reject|warning|warn|assert|shader|pipeline|webgpu|webgl|gpu|wasm|worker|wait|stall|timeout|fence|ring|framebuffer|context|initiali[sz]|loaded|world|readback|memory|allocator|arena|heap|oom|audio|autoplay|media|sound|leaderboard|net_|resize|viewport|canvas|present|menu|pause|hud|scene|unreachable|device lost|validation|adapter|feature|limit|texture|format|attachment|crash)/i.test(text);
    }

    function boundedPush(list, value, limit) {
        list.push(value);
        if (list.length > limit) list.splice(0, list.length - limit);
    }

    function recordLog(value, category) {
        var text = safeText(value, 16000);
        var lines = text.split(/\r?\n/);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line || !relevantLog(line)) continue;
            var at = Date.now();
            boundedPush(recentGameLogs, { at: at, category: safeText(category || 'console', 160), message: safeText(line, 2000) }, 120);
            recordEvent(category || 'console', line);
            if (/shader|pipeline|validation/i.test(line)) state.gpu.lastShaderError = line;
            if (/device ready|adapter/i.test(line)) state.gpu.workerDevice = line;
            if (/features?/i.test(line)) state.gpu.features = line;
            if (/limits?/i.test(line)) state.gpu.limits = line;
            if (/gpu|webgpu|device lost|framebuffer|context/i.test(line) && /error|failed|lost|unsupported|invalid/i.test(line)) {
                state.gpu.lastGpuError = line;
            }
            if (/wasm|instantiate|compile/i.test(line)) state.wasm.lastOperation = line;
            if (/audio|autoplay|media|sound|worklet/i.test(line)) {
                boundedPush(state.audio.outputEvents, { at: isoTime(at), source: safeText(category || 'engine log', 160), message: safeText(line, 2000) }, 100);
            }
            if (/menu|pause|hud|scene|world/i.test(line)) {
                boundedPush(state.rendering.sceneHints, { at: isoTime(at), source: safeText(category || 'engine log', 160), message: safeText(line, 2000) }, 100);
            }
            var resizeMatch = line.match(/window resized to\s+(\d+)x(\d+)(?:\s*\(was\s+(\d+)x(\d+)\))?/i);
            if (resizeMatch) {
                boundedPush(state.viewport.engineResizeMessages, {
                    at: isoTime(at),
                    message: safeText(line, 2000),
                    width: Number(resizeMatch[1]),
                    height: Number(resizeMatch[2]),
                    previousWidth: resizeMatch[3] ? Number(resizeMatch[3]) : null,
                    previousHeight: resizeMatch[4] ? Number(resizeMatch[4]) : null,
                    source: safeText(category || 'engine log', 160)
                }, 50);
            }
            if (/memory|allocator|arena|heap|oom/i.test(line)) {
                var memoryEntry = { at: isoTime(at), source: safeText(category || 'engine log', 160), message: safeText(line, 2000), metric: 'engine text; numeric meaning not inferred' };
                var emscriptenHeapMatch = line.match(/\[mem\]\s*wasm memory\s+([\d.]+)\s*MB/i);
                if (emscriptenHeapMatch) {
                    memoryEntry.valueMiB = Number(emscriptenHeapMatch[1]);
                    memoryEntry.metric = 'Emscripten HEAPU8.length / 1048576, integer-truncated in the observed game.js log; linear-memory view capacity, not allocator-used bytes or RSS';
                }
                boundedPush(state.memory.engineMemoryMessages, memoryEntry, 100);
            }
        }
    }

    function viewportSnapshot() {
        var snapshot = {
            at: isoTime(Date.now()),
            innerWidth: Number(window.innerWidth) || null,
            innerHeight: Number(window.innerHeight) || null,
            outerWidth: Number(window.outerWidth) || null,
            outerHeight: Number(window.outerHeight) || null,
            devicePixelRatio: Number(window.devicePixelRatio) || 1,
            visualViewport: null,
            screenOrientation: null,
            documentVisibilityState: document.visibilityState || 'unknown',
            canvases: []
        };
        try {
            var vv = window.visualViewport;
            if (vv) snapshot.visualViewport = {
                width: Number(vv.width) || null,
                height: Number(vv.height) || null,
                offsetLeft: Number(vv.offsetLeft) || 0,
                offsetTop: Number(vv.offsetTop) || 0,
                scale: Number(vv.scale) || 1
            };
        } catch (ignored) { }
        try {
            var orientation = window.screen && window.screen.orientation;
            if (orientation) snapshot.screenOrientation = {
                type: safeText(orientation.type || '', 80),
                angle: Number(orientation.angle) || 0
            };
        } catch (ignored) { }
        try {
            var canvases = document.getElementsByTagName('canvas');
            for (var i = 0; canvases && i < Math.min(canvases.length, 8); i++) {
                var canvas = canvases[i];
                var rect = canvas.getBoundingClientRect ? canvas.getBoundingClientRect() : null;
                var style = null;
                try { style = window.getComputedStyle ? window.getComputedStyle(canvas) : null; } catch (ignored) { }
                var display = style ? safeText(style.display || '', 40) : '';
                var visibility = style ? safeText(style.visibility || '', 40) : '';
                var opacity = style ? Number(style.opacity) : null;
                snapshot.canvases.push({
                    index: i,
                    bitmapWidth: Number(canvas.width) || 0,
                    bitmapHeight: Number(canvas.height) || 0,
                    clientWidth: Number(canvas.clientWidth) || 0,
                    clientHeight: Number(canvas.clientHeight) || 0,
                    cssWidth: style ? safeText(style.width || '', 40) : '',
                    cssHeight: style ? safeText(style.height || '', 40) : '',
                    rect: rect ? { x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height) } : null,
                    display: display,
                    visibility: visibility,
                    opacity: isFinite(opacity) ? opacity : null,
                    cssVisible: !!rect && rect.width > 0 && rect.height > 0 && display !== 'none' && visibility !== 'hidden' && opacity !== 0
                });
            }
        } catch (ignored) { }
        return snapshot;
    }

    function sampleViewport(force) {
        try {
            var snapshot = viewportSnapshot();
            var signatureObject = Object.assign({}, snapshot);
            delete signatureObject.at;
            var signature = JSON.stringify(signatureObject);
            state.documentVisibilityState = snapshot.documentVisibilityState;
            if (!state.viewport.initial) state.viewport.initial = snapshot;
            if (!force && signature === lastViewportSignature) {
                state.viewport.current = snapshot;
                return;
            }
            lastViewportSignature = signature;
            state.viewport.current = snapshot;
            boundedPush(state.viewport.samples, snapshot, 100);
            recordEvent('viewport sample', 'inner=' + snapshot.innerWidth + 'x' + snapshot.innerHeight +
                ', DPR=' + snapshot.devicePixelRatio + ', canvas count=' + snapshot.canvases.length +
                ', visualViewport=' + (snapshot.visualViewport ? snapshot.visualViewport.width + 'x' + snapshot.visualViewport.height : 'unavailable'));
        } catch (error) {
            recordEvent('viewport observer', 'sample failed: ' + safeText(error));
        }
    }

    function installViewportObservers() {
        sampleViewport(true);
        var debounced = null;
        function schedule() {
            if (debounced) clearTimeout(debounced);
            debounced = setTimeout(function () { sampleViewport(true); }, 150);
        }
        try {
            window.addEventListener('resize', schedule, { passive: true });
            window.addEventListener('orientationchange', schedule, { passive: true });
            if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', schedule, { passive: true });
                window.visualViewport.addEventListener('scroll', schedule, { passive: true });
            }
            document.addEventListener('visibilitychange', function () {
                state.documentVisibilityState = document.visibilityState || 'unknown';
                sampleViewport(true);
                recordEvent('document visibility', state.documentVisibilityState);
            });
        } catch (ignored) { }
    }

    function audioContextRecord(context, constructorName) {
        if (!context || typeof context !== 'object') return null;
        var record = null;
        try { if (audioContextRecords) record = audioContextRecords.get(context) || null; } catch (ignored) { }
        if (!record) {
            record = {
                id: 'audio-context-' + (++audioContextSequence),
                constructor: safeText(constructorName || 'AudioContext', 120),
                createdAt: isoTime(Date.now()),
                state: 'unknown',
                sampleRate: null,
                baseLatency: null,
                outputLatency: null,
                destinationMaxChannelCount: null,
                resumeAttempts: 0,
                lastResumeResult: 'not observed',
                stateChanges: 0
            };
            try { if (audioContextRecords) audioContextRecords.set(context, record); } catch (ignored) { }
            boundedPush(state.audio.contexts, record, 30);
        }
        try { record.state = safeText(context.state || 'unknown', 80); } catch (ignored) { }
        try { record.sampleRate = Number(context.sampleRate) || null; } catch (ignored) { }
        try { record.baseLatency = Number(context.baseLatency) || null; } catch (ignored) { }
        try { record.outputLatency = Number(context.outputLatency) || null; } catch (ignored) { }
        try { record.destinationMaxChannelCount = Number(context.destination && context.destination.maxChannelCount) || null; } catch (ignored) { }
        return record;
    }

    function recordAudioEvent(kind, message, details) {
        var event = { at: isoTime(Date.now()), kind: safeText(kind, 120), message: safeText(message, 2000) };
        if (details) event.details = details;
        boundedPush(state.audio.outputEvents, event, 100);
        recordEvent('audio ' + event.kind, event.message);
    }

    function observeAudioWorkerMessage(audio, role) {
        state.audio.audioDataMessageReceived = true;
        var summary = { at: isoTime(Date.now()), role: safeText(role || 'worker', 120), type: typeof audio };
        if (audio && typeof audio === 'object') {
            ['state', 'type', 'status', 'message', 'sampleRate', 'channels', 'bufferBytes', 'ringCapacity', 'workletName'].forEach(function (key) {
                if (Object.prototype.hasOwnProperty.call(audio, key)) summary[key] = safeText(audio[key], 500);
            });
            if (typeof audio.memoryBufferBytes === 'number') state.audio.memoryBufferBytes = audio.memoryBufferBytes;
            if (typeof audio.bufferBytes === 'number') state.audio.memoryBufferBytes = audio.bufferBytes;
            if (typeof audio.ringCapacity === 'number') state.audio.ringCapacity = audio.ringCapacity;
        } else {
            summary.value = safeText(audio, 500);
        }
        boundedPush(state.audio.outputEvents, { at: summary.at, kind: 'worker audio message', message: JSON.stringify(summary) }, 100);
    }

    function recordAudioPlayAttempt(kind, outcome, error, contextId) {
        var activation = null;
        try {
            if (navigator.userActivation) activation = {
                isActive: !!navigator.userActivation.isActive,
                hasBeenActive: !!navigator.userActivation.hasBeenActive
            };
        } catch (ignored) { }
        var attempt = {
            at: isoTime(Date.now()),
            kind: safeText(kind, 120),
            outcome: safeText(outcome, 120),
            error: error ? safeText(error, 4000) : '',
            contextId: safeText(contextId || '', 120),
            lastUserGestureAt: state.audio.userGestureAt,
            userActivation: activation,
            documentVisibilityState: document.visibilityState || 'unknown'
        };
        boundedPush(state.audio.playAttempts, attempt, 100);
        recordAudioEvent('playback attempt', kind + ' ' + outcome + (attempt.error ? ': ' + attempt.error : ''), attempt);
    }

    function installAudioObservers() {
        if (!DEBUG_ENABLED) return;
        function observeContext(context, constructorName) {
            var record = audioContextRecord(context, constructorName);
            if (!record || record.listenerInstalled) return record;
            record.listenerInstalled = true;
            recordAudioEvent('context created', record.id + ' (' + record.constructor + '), state=' + record.state, {
                sampleRate: record.sampleRate, baseLatency: record.baseLatency,
                outputLatency: record.outputLatency, destinationMaxChannelCount: record.destinationMaxChannelCount
            });
            try {
                if (typeof context.addEventListener === 'function') context.addEventListener('statechange', function () {
                    record.state = safeText(context.state || 'unknown', 80);
                    record.stateChanges++;
                    record.lastStateChangeAt = isoTime(Date.now());
                    recordAudioEvent('context state', record.id + ' state=' + record.state, {
                        stateChanges: record.stateChanges, lastUserGestureAt: state.audio.userGestureAt
                    });
                });
            } catch (ignored) { }
            try {
                var worklet = context.audioWorklet;
                if (worklet && !worklet.__newcatroidObserved && typeof worklet.addModule === 'function') {
                    var originalAddModule = worklet.addModule;
                    worklet.addModule = function (url) {
                        var safeModule = safeUrl(url);
                        boundedPush(state.audio.workletModules, { at: isoTime(Date.now()), contextId: record.id, module: safeModule, status: 'requested' }, 50);
                        recordAudioEvent('worklet module', record.id + ' requested ' + safeModule);
                        var result;
                        try { result = Reflect.apply(originalAddModule, this, arguments); }
                        catch (error) {
                            recordAudioEvent('worklet module failure', safeModule + ': ' + safeText(error, 4000));
                            throw error;
                        }
                        if (result && typeof result.then === 'function') {
                            result.then(function () {
                                boundedPush(state.audio.workletModules, { at: isoTime(Date.now()), contextId: record.id, module: safeModule, status: 'loaded' }, 50);
                                recordAudioEvent('worklet module loaded', safeModule);
                            }, function (error) {
                                boundedPush(state.audio.workletModules, { at: isoTime(Date.now()), contextId: record.id, module: safeModule, status: 'rejected', error: safeText(error, 2000) }, 50);
                                recordAudioEvent('worklet module rejected', safeModule + ': ' + safeText(error, 4000));
                            });
                        }
                        return result;
                    };
                    worklet.__newcatroidObserved = true;
                }
            } catch (ignored) { }
            return record;
        }

        ['AudioContext', 'webkitAudioContext'].forEach(function (name) {
            var NativeAudioContext;
            try { NativeAudioContext = window[name]; } catch (ignored) { }
            if (typeof NativeAudioContext !== 'function') return;
            try {
                if (typeof Proxy !== 'function') return;
                var wrappedConstructor;
                wrappedConstructor = new Proxy(NativeAudioContext, {
                    construct: function (target, args, newTarget) {
                        var context = Reflect.construct(target, args, newTarget === wrappedConstructor ? target : newTarget);
                        observeContext(context, name);
                        return context;
                    }
                });
                window[name] = wrappedConstructor;
            } catch (error) {
                recordEvent('audio observer', name + ' constructor hook unavailable: ' + safeText(error));
            }
            try {
                var prototype = NativeAudioContext.prototype;
                ['resume', 'suspend', 'close'].forEach(function (methodName) {
                    var original = prototype && prototype[methodName];
                    if (typeof original !== 'function' || original.__newcatroidAudioObserver) return;
                    var wrapped = function () {
                        var record = observeContext(this, name);
                        if (methodName === 'resume' && record) record.resumeAttempts++;
                        recordAudioPlayAttempt('AudioContext.' + methodName, 'called', '', record && record.id);
                        var result;
                        try { result = Reflect.apply(original, this, arguments); }
                        catch (error) {
                            if (record && methodName === 'resume') record.lastResumeResult = 'threw: ' + safeText(error, 1000);
                            recordAudioPlayAttempt('AudioContext.' + methodName, 'threw', error, record && record.id);
                            throw error;
                        }
                        if (result && typeof result.then === 'function') {
                            result.then(function () {
                                if (record && methodName === 'resume') record.lastResumeResult = 'fulfilled';
                                recordAudioPlayAttempt('AudioContext.' + methodName, 'fulfilled', '', record && record.id);
                            }, function (error) {
                                if (record && methodName === 'resume') record.lastResumeResult = 'rejected: ' + safeText(error, 1000);
                                recordAudioPlayAttempt('AudioContext.' + methodName, 'rejected', error, record && record.id);
                            });
                        }
                        return result;
                    };
                    wrapped.__newcatroidAudioObserver = true;
                    prototype[methodName] = wrapped;
                });
            } catch (ignored) { }
        });

        try {
            var BufferSource = window.AudioBufferSourceNode;
            var sourcePrototype = BufferSource && BufferSource.prototype;
            var originalStart = sourcePrototype && sourcePrototype.start;
            if (typeof originalStart === 'function' && !originalStart.__newcatroidAudioObserver) {
                var wrappedStart = function () {
                    var contextRecord = observeContext(this.context, 'AudioBufferSourceNode.context');
                    recordAudioPlayAttempt('AudioBufferSourceNode.start', 'called', '', contextRecord && contextRecord.id);
                    try {
                        var result = Reflect.apply(originalStart, this, arguments);
                        recordAudioPlayAttempt('AudioBufferSourceNode.start', 'returned without synchronous error', '', contextRecord && contextRecord.id);
                        return result;
                    } catch (error) {
                        recordAudioPlayAttempt('AudioBufferSourceNode.start', 'threw', error, contextRecord && contextRecord.id);
                        throw error;
                    }
                };
                wrappedStart.__newcatroidAudioObserver = true;
                sourcePrototype.start = wrappedStart;
            }
        } catch (ignored) { }
        try {
            var NativeWorkletNode = window.AudioWorkletNode;
            if (typeof NativeWorkletNode === 'function' && typeof Proxy === 'function') {
                var WrappedWorkletNode;
                WrappedWorkletNode = new Proxy(NativeWorkletNode, {
                    construct: function (target, args, newTarget) {
                        var node = Reflect.construct(target, args, newTarget === WrappedWorkletNode ? target : newTarget);
                        var contextRecord = observeContext(args[0], 'AudioWorkletNode.context');
                        var nodeRecord = {
                            at: isoTime(Date.now()),
                            type: 'AudioWorkletNode',
                            processorName: safeText(args[1] || '', 200),
                            contextId: contextRecord && contextRecord.id,
                            state: contextRecord && contextRecord.state
                        };
                        boundedPush(state.audio.nodes, nodeRecord, 100);
                        recordAudioEvent('worklet node created', nodeRecord.processorName || 'processor name not reported', nodeRecord);
                        try {
                            node.addEventListener('processorerror', function () {
                                recordAudioEvent('worklet processor error', nodeRecord.processorName || 'AudioWorkletNode processorerror event', nodeRecord);
                            });
                        } catch (ignored) { }
                        return node;
                    }
                });
                window.AudioWorkletNode = WrappedWorkletNode;
            }
        } catch (error) {
            recordEvent('audio observer', 'AudioWorkletNode constructor hook unavailable: ' + safeText(error));
        }
        try {
            var mediaPrototype = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
            var originalPlay = mediaPrototype && mediaPrototype.play;
            if (typeof originalPlay === 'function' && !originalPlay.__newcatroidAudioObserver) {
                var wrappedPlay = function () {
                    recordAudioPlayAttempt('HTMLMediaElement.play', 'called');
                    var result;
                    try { result = Reflect.apply(originalPlay, this, arguments); }
                    catch (error) { recordAudioPlayAttempt('HTMLMediaElement.play', 'threw', error); throw error; }
                    if (result && typeof result.then === 'function') {
                        result.then(function () { recordAudioPlayAttempt('HTMLMediaElement.play', 'fulfilled'); },
                            function (error) { recordAudioPlayAttempt('HTMLMediaElement.play', 'rejected', error); });
                    }
                    return result;
                };
                wrappedPlay.__newcatroidAudioObserver = true;
                mediaPrototype.play = wrappedPlay;
            }
        } catch (ignored) { }

        try {
            ['pointerdown', 'touchstart', 'keydown', 'click'].forEach(function (eventName) {
                window.addEventListener(eventName, function () {
                    state.audio.userGestureAt = isoTime(Date.now());
                    var activation = null;
                    try { if (navigator.userActivation) activation = { isActive: !!navigator.userActivation.isActive, hasBeenActive: !!navigator.userActivation.hasBeenActive }; } catch (ignored) { }
                    state.audio.lastUserActivation = activation;
                }, { capture: true, passive: true });
            });
        } catch (ignored) { }
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
        var now = Date.now();
        if (Array.isArray(data.lines)) recordLog(data.lines.join('\n'), 'game-progress engine log');
        state.lastGameMessageAt = now;
        state.lastProgressEventAt = now;
        var label = typeof data.label === 'string' ? safeText(data.label, 500) : '';
        if (typeof data.pct === 'number') setProgress(data.pct, label);
        else if (label) setProgress(state.targetProgress, label);
        if (/first frame/i.test(label) && !state.rendering.firstWorkerFrameSignalAt) {
            state.rendering.firstWorkerFrameSignalAt = isoTime(now);
            recordEvent('render milestone', 'GPU worker reported its first-frame milestone; pixel validity/display visibility are not proven');
        }
        if ((/^(ready|world shown)$/i.test(label) || (typeof data.pct === 'number' && data.pct >= 100)) && !state.rendering.readySignalAt) {
            state.rendering.readySignalAt = isoTime(now);
            recordEvent('render milestone', 'Game progress channel reported Ready/100%; this is an engine signal, not proof of visible non-black output');
        }
        if (data.world === true || data.ready === true) {
            state.gameWorldReady = true;
            state.rendering.worldReadySignalAt = state.rendering.worldReadySignalAt || isoTime(now);
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
            state.gpu.lastGpuError = safeText(data.gpuLost, 8000);
            recordEvent('GPU device lost', data.gpuLost);
        }
        if (data.error) {
            setStage('Initialization error');
            captureException({ message: safeText(data.error, 12000), thread: 'game-progress channel' }, 'game-progress channel', 'initialization error');
        }
        recordEvent('game-progress', JSON.stringify(summarizeMessage(data)));
    }

    function observeGameLog(data) {
        if (typeof data === 'string') recordLog(data, 'game-log channel');
        else if (data && typeof data.message === 'string') recordLog(data.message, 'game-log channel');
        else if (data && Array.isArray(data.lines)) recordLog(data.lines.join('\n'), 'game-log channel');
    }

    function observeGameCrash(data) {
        if (!data || typeof data !== 'object') return;
        var heap = Number(data.heap);
        var heapMetric = heap >= 0
            ? 'game.js crash hook: HEAPU8.length / 1048576 integer-truncated (WASM linear-memory view capacity in MiB; not allocator-used bytes, committed/physical RAM or RSS)'
            : '';
        captureCrashText(data.text || data.message || '[game-crash channel did not include text]',
            safeText(data.thread || 'main', 200), 'PlayGTA5 crash text', {
                kind: safeText(data.kind || 'crash', 160),
                at: Date.now(),
                recentMessages: Array.isArray(data.tail) ? data.tail.slice(-60) : [],
                heapMiB: heap >= 0 ? heap : null,
                heapMetric: heapMetric
            });
    }

    function captureResourceFailure(data, source) {
        var details = data && data.details && typeof data.details === 'object' ? data.details : {};
        var kind = safeText(details.kind || 'unclassified-resource', 180);
        var method = safeText(details.method || 'unknown', 24);
        var status = Number(details.status) || null;
        var message = safeText(details.message || (status ? 'HTTP status ' + status : 'resource request failed'), 4000);
        var key = safeText(source || data.source || 'worker', 160) + '|' + kind + '|' + method + '|' + (status || '') + '|' + message;
        var group = resourceFailureIndex[key];
        if (!group) {
            group = {
                source: safeText(source || data.source || 'worker', 160),
                kind: kind,
                method: method,
                status: status,
                message: message,
                contentType: safeText(details.contentType || '', 240),
                contentLength: Number(details.contentLength) || null,
                durationMs: Number(details.durationMs) || null,
                firstAt: isoTime(Number(data.at) || Date.now()),
                lastAt: isoTime(Number(data.at) || Date.now()),
                count: 0
            };
            resourceFailureIndex[key] = group;
            boundedPush(state.resources.failures, group, 100);
            recordEvent('resource fetch failure', group.source + ' ' + method + ' ' + kind +
                (status ? ' HTTP ' + status : '') + ': ' + message);
        }
        group.count++;
        group.lastAt = isoTime(Number(data.at) || Date.now());
        if (Number(details.durationMs) > 0) group.durationMs = Number(details.durationMs);
        if (Number(details.contentLength) > 0) group.contentLength = Number(details.contentLength);
    }

    function copyNumberMap(value) {
        var result = {};
        if (!value || typeof value !== 'object') return result;
        Object.keys(value).slice(0, 100).forEach(function (key) {
            var number = Number(value[key]);
            if (isFinite(number)) result[safeText(key, 160)] = number;
        });
        return result;
    }

    function copyOperationSnapshot(items) {
        if (!Array.isArray(items)) return [];
        return items.slice(-40).map(function (item) {
            return {
                sequence: Number(item && item.sequence) || null,
                at: isoTime(Number(item && item.at) || Date.now()),
                lastAt: isoTime(Number(item && item.lastAt) || Number(item && item.at) || Date.now()),
                operation: safeText(item && item.operation || '', 180),
                count: Number(item && item.count) || 1,
                details: safeText(item && item.details ? JSON.stringify(item.details) : '{}', 4000)
            };
        });
    }

    function updatePipelineApiCounts(apiCalls, timestamp) {
        if (!apiCalls || typeof apiCalls !== 'object') return;
        var names = ['createRenderPipeline', 'createRenderPipelineAsync', 'createComputePipeline', 'createComputePipelineAsync'];
        names.forEach(function (name) {
            if (typeof apiCalls[name] === 'number') state.rendering.pipelineApiCalls[name] = apiCalls[name];
        });
        state.fix.syncPipelineMethodCallsObserved = state.rendering.pipelineApiCalls.createRenderPipeline + state.rendering.pipelineApiCalls.createComputePipeline;
        state.fix.asyncPipelineMethodCallsObserved = state.rendering.pipelineApiCalls.createRenderPipelineAsync + state.rendering.pipelineApiCalls.createComputePipelineAsync;
        if (ADRENO_FIX_ENABLED && state.fix.workerOptionConfirmed) {
            state.fix.status = 'GPU worker URL carried syncpipelines=1; observed sync API calls=' + state.fix.syncPipelineMethodCallsObserved +
                ', async API calls=' + state.fix.asyncPipelineMethodCallsObserved + '; option-to-effect causality is not established';
        }
        if (timestamp) state.rendering.lastGraphicsSampleAt = isoTime(timestamp);
    }

    function updateGraphicsSnapshot(details, timestamp) {
        timestamp = Number(timestamp) || Date.now();
        if (!details || typeof details !== 'object') return;
        var gpuOperations = details.gpuOperations && typeof details.gpuOperations === 'object' ? details.gpuOperations : null;
        if (gpuOperations) {
            if (gpuOperations.counts) state.rendering.gpuOperationCounts = copyNumberMap(gpuOperations.counts);
            if (typeof gpuOperations.queueSubmitCount === 'number') {
                state.rendering.queueSubmitCount = gpuOperations.queueSubmitCount;
                if (gpuOperations.queueSubmitCount > 0 && !state.rendering.firstQueueSubmitAt) {
                    state.rendering.firstQueueSubmitAt = isoTime(timestamp);
                    state.rendering.firstQueueSubmitObservedBy = 'heartbeat sample (submission occurred before sample; exact first time unavailable)';
                }
            }
            if (typeof gpuOperations.canvasConfigureCount === 'number' && gpuOperations.canvasConfigureCount > 0 && !state.rendering.firstCanvasConfigureAt) {
                state.rendering.firstCanvasConfigureAt = isoTime(timestamp);
            }
            if (typeof gpuOperations.canvasTextureCount === 'number' && gpuOperations.canvasTextureCount > 0 && !state.rendering.firstCanvasTextureAt) {
                state.rendering.firstCanvasTextureAt = isoTime(timestamp);
            }
            if (gpuOperations.canvasSize || gpuOperations.canvasConfiguration) {
                state.rendering.canvas = gpuOperations.canvasSize || (gpuOperations.canvasConfiguration && gpuOperations.canvasConfiguration.canvas) || state.rendering.canvas;
                state.rendering.canvasConfiguration = gpuOperations.canvasConfiguration || state.rendering.canvasConfiguration;
            }
            if (gpuOperations.recentOperations) state.rendering.recentGpuOperations = copyOperationSnapshot(gpuOperations.recentOperations);
        }
        if (details.pipeline && typeof details.pipeline === 'object') {
            updatePipelineApiCounts(details.pipeline.apiCalls, timestamp);
            state.fix.gpuWorkerSyncQueryObserved = details.pipeline.workerUrlSyncPipelines === true;
            state.fix.workerPipelineImplementationObserved = true;
            if (details.pipeline.recentOperations) state.rendering.recentGpuOperations = copyOperationSnapshot(details.pipeline.recentOperations);
        }
        if (details.apiCalls) {
            updatePipelineApiCounts(details.apiCalls, timestamp);
            state.fix.workerPipelineImplementationObserved = true;
        }
        if (!state.rendering.lastGraphicsSampleAt) state.rendering.lastGraphicsSampleAt = isoTime(timestamp);
    }

    function captureWasmMemory(data, type) {
        var details = data && data.details && typeof data.details === 'object' ? data.details : {};
        var at = Number(data && data.at) || Date.now();
        if (type === 'wasm-memory') {
            var sample = {
                at: isoTime(at),
                origin: safeText(details.origin || 'WebAssembly memory observation', 240),
                metric: safeText(details.metric || 'WebAssembly.Memory.buffer.byteLength capacity', 400),
                bufferBytes: Number(details.bufferBytes) || null,
                bufferMiB: Number(details.bufferMiB) || null,
                shared: typeof details.shared === 'boolean' ? details.shared : null
            };
            state.memory.latestWasmLinearMemory = sample;
            boundedPush(state.memory.wasmLinearMemorySnapshots, sample, 100);
        } else if (type === 'wasm-memory-growth') {
            boundedPush(state.memory.wasmGrowthEvents, {
                at: isoTime(at),
                deltaPages: safeText(details.deltaPages || '', 80),
                previousBufferBytes: Number(details.previousBufferBytes) || null,
                currentBufferBytes: Number(details.currentBufferBytes) || null,
                metric: safeText(details.metric || 'WebAssembly linear-memory buffer capacity', 400)
            }, 100);
        } else if (type === 'wasm-memory-growth-failure') {
            boundedPush(state.memory.wasmGrowthEvents, {
                at: isoTime(at),
                deltaPages: safeText(details.deltaPages || '', 80),
                previousBufferBytes: details.before && Number(details.before.bufferBytes) || null,
                currentBufferBytes: null,
                failure: details.error || null,
                metric: safeText(details.before && details.before.metric || 'WebAssembly linear-memory capacity', 400)
            }, 100);
            var error = details.error && typeof details.error === 'object' ? details.error : {};
            captureException({
                name: error.name || 'WebAssembly.Memory.grow failure',
                message: error.message || 'WebAssembly memory growth failed',
                stack: error.stack || '',
                thread: data.source || 'worker',
                at: at,
                recentContext: recentEventContext(24)
            }, data.source || 'worker', 'WASM memory growth failure');
        }
    }

    function observeWorkerDebug(data) {
        if (!data || typeof data !== 'object') return;
        var source = safeText(data.source || 'worker', 160);
        var type = safeText(data.type || 'event', 120);
        var details = data.details && typeof data.details === 'object' ? data.details : {};
        var at = Number(data.at) || Date.now();

        if (type === 'webgpu-error-group') {
            if (DEBUG_ENABLED) captureWebGpuErrorGroup(data);
            return;
        }
        if (type === 'pipeline-mode') {
            state.fix.gpuWorkerObserved = details.gpuWorkerCreated === true;
            state.fix.workerSyncPipelines = details.syncPipelines === true;
            state.fix.workerOptionConfirmed = state.fix.gpuWorkerObserved && state.fix.workerSyncPipelines;
            state.fix.status = !ADRENO_FIX_ENABLED
                ? 'Experimental mode not requested; observed GPU worker syncpipelines=' + state.fix.workerSyncPipelines
                : state.fix.workerOptionConfirmed
                    ? 'syncPipelines=true reached the GPU worker URL; implementation/effect confirmation pending'
                    : !state.fix.gpuWorkerObserved
                        ? 'Requested, but GPU worker creation/URL was not confirmed'
                        : 'GPU worker URL did not carry syncpipelines=1';
            if (DEBUG_ENABLED) recordEvent('graphics experiment', 'Loader observed GPU worker creation=' +
                state.fix.gpuWorkerObserved + ', syncpipelines=' + state.fix.workerSyncPipelines +
                (details.message ? ', ' + safeText(details.message, 800) : ''));
            if (!DEBUG_ENABLED) {
                render();
                return;
            }
        } else if (!DEBUG_ENABLED) {
            return;
        }

        if (type === 'loader-init') {
            var childWorkerObserved = Object.keys(workerStates).some(function (key) {
                return /gpu|i\/o|io-worker/i.test(workerStates[key].role || '');
            });
            if (!childWorkerObserved) setStage('Loader received game launch; waiting for GPU and I/O workers');
            state.fix.launchMessageObserved = true;
            state.fix.loaderReceivedSyncPipelines = details.syncPipelinesRequested === true;
            if (ADRENO_FIX_ENABLED) {
                state.fix.status = state.fix.loaderReceivedSyncPipelines
                    ? 'syncPipelines=true received by the game loader; GPU worker URL confirmation pending'
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
            captureException({
                name: details.name || 'WebAssembly error',
                message: details.message || state.wasm.lastOperation,
                stack: details.stack || '',
                thread: details.thread || source,
                at: at,
                recentContext: details.recentContext || recentEventContext(24)
            }, source, 'WASM error');
        } else if (type === 'wasm-memory' || type === 'wasm-memory-growth' || type === 'wasm-memory-growth-failure') {
            captureWasmMemory(data, type);
        } else if (type === 'worker-heartbeat' && source === 'gpu-worker') {
            state.gpu.gpuWorkerHeartbeatAt = at;
            state.gpu.gpuWorkerHeartbeatGapMs = Number(details.gapMs) || 0;
            updateGraphicsSnapshot(details, at);
            if (Array.isArray(details.resourceGroups)) state.resources.groups = details.resourceGroups.slice(0, 40);
        } else if (type === 'gpu-context') {
            state.rendering.firstGpuContextAt = state.rendering.firstGpuContextAt || isoTime(at);
            state.gpu.operationObserverStatus = details.available === false ? 'GPU canvas context unavailable' : 'GPU canvas context observed';
            if (details.canvas) state.rendering.canvas = details.canvas;
            recordEvent('GPU context', details.message || (details.contextType || 'GPU') + ' context observed');
        } else if (type === 'gpu-adapter-observed') {
            state.gpu.workerAdapterDetails = safeText(JSON.stringify(details.information || {}), 2000);
            state.gpu.features = safeText(JSON.stringify(details.features || []), 2000);
            state.gpu.limits = safeText(JSON.stringify(details.limits || {}), 4000);
            recordEvent('GPU worker adapter', state.gpu.workerAdapterDetails || 'Adapter observed; vendor information not exposed');
        } else if (type === 'gpu-device-observed') {
            state.gpu.workerDevice = 'GPUDevice observed; uncapturederror listener installed=' + !!details.uncapturederrorListenerInstalled;
            state.gpu.operationObserverStatus = safeText(state.gpu.operationObserverStatus ||
                ('GPU API hooks installed=' + (details.installedApiHooks || 'not reported')), 500);
        } else if (type === 'worker-bootstrap' && source === 'gpu-worker') {
            state.fix.gpuWorkerStarted = true;
            state.fix.gpuWorkerSyncQueryObserved = details.workerUrlSyncPipelines === true;
        } else if (type === 'gpu-worker-config') {
            state.fix.gpuWorkerStarted = true;
            state.gpu.gpuWorkerConfig = details;
            state.fix.gpuWorkerSyncQueryObserved = details.workerUrlSyncPipelines === true;
            state.gpu.operationObserverStatus = safeText('GPU API hooks=' + (Number(details.installedApiHooks) || 0) +
                '; sync pipeline available=' + !!details.createRenderPipelineAvailable +
                '; async pipeline available=' + !!details.createRenderPipelineAsyncAvailable, 500);
        } else if (type === 'gpu-worker-init') {
            state.fix.gpuWorkerStarted = true;
            state.gpu.workerInit = {
                at: isoTime(at),
                hasMemory: !!details.hasMemory,
                memoryMetric: safeText(details.memoryMetric || '', 400),
                memoryBufferBytes: Number(details.memoryBufferBytes) || null,
                memoryBufferMiB: Number(details.memoryBufferMiB) || null,
                sharedMemory: typeof details.sharedMemory === 'boolean' ? details.sharedMemory : null,
                gpuCommandRingBytes: Number(details.ringSizeBytes) || null,
                diagnosticNote: safeText(details.diagnosticNote || '', 300)
            };
        } else if (type === 'gpu-device-lost') {
            state.gpu.deviceLost = {
                at: isoTime(at),
                reason: safeText(details.reason || 'unknown', 120),
                message: safeText(details.message || '', 8000),
                deviceLabel: safeText(details.deviceLabel || '', 200),
                recentOperations: copyOperationSnapshot(details.recentOperations)
            };
            state.gpu.lastGpuError = 'GPUDevice lost: ' + state.gpu.deviceLost.reason +
                (state.gpu.deviceLost.message ? ' — ' + state.gpu.deviceLost.message : '');
            recordEvent('GPU device lost', state.gpu.lastGpuError);
        } else if (type === 'canvas-configure') {
            state.rendering.firstCanvasConfigureAt = state.rendering.firstCanvasConfigureAt || isoTime(at);
            state.rendering.canvas = details.canvas || state.rendering.canvas;
            state.rendering.canvasConfiguration = {
                at: isoTime(at), count: Number(details.count) || null, format: safeText(details.format || '', 100),
                alphaMode: safeText(details.alphaMode || '', 80), colorSpace: safeText(details.colorSpace || '', 80),
                usage: Number(details.usage) || null, canvas: details.canvas || null
            };
        } else if (type === 'canvas-texture-first') {
            state.rendering.firstCanvasTextureAt = state.rendering.firstCanvasTextureAt || isoTime(at);
            state.rendering.canvas = details.canvas || state.rendering.canvas;
            state.rendering.firstCanvasTexture = { at: isoTime(at), canvas: details.canvas || null, texture: details.texture || null };
        } else if (type === 'gpu-queue-submit-first') {
            state.rendering.firstQueueSubmitAt = state.rendering.firstQueueSubmitAt || isoTime(at);
            state.rendering.firstQueueSubmitObservedBy = 'GPUQueue.submit hook';
            state.rendering.queueSubmitCount = Math.max(1, state.rendering.queueSubmitCount);
            state.rendering.firstQueueSubmit = { at: isoTime(at), commandBufferCount: Number(details.commandBufferCount) || null };
            recordEvent('render milestone', 'First GPUQueue.submit observed; submission validity/presentation are not proven');
        } else if (type === 'pipeline-implementation') {
            state.fix.gpuWorkerStarted = true;
            state.fix.workerPipelineImplementationObserved = true;
            updatePipelineApiCounts(details.apiCalls, at);
            state.fix.gpuWorkerSyncQueryObserved = details.workerUrlSyncPipelines === true;
            state.rendering.recentGpuOperations = copyOperationSnapshot(details.recentOperations);
        } else if (type === 'audio-capability') {
            boundedPush(state.audio.capabilities, {
                at: isoTime(at), source: source, environment: safeText(details.environment || source, 120),
                audioContextAvailable: !!details.audioContextAvailable,
                audioWorkletNodeAvailable: !!details.audioWorkletNodeAvailable,
                mediaElementAvailable: !!details.mediaElementAvailable,
                autoplayUserActivationAvailable: !!details.autoplayUserActivationAvailable,
                note: safeText(details.note || '', 500)
            }, 20);
            recordEvent('audio capability', source + ': AudioContext ' + (!!details.audioContextAvailable ? 'available' : 'not available in this worker'));
        } else if (type === 'audio-context') {
            var contextId = safeText(details.contextId || 'unknown-worker-audio-context', 120);
            var audioRecord = null;
            for (var audioIndex = 0; audioIndex < state.audio.contexts.length; audioIndex++) {
                if (state.audio.contexts[audioIndex].source === source && state.audio.contexts[audioIndex].id === contextId) {
                    audioRecord = state.audio.contexts[audioIndex];
                    break;
                }
            }
            if (!audioRecord) {
                audioRecord = { id: contextId, source: source, constructor: safeText(details.constructor || 'AudioContext', 120), createdAt: details.createdAt || isoTime(at), resumeAttempts: 0, stateChanges: 0 };
                boundedPush(state.audio.contexts, audioRecord, 30);
            } else {
                audioRecord.stateChanges++;
            }
            audioRecord.state = safeText(details.state || 'unknown', 80);
            audioRecord.sampleRate = Number(details.sampleRate) || null;
            audioRecord.baseLatency = Number(details.baseLatency) || null;
            audioRecord.outputLatency = Number(details.outputLatency) || null;
            audioRecord.destinationMaxChannelCount = Number(details.destinationMaxChannelCount) || null;
            audioRecord.lastObservedAt = isoTime(at);
        } else if (type === 'audio-playback-attempt') {
            var audioAttempt = {
                at: isoTime(at), source: source,
                contextId: safeText(details.contextId || '', 120),
                kind: safeText(details.operation || 'audio operation', 160),
                outcome: safeText(details.outcome || 'observed', 120),
                error: safeText(details.message || '', 2000),
                userActivation: 'not exposed to worker'
            };
            boundedPush(state.audio.playAttempts, audioAttempt, 100);
            recordEvent('audio playback attempt', audioAttempt.kind + ' ' + audioAttempt.outcome + (audioAttempt.error ? ': ' + audioAttempt.error : ''));
        } else if (type === 'audio-worklet-module') {
            var workletEntry = {
                at: isoTime(at), source: source,
                contextId: safeText(details.contextId || '', 120),
                module: safeText(details.module || '', 500),
                status: safeText(details.status || 'observed', 80),
                error: safeText(details.message || '', 2000)
            };
            boundedPush(state.audio.workletModules, workletEntry, 50);
            recordEvent('audio worklet module', workletEntry.status + ' ' + workletEntry.module + (workletEntry.error ? ': ' + workletEntry.error : ''));
        } else if (type === 'worker-created' && (details.role === 'gpu-worker' || details.role === 'io-worker')) {
            setStage('Starting ' + (details.role === 'gpu-worker' ? 'GPU' : 'I/O') + ' worker');
            workerStates[data.id || source + '-' + details.role] = {
                id: safeText(data.id || source + '-' + details.role),
                role: details.role === 'gpu-worker' ? 'GPU worker' : 'I/O worker',
                url: safeText(details.url || details.role, 500),
                state: 'created',
                createdAt: isoTime(at),
                lastMessageAt: null,
                lastMessage: ''
            };
            if (details.role === 'gpu-worker') {
                state.fix.gpuWorkerObserved = true;
                state.fix.workerSyncPipelines = details.syncPipelines === true;
                state.fix.workerOptionConfirmed = state.fix.workerSyncPipelines;
                if (ADRENO_FIX_ENABLED) {
                    state.fix.status = state.fix.workerOptionConfirmed
                        ? 'GPU worker URL carried syncpipelines=1; observing pipeline API calls'
                        : 'GPU worker URL did not carry syncpipelines=1';
                }
            }
        } else if (type === 'worker-state' && data.id) {
            var existing = workerStates[data.id] || { id: safeText(data.id), role: safeText(details.role || 'worker') };
            existing.state = safeText(details.state || existing.state || 'observed');
            existing.lastMessageAt = isoTime(at);
            existing.lastMessage = safeText(details.message || '', 4000);
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
        } else if (type === 'resource-fetch-failure') {
            captureResourceFailure(data, source);
        } else if (type === 'worker-error' || type === 'worker-message-error' || type === 'unhandled-rejection' ||
                type === 'exception' || type === 'wasm-error') {
            var errorDetails = details.error && typeof details.error === 'object' ? details.error : {};
            captureException({
                name: details.name || errorDetails.name || (type === 'unhandled-rejection' ? 'UnhandledRejection' : 'Error'),
                message: details.message || errorDetails.message || details.reason || 'worker error',
                stack: details.stack || errorDetails.stack || '',
                originalText: details.originalText || details.message || '',
                thread: details.thread || details.role || source,
                at: at,
                resource: details.resource || details.filename || '',
                line: details.line || details.lineno,
                column: details.column || details.colno,
                recentContext: details.recentContext || details.recentParentContext || recentEventContext(24),
                recentMessages: details.recentMessages || [],
                recentGpuOperations: details.recentGpuOperations || null
            }, source, type);
        } else if (type === 'console') {
            recordLog(details.message || '', source + ' console.' + safeText(details.method || 'log', 40));
        } else if (type === 'worker-heartbeat-gap') {
            recordEvent('worker heartbeat', source + ': delayed heartbeat (' + (Number(details.gapMs) || 0) + ' ms)');
        } else if (type === 'worker-heartbeat') {
            var heartbeatState = workerStates[source] || { id: source, role: source, state: 'running' };
            heartbeatState.lastMessageAt = isoTime(at);
            heartbeatState.heartbeatGapMs = Number(details.gapMs) || 0;
            workerStates[source] = heartbeatState;
            if (details.gpuOperations || details.pipeline) updateGraphicsSnapshot(details, at);
        } else if (type === 'gpu-observer-status' || type === 'worker-instrumentation') {
            state.gpu.operationObserverStatus = safeText(details.message || type, 1000);
            recordEvent('worker instrumentation', source + ': ' + state.gpu.operationObserverStatus);
        } else if (type === 'pipeline-mode') {
            // Already recorded above; this is reached only with full diagnostics enabled.
        } else {
            var summary = {};
            Object.keys(details).slice(0, 24).forEach(function (key) {
                var value = details[key];
                if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') summary[key] = safeText(value, 1000);
                else if (value != null) summary[key] = safeText(JSON.stringify(value), 2000);
            });
            recordEvent(source + ' ' + type, JSON.stringify(summary));
        }
        if (/adapter|device ready/i.test(JSON.stringify(details))) {
            state.gpu.workerDevice = safeText(JSON.stringify(details), 4000);
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
                    var kind = safeText(event.target.tagName || 'resource', 100).toLowerCase();
                    captureResourceFailure({ at: Date.now(), details: {
                        kind: kind + ' resource load', method: 'load',
                        message: resource ? 'resource element failed to load: ' + safeUrl(resource) : 'resource element failed to load'
                    } }, 'page');
                    return;
                }
                var original = event && event.error;
                captureException({
                    name: original && original.name || 'PageError',
                    message: original && original.message || event && event.message || 'uncaught page error',
                    stack: original && original.stack || '',
                    originalText: event && event.message || '',
                    thread: 'page',
                    resource: safeUrl(event && event.filename || ''),
                    line: Number(event && event.lineno) || null,
                    column: Number(event && event.colno) || null,
                    at: Date.now(),
                    recentContext: recentEventContext(24)
                }, 'page', 'uncaught page error');
            }, true);
            window.addEventListener('unhandledrejection', function (event) {
                var reason = event && event.reason;
                captureException({
                    name: reason && reason.name || 'UnhandledRejection',
                    message: reason && reason.message || safeText(reason || 'unhandled promise rejection', 8000),
                    stack: reason && reason.stack || '',
                    originalText: reason && reason.stack || safeText(reason || '', 12000),
                    thread: 'page',
                    at: Date.now(),
                    recentContext: recentEventContext(24)
                }, 'page', 'unhandled promise rejection');
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
        var recentEvents = events.slice(-100).map(function (event) {
            return {
                sequence: event.sequence,
                at: event.at,
                firstAt: event.firstAt || event.at,
                lastAt: event.lastAt || event.at,
                category: event.category,
                message: event.message,
                count: event.repeat || 1
            };
        });
        var workers = Object.keys(workerStates).map(function (key) {
            var worker = workerStates[key];
            return {
                id: safeText(worker.id, 160),
                role: safeText(worker.role, 160),
                url: safeText(worker.url || '', 500),
                state: safeText(worker.state || 'observed', 160),
                createdAt: safeText(worker.createdAt || '', 80),
                lastMessageAt: safeText(worker.lastMessageAt || '', 80),
                lastMessage: safeText(worker.lastMessage || '', 4000),
                heartbeatGapMs: Number(worker.heartbeatGapMs) || null
            };
        });
        var now = Date.now();
        var reportRendering = {
            firstWorkerFrameSignalAt: state.rendering.firstWorkerFrameSignalAt,
            readySignalAt: state.rendering.readySignalAt,
            worldReadySignalAt: state.rendering.worldReadySignalAt,
            firstGpuContextAt: state.rendering.firstGpuContextAt,
            firstCanvasConfigureAt: state.rendering.firstCanvasConfigureAt,
            firstCanvasTextureAt: state.rendering.firstCanvasTextureAt,
            firstQueueSubmitAt: state.rendering.firstQueueSubmitAt,
            firstQueueSubmitObservedBy: state.rendering.firstQueueSubmitObservedBy || '',
            firstQueueSubmit: state.rendering.firstQueueSubmit || null,
            queueSubmitCount: state.rendering.queueSubmitCount,
            canvas: state.rendering.canvas,
            canvasConfiguration: state.rendering.canvasConfiguration,
            firstCanvasTexture: state.rendering.firstCanvasTexture || null,
            gpuOperationCounts: state.rendering.gpuOperationCounts,
            recentGpuOperations: state.rendering.recentGpuOperations,
            pipelineApiCalls: state.rendering.pipelineApiCalls,
            lastGraphicsSampleAt: state.rendering.lastGraphicsSampleAt,
            sceneHints: state.rendering.sceneHints.slice(-100),
            displayPresentationObserved: state.rendering.displayPresentationObserved,
            note: state.rendering.note
        };
        return {
            schema: 'NewCatroid local game diagnostics v2',
            capturedAt: isoTime(now),
            page: safeUrl(window.location.href),
            privacy: 'Collected in this WebView only over local BroadcastChannel messages. Exported URLs omit query values; credential-like fields and local paths are redacted. No diagnostic HTTP telemetry is sent.',
            optIns: { newcatroid_debug: DEBUG_ENABLED, newcatroid_adreno_fix: ADRENO_FIX_ENABLED },
            initialization: {
                stage: safeText(state.stage, 500),
                stageChangedAt: isoTime(state.stageChangedAt),
                displayedPercent: state.progress,
                lastEngineProgressTarget: state.targetProgress,
                progressLabel: safeText(state.progressLabel, 500),
                lastProgressChangeAt: isoTime(state.progressChangedAt),
                secondsSinceProgressChange: Math.floor((now - state.progressChangedAt) / 1000),
                lastProgressEventAt: isoTime(state.lastProgressEventAt),
                lastGameMessageAt: isoTime(state.lastGameMessageAt),
                gameWorldReadySignal: state.gameWorldReady,
                documentReadyState: safeText(state.documentReadyState, 80),
                documentVisibilityState: safeText(state.documentVisibilityState, 80)
            },
            mainPageHeartbeat: state.mainHeartbeat,
            webAssembly: state.wasm,
            rendering: reportRendering,
            viewport: state.viewport,
            graphics: {
                webGpuApi: state.gpu.webGpuApi,
                webGlApi: state.gpu.webGlApi,
                webGl2Api: state.gpu.webGl2Api,
                pageAdapterProbe: state.gpu.adapter,
                adapterInformation: safeText(state.gpu.adapterDetails, 2000),
                adapterFeatures: safeText(state.gpu.adapterFeatures, 4000),
                adapterLimits: safeText(state.gpu.adapterLimits, 4000),
                gpuWorkerAdapterDetails: safeText(state.gpu.workerAdapterDetails, 4000),
                gpuWorkerDeviceLog: safeText(state.gpu.workerDevice, 4000),
                gpuWorkerFeaturesLog: safeText(state.gpu.features, 4000),
                gpuLimitsLog: safeText(state.gpu.limits, 8000),
                gpuWorkerConfig: state.gpu.gpuWorkerConfig,
                gpuWorkerInit: state.gpu.workerInit || null,
                operationObserverStatus: safeText(state.gpu.operationObserverStatus, 1000),
                pendingPipelineReport: state.gpu.pendingPipelines,
                totalErrorOccurrences: state.gpu.errorOccurrences,
                overflowErrorOccurrences: state.gpu.overflowErrorOccurrences,
                errorGroups: state.gpu.errorGroups,
                lastGpuError: safeText(state.gpu.lastGpuError, 12000),
                lastShaderOrPipelineError: safeText(state.gpu.lastShaderError, 12000),
                deviceLost: state.gpu.deviceLost,
                gpuWorkerLastHeartbeatAt: isoTime(state.gpu.gpuWorkerHeartbeatAt),
                gpuWorkerHeartbeatGapMs: state.gpu.gpuWorkerHeartbeatGapMs,
                errorScopePolicy: 'Observes uncapturederror and popErrorScope results already used by the game. Diagnostics do not push error scopes.'
            },
            experimentalGraphicsMode: {
                requested: state.fix.requested,
                pageLaunchWorkerObserved: state.fix.launchMessageObserved,
                syncPipelinesSetOnLaunchMessage: state.fix.appliedToLaunchMessage,
                loaderReceivedSyncPipelines: state.fix.loaderReceivedSyncPipelines,
                gpuWorkerObserved: state.fix.gpuWorkerObserved,
                syncPipelinesOnGpuWorkerUrl: state.fix.workerSyncPipelines,
                gpuWorkerStarted: state.fix.gpuWorkerStarted,
                gpuWorkerSyncQueryObserved: state.fix.gpuWorkerSyncQueryObserved,
                syncPipelineApiCallsObserved: state.fix.syncPipelineMethodCallsObserved,
                asyncPipelineApiCallsObserved: state.fix.asyncPipelineMethodCallsObserved,
                pipelineImplementationObserved: state.fix.workerPipelineImplementationObserved,
                behaviorConsistentWithRequest: state.fix.behaviorConsistentWithRequest,
                status: safeText(state.fix.status, 1200),
                interpretation: 'URL/launch receipt and observed API calls are separate evidence; API calls do not by themselves prove that the option caused a particular implementation path or rendering outcome.'
            },
            audio: state.audio,
            memory: state.memory,
            resources: state.resources,
            exceptions: state.exceptions,
            workers: workers,
            recentEvents: recentEvents,
            interpretationNotes: [
                'Ready/100% and gameWorldReadySignal are engine messages, not proof of a visible non-black frame.',
                'GPUQueue.submit, canvas configuration, and texture acquisition are separate observations; no pixel readback or compositor presentation is inferred.',
                'WASM linear-memory capacity, engine allocator counters, JS heap estimates, and process/physical memory are separate metrics.',
                'Audio API state and play attempts do not prove audible output; hardware route and actual sound are not measured.',
                state.resources.note,
                'Viewport measurements are observations only; no orientation or resolution override is applied.'
            ]
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
        var canvas = state.rendering.canvas;
        var canvasText = canvas ? (Number(canvas.width) || Number(canvas.bitmapWidth) || '?') + 'x' +
            (Number(canvas.height) || Number(canvas.bitmapHeight) || '?') : 'not observed';
        var latestMemory = state.memory.latestWasmLinearMemory;
        var latestAudio = state.audio.contexts.length ? state.audio.contexts[state.audio.contexts.length - 1] : null;
        var lines = [
            'Stage: ' + safeText(state.stage),
            'Progress: ' + displayed + ' · latest target ' + target,
            'World-ready signal: ' + state.gameWorldReady + ' · visibility: ' + safeText(state.documentVisibilityState),
            'Workers: ' + Object.keys(workerStates).length + ' observed · WASM: ' + state.wasm.state,
            'WebGPU errors: ' + state.gpu.errorOccurrences + ' occurrences / ' + state.gpu.errorGroups.length + ' retained groups',
            'Render evidence: canvas ' + canvasText + ' · submits ' + state.rendering.queueSubmitCount +
                ' · first submit ' + (state.rendering.firstQueueSubmitAt || 'not observed') +
                ' · display presentation ' + (state.rendering.displayPresentationObserved ? 'observed' : 'not observable'),
            'Viewport: ' + (state.viewport.current ? state.viewport.current.innerWidth + 'x' + state.viewport.current.innerHeight +
                ' CSS px · DPR ' + state.viewport.current.devicePixelRatio : 'not observed'),
            'GPU: WebGPU ' + state.gpu.webGpuApi + ' · adapter ' + state.gpu.adapter +
                (state.gpu.adapterDetails ? ' (' + safeText(state.gpu.adapterDetails) + ')' : ''),
            'Audio: ' + state.audio.contexts.length + ' AudioContext(s)' +
                (latestAudio ? ' · last state ' + latestAudio.state + ' · resume ' + latestAudio.lastResumeResult : ' · no context observed'),
            'WASM memory capacity: ' + (latestMemory ? latestMemory.bufferMiB + ' MiB (' + latestMemory.metric + ')' : 'not observed'),
            'Graphics experiment: ' + safeText(state.fix.status),
            'Note: engine Ready/100%, submit, and AudioContext state do not prove visible pixels or audible sound.'
        ];
        return lines.join('\n');
    }

    function renderDiagnosticDetails() {
        var lines = [];
        lines.push('WebGPU validation / uncaptured errors: ' + state.gpu.errorOccurrences + ' total occurrences; ' + state.gpu.errorGroups.length + ' groups retained.');
        state.gpu.errorGroups.slice(-5).reverse().forEach(function (group) {
            lines.push('[' + group.firstAt + ' .. ' + group.lastAt + '] x' + group.count + ' ' + group.type + ' (' + group.sourceKind + ')');
            lines.push(safeText(group.message, 1600));
            var context = group.latestContext || group.firstContext || [];
            if (context.length) lines.push('Latest GPU operations: ' + safeText(JSON.stringify(context.slice(-5)), 2400));
        });
        if (!state.gpu.errorGroups.length) lines.push('No WebGPU error event has been captured in this run.');

        lines.push('Exceptions / crash reports: ' + state.exceptions.length + ' groups retained.');
        state.exceptions.slice(-4).reverse().forEach(function (exception) {
            lines.push('[' + exception.firstAt + ' .. ' + exception.lastAt + '] x' + exception.count + ' ' + exception.thread + ' ' + exception.name + ': ' + safeText(exception.message, 1200));
            if (exception.stack) lines.push('Stack: ' + safeText(exception.stack, 1400));
            if (exception.recentMessages && exception.recentMessages.length) lines.push('Recent engine log tail: ' + safeText(exception.recentMessages.slice(-8).join('\n'), 1600));
        });

        lines.push('Viewport / engine resize: ' + (state.viewport.current ? JSON.stringify({
            at: state.viewport.current.at, innerWidth: state.viewport.current.innerWidth,
            innerHeight: state.viewport.current.innerHeight, devicePixelRatio: state.viewport.current.devicePixelRatio,
            visualViewport: state.viewport.current.visualViewport, canvases: state.viewport.current.canvases
        }) : 'not observed'));
        state.viewport.engineResizeMessages.slice(-4).forEach(function (entry) {
            lines.push('Engine resize [' + entry.at + ']: ' + entry.message);
        });
        lines.push('Canvas/GPU milestones: configured=' + (state.rendering.firstCanvasConfigureAt || 'no') +
            ', texture=' + (state.rendering.firstCanvasTextureAt || 'no') +
            ', queue submit=' + (state.rendering.firstQueueSubmitAt || 'no') +
            ', canvas details=' + safeText(JSON.stringify(state.rendering.canvasConfiguration || state.rendering.canvas || {}), 1500));

        lines.push('Audio observations: contexts=' + state.audio.contexts.length + ', worklet modules=' + state.audio.workletModules.length +
            ', nodes=' + state.audio.nodes.length + ', play attempts=' + state.audio.playAttempts.length +
            ', last gesture=' + (state.audio.userGestureAt || 'not observed'));
        state.audio.contexts.slice(-3).forEach(function (context) {
            lines.push(context.id + ' ' + context.constructor + ': state=' + context.state + ', resume=' + context.lastResumeResult +
                ', attempts=' + context.resumeAttempts + ', sampleRate=' + context.sampleRate + ', channels=' + context.destinationMaxChannelCount);
        });
        state.audio.playAttempts.slice(-4).forEach(function (attempt) {
            lines.push(attempt.at + ' ' + attempt.kind + ' ' + attempt.outcome + (attempt.error ? ': ' + attempt.error : '') +
                ', userActivation=' + JSON.stringify(attempt.userActivation));
        });

        lines.push('Memory observations: linear-memory samples=' + state.memory.wasmLinearMemorySnapshots.length +
            ', growth events=' + state.memory.wasmGrowthEvents.length + ', engine allocator/memory messages=' + state.memory.engineMemoryMessages.length +
            ', crash heap samples=' + state.memory.crashHeapSamples.length + '. RSS/physical memory is not reported by these APIs.');
        state.resources.failures.slice(-4).reverse().forEach(function (failure) {
            lines.push('Resource failure [' + failure.firstAt + ' .. ' + failure.lastAt + '] x' + failure.count + ' ' +
                failure.source + ' ' + failure.kind + ' status=' + failure.status + ': ' + safeText(failure.message, 1000));
        });
        return lines.join('\n');
    }

    function renderEvents() {
        var recent = events.slice(-8).reverse();
        if (!recent.length) return 'Waiting for relevant errors, worker state, shader/GPU messages, and heartbeats.';
        return recent.map(function (event) {
            var repeat = event.repeat > 1 ? ' (x' + event.repeat + ', last ' + new Date(event.lastAtMs).toLocaleTimeString() + ')' : '';
            return '[' + new Date(event.atMs).toLocaleTimeString() + '] #' + event.sequence + ' ' + event.category + ': ' + event.message + repeat;
        }).join('\n');
    }

    function render() {
        if (!ui) return;
        try {
            ui.summary.textContent = renderSummary();
            if (ui.diagnosticDetails) ui.diagnosticDetails.textContent = renderDiagnosticDetails();
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
            details { margin: 5px 0; border-top: 1px solid #50617a; }\
            details > summary { min-height: 40px; padding: 9px 4px; cursor: pointer; font-weight: 600; overflow-wrap: anywhere; }\
            .diagnostic-details { max-height: 30vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }\
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
        var detailsElement = document.createElement('details');
        var detailsSummary = document.createElement('summary');
        detailsSummary.textContent = 'GPU errors, exceptions, viewport, audio and memory details';
        var diagnosticDetails = document.createElement('pre');
        diagnosticDetails.className = 'diagnostic-details';
        detailsElement.appendChild(detailsSummary);
        detailsElement.appendChild(diagnosticDetails);
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
        content.appendChild(detailsElement);
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

        ui = { host: host, panel: panel, summary: summary, diagnosticDetails: diagnosticDetails,
            workers: workers, events: eventsElement, report: report, exportStatus: exportStatus, fixBadge: fixBadge };
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

    function samplePageMemory() {
        var now = Date.now();
        if (now - lastPageMemorySampleAt < 5000) return;
        lastPageMemorySampleAt = now;
        try {
            var memory = window.performance && window.performance.memory;
            if (!memory) return;
            state.memory.pageJsHeap = {
                at: isoTime(now),
                metric: 'Chromium performance.memory estimate for page JavaScript heap; not WASM allocator usage or process RSS',
                usedBytes: Number(memory.usedJSHeapSize) || null,
                totalBytes: Number(memory.totalJSHeapSize) || null,
                limitBytes: Number(memory.jsHeapSizeLimit) || null
            };
        } catch (ignored) { }
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
            sampleViewport(false);
            samplePageMemory();
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
        installViewportObservers();
        installAudioObservers();
        listenChannel('game-progress', observeGameProgress);
        listenChannel('game-log', observeGameLog);
        listenChannel('game-crash', observeGameCrash);
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
