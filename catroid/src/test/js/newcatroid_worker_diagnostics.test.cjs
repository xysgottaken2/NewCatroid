'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/res/raw/newcatroid_worker_diagnostics.js'),
    'utf8'
);

class FakeBroadcastChannel {
    static instances = [];
    constructor(name) {
        this.name = name;
        this.messages = [];
        FakeBroadcastChannel.instances.push(this);
    }
    postMessage(data) { this.messages.push(data); }
    close() { this.closed = true; }
}

class FakeWorker {
    constructor(url, options) {
        this.url = String(url);
        this.options = options;
        this.listeners = Object.create(null);
    }
    addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
    postMessage(message) { this.lastMessage = message; }
    emit(type, data) { for (const listener of this.listeners[type] || []) listener(data); }
}

function createWorkerContext(scriptPath = '/loader.js?newcatroid_worker_debug=1') {
    FakeBroadcastChannel.instances = [];
    const listeners = Object.create(null);
    const intervalCallbacks = [];
    let clock = 10;
    const origin = 'http://local.test:8080';
    const self = {
        location: {
            href: origin + scriptPath,
            origin,
            search: scriptPath.includes('?') ? '?' + scriptPath.split('?').slice(1).join('?') : ''
        },
        performance: { now: () => clock },
        console: {
            error() {}, warn() {}, log() {}
        },
        Worker: FakeWorker,
        addEventListener(type, listener) { (listeners[type] || (listeners[type] = [])).push(listener); },
        dispatch(type, event) { for (const listener of listeners[type] || []) listener(event || {}); },
        setInterval(callback) { intervalCallbacks.push(callback); return intervalCallbacks.length; }
    };
    const offscreenPrototype = {
        getContext(type) { return type === 'webgpu' ? { kind: type } : null; }
    };
    function OffscreenCanvas() {}
    OffscreenCanvas.prototype = offscreenPrototype;
    self.OffscreenCanvas = OffscreenCanvas;
    const wasm = {
        instantiateStreaming() { return Promise.resolve({ instance: {}, module: {} }); },
        instantiate() { return Promise.resolve({ instance: {}, module: {} }); },
        compileStreaming() { return Promise.resolve({}); },
        compile() { return Promise.resolve({}); }
    };
    const context = {
        self,
        URL,
        URLSearchParams,
        BroadcastChannel: FakeBroadcastChannel,
        OffscreenCanvas,
        WebAssembly: wasm,
        performance: self.performance,
        Date,
        Math,
        Object,
        Array,
        String,
        Number,
        RegExp,
        JSON,
        Promise,
        Reflect,
        Proxy,
        isFinite
    };
    vm.runInNewContext(source, context, { filename: 'newcatroid_worker_diagnostics.js' });
    return {
        context,
        self,
        intervals: intervalCallbacks,
        advance(ms) { clock += ms; },
        channel: FakeBroadcastChannel.instances.find((item) => item.name === 'newcatroid-debug'),
        emit(type, event) { self.dispatch(type, event); }
    };
}

test('loader worker observes only the GPU and I/O workers and confirms sync pipeline URL', () => {
    const env = createWorkerContext('/loader.js?newcatroid_worker_debug=1');
    assert.ok(env.channel);
    assert.ok(env.channel.messages.some((message) => message.type === 'worker-bootstrap' && message.source === 'loader-worker'));

    const gpu = new env.self.Worker('/wgpu_worker.js?syncpipelines=1');
    const io = new env.self.Worker('/io_worker.js');
    const unrelated = new env.self.Worker('blob:http://local.test/opaque-worker-id');
    assert.equal(gpu.url, 'http://local.test:8080/wgpu_worker.js?syncpipelines=1&newcatroid_worker_debug=1');
    assert.equal(io.url, 'http://local.test:8080/io_worker.js?newcatroid_worker_debug=1');
    assert.equal(unrelated.url, 'blob:http://local.test/opaque-worker-id');

    const created = env.channel.messages.filter((message) => message.type === 'worker-created');
    assert.equal(created.length, 2, 'Emscripten/game pthread workers are not wrapped');
    assert.deepEqual(JSON.parse(JSON.stringify(created.map((message) => ({
        role: message.details.role,
        syncPipelines: message.details.syncPipelines
    })))), [
        { role: 'gpu-worker', syncPipelines: true },
        { role: 'io-worker', syncPipelines: false }
    ]);

    gpu.emit('message', { data: { loaded: true, token: 'must-not-leak' } });
    const loaded = env.channel.messages.find((message) => message.type === 'worker-state' && message.details.role === 'gpu-worker');
    assert.ok(loaded);
    assert.equal(loaded.details.state, 'loaded');
    assert.doesNotMatch(JSON.stringify(loaded), /must-not-leak/);
});

