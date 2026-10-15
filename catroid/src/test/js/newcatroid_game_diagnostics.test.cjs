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
    const window = {
        location: { href: origin + '/index.html' + search, search, origin },
        document,
        Worker: FakeWorker,
        console: {
            error() {}, warn() {}, log() {}
        },
        performance: { now: () => 100 },
        addEventListener(type, listener) { (windowListeners[type] || (windowListeners[type] = [])).push(listener); },
        dispatch(type, event) { for (const listener of windowListeners[type] || []) listener(event || {}); }
    };
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
        adrenoFix: false
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
    assert.match(fixOnly.find((element) => element.className === 'badge').textContent, /URL carried syncpipelines=1/);
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
    assert.equal(parsed.experimentalGraphicsMode.loaderGpuWorkerUrlConfirmedSyncPipelines, true);
    assert.match(parsed.graphics.lastShaderOrPipelineError, /validation error/);
    assert.match(report, /heartbeat/i);
    assert.doesNotMatch(report, /do-not-export|secret-value|abc|key123|private-user|private@example\.com|url-secret|private\/path|save\.dat/);
    assert.match(report, /\[REDACTED\]/);
    assert.equal(page.fetchCalls(), 0, 'no diagnostic request is sent');

    const selectButton = page.find((element) => element.textContent === 'Show/select text');
    assert.ok(selectButton);
    selectButton.click();
    const textArea = page.find((element) => element.tagName === 'TEXTAREA');
    assert.ok(textArea);
    assert.equal(textArea.hidden, false);
    assert.equal(textArea.readOnly, true);
    assert.equal(textArea.selected, true);
    assert.match(textArea.value, /NewCatroid local game diagnostics/);

    const copyButton = page.find((element) => element.textContent === 'Copy report');
    copyButton.click();
    assert.equal(textArea.selected, true, 'clipboard absence falls back to selectable text');
});
