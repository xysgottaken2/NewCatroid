# PlayGTA5: scalar alpha-test correction and remaining device investigation

## Result and limits

The public GPU worker generates invalid WGSL when alpha-to-coverage is enabled
on a single-sample draw whose target-zero output `o0` is scalar. Its synthetic
alpha test accesses `o0.w`. Dawn/Tint rejects that expression. This is a
reproduced **client shader-generation bug**, not a captured failure from the
Galaxy S23 Ultra and not a proven explanation of the entire black screen.

A minimal, opt-in integration correction now keeps the original shader when
there is no four-component floating-point `o0`. RGBA alpha testing, inter-stage
location remapping, pipeline descriptors, timing mode, textures, commands,
resize, audio, and the Wasm module otherwise keep their existing behavior.

No S23 Ultra/Adreno 740, Android emulator, GPU driver, game.wasm, translated
shader set, or full game-data mirror was available in this environment. The
public repository explicitly excludes its mirror directory. The reduced shader
below is our own test input. It is not a captured gameplay shader. There is no
claim of visible world rendering, corrected audio, or a resolved `unreachable`.

## State reconstructed

- PR #3 is maintained on `arena/5736be66-newcatroid`. At the start of this
  follow-up, its remote head was `c7879e0a53a2046ebe9defd86e9678258731c549`;
  the local checkout was stale and dirty, so its pending tree was reconciled
  forward with the remote head before these edits (no reset or force-push).
- `85b838cc` added the hash-guarded scalar alpha-test experiment and Dawn/Tint
  validation; `c7879e0a` fixed the workflow's temporary worker path. Both are
  retained. `newcatroid_alpha_fix=1` did **not** remove the black 3D world in
  the user's S23 Ultra test; the same symptom also occurs in mobile Chrome.
  Audio and a few interface elements remain visible, which is consistent with
  separate rendering paths but does not identify the failing 3D stage.
