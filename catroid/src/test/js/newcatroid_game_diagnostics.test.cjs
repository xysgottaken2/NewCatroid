'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/res/raw/newcatroid_game_diagnostics.js'),
    'utf8'
);

class FakeElement {
    constructor(tagName) {
        this.tagName = String(tagName || 'div').toUpperCase();
        this.children = [];
        this.listeners = Object.create(null);
        this.attributes = Object.create(null);
        this.style = {};
        this.className = '';
        this.classList = {
            toggle: (name) => {
                const values = new Set(this.className.split(/\s+/).filter(Boolean));
                if (values.has(name)) values.delete(name); else values.add(name);
                this.className = Array.from(values).join(' ');
                return values.has(name);
            }
        };
        this.textContent = '';
        this.value = '';
        this.hidden = false;
        this.selected = false;
        this.focused = false;
        this.readOnly = false;
        this._shadowRoot = null;
    }

    appendChild(child) {
        this.children.push(child);
        child.parentNode = this;
        return child;
    }

    addEventListener(type, listener) {
        (this.listeners[type] || (this.listeners[type] = [])).push(listener);
    }

    setAttribute(name, value) { this.attributes[name] = String(value); }
    attachShadow() { this._shadowRoot = new FakeElement('shadow-root'); return this._shadowRoot; }
    focus() { this.focused = true; }
    select() { this.selected = true; }
    click() { for (const listener of this.listeners.click || []) listener({ target: this }); }
}

class FakeWorker {
    constructor(url, options) {
        this.url = String(url);
        this.options = options;
        this.listeners = Object.create(null);
        this.sent = [];
    }

    addEventListener(type, listener) {
        (this.listeners[type] || (this.listeners[type] = [])).push(listener);
    }

    postMessage(message, transfer) { this.sent.push({ message, transfer, receiver: this }); }
    emit(type, data) {
        for (const listener of this.listeners[type] || []) listener(data);
    }
}

class FakeBroadcastChannel {
    static instances = [];
    constructor(name) {
        this.name = name;
        this.listeners = Object.create(null);
        FakeBroadcastChannel.instances.push(this);
    }
    addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
    postMessage(data) { this.lastPosted = data; }
    emit(data) { for (const listener of this.listeners.message || []) listener({ data }); }
    close() { this.closed = true; }
}

function findElement(root, predicate) {
    if (!root) return null;
    if (predicate(root)) return root;
    const found = findElement(root._shadowRoot, predicate);
    if (found) return found;
    for (const child of root.children || []) {
        const match = findElement(child, predicate);
        if (match) return match;
    }
    return null;
}

function createPage(search = '', overrides = {}) {
    FakeBroadcastChannel.instances = [];
    const elements = Object.create(null);
    elements.pct = new FakeElement('span');
    elements.label = new FakeElement('span');
    const html = new FakeElement('html');
    const document = {
        readyState: 'complete',
        documentElement: html,
        createElement: (tag) => new FakeElement(tag),
        getElementById: (id) => elements[id] || null,
        addEventListener: (type, listener) => {
            (document.listeners[type] || (document.listeners[type] = [])).push(listener);
        },
        listeners: Object.create(null)
    };
    const windowListeners = Object.create(null);
    const origin = 'http://local.test:8080';
    const window = Object.assign({
        location: { href: origin + '/index.html' + search, search, origin },
        document,
        Worker: FakeWorker,
        console: {
            error() {}, warn() {}, log() {}
        },
        performance: { now: () => 100 },
        addEventListener(type, listener) { (windowListeners[type] || (windowListeners[type] = [])).push(listener); },
        dispatch(type, event) { for (const listener of windowListeners[type] || []) listener(event || {}); }
    }, overrides.window || {});
    const navigator = Object.assign({ maxTouchPoints: 0 }, overrides.navigator || {});
    let timerId = 0;
    let fetchCalls = 0;
    const intervals = [];
    const context = {
        window,
        document,
        navigator,
        URL,
        URLSearchParams,
        BroadcastChannel: FakeBroadcastChannel,
        WebGLRenderingContext: undefined,
        WebGL2RenderingContext: undefined,
        console: window.console,
        performance: window.performance,
        setInterval: (callback) => { intervals.push(callback); return ++timerId; },
        setTimeout: () => ++timerId,
        clearTimeout() {},
        fetch() { fetchCalls++; return Promise.reject(new Error('fetch should not be called')); },
        XMLHttpRequest: function () { throw new Error('XHR should not be created'); },
        isFinite,
        Date,
        Math,
        Promise,
        Reflect,
        Proxy,
        Object,
        Array,
        String,
        Number,
        RegExp,
        JSON,
        Error,
        Atomics,
        SharedArrayBuffer,
        WebAssembly,
        parseInt,
        parseFloat
    };
    vm.runInNewContext(source, context, { filename: 'newcatroid_game_diagnostics.js' });
    return {
        context,
        window,
        document,
        elements,
        fetchCalls: () => fetchCalls,
        runIntervals: () => intervals.forEach((callback) => callback()),
        find: (predicate) => findElement(html, predicate),
        getChannels: () => FakeBroadcastChannel.instances
    };
}

test('no new opt-in parameter leaves page and Worker behavior unchanged (touch parameter is isolated)', () => {
    const page = createPage('?newcatroid_touch=1');
    assert.equal(page.window.__newcatroidGameDiagnostics, undefined);
    assert.equal(page.window.Worker, FakeWorker);
    assert.equal(page.document.documentElement.children.length, 0);
    assert.equal(page.getChannels().length, 0);
    assert.equal(page.fetchCalls(), 0);
});

