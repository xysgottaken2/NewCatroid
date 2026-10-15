# Download reliability and hosted-game diagnostics

This note records what is changed, what the reported worker error does and does not prove, and how to collect useful on-device evidence. It deliberately separates a browser downloading hosted game assets from Catroid's URL-to-file blocks; they are different network paths.

## Evidence-based findings

### Hosted browser game assets

The game page runs inside `EmbeddedWebViewActivity`. Its JavaScript makes `fetch()` requests to the Catroid embedded HTTP server; those requests do **not** pass through `NewCatroidHttpManager`, `FileUrlAction`, or `FilesUrlAction`.

The checked public PlayGTA5 client snapshot uses a top-level `loader.js` worker, separate I/O and GPU workers, and an I/O worker that reads `/data/manifest.json`, uses `/data/batch` for batches of byte runs, tries cached `/data/batchc/<hash>.bin` responses, and issues `Range: bytes=start-end` requests for other asset ranges. The I/O worker streams fetched blocks into OPFS when available and falls back to a bounded in-memory cache when it cannot open its persistent store. It logs observed bytes, active fetches, wait time and MiB/s when its optional diagnostics are enabled. The snapshot's current `RUN_LANES` / `BATCH_LANES` settings are 32 / 4; those are client settings, not a performance guarantee for a different server or device.

In the Catroid server, each socket handler runs until the response finishes. Before this change the executor was constructed with `corePoolSize = 0`, `maximumPoolSize = 8`, and a queue of 64. With a non-full work queue, `ThreadPoolExecutor` ordinarily keeps a single worker and places new connections in the queue; the maximum of eight was reached only after that queue filled. That configuration serialized the client's ordinary range requests and is a concrete server-side concurrency defect consistent with poor throughput. The server now grows on demand up to 36 workers (32 range lanes plus four batch lanes), retains a bounded 64-connection queue, and allows idle workers to time out. A socket-level regression test blocks document opens and asserts that 16 requests can enter the store concurrently. Its source is present, but no JVM test result is available yet. Even when it passes, it will prove the concurrency behavior, **not** a measured improvement in MB/s on the target phone or a SAF provider.

Large ordinary GET/range bodies are streamed by `HttpEngine` in 64 KiB chunks. The `/data/batch` handler is a separate bounded implementation that assembles each response in memory (maximum 64 MiB); the researched client groups its compressed batches at about 4 MiB. No batch endpoint semantics or hosted files were changed in this work.

### URL-to-file blocks

There are two separate existing blocks:

- `FilesUrlBrick` saves into the current project's `filesDir`.
- `FileUrlBrick` saves into the user's public Downloads folder.

The previous `FilesUrlAction` was a `TemporalAction` that started an IO coroutine, opened a `HttpURLConnection`, and copied directly to the final file. It did not wait for the transfer, provide progress state, cancel on action reset, validate the completed length, or preserve a pre-existing destination atomically. The generic `NewCatroidHttpManager.executeRequest()` path is different again: it intentionally stores an HTTP response body in a `ByteArray` for the HTTP formula blocks, so it is not suitable for a very large download.

`FilesUrlAction` now uses `DownloadFileAction` and `NewCatroidHttpManager.downloadToFile()`. The block waits for a terminal state; the manager uses a bounded two-worker queue, writes a 64 KiB stream to a sibling partial file, validates known body/range lengths, flushes before finalization, and atomically replaces the destination only on success. HTTP status plus a bounded error-body excerpt is retained in the state. `bytesDownloaded`, `totalBytes`, `progressPercent`, response code, cancellation and a throttled progress callback are available to the caller; the action logs progress at 5% intervals (or every 4 MiB if the server gives no length) and reports completion/failure to the user.

A partial is resumed only if the previous response supplied a strong ETag or Last-Modified validator. The next request uses `Range` plus `If-Range`; a valid `206 Content-Range` is checked before appending. A server that returns `200` instead is treated as a full restart. Without a validator, an interrupted partial is removed on the next attempt. A failed or cancelled transfer never replaces the existing final file. Regression tests are defined for large streamed bodies, HTTP errors, truncated bodies, safe continuation, cancellation, the optional state parameter, action reset/reuse and destination preservation; they still need a JVM/Gradle run.

