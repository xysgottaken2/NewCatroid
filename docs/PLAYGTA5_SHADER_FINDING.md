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

- PR #3 remains on `arena/5736be66-newcatroid`, initially at
  `513a5ea8918ccc6cc8c3a1d04d076b240e93feaa`; all nine prior PR commits retained.
- `baec468f` through `7c88f872`: HTTP concurrency, resumable streamed downloads,
  touch controls, cancellation/resume tests. These address other failure paths;
  they do not prove graphics compatibility.
- `fb6a489b`: APK build independent of focused tests; full suite opt-in. Retained.
- `93268f86`: debug capture and manual synchronous pipeline option.
- `513a5ea8`: more detailed observations and URL query redaction, with synthetic
  error fixtures. No actual GPU failure captured. The 17 existing JS tests pass.
- Run [38785385619](https://github.com/xysgottaken2/NewCatroid/actions/runs/38785385619):
  focused tests and APK succeeded; full suite skipped; APK artifact 11998621504
  present and not expired when checked. No PR discussion comments were returned.

No earlier graphics hypothesis was actually ruled out by those synthetic tests.
The new experiment differs: it executes the original worker's transformation
and submits its resulting WGSL to a real compiler, before and after the change.

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
render-pipeline validation. The 19 JS tests pass, including option propagation
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
The existing HTTP log records `scalar alpha-test renderer correction`; the
worker emits one application confirmation. A different SHA produces a clear
server warning and the original worker is served unchanged. Do not bypass that
check: review the actual different client first. Hosted game files are not
modified, and disabling the parameter restores the original response.

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