test('only exact independent opt-in values enable their own modes', () => {
    const disabled = createPage('?newcatroid_debug=0&newcatroid_adreno_fix=true');
    assert.equal(disabled.window.__newcatroidGameDiagnostics, undefined);

    const debugOnly = createPage('?newcatroid_debug=1');
    assert.deepEqual(JSON.parse(JSON.stringify(debugOnly.window.__newcatroidGameDiagnostics.flags)), {
        debug: true,
        adrenoFix: false,
        alphaFix: false
    });
    const debugWorker = new debugOnly.window.Worker('/loader.js');
    const originalMessage = { canvas: { transfer: true }, args: ['-wgpu'], syncPipelines: false, remoteLog: false };
    const transfer = [{ transferable: true }];
    debugWorker.postMessage(originalMessage, transfer);
    assert.equal(debugWorker.url, 'http://local.test:8080/loader.js?newcatroid_worker_debug=1');
    assert.equal(debugWorker.sent[0].message.syncPipelines, false);
    assert.equal(debugWorker.sent[0].message.remoteLog, false);
    assert.equal(debugWorker.sent[0].message.fullConsole, undefined, 'diagnostics do not enable full-console logging');
    assert.equal(debugWorker.sent[0].transfer, transfer);
    assert.equal(originalMessage.syncPipelines, false, 'the caller-owned launch message is not mutated');

    let fixOnlyAdapterProbeCount = 0;
    const fixOnly = createPage('?newcatroid_adreno_fix=1', {
        navigator: { gpu: { requestAdapter() { fixOnlyAdapterProbeCount++; return Promise.resolve(null); } } }
    });
    assert.equal(fixOnly.window.__newcatroidGameDiagnostics, undefined, 'fix-only mode does not publish the diagnostic API');
    const fixWorker = new fixOnly.window.Worker('/loader.js');
    assert.equal(fixWorker.url, 'http://local.test:8080/loader.js?newcatroid_pipeline_probe=1');
    const fixMessage = { canvas: { transfer: true }, args: ['-wgpu'], remoteLog: false };
    fixWorker.postMessage(fixMessage);
    assert.equal(fixWorker.sent[0].message.syncPipelines, true);
    assert.equal(fixWorker.sent[0].message.remoteLog, false, 'the experimental mode does not enable remote logging');
    assert.equal(fixMessage.syncPipelines, undefined, 'the caller-owned launch message is not mutated');
    assert.equal(fixOnlyAdapterProbeCount, 0, 'fix-only mode does not run the diagnostic GPU probe');
    assert.deepEqual(fixOnly.getChannels().map((channel) => channel.name), ['newcatroid-debug']);
    assert.equal(fixOnly.document.documentElement.children.length, 1, 'fix-only mode shows a status badge, not the diagnostic panel');
    assert.equal(fixOnly.find((element) => element.tagName === 'SECTION'), null, 'no diagnostic overlay is mounted without newcatroid_debug=1');
    fixOnly.getChannels()[0].emit({
        source: 'loader-worker', type: 'pipeline-mode', details: { gpuWorkerCreated: true, syncPipelines: true }
    });
    assert.match(fixOnly.find((element) => element.className === 'badge').textContent, /syncPipelines=true reached the GPU worker URL/);
});

test('the experimental option is applied only to the loader launch and never changes touch controls', () => {
    const page = createPage('?newcatroid_adreno_fix=1&newcatroid_touch=1');
    const otherWorker = new page.window.Worker('/unrelated.js');
    otherWorker.postMessage({ canvas: {}, args: ['unrelated'] });
    assert.equal(otherWorker.sent[0].message.syncPipelines, undefined);

    const loader = new page.window.Worker('/loader.js');
    loader.postMessage({ canvas: {}, args: ['-wgpu'], syncPipelines: false });
    assert.equal(loader.sent[0].message.syncPipelines, true);
    assert.equal(page.document.documentElement.children.length, 1, 'only the fix status badge is added');
    assert.equal(page.find((element) => element.id === 'newcatroid-game-diagnostics'), null);
    assert.equal(source.includes('newcatroid_touch_controls'), false, 'graphics code contains no touch-control implementation');
});

test('diagnostics collect progress, WASM, workers, GPU errors and heartbeat without uploading', async () => {
    const page = createPage('?newcatroid_debug=1&token=do-not-export', {
        navigator: {
            gpu: {
                requestAdapter: () => Promise.resolve({
                    info: { vendor: 'Qualcomm', architecture: 'Adreno', device: '740' },
                    features: new Set(['timestamp-query', 'texture-compression-bc']),
                    limits: { maxTextureDimension2D: 8192, maxBindGroups: 4, maxBufferSize: 268435456 }
                })
            }
        }
    });
    const api = page.window.__newcatroidGameDiagnostics;
    assert.ok(api);
    assert.equal(api.flags.debug, true);
    assert.equal(api.flags.adrenoFix, false);
    assert.equal(api.flags.alphaFix, false);

    page.elements.pct.textContent = '80%';
    page.elements.label.textContent = 'Loading the world';
    page.runIntervals();
    const progress = page.getChannels().find((channel) => channel.name === 'game-progress');
    progress.emit({ pct: 76, label: 'First frame received' });

    const workerChannel = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    workerChannel.emit({ source: 'loader-worker', type: 'wasm-start', at: Date.now(), details: { operation: 'instantiateStreaming' } });
    workerChannel.emit({ source: 'loader-worker', type: 'wasm-end', at: Date.now(), details: { operation: 'instantiateStreaming', durationMs: 240 } });
    workerChannel.emit({ source: 'loader-worker', type: 'worker-created', id: 'loader-child-1', at: Date.now(), details: { role: 'gpu-worker', url: '[local]/wgpu_worker.js', syncPipelines: true } });
    workerChannel.emit({ source: 'gpu-worker', type: 'worker-heartbeat', at: Date.now(), details: { gapMs: 5000 } });
    workerChannel.emit({ source: 'gpu-worker', type: 'console', at: Date.now(), details: { method: 'error', message: '[wgpu] validation error: framebuffer format unsupported' } });
    page.getChannels().find((channel) => channel.name === 'game-log').emit('[wgpu] shader pipeline failed: validation error');
    api.recordForTest('diagnostic test', 'Authorization: Bearer secret-value\ntoken=abc api_key=key123 user=private-user email=private@example.com\nhttps://local.test/private/path?token=url-secret /storage/emulated/0/private/save.dat');

    await new Promise((resolve) => setImmediate(resolve));
    const report = api.getReport();
    const parsed = JSON.parse(report);
    assert.equal(parsed.initialization.displayedPercent, 80);
    assert.equal(parsed.initialization.lastEngineProgressTarget, 76);
    assert.equal(parsed.webAssembly.state, 'ready');
    assert.equal(parsed.webAssembly.lastDurationMs, 240);
    assert.equal(parsed.graphics.pageAdapterProbe, 'available');
    assert.match(parsed.graphics.adapterInformation, /Qualcomm/);
    assert.match(parsed.graphics.adapterFeatures, /timestamp-query/);
    assert.match(parsed.graphics.adapterLimits, /maxTextureDimension2D=8192/);
    assert.equal(parsed.experimentalGraphicsMode.syncPipelinesOnGpuWorkerUrl, true);
    assert.equal(parsed.experimentalGraphicsMode.loaderReceivedSyncPipelines, null, 'the test observed worker URL only, not a loader init message');
    assert.match(parsed.graphics.lastShaderOrPipelineError, /validation error/);
    assert.match(report, /heartbeat/i);
    assert.doesNotMatch(report, /do-not-export|secret-value|abc|key123|private-user|private@example\.com|url-secret|private\/path|save\.dat/);
    assert.match(report, /\[REDACTED\]/);
    assert.equal(page.fetchCalls(), 0, 'no diagnostic request is sent');

    const selectButton = page.find((element) => element.textContent === 'Show full report text');
    assert.ok(selectButton);
    selectButton.click();
    const textArea = page.find((element) => element.tagName === 'TEXTAREA');
    assert.ok(textArea);
    assert.equal(textArea.hidden, false);
    assert.equal(textArea.readOnly, true);
    assert.equal(textArea.selected, true);
    assert.match(textArea.value, /NewCatroid local game diagnostics/);

    const copyButton = page.find((element) => element.textContent === 'Copy full report');
    copyButton.click();
    assert.equal(textArea.selected, true, 'clipboard absence falls back to selectable text');
});

