'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/res/raw/newcatroid_shader_compat.js'), 'utf8');

function environment(url, page = false) {
    class Worker {
        constructor(url, options) { this.url = url; this.options = options; }
        postMessage(data, transfer) { this.message = data; this.transfer = transfer; }
    }
    const self = {location: new URL(url), Worker};
    const context = vm.createContext({self, URL, URLSearchParams, ...(page ? {document: {}} : {})});
    vm.runInContext(source, context);
    return {self, Worker};
}

test('alpha fix is opt-in and only propagates through same-origin loader and GPU workers', () => {
    for (const query of ['', '?newcatroid_alpha_fix=0', '?newcatroid_alpha_fix=true', '?newcatroid_adreno_fix=1', '?newcatroid_debug=1']) {
        const env = environment('http://localhost/index.html' + query, true);
        assert.equal(env.self.Worker, env.Worker);
    }
    const page = environment('http://localhost/index.html?newcatroid_alpha_fix=1', true);
    const options = {name:'engine'};
    const loader = new page.self.Worker('/b/build/loader.js?existing=1', options);
    assert.equal(loader.url, 'http://localhost/b/build/loader.js?existing=1&newcatroid_alpha_fix=1');
    assert.equal(loader.options, options);
    const data = {canvas:{},args:[]}, transfer = [data.canvas];
    loader.postMessage(data, transfer);
    assert.equal(loader.message, data);
    assert.equal(loader.transfer, transfer);
    for (const url of ['/io_worker.js','/game.js','blob:http://localhost/abc','https://elsewhere/loader.js']) {
        assert.equal(new page.self.Worker(url).url, url);
    }
    const nested = environment(loader.url);
    assert.equal(new nested.self.Worker('/b/build/wgpu_worker.js?syncpipelines=1&newcatroid_worker_debug=1').url,
        'http://localhost/b/build/wgpu_worker.js?syncpipelines=1&newcatroid_worker_debug=1&newcatroid_alpha_fix=1');
    assert.equal(new nested.self.Worker('/io_worker.js').url, '/io_worker.js');
    assert.equal(new nested.self.Worker('/game.js').url, '/game.js');
});

test('only alpha eligibility changes; program and location-remap identities are preserved', () => {
    const calls = [];
    const context = vm.createContext({self:{location:new URL('http://localhost/wgpu_worker.js?newcatroid_alpha_fix=1')}, URLSearchParams,
        console:{info(){}}, pixelModule:(...args)=>{calls.push(args);return 'module';}});
    vm.runInContext(source, context);
    for (const type of ['f32','vec2<f32>','vec3<f32>','vec4<f32>','vec4f']) {
        const program = {text:'var<private> o0 : ' + type + ';'}, remap = {ps:true};
        assert.equal(context.pixelModule(program,true,remap), 'module');
        const call = calls.at(-1);
        assert.equal(call[0], program);
        assert.equal(call[1], type.startsWith('vec4'));
        assert.equal(call[2], remap);
        context.pixelModule(program,false,remap);
        assert.equal(calls.at(-1)[1],false);
    }
    const stats = context.self.__newcatroidAlphaCompatibility;
    assert.equal(stats.enabled, true);
    assert.equal(stats.hookInstalled, true);
    assert.equal(stats.alphaCoverageVariantsSeen, 5);
    assert.equal(stats.nonVec4AlphaTestsBypassed, 3);
    assert.equal(stats.vec4AlphaTestsPreserved, 2);
    assert.equal(stats.firstNonVec4OutputType, 'f32');
    assert.equal(typeof stats.lastObservedAt, 'number');
});