- This follow-up was committed as `ab296a12` on the same branch and pushed to
  PR #3 (still open). Actions run
  [38813369221](https://github.com/xysgottaken2/NewCatroid/actions/runs/38813369221)
  passed the focused local-web/download Kotlin tests, browser-diagnostics JS
  tests, pinned-worker Dawn/Tint regression, and debug APK build/upload. The
  full optional unit-test suite was skipped. Dawn uses a null backend and does
  not render pixels or emulate Adreno.
- The new APK artifact is
  `NewCatroid-debug-apk-run71-ab296a1248a5dc067b5dda4a37b51cf0b1c73be6` in that
  Actions run. Its successful build is not a device test. No independent
  hardware capture, full game assets, translated shader set, or `game.wasm` was
  available in this environment.

No visual fix is claimed. The reproducible WGSL failure is one real defect in
this renderer snapshot; it remains unproven as the cause of the S23 black world.
The follow-up adds a short, sanitized in-app export and per-worker confirmation
of whether the guarded shim was loaded and whether it actually encountered
alpha-test shader variants.

## Pinned sources and renderer path

Inspected [shadany7824/playgta5 @ 2fb671ad](https://github.com/shadany7824/playgta5/tree/2fb671adca55cd0317aa551b33ffd922f1639660),
including `INVESTIGATION.md`, `ENGINE_INVESTIGATION.md`, `homepage.html`,
`loader.js`, `game.js`, `wgpu_worker.js`, `io_worker.js`, and manifest/index metadata.
The JS is integration code; the internal engine is compiled into the absent Wasm.

1. `homepage.html` requires cross-origin isolation, transfers an OffscreenCanvas,
   and launches `loader.js`. The loader creates GPU/I/O workers before starting
   the blocking Wasm runtime. Streaming instantiation has a buffered fallback.
2. `game.js` imports shared Memory64 (3 GiB initial / 16 GiB maximum addressable
   linear memory, **not** measured physical usage). Pthreads share the compiled
   module/memory. `wgpu_start_worker` transfers the canvas and shared command ring
   to the GPU worker. `wasm_httpfs_io_start_js` passes the I/O request table.
3. `initDevice` requests a high-performance adapter, filters 11 optional feature
   names against advertised support, and requests adapter limits. Filtering does
   not implement fallbacks: e.g. BC textures still need actual BC support. No
   observed device capabilities were supplied for this investigation.
4. `createShader`/`prefetchShader` fetch translated WGSL and constants through
   packs or individual files. `shaderProgram` packs uniform buffers and creates
   a module. `pipelineDescFor` selects the vertex/pixel variants and target state.
5. `pixelModule` calls `a2cModule` for ordinary alpha-test variants, or injects
   the same `o0.w` test directly when remapping pixel inputs. Both paths are
   defective for scalar `o0`. Failed asynchronous pipelines are cached as null
   and their draws skipped. Synchronous creation does not bypass validation;
   it can return an invalid pipeline with an asynchronous validation error.
6. The shared ring uses Atomics and a yielding command loop. DRAW encodes render
   passes; PRESENT blits the engine back buffer into the canvas current texture;
   `flush` submits the command buffer. Frame-slot reuse waits for completion.
   API calls/counters cannot establish successful validation or compositor output.
7. `Ready/world=true` follows draw-count and pending-compilation heuristics,
   including a 60-second timeout. It contains no pixel correctness test.
8. I/O uses the manifest, OPFS cache, range requests and compressed/uncompressed
   batch routes. Existing HTTP fixes/tests remain. No real missing-resource or
   corrupted-response capture was available to link this to the black screen.
9. Audio uses a separate shared mixer ring and AudioWorklet after a page gesture.
   Context resume, worklet loading, data production and actual audio output are
   distinct. The shader correction does not change audio policy or playback.

## What `syncPipelines` changes

In the pinned `loader.js`, a truthy launch field `syncPipelines` is converted to
`syncpipelines=1` on the GPU-worker URL. The pinned `wgpu_worker.js` derives its
render-pipeline mode from that query: in the browser it normally calls
`GPUDevice.createRenderPipelineAsync`; with `syncpipelines=1`, its render-pipeline
cache calls `GPUDevice.createRenderPipeline` instead. In async mode, a draw can
be skipped while its pipeline is pending. The sync branch avoids that pending
state for those render pipelines, but can block the GPU worker and still cannot
bypass shader or pipeline validation. A sync call or non-null return is not a
visible-frame guarantee.

`newcatroid_adreno_fix=1` only requests this existing render-pipeline mode by
setting `syncPipelines=true` on the recognized launch message; it does not alter
WGSL, formats, textures, render passes, buffers, submits, or presentation. In the
pinned source, compute pipelines use `GPUDevice.createComputePipeline` directly
and are not selected by the `asyncPipelines` switch. The panel therefore reports
render sync/async calls separately from compute calls. URL receipt proves
propagation; the API counters show methods observed; neither proves a visual
benefit or that a particular call caused the black screen. The user has not
reported a successful S23 result for this mode.

Sources: [`loader.js` option mapping](https://github.com/shadany7824/playgta5/blob/2fb671adca55cd0317aa551b33ffd922f1639660/loader.js#L20-L35),
[`wgpu_worker.js` render-pipeline dispatch](https://github.com/shadany7824/playgta5/blob/2fb671adca55cd0317aa551b33ffd922f1639660/wgpu_worker.js#L1188-L1290),
and [compute-pipeline creation](https://github.com/shadany7824/playgta5/blob/2fb671adca55cd0317aa551b33ffd922f1639660/wgpu_worker.js#L2210-L2224).

`THREAD ERROR (main)` is formatted by the JS error listener. `unreachable` alone
cannot locate the originating Wasm assertion/trap or distinguish memory,
threading, resource or engine-state failures. An original Wasm stack is needed.

## Architectural comparison

Inspected [gtav-native @ 07815b4c](https://github.com/kalki-kgp/gtav-native/tree/07815b4c7d8fd6592bf6d672243ce6a0348016f3),
including `host/gpu.cc`, `host/wgsl.cc`, thread/memory/I/O/audio hosting and
`docs/problems-and-fixes.md`.

That port translates Wasm to C with wasm2c, hosts futexes/threads and shared
memory, replaces HTTP reads with pread, sends mixer output to CoreAudio, and
executes the D3D-style ring through Dawn/Metal. Its `AddAlphaTest` explicitly
requires a vec4 output; its documentation reports fixing scalar variants.
Our correction independently implements that type precondition at the browser
worker's common pixel-variant entry point and tests **both** remap branches.

Its iOS BC feature patch modifies Dawn's Metal backend; it is not an Android
solution. Its acquire/release changes modify generated C ordinary memory
accesses, not browser Emscripten JS. Neither that patch nor its memory budget
was transplanted. Applying them here without a matching failure would change
unrelated behavior and would not validate an Adreno driver fix.

## Galaxy S23 report: float32 sample-type validation and binding 44

An earlier capture was produced in **Chrome on Android** (not presumed to be an
Android WebView), with `newcatroid_debug=1&newcatroid_alpha_fix=1` and
`newcatroid_adreno_fix` off. It identifies WebGPU and a Qualcomm / Adreno 7xx
adapter, but does not establish the exact Chrome/Dawn revision. The reported
4,153 WebGPU error occurrences include 994 `createRenderPipelineAsync` and 4
`createRenderPipeline` calls. The alpha-shim installation was not confirmed;
6,466 queue submits were counted; the runtime reported
`RuntimeError: null function`; and some audio/other fetches failed. Those
observations do not identify the black world's cause.

The later v2 clue is a validation mismatch for **`R32Float` at Fragment
`binding 44`**, with expected sample type `Float`; the reported GPU device did
not have `float32-filterable` enabled. That is compatible with Dawn reporting
`UnfilterableFloat` for this format. It establishes an incompatible resource /
layout combination in that validation path—not that the corresponding draw is
for the world pass, that all rendering failed, or that it caused the black
screen. The earlier `RGBA32Float` report is a separate observation; it must not
be silently treated as the same texture, bind group, shader, or render operation.

The message `None of the supported sample types (UnfilterableFloat) of Texture
RGBA32Float match the expected sample types (Float)` demonstrates a resource /
bind-group layout sample-type mismatch in that validation path. The later
BindGroup/CommandBuffer errors may be consequences, but the report did not
correlate the first error with a particular world-rendering pass. It therefore
does **not** establish that this mismatch alone caused the black scene.

The pinned worker's static mapping narrows the v2 clue but does not reveal the
runtime shader's use. `mapFormat` maps DXGI `R32_FLOAT` (41) to `r32float`
(`wgpu_worker.js` lines 69–86). `parseWgsl` maps `texture_2d<f32>` to sample
`Float` (around lines 230–247), and `layoutEntry` passes that sample type into
the explicit texture layout while assigning ordinary samplers type `filtering`
(lines 264–277). The Fragment stage is visibility bit 2; pipeline layout places
group 1 at index 1 (lines 264–275 and 1208–1216). In `stageBindGroup`, a texture
at binding 44 is resolved from `S.srv[stage][44 - 32]`, i.e. SRV slot `t12`,
while sampler bindings use `S.smp[stage][binding - 16]` (lines 1557–1593).

This is **not** the worker's separate `GenerateMips` helper: that internal path
uses group 0, texture binding 0, sampler binding 1, and a linear
`textureSampleLevel` call (lines 2294–2323). The v2 report did not name the
shader module or draw, so the resource at fragment binding 44 cannot yet be
attributed to mip generation or to the 3D world pass. The next capture now
records the shader module's direct WGSL built-in uses, paired sampler bindings,
actual texture/view, explicit layout, pipeline-layout group, render pass target,
and the draw call that had that state. The lexical probe intentionally does not
follow aliases or calls hidden in helper functions; it is evidence to guide
inspection, not a complete WGSL AST or proof of draw validity.

Dawn upstream source at commit
[`51e8b9d0`](https://github.com/google/dawn/commit/51e8b9d0ce4efb50a86bb684bfca5b6fc62dd861)
sets the supported sample type of 32-bit float formats to `UnfilterableFloat`
without `Feature::Float32Filterable` (`Format.cpp` lines 488–490), and applies
that to `R32Float` (line 536). The bind-group validator then checks the layout's
required sample-type bit against the texture format's supported bits
(`BindGroup.cpp` lines 240–250). This matches the reported validation mismatch.
It is upstream Dawn `main`, not yet proven to be the exact Dawn revision bundled
in the user's Chrome build; the device report must include its Chrome version
before claiming that exact source revision.

No layout or format has been changed. The WebGPU rules make the actual sampler
important: the default pipeline layout infers `sampleType: "float"` for an
`f32` texture statically used by a texture builtin that also takes a sampler,
and `"unfilterable-float"` otherwise (§10.1.1). Interface validation permits a
WGSL `sampler` with either texture sample type, but a bind-group sampler layout
of type `"filtering"` requires texture `sampleType: "float"` (§10.1.2). A
`"non-filtering"` sampler layout instead requires an actual sampler whose
filters are all non-linear (§§7.1.1, 8.2.1). Thus a WGSL `textureSample*` call
alone does **not** prove that linear filtering is required; inspect the bound
sampler's `minFilter`, `magFilter`, and `mipmapFilter` and the shader's intended
result. With nearest-only sampling, an explicit
`unfilterable-float`/`non-filtering` layout pair can be compatible with
`texture_2d<f32>` and sampler-based texture built-ins. The worker's present
explicit layout generator instead assigns `Float` and `filtering`, so that pair
would require a narrowly scoped layout change and device-side validation. An
`auto` layout may still infer `Float` when a sampler-based texture builtin is
statically used. For `textureLoad` alone, no sampler is needed, though the
texture layout still has to match its format.

If the trace shows linear filtering is genuinely required, first check whether
the **adapter** advertises `float32-filterable`. The inspected pinned worker's
existing `want` list already includes this optional feature and filters it
against `adapter.features` before `requestDevice`; when that exact worker is in
use, it requests the feature if the adapter advertises it. The v2 report only
says the feature was not enabled on the device; without the new worker hash and
adapter/requested/device feature lists, it does not prove whether the adapter
lacks support or whether the captured worker matched this reviewed version. If
the adapter does not support the feature, scoped alternatives are manual
filtering with `textureLoad` (preserving address, mip and LOD semantics) or a
resource-specific lower-precision format such as `r16float` if its precision is
acceptable. Neither fallback is applied without the runtime use and
visual/numeric consequences being established. No blanket format conversion
is justified.

The comparable `gtav-native` snapshot also uses `Float` for `f32` textures,
conditionally requests the float32-filterable feature, and samples linearly in
its mip-generation path. It is a native Metal port, not evidence of Android
Chrome behavior or a transferable Adreno fix. Related WebGPU/wgpu discussions
about inferred layouts and filterability are useful background, but do not
replace inspecting this worker's explicit layout and actual resource use.

The v3 short summary keeps the 5,000-character cap and earlier browser/device
feature and first/latest-error evidence, and adds a targeted Fragment binding 44
trace: direct `textureLoad`/`textureSample*` counts, WGSL variable/type, mapped
sampler binding and actual filters, `Float`/`UnfilterableFloat` layout facts,
texture/view IDs and format, pipeline-layout group match, render target, and
`draw`/`drawIndexed` call. Error contexts also compactly summarize those facts,
so the first error can be compared with nearby operations without exporting a
full log. The worker's HTTP server now puts the expected and actual SHA-256,
hash-match result, and whether it appended the shim into the local diagnostic
bootstrap. This distinguishes server verification/injection from the separate
runtime hook counter. Full SHA-256 values are reported only in this local
short-summary export; no diagnostic HTTP telemetry is sent.

The audio summary now reports observed `AudioContext` state changes and
`resume()` attempts, `AudioBufferSourceNode.start()` and media `.play()` calls,
loaded/rejected worklet modules, `AudioWorkletNode` count, worker audio-message
presence, and the last page gesture. Worker-side context resume outcomes update
the same summary. A suspended context with `resume=not observed` is not, by
itself, proof of an autoplay rejection or of why an earlier session was
audible. The earlier audible report and this later API-state observation do not
establish that they came from the same context, session, or output route; the
prior capture does not provide a like-for-like count of all these audio signals.
Chrome documents that a context created before interaction may be suspended
until `resume()` is called after a gesture; it may also resume after a gesture
when `start()` is called on a connected node. Neither this telemetry nor an API
state proves audible output.

Separate observations remain unresolved:

- **Alpha shim:** the old v2 capture predates the server-verification marker,
  so it cannot tell whether the flag was lost before the worker request, the
  source hash mismatched, the provider was unavailable, or the shim appended
  but its runtime hook was not observed. The next capture distinguishes the
  GPU-worker URL flag, expected/actual SHA-256, hash match, shim append, runtime
  hook, and alpha variants seen. A fresh read of the reviewed upstream worker
  at `2fb671a` hashes to
  `e1b83d2b10a98d29d8f7ac45a4e42f68486144a8046df688667891af034716c7`, matching
  the server constant and integration-test expectation; this verifies the
  reference file, not the bytes delivered to the Galaxy. Only a device report
  with `hashMatches=true` and `shimInjected=true` confirms the server-side
  step, and `hookInstalled=true` separately confirms the runtime hook. None of
  these claims that an alpha variant was relevant or fixed the black world.
- **`RuntimeError: null function`:** consistent with a WebAssembly runtime trap,
  potentially an indirect call through an uninitialized function-table entry.
  Without the original stack/offset and preceding events, its origin cannot be
  located or causally linked to WebGPU validation or fetch failures.
- **Audio:** an earlier audible session and a later suspended context with no
  observed `resume()` are not necessarily contradictory and do not prove a
  common context/session. The earlier report did not provide a comparable set
  of state-change, resume/start/play, worklet, node, and worker-message counts.
  The new short summary records these API observations and worker resume
  outcomes; Chrome autoplay policy remains a plausible context, not a
  demonstrated cause. Audio-classified fetch failures may explain missing audio
  assets, but URLs remain excluded and this does not explain the 3D world
  without a linked shader/data/WASM failure.
- **Adreno Fix:** it was off in this capture. When requested, it selects the
  existing synchronous *render*-pipeline path; it does not disable validation
  or select compute pipelines. The 994 asynchronous and 4 synchronous API-call
  counts are not a failure count or a controlled A/B result.
- **Submits:** 6,466 `GPUQueue.submit` observations are calls, not validated
  frames, successful presents, or measured pixels. The report still does not
  measure a visible frame.

The graphics/debug flags remain independently controllable: keep the current
debug and alpha-fix settings for the next comparable capture, and do not infer
an Adreno Fix effect without an equivalent on/off comparison. No format or shader change
is made in this follow-up; the next step is to use the new capture to identify
the affected resource and shader/pass before choosing a narrowly scoped fix.

Sources: [pinned PlayGTA5 worker](https://github.com/shadany7824/playgta5/blob/2fb671adca55cd0317aa551b33ffd922f1639660/wgpu_worker.js),
[WebGPU default pipeline-layout inference](https://www.w3.org/TR/webgpu/#abstract-opdef-default-pipeline-layout),
[WebGPU shader/layout validation](https://www.w3.org/TR/webgpu/#abstract-opdef-validating-gpuprogrammablestage),
[WebGPU sampler and bind-group rules](https://www.w3.org/TR/webgpu/#dictdef-gpusamplerbindinglayout),
[WebGPU `float32-filterable` feature](https://www.w3.org/TR/webgpu/#float32-filterable),
[Dawn `Format.cpp` float32 capabilities at commit `51e8b9d0`](https://github.com/google/dawn/blob/51e8b9d0ce4efb50a86bb684bfca5b6fc62dd861/src/dawn/native/Format.cpp#L488-L490),
[Dawn `R32Float` mapping at that commit](https://github.com/google/dawn/blob/51e8b9d0ce4efb50a86bb684bfca5b6fc62dd861/src/dawn/native/Format.cpp#L536-L538),
[Dawn bind-group validation at the same commit](https://github.com/google/dawn/blob/51e8b9d0ce4efb50a86bb684bfca5b6fc62dd861/src/dawn/native/BindGroup.cpp#L240-L250),
[Chrome autoplay policy for Web Audio](https://developer.chrome.com/blog/autoplay),
[Chrome 119 WebGPU feature notes](https://developer.chrome.com/blog/new-in-webgpu-119),
[WebGPU supported features](https://developer.mozilla.org/en-US/docs/Web/API/GPUSupportedFeatures),
[UA Client Hints version fields](https://developer.mozilla.org/en-US/docs/Web/API/NavigatorUAData/getHighEntropyValues),
[comparable `gtav-native` worker sources](https://github.com/kalki-kgp/gtav-native/tree/07815b4c7d8fd6592bf6d672243ce6a0348016f3),
[related gpuweb discussion](https://github.com/gpuweb/gpuweb/issues/2064), and
[related wgpu layout fix](https://github.com/gfx-rs/wgpu/pull/6531).

## Resize finding

`homepage.html` computes `innerWidth * devicePixelRatio * scale` and likewise
for height, scales to fit 3840x2160 (2560x1440 for its low-memory profile), rounds
to even dimensions, and debounces resize notifications by 150 ms. It publishes
width/height through the shared input block, consumed by the Wasm engine.

1152x2160 is compatible with that portrait cap/aspect calculation. The previous
1838x658 is a different viewport aspect. Without the page's viewport/orientation
capture this does not prove precisely what triggered the change, but there is
no evidence for a fork resize defect. No fixed resolution or orientation is forced.

## Reproduction and regression

```sh
npm ci --prefix tools/playgta5-validation
curl --fail --location https://raw.githubusercontent.com/shadany7824/playgta5/2fb671adca55cd0317aa551b33ffd922f1639660/wgpu_worker.js -o /tmp/wgpu_worker.js
node tools/playgta5-validation/validate.mjs /tmp/wgpu_worker.js
node --test catroid/src/test/js/*.test.cjs
```

The validator checks SHA-256
`e1b83d2b10a98d29d8f7ac45a4e42f68486144a8046df688667891af034716c7`,
loads the **entire original worker**, calls its `pixelModule`, and uses pinned
`webgpu@0.6.2` Dawn/Tint (null backend; no rasterization).

Local result: 8 cases. The two original scalar variants genuinely fail with
`cannot index into expression of type 'f32'`. Original RGBA and all four
corrected variants pass WGSL compilation and synchronous **and** asynchronous
render-pipeline validation. The full 27-test JavaScript suite passes 27/27 in
this follow-up, including synthetic binding-44, hash-verification and audio
summary coverage. These tests are not real WebGPU error events and do not run
the full game's render workload.

`ShaderCompatibilityInjectionTest` covers the server's independent opt-in,
unchanged default bytes, same-prefix resource, HEAD/Content-Length, unknown
worker rejection, and the pinned full worker combined with diagnostics. CI
supplies `PLAYGTA5_WORKER_SOURCE` for the full-worker integration case. Without
that environment variable only that case is explicitly skipped, never counted
as a real snapshot pass. The local Kotlin test could not start because this
environment has neither `JAVA_HOME` nor a `java` command; no local Kotlin result
or APK was produced. The updated [Actions run 38813369221](https://github.com/xysgottaken2/NewCatroid/actions/runs/38813369221)
ran the focused Kotlin and JavaScript tests, Dawn/Tint regression, and debug APK
build/upload successfully. Its optional full unit-test suite was skipped; no
visual test ran on the Galaxy S23 Ultra.

## Applying and distinguishing the experiment on the S23 Ultra

Append `newcatroid_alpha_fix=1` to the existing local game URL. For example:

```text
http://127.0.0.1:<port>/<existing-page>?newcatroid_debug=1&newcatroid_alpha_fix=1
```

Use `&` if the URL already contains `?`. Keep the same scene, options, data and
orientation in the baseline and corrected runs. Initially omit
`newcatroid_adreno_fix` from **both** runs to isolate the shader correction.
If comparing sync pipelines later, enable it in both members of that comparison.

The server appends the correction only to the exact reviewed GPU-worker bytes.
With `newcatroid_debug=1` as well, the worker receives a structured local
verification record containing the expected and actual SHA-256, match result,
and whether the shim was appended; the separate runtime hook snapshot reports
whether the shim actually installed. A hash mismatch leaves the original worker
unchanged and is also logged locally. The short summary distinguishes
`newcatroid_alpha_fix=1` requested, the flag on the GPU-worker URL, server hash
verification, shim append, runtime hook and alpha-test variants seen. Variant
counts mean the wrapper handled shader-generation calls, not that a pipeline
validated or pixels were displayed. Do not bypass the hash check: review the
actual different client first. Hosted game files are not modified, and disabling
the parameter restores the original response.

The panel's primary `Copy short summary` action is capped at 5,000 characters.
Version 3 includes browser/platform version evidence, worker
adapter/requested/enabled-device features, first/latest WebGPU error groups and
nearby operation context, targeted `binding 44` WGSL/layout/texture/sampler/
pipeline/draw evidence, server worker-hash status, active flags,
canvas/submission observations, and audio context/state-change/gesture/
resume/start/play/worklet/node/worker-message signals. It excludes the full
console log and full stack; URLs/query values and common private fields are
sanitized. Share only the compact summary first; the longer `Copy full report`
remains optional.

Required acceptance evidence: visible world with correct geometry/textures over
multiple frames, continued input after pause/resume, matching capture without
rejected relevant pipelines/device loss, and a separate audio test. Queue submits,
100%, `Ready`, compiler success and the APK build are **not** acceptance evidence.

Remaining discriminating evidence:

| Hypothesis | Evidence still needed |
|---|---|
| This scalar bug contributes to this device failure | Actual failing shader/pipeline label and original error, disappearance with only this correction enabled, visual improvement |
| Other missing features/formats or device limits | Actual adapter/device feature and limit capture plus earliest validation error |
| Driver shader miscompile / Android Chrome compositor issue | Exact Chrome/Android platform versions and driver/firmware build, same client tested in another browser, back-buffer vs displayed-frame evidence |
| Async pipeline latency/starvation | Equal-condition captures of pending/failed pipelines and worker/queue progress, sync vs async |
| Memory, resource or internal engine trap | Original Wasm stack and preceding allocator/resource/thread events; process-memory evidence where available |
| Audio initialization or routing | Real gesture, worklet response, context state, ring progress and audible-output test |

The deliverable is a tested correction for one demonstrated renderer defect
and an APK for device validation. The broader mission remains unproven until
real-device evidence establishes that the world renders correctly.