test('integrates grouped GPU errors, crash stack, resize, audio, memory, resources and engine milestones', () => {
    const page = createPage('?newcatroid_debug=1&token=private-query-token');
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    const progress = page.getChannels().find((channel) => channel.name === 'game-progress');
    const crash = page.getChannels().find((channel) => channel.name === 'game-crash');
    const firstAt = Date.now() - 2500;
    const lastAt = Date.now() - 100;
    const errorDetails = {
        groupId: 'gpu-error-7',
        type: 'GPUValidationError',
        sourceKind: 'GPUDevice uncapturederror event',
        message: 'Attachment format rgba16float is not supported for this pipeline.',
        count: 1,
        firstAt,
        lastAt: firstAt,
        firstContext: [{ at: firstAt, sequence: 8, operation: 'GPUDevice.createRenderPipeline', count: 1,
            details: { fragmentTargetFormats: ['rgba16float'] } }],
        latestContext: [{ at: firstAt, sequence: 8, operation: 'GPUDevice.createRenderPipeline', count: 1,
            details: { fragmentTargetFormats: ['rgba16float'] } }],
        firstOperationCounts: { 'GPUDevice.createRenderPipeline': 1 },
        latestOperationCounts: { 'GPUDevice.createRenderPipeline': 1 }
    };
    worker.emit({ source: 'gpu-worker', sessionId: 'gpu-worker-1', type: 'webgpu-error-group', at: firstAt, details: errorDetails });
    worker.emit({ source: 'gpu-worker', sessionId: 'gpu-worker-1', type: 'webgpu-error-group', at: lastAt, details: Object.assign({}, errorDetails, {
        count: 3, lastAt, latestContext: [{ at: lastAt, sequence: 14, operation: 'GPUQueue.submit', count: 2, details: {} }],
        latestOperationCounts: { 'GPUQueue.submit': 2 }
    }) });
    worker.emit({ source: 'gpu-worker', type: 'pipeline-implementation', at: lastAt, details: {
        workerUrlSyncPipelines: true,
        apiCalls: { createRenderPipeline: 2, createRenderPipelineAsync: 1, createComputePipeline: 0, createComputePipelineAsync: 0 },
        recentOperations: [
            { sequence: 12, at: lastAt, operation: 'GPURenderPassEncoder.setViewport', count: 1, details: { values: [0, 0, 1152, 2160] } },
            { sequence: 13, at: lastAt, operation: 'GPURenderPassEncoder.setScissorRect', count: 1, details: { values: [0, 0, 1152, 2160] } }
        ]
    } });
    worker.emit({ source: 'gpu-worker', type: 'canvas-configure', at: lastAt, details: {
        count: 1, format: 'bgra8unorm', alphaMode: 'opaque', canvas: { width: 1152, height: 2160, cssWidth: null, cssHeight: null }
    } });
    worker.emit({ source: 'gpu-worker', type: 'canvas-texture-first', at: lastAt + 1, details: {
        canvas: { width: 1152, height: 2160 }, texture: { width: 1152, height: 2160 }
    } });
    worker.emit({ source: 'gpu-worker', type: 'gpu-queue-submit-first', at: lastAt + 2, details: { commandBufferCount: 1 } });
    worker.emit({ source: 'gpu-worker', type: 'worker-heartbeat', at: lastAt + 3, details: {
        gapMs: 5000,
        gpuOperations: { counts: { 'GPUQueue.submit': 5, 'GPURenderPassEncoder.draw': 20 }, queueSubmitCount: 5,
            canvasConfigureCount: 1, canvasTextureCount: 2, canvasSize: { width: 1152, height: 2160 },
            recentOperations: [{ sequence: 20, at: lastAt, operation: 'GPURenderPassEncoder.draw', count: 20, details: { vertexCount: 3 } }] }
    } });
    worker.emit({ source: 'gpu-worker', type: 'wasm-memory', at: lastAt + 4, details: {
        origin: 'GPU worker initialization message', metric: 'WebAssembly.Memory.buffer.byteLength capacity',
        bufferBytes: 2684354560, bufferMiB: 2560, shared: true
    } });
    worker.emit({ source: 'gpu-worker', type: 'wasm-memory-growth', at: lastAt + 5, details: {
        deltaPages: '1024', previousBufferBytes: 2684354560, currentBufferBytes: 2751463424,
        metric: 'WebAssembly linear-memory capacity; not allocator-used bytes or physical RAM'
    } });
    worker.emit({ source: 'io-worker', type: 'resource-fetch-failure', at: lastAt + 6, details: {
        kind: 'audio-resource', method: 'GET', status: 404, contentType: 'text/plain', contentLength: 12, durationMs: 40
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-capability', at: lastAt + 7, details: {
        environment: 'loader-worker', audioContextAvailable: false, audioWorkletNodeAvailable: false,
        autoplayUserActivationAvailable: false, note: 'worker capability only'
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-context', at: lastAt + 8, details: {
        contextId: 'worker-audio-1', constructor: 'AudioContext', state: 'suspended', sampleRate: 48000,
        destinationMaxChannelCount: 2
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-playback-attempt', at: lastAt + 9, details: {
        contextId: 'worker-audio-1', operation: 'AudioContext.resume', outcome: 'rejected', name: 'NotAllowedError',
        message: 'The request is not allowed by autoplay policy.'
    } });
    progress.emit({ lines: [
        '[wasm] window resized to 1152x2160 (was 1838x658)',
        '[mem] wasm memory 2560 MB (main)',
        'arena allocator: 2560 MB reserved, 2400 MB reported used',
        'Assert: net_leaderboard_mgr encountered an error',
        '[wasm] Starting audio thread'
    ] });
    crash.emit({ kind: 'error', thread: 'main',
        text: 'THREAD ERROR (main): RuntimeError: unreachable\n    at wasm-function[4132]:0x1a23bc\n    at mainLoop (game.js:1:91234)',
        heap: 2560, tail: ['[wasm] Loading the world', 'Assert: net_leaderboard_mgr encountered an error', '[wasm] Starting audio thread'] });

    const report = JSON.parse(api.getReport());
    assert.equal(report.graphics.totalErrorOccurrences, 3);
    assert.equal(report.graphics.errorGroups.length, 1);
    assert.equal(report.graphics.errorGroups[0].count, 3);
    assert.equal(report.graphics.errorGroups[0].firstAt, new Date(firstAt).toISOString());
    assert.equal(report.graphics.errorGroups[0].lastAt, new Date(lastAt).toISOString());
    assert.match(report.graphics.errorGroups[0].message, /rgba16float/);
    assert.equal(report.graphics.errorGroups[0].latestContext[0].operation, 'GPUQueue.submit');
    assert.equal(report.graphics.errorGroups[0].latestContext[0].count, 2);
    assert.equal(report.graphics.errorGroups[0].latestContext[0].operationDetails, '{}');
    assert.equal(report.rendering.firstCanvasConfigureAt, new Date(lastAt).toISOString());
    assert.equal(report.rendering.firstCanvasTextureAt, new Date(lastAt + 1).toISOString());
    assert.equal(report.rendering.firstQueueSubmitAt, new Date(lastAt + 2).toISOString());
    assert.equal(report.rendering.queueSubmitCount, 5);
    assert.equal(report.rendering.displayPresentationObserved, false);
    assert.equal(report.rendering.pipelineApiCalls.createRenderPipeline, 2);
    assert.equal(report.rendering.pipelineApiCalls.createRenderPipelineAsync, 1);
    assert.match(report.rendering.recentGpuOperations[0].operation, /setViewport|setScissorRect|draw/);

    assert.equal(report.viewport.engineResizeMessages[0].width, 1152);
    assert.equal(report.viewport.engineResizeMessages[0].previousWidth, 1838);
    assert.match(report.viewport.engineResizeMessages[0].message, /was 1838x658/);
    assert.equal(report.memory.latestWasmLinearMemory.bufferMiB, 2560);
    assert.equal(report.memory.wasmGrowthEvents[0].currentBufferBytes, 2751463424);
    assert.ok(report.memory.engineMemoryMessages.some((entry) => /2400 MB reported used/.test(entry.message)));
    assert.ok(report.recentEvents.some((event) => /net_leaderboard_mgr/.test(event.message)));

    const exception = report.exceptions.find((entry) => /RuntimeError/.test(entry.name));
    assert.ok(exception);
    assert.equal(exception.thread, 'main');
    assert.match(exception.message, /unreachable/);
    assert.match(exception.stack, /wasm-function\[4132\]/);
    assert.match(exception.stack, /mainLoop/);
    assert.equal(exception.wasmLinearMemoryBufferMiBAtCrash, 2560);
    assert.match(exception.wasmMemoryMetric, /not allocator-used bytes/);
    assert.deepEqual(exception.recentMessages, ['[wasm] Loading the world', 'Assert: net_leaderboard_mgr encountered an error', '[wasm] Starting audio thread']);
    assert.match(exception.timestampSource, /payload did not supply a crash timestamp/);

    assert.equal(report.resources.failures[0].kind, 'audio-resource');
    assert.equal(report.resources.failures[0].status, 404);
    assert.equal(report.audio.capabilities[0].audioContextAvailable, false);
    assert.equal(report.audio.contexts[0].state, 'suspended');
    assert.equal(report.audio.playAttempts[0].outcome, 'rejected');
    assert.match(report.audio.playAttempts[0].error, /autoplay policy/);
    assert.ok(report.audio.outputEvents.some((entry) => /Starting audio thread/.test(entry.message)));
    assert.match(report.interpretationNotes.join(' '), /do not prove audible output/);
    assert.doesNotMatch(JSON.stringify(report), /private-query-token/);
});

