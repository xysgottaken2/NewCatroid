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
    var workerStartedAt = Date.now();
    var workerSessionId = 'worker-' + workerStartedAt;
    var workerSequence = 0;
    var lastBeat = workerNow();
    var recentWorkerContext = [];
    var recentGpuOperations = [];
    var hotGpuOperations = Object.create(null);
    var gpuOperationCounts = Object.create(null);
    var pipelineApiCalls = { createRenderPipeline: 0, createRenderPipelineAsync: 0, createComputePipeline: 0, createComputePipelineAsync: 0 };
    var lastPipelineImplementationSentAt = 0;
    var gpuCanvasState = { configureCount: 0, textureCount: 0, firstConfiguredAt: null, firstTextureAt: null, lastSize: null, lastConfiguration: null };
    var gpuErrorGroups = new Map();
    var gpuErrorSequence = 0;
    var pendingGpuErrorFlush = false;
    var pendingGpuErrorFlushTimer = null;
    var memorySnapshots = new WeakMap();
    var gpuTextureViewSnapshots = new WeakMap();
    var observedMemories = new WeakSet();
    var resourceGroups = new Map();
    var observedGpuDevices = new WeakSet();
    var workerQuery = { syncPipelines: false };

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

    function safeText(value, maxLength) {
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
            .replace(/(\b(?:user\s*(?:name|id)?|player\s*(?:name|id)?|account\s*(?:id)?|profile(?:\s*id)?|display\s*name|email)\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^,;\s}\]]+)/gi, '$1[REDACTED]')
            .replace(/(?:\/data\/user\/\d+\/|\/storage\/emulated\/\d+\/|\/sdcard\/|\/home\/[^/\s]+\/|[A-Z]:\\Users\\[^\\\s]+\\)[^\s]*/gi, '[LOCAL_PATH]')
            .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[EMAIL]')
            .replace(/https?:\/\/[^\s"'<>]+/gi, function (match) { return safeUrl(match); })
            .slice(0, maxLength || 4000);
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
    workerSessionId = component + '-' + workerStartedAt;

    try {
        if (typeof BroadcastChannel === 'function') channel = new BroadcastChannel('newcatroid-debug');
    } catch (ignored) { }

    function send(type, details, id) {
        if (!channel) return;
        try {
            var now = Date.now();
            channel.postMessage({
                source: component,
                type: type,
                at: now,
                elapsedMs: Math.max(0, now - workerStartedAt),
                sequence: ++workerSequence,
                sessionId: workerSessionId,
                id: id || undefined,
                details: details || {}
            });
        } catch (ignored) { }
    }

    function relevant(text) {
        return /(error|failed|exception|reject|warning|warn|assert|shader|pipeline|webgpu|webgl|gpu|wasm|worker|wait|stall|timeout|fence|ring|framebuffer|context|initiali[sz]|loaded|world|readback|memory|allocator|arena|heap|oom|audio|leaderboard|resize|viewport|canvas|present|adapter|limits|resource)/i.test(text);
    }

    function rememberContext(category, message) {
        var text = safeText(message, 8000).trim();
        if (!text) return;
        recentWorkerContext.push({ at: Date.now(), category: safeText(category, 120), message: text });
        if (recentWorkerContext.length > 50) recentWorkerContext.splice(0, recentWorkerContext.length - 50);
    }

    function describeError(error, fallback, maxLength) {
        var name = 'Error';
        var message = '';
        var stack = '';
        try {
            if (error && typeof error === 'object') {
                name = safeText(error.name || (error.constructor && error.constructor.name) || name, 160);
                message = safeText(error.message || fallback || String(error), maxLength || 12000);
                stack = typeof error.stack === 'string' ? safeText(error.stack, maxLength || 16000) : '';
            } else {
                message = safeText(error == null ? fallback || '' : error, maxLength || 12000);
            }
        } catch (ignored) {
            message = safeText(fallback || '[unreadable error]', maxLength || 12000);
        }
        if (!message && fallback) message = safeText(fallback, maxLength || 12000);
        return { name: name, message: message, stack: stack };
    }

    function memoryInfo(memory) {
        try {
            if (!memory || typeof memory !== 'object') return null;
            var buffer = memory.buffer && typeof memory.buffer.byteLength === 'number' ? memory.buffer : memory;
            if (typeof buffer.byteLength !== 'number') return null;
            var bytes = buffer.byteLength;
            var shared = false;
            try { shared = Object.prototype.toString.call(buffer) === '[object SharedArrayBuffer]'; } catch (ignored) { }
            return {
                metric: memory.buffer ? 'WebAssembly.Memory.buffer.byteLength (linear-memory capacity)' : 'buffer.byteLength (memory type not confirmed)',
                bufferBytes: bytes,
                bufferMiB: Math.round(bytes / 1048576 * 100) / 100,
                shared: shared
            };
        } catch (ignored) {
            return null;
        }
    }

    function trackWasmMemory(memory, origin) {
        if (!memory || typeof memory !== 'object') return;
        var snapshot = memoryInfo(memory);
        if (!snapshot) return;
        var previous = memorySnapshots.get(memory);
        if (observedMemories.has(memory) && previous && previous.bufferBytes === snapshot.bufferBytes) return;
        observedMemories.add(memory);
        memorySnapshots.set(memory, snapshot);
        send('wasm-memory', {
            origin: safeText(origin || 'WebAssembly memory observation', 180),
            metric: snapshot.metric,
            bufferBytes: snapshot.bufferBytes,
            bufferMiB: snapshot.bufferMiB,
            shared: snapshot.shared
        });
    }

    function captureMemories(value, origin) {
        try {
            if (!value || typeof value !== 'object') return;
            if (value.buffer && typeof value.buffer.byteLength === 'number' && typeof value.grow === 'function') {
                trackWasmMemory(value, origin);
                return;
            }
            Object.keys(value).slice(0, 16).forEach(function (namespaceName) {
                var namespace = value[namespaceName];
                if (!namespace || typeof namespace !== 'object') return;
                Object.keys(namespace).slice(0, 32).forEach(function (importName) {
                    var candidate = namespace[importName];
                    if (candidate && candidate.buffer && typeof candidate.buffer.byteLength === 'number' && typeof candidate.grow === 'function') {
                        trackWasmMemory(candidate, origin + ' ' + namespaceName + '.' + importName);
                    }
                });
            });
        } catch (ignored) { }
    }

    function gpuCanvasSize(context) {
        try {
            var canvas = context && context.canvas;
            if (!canvas) return null;
            return {
                width: Number(canvas.width) || 0,
                height: Number(canvas.height) || 0,
                cssWidth: Number(canvas.clientWidth) || null,
                cssHeight: Number(canvas.clientHeight) || null
            };
        } catch (ignored) {
            return null;
        }
    }

    function objectLabel(value) {
        try { return value && value.label ? safeText(value.label, 180) : ''; }
        catch (ignored) { return ''; }
    }

    function gpuTextureSnapshot(texture) {
        if (!texture || typeof texture !== 'object') return null;
        return {
            label: objectLabel(texture),
            width: Number(texture.width) || null,
            height: Number(texture.height) || null,
            depthOrArrayLayers: Number(texture.depthOrArrayLayers) || null,
            mipLevelCount: Number(texture.mipLevelCount) || null,
            sampleCount: Number(texture.sampleCount) || null,
            dimension: safeText(texture.dimension || '', 40),
            format: safeText(texture.format || '', 100),
            usage: Number(texture.usage) || null
        };
    }

    function gpuTextureViewSnapshot(view) {
        try { return view && gpuTextureViewSnapshots ? gpuTextureViewSnapshots.get(view) || null : null; }
        catch (ignored) { return null; }
    }

    function bindResourceSummary(resource) {
        if (!resource || typeof resource !== 'object') return { kind: resource == null ? 'null' : typeof resource };
        try {
            if (resource.buffer) return {
                kind: 'buffer binding', label: objectLabel(resource.buffer), offset: Number(resource.offset) || 0,
                size: Number(resource.size) || null
            };
            if (resource.sampler) return { kind: 'sampler', label: objectLabel(resource.sampler) };
            if (resource.textureView) return {
                kind: 'texture view', label: objectLabel(resource.textureView), texture: gpuTextureViewSnapshot(resource.textureView)
            };
            if (resource.externalTexture) return { kind: 'external texture', label: objectLabel(resource.externalTexture) };
            var objectType = '';
            try { objectType = Object.prototype.toString.call(resource); } catch (ignored) { }
            if (/GPUSampler/i.test(objectType)) return { kind: 'sampler', label: objectLabel(resource) };
            if (/GPUTextureView/i.test(objectType)) return { kind: 'texture view', label: objectLabel(resource), texture: gpuTextureViewSnapshot(resource) };
            if (/GPUExternalTexture/i.test(objectType)) return { kind: 'external texture', label: objectLabel(resource) };
            return { kind: 'resource object', label: objectLabel(resource), objectType: safeText(objectType, 120) };
        } catch (ignored) {
            return { kind: 'resource summary unavailable' };
        }
    }

    function bindGroupLayoutEntrySummary(entry) {
        var result = {
            binding: Number(entry && entry.binding),
            visibility: Number(entry && entry.visibility) || 0
        };
        ['buffer', 'sampler', 'texture', 'storageTexture', 'externalTexture'].forEach(function (kind) {
            var value = entry && entry[kind];
            if (!value) return;
            var summary = {};
            ['type', 'viewDimension', 'sampleType', 'format', 'access', 'multisampled', 'hasDynamicOffset', 'minBindingSize'].forEach(function (key) {
                if (value[key] !== undefined && value[key] !== null) summary[key] = safeText(value[key], 100);
            });
            result[kind] = summary;
        });
        return result;
    }

    function renderAttachmentSummary(attachment, index) {
        if (!attachment) return null;
        var clear = attachment.clearValue;
        var clearValue = null;
        if (clear && typeof clear === 'object') {
            if (Array.isArray(clear) || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(clear))) {
                clearValue = Array.prototype.slice.call(clear, 0, 4).map(function (value) { return Number(value); });
            } else {
                clearValue = {};
                ['r', 'g', 'b', 'a'].forEach(function (key) {
                    if (typeof clear[key] === 'number') clearValue[key] = clear[key];
                });
            }
        } else if (typeof clear === 'number') {
            clearValue = clear;
        }
        var view = attachment.view;
        var textureView = gpuTextureViewSnapshot(view);
        return {
            index: index,
            viewLabel: objectLabel(view),
            texture: textureView,
            resolveTargetLabel: objectLabel(attachment.resolveTarget),
            resolveTargetTexture: gpuTextureViewSnapshot(attachment.resolveTarget),
            loadOp: safeText(attachment.loadOp || '', 40),
            storeOp: safeText(attachment.storeOp || '', 40),
            clearValue: clearValue
        };
    }

    function operationDetails(interfaceName, methodName, args, receiver) {
        var descriptor = args && args[0] && typeof args[0] === 'object' ? args[0] : {};
        var detail = {};
        if (methodName === 'createShaderModule') {
            detail.label = objectLabel(descriptor);
            detail.codeLength = typeof descriptor.code === 'string' ? descriptor.code.length : null;
        } else if (/create(Render|Compute)Pipeline/.test(methodName)) {
            detail.label = objectLabel(descriptor);
            detail.layout = typeof descriptor.layout === 'string' ? descriptor.layout : descriptor.layout ? 'explicit' : 'not reported';
            if (descriptor.vertex) {
                detail.vertexEntryPoint = safeText(descriptor.vertex.entryPoint || '', 120);
                detail.vertexShaderLabel = objectLabel(descriptor.vertex.module);
                detail.vertexBufferLayouts = (descriptor.vertex.buffers || []).slice(0, 8).map(function (buffer) {
                    return {
                        arrayStride: Number(buffer && buffer.arrayStride) || 0,
                        stepMode: safeText(buffer && buffer.stepMode || 'vertex', 40),
                        attributes: (buffer && buffer.attributes || []).slice(0, 16).map(function (attribute) {
                            return { format: safeText(attribute && attribute.format || '', 100), offset: Number(attribute && attribute.offset) || 0,
                                shaderLocation: Number(attribute && attribute.shaderLocation) || 0 };
                        })
                    };
                });
            }
            if (descriptor.fragment) {
                detail.fragmentEntryPoint = safeText(descriptor.fragment.entryPoint || '', 120);
                detail.fragmentShaderLabel = objectLabel(descriptor.fragment.module);
                detail.colorTargetFormats = (descriptor.fragment.targets || []).slice(0, 12).map(function (target) {
                    return safeText(target && target.format || 'unknown', 100);
                });
                detail.colorTargets = (descriptor.fragment.targets || []).slice(0, 12).map(function (target) {
                    var blend = target && target.blend;
                    function component(value) {
                        if (!value) return null;
                        return { operation: safeText(value.operation || '', 40), srcFactor: safeText(value.srcFactor || '', 60), dstFactor: safeText(value.dstFactor || '', 60) };
                    }
                    return {
                        format: safeText(target && target.format || 'unknown', 100),
                        writeMask: target && target.writeMask != null ? Number(target.writeMask) : null,
                        blend: blend ? { color: component(blend.color), alpha: component(blend.alpha) } : null
                    };
                });
            }
            if (descriptor.primitive) {
                detail.primitive = {
                    topology: safeText(descriptor.primitive.topology || 'triangle-list', 60),
                    frontFace: safeText(descriptor.primitive.frontFace || 'ccw', 40),
                    cullMode: safeText(descriptor.primitive.cullMode || 'none', 40),
                    stripIndexFormat: safeText(descriptor.primitive.stripIndexFormat || '', 40)
                };
            }
            if (descriptor.compute) {
                detail.computeEntryPoint = safeText(descriptor.compute.entryPoint || '', 120);
                detail.computeShaderLabel = objectLabel(descriptor.compute.module);
            }
            if (descriptor.depthStencil) detail.depthStencil = {
                format: safeText(descriptor.depthStencil.format || 'unknown', 100),
                depthWriteEnabled: !!descriptor.depthStencil.depthWriteEnabled,
                depthCompare: safeText(descriptor.depthStencil.depthCompare || 'always', 40),
                stencilReadMask: Number(descriptor.depthStencil.stencilReadMask) || 0,
                stencilWriteMask: Number(descriptor.depthStencil.stencilWriteMask) || 0
            };
            if (descriptor.multisample) detail.sampleCount = Number(descriptor.multisample.count) || null;
        } else if (methodName === 'createBindGroup') {
            detail.label = objectLabel(descriptor);
            detail.bindingCount = Array.isArray(descriptor.entries) ? descriptor.entries.length : null;
            detail.bindings = (descriptor.entries || []).slice(0, 32).map(function (entry) {
                return { binding: Number(entry && entry.binding), resource: bindResourceSummary(entry && entry.resource) };
            });
        } else if (methodName === 'createBindGroupLayout') {
            detail.label = objectLabel(descriptor);
            detail.bindingCount = Array.isArray(descriptor.entries) ? descriptor.entries.length : null;
            detail.bindings = (descriptor.entries || []).slice(0, 32).map(bindGroupLayoutEntrySummary);
        } else if (methodName === 'createPipelineLayout') {
            detail.label = objectLabel(descriptor);
            detail.bindGroupLayoutCount = Array.isArray(descriptor.bindGroupLayouts) ? descriptor.bindGroupLayouts.length : null;
            detail.bindGroupLayoutLabels = (descriptor.bindGroupLayouts || []).slice(0, 16).map(objectLabel);
        } else if (methodName === 'createSampler') {
            detail.label = objectLabel(descriptor);
            ['addressModeU', 'addressModeV', 'addressModeW', 'magFilter', 'minFilter', 'mipmapFilter', 'compare'].forEach(function (key) {
                if (descriptor[key] != null) detail[key] = safeText(descriptor[key], 80);
            });
            detail.lodMinClamp = Number(descriptor.lodMinClamp) || 0;
            detail.lodMaxClamp = Number(descriptor.lodMaxClamp) || 0;
        } else if (methodName === 'createTexture') {
            var size = descriptor.size || {};
            detail.label = objectLabel(descriptor);
            detail.dimension = safeText(descriptor.dimension || '2d', 40);
            detail.format = safeText(descriptor.format || 'unknown', 100);
            detail.width = Number(size.width || size[0]) || null;
            detail.height = Number(size.height || size[1]) || null;
            detail.depthOrArrayLayers = Number(size.depthOrArrayLayers || size[2]) || null;
            detail.mipLevelCount = Number(descriptor.mipLevelCount) || 1;
            detail.sampleCount = Number(descriptor.sampleCount) || 1;
            detail.usage = Number(descriptor.usage) || null;
            detail.viewFormats = Array.isArray(descriptor.viewFormats) ? descriptor.viewFormats.slice(0, 16).map(function (format) { return safeText(format, 100); }) : [];
        } else if (methodName === 'createView') {
            detail.texture = gpuTextureSnapshot(receiver);
            detail.view = {};
            ['format', 'dimension', 'aspect', 'baseMipLevel', 'mipLevelCount', 'baseArrayLayer', 'arrayLayerCount'].forEach(function (key) {
                if (descriptor[key] !== undefined && descriptor[key] !== null) detail.view[key] = safeText(descriptor[key], 100);
            });
        } else if (methodName === 'createBuffer') {
            detail.label = objectLabel(descriptor);
            detail.size = Number(descriptor.size) || null;
            detail.usage = Number(descriptor.usage) || null;
            detail.mappedAtCreation = !!descriptor.mappedAtCreation;
        } else if (methodName === 'beginRenderPass') {
            detail.label = objectLabel(descriptor);
            detail.colorAttachmentCount = Array.isArray(descriptor.colorAttachments)
                ? descriptor.colorAttachments.filter(function (attachment) { return !!attachment; }).length : 0;
            detail.colorAttachments = (descriptor.colorAttachments || []).slice(0, 12).map(function (attachment, index) {
                return renderAttachmentSummary(attachment, index);
            }).filter(function (attachment) { return !!attachment; });
            var depthAttachment = descriptor.depthStencilAttachment;
            detail.depthStencilAttachment = depthAttachment ? {
                viewLabel: objectLabel(depthAttachment.view),
                texture: gpuTextureViewSnapshot(depthAttachment.view),
                depthLoadOp: safeText(depthAttachment.depthLoadOp || '', 40),
                depthStoreOp: safeText(depthAttachment.depthStoreOp || '', 40),
                depthReadOnly: !!depthAttachment.depthReadOnly,
                stencilLoadOp: safeText(depthAttachment.stencilLoadOp || '', 40),
                stencilStoreOp: safeText(depthAttachment.stencilStoreOp || '', 40),
                stencilReadOnly: !!depthAttachment.stencilReadOnly
            } : null;
        } else if (methodName === 'beginComputePass' || methodName === 'createCommandEncoder') {
            detail.label = objectLabel(descriptor);
        } else if (methodName === 'setPipeline') {
            detail.pipelineLabel = objectLabel(args[0]);
        } else if (methodName === 'setBindGroup') {
            detail.index = Number(args[0]);
            detail.bindGroupLabel = objectLabel(args[1]);
            detail.dynamicOffsetCount = args[2] && typeof args[2].length === 'number' ? Number(args[2].length) : 0;
            detail.dynamicOffsets = args[2] && typeof args[2].length === 'number' ? Array.prototype.slice.call(args[2], 0, 16).map(function (value) { return Number(value); }) : [];
        } else if (methodName === 'setVertexBuffer') {
            detail.slot = Number(args[0]);
            detail.bufferLabel = objectLabel(args[1]);
            detail.offset = Number(args[2]) || 0;
            detail.size = args.length > 3 ? Number(args[3]) || null : null;
        } else if (methodName === 'setIndexBuffer') {
            detail.bufferLabel = objectLabel(args[0]);
            detail.format = safeText(args[1] || 'unknown', 40);
            detail.offset = Number(args[2]) || 0;
            detail.size = args.length > 3 ? Number(args[3]) || null : null;
        } else if (methodName === 'draw') {
            detail.vertexCount = Number(args[0]) || 0;
            detail.instanceCount = args.length > 1 ? Number(args[1]) || 0 : 1;
            detail.firstVertex = args.length > 2 ? Number(args[2]) || 0 : 0;
            detail.firstInstance = args.length > 3 ? Number(args[3]) || 0 : 0;
        } else if (methodName === 'drawIndexed') {
            detail.indexCount = Number(args[0]) || 0;
            detail.instanceCount = args.length > 1 ? Number(args[1]) || 0 : 1;
            detail.firstIndex = args.length > 2 ? Number(args[2]) || 0 : 0;
            detail.baseVertex = args.length > 3 ? Number(args[3]) || 0 : 0;
            detail.firstInstance = args.length > 4 ? Number(args[4]) || 0 : 0;
        } else if (methodName === 'drawIndirect' || methodName === 'drawIndexedIndirect') {
            detail.bufferLabel = objectLabel(args[0]);
            detail.indirectOffset = Number(args[1]) || 0;
        } else if (methodName === 'dispatchWorkgroupsIndirect') {
            detail.bufferLabel = objectLabel(args[0]);
            detail.indirectOffset = Number(args[1]) || 0;
        } else if (methodName === 'dispatchWorkgroups') {
            detail.workgroups = [Number(args[0]) || 0, Number(args[1]) || 1, Number(args[2]) || 1];
        } else if (methodName === 'setViewport' || methodName === 'setScissorRect') {
            detail.values = args.slice(0, 6).map(function (value) { return Number(value); });
        } else if (methodName === 'copyBufferToBuffer') {
            detail.sourceBufferLabel = objectLabel(args[0]);
            detail.sourceOffset = Number(args[1]) || 0;
            detail.destinationBufferLabel = objectLabel(args[2]);
            detail.destinationOffset = Number(args[3]) || 0;
            detail.size = Number(args[4]) || 0;
        } else if (methodName === 'copyBufferToTexture' || methodName === 'copyTextureToBuffer' || methodName === 'copyTextureToTexture') {
            detail.source = args[0] && args[0].texture ? { label: objectLabel(args[0].texture), texture: gpuTextureSnapshot(args[0].texture) } : { label: objectLabel(args[0]) };
            detail.destination = args[1] && args[1].texture ? { label: objectLabel(args[1].texture), texture: gpuTextureSnapshot(args[1].texture) } : { label: objectLabel(args[1]) };
            detail.extent = args[2] || null;
        } else if (methodName === 'clearBuffer') {
            detail.bufferLabel = objectLabel(args[0]);
            detail.offset = Number(args[1]) || 0;
            detail.size = args.length > 2 ? Number(args[2]) || null : null;
        } else if (methodName === 'writeBuffer') {
            detail.bufferLabel = objectLabel(args[0]);
            detail.bufferOffset = Number(args[1]) || 0;
            detail.dataByteLength = args[2] && Number(args[2].byteLength || args[2].length) || null;
            detail.dataOffset = args.length > 3 ? Number(args[3]) || 0 : 0;
            detail.dataSize = args.length > 4 ? Number(args[4]) || null : null;
        } else if (methodName === 'writeTexture' || methodName === 'copyExternalImageToTexture') {
            var destination = args[0] || {};
            var source = args[1] || {};
            detail.destinationTextureLabel = objectLabel(destination.texture);
            detail.destinationTexture = gpuTextureSnapshot(destination.texture);
            detail.destinationOrigin = destination.origin || null;
            detail.extent = args[3] || null;
            if (methodName === 'writeTexture') {
                detail.dataByteLength = source && Number(source.byteLength || source.length) || null;
                detail.dataLayout = args[2] || null;
            } else {
                detail.sourceImageSize = source && source.source ? {
                    width: Number(source.source.width) || null,
                    height: Number(source.source.height) || null
                } : null;
            }
        } else if (methodName === 'submit') {
            detail.commandBufferCount = Array.isArray(args[0]) ? args[0].length : null;
            detail.commandBufferLabels = Array.isArray(args[0]) ? args[0].slice(0, 16).map(objectLabel) : [];
        } else if (methodName === 'configure') {
            detail.format = safeText(descriptor.format || 'unknown', 100);
            detail.alphaMode = safeText(descriptor.alphaMode || 'default', 60);
            detail.colorSpace = safeText(descriptor.colorSpace || 'default', 60);
            detail.usage = Number(descriptor.usage) || null;
            detail.viewFormats = Array.isArray(descriptor.viewFormats) ? descriptor.viewFormats.slice(0, 12).map(function (x) { return safeText(x, 100); }) : [];
            detail.canvas = gpuCanvasSize(receiver);
        } else if (methodName === 'getCurrentTexture') {
            detail.canvas = gpuCanvasSize(receiver);
        } else if (methodName === 'destroy') {
            detail.resourceLabel = objectLabel(receiver);
        } else if (methodName === 'pushErrorScope') {
            detail.scopeType = safeText(args[0] || 'unknown', 80);
        }
        return detail;
    }

    function snapshotGpuOperations() {
        return recentGpuOperations.slice(-16).map(function (entry) {
            return {
                sequence: entry.sequence,
                at: entry.at,
                lastAt: entry.lastAt,
                operation: entry.operation,
                count: entry.count || 1,
                details: entry.details
            };
        });
    }

    function noteGpuOperation(interfaceName, methodName, args, receiver) {
        var operation = interfaceName + '.' + methodName;
        var count = (gpuOperationCounts[operation] || 0) + 1;
        gpuOperationCounts[operation] = count;
        if (methodName === 'createRenderPipeline' || methodName === 'createRenderPipelineAsync' ||
            methodName === 'createComputePipeline' || methodName === 'createComputePipelineAsync') {
            pipelineApiCalls[methodName] = (pipelineApiCalls[methodName] || 0) + 1;
        }
        var hot = methodName === 'draw' || methodName === 'drawIndexed' || methodName === 'dispatchWorkgroups' ||
            methodName === 'submit' || methodName === 'getCurrentTexture' ||
            (interfaceName === 'GPURenderPassEncoder' && /^(setPipeline|setBindGroup|setVertexBuffer|setIndexBuffer|setViewport|setScissorRect)$/.test(methodName));
        var veryHot = methodName === 'draw' || methodName === 'drawIndexed' ||
            (interfaceName === 'GPURenderPassEncoder' && /^(setPipeline|setBindGroup|setVertexBuffer|setIndexBuffer)$/.test(methodName));
        var entry = hot ? hotGpuOperations[operation] : null;
        if (veryHot && count % 128 !== 1 && entry) {
            entry.count++;
            return entry;
        }
        var detail = {};
        try { detail = operationDetails(interfaceName, methodName, args, receiver); }
        catch (ignored) { detail = { summaryUnavailable: true }; }
        var now = Date.now();
        var elapsed = workerNow();
        if (entry && elapsed - entry.lastElapsedMs < 1000) {
            entry.count++;
            entry.lastAt = now;
            entry.lastElapsedMs = elapsed;
            entry.details = detail;
        } else {
            entry = { sequence: count, at: now, lastAt: now, lastElapsedMs: elapsed, operation: operation, count: 1, details: detail };
            recentGpuOperations.push(entry);
            if (recentGpuOperations.length > 40) recentGpuOperations.splice(0, recentGpuOperations.length - 40);
            if (hot) hotGpuOperations[operation] = entry;
        }
        if (methodName === 'createRenderPipeline' || methodName === 'createRenderPipelineAsync' ||
            methodName === 'createComputePipeline' || methodName === 'createComputePipelineAsync') {
            if (count === 1 || elapsed - lastPipelineImplementationSentAt >= 1000) {
                lastPipelineImplementationSentAt = elapsed;
                send('pipeline-implementation', pipelineImplementationSnapshot());
            }
        }
        return entry;
    }

    function pipelineImplementationSnapshot() {
        return {
            workerUrlSyncPipelines: workerQuery.syncPipelines,
            apiCalls: {
                createRenderPipeline: pipelineApiCalls.createRenderPipeline,
                createRenderPipelineAsync: pipelineApiCalls.createRenderPipelineAsync,
                createComputePipeline: pipelineApiCalls.createComputePipeline,
                createComputePipelineAsync: pipelineApiCalls.createComputePipelineAsync
            },
            synchronousRenderPipelineCallObserved: pipelineApiCalls.createRenderPipeline > 0,
            asynchronousRenderPipelineCallObserved: pipelineApiCalls.createRenderPipelineAsync > 0,
            recentOperations: snapshotGpuOperations()
        };
    }

    function flushGpuErrorGroups() {
        pendingGpuErrorFlush = false;
        if (pendingGpuErrorFlushTimer != null) {
            try { clearTimeout(pendingGpuErrorFlushTimer); } catch (ignored) { }
            pendingGpuErrorFlushTimer = null;
        }
        gpuErrorGroups.forEach(function (group) {
            if (group.sentCount === group.count) return;
            group.sentCount = group.count;
            send('webgpu-error-group', {
                groupId: group.id,
                type: group.type,
                message: group.message,
                sourceKind: group.sourceKind,
                sourceKinds: group.sourceKinds || [group.sourceKind],
                sourceKindCounts: group.sourceKindCounts || {},
                count: group.count,
                firstAt: group.firstAt,
                lastAt: group.lastAt,
                firstContext: group.firstContext,
                latestContext: group.latestContext,
                firstOperationCounts: group.firstOperationCounts,
                latestOperationCounts: group.latestOperationCounts
            });
        });
    }

    function recordGpuError(error, sourceKind, operationEntry, fallback) {
        var described = describeError(error, fallback, 12000);
        var type = safeText(described.name || 'GPUError', 160);
        var message = safeText(described.message || fallback || '[WebGPU error without a message]', 12000);
        var key = type + '\n' + message;
        var now = Date.now();
        var group = gpuErrorGroups.get(key);
        var context = snapshotGpuOperations();
        if (!group) {
            if (gpuErrorGroups.size >= 100) {
                key = 'additional-unique-webgpu-errors';
                group = gpuErrorGroups.get(key);
                if (!group) {
                    group = { id: 'gpu-error-overflow', type: 'additional unique GPU errors', message: 'More than 100 distinct WebGPU errors; individual details are bounded.', sourceKind: 'overflow', sourceKinds: [], sourceKindCounts: {}, count: 0, firstAt: now, lastAt: now, firstContext: context, latestContext: context, firstOperationCounts: Object.assign({}, gpuOperationCounts), latestOperationCounts: Object.assign({}, gpuOperationCounts), sentCount: 0 };
                    gpuErrorGroups.set(key, group);
                }
            } else {
                group = {
                    id: 'gpu-error-' + (++gpuErrorSequence),
                    type: type,
                    message: message,
                    sourceKind: safeText(sourceKind || 'WebGPU error', 160),
                    sourceKinds: [],
                    sourceKindCounts: {},
                    count: 0,
                    firstAt: now,
                    lastAt: now,
                    firstContext: context,
                    latestContext: context,
                    firstOperationCounts: Object.assign({}, gpuOperationCounts),
                    latestOperationCounts: Object.assign({}, gpuOperationCounts),
                    sentCount: 0
                };
                gpuErrorGroups.set(key, group);
            }
        }
        group.count++;
        var sourceKindValue = safeText(sourceKind || 'WebGPU error', 160);
        if (!group.sourceKinds) group.sourceKinds = [];
        if (group.sourceKinds.indexOf(sourceKindValue) === -1) group.sourceKinds.push(sourceKindValue);
        if (!group.sourceKindCounts) group.sourceKindCounts = {};
        group.sourceKindCounts[sourceKindValue] = (group.sourceKindCounts[sourceKindValue] || 0) + 1;
        group.lastAt = now;
        group.latestContext = context;
        group.latestOperationCounts = Object.assign({}, gpuOperationCounts);
        if (operationEntry && context.length === 0) group.latestContext = [operationEntry];
        rememberContext('WebGPU ' + type, message);
        if (group.count === 1) {
            // Publish the first instance immediately so a subsequent worker
            // crash cannot erase the first diagnostic; repeated instances are
            // batched briefly and retain their exact count/first/last times.
            flushGpuErrorGroups();
        } else if (!pendingGpuErrorFlush) {
            pendingGpuErrorFlush = true;
            try { pendingGpuErrorFlushTimer = setTimeout(flushGpuErrorGroups, 200); }
            catch (ignored) { flushGpuErrorGroups(); }
        }
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

    function replaceMethod(holder, methodName, makeWrapper) {
        try {
            var target = holder;
            var descriptor = null;
            while (target && !descriptor) {
                descriptor = Object.getOwnPropertyDescriptor(target, methodName);
                if (!descriptor) target = Object.getPrototypeOf(target);
            }
            if (!target || !descriptor || typeof descriptor.value !== 'function') return false;
            if (descriptor.value.__newcatroidDiagnostics === true) return true;
            var wrapped = makeWrapper(descriptor.value);
            try { Object.defineProperty(wrapped, '__newcatroidDiagnostics', { value: true }); } catch (ignored) { }
            Object.defineProperty(target, methodName, Object.assign({}, descriptor, { value: wrapped }));
            return true;
        } catch (ignored) {
            return false;
        }
    }

    function shaderModules(descriptor) {
        var modules = [];
        try {
            if (descriptor && descriptor.vertex && descriptor.vertex.module) modules.push({ stage: 'vertex', module: descriptor.vertex.module });
            if (descriptor && descriptor.fragment && descriptor.fragment.module) modules.push({ stage: 'fragment', module: descriptor.fragment.module });
            if (descriptor && descriptor.compute && descriptor.compute.module) modules.push({ stage: 'compute', module: descriptor.compute.module });
        } catch (ignored) { }
        return modules;
    }

    function inspectShaderCompilation(descriptor, reason, operationEntry) {
        shaderModules(descriptor).forEach(function (item) {
            try {
                if (!item.module || typeof item.module.getCompilationInfo !== 'function') return;
                Promise.resolve(item.module.getCompilationInfo()).then(function (info) {
                    var messages = info && Array.isArray(info.messages) ? info.messages : [];
                    messages.filter(function (message) { return message && message.type && message.type !== 'info'; })
                        .slice(0, 30).forEach(function (message) {
                            var text = safeText(message.message || 'shader compilation diagnostic', 8000);
                            var line = Number(message.lineNum) || 0;
                            var column = Number(message.linePos) || 0;
                            recordGpuError({
                                name: 'GPUCompilationMessage (' + safeText(message.type, 40) + ')',
                                message: item.stage + ' shader, line ' + line + ', column ' + column + ': ' + text
                            }, 'getCompilationInfo after ' + safeText(reason, 180), operationEntry);
                        });
                }, function (error) {
                    rememberContext('shader compilation info observer', safeText(error, 3000));
                });
            } catch (ignored) { }
        });
    }

    function notifyCanvasConfiguration(context, descriptor) {
        var size = gpuCanvasSize(context);
        var configuration = {
            format: safeText(descriptor && descriptor.format || 'unknown', 100),
            alphaMode: safeText(descriptor && descriptor.alphaMode || 'default', 60),
            colorSpace: safeText(descriptor && descriptor.colorSpace || 'default', 60),
            usage: Number(descriptor && descriptor.usage) || null,
            canvas: size
        };
        var signature = JSON.stringify(configuration);
        gpuCanvasState.configureCount++;
        if (signature !== gpuCanvasState.lastConfiguration) {
            gpuCanvasState.lastConfiguration = signature;
            gpuCanvasState.lastSize = size;
            if (!gpuCanvasState.firstConfiguredAt) gpuCanvasState.firstConfiguredAt = Date.now();
            send('canvas-configure', {
                count: gpuCanvasState.configureCount,
                format: configuration.format,
                alphaMode: configuration.alphaMode,
                colorSpace: configuration.colorSpace,
                usage: configuration.usage,
                canvas: size
            });
        }
    }

    function installPrototypeHooks(globalName, methodNames) {
        var constructor;
        try { constructor = self[globalName]; } catch (ignored) { }
        if (!constructor || !constructor.prototype) return 0;
        var installed = 0;
        methodNames.forEach(function (methodName) {
            var didInstall = replaceMethod(constructor.prototype, methodName, function (original) {
                return function () {
                    var args = arguments;
                    var entry = noteGpuOperation(globalName, methodName, args, this);
                    var result;
                    try {
                        result = Reflect.apply(original, this, args);
                    } catch (error) {
                        recordGpuError(error, 'thrown by ' + globalName + '.' + methodName, entry);
                        if (/create(Render|Compute)Pipeline/.test(methodName)) inspectShaderCompilation(args[0], 'synchronous pipeline creation failure', entry);
                        throw error;
                    }
                    if ((methodName === 'createRenderPipelineAsync' || methodName === 'createComputePipelineAsync') && result && typeof result.then === 'function') {
                        try {
                            result.then(function () { }, function (error) {
                                recordGpuError(error, 'rejected ' + globalName + '.' + methodName, entry);
                                inspectShaderCompilation(args[0], 'asynchronous pipeline creation rejection', entry);
                            });
                        } catch (ignored) { }
                    } else if (methodName === 'popErrorScope' && result && typeof result.then === 'function') {
                        try {
                            result.then(function (error) {
                                if (error) recordGpuError(error, 'existing GPUDevice.popErrorScope', entry);
                            }, function (error) {
                                recordGpuError(error, 'rejected GPUDevice.popErrorScope', entry);
                            });
                        } catch (ignored) { }
                    }
                    if (globalName === 'GPUTexture' && methodName === 'createView' && result && gpuTextureViewSnapshots) {
                        try {
                            var viewDetail = operationDetails(globalName, methodName, args, this);
                            gpuTextureViewSnapshots.set(result, {
                                texture: gpuTextureSnapshot(this),
                                view: viewDetail.view || {}
                            });
                        } catch (ignored) { }
                    }
                    if (globalName === 'GPUCanvasContext' && methodName === 'configure') {
                        try { notifyCanvasConfiguration(this, args[0] || {}); } catch (ignored) { }
                    } else if (globalName === 'GPUCanvasContext' && methodName === 'getCurrentTexture') {
                        gpuCanvasState.textureCount++;
                        if (!gpuCanvasState.firstTextureAt) {
                            gpuCanvasState.firstTextureAt = Date.now();
                            var textureSize = null;
                            try { textureSize = { width: Number(result.width) || null, height: Number(result.height) || null }; } catch (ignored) { }
                            send('canvas-texture-first', {
                                count: gpuCanvasState.textureCount,
                                canvas: gpuCanvasSize(this),
                                texture: textureSize
                            });
                        }
                    } else if (globalName === 'GPUQueue' && methodName === 'submit' && gpuOperationCounts['GPUQueue.submit'] === 1) {
                        send('gpu-queue-submit-first', { commandBufferCount: Array.isArray(args[0]) ? args[0].length : null });
                    }
                    return result;
                };
            });
            if (didInstall) installed++;
        });
        return installed;
    }

    function attachGpuDevice(device) {
        if (!device || typeof device !== 'object' || observedGpuDevices.has(device)) return;
        observedGpuDevices.add(device);
        var uncapturedListenerInstalled = false;
        try {
            if (typeof device.addEventListener === 'function') {
                device.addEventListener('uncapturederror', function (event) {
                    var error = event && event.error;
                    recordGpuError(error || event, 'GPUDevice uncapturederror event');
                    if (error && /shader|pipeline|layout|bind group|binding/i.test(String(error.message || ''))) {
                        var recentPipeline = null;
                        for (var i = recentGpuOperations.length - 1; i >= 0; i--) {
                            if (/create(Render|Compute)Pipeline/.test(recentGpuOperations[i].operation)) { recentPipeline = recentGpuOperations[i]; break; }
                        }
                        if (recentPipeline) rememberContext('possible pipeline context (temporal only)', JSON.stringify(recentPipeline));
                    }
                });
                uncapturedListenerInstalled = true;
            }
        } catch (error) {
            rememberContext('GPU error observer', 'Could not attach uncapturederror listener: ' + safeText(error));
        }
        try {
            if (device.lost && typeof device.lost.then === 'function') {
                device.lost.then(function (info) {
                    var details = {
                        reason: safeText(info && info.reason || 'unknown', 120),
                        message: safeText(info && info.message || '', 8000),
                        deviceLabel: objectLabel(device),
                        recentOperations: snapshotGpuOperations()
                    };
                    rememberContext('GPU device lost', details.message || details.reason);
                    send('gpu-device-lost', details);
                }, function (error) {
                    recordGpuError(error, 'GPUDevice.lost promise rejection');
                });
            }
        } catch (ignored) { }
        var featureList = [];
        try { if (device.features && typeof device.features.forEach === 'function') device.features.forEach(function (feature) { featureList.push(safeText(feature, 100)); }); } catch (ignored) { }
        var limits = {};
        ['maxTextureDimension2D', 'maxTextureArrayLayers', 'maxBindGroups', 'maxSampledTexturesPerShaderStage',
            'maxSamplersPerShaderStage', 'maxStorageBuffersPerShaderStage', 'maxStorageTexturesPerShaderStage',
            'maxUniformBuffersPerShaderStage', 'maxBufferSize', 'maxUniformBufferBindingSize', 'maxStorageBufferBindingSize',
            'maxColorAttachments', 'maxComputeInvocationsPerWorkgroup', 'maxComputeWorkgroupsPerDimension'].forEach(function (key) {
            try { if (device.limits && typeof device.limits[key] === 'number') limits[key] = device.limits[key]; } catch (ignored) { }
        });
        send('gpu-device-observed', {
            uncapturederrorListenerInstalled: uncapturedListenerInstalled,
            features: featureList.slice(0, 80),
            limits: limits
        });
    }

    function attachGpuAdapter(adapter) {
        if (!adapter || typeof adapter !== 'object') return;
        var info = {};
        try {
            var adapterInfo = adapter.info || {};
            ['vendor', 'architecture', 'device', 'description'].forEach(function (key) {
                try { if (adapterInfo[key]) info[key] = safeText(adapterInfo[key], 180); } catch (ignored) { }
            });
        } catch (ignored) { }
        var features = [];
        try { if (adapter.features && typeof adapter.features.forEach === 'function') adapter.features.forEach(function (feature) { features.push(safeText(feature, 100)); }); } catch (ignored) { }
        var limits = {};
        ['maxTextureDimension2D', 'maxTextureArrayLayers', 'maxBindGroups', 'maxSampledTexturesPerShaderStage',
            'maxBufferSize', 'maxUniformBufferBindingSize', 'maxStorageBufferBindingSize', 'maxColorAttachments'].forEach(function (key) {
            try { if (adapter.limits && typeof adapter.limits[key] === 'number') limits[key] = adapter.limits[key]; } catch (ignored) { }
        });
        send('gpu-adapter-observed', { information: info, features: features.slice(0, 80), limits: limits });
    }

    function wrapAdapterRequestDevice(holder) {
        var target = holder;
        try {
            while (target && !Object.prototype.hasOwnProperty.call(target, 'requestDevice')) target = Object.getPrototypeOf(target);
        } catch (ignored) { }
        replaceMethod(target, 'requestDevice', function (original) {
            return function () {
                var result = Reflect.apply(original, this, arguments);
                if (result && typeof result.then === 'function') {
                    try { result.then(attachGpuDevice, function (error) { recordGpuError(error, 'GPUAdapter.requestDevice rejection'); }); } catch (ignored) { }
                } else {
                    attachGpuDevice(result);
                }
                return result;
            };
        });
    }

    function installGpuDeviceCreationHook() {
        try {
            if (self.GPUAdapter && self.GPUAdapter.prototype) wrapAdapterRequestDevice(self.GPUAdapter.prototype);
            var gpu = self.navigator && self.navigator.gpu;
            if (gpu && typeof gpu.requestAdapter === 'function') {
                replaceMethod(gpu, 'requestAdapter', function (original) {
                    return function () {
                        var result = Reflect.apply(original, this, arguments);
                        if (result && typeof result.then === 'function') {
                            try {
                                result.then(function (adapter) {
                                    attachGpuAdapter(adapter);
                                    if (adapter && typeof adapter.requestDevice === 'function') wrapAdapterRequestDevice(adapter);
                                }, function (error) { recordGpuError(error, 'GPU.requestAdapter rejection'); });
                            } catch (ignored) { }
                        }
                        return result;
                    };
                });
            }
        } catch (error) {
            send('gpu-observer-status', { message: 'Could not wrap GPU adapter/device creation: ' + safeText(error) });
        }
    }

    function installGpuApiHooks() {
        if (component !== 'gpu-worker') return;
        var installed = 0;
        installed += installPrototypeHooks('GPUDevice', ['createBuffer', 'createTexture', 'createSampler', 'createBindGroup',
            'createBindGroupLayout', 'createPipelineLayout', 'createShaderModule', 'createRenderPipeline',
            'createRenderPipelineAsync', 'createComputePipeline', 'createComputePipelineAsync', 'createCommandEncoder',
            'pushErrorScope', 'popErrorScope', 'destroy']);
        installed += installPrototypeHooks('GPUQueue', ['submit', 'writeBuffer', 'writeTexture', 'copyExternalImageToTexture']);
        installed += installPrototypeHooks('GPUCommandEncoder', ['beginRenderPass', 'beginComputePass', 'finish', 'copyBufferToBuffer', 'copyBufferToTexture', 'copyTextureToBuffer', 'copyTextureToTexture', 'clearBuffer']);
        installed += installPrototypeHooks('GPURenderPassEncoder', ['setPipeline', 'setBindGroup', 'setVertexBuffer', 'setIndexBuffer',
            'setViewport', 'setScissorRect', 'draw', 'drawIndexed', 'drawIndirect', 'drawIndexedIndirect', 'end']);
        installed += installPrototypeHooks('GPUComputePassEncoder', ['setPipeline', 'setBindGroup', 'dispatchWorkgroups', 'dispatchWorkgroupsIndirect', 'end']);
        installed += installPrototypeHooks('GPUCanvasContext', ['configure', 'getCurrentTexture']);
        installed += installPrototypeHooks('GPUTexture', ['createView', 'destroy']);
        installed += installPrototypeHooks('GPUBuffer', ['destroy']);
        installed += installPrototypeHooks('GPUQuerySet', ['destroy']);
        installGpuDeviceCreationHook();
        var canCreatePipelineAsync = false;
        try { canCreatePipelineAsync = !!(self.GPUDevice && self.GPUDevice.prototype && typeof self.GPUDevice.prototype.createRenderPipelineAsync === 'function'); } catch (ignored) { }
        send('gpu-worker-config', {
            workerUrlSyncPipelines: workerQuery.syncPipelines,
            createRenderPipelineAvailable: !!(self.GPUDevice && self.GPUDevice.prototype && typeof self.GPUDevice.prototype.createRenderPipeline === 'function'),
            createRenderPipelineAsyncAvailable: canCreatePipelineAsync,
            installedApiHooks: installed,
            diagnosticScopePolicy: 'observes uncapturederror and existing popErrorScope results; does not push error scopes'
        });
    }

    function classifyResource(value) {
        try {
            var raw = value && value.url ? value.url : value;
            var url = new URL(String(raw), self.location.href);
            var path = url.pathname.toLowerCase();
            if (/\/game\.wasm$/.test(path)) return 'game-wasm';
            if (/\/manifest\.json$/.test(path)) return 'data-manifest';
            if (/\/bootset(_low)?\.json$/.test(path)) return 'data-bootset';
            if (/\/data\/batchc?\//.test(path) || /\/data\/batch$/.test(path)) return 'data-batch';
            if (/\/shaders?\//.test(path) || /pipeline/.test(path) || /\.wgsl$/.test(path)) return 'shader-or-pipeline-resource';
            if (/\/game\.js$/.test(path)) return 'game-javascript';
            if (/\.(?:ogg|oga|mp3|wav|flac|opus|aac|m4a|wem|bank)$/i.test(path) || /\/(?:audio|sound|sounds)\//i.test(path)) return 'audio-resource';
            if (/\.bin$/.test(path)) return 'binary-resource';
            return url.origin === self.location.origin ? 'local-resource' : 'remote-resource';
        } catch (ignored) {
            return 'unclassified-resource';
        }
    }

    function installFetchObserver() {
        if (typeof self.fetch !== 'function') return;
        var original = self.fetch;
        try {
            self.fetch = function (input, init) {
                var started = workerNow();
                var kind = classifyResource(input);
                var method = safeText((init && init.method) || (input && input.method) || 'GET', 20).toUpperCase();
                var keyBase = component + '|' + method + '|' + kind;
                var result;
                try { result = Reflect.apply(original, this, arguments); }
                catch (error) {
                    send('resource-fetch-failure', { kind: kind, method: method, message: describeError(error).message, durationMs: Math.round(workerNow() - started) });
                    throw error;
                }
                if (result && typeof result.then === 'function') {
                    try {
                        result.then(function (response) {
                            var status = Number(response && response.status) || 0;
                            var bytes = null;
                            var contentType = '';
                            try { bytes = Number(response.headers.get('content-length')) || null; } catch (ignored) { }
                            try { contentType = safeText(response.headers.get('content-type') || '', 120); } catch (ignored) { }
                            var key = keyBase + '|' + status;
                            var now = Date.now();
                            var group = resourceGroups.get(key);
                            if (!group) {
                                group = { kind: kind, method: method, status: status, ok: !!(response && response.ok), count: 0, bytesKnownTotal: 0, responseCountWithLength: 0, firstAt: now, lastAt: now, contentType: contentType };
                                resourceGroups.set(key, group);
                            }
                            group.count++;
                            group.lastAt = now;
                            if (bytes != null) { group.bytesKnownTotal += bytes; group.responseCountWithLength++; }
                            if (!response || !response.ok) {
                                send('resource-fetch-failure', {
                                    kind: kind,
                                    method: method,
                                    status: status,
                                    contentType: contentType,
                                    contentLength: bytes,
                                    durationMs: Math.round(workerNow() - started)
                                });
                            }
                        }, function (error) {
                            send('resource-fetch-failure', {
                                kind: kind,
                                method: method,
                                message: describeError(error).message,
                                durationMs: Math.round(workerNow() - started)
                            });
                        });
                    } catch (ignored) { }
                }
                return result;
            };
        } catch (error) {
            send('worker-instrumentation', { message: 'Fetch observer unavailable: ' + safeText(error) });
        }
    }

    function installWasmMemoryObserver() {
        try {
            var Memory = WebAssembly && WebAssembly.Memory;
            if (!Memory || !Memory.prototype || typeof Memory.prototype.grow !== 'function') return;
            replaceMethod(Memory.prototype, 'grow', function (original) {
                return function (delta) {
                    var before = memoryInfo(this);
                    var result;
                    try {
                        result = Reflect.apply(original, this, arguments);
                    } catch (error) {
                        var described = describeError(error, '', 8000);
                        rememberContext('WebAssembly memory growth failure', described.message);
                        send('wasm-memory-growth-failure', {
                            deltaPages: safeText(delta, 80),
                            before: before,
                            error: describeError(error, '', 8000)
                        });
                        throw error;
                    }
                    var after = memoryInfo(this);
                    trackWasmMemory(this, 'WebAssembly.Memory.grow');
                    send('wasm-memory-growth', {
                        deltaPages: safeText(delta, 80),
                        previousBufferBytes: before ? before.bufferBytes : null,
                        currentBufferBytes: after ? after.bufferBytes : null,
                        metric: 'WebAssembly linear-memory buffer capacity; not allocator-used bytes or physical RAM'
                    });
                    return result;
                };
            });
        } catch (ignored) { }
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
        if (event && event.data && event.data.error) {
            record.state = 'error';
            var described = describeError(event.data.error, 'worker reported an error', 12000);
            send('worker-message-error', {
                role: record.role,
                name: described.name,
                message: described.message,
                stack: described.stack,
                recentParentContext: recentWorkerContext.slice(-12)
            }, record.id);
        }
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
                var described = describeError(event && event.error, event && event.message || 'worker error', 12000);
                send('worker-error', {
                    role: role,
                    name: described.name,
                    message: described.message,
                    stack: described.stack,
                    filename: safeUrl(event && event.filename || workerUrl),
                    line: Number(event && event.lineno) || null,
                    column: Number(event && event.colno) || null,
                    workerName: safeText(worker && worker.name || '', 120),
                    recentParentContext: recentWorkerContext.slice(-12)
                }, record.id);
            });
            worker.addEventListener('messageerror', function (event) {
                send('worker-error', {
                    role: role,
                    message: 'message deserialization failed',
                    detail: safeText(event && event.message || '', 2000),
                    workerName: safeText(worker && worker.name || '', 120)
                }, record.id);
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
        if (typeof WebAssembly !== 'object') return;
        installWasmMemoryObserver();
        ['instantiateStreaming', 'instantiate', 'compileStreaming', 'compile'].forEach(function (operation) {
            var original = WebAssembly[operation];
            if (typeof original !== 'function') return;
            try {
                WebAssembly[operation] = function () {
                    var args = Array.prototype.slice.call(arguments);
                    var started = workerNow();
                    rememberContext('WebAssembly', operation + ' started');
                    if (component === 'loader-worker' && (operation === 'instantiateStreaming' || operation === 'instantiate')) {
                        captureMemories(args[1], 'WebAssembly ' + operation + ' imports');
                    }
                    if (component === 'loader-worker') send('wasm-start', { operation: operation });
                    var result;
                    try {
                        result = Reflect.apply(original, this, args);
                    } catch (error) {
                        var described = describeError(error, '', 12000);
                        rememberContext('WebAssembly error', described.message);
                        if (component === 'loader-worker') send('wasm-error', {
                            operation: operation,
                            name: described.name,
                            message: described.message,
                            stack: described.stack,
                            durationMs: Math.round(workerNow() - started),
                            thread: component
                        });
                        throw error;
                    }
                    function onSuccess(value) {
                        try {
                            var instance = value && value.instance ? value.instance : value;
                            if (instance && instance.exports) captureMemories(instance.exports, 'WebAssembly ' + operation + ' exports');
                        } catch (ignored) { }
                        rememberContext('WebAssembly', operation + ' completed in ' + Math.round(workerNow() - started) + ' ms');
                        if (component === 'loader-worker') send('wasm-end', {
                            operation: operation,
                            durationMs: Math.round(workerNow() - started),
                            exportedMemoryObserved: !!(value && value.instance && value.instance.exports && value.instance.exports.memory)
                        });
                    }
                    function onFailure(error) {
                        var described = describeError(error, '', 12000);
                        rememberContext('WebAssembly error', described.message);
                        if (component === 'loader-worker') send('wasm-error', {
                            operation: operation,
                            name: described.name,
                            message: described.message,
                            stack: described.stack,
                            durationMs: Math.round(workerNow() - started),
                            thread: component
                        });
                    }
                    if (result && typeof result.then === 'function') {
                        try { result.then(onSuccess, onFailure); } catch (ignored) { }
                    } else {
                        onSuccess(result);
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
                        var described = describeError(error, '', 8000);
                        rememberContext('OffscreenCanvas.getContext', described.message);
                        send('gpu-context', {
                            contextType: safeText(type, 80),
                            available: false,
                            name: described.name,
                            message: described.message,
                            stack: described.stack,
                            canvas: { width: Number(this.width) || 0, height: Number(this.height) || 0 }
                        });
                    }
                    throw error;
                }
                if (/webgpu|webgl/i.test(String(type))) {
                    var canvasInfo = { width: Number(this.width) || 0, height: Number(this.height) || 0 };
                    rememberContext('OffscreenCanvas.getContext', type + ' context ' + (result ? 'created' : 'unavailable') + ' ' + canvasInfo.width + 'x' + canvasInfo.height);
                    send('gpu-context', {
                        contextType: safeText(type, 80),
                        available: !!result,
                        message: type + ' OffscreenCanvas context ' + (result ? 'created' : 'unavailable'),
                        canvas: canvasInfo
                    });
                }
                return result;
            };
        } catch (ignored) { }
    }

    function installAudioObservers() {
        if (component !== 'loader-worker') return;
        var NativeAudioContext = null;
        try { NativeAudioContext = self.AudioContext || self.webkitAudioContext || null; } catch (ignored) { }
        send('audio-capability', {
            environment: component,
            audioContextAvailable: typeof NativeAudioContext === 'function',
            audioWorkletNodeAvailable: typeof self.AudioWorkletNode === 'function',
            mediaElementAvailable: typeof self.HTMLMediaElement === 'function',
            autoplayUserActivationAvailable: !!self.navigator && !!self.navigator.userActivation,
            note: 'Worker capability only; this does not establish whether the page or device produced audible output.'
        });
        if (typeof NativeAudioContext !== 'function' || typeof Proxy !== 'function') return;
        var records = typeof WeakMap === 'function' ? new WeakMap() : null;
        var contextSequence = 0;

        function observeContext(context, constructorName) {
            if (!context || typeof context !== 'object') return null;
            var record = null;
            try { if (records) record = records.get(context) || null; } catch (ignored) { }
            if (record) return record;
            record = { id: 'worker-audio-' + (++contextSequence), constructor: constructorName, createdAt: Date.now() };
            try { if (records) records.set(context, record); } catch (ignored) { }
            function snapshot() {
                var details = {
                    contextId: record.id,
                    constructor: record.constructor,
                    createdAt: isoTime(record.createdAt),
                    state: safeText(context.state || 'unknown', 80),
                    sampleRate: Number(context.sampleRate) || null,
                    baseLatency: Number(context.baseLatency) || null,
                    outputLatency: Number(context.outputLatency) || null,
                    destinationMaxChannelCount: Number(context.destination && context.destination.maxChannelCount) || null
                };
                send('audio-context', details);
            }
            snapshot();
            try { context.addEventListener('statechange', snapshot); } catch (ignored) { }
            try {
                var worklet = context.audioWorklet;
                if (worklet && typeof worklet.addModule === 'function') {
                    replaceMethod(worklet, 'addModule', function (original) {
                        return function (url) {
                            var module = safeUrl(url);
                            send('audio-worklet-module', { contextId: record.id, module: module, status: 'requested' });
                            var result;
                            try { result = Reflect.apply(original, this, arguments); }
                            catch (error) {
                                send('audio-worklet-module', { contextId: record.id, module: module, status: 'threw', message: describeError(error).message });
                                throw error;
                            }
                            if (result && typeof result.then === 'function') {
                                result.then(function () { send('audio-worklet-module', { contextId: record.id, module: module, status: 'loaded' }); },
                                    function (error) { send('audio-worklet-module', { contextId: record.id, module: module, status: 'rejected', message: describeError(error).message }); });
                            }
                            return result;
                        };
                    });
                }
            } catch (ignored) { }
            return record;
        }

        ['resume', 'suspend', 'close'].forEach(function (methodName) {
            try {
                replaceMethod(NativeAudioContext.prototype, methodName, function (original) {
                    return function () {
                        var record = observeContext(this, 'AudioContext');
                        send('audio-playback-attempt', { contextId: record && record.id, operation: 'AudioContext.' + methodName, outcome: 'called' });
                        var result;
                        try { result = Reflect.apply(original, this, arguments); }
                        catch (error) {
                            var description = describeError(error);
                            send('audio-playback-attempt', { contextId: record && record.id, operation: 'AudioContext.' + methodName,
                                outcome: 'threw', name: description.name, message: description.message });
                            throw error;
                        }
                        if (result && typeof result.then === 'function') {
                            result.then(function () { send('audio-playback-attempt', { contextId: record && record.id, operation: 'AudioContext.' + methodName, outcome: 'fulfilled' }); },
                                function (error) {
                                    var description = describeError(error);
                                    send('audio-playback-attempt', { contextId: record && record.id, operation: 'AudioContext.' + methodName,
                                        outcome: 'rejected', name: description.name, message: description.message });
                                });
                        }
                        return result;
                    };
                });
            } catch (ignored) { }
        });
        try {
            var wrapped;
            wrapped = new Proxy(NativeAudioContext, {
                construct: function (target, args, newTarget) {
                    var context = Reflect.construct(target, args, newTarget === wrapped ? target : newTarget);
                    observeContext(context, 'AudioContext');
                    return context;
                }
            });
            if (self.AudioContext) self.AudioContext = wrapped;
            else self.webkitAudioContext = wrapped;
        } catch (error) {
            send('worker-instrumentation', { message: 'AudioContext observer unavailable: ' + safeText(error) });
        }
    }

    function installConsoleHooks() {
        if (!self.console) return;
        ['error', 'warn', 'log'].forEach(function (method) {
            var original = self.console[method];
            if (typeof original !== 'function') return;
            try {
                self.console[method] = function () {
                    var text = Array.prototype.map.call(arguments, function (value) { return safeText(value, 12000); }).join(' ');
                    if (relevant(text)) {
                        rememberContext('console.' + method, text);
                        send('console', { method: method, message: text });
                    }
                    return Reflect.apply(original, this, arguments);
                };
            } catch (ignored) { }
        });
    }

    function installErrorObservers() {
        try {
            self.addEventListener('error', function (event) {
                var described = describeError(event && event.error, event && event.message || 'worker error', 16000);
                var details = {
                    name: described.name,
                    message: described.message,
                    stack: described.stack,
                    thread: component,
                    workerName: safeText(self.name || '', 120),
                    resource: safeUrl(event && event.filename || ''),
                    line: Number(event && event.lineno) || null,
                    column: Number(event && event.colno) || null,
                    recentContext: recentWorkerContext.slice(-20),
                    recentGpuOperations: snapshotGpuOperations()
                };
                rememberContext('uncaught worker error', described.message);
                send('exception', details);
            });
            self.addEventListener('unhandledrejection', function (event) {
                var reason = event && event.reason;
                var described = describeError(reason, 'unhandled promise rejection', 16000);
                rememberContext('unhandled promise rejection', described.message);
                send('unhandled-rejection', {
                    name: described.name,
                    message: described.message,
                    stack: described.stack,
                    thread: component,
                    workerName: safeText(self.name || '', 120),
                    recentContext: recentWorkerContext.slice(-20),
                    recentGpuOperations: snapshotGpuOperations()
                });
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
                    rememberContext('loader initialization', 'syncPipelines=' + (data.syncPipelines === true));
                } else if (component === 'gpu-worker' && data && typeof data === 'object') {
                    var memory = memoryInfo(data.memory);
                    if (memory) trackWasmMemory(data.memory, 'GPU worker initialization message');
                    var details = {
                        keys: Object.keys(data).slice(0, 16),
                        hasMemory: !!data.memory,
                        memoryMetric: memory && memory.metric,
                        memoryBufferBytes: memory && memory.bufferBytes,
                        memoryBufferMiB: memory && memory.bufferMiB,
                        sharedMemory: memory && memory.shared,
                        hasRing: !!data.ring,
                        ringSizeBytes: Number(data.size) || null,
                        diagnosticNote: 'ring address is intentionally not exported'
                    };
                    send('gpu-worker-init', details);
                    rememberContext('GPU worker initialization', 'shared WebAssembly memory present=' + !!data.memory + ', ring bytes=' + (Number(data.size) || 'unknown'));
                } else if (data && typeof data === 'object' && data.error) {
                    var error = describeError(data.error, 'worker message error', 12000);
                    send('worker-message-error', {
                        name: error.name,
                        message: error.message,
                        stack: error.stack,
                        thread: component,
                        recentContext: recentWorkerContext.slice(-15)
                    });
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
                if (pendingGpuErrorFlush) flushGpuErrorGroups();
                if (gap > 10000) send('worker-heartbeat-gap', { gapMs: gap });
                send('worker-heartbeat', {
                    gapMs: gap,
                    pipeline: component === 'gpu-worker' ? pipelineImplementationSnapshot() : undefined,
                    gpuOperations: component === 'gpu-worker' ? {
                        counts: gpuOperationCounts,
                        queueSubmitCount: gpuOperationCounts['GPUQueue.submit'] || 0,
                        canvasConfigureCount: gpuCanvasState.configureCount,
                        canvasTextureCount: gpuCanvasState.textureCount,
                        canvasSize: gpuCanvasState.lastSize,
                        canvasConfiguration: gpuCanvasState.lastConfiguration ? JSON.parse(gpuCanvasState.lastConfiguration) : null,
                        recentOperations: snapshotGpuOperations()
                    } : undefined,
                    resourceGroups: Array.from(resourceGroups.values()).slice(0, 24).map(function (group) {
                        return {
                            kind: group.kind,
                            method: group.method,
                            status: group.status,
                            ok: group.ok,
                            count: group.count,
                            contentType: group.contentType,
                            responseCountWithLength: group.responseCountWithLength,
                            bytesKnownTotal: group.bytesKnownTotal,
                            firstAt: group.firstAt,
                            lastAt: group.lastAt
                        };
                    })
                });
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
    workerQuery.syncPipelines = pageQuery.get('syncpipelines') === '1';
    if (pageQuery.get('newcatroid_pipeline_probe') === '1' && pageQuery.get('newcatroid_worker_debug') !== '1') {
        // Fix-only mode: inspect exactly which syncPipelines URL option the
        // existing loader gives to wgpu_worker.js. No logs, heartbeat, WASM
        // hooks, or GPU instrumentation are enabled in this branch.
        installPipelineSelectionProbe();
        return;
    }

    installNestedWorkerHooks();
    installWasmHooks();
    installFetchObserver();
    installOffscreenContextObserver();
    installConsoleHooks();
    installAudioObservers();
    installErrorObservers();
    installGpuApiHooks();
    installHeartbeat();
    send('worker-bootstrap', {
        component: component,
        script: safeUrl(self.location.href),
        workerUrlSyncPipelines: component === 'gpu-worker' ? workerQuery.syncPipelines : null,
        contextNote: 'worker events are local BroadcastChannel messages; no HTTP telemetry is sent'
    });
})();
