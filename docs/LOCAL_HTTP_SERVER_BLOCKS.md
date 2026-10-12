# Local Web Blocks: SAF selection, embedded HTTP server and WebView

NewCatroid can pick files/directories from the user's storage, serve them from
an embedded HTTP server running on the device, and display the result in an
in-app WebView — all from visual programming blocks.

## The blocks

All blocks live in the **Internet** category of the block list.

### 0. Select folder, save to variable and host *(recommended for hosting a game)*

Combined block: opens the system folder picker, waits for the user, stores
the selected SAF tree URI in the chosen variable **and** configures the
embedded HTTP server to serve that same folder at the `/` URL prefix —
atomically. The URI object returned by the picker is passed directly to the
hosting manager, so the block does not depend on a separate "Host directory"
block or on formula evaluation carrying the value between blocks.

- On **cancel** nothing changes: the variable keeps its previous value and
  the server configuration is untouched.
- The two operations (save to variable, configure hosting) are independent;
  a failure of either is reported through the *local HTTP server error*
  reporter (and a toast) without undoing the other.
- Success is confirmed by checking that the manager accepted the document
  root; the *local HTTP server error* reporter stays empty.

### 1. Select directory and store in [variable] *(optional: initial directory)*

Opens the system directory picker (Storage Access Framework,
`ACTION_OPEN_DOCUMENT_TREE`). The URI of the selected directory is stored as a
string in the chosen user variable.

- The block **waits** until the picker is closed.
- On **cancel** (or when the returned URI is not accessible) the variable
  **keeps its previous value** — a cancelled selection never overwrites a
  valid earlier one.
- The permission for the selected directory is persisted
  (`takePersistableUriPermission`), so the directory stays readable after the
  picker closes and across restarts.
- Nothing is copied into app-private storage; the variable contains a
  `content://` URI.
- The optional *initial directory* formula pre-selects a directory in the
  picker (API 26+).

### 2. Select file and store in [variable] *(MIME type)*

Opens the system file picker (`ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE`)
and stores the selected file's `content://` URI in the chosen user variable.
The MIME type field filters the picker (default `*/*` = all files). Same
cancellation/permission semantics as the directory block.

### 3. Host directory at [directory URI] *(optional: URL prefix)*

Configures **which** directory the embedded server serves. The directory URI
is typically the variable filled by block 1 (or a `content://` URI string).
The optional URL prefix mounts the directory below a path, e.g. prefix
`/game` serves the directory at `http://127.0.0.1:8080/game/...`.

This block only **configures** the server; it does not start it.

### 4. Start local HTTP server on port [port] *(bind address)*

Starts the embedded HTTP server. Defaults: port `8080`, bind address
`127.0.0.1` (loopback only).

- The block completes only after the server has actually started, so the
  reporter blocks below can be used right after it.
- Starting an already-running server with the **same** port/address is a
  no-op success; with a **different** port/address the server is restarted.
- If the port is occupied or invalid, the block finishes and the
  *local HTTP server error* reporter returns the reason (see below).
- Binding to a non-loopback address (e.g. `0.0.0.0`) is possible but **not
  the default** — it exposes the served directory to the local network.
  Only do this on trusted networks, and only with a directory the user
  explicitly selected.

### 5. Stop local HTTP server

Stops the server. Idempotent: stopping a stopped server does nothing.

### 6. Open web page at [URL] *(optional: target)*

Opens a URL in an in-app WebView.

- **Without** a target: the URL opens in the full-screen embedded WebView
  (`EmbeddedWebViewActivity`) with **loopback-only** origin restrictions
  (`http(s)://127.0.0.1`, `localhost`, `[::1]`, `0.0.0.0`, any port).
  Navigation to any other origin is blocked and shown as an error.
- **With** a target name: the URL loads into the named in-stage WebView of
  the existing native-view architecture (created by the existing
  "Create WebView" blocks); if no such view exists, a full-size one is
  created for that name. The same origin policy applies.

The embedded WebView has JavaScript, DOM storage and WebAssembly enabled and
**no JavaScript bridge** to the app. It shows a progress bar, an error state,
and offers back/reload/close controls; pressing back navigates back in the
WebView history and finishes the activity when there is nothing to go back
to.

## Reporter (formula) functions

Catroid reports values through formula functions. The following are available
in the function picker (and therefore usable in *if* conditions, text display,
string concatenation and comparisons):

| Function | Type | Meaning |
|---|---|---|
| `local HTTP server running?` | boolean | `true` while the embedded server is running |
| `local HTTP server URL` | string | base URL of the running server (e.g. `http://127.0.0.1:8080/`), empty when stopped |
| `local HTTP server error` | string | last start error (occupied port, invalid address, ...), empty when none |
| `display name of selected item(uri)` | string | display name of a selected file/directory; with an empty argument the most recent selection is used |
| `selected item URI(uri)` | string | normalized `content://` URI of a selected item (a URI, never a claimed absolute file-system path) |