test('short summary is bounded, sanitized and the primary copy fallback selects compact text', () => {
    const page = createPage('?newcatroid_debug=1&newcatroid_adreno_fix=1&newcatroid_alpha_fix=1');
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    const now = Date.now();
    assert.deepEqual(JSON.parse(JSON.stringify(api.flags)), { debug: true, adrenoFix: true, alphaFix: true });

    worker.emit({ source: 'loader-worker', type: 'loader-init', at: now, details: { hasWgpuArgument: true, syncPipelinesRequested: true } });
    worker.emit({ source: 'loader-worker', type: 'worker-created', id: 'gpu-short', at: now + 1,
        details: { role: 'gpu-worker', url: '/wgpu_worker.js', syncPipelines: true } });
    const shaderCompatibility = {
        enabled: true, hookInstalled: true, alphaCoverageVariantsSeen: 4,
        nonVec4AlphaTestsBypassed: 1, vec4AlphaTestsPreserved: 3,
        firstNonVec4OutputType: 'f32', lastObservedAt: now + 2
    };
    worker.emit({ source: 'gpu-worker', type: 'gpu-worker-config', at: now + 2, details: {
        workerUrlSyncPipelines: true, workerUrlAlphaFix: true, shaderCompatibility,
        createRenderPipelineAvailable: true, createRenderPipelineAsyncAvailable: true, installedApiHooks: 28
    } });
    worker.emit({ source: 'gpu-worker', type: 'pipeline-implementation', at: now + 3, details: {
        workerUrlSyncPipelines: true, workerUrlAlphaFix: true, shaderCompatibility,
        apiCalls: { createRenderPipeline: 6, createRenderPipelineAsync: 0, createComputePipeline: 1, createComputePipelineAsync: 0 },
        recentOperations: [{ sequence: 8, at: now + 3, operation: 'GPUDevice.createRenderPipeline', count: 6,
            details: { label: 'world-pipeline' } }]
    } });
    worker.emit({ source: 'gpu-worker', type: 'webgpu-error-group', at: now + 4, details: {
        groupId: 'summary-error', type: 'GPUValidationError', sourceKind: 'GPUDevice uncapturederror event',
        sourceKindCounts: { 'GPUDevice uncapturederror event': 2 }, count: 2, firstAt: now + 3, lastAt: now + 4,
        message: 'Pipeline validation failed token=short-secret ' + 'x'.repeat(15000),
        latestContext: [{ at: now + 4, sequence: 9, operation: 'GPUDevice.createShaderModule', count: 1,
            details: { label: 'world-shader', token: 'context-secret' } }]
    } });
    worker.emit({ source: 'gpu-worker', type: 'worker-error', at: now + 5, details: {
        name: 'GPUWorkerError', message: 'shader operation failed',
        stack: 'GPUWorkerError: shader operation failed\n    at public-frame\n    at full-private-stack-marker',
        thread: 'gpu-worker'
    } });
    worker.emit({ source: 'gpu-worker', type: 'canvas-configure', at: now + 6, details: {
        count: 1, format: 'bgra8unorm', canvas: { width: 1152, height: 2160 }
    } });
    worker.emit({ source: 'gpu-worker', type: 'gpu-queue-submit-first', at: now + 7, details: { commandBufferCount: 1 } });
    worker.emit({ source: 'page', type: 'audio-context', at: now + 8, details: {
        contextId: 'page-audio', constructor: 'AudioContext', state: 'running', sampleRate: 48000,
        destinationMaxChannelCount: 2
    } });

    const summary = api.getShortSummary();
    assert.ok(summary.length <= 5000);
    assert.match(summary, /newcatroid_adreno_fix=1.*newcatroid_alpha_fix=1/);
    assert.match(summary, /GPU URL=true.*createRenderPipeline sync=6, async=0/);
    assert.match(summary, /worker shim confirmed.*non-vec4 bypassed=1/);
    assert.match(summary, /GPUValidationError x2/);
    assert.match(summary, /GPUDevice\.createShaderModule/);
    assert.match(summary, /AudioContext state=running/);
    assert.match(summary, /display\/pixels=not measured/);
    assert.doesNotMatch(summary, /short-secret|context-secret|full-private-stack-marker/);

    const report = JSON.parse(api.getReport());
    assert.equal(report.optIns.newcatroid_alpha_fix, true);
    assert.equal(report.graphics.shaderCompatibility.workerUrlConfirmed, true);
    assert.equal(report.graphics.shaderCompatibility.hookInstalled, true);
    assert.equal(report.graphics.shaderCompatibility.nonVec4AlphaTestsBypassed, 1);

    const copyButton = page.find((element) => element.textContent === 'Copy short summary');
    assert.ok(copyButton);
    copyButton.click();
    const textArea = page.find((element) => element.tagName === 'TEXTAREA');
    assert.equal(textArea.hidden, false);
    assert.equal(textArea.readOnly, true);
    assert.equal(textArea.selected, true);
    assert.match(textArea.value, /NewCatroid PlayGTA5 short diagnostics v3/);
    assert.ok(textArea.value.length <= 5000);
    assert.doesNotMatch(textArea.value, /full-private-stack-marker/);
});

