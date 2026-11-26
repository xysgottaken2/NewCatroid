// Actual Dawn/Tint validation of the pinned client's shader transformation.
// Null backend: validates WGSL/pipelines, DOES NOT render pixels or model Adreno.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import vm from 'node:vm';
import {MessageChannel} from 'node:worker_threads';
import {create, globals} from 'webgpu';

const worker = readFileSync(process.argv[2]);
const expected = 'e1b83d2b10a98d29d8f7ac45a4e42f68486144a8046df688667891af034716c7';
assert.equal(createHash('sha256').update(worker).digest('hex'), expected, 'review the new client before updating its hash');
const compatibility = readFileSync(new URL('../../catroid/src/main/res/raw/newcatroid_shader_compat.js', import.meta.url), 'utf8');
const gpu = create(['backend=null']);
const adapter = await gpu.requestAdapter();
assert.ok(adapter, 'Dawn null adapter is required, not a mocked WebGPU API');
const device = await adapter.requestDevice();
let cases = 0;
try {
    for (const fixed of [false,true]) for (const remapped of [false,true]) for (const scalar of [false,true]) {
        const context = vm.createContext({self:{location:new URL('http://localhost/wgpu_worker.js?newcatroid_alpha_fix=1'),
            navigator:{hardwareConcurrency:4},addEventListener(){},postMessage(){}},
            URLSearchParams,MessageChannel,performance,console:{log(){},info(){}},...globals});
        vm.runInContext(worker.toString(),context);
        context.testDevice = device;
        vm.runInContext('device = testDevice',context);
        if (fixed) vm.runInContext(compatibility,context);
        const type = scalar ? 'f32' : 'vec4<f32>';
        // Original, copyright-free reduced shader with the client's generated ABI.
        const code = `var<private> o0 : ${type};
fn main_inner() { o0 = ${scalar ? '0.75f' : 'vec4<f32>(0.25f, 0.5f, 0.75f, 1.0f)'}; }
@fragment fn main(@location(15) v: f32) -> @location(0) ${type} {
  main_inner();
  return o0;
}`;
        context.prog = {text:code, module:device.createShaderModule({code}),variants:new Map(),label:'reduced alpha shader'};
        context.remap = remapped ? {ps:true,key:'15>0',map:new Map([[15,0]])} : null;
        device.pushErrorScope('validation');
        const module = vm.runInContext('pixelModule(prog, true, remap)',context);
        const messages = (await module.getCompilationInfo()).messages.filter(m=>m.type==='error');
        const error = await device.popErrorScope();
        const mustFail = scalar && !fixed;
        assert.equal(messages.length > 0,mustFail);
        if (mustFail) {
            assert.match(error.message,/cannot index into expression of type 'f32'/);
            console.log(`REPRODUCED original scalar / remap=${remapped}: ${messages[0].message}`);
        } else {
            assert.equal(error,null);
            // Use a matching vertex varying to also validate the complete pipeline.
            const loc = remapped ? 0 : 15;
            const vs = device.createShaderModule({code:`struct Out { @builtin(position) p: vec4f, @location(${loc}) v: f32 }
@vertex fn main() -> Out { return Out(vec4f(0, 0, 0, 1), 1); }`});
            const descriptor = {layout:'auto',vertex:{module:vs,entryPoint:'main'},fragment:{module,entryPoint:'main',targets:[{format:scalar?'r32float':'rgba8unorm'}]}};
            device.pushErrorScope('validation');
            device.createRenderPipeline(descriptor);
            assert.equal(await device.popErrorScope(),null);
            await device.createRenderPipelineAsync(descriptor);
            console.log(`PASS ${fixed?'corrected':'original'} ${scalar?'scalar':'RGBA'} / remap=${remapped}: WGSL + sync/async pipelines`);
        }
        cases++;
    }
    console.log(`PASS ${cases} cases; 2 real original WGSL failures reproduced, 6 valid variants. No device/gameplay/pixel validation.`);
} finally {
    device.destroy();
}