test('fix-only worker probe confirms the GPU URL without enabling full diagnostics hooks', () => {
    const env = createWorkerContext('/loader.js?newcatroid_pipeline_probe=1');
    const originalInstantiate = env.context.WebAssembly.instantiateStreaming;
    const originalConsoleLog = env.self.console.log;
    const worker = new env.self.Worker('/wgpu_worker.js?syncpipelines=1');
    const mode = env.channel.messages.find((message) => message.type === 'pipeline-mode');
    assert.ok(mode);
    assert.equal(mode.details.gpuWorkerCreated, true);
    assert.equal(mode.details.syncPipelines, true);
    assert.equal(worker.url, '/wgpu_worker.js?syncpipelines=1', 'the lightweight probe leaves worker URL unchanged');
    assert.equal(env.intervals.length, 0, 'fix-only mode does not install heartbeat timers');
    assert.equal(env.context.WebAssembly.instantiateStreaming, originalInstantiate, 'fix-only mode does not wrap WASM');
    assert.equal(env.self.console.log, originalConsoleLog, 'fix-only mode does not capture worker console logs');
    assert.equal(env.channel.messages.some((message) => message.type === 'worker-bootstrap'), false);
});

test('worker bootstrap records WASM, local errors, context creation and nonblocking heartbeats', async () => {
    const env = createWorkerContext('/loader.js?newcatroid_worker_debug=1');
    await env.context.WebAssembly.instantiateStreaming();
    const wasmEvents = env.channel.messages.filter((message) => /^wasm-/.test(message.type));
    assert.deepEqual(wasmEvents.map((message) => message.type), ['wasm-start', 'wasm-end']);
    assert.equal(wasmEvents[0].details.operation, 'instantiateStreaming');
    assert.equal(typeof wasmEvents[1].details.durationMs, 'number');

    const canvas = new env.context.OffscreenCanvas();
    assert.equal(canvas.getContext('webgpu').kind, 'webgpu');
    assert.ok(env.channel.messages.some((message) => message.type === 'gpu-context' && /webgpu.*created/.test(message.details.message)));

    env.self.console.error('WebGPU validation error: Authorization: Bearer private-token');
    const consoleEvent = env.channel.messages.find((message) => message.type === 'console');
    assert.ok(consoleEvent);
    assert.doesNotMatch(JSON.stringify(consoleEvent), /private-token/);
    assert.match(consoleEvent.details.message, /REDACTED/);

    env.emit('error', { message: 'GPU worker failed', filename: 'http://local.test:8080/wgpu_worker.js?token=secret' });
    env.emit('unhandledrejection', { reason: new Error('WASM rejected') });
    assert.ok(env.channel.messages.some((message) => message.type === 'exception' && /GPU worker failed/.test(message.details.message)));
    assert.ok(env.channel.messages.some((message) => message.type === 'unhandled-rejection' && /WASM rejected/.test(message.details.message)));
    assert.doesNotMatch(JSON.stringify(env.channel.messages), /token=secret/);

    env.advance(6000);
    env.intervals[0]();
    assert.ok(env.channel.messages.some((message) => message.type === 'worker-heartbeat' && message.details.gapMs === 6000));
    assert.equal(env.channel.messages.some((message) => message.type === 'worker-heartbeat-gap'), false);
});

test('GPU worker bootstrap runs without touching unrelated network or credentials APIs', () => {
    const env = createWorkerContext('/wgpu_worker.js?newcatroid_worker_debug=1');
    env.emit('error', { message: 'GPU device lost', filename: '/wgpu_worker.js' });
    assert.ok(env.channel.messages.some((message) => message.type === 'worker-bootstrap' && message.source === 'gpu-worker'));
    assert.ok(env.channel.messages.some((message) => message.type === 'exception'));
    assert.equal(env.context.fetch, undefined);
    assert.equal(env.context.XMLHttpRequest, undefined);
    assert.doesNotMatch(source, /fetch\s*\(|XMLHttpRequest|\/log/);
});