test('short diagnostics distinguish server hash, binding 44 draw evidence and suspended-audio attempts', () => {
    const page = createPage('?newcatroid_debug=1&newcatroid_alpha_fix=1');
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    const hash = 'a'.repeat(64);
    const now = Date.now();
    worker.emit({ source: 'gpu-worker', type: 'worker-bootstrap', at: now, details: {
        workerUrlAlphaFix: true,
        alphaFixServerVerification: { requested: true, expectedSha256: hash, actualSha256: hash,
            expectedSource: 'shadany7824/playgta5@2fb671ad/wgpu_worker.js',
            requestedRelativePath: 'wgpu_worker.js', resolvedSourcePath: 'mirror/site/wgpu_worker.js',
            sourceResolution: 'nested-suffix', sourceBytes: 123456, reason: 'reviewed-worker-hash',
            hashMatches: true, shimInjected: true },
        shaderCompatibility: { hookInstalled: false, alphaCoverageVariantsSeen: 0, nonVec4AlphaTestsBypassed: 0, vec4AlphaTestsPreserved: 0 }
    } });
    worker.emit({ source: 'gpu-worker', type: 'gpu-binding-trace', at: now + 1, details: {
        traceId: 'binding44-test-trace',
        pass: { sequence: 3, label: 'world-pass', colorAttachments: [{ index: 0, format: 'bgra8unorm', width: 720, height: 1280, viewResourceId: 'view-target' }] },
        command: { operation: 'GPURenderPassEncoder.drawIndexed', apiCallReturned: true, arguments: { indexCount: 6 } },
        pipeline: { resourceId: 'pipeline-3', label: 'world-terrain', layoutResourceId: 'pipeline-layout-2',
            fragmentShaderResourceId: 'shader-module-1', fragmentShaderLabel: 'terrain-fs', fragmentEntryPoint: 'fsMain', colorTargetFormats: ['bgra8unorm'] },
        bindGroup: { index: 1, resourceId: 'bind-group-4', layoutResourceId: 'bind-layout-2',
            pipelineLayoutExpectedLayoutResourceId: 'bind-layout-2', layoutMatchesPipeline: true },
        binding44: { group: 1, binding: 44, wgslName: 'heightTexture', wgslType: 'texture_2d<f32>',
            textureLoadCount: 0, sampleCounts: { textureSampleLevel: 1 }, sampleCallCount: 1,
            analysisScope: 'direct WGSL builtin calls only; aliases and helper-function flow are not followed',
            layoutSampleType: 'float', layoutViewDimension: '2d', layoutVisibility: 2,
            resource: { kind: 'texture view', format: 'r32float', width: 256, height: 128,
                textureResourceId: 'texture-4', viewResourceId: 'view-4', mipLevelCount: 1 },
            samplers: [{ builtin: 'textureSampleLevel', name: 'heightSampler', group: 1, binding: 28,
                wgslType: 'sampler', layoutType: 'filtering', resourceId: 'sampler-2',
                minFilter: 'linear', magFilter: 'linear', mipmapFilter: 'nearest' }] }
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-context', at: now + 2, details: {
        contextId: 'worker-audio-1', constructor: 'AudioContext', state: 'suspended', sampleRate: 48000
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-playback-attempt', at: now + 3, details: {
        contextId: 'worker-audio-1', operation: 'AudioContext.resume', outcome: 'called'
    } });
    worker.emit({ source: 'loader-worker', type: 'audio-playback-attempt', at: now + 4, details: {
        contextId: 'worker-audio-1', operation: 'AudioContext.resume', outcome: 'rejected', name: 'NotAllowedError', message: 'autoplay blocked'
    } });

    const summary = api.getShortSummary();
    assert.match(summary, /server SHA match=true.*shim appended=true/);
    assert.match(summary, /source=shadany7824\/playgta5@2fb671ad\/wgpu_worker\.js request=wgpu_worker\.js selected=mirror\/site\/wgpu_worker\.js resolution=nested-suffix bytes=123456 reason=reviewed-worker-hash/);
    assert.match(summary, /runtime hook not observed/);
    assert.match(summary, /Binding 44 draw trace: group=1 b44=heightTexture:texture_2d<f32>/);
    assert.match(summary, /direct textureLoad=0 sample=textureSampleLevelx1/);
    assert.match(summary, /BGL=float\/2d resource=r32float/);
    assert.match(summary, /heightSampler|b28/);
    assert.match(summary, /target=bgra8unorm 720x1280/);
    assert.match(summary, /AudioContext state=suspended stateChanges=0 resume=rejected NotAllowedError/);
    assert.match(summary, /resume calls=1/);
    assert.match(summary, /worklets loaded\/rejected=0\/0 · AudioWorkletNode=0 · direct destination connections=0 · worker audio message=false/);
    assert.match(summary, /display\/pixels=not measured/);

    const report = JSON.parse(api.getReport());
    assert.equal(report.graphics.shaderCompatibility.serverVerification.requested, true);
    assert.equal(report.graphics.shaderCompatibility.serverVerification.expectedSha256, hash);
    assert.equal(report.graphics.shaderCompatibility.serverVerification.actualSha256, hash);
    assert.equal(report.graphics.shaderCompatibility.serverVerification.expectedSource, 'shadany7824/playgta5@2fb671ad/wgpu_worker.js');
    assert.equal(report.graphics.shaderCompatibility.serverVerification.requestedRelativePath, 'wgpu_worker.js');
    assert.equal(report.graphics.shaderCompatibility.serverVerification.resolvedSourcePath, 'mirror/site/wgpu_worker.js');
    assert.equal(report.graphics.shaderCompatibility.serverVerification.sourceResolution, 'nested-suffix');
    assert.equal(report.graphics.shaderCompatibility.serverVerification.sourceBytes, 123456);
    assert.equal(report.graphics.shaderCompatibility.serverVerification.reason, 'reviewed-worker-hash');
    assert.equal(report.graphics.shaderCompatibility.serverVerification.hashMatches, true);
    assert.equal(report.graphics.shaderCompatibility.serverVerification.shimInjected, true);
    assert.equal(report.rendering.binding44Traces[0].binding44.resource.format, 'r32float');
    assert.equal(report.rendering.binding44Traces[0].bindGroup.layoutMatchesPipeline, true);
    assert.equal(report.audio.contexts[0].resumeAttempts, 1);
    assert.match(report.audio.contexts[0].lastResumeResult, /rejected NotAllowedError/);
});