All reporter values are also plain user-variable-compatible strings, so they
can be stored, concatenated and compared like any other value.

## Recommended block order

The blocks are designed to be used in this order:

1. **Select folder, save to variable and host** → one atomic block: picks the
   folder, stores its URI in a variable and configures it as the HTTP document
   root at `/` (default URL `http://127.0.0.1:8080/`). The separate
   **Select directory** + **Host directory** blocks remain available and are
   still the way to host a fixed directory without a picker.
2. **Start local HTTP server** → starts serving; use the *running?* / *URL* /
   *error* reporters to react to the result (e.g. show the URL or an error
   message).
3. **Open web page** → open the server URL (from the *local HTTP server URL*
   reporter, or `http://127.0.0.1:8080/` for an `index.html` entry, or the
   entry file's own URL like `http://127.0.0.1:8080/game.html`) in the
   embedded WebView.
4. **Stop local HTTP server** → when the project/scene ends (the server is
   also stopped automatically when the stage activity is destroyed).

Steps 1 can be replaced by **Select directory** + **Host directory** when
hosting a fixed directory; step 3 can target any loopback URL, not only the
embedded server.

## Nested directories

The selected folder is served as the root of a complete directory tree:
files at the root and at arbitrary nesting depth are resolved directly
through the document provider (each requested path is addressed as one
tree-scoped document URI; a display-name walk is the fallback for providers
with opaque document ids). There is no depth limit and no full-tree scan per
request. Nested paths with spaces, Unicode characters (including names stored
in a different Unicode normalization form than the request, e.g. NFD on disk
vs NFC in the URL) and URL-encoded characters are handled, directory listings
include subdirectories at every level, and path traversal above the selected
root is rejected (in-root `..` segments normalize per RFC 3986 dot-segment
removal, exactly like `/assets/../` serving the root index).

Regression coverage lives in `SafDeepTreeTest` (document resolution) and
`SafDeepHostingEndToEndTest` (full HTTP pipeline) using a fixture that models
the reported device structure:

```
Game/
|- index.html, root.js
|- assets/app.js
|- assets/nested/module.js
|- assets/nested/deep/config.json      (three directory levels deep)
|- data/config.json                    (independent second branch)
|- my folder/spaced file.js            (spaces)
`- ícones/häupt.js, café(résumé).js   (Unicode, incl. NFD on-disk names)
```

**How nested paths are resolved (SAF contract, not assumptions):** a
requested path is resolved in two steps. First a fast path queries the
document directly through one tree-scoped document URI
(`DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId + "/" +
relativePath)`) — an optimization that is only valid for providers whose
document ids are path-like; the result is accepted only when it stays inside
the selected tree AND the provider's own display name matches the requested
last segment. Second, the contract path walks the tree segment by segment:
each step lists the children of the current directory through its tree-scoped
children URI (`buildChildDocumentsUriUsingTree`) and matches the display
name, using the matched row's own document id for the next step, and the
final entry is taken from the last listing row itself — so resolution never
depends on a document query with a constructed URI, and works for providers
with opaque (non-path-like) document ids. Tree confinement is verified
against the provider (a tree-scoped query only succeeds for descendants of
the granted tree), with a string check as the fast path for path-like ids.
Query failures are logged with the exact document URI (`SafDocumentStore`
tag) instead of being silently turned into empty listings.

**Research basis (external):** androidx `DocumentFile`/`TreeDocumentFile`
resolve children exactly this way (children URI + display-name match +
row's document id); a real project serving SAF trees over HTTP, verified on
a Samsung device (tophatch/swift-pwa#252), states a path under the mount
"has to become a document URI, which is a DocumentsContract walk rather than
string concatenation — a provider's document ids are its own business"; the
AOSP android-14 `ExternalStorageProvider` uses `rootId:path` document ids
(concatenation happens to hold there) and its `queryDocument`,
`queryChildDocuments`, `enforceTree` and `isChildDocument` are all
depth-agnostic, hiding only `Android/data|obb|sandbox` from listings.

**Testing limitation:** the SAF tests run under Robolectric against fakes
that extend the *real* `android.provider.DocumentsProvider`, so the genuine
AOSP URI matcher and tree-descendant enforcement execute. Two fixture
styles model the storage backend: an in-memory tree, and
`FakeFileSystemDocumentsProvider`, which models the real
`ExternalStorageProvider` on an actual on-disk directory tree (document ids
derived from file paths, canonical file resolution, real listings,
scoped-storage `Android/data` hiding, canonical-containment
`isChildDocument`, real file streams). `FakeOpaqueIdDocumentsProvider`
models providers with opaque document ids to prove the contract walk. A
physical-device (instrumented) SAF traversal test is not feasible in this
environment; the on-device verification steps are: pick a folder containing
the structure above with the combined block, start the server, and request
`/assets/nested/deep/config.json` (expect 200), browse `/assets/nested/`
(expect `module.js` and `deep/`), and check `logcat` for `SafDocumentStore`
tagged lines if any nested path 404s — they name the exact document URI or
document id whose query failed (`adb logcat -s SafDocumentStore`).

## What the server does

- Serves the hosted SAF directory (or a single selected file) via
  `ContentResolver`/`DocumentsContract` — no copying, no broad storage
  permissions; only the directory the user picked is readable.
- `GET` and `HEAD` only (other methods → `405`).
- Correct MIME types for ~90 common extensions (text types carry
  `; charset=utf-8`).
- `206 Partial Content` for single `Range` requests (`416` when
  unsatisfiable). Multi-range requests serve the first range.
- Files are streamed with bounded buffers (64 KiB); unknown sizes use
  `Transfer-Encoding: chunked`.
- Directory requests redirect to a trailing slash (`301`), serve
  `index.html`/`index.htm` when present, otherwise a minimal HTML listing
  (names are HTML-escaped and URL-encoded).
- Requests are served off the UI thread by a small thread pool; slow clients
  time out after 15 s; the request line is limited to 8 KiB and headers to
  16 KiB / 100 entries.

## Security notes

- **Loopback by default.** The server binds to `127.0.0.1`. It never binds to
  the LAN unless a block explicitly sets a non-loopback bind address.
- **Scoped access.** Only the user-selected SAF directory (or file) is
  served. The app does **not** request `READ_EXTERNAL_STORAGE` /
  `MANAGE_EXTERNAL_STORAGE`; the SAF grant is sufficient.
- **Path confinement.** Request paths are percent-decoded first and then
  normalized; `..` segments, backslashes, NUL bytes, Windows drive prefixes
  and absolute-form targets (`//host/path`) are rejected with `403`.
  Encoded traversal (`%2e%2e`, `%2f`, `%5c`) is caught the same way because
  decoding happens before validation. The file-system store additionally
  re-checks canonical paths (defense in depth); the SAF store confines
  document IDs to the selected tree.