`FileUrlAction` remains the separate legacy public-Downloads path; it was not migrated to the app-private file downloader because changing public Downloads storage semantics requires a `DownloadManager`/MediaStore design and collision/permission behavior to preserve. Its direct `HttpURLConnection` path is not the PlayGTA5 worker path and should be treated as an outstanding Android scoped-storage and lifecycle follow-up, not as fixed by `FilesUrlAction`.

### The reported `worker sent an error!` text

The public snapshot's Emscripten worker error formatter interpolates `event.filename`, `event.lineno`, and `event.message` directly. If the browser omits one of those fields, the resulting message can contain the literal word `undefined`. That is a diagnostic-format weakness, **not** the cause of the worker failure. The actual cause may be an HTTP error, an unsupported browser feature, an unhandled exception, a worker startup failure, or something inside the unavailable game module; the text alone does not distinguish them.

`EmbeddedWebViewActivity` now observes errors from page-created `Worker` instances and records a JSON diagnostic with explicit `(not reported)` / `null` values, plus `messageerror`. The WebView capability report and the server's built-in isolation page also probe cross-origin isolation, SharedArrayBuffer construction, shared WebAssembly Memory64, Workers, OffscreenCanvas, AudioWorklet, OPFS API presence, WebGPU API presence and asynchronous adapter availability. These probes improve evidence collection; they do not suppress errors or prove that the game can render.

The worker observer wraps Workers created by the page. It does not inject code into a worker's own global scope, so nested worker failures may still need to be read from their parent worker's forwarded console output.

## Kotlin `state` shadowing question

The checked-out merged branch did not contain `DownloadFileAction.kt` or a `downloadToFile()` function with `val state = state ?: DownloadState(...)`; it therefore had no current reset race at that expression to reproduce. In Kotlin, the initializer's right-hand `state` resolves to the function parameter (the new local is not in scope until its initializer has completed). The implementation here names the result `resolvedState` to make that intent unambiguous. A regression test verifies that a supplied state is returned/used only for the matching URL and destination; reset/restart cancels the action's own state rather than sharing a mutable completion flag.

## Verification status

- `node --test catroid/src/test/js/newcatroid_touch_controls.test.cjs` passes locally (5 tests), including a regression for releasing held controls when the editor opens. `git diff --check` is clean.
- JVM/Gradle unit tests and the APK build have not run locally: this sandbox has no `java` executable and `JAVA_HOME` is unset. The GitHub Actions workflow is configured to run the focused local-web/download tests, the Node suite, and then upload a debug APK artifact after the test job succeeds; no Actions result or artifact exists until that workflow completes.

## What remains unproven

- No throughput benchmark on a SAF-backed directory or a Galaxy S23 Ultra has been performed. New request diagnostics report per-request bytes, duration and derived MiB/s so a device run can compare before/after conditions without inventing a speed figure.
- The reported failure near 167 MB has not been tied to an HTTP request, a Web Worker, an OPFS write, a WASM memory allocation, or a particular action. The live PlayGTA5 root returned HTTP 401 during this investigation; no credentials or access controls were bypassed. The NewCatroid checkout contains neither the game's `game.wasm` nor its data mirror.
- The target Galaxy S23 Ultra / Android 16 device was not available to test. No gameplay/device validation is claimed.

## Device verification checklist

1. Use a local game/site tree that you are authorized to access; start the server with the existing blocks and open it in the embedded WebView. The touch overlay remains opt-in via `?newcatroid_touch=1`.
2. Open **Diag** in the WebView and save/copy the diagnostics shortly before and after a failure. Check the worker JSON, capability probes, HTTP errors and resource errors.
3. In the server request log, compare `bytes`, `duration`, and `rate=... MiB/s` for the same requests across repeated runs. Keep power mode, Wi-Fi, app state, storage provider, game profile, and background load constant. These are per-request local-server rates, not an independent internet speed test.
4. For the `FilesUrl` block, test success, HTTP 404/503, network interruption, action restart/cancel, a server with ETag/Range support, and a server without a validator. Verify the final file checksum and that an existing destination remains unchanged after failure.
5. Record the exact Android/WebView version and copy the diagnostic log; do not infer a game-specific key meaning from the browser input table alone.