test('short diagnostics retain a compute binding 39 trace alongside fragment binding 44 evidence', () => {
    const page = createPage('?newcatroid_debug=1');
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    worker.emit({ source: 'gpu-worker', type: 'gpu-compute-binding-trace', at: Date.now(), details: {
        traceId: 'compute-b39-trace', stage: 'compute',
        pass: { sequence: 12, label: 'height-convert' },
        command: { operation: 'GPUComputePassEncoder.dispatchWorkgroups', apiCallReturned: true, arguments: { workgroups: [16, 8, 1] } },
        pipeline: { resourceId: 'compute-pipeline-2', label: 'height-convert-pipeline', layoutResourceId: '',
            layoutMode: 'auto or implicit', computeShaderResourceId: 'shader-module-7', computeShaderLabel: 'height-convert-cs', computeEntryPoint: 'csMain' },
        bindGroup: { index: 0, resourceId: 'bind-group-5', layoutResourceId: 'bind-layout-8',
            pipelineExpectedLayoutResourceId: 'bind-layout-8', layoutMatchesPipeline: true },
        binding39: { group: 0, binding: 39, wgslName: 'heightTexture', wgslType: 'texture_2d<f32>',
            textureLoadCount: 1, sampleCounts: {}, sampleCallCount: 0,
            analysisScope: 'direct WGSL builtin calls only; aliases and helper-function flow are not followed',
            layoutSampleType: 'float', layoutViewDimension: '2d', layoutVisibility: 4,
            resource: { kind: 'texture view', format: 'r32float', width: 128, height: 64,
                textureResourceId: 'texture-9', viewResourceId: 'view-9', mipLevelCount: 1 },
            samplers: [] }
    } });

    const summary = api.getShortSummary();
    assert.match(summary, /Binding 39 compute trace: group=0 b39=heightTexture:texture_2d<f32>/);
    assert.match(summary, /direct textureLoad=1 sample=none BGL=float\/2d visibility=4 resource=r32float#texture-9\/view-9 128x64/);
    assert.match(summary, /workgroups=16x8x1/);
    const report = JSON.parse(api.getReport());
    assert.equal(report.rendering.computeBinding39Traces.length, 1);
    assert.equal(report.rendering.computeBinding39Traces[0].binding39.resource.format, 'r32float');
    assert.equal(report.rendering.computeBinding39Traces[0].bindGroup.layoutMatchesPipeline, true);
});

test('short diagnostics preserve first and latest distinct GPU errors with texture and layout facts', () => {
    const page = createPage('?newcatroid_debug=1');
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    const now = Date.now();
    const groups = [
        ['earliest-layout-issue', 'earliest-layout-issue: RGBA32Float binding did not match Float layout.', now - 6000],
        ['second-early-issue', 'second-early-issue: distinct layout failure.', now - 5000],
        ['middle-issue-a', 'middle-issue-a: validation error A.', now - 4000],
        ['middle-issue-b', 'middle-issue-b: validation error B.', now - 3000],
        ['second-latest-issue', 'second-latest-issue: distinct validation error.', now - 2000],
        ['latest-issue', 'latest-issue: distinct validation error.', now - 1000]
    ];
    groups.forEach(([groupId, message, firstAt], index) => {
        const firstContext = groupId === 'earliest-layout-issue' ? [
            { at: firstAt, sequence: 10, operation: 'GPUDevice.createBindGroupLayout', count: 1, details: {
                resourceId: 'bind-layout-3',
                textureBindings: [{ binding: 32, texture: { sampleType: 'float', viewDimension: '2d', multisampled: 'false' } }],
                samplerBindings: [{ binding: 16, sampler: { type: 'filtering' } }]
            } },
            { at: firstAt + 1, sequence: 11, operation: 'GPUDevice.createBindGroup', count: 1, details: {
                layoutResourceId: 'bind-layout-3',
                textureBindings: [{ binding: 32, resource: { kind: 'texture view', resourceId: 'view-4', texture: {
                    resourceId: 'view-4', texture: { resourceId: 'texture-2', format: 'rgba32float', width: 1024,
                        height: 512, depthOrArrayLayers: 1, dimension: '2d', mipLevelCount: 4 },
                    view: { dimension: '2d', format: 'rgba32float', baseMipLevel: 1, mipLevelCount: 2 }
                } } }],
                samplerBindings: [{ binding: 16, resource: { kind: 'sampler', sampler: { resourceId: 'sampler-1', minFilter: 'linear', magFilter: 'linear', mipmapFilter: 'linear' } } }]
            } },
            { at: firstAt + 2, sequence: 12, operation: 'GPUDevice.createRenderPipeline', count: 1, details: {
                resourceId: 'pipeline-9', pipelineLayoutResourceId: 'pipeline-layout-8',
                fragmentShaderResourceId: 'shader-module-7', fragmentShaderLabel: 'world-fs',
                fragmentEntryPoint: 'fsMain'
            } }
        ] : [];
        worker.emit({ source: 'gpu-worker', sessionId: 'summary-errors', type: 'webgpu-error-group', at: firstAt, details: {
            groupId: 'gpu-error-' + groupId,
            type: 'GPUValidationError',
            sourceKind: 'GPUDevice uncapturederror event',
            sourceKindCounts: { 'GPUDevice uncapturederror event': index + 1 },
            count: index + 1,
            firstAt,
            lastAt: firstAt + index * 10,
            message,
            firstContext,
            latestContext: firstContext
        } });
    });

    const summary = api.getShortSummary();
    assert.ok(summary.length <= 5000);
    assert.match(summary, /First and latest distinct WebGPU error groups/);
    assert.match(summary, /earliest-layout-issue/);
    assert.match(summary, /second-early-issue/);
    assert.match(summary, /second-latest-issue/);
    assert.match(summary, /latest-issue/);
    assert.match(summary, /first=\d{2}:\d{2}:\d{2}Z last=\d{2}:\d{2}:\d{2}Z/);
    assert.doesNotMatch(summary, /middle-issue-[ab]/);
    assert.match(summary, /bind-layout=bind-layout-3/);
    assert.match(summary, /texture layout b32=float\/2d/);
    assert.match(summary, /sampler layout b16=filtering/);
    assert.match(summary, /pipeline=pipeline-9/);
    assert.match(summary, /pipeline-layout=pipeline-layout-8/);
    assert.match(summary, /shaders=\[fs=shader-module-7\(world-fs\)\]/);
    assert.match(summary, /texture b32=rgba32float 1024x512x1 texture-2\/view-4 textureMips=4 viewMips=1\+2/);
    assert.match(summary, /sampler b16=linear\/linear\/linear sampler-1/);
    assert.match(summary, /x6/);

    const overflowPage = createPage('?newcatroid_debug=1');
    const overflowApi = overflowPage.window.__newcatroidGameDiagnostics;
    const overflowWorker = overflowPage.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    for (let index = 0; index < 205; index++) {
        const at = now + index * 10;
        overflowWorker.emit({ source: 'gpu-worker', sessionId: 'overflow-errors', type: 'webgpu-error-group', at, details: {
            groupId: 'overflow-' + index,
            type: 'GPUValidationError',
            sourceKind: 'GPUDevice uncapturederror event',
            count: index + 1,
            firstAt: at,
            lastAt: at,
            message: 'overflow-marker-' + index
        } });
    }
    const overflowSummary = overflowApi.getShortSummary();
    assert.ok(overflowSummary.length <= 5000);
    assert.equal(overflowApi.state.gpu.errorGroups.length, 200);
    assert.match(overflowSummary, /overflow-marker-0/);
    assert.match(overflowSummary, /overflow-marker-1/);
    assert.match(overflowSummary, /overflow-marker-203/);
    assert.match(overflowSummary, /overflow-marker-204/);
    assert.doesNotMatch(overflowSummary, /overflow-marker-100/);
});

test('short diagnostics report browser version and actual worker adapter/device features', () => {
    const page = createPage('?newcatroid_debug=1', {
        navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 16; SM-S918B) AppleWebKit/537.36 Chrome/144.0.7550.12 Mobile Safari/537.36' }
    });
    const api = page.window.__newcatroidGameDiagnostics;
    const worker = page.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    worker.emit({ source: 'gpu-worker', type: 'gpu-adapter-observed', at: Date.now(), details: {
        information: { vendor: 'qualcomm', architecture: 'adreno-7xx' },
        features: ['float32-filterable', 'texture-compression-bc'],
        limits: { maxTextureDimension2D: 8192 }
    } });
    worker.emit({ source: 'gpu-worker', type: 'gpu-device-observed', at: Date.now(), details: {
        uncapturederrorListenerInstalled: true,
        requestedFeatures: ['float32-filterable'],
        features: ['float32-filterable']
    } });

    const summary = api.getShortSummary();
    assert.match(summary, /Chrome\/Chromium 144\.0\.7550\.12/);
    assert.match(summary, /Android 16/);
    assert.match(summary, /float32-filterable adapter=present, requested=present, device=present/);
    assert.match(summary, /adapter=\[float32-filterable, texture-compression-bc\]/);
    assert.match(summary, /device=\[float32-filterable\]/);

    const report = JSON.parse(api.getReport());
    assert.equal(report.browser.chromiumVersion, '144.0.7550.12');
    assert.equal(report.browser.androidVersion, '16');
    assert.deepEqual(report.graphics.gpuWorkerAdapterFeatures, ['float32-filterable', 'texture-compression-bc']);
    assert.deepEqual(report.graphics.gpuWorkerDeviceRequestedFeatures, ['float32-filterable']);
    assert.deepEqual(report.graphics.gpuWorkerDeviceFeatures, ['float32-filterable']);

    const missingListsPage = createPage('?newcatroid_debug=1');
    const missingListsWorker = missingListsPage.getChannels().find((channel) => channel.name === 'newcatroid-debug');
    missingListsWorker.emit({ source: 'gpu-worker', type: 'gpu-adapter-observed', at: Date.now(), details: {
        information: { vendor: 'qualcomm', architecture: 'adreno-7xx' }
    } });
    missingListsWorker.emit({ source: 'gpu-worker', type: 'gpu-device-observed', at: Date.now(), details: {
        uncapturederrorListenerInstalled: true
    } });
    assert.match(missingListsPage.window.__newcatroidGameDiagnostics.getShortSummary(), /float32-filterable adapter=unobserved, requested=unobserved, device=unobserved/);
    const missingListsReport = JSON.parse(missingListsPage.window.__newcatroidGameDiagnostics.getReport());
    assert.equal(missingListsReport.graphics.gpuWorkerAdapterFeatures, null);
    assert.equal(missingListsReport.graphics.gpuWorkerDeviceRequestedFeatures, null);
    assert.equal(missingListsReport.graphics.gpuWorkerDeviceFeatures, null);
});

