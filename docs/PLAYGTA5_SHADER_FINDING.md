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
- Run [38790036910](https://github.com/xysgottaken2/NewCatroid/actions/runs/38790036910)
  passed the focused local-web/download tests, pinned-worker Dawn/Tint regression,
  and debug APK build/upload. The full unit-test suite was skipped. Dawn uses a
  null backend and does not render pixels or emulate Adreno.
- The APK artifact was uploaded by that run; its successful build is not a
  device test. No independent hardware capture, full game assets, translated
  shader set, or `game.wasm` was available in this environment.

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
render-pipeline validation. The current 21 JS tests pass, including option
propagation, short-summary bounds/sanitization, alpha-fix worker confirmation,
and unchanged message/transfer identities. These checks are not synthetic
WebGPU error events, but are also not the full game's render workload.

`ShaderCompatibilityInjectionTest` covers the server's independent opt-in,
unchanged default bytes, same-prefix resource, HEAD/Content-Length, unknown
worker rejection, and the pinned full worker combined with diagnostics. CI
supplies `PLAYGTA5_WORKER_SOURCE` for the full-worker integration case. Without
that environment variable only that case is explicitly skipped, never counted
as a real snapshot pass. Gradle/APK validation runs in Actions, not locally.

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
The existing HTTP log records `scalar alpha-test renderer correction`; a
checksum mismatch produces a warning and the original worker is served
unchanged. With `newcatroid_debug=1` as well, the short diagnostics report
separates `newcatroid_alpha_fix=1` requested, the flag on the GPU-worker URL,
shim installation, alpha-test variants seen, non-vec4 bypasses and vec4
preservation. If the URL flag is present but shim installation is not confirmed,
that is not evidence the correction ran; check the local HTTP warning. Variant
counts mean the wrapper handled shader-generation calls, not that a pipeline
validated or pixels were displayed. Do not bypass the hash check: review the
actual different client first. Hosted game files are not modified, and disabling
the parameter restores the original response.

The panel's primary `Copy short summary` action is capped at 5,000 characters.
It includes up to three recent WebGPU error groups and nearby GPU operations,
the latest exception with only a stack head, active experiment flags, API counts,
canvas/submission observations and audio API state. It excludes the full console
log and full stack; URLs/query values and common private fields are sanitized.
Share that short summary first; the longer `Copy full report` remains optional.

Required acceptance evidence: visible world with correct geometry/textures over
multiple frames, continued input after pause/resume, matching capture without
rejected relevant pipelines/device loss, and a separate audio test. Queue submits,
100%, `Ready`, compiler success and the APK build are **not** acceptance evidence.

Remaining discriminating evidence:

| Hypothesis | Evidence still needed |
|---|---|
| This scalar bug contributes to this device failure | Actual failing shader/pipeline label and original error, disappearance with only this correction enabled, visual improvement |
| Other missing features/formats or device limits | Actual adapter/device feature and limit capture plus earliest validation error |
| Driver shader miscompile / Android WebView compositor issue | Exact WebView/provider and driver versions, same client tested in another browser, back-buffer vs displayed-frame evidence |
| Async pipeline latency/starvation | Equal-condition captures of pending/failed pipelines and worker/queue progress, sync vs async |
| Memory, resource or internal engine trap | Original Wasm stack and preceding allocator/resource/thread events; process-memory evidence where available |
| Audio initialization or routing | Real gesture, worklet response, context state, ring progress and audible-output test |

The deliverable is a tested correction for one demonstrated renderer defect
and an APK for device validation. The broader mission remains unproven until
real-device evidence establishes that the world renders correctly.
