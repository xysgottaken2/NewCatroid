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

function createWorkerContext(scriptPath = '/loader.js?newcatroid_worker_debug=1', options = {}) {
    FakeBroadcastChannel.instances = [];
    const listeners = Object.create(null);
    const intervalCallbacks = [];
    let clock = 10;
    const origin = 'http://local.test:8080';
    const self = Object.assign({
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
    }, options.self || {});
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
    const context = Object.assign({
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
    }, options.context || {});
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

test('GPU hooks preserve uncaptured error text, pipeline context, counts, operation order and existing error-scope usage', async () => {
    class FakeGpuDevice {
        constructor() {
            this.features = new Set(['texture-compression-bc']);
            this.limits = { maxBindGroups: 4 };
            this.listeners = Object.create(null);
            this.lost = new Promise(() => {});
            this.pushCalls = 0;
            this.popCalls = 0;
        }
        addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
        createRenderPipeline() {
            const error = new Error('Invalid fragment target format rgba16float');
            error.name = 'GPUValidationError';
            throw error;
        }
        createRenderPipelineAsync() { return Promise.reject(Object.assign(new Error('async pipeline validation failed'), { name: 'GPUValidationError' })); }
        pushErrorScope() { this.pushCalls++; }
        popErrorScope() { this.popCalls++; return Promise.resolve(null); }
    }
    class FakeGpuQueue {
        submit(commandBuffers) { this.lastCount = commandBuffers.length; }
    }
    class FakeGpuTextureView {
        constructor(descriptor) { this.label = descriptor.label || 'color-view'; }
    }
    class FakeGpuTexture {
        constructor() {
            this.label = 'color-texture'; this.width = 1152; this.height = 2160; this.depthOrArrayLayers = 1;
            this.mipLevelCount = 1; this.sampleCount = 1; this.dimension = '2d'; this.format = 'rgba16float'; this.usage = 16;
        }
        createView(descriptor) { return new FakeGpuTextureView(descriptor || {}); }
        destroy() {}
    }
    class FakeRenderPassEncoder {
        setViewport() {}
        setScissorRect() {}
        draw() {}
    }
    class FakeCommandEncoder {
        beginRenderPass() { return new FakeRenderPassEncoder(); }
        finish() { return { label: 'commands' }; }
    }
    class FakeGpuAdapter {
        requestDevice() { return Promise.resolve(device); }
    }
    const device = new FakeGpuDevice();
    const adapter = new FakeGpuAdapter();
    const gpu = { requestAdapter() { return Promise.resolve(adapter); } };
    const env = createWorkerContext('/wgpu_worker.js?newcatroid_worker_debug=1', {
        self: { GPUDevice: FakeGpuDevice, GPUQueue: FakeGpuQueue, GPUAdapter: FakeGpuAdapter,
            GPUTexture: FakeGpuTexture, GPUCommandEncoder: FakeCommandEncoder, GPURenderPassEncoder: FakeRenderPassEncoder,
            navigator: { gpu } }
    });

    const selectedAdapter = await env.self.navigator.gpu.requestAdapter();
    const selectedDevice = await selectedAdapter.requestDevice();
    assert.equal(selectedDevice, device);

    const shaderModule = { label: 'test-fragment', getCompilationInfo: () => Promise.resolve({ messages: [
        { type: 'error', message: 'entry point has no matching return type', lineNum: 8, linePos: 4 }
    ] }) };
    const descriptor = {
        label: 'test-pipeline',
        vertex: { module: { label: 'test-vertex' }, entryPoint: 'main', buffers: [{ arrayStride: 12, stepMode: 'vertex', attributes: [
            { format: 'float32x3', offset: 0, shaderLocation: 0 }
        ] }] },
        fragment: { module: shaderModule, entryPoint: 'main', targets: [{ format: 'rgba16float' }] },
        primitive: { topology: 'triangle-list', cullMode: 'back' }
    };
    assert.throws(() => device.createRenderPipeline(descriptor), /Invalid fragment target format/);
    const queue = new FakeGpuQueue();
    queue.submit([{}]);
    const texture = new FakeGpuTexture();
    const view = texture.createView({ label: 'color-view', format: 'rgba16float', dimension: '2d' });
    const encoder = new FakeCommandEncoder();
    const pass = encoder.beginRenderPass({ label: 'world-pass', colorAttachments: [
        { view, loadOp: 'clear', storeOp: 'store', clearValue: { r: 0, g: 0, b: 0, a: 1 } }
    ] });
    pass.setViewport(0, 0, 1152, 2160, 0, 1);
    pass.setScissorRect(0, 0, 1152, 2160);
    pass.draw(3, 1, 0, 0);
    await assert.rejects(device.createRenderPipelineAsync(descriptor), /async pipeline validation failed/);
    for (const listener of device.listeners.uncapturederror || []) {
        listener({ error: Object.assign(new Error('Invalid fragment target format rgba16float'), { name: 'GPUValidationError' }) });
    }
    await new Promise((resolve) => setImmediate(resolve));

    const messages = env.channel.messages;
    const errors = messages.filter((message) => message.type === 'webgpu-error-group');
    assert.ok(errors.some((message) => /rgba16float/.test(message.details.message)));
    assert.ok(errors.some((message) => /entry point has no matching return type/.test(message.details.message)));
    const repeated = errors.filter((message) => /rgba16float/.test(message.details.message)).at(-1);
    assert.equal(repeated.details.count, 2, 'throw and uncapturederror messages with the same type/text are grouped');
    assert.deepEqual(JSON.parse(JSON.stringify(repeated.details.sourceKinds)), [
        'thrown by GPUDevice.createRenderPipeline', 'GPUDevice uncapturederror event'
    ]);
    assert.ok(repeated.details.firstAt <= repeated.details.lastAt);
    assert.ok(repeated.details.firstContext.some((operation) => /createRenderPipeline/.test(operation.operation)));
    const pipelineOperation = repeated.details.firstContext.find((operation) => /createRenderPipeline/.test(operation.operation));
    assert.equal(pipelineOperation.details.colorTargetFormats[0], 'rgba16float');
    assert.equal(pipelineOperation.details.vertexBufferLayouts[0].attributes[0].format, 'float32x3');
    assert.equal(pipelineOperation.details.primitive.cullMode, 'back');
    assert.ok(repeated.details.latestContext.some((operation) => /GPUQueue.submit/.test(operation.operation)));
    const renderPassOperation = repeated.details.latestContext.find((operation) => /GPUCommandEncoder.beginRenderPass/.test(operation.operation));
    assert.equal(renderPassOperation.details.colorAttachments[0].texture.texture.format, 'rgba16float');
    assert.equal(renderPassOperation.details.colorAttachments[0].loadOp, 'clear');
    assert.ok(repeated.details.latestContext.some((operation) => /setViewport/.test(operation.operation)));
    assert.ok(repeated.details.latestContext.some((operation) => /setScissorRect/.test(operation.operation)));
    assert.ok(messages.some((message) => message.type === 'gpu-queue-submit-first'));
    const pipelineImplementation = messages.filter((message) => message.type === 'pipeline-implementation').at(-1);
    assert.equal(pipelineImplementation.details.apiCalls.createRenderPipeline, 1);
    assert.equal(pipelineImplementation.details.apiCalls.createRenderPipelineAsync, 1);
    assert.equal(pipelineImplementation.details.workerUrlSyncPipelines, false);
    assert.equal(device.pushCalls, 0, 'diagnostics never push error scopes');
    assert.equal(device.popCalls, 0, 'diagnostics do not change the game’s error-scope balance');
    assert.ok(messages.some((message) => message.type === 'gpu-device-observed' && message.details.uncapturederrorListenerInstalled));
});

test('GPU diagnostics identify RGBA32Float bind-group resources, layouts, sampler filters and enabled features', async () => {
    class FakeGpuTextureView {
        constructor(descriptor) { this.label = descriptor.label || ''; }
    }
    Object.defineProperty(FakeGpuTextureView.prototype, Symbol.toStringTag, { value: 'GPUTextureView' });

    class FakeGpuSampler {
        constructor(descriptor) { this.label = descriptor.label || ''; }
    }
    Object.defineProperty(FakeGpuSampler.prototype, Symbol.toStringTag, { value: 'GPUSampler' });

    class FakeGpuTexture {
        constructor(descriptor) {
            this.label = descriptor.label || '';
            this.width = descriptor.size.width;
            this.height = descriptor.size.height;
            this.depthOrArrayLayers = descriptor.size.depthOrArrayLayers || 1;
            this.mipLevelCount = descriptor.mipLevelCount || 1;
            this.sampleCount = descriptor.sampleCount || 1;
            this.dimension = descriptor.dimension || '2d';
            this.format = descriptor.format;
            this.usage = descriptor.usage || 0;
        }
        createView(descriptor) { return new FakeGpuTextureView(descriptor || {}); }
    }

    class FakeGpuDevice {
        constructor() {
            this.features = new Set(['texture-compression-bc']);
            this.limits = { maxBindGroups: 4 };
            this.listeners = Object.create(null);
            this.lost = new Promise(() => {});
        }
        addEventListener(type, listener) { (this.listeners[type] || (this.listeners[type] = [])).push(listener); }
        createTexture(descriptor) { return new FakeGpuTexture(descriptor); }
        createSampler(descriptor) { return new FakeGpuSampler(descriptor || {}); }
        createBindGroupLayout(descriptor) { return { label: descriptor.label || '' }; }
        createBindGroup(descriptor) { return { label: descriptor.label || '' }; }
        createShaderModule(descriptor) { return { label: descriptor.label || '' }; }
        createPipelineLayout(descriptor) { return { label: descriptor.label || '' }; }
        createRenderPipeline(descriptor) { return { label: descriptor.label || '' }; }
    }
    class FakeGpuAdapter {
        constructor() {
            this.features = new Set(['texture-compression-bc']);
            this.limits = { maxTextureDimension2D: 8192 };
            this.info = { vendor: 'qualcomm', architecture: 'adreno-7xx' };
        }
        requestDevice(descriptor) { this.lastRequest = descriptor; return Promise.resolve(device); }
    }
    const device = new FakeGpuDevice();
    const adapter = new FakeGpuAdapter();
    const gpu = { requestAdapter() { return Promise.resolve(adapter); } };
    const env = createWorkerContext('/wgpu_worker.js?newcatroid_worker_debug=1', {
        self: {
            GPUDevice: FakeGpuDevice,
            GPUAdapter: FakeGpuAdapter,
            GPUTexture: FakeGpuTexture,
            GPUTextureView: FakeGpuTextureView,
            GPU: function () {},
            navigator: { gpu }
        }
    });
    const selectedAdapter = await env.self.navigator.gpu.requestAdapter();
    await selectedAdapter.requestDevice({ requiredFeatures: ['texture-compression-bc'] });

    const texture = device.createTexture({
        label: 'hdr-target',
        size: { width: 1024, height: 512, depthOrArrayLayers: 1 },
        dimension: '2d', mipLevelCount: 4, sampleCount: 1, format: 'rgba32float', usage: 4
    });
    const view = texture.createView({ dimension: '2d', format: 'rgba32float', baseMipLevel: 0, mipLevelCount: 4 });
    const sampler = device.createSampler({ minFilter: 'linear', magFilter: 'linear', mipmapFilter: 'linear' });
    device.createSampler({});
    const layout = device.createBindGroupLayout({ label: 'sample-layout', entries: [
        { binding: 16, visibility: 2, sampler: { type: 'filtering' } },
        { binding: 32, visibility: 2, texture: { sampleType: 'float', viewDimension: '2d', multisampled: false } }
    ] });
    device.createBindGroup({ label: 'sample-group', layout, entries: [
        { binding: 16, resource: sampler },
        { binding: 32, resource: view }
    ] });
    const shader = device.createShaderModule({ label: 'world-fragment', code: 'fn fs() {}' });
    const pipelineLayout = device.createPipelineLayout({ label: 'world-pipeline-layout', bindGroupLayouts: [layout] });
    device.createRenderPipeline({ label: 'world-pipeline', layout: pipelineLayout,
        vertex: { module: shader, entryPoint: 'vsMain' },
        fragment: { module: shader, entryPoint: 'fsMain', targets: [{ format: 'bgra8unorm' }] }
    });

    const validation = Object.assign(new Error('None of the supported sample types (UnfilterableFloat) of Texture RGBA32Float match expected sample type Float'), {
        name: 'GPUValidationError'
    });
    for (const listener of device.listeners.uncapturederror || []) listener({ error: validation });

    const messages = env.channel.messages;
    const adapterMessage = messages.find((message) => message.type === 'gpu-adapter-observed');
    const requestMessage = messages.find((message) => message.type === 'gpu-device-request');
    const deviceMessage = messages.find((message) => message.type === 'gpu-device-observed');
    assert.deepEqual(JSON.parse(JSON.stringify(adapterMessage.details.features)), ['texture-compression-bc']);
    assert.deepEqual(JSON.parse(JSON.stringify(requestMessage.details.requestedFeatures)), ['texture-compression-bc']);
    assert.deepEqual(JSON.parse(JSON.stringify(deviceMessage.details.requestedFeatures)), ['texture-compression-bc']);
    assert.deepEqual(JSON.parse(JSON.stringify(deviceMessage.details.features)), ['texture-compression-bc']);

    const errorGroup = messages.find((message) => message.type === 'webgpu-error-group' && /RGBA32Float/.test(message.details.message));
    assert.ok(errorGroup);
    const layoutOperation = errorGroup.details.firstContext.find((operation) => /GPUDevice.createBindGroupLayout/.test(operation.operation));
    const bindGroupOperation = errorGroup.details.firstContext.find((operation) => /GPUDevice.createBindGroup$/.test(operation.operation));
    assert.equal(layoutOperation.details.textureBindings[0].texture.sampleType, 'float');
    assert.equal(layoutOperation.details.samplerBindings[0].sampler.type, 'filtering');
    assert.match(layoutOperation.details.resourceId, /^bind-layout-\d+$/);
    assert.equal(bindGroupOperation.details.layoutResourceId, layoutOperation.details.resourceId);
    const pipelineLayoutOperation = errorGroup.details.firstContext.find((operation) => /GPUDevice.createPipelineLayout/.test(operation.operation));
    assert.equal(pipelineLayoutOperation.details.bindGroupLayoutResourceIds[0], layoutOperation.details.resourceId);
    const shaderOperation = errorGroup.details.firstContext.find((operation) => /GPUDevice.createShaderModule/.test(operation.operation));
    const pipelineOperation = errorGroup.details.firstContext.find((operation) => /GPUDevice.createRenderPipeline$/.test(operation.operation));
    assert.equal(pipelineOperation.details.pipelineLayoutResourceId, pipelineLayoutOperation.details.resourceId);
    assert.equal(pipelineOperation.details.fragmentShaderResourceId, shaderOperation.details.resourceId);
    assert.match(shaderOperation.details.resourceId, /^shader-module-\d+$/);
    const textureBinding = bindGroupOperation.details.textureBindings[0];
    assert.equal(textureBinding.binding, 32);
    assert.equal(textureBinding.resource.texture.texture.format, 'rgba32float');
    assert.match(textureBinding.resource.texture.texture.resourceId, /^texture-\d+$/);
    assert.match(textureBinding.resource.resourceId, /^view-\d+$/);
    assert.equal(textureBinding.resource.texture.texture.mipLevelCount, 4);
    assert.equal(textureBinding.resource.texture.view.baseMipLevel, '0');
    assert.equal(textureBinding.resource.texture.view.mipLevelCount, '4');
    assert.equal(bindGroupOperation.details.samplerBindings[0].resource.sampler.minFilter, 'linear');
    assert.equal(bindGroupOperation.details.samplerBindings[0].resource.sampler.mipmapFilter, 'linear');
    const samplerOperations = errorGroup.details.firstContext.filter((operation) => /GPUDevice.createSampler/.test(operation.operation));
    assert.equal(samplerOperations.at(-1).details.minFilter, 'nearest (default)');
    assert.equal(samplerOperations.at(-1).details.magFilter, 'nearest (default)');
    assert.equal(samplerOperations.at(-1).details.mipmapFilter, 'nearest (default)');
    const textureCreate = errorGroup.details.firstContext.find((operation) => /GPUDevice.createTexture/.test(operation.operation));
    assert.equal(textureCreate.details.resourceId, textureBinding.resource.texture.texture.resourceId);
});

test('GPU worker reports alpha-fix URL propagation separately from shim and shader-variant counters', () => {
    const stats = {
        enabled: true,
        hookInstalled: true,
        alphaCoverageVariantsSeen: 7,
        nonVec4AlphaTestsBypassed: 2,
        vec4AlphaTestsPreserved: 5,
        firstNonVec4OutputType: 'f32',
        lastObservedAt: 12345
    };
    const env = createWorkerContext('/wgpu_worker.js?newcatroid_worker_debug=1&syncpipelines=1&newcatroid_alpha_fix=1', {
        self: { __newcatroidAlphaCompatibility: stats }
    });
    const config = env.channel.messages.find((message) => message.type === 'gpu-worker-config');
    const bootstrap = env.channel.messages.find((message) => message.type === 'worker-bootstrap');
    assert.equal(config.details.workerUrlSyncPipelines, true);
    assert.equal(config.details.workerUrlAlphaFix, true);
    assert.equal(config.details.shaderCompatibility.hookInstalled, true);
    assert.equal(config.details.shaderCompatibility.alphaCoverageVariantsSeen, 7);
    assert.equal(config.details.shaderCompatibility.nonVec4AlphaTestsBypassed, 2);
    assert.equal(bootstrap.details.workerUrlAlphaFix, true);
    assert.equal(bootstrap.details.shaderCompatibility.firstNonVec4OutputType, 'f32');

    env.intervals[0]();
    const heartbeat = env.channel.messages.filter((message) => message.type === 'worker-heartbeat').at(-1);
    assert.equal(heartbeat.details.shaderCompatibility.vec4AlphaTestsPreserved, 5);
    assert.equal(heartbeat.details.pipeline.workerUrlAlphaFix, true);
});

test('fetch observer groups audio-like resource status without exporting request URLs or query values', async () => {
    const response = {
        status: 404,
        ok: false,
        headers: { get(name) { return name === 'content-type' ? 'text/plain' : name === 'content-length' ? '18' : null; } }
    };
    const env = createWorkerContext('/io_worker.js?newcatroid_worker_debug=1', {
        self: { fetch() { return Promise.resolve(response); } }
    });
    await env.self.fetch('http://local.test:8080/audio/music.ogg?token=private-query');
    const failure = env.channel.messages.find((message) => message.type === 'resource-fetch-failure');
    assert.ok(failure);
    assert.equal(failure.details.kind, 'audio-resource');
    assert.equal(failure.details.status, 404);
    assert.doesNotMatch(JSON.stringify(failure), /music\.ogg|private-query|token=/);
    env.intervals[0]();
    const heartbeat = env.channel.messages.filter((message) => message.type === 'worker-heartbeat').at(-1);
    assert.ok(heartbeat.details.resourceGroups.some((group) => group.kind === 'audio-resource' && group.status === 404 && group.count === 1));
});