test('short diagnostics prefer exact Chrome and Android versions from supported UA client hints', async () => {
    const page = createPage('?newcatroid_debug=1', {
        navigator: {
            userAgent: 'Mozilla/5.0 (Linux; Android 16; SM-S918B) AppleWebKit/537.36 Chrome/144.0.7550.12 Mobile Safari/537.36',
            userAgentData: {
                platform: 'Android',
                getHighEntropyValues: async (requestedHints) => {
                    assert.equal(Array.from(requestedHints).join(','), 'fullVersionList,platformVersion');
                    return {
                        fullVersionList: [
                            { brand: 'Chromium', version: '144.0.7550.10' },
                            { brand: 'Google Chrome', version: '144.0.7550.12' }
                        ],
                        platformVersion: '16.0.0'
                    };
                }
            }
        }
    });
    await new Promise((resolve) => setImmediate(resolve));

    const api = page.window.__newcatroidGameDiagnostics;
    const summary = api.getShortSummary();
    assert.match(summary, /Chrome\/Chromium 144\.0\.7550\.12/);
    assert.match(summary, /OS Android 16\.0\.0 \(platformVersion=16\.0\.0, UA Android token=16\) · UA-CH=captured/);

    const report = JSON.parse(api.getReport());
    assert.equal(report.browser.chromiumVersion, '144.0.7550.12');
    assert.equal(report.browser.androidVersion, '16');
    assert.equal(report.browser.platform, 'Android');
    assert.equal(report.browser.platformVersion, '16.0.0');
    assert.equal(report.browser.userAgentHintsStatus, 'captured');
    assert.match(report.browser.versionSource, /fullVersionList\/platformVersion/);
});