- **No directory escapes.** A request that normalizes outside the hosted
  root can never reach the store: the engine rejects it before resolving, and
  the stores confine independently.
- **WebView origin policy.** The embedded WebView only loads loopback
  origins. External navigation is blocked and reported as an error.
- **No JavaScript bridge.** The embedded WebView exposes no `addJavascriptInterface`
  object to loaded pages (unlike the legacy stage WebView used by the JS
  blocks, which is unchanged).
- **Cancellation safety.** A cancelled picker never overwrites the variable,
  so a stale/empty URI cannot silently replace a working selection.

## WebView capability reporting

After each page load the embedded WebView evaluates a small diagnostic
script (plain JavaScript, surfaced via the console log — no bridge) and
reports which capabilities the page sees:

- `javascript` — always `true` (JavaScript is enabled).
- `webAssembly` — `typeof WebAssembly === 'object'`.
- `webWorker` — `typeof Worker === 'function'`.
- `crossOriginIsolated` — the page's `crossOriginIsolated` value.
- `sharedArrayBuffer` — `typeof SharedArrayBuffer === 'function'`.
- `webgpu` — `navigator.gpu` present.
- `webgl` / `webgl2` — a canvas context can actually be created.

Interpretation:

- `false` means the **platform/WebView reports the feature as unsupported**
  for that page.
- `crossOriginIsolated: false` additionally means `SharedArrayBuffer` is
  typically unavailable, because cross-origin isolation is required for it —
  this server does not send the `Cross-Origin-Opener/Embedder` headers
  needed for isolation.
- WebGPU (`navigator.gpu`) on Android WebView depends on the device, the
  WebView version and flags; treat a `true` here as "the API object exists",
  not as a guarantee that a GPU adapter can be requested.
- **Untested:** actual WebGL rendering performance/quality, WebGPU adapter
  requests, and Worker-based multi-threading with `SharedArrayBuffer` have
  not been exercised on physical devices in this project. The report tells
  you what the page *sees*; it does not certify that heavy workloads run
  well. If a project needs guaranteed WASM threading or WebGPU, verify on the
  target devices first.

## Known limitations

- Single-range requests only (the first range of a multi-range request is
  served).
- One request per connection (`Connection: close`); no keep-alive, no HTTP/2.
- Directory listings are minimal (no sorting controls, capped at 1000
  entries).
- The embedded WebView is a single shared instance; opening a second URL
  replaces the current page.
- SAF directory listing of very large directories is capped by the provider.
- The server is stopped when the stage activity is destroyed; it is not
  restarted automatically with the activity.