test('audio observer samples shared ring counters and a direct worklet destination edge without claiming audible output', async () => {
    class FakeAudioNode {
        constructor(context) { this.context = context; }
        connect(destination) { this.connectedDestination = destination; return this; }
    }
    class FakeAudioWorkletNode extends FakeAudioNode {
        constructor(context, processorName) {
            super(context);
            this.processorName = processorName;
            this.listeners = Object.create(null);
        }
        addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
    }
    class FakeAudioContext {
        constructor() {
            this.state = 'running';
            this.sampleRate = 48000;
            this.destination = { maxChannelCount: 2 };
            this.listeners = Object.create(null);
            this.audioWorklet = { addModule() { return Promise.resolve(); } };
        }
        addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
        resume() { return Promise.resolve(); }
    }
    const page = createPage('?newcatroid_debug=1', {
        window: { AudioNode: FakeAudioNode, AudioContext: FakeAudioContext, AudioWorkletNode: FakeAudioWorkletNode }
    });
    const context = new page.window.AudioContext();
    await context.audioWorklet.addModule('/audio-worklet.js');
    const node = new page.window.AudioWorkletNode(context, 'game-audio');
    node.connect(context.destination);

    const worker = new page.window.Worker('/game.js');
    const shared = new SharedArrayBuffer(128);
    const header = new Int32Array(shared, 32, 8);
    Atomics.store(header, 0, 100);
    Atomics.store(header, 1, 40);
    Atomics.store(header, 3, 5);
    Atomics.store(header, 4, 123456);
    worker.emit('message', { data: { audio: { memory: { buffer: shared }, ring: 32, capacity: 64 } } });

    Atomics.store(header, 0, 148);
    Atomics.store(header, 1, 88);
    Atomics.store(header, 3, 8);
    Atomics.store(header, 4, 654321);
    page.runIntervals();

    const report = JSON.parse(page.window.__newcatroidGameDiagnostics.getReport());
    assert.equal(report.audio.nodes.length, 1);
    assert.equal(report.audio.nodes[0].processorName, 'game-audio');
    assert.equal(report.audio.nodes[0].connectedDirectlyToDestination, true);
    assert.equal(report.audio.nodes[0].directDestinationConnectAttempts, 1);
    assert.equal(report.audio.ringStatus, 'SharedArrayBuffer ring sampled with non-destructive Atomics.load');
    assert.equal(report.audio.ringCapacity, 64);
    assert.ok(report.audio.ringSamples.length >= 2);
    assert.deepEqual({
        written: report.audio.ringSamples.at(-1).framesWritten,
        read: report.audio.ringSamples.at(-1).framesRead,
        queued: report.audio.ringSamples.at(-1).queuedFrames,
        calls: report.audio.ringSamples.at(-1).workletCalls,
        peakRaw: report.audio.ringSamples.at(-1).peakRaw,
        deltaWritten: report.audio.ringSamples.at(-1).deltaFramesWritten,
        deltaRead: report.audio.ringSamples.at(-1).deltaFramesRead,
        deltaCalls: report.audio.ringSamples.at(-1).deltaWorkletCalls
    }, { written: 148, read: 88, queued: 60, calls: 8, peakRaw: 654321, deltaWritten: 48, deltaRead: 48, deltaCalls: 3 });
    assert.match(report.audio.ringSamples.at(-1).counterSchema, /served page identity unverified/);

    const summary = page.window.__newcatroidGameDiagnostics.getShortSummary();
    assert.match(summary, /AudioBufferSourceNode\.start calls=0/);
    assert.match(summary, /AudioWorkletNode=1 · direct destination connections=1/);
    assert.match(summary, /workletCalls=8/);
    assert.match(summary, /actual audible output is not measured/);
    assert.equal(page.fetchCalls(), 0);
});

test('audio observer records context state, user gesture and autoplay rejection without resuming automatically', async () => {
    class FakeAudioContext {
        constructor() {
            this.state = 'suspended';
            this.sampleRate = 48000;
            this.baseLatency = 0.01;
            this.outputLatency = 0.03;
            this.destination = { maxChannelCount: 2 };
            this.listeners = Object.create(null);
        }
        addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
        resume() { return Promise.reject(Object.assign(new Error('autoplay blocked'), { name: 'NotAllowedError' })); }
        suspend() { return Promise.resolve(); }
        close() { return Promise.resolve(); }
        emit(type) { for (const listener of this.listeners[type] || []) listener(); }
    }
    const page = createPage('?newcatroid_debug=1', {
        window: { AudioContext: FakeAudioContext },
        navigator: { userActivation: { isActive: true, hasBeenActive: true } }
    });
    page.window.dispatch('pointerdown');
    const context = new page.window.AudioContext();
    assert.equal(context.state, 'suspended');
    await assert.rejects(context.resume(), /autoplay blocked/);
    context.state = 'running';
    context.emit('statechange');
    await new Promise((resolve) => setImmediate(resolve));

    const report = JSON.parse(page.window.__newcatroidGameDiagnostics.getReport());
    assert.equal(report.audio.contexts.length, 1);
    assert.equal(report.audio.contexts[0].state, 'running');
    assert.equal(report.audio.contexts[0].sampleRate, 48000);
    assert.equal(report.audio.contexts[0].resumeAttempts, 1);
    assert.match(report.audio.contexts[0].lastResumeResult, /rejected.*NotAllowedError/);
    assert.ok(report.audio.userGestureAt);
    assert.equal(report.audio.playAttempts.some((attempt) => attempt.outcome === 'rejected' && /autoplay blocked/.test(attempt.error)), true);
    assert.equal(report.audio.playAttempts.at(-1).userActivation.hasBeenActive, true);
    assert.equal(page.fetchCalls(), 0);
});
