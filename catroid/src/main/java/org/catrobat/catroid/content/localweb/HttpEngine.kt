/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://www.gnu.org/licenses/.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.localweb

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Severity for [HttpLogger] request diagnostics. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Sink for per-request diagnostics. Pure JVM (no Android dependencies) so
 * the engine stays unit testable; the Android layer wires this to
 * `android.util.Log` (see LocalHttpServer).
 */
typealias HttpLogSink = (level: LogLevel, message: String, error: Throwable?) -> Unit

/**
 * Stateless HTTP request handler used by [HttpServerEngine].
 *
 * Serves static files from a [DocumentStore] (plain filesystem directory or
 * a SAF document tree). Supports GET and HEAD, byte ranges (206), directory
 * index files and simple directory listings. Pure JVM so the whole request
 * pipeline can be unit tested without a device.
 *
 * One request is handled per connection; responses always carry
 * `Connection: close`.
 *
 * Response protocol correctness: a response body is never promised before it
 * can be delivered. The document stream is opened BEFORE any status line or
 * headers are written, small files are buffered so `Content-Length` always
 * matches the actual bytes, and a failure while streaming aborts the
 * connection without appending anything to a response that already started -
 * a client therefore never receives a corrupted or self-contradicting
 * response (which would break scripts, WASM modules and textures and leave
 * applications stuck on their loading screen).
 */
class HttpEngine(
    private val storeProvider: () -> DocumentStore?,
    private val urlPrefixProvider: () -> String = { "/" },
    private val logger: HttpLogSink = { _, _, _ -> },
    private val addCrossOriginIsolationHeaders: Boolean = true,
    private val requestLog: RequestLog? = null,
    private val touchControlsScriptProvider: (() -> String?)? = null,
    private val gameDiagnosticsScriptProvider: (() -> String?)? = null,
    private val workerDiagnosticsBootstrapProvider: (() -> String?)? = null
) {

    /** Cache of discovered entry points per directory (tree-relative path). */
    private val entryPointCache = HashMap<String, String?>()
    private val entryPointCacheLock = Any()

    /**
     * Fallback searcher for request paths whose SITE root is nested inside
     * the selected folder (a downloaded site placed in a mirror/wrapper
     * directory): the full requested path must exist, uniquely, somewhere
     * under the served root. Applied ONLY to the main request path - never to
     * default-document or entry-point discovery, which must stay direct.
     * Recreated when the served store changes (setDocumentRoot).
     */
    private var suffixSearcher: PathSuffixSearcher? = null
    private var suffixSearcherStore: DocumentStore? = null

    /** Redacts query strings in diagnostics while leaving the HTTP target untouched. */
    private fun sanitizedLogTarget(rawTarget: String): String {
        val queryDelimiter = rawTarget.indexOf('?')
        return if (queryDelimiter < 0) rawTarget else rawTarget.substring(0, queryDelimiter) + "?[query redacted]"
    }

    /**
     * Handles a single HTTP request and writes the full response (status line,
     * headers and, for GET, the body) to [out]. Never throws for expected
     * error conditions; unexpected exceptions are turned into 500 responses.
     *
     * @param method HTTP method (GET or HEAD expected).
     * @param rawTarget request target, e.g. "/sub/page.html?a=1".
     * @param headers lower-cased header name -> value map.
     * @param out raw socket output stream (buffered by the caller).
     */
    fun handle(
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        out: OutputStream,
        body: ByteArray? = null
    ) {
        val response = ResponseStream(out)
        val loggedTarget = sanitizedLogTarget(rawTarget)
        val record = RequestRecord(method, loggedTarget)
        val startedAt = System.nanoTime()
        try {
            handleInternal(method, rawTarget, headers, body, response, record)
        } catch (e: Exception) {
            if (!response.started) {
                record.status = 500
                record.detail = "internal error: ${e.message}"
                logger(LogLevel.ERROR, "$method $loggedTarget -> request failed before any response was sent", e)
                try {
                    response.writeSimpleError(500, "Internal Server Error", "500 Internal Server Error")
                } catch (ignored: Exception) {
                }
            } else {
                // The response is already on the wire. Appending an error
                // response would corrupt the body the client is reading;
                // abort by closing the connection instead (the caller does).
                record.status = response.status
                record.bytesSent = response.bytesWritten
                record.detail = "aborted mid-stream: " + when (e) {
                    is EOFException -> "document stream ended early (truncated file?)"
                    is java.io.IOException -> "connection failed while writing (client closed early?)"
                    else -> "unexpected error: ${e.message}"
                }
                logger(LogLevel.ERROR, "$method $loggedTarget -> response aborted mid-stream (${record.detail}, ${response.bytesWritten} body bytes sent)", e)
            }
        } finally {
            try {
                out.flush()
            } catch (ignored: Exception) {
            }
            record.durationMs = (System.nanoTime() - startedAt) / 1_000_000
            requestLog?.add(record.toEntry())
        }
    }

    /** Mutable per-request record; converted to a [RequestLogEntry] when done. */
    private class RequestRecord(val method: String, val path: String) {
        var status: Int = 0
        var mimeType: String? = null
        var bytesSent: Long = 0
        var detail: String? = null
        var durationMs: Long = 0

        fun toEntry(): RequestLogEntry = RequestLogEntry(
            timestampMs = System.currentTimeMillis(),
            method = method,
            path = path,
            status = status,
            mimeType = mimeType,
            bytesSent = bytesSent,
            durationMs = durationMs,
            detail = detail
        )
    }

    /**
     * Output stream wrapper that records whether a response has started, its
     * status, and how many body bytes were written (for abort diagnostics).
     */
    private class ResponseStream(private val delegate: OutputStream) : OutputStream() {
        @Volatile
        var started = false
            private set

        var status: Int = 0

        var bytesWritten: Long = 0
            private set

        private var countBodyBytes = false

        /** Starts counting written bytes (called once the headers are on the wire). */
        fun beginBody() {
            countBodyBytes = true
        }

        override fun write(b: Int) {
            started = true
            if (countBodyBytes) bytesWritten++
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            started = true
            if (countBodyBytes) bytesWritten += len
            delegate.write(b, off, len)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private fun handleInternal(
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        body: ByteArray?,
        out: OutputStream,
        record: RequestRecord
    ) {
        val rawPath = rawTarget.substringBefore('?').ifEmpty { "/" }
        val query = if ('?' in rawTarget) rawTarget.substringAfter('?') else ""
        val loggedTarget = sanitizedLogTarget(rawTarget)

        val normalizedEarly = HttpPaths.normalize(rawPath)

        // Built-in data batch endpoint (POST /data/batch). Hosted games whose
        // engine streams its data through an io worker (like the playgta5
        // build) ask for whole batches of byte ranges in ONE POST: "thousands
        // of small archive pieces cost one round trip per batch instead of one
        // each" (the reference server implements exactly this contract).
        // Without it the engine's start-up data arrives one SAF open+Range
        // round trip per piece and the boot stalls right after the engine
        // starts. Matched before the method/prefix checks because the game's
        // io worker always addresses it at <origin>/data/batch.
        if (normalizedEarly == DATA_BATCH_PATH) {
            handleDataBatch(method, rawPath, query, body, out, record)
            return
        }

        val isHead = method.equals("HEAD", ignoreCase = true)
        if (!method.equals("GET", ignoreCase = true) && !isHead) {
            out.writeSimpleError(405, "Method Not Allowed", "405 Method Not Allowed", extraHeaders = listOf("Allow" to "GET, HEAD"))
            record.status = 405
            record.detail = "method not allowed"
            logger(LogLevel.INFO, "$method $loggedTarget -> 405 method not allowed", null)
            return
        }

        // Normalize the full request path first: invalid paths and traversal
        // attempts escaping the served root are rejected before anything else.
        val normalized = HttpPaths.normalize(rawPath)
        if (normalized == null) {
            out.writeSimpleError(403, "Forbidden", "403 Forbidden (invalid path)")
            record.status = 403
            record.detail = "invalid or escaping path"
            logger(LogLevel.INFO, "$method $rawPath -> 403 invalid or escaping path", null)
            return
        }

        // Then confine the normalized path to the configured URL prefix.
        val relativeToPrefix = HttpPaths.stripPrefix(normalized, urlPrefixProvider())
        if (relativeToPrefix == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (path is outside the served prefix)")
            record.status = 404
            record.detail = "path is outside the served prefix"
            logger(LogLevel.INFO, "$method $rawPath -> 404 outside the served prefix", null)
            return
        }

        // Built-in diagnostic route, served by the server itself (with the
        // standard response headers): a minimal page that isolates WebView
        // cross-origin-isolation behavior from any hosted game's JavaScript -
        // it reports crossOriginIsolated, isSecureContext, SharedArrayBuffer,
        // WebAssembly, the user agent (WebView/Chromium versions), and the
        // headers the browser actually received for its own document.
        if (relativeToPrefix == ISOLATION_CHECK_PATH) {
            val body = ISOLATION_CHECK_HTML.toByteArray(StandardCharsets.UTF_8)
            writeResponseHeaders(
                out, 200, "OK",
                listOf(
                    "Content-Type" to "text/html; charset=utf-8",
                    "Content-Length" to body.size.toString()
                )
            )
            if (!isHead) out.write(body)
            record.status = 200
            record.mimeType = "text/html; charset=utf-8"
            record.detail = "built-in isolation check page"
            logger(LogLevel.INFO, "$method $rawPath -> 200 isolation check page", null)
            return
        }

        // Built-in optional touch controls overlay script. Served by the
        // server itself (a separate, removable file - no hosted game file is
        // modified). Injected into HTML pages only when requested with
        // ?newcatroid_touch=1 (see handleFile); it is independent of both
        // game diagnostics and the experimental graphics mode.
        if (relativeToPrefix == TOUCH_CONTROLS_PATH) {
            val script = touchControlsScriptProvider?.invoke()
            if (script == null) {
                out.writeSimpleError(404, "Not Found", "404 Not Found (touch controls unavailable)")
                record.status = 404
                record.detail = "touch controls script unavailable"
                return
            }
            writeBuiltInScriptResponse(method, rawPath, script, "touch controls overlay script", isHead, out, record)
            return
        }

        // The opt-in diagnostics are separate local resources. The page
        // bootstrap is injected only for newcatroid_debug=1 or
        // newcatroid_adreno_fix=1; the worker bootstrap is injected only into
        // the game workers requested by the diagnostic bootstrap.
        if (relativeToPrefix == GAME_DIAGNOSTICS_PATH) {
            val script = gameDiagnosticsScriptProvider?.invoke()
            if (script == null) {
                out.writeSimpleError(404, "Not Found", "404 Not Found (game diagnostics unavailable)")
                record.status = 404
                record.detail = "game diagnostics script unavailable"
                return
            }
            writeBuiltInScriptResponse(method, rawPath, script, "opt-in game diagnostics script", isHead, out, record)
            return
        }

        val store = storeProvider()
        if (store == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (no directory is being hosted)")
            record.status = 404
            record.detail = "no directory is being hosted"
            logger(LogLevel.WARN, "$method $rawPath -> 404 no directory is being hosted", null)
            return
        }

        var entry = store.resolve(relativeToPrefix)
        if (entry == null) {
            // The path does not exist directly under the served root. Pages
            // reference absolute URLs relative to their site root, which may
            // be nested inside the selected folder (a downloaded site placed
            // in a mirror/wrapper directory): locate the full requested path
            // under the served root, uniquely. This fallback applies ONLY to
            // the request path - default-document and entry-point discovery
            // below must stay direct (they look inside the requested
            // directory).
            entry = suffixSearcherFor(store).find(relativeToPrefix)
            if (entry == null) {
                out.writeSimpleError(404, "Not Found", "404 Not Found")
                record.status = 404
                record.detail = "document not resolved: $relativeToPrefix (no match anywhere in the selected tree)"
                logger(LogLevel.INFO, "$method $rawPath -> 404 document not resolved ($relativeToPrefix)", null)
                return
            }
            logger(
                LogLevel.INFO,
                "$method $rawPath -> resolved under a nested site root (${entry.id})",
                null
            )
        }

        if (entry.isDirectory) {
            handleDirectory(store, entry, rawPath, relativeToPrefix, query, isHead, out, record)
        } else {
            handleFile(store, entry, rawPath, headers, isHead, out, record, query)
        }
    }

    private fun handleDirectory(
        store: DocumentStore,
        entry: DocumentEntry,
        rawPath: String,
        normalized: String,
        query: String,
        isHead: Boolean,
        out: OutputStream,
        record: RequestRecord
    ) {
        // Redirect to a trailing slash so relative resource URLs resolve correctly.
        if (!rawPath.endsWith("/")) {
            val location = rawPath + "/" + if (query.isEmpty()) "" else "?$query"
            out.writeSimpleError(
                301, "Moved Permanently", "301 Moved Permanently",
                extraHeaders = listOf("Location" to location)
            )
            record.status = 301
            val loggedLocation = sanitizedLogTarget(location)
            record.detail = "slash redirect to $loggedLocation"
            logger(LogLevel.INFO, "GET $rawPath -> 301 directory slash redirect to $loggedLocation", null)
            return
        }

        for (indexName in INDEX_FILE_NAMES) {
            val index = store.resolve(if (normalized.isEmpty()) indexName else "$normalized/$indexName")
            if (index != null && !index.isDirectory) {
                handleFile(store, index, rawPath, emptyMap(), isHead, out, record, query)
                return
            }
        }

        // No default document at this directory: try to discover a generic,
        // deterministic entry point (web app manifest start_url, a single
        // HTML file, a single entry-bearing subdirectory, or a unique entry
        // directory in the subtree). The client is REDIRECTED to the entry
        // document's own URL - never served under the directory URL - so that
        // relative asset URLs resolve against the entry document's location.
        cachedEntryPoint(store, entry, normalized)?.let { entryPath ->
            val prefix = HttpPaths.normalizePrefix(urlPrefixProvider())
            val location = buildString {
                append(if (prefix == "/") "/" else "$prefix/")
                append(entryPath)
                if (query.isNotEmpty()) append('?').append(query)
            }
            writeEntryRedirect(out, location, isHead)
            record.status = 302
            val loggedLocation = sanitizedLogTarget(location)
            record.detail = "entry point redirect to $loggedLocation"
            logger(LogLevel.INFO, "GET $rawPath -> 302 entry point redirect to $loggedLocation", null)
            return
        }

        // No entry point found: render a minimal directory listing.
        record.status = 200
        record.mimeType = "text/html; charset=utf-8"
        record.detail = "directory listing (no default document or entry point)"
        logger(LogLevel.INFO, "GET $rawPath -> 200 directory listing (no default document or entry point)", null)
        val children = store.listChildren(entry).take(MAX_LISTING_ENTRIES)
        val title = escapeHtml(entry.name.ifEmpty { store.rootDisplayName })
        val body = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<title>Index of /").append(title).append("</title></head><body>")
            append("<h1>Index of /").append(title).append("</h1><ul>")
            if (normalized.isNotEmpty()) {
                append("<li><a href=\"../\">../</a></li>")
            }
            for (child in children) {
                val suffix = if (child.isDirectory) "/" else ""
                append("<li><a href=\"")
                append(escapeHtml(urlEncodePathSegment(child.name)))
                append("\">")
                append(escapeHtml(child.name))
                append(suffix)
                append("</a></li>")
            }
            append("</ul></body></html>")
        }.toByteArray(StandardCharsets.UTF_8)
        writeResponseHeaders(
            out, 200, "OK",
            listOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString()
            )
        )
        if (!isHead) out.write(body)
    }

    // ------------------------------------------------------------------
    // Generic entry-point discovery for directories without a default
    // document. Everything here is deterministic: a candidate is only used
    // when it is the single unambiguous choice, or when the application
    // itself declares it (web app manifest start_url). Anything ambiguous
    // falls through to the directory listing - the server never guesses
    // which nested page should launch.
    // ------------------------------------------------------------------

    private fun cachedEntryPoint(
        store: DocumentStore,
        directory: DocumentEntry,
        dirRelativePath: String
    ): String? {
        synchronized(entryPointCacheLock) {
            if (entryPointCache.containsKey(dirRelativePath)) {
                return entryPointCache[dirRelativePath]
            }
        }
        val discovered = discoverEntryPoint(store, directory, dirRelativePath)
        synchronized(entryPointCacheLock) {
            if (entryPointCache.size >= MAX_ENTRY_POINT_CACHE_ENTRIES) {
                entryPointCache.clear()
            }
            entryPointCache[dirRelativePath] = discovered
        }
        return discovered
    }

    /**
     * Finds the entry document for a directory that has no default document.
     *
     * @return the tree-relative path of the entry document, or null when no
     *         unambiguous entry point exists (the caller then shows a listing).
     */
    private fun discoverEntryPoint(
        store: DocumentStore,
        directory: DocumentEntry,
        dirRelativePath: String
    ): String? {
        // 1. Web App Manifest (W3C standard): the application itself declares
        //    its start_url. Relative values resolve against the manifest's
        //    directory, exactly like a browser resolves them.
        for (manifestName in MANIFEST_FILE_NAMES) {
            val manifest = store.resolve(joinPath(dirRelativePath, manifestName))
            if (manifest != null && !manifest.isDirectory) {
                val text = readDocumentText(store, manifest)
                if (text != null) {
                    extractJsonStringField(text, "start_url")?.let { startUrl ->
                        resolveStartUrl(store, dirRelativePath, startUrl)?.let { return it }
                    }
                }
            }
        }

        val children = store.listChildren(directory)

        // 2. Exactly one HTML file in this directory: it is the only possible
        //    entry point, so it is not a guess.
        val htmlFiles = children.filter { !it.isDirectory && it.name.isHtmlFileName() }
        if (htmlFiles.size == 1) return joinPath(dirRelativePath, htmlFiles[0].name)

        // 3. Exactly one subdirectory that contains a default document.
        val subdirs = children.filter { it.isDirectory }
        if (subdirs.size == 1) {
            val sub = subdirs[0]
            for (indexName in INDEX_FILE_NAMES) {
                val doc = store.resolve(joinPath(joinPath(dirRelativePath, sub.name), indexName))
                if (doc != null && !doc.isDirectory) {
                    return joinPath(joinPath(dirRelativePath, sub.name), indexName)
                }
            }
        }

        // 4. Bounded subtree search: exactly one directory in the subtree
        //    contains a default document. Two or more candidates are
        //    ambiguous and keep the listing.
        return findUniqueEntryDocument(store, dirRelativePath)
    }

    /**
     * Breadth-first search (bounded depth and directory count) for
     * directories containing a default document. Returns the tree-relative
     * path of the default document when exactly one such directory exists in
     * the subtree, null otherwise.
     */
    private fun findUniqueEntryDocument(store: DocumentStore, dirRelativePath: String): String? {
        var found: String? = null
        var visited = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(dirRelativePath to 0)
        while (queue.isNotEmpty() && visited < MAX_ENTRY_SEARCH_DIRECTORIES) {
            val (current, depth) = queue.removeFirst()
            if (depth >= MAX_ENTRY_SEARCH_DEPTH) continue
            val dirEntry = store.resolve(current) ?: continue
            if (!dirEntry.isDirectory) continue
            visited++
            for (child in store.listChildren(dirEntry)) {
                if (!child.isDirectory) continue
                val childPath = joinPath(current, child.name)
                for (indexName in INDEX_FILE_NAMES) {
                    val doc = store.resolve(joinPath(childPath, indexName))
                    if (doc != null && !doc.isDirectory) {
                        if (found != null) return null // ambiguous: more than one entry point
                        found = joinPath(childPath, indexName)
                    }
                }
                if (queue.size < MAX_ENTRY_SEARCH_DIRECTORIES * 4) {
                    queue.add(childPath to depth + 1)
                }
            }
        }
        return found
    }

    /**
     * Resolves a manifest [startUrl] against the directory the manifest was
     * found in and verifies the result is an existing document inside the
     * served tree. Returns the tree-relative entry path or null.
     */
    private fun resolveStartUrl(store: DocumentStore, dirRelativePath: String, startUrl: String): String? {
        val trimmed = startUrl.trim().substringBefore('#').substringBefore('?')
        if (trimmed.isEmpty()) return null
        val treeRelative: String? = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                // Absolute URL: keep its path, confined to the served prefix.
                val path = try {
                    URI(trimmed).path
                } catch (e: Exception) {
                    null
                } ?: return null
                HttpPaths.stripPrefix(path, urlPrefixProvider())
            }
            trimmed.startsWith("/") -> HttpPaths.stripPrefix(trimmed, urlPrefixProvider())
            else -> HttpPaths.normalize(
                if (dirRelativePath.isEmpty()) trimmed else "$dirRelativePath/$trimmed"
            )
        }
        if (treeRelative.isNullOrEmpty()) return null
        val doc = store.resolve(treeRelative)
        return if (doc != null && !doc.isDirectory) treeRelative else null
    }

    /** Reads a small text document (used for web app manifests), bounded in size. */
    private fun readDocumentText(store: DocumentStore, entry: DocumentEntry): String? {
        if (entry.size > MAX_MANIFEST_BYTES) return null
        return try {
            store.openInput(entry)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_MANIFEST_BYTES) return null
                }
                if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeEntryRedirect(out: OutputStream, location: String, isHead: Boolean) {
        val body = "302 Found (entry point: $location)\n".toByteArray(StandardCharsets.UTF_8)
        writeResponseHeaders(
            out, 302, "Found",
            listOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Location" to location
            )
        )
        if (!isHead) out.write(body)
    }

    private fun joinPath(dirRelativePath: String, name: String): String =
        if (dirRelativePath.isEmpty()) name else "$dirRelativePath/$name"

    private fun String.isHtmlFileName(): Boolean =
        endsWith(".html", ignoreCase = true) || endsWith(".htm", ignoreCase = true)

    private fun handleFile(
        store: DocumentStore,
        entry: DocumentEntry,
        requestPath: String,
        headers: Map<String, String>,
        isHead: Boolean,
        out: OutputStream,
        record: RequestRecord,
        query: String = ""
    ) {
        val contentType = resolveContentType(entry)
        val size = entry.size

        // Open the document BEFORE writing any status line or headers: a file
        // that cannot be opened must produce a clean error response, never a
        // 200 response whose promised body is not (fully) delivered.
        val input = store.openInput(entry)
        if (input == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (document could not be opened)")
            record.status = 404
            record.detail = "document could not be opened: ${entry.id}"
            logger(LogLevel.WARN, "GET $requestPath -> 404 document could not be opened (${entry.id})", null)
            return
        }

        // Small files are buffered completely, so the Content-Length always
        // matches the bytes actually sent - even when the provider reported a
        // wrong or missing size. Ranges are applied to the buffered bytes.
        if (size in 0..SMALL_FILE_BUFFER_BYTES) {
            val bytes = input.use { it.readBytes() }
            val actual = bytes.size.toLong()
            if (actual != size) {
                // The provider-reported size did not match the stream: the
                // response stays well-formed (Content-Length = actual bytes)
                // but the mismatch is a provider/stream problem worth logging.
                logger(
                    LogLevel.WARN,
                    "GET $requestPath -> stream delivered $actual bytes but size was reported as $size (${entry.id})",
                    null
                )
            }
            val rangeResult = HttpRanges.parse(headers["range"], actual)
            when (rangeResult) {
                is HttpRanges.Result.NotSatisfiable -> {
                    out.writeSimpleError(
                        416, "Range Not Satisfiable", "416 Range Not Satisfiable",
                        extraHeaders = listOf("Content-Range" to "bytes */$actual")
                    )
                    record.status = 416
                    record.detail = "range not satisfiable (size $actual)"
                    logger(LogLevel.INFO, "GET $requestPath -> 416 range not satisfiable", null)
                    return
                }
                is HttpRanges.Result.Single -> {
                    val slice = bytes.copyOfRange(rangeResult.start.toInt(), (rangeResult.end + 1).toInt())
                    writeResponseHeaders(
                        out, 206, "Partial Content",
                        listOf(
                            "Content-Type" to contentType,
                            "Content-Length" to slice.size.toString(),
                            "Content-Range" to "bytes ${rangeResult.start}-${rangeResult.end}/$actual",
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    if (!isHead) out.write(slice)
                    record.status = 206
                    record.mimeType = contentType
                    record.bytesSent = slice.size.toLong()
                    record.detail = "range ${rangeResult.start}-${rangeResult.end} of $actual bytes"
                    logger(LogLevel.INFO, "GET $requestPath -> 206 $contentType ${slice.size} of $actual bytes (${entry.id})", null)
                    return
                }
                is HttpRanges.Result.Ignore -> {
                    var outBytes = bytes
                    val injected = mutableListOf<String>()
                    val isHtml = contentType.startsWith("text/html")

                    // The diagnostics and graphics experiment are independent
                    // opt-ins. Their shared bootstrap only observes the game
                    // launch message and, for the experiment, sets the
                    // existing synchronous-pipeline option. It never touches
                    // the touch-controls code or input events.
                    if (isHtml && (hasQueryFlag(query, GAME_DIAGNOSTICS_QUERY) ||
                            hasQueryFlag(query, ADRENO_FIX_QUERY))
                    ) {
                        if (gameDiagnosticsScriptProvider?.invoke() != null) {
                            outBytes = injectGameDiagnosticsScript(outBytes)
                            injected += "opt-in game diagnostics bootstrap"
                        }
                    }

                    // Full diagnostics mark the loader and its GPU/I/O
                    // workers; fix-only mode marks just loader.js so a
                    // minimal hook can confirm the existing pipeline URL
                    // without enabling the diagnostics collection.
                    val workerName = entry.name.lowercase()
                    val fullWorkerDiagnostics = hasQueryFlag(query, WORKER_DIAGNOSTICS_QUERY) &&
                        workerName in DIAGNOSTIC_WORKER_SCRIPT_NAMES
                    val pipelineSelectionProbe = hasQueryFlag(query, WORKER_PIPELINE_QUERY) && workerName == "loader.js"
                    if (fullWorkerDiagnostics || pipelineSelectionProbe) {
                        val workerScript = workerDiagnosticsBootstrapProvider?.invoke()
                        if (workerScript != null) {
                            outBytes = appendWorkerDiagnosticsBootstrap(outBytes, workerScript)
                            injected += "worker diagnostics bootstrap"
                        }
                    }

                    // Keep the existing touch feature separately gated and
                    // byte-for-byte unchanged when its own parameter is not
                    // requested.
                    if (isHtml && query.split('&').any { it == "newcatroid_touch" || it == "newcatroid_touch=1" }) {
                        outBytes = injectTouchControlsScript(outBytes)
                        injected += "touch controls overlay"
                    }
                    if (injected.isNotEmpty()) {
                        record.detail = injected.joinToString("; ") + " injected"
                        logger(LogLevel.INFO, "GET $requestPath -> injected ${injected.joinToString(" and ")}", null)
                    }

                    val sent = outBytes.size.toLong()
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOfNotNull(
                            "Content-Type" to contentType,
                            "Content-Length" to sent.toString(),
                            "Accept-Ranges" to "bytes",
                            if (injected.any { it == "worker diagnostics bootstrap" }) "Cache-Control" to "no-store" else null
                        )
                    )
                    if (!isHead) out.write(outBytes)
                    record.status = 200
                    record.mimeType = contentType
                    record.bytesSent = if (isHead) 0 else sent
                    if (sent == bytes.size.toLong() && bytes.size.toLong() != size) {
                        record.detail = "size mismatch: provider reported $size, sent ${bytes.size} bytes"
                    }
                    logger(LogLevel.INFO, "GET $requestPath -> 200 $contentType $sent bytes (${entry.id})", null)
                    return
                }
            }
        }

        // Large or unknown-size files are streamed. The stream is already open;
        // if reading fails midway the connection is aborted by the caller
        // (handle) without appending anything to the started response.
        val rangeResult = if (size >= 0) HttpRanges.parse(headers["range"], size) else HttpRanges.Result.Ignore
        when (rangeResult) {
            is HttpRanges.Result.NotSatisfiable -> {
                out.writeSimpleError(
                    416, "Range Not Satisfiable", "416 Range Not Satisfiable",
                    extraHeaders = listOf("Content-Range" to "bytes */$size")
                )
                record.status = 416
                record.detail = "range not satisfiable (size $size)"
                logger(LogLevel.INFO, "GET $requestPath -> 416 range not satisfiable", null)
                input.close()
                return
            }
            is HttpRanges.Result.Single -> {
                writeResponseHeaders(
                    out, 206, "Partial Content",
                    listOf(
                        "Content-Type" to contentType,
                        "Content-Length" to rangeResult.length.toString(),
                        "Content-Range" to "bytes ${rangeResult.start}-${rangeResult.end}/$size",
                        "Accept-Ranges" to "bytes"
                    )
                )
                val sent = if (isHead) 0L else input.use { writeFileRange(it, entry, rangeResult.start, rangeResult.length, out) }
                if (isHead) input.close()
                record.status = 206
                record.mimeType = contentType
                record.bytesSent = sent
                record.detail = "range ${rangeResult.start}-${rangeResult.end} of $size bytes"
                logger(LogLevel.INFO, "GET $requestPath -> 206 $contentType $sent of $size bytes (${entry.id})", null)
                return
            }
            is HttpRanges.Result.Ignore -> {
                if (size >= 0) {
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOf(
                            "Content-Type" to contentType,
                            "Content-Length" to size.toString(),
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    val sent = if (isHead) 0L else input.use { writeFileRange(it, entry, 0, size, out) }
                    if (isHead) input.close()
                    record.status = 200
                    record.mimeType = contentType
                    record.bytesSent = sent
                    logger(LogLevel.INFO, "GET $requestPath -> 200 $contentType $sent of $size bytes (${entry.id})", null)
                } else {
                    // Unknown length: chunked transfer encoding.
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOf(
                            "Content-Type" to contentType,
                            "Transfer-Encoding" to "chunked",
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    val sent = if (isHead) 0L else input.use { writeFileChunked(it, entry, out) }
                    if (isHead) input.close()
                    record.status = 200
                    record.mimeType = contentType
                    record.bytesSent = sent
                    record.detail = "chunked (unknown size)"
                    logger(LogLevel.INFO, "GET $requestPath -> 200 $contentType $sent bytes (chunked, ${entry.id})", null)
                }
            }
        }
    }

    /**
     * Picks the response Content-Type. The extension based table wins when it
     * knows the type (it is tuned for web content, e.g. `text/javascript`
     * instead of legacy `application/javascript`); otherwise the provider
     * reported MIME type is used, falling back to `application/octet-stream`.
     */
    private fun resolveContentType(entry: DocumentEntry): String {
        val byExtension = MimeTypes.forPath(entry.name)
        if (byExtension != MimeTypes.DEFAULT) return byExtension
        val reported = entry.mimeType?.takeIf { it.isNotBlank() && it != DIRECTORY_MIME && it != MimeTypes.DEFAULT }
        return reported ?: MimeTypes.DEFAULT
    }

    /**
     * Handles `POST /data/batch` - the batch byte-range endpoint used by
     * hosted games whose engine streams its data through an io worker (the
     * playgta5 build's io worker, for example, fetches its whole boot read-set
     * this way: "thousands of small archive pieces cost one round trip per
     * batch instead of one each"). The contract mirrors the reference server:
     *
     * - request body: a JSON array of `[name, start, end]` runs (name relative
     *   to the hosted `data/` directory, byte offsets inclusive);
     * - response: one `application/octet-stream` body containing the runs
     *   concatenated in order, an `X-Run-Lengths` header with the length of
     *   each run AS SENT (each range is clamped at the end of the file), and
     *   with `?gz=1` a gzip-compressed body plus `Content-Encoding: gzip`
     *   (the browser decodes it transparently);
     * - limits: at most 1000 runs, request body at most 1 MiB, total at most
     *   64 MiB; invalid bodies/ranges answer 400, unknown files 404, other
     *   methods 405.
     *
     * Files are resolved through the served store (with the nested-site-root
     * fallback), so the batch reads stay confined to the selected tree.
     */
    private fun handleDataBatch(
        method: String,
        rawPath: String,
        query: String,
        body: ByteArray?,
        out: OutputStream,
        record: RequestRecord
    ) {
        val store = storeProvider()
        if (store == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (no directory is being hosted)")
            record.status = 404
            record.detail = "no directory is being hosted"
            return
        }
        if (!method.equals("POST", ignoreCase = true)) {
            out.writeSimpleError(405, "Method Not Allowed", "405 Method Not Allowed", extraHeaders = listOf("Allow" to "POST"))
            record.status = 405
            record.detail = "batch endpoint expects POST"
            logger(LogLevel.INFO, "$method $rawPath -> 405 batch endpoint expects POST", null)
            return
        }
        if (body != null && body.size > MAX_BATCH_BODY_BYTES) {
            out.writeSimpleError(413, "Payload Too Large", "413 Payload Too Large (batch body exceeds 1 MiB)")
            record.status = 413
            record.detail = "batch body exceeds 1 MiB"
            return
        }
        val runs = try {
            parseBatchRuns(body?.toString(StandardCharsets.UTF_8) ?: "")
        } catch (e: Exception) {
            null
        }
        if (runs == null) {
            out.writeSimpleError(400, "Bad Request", "400 Bad Request (invalid batch body)")
            record.status = 400
            record.detail = "invalid batch body"
            return
        }
        if (runs.size > MAX_BATCH_RUNS) {
            out.writeSimpleError(400, "Bad Request", "400 Bad Request (too many runs)")
            record.status = 400
            record.detail = "too many runs: ${runs.size}"
            return
        }

        data class Selected(val entry: DocumentEntry, val start: Long, val length: Long)
        val selected = mutableListOf<Selected>()
        var total = 0L
        for ((name, start, end) in runs) {
            // Confine every run name to a safe relative path inside data/.
            val normalizedName = HttpPaths.normalize(name)
            if (normalizedName.isNullOrEmpty()) {
                out.writeSimpleError(400, "Bad Request", "400 Bad Request (invalid file name)")
                record.status = 400
                record.detail = "invalid file name: $name"
                return
            }
            if (start < 0 || end < start) {
                out.writeSimpleError(400, "Bad Request", "400 Bad Request (invalid range)")
                record.status = 400
                record.detail = "invalid range for $name"
                return
            }
            val storePath = "data/$normalizedName"
            val entry = store.resolve(storePath) ?: suffixSearcherFor(store).find(storePath)
            if (entry == null || entry.isDirectory) {
                out.writeSimpleError(404, "Not Found", "404 Not Found (data file not found: $normalizedName)")
                record.status = 404
                record.detail = "data file not found: $normalizedName"
                return
            }
            // Clamp the range at the end of the server's copy of the file.
            val length = if (entry.size >= 0) {
                maxOf(0L, minOf(end + 1, entry.size) - start)
            } else {
                end + 1 - start
            }
            total += length
            if (total > MAX_BATCH_TOTAL_BYTES) {
                out.writeSimpleError(400, "Bad Request", "400 Bad Request (batch exceeds 64 MiB)")
                record.status = 400
                record.detail = "batch exceeds 64 MiB"
                return
            }
            selected.add(Selected(entry, start, length))
        }

        // Read the runs and concatenate them in order.
        val output = ByteArrayOutputStream()
        val lengths = ArrayList<Long>(selected.size)
        var failure: String? = null
        for (item in selected) {
            val input = store.openInput(item.entry)
            if (input == null) {
                failure = "cannot open ${item.entry.name}"
                break
            }
            var sent = 0L
            try {
                input.use { inp ->
                    skipFully(inp, item.start)
                    val buffer = ByteArray(
                        minOf(STREAM_BUFFER_SIZE.toLong(), maxOf(1L, item.length)).toInt()
                    )
                    var remaining = item.length
                    while (remaining > 0) {
                        val read = inp.read(
                            buffer, 0,
                            minOf(buffer.size.toLong(), remaining).toInt()
                        )
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        remaining -= read
                    }
                }
            } catch (e: Exception) {
                failure = "read failed for ${item.entry.name}: ${e.message}"
                break
            }
            lengths.add(sent)
        }
        if (failure != null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found ($failure)")
            record.status = 404
            record.detail = failure
            return
        }

        var responseBody = output.toByteArray()
        val compressed = query.split('&').any { it == "gz=1" }
        if (compressed) {
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(responseBody) }
            responseBody = gz.toByteArray()
        }
        writeResponseHeaders(
            out, 200, "OK",
            listOfNotNull(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to responseBody.size.toString(),
                "X-Run-Lengths" to lengths.joinToString(","),
                if (compressed) "Content-Encoding" to "gzip" else null
            )
        )
        out.write(responseBody)
        record.status = 200
        record.mimeType = "application/octet-stream"
        record.bytesSent = responseBody.size.toLong()
        record.detail = "data batch: ${selected.size} runs, ${lengths.sum()} bytes" +
            if (compressed) " (gzipped)" else ""
        logger(
            LogLevel.INFO,
            "POST $rawPath -> 200 data batch (${selected.size} runs, ${lengths.sum()} bytes)",
            null
        )
    }

    /** Lazily created (and store-change aware) nested-site-root searcher. */
    private fun suffixSearcherFor(store: DocumentStore): PathSuffixSearcher {
        if (suffixSearcherStore !== store) {
            suffixSearcher = PathSuffixSearcher(store)
            suffixSearcherStore = store
        }
        return suffixSearcher!!
    }

    private fun writeBuiltInScriptResponse(
        method: String,
        rawPath: String,
        script: String,
        description: String,
        isHead: Boolean,
        out: OutputStream,
        record: RequestRecord
    ) {
        val body = script.toByteArray(StandardCharsets.UTF_8)
        writeResponseHeaders(
            out, 200, "OK",
            listOf(
                "Content-Type" to "text/javascript; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-store"
            )
        )
        if (!isHead) out.write(body)
        record.status = 200
        record.mimeType = "text/javascript; charset=utf-8"
        record.bytesSent = if (isHead) 0 else body.size.toLong()
        record.detail = description
        logger(LogLevel.INFO, "$method $rawPath -> 200 $description", null)
    }

    /** Injects the early page diagnostics bootstrap into an HTML document. */
    private fun injectGameDiagnosticsScript(html: ByteArray): ByteArray {
        val prefix = HttpPaths.normalizePrefix(urlPrefixProvider())
        val src = if (prefix == "/") "/$GAME_DIAGNOSTICS_PATH" else "$prefix/$GAME_DIAGNOSTICS_PATH"
        val tag = "<script src=\"$src\"></script>"
        val text = String(html, StandardCharsets.UTF_8)
        val headStart = text.indexOf("<head", ignoreCase = true)
        val tagBoundary = headStart + "<head".length
        val isHeadTag = headStart >= 0 &&
            (tagBoundary >= text.length || text[tagBoundary].isWhitespace() || text[tagBoundary] == '>')
        val headEnd = if (isHeadTag) text.indexOf('>', headStart) else -1
        val injected = if (headEnd >= 0) {
            text.substring(0, headEnd + 1) + tag + text.substring(headEnd + 1)
        } else {
            tag + text
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
    }

    /** Appends observational hooks so an original worker's strict directive remains effective. */
    private fun appendWorkerDiagnosticsBootstrap(script: ByteArray, bootstrap: String): ByteArray {
        val suffix = ("\n" + bootstrap + "\n").toByteArray(StandardCharsets.UTF_8)
        return ByteArray(script.size + suffix.size).also {
            System.arraycopy(script, 0, it, 0, script.size)
            System.arraycopy(suffix, 0, it, script.size, suffix.size)
        }
    }

    private fun hasQueryFlag(query: String, name: String): Boolean = query.split('&').any { part ->
        part.substringBefore('=') == name && part.substringAfter('=', "") == "1"
    }

    /** Injects the touch controls overlay script tag into an HTML document. */
    private fun injectTouchControlsScript(html: ByteArray): ByteArray {
        val prefix = HttpPaths.normalizePrefix(urlPrefixProvider())
        val src = if (prefix == "/") "/$TOUCH_CONTROLS_PATH" else "$prefix/$TOUCH_CONTROLS_PATH"
        val tag = "<script src=\"$src\" defer></script>"
        val text = String(html, StandardCharsets.UTF_8)
        val bodyEnd = text.lastIndexOf("</body>", ignoreCase = true)
        val injected = if (bodyEnd >= 0) {
            text.substring(0, bodyEnd) + tag + text.substring(bodyEnd)
        } else {
            text + tag
        }
        return injected.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Minimal parser for the batch request body: a JSON array of
     * `[name, start, end]` runs. Hand-rolled (the engine stays pure JVM and
     * dependency-free); returns null when the body is not exactly that shape.
     */
    private fun parseBatchRuns(body: String): List<Triple<String, Long, Long>>? {
        var i = 0
        fun skipWs() {
            while (i < body.length && body[i].isWhitespace()) i++
        }
        fun parseString(): String? {
            if (i >= body.length || body[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    sb.append(body[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                sb.append(c)
                i++
            }
            return null
        }
        fun parseNumber(): Long? {
            val start = i
            if (i < body.length && (body[i] == '-' || body[i] == '+')) i++
            while (i < body.length && body[i].isDigit()) i++
            if (i < body.length && body[i] == '.') {
                i++
                while (i < body.length && body[i].isDigit()) i++
            }
            if (i < body.length && (body[i] == 'e' || body[i] == 'E')) {
                i++
                if (i < body.length && (body[i] == '+' || body[i] == '-')) i++
                while (i < body.length && body[i].isDigit()) i++
            }
            if (start == i) return null
            return body.substring(start, i).toDoubleOrNull()?.toLong()
        }
        fun parseArray(): List<Any>? {
            if (i >= body.length || body[i] != '[') return null
            i++
            val out = mutableListOf<Any>()
            skipWs()
            if (i < body.length && body[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val value: Any = when {
                    i < body.length && body[i] == '"' -> parseString() ?: return null
                    i < body.length && body[i] == '[' -> parseArray() ?: return null
                    else -> parseNumber() ?: return null
                }
                out.add(value)
                skipWs()
                if (i >= body.length) return null
                if (body[i] == ',') {
                    i++
                    continue
                }
                if (body[i] == ']') {
                    i++
                    return out
                }
                return null
            }
        }
        skipWs()
        val parsed = parseArray() ?: return null
        skipWs()
        if (i != body.length) return null
        return parsed.map { run ->
            if (run !is List<*> || run.size != 3) return null
            val name = run[0] as? String ?: return null
            val start = (run[1] as? Long) ?: return null
            val end = (run[2] as? Long) ?: return null
            Triple(name, start, end)
        }
    }

    private fun writeFileRange(
        input: InputStream,
        entry: DocumentEntry,
        start: Long,
        length: Long,
        out: OutputStream
    ): Long {
        skipFully(input, start)
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var remaining = length
        var written = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected end of file while streaming ${entry.name}")
            out.write(buffer, 0, read)
            written += read
            remaining -= read
        }
        return written
    }

    private fun writeFileChunked(input: InputStream, entry: DocumentEntry, out: OutputStream): Long {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write((Integer.toHexString(read) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
            out.write(buffer, 0, read)
            out.write(CRLF)
            written += read
        }
        out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        return written
    }

    private fun writeResponseHeaders(out: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        val providedNames = headers.mapTo(HashSet()) { it.first.lowercase() }
        for ((name, value) in headers) {
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        // Cross-origin isolation (COOP/COEP): makes the served page a secure
        // context with crossOriginIsolated=true, which games using
        // SharedArrayBuffer, WASM threads or WebGPU require. Added to every
        // response (files, listings, redirects, errors) unless the caller
        // already provided the header - never duplicated.
        if (addCrossOriginIsolationHeaders) {
            if (HEADER_CROSS_ORIGIN_OPENER_POLICY !in providedNames) {
                builder.append("Cross-Origin-Opener-Policy: same-origin").append("\r\n")
            }
            if (HEADER_CROSS_ORIGIN_EMBEDDER_POLICY !in providedNames) {
                builder.append("Cross-Origin-Embedder-Policy: require-corp").append("\r\n")
            }
        }
        builder.append("Connection: close").append("\r\n")
        builder.append("Server: NewCatroidLocalServer").append("\r\n")
        builder.append("\r\n")
        (out as? ResponseStream)?.status = status
        out.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
        (out as? ResponseStream)?.beginBody()
    }

    private fun OutputStream.writeSimpleError(
        status: Int,
        reason: String,
        message: String,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        val body = "$message\n".toByteArray(StandardCharsets.UTF_8)
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Content-Type" to "text/plain; charset=utf-8")
        headers.add("Content-Length" to body.size.toString())
        headers.addAll(extraHeaders)
        writeResponseHeaders(this, status, reason, headers)
        write(body)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) throw EOFException("Unexpected end of file while seeking")
            remaining -= 1
        }
    }

    companion object {
        /**
         * Path of the built-in cross-origin isolation check page
         * (`/__newcatroid_isolation_check`).
         */
        const val ISOLATION_CHECK_PATH = "__newcatroid_isolation_check"
        const val TOUCH_CONTROLS_PATH = "__newcatroid_touch_controls.js"
        const val GAME_DIAGNOSTICS_PATH = "__newcatroid_game_diagnostics.js"
        const val GAME_DIAGNOSTICS_QUERY = "newcatroid_debug"
        const val ADRENO_FIX_QUERY = "newcatroid_adreno_fix"
        const val WORKER_DIAGNOSTICS_QUERY = "newcatroid_worker_debug"
        const val WORKER_PIPELINE_QUERY = "newcatroid_pipeline_probe"
        val DIAGNOSTIC_WORKER_SCRIPT_NAMES = setOf("loader.js", "wgpu_worker.js", "io_worker.js")
        const val DATA_BATCH_PATH = "data/batch"
        const val MAX_BATCH_RUNS = 1000
        const val MAX_BATCH_BODY_BYTES = 1024 * 1024
        const val MAX_BATCH_TOTAL_BYTES = 64L * 1024 * 1024

        /**
         * Minimal diagnostic page served by the server itself. Reports the
         * WebView's cross-origin isolation state, secure-context state,
         * SharedArrayBuffer/WebAssembly availability, the user agent (WebView
         * and Chromium versions), and the response headers the browser
         * actually received for this document (via a HEAD fetch of itself).
         * Everything is also console.logged so the WebView event capture
         * records it. Used to distinguish a WebView limitation from a hosted
         * game's own JavaScript failure.
         */
        const val ISOLATION_CHECK_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>NewCatroid isolation check</title></head>
<body>
<h1>NewCatroid cross-origin isolation check</h1>
<p>Served by the local server with the standard COOP/COEP headers. Open this page
directly (no redirect) and compare with the hosted game's page.</p>
<pre id="out">running...</pre>
<script>
(function () {
    var lines = [];
    function log(s) {
        lines.push(s);
        var el = document.getElementById('out');
        if (el) el.textContent = lines.join('
');
        console.log(s);
    }
    log('url: ' + location.href);
    log('isSecureContext: ' + window.isSecureContext);
    log('crossOriginIsolated: ' + window.crossOriginIsolated);
    log('SharedArrayBuffer: ' + typeof SharedArrayBuffer);
    log('WebAssembly: ' + typeof WebAssembly);
    log('Worker: ' + typeof Worker);
    log('OffscreenCanvas: ' + typeof OffscreenCanvas);
    log('AudioWorkletNode: ' + typeof AudioWorkletNode);
    log('navigator.storage.getDirectory (OPFS): ' + (navigator.storage && typeof navigator.storage.getDirectory === 'function'));
    log('FileSystemSyncAccessHandle: ' + typeof FileSystemSyncAccessHandle);
    log('navigator.gpu: ' + (navigator.gpu ? 'present' : 'absent'));
    log('userAgent: ' + navigator.userAgent);
    try {
        var sab = new SharedArrayBuffer(8);
        log('SharedArrayBuffer(8): OK (' + sab.byteLength + ' bytes)');
    } catch (e) {
        log('SharedArrayBuffer(8): FAILED: ' + e);
    }
    try {
        if (typeof BigInt !== 'function') throw new Error('BigInt is unavailable');
        var memory64 = new WebAssembly.Memory({ initial: BigInt(1), maximum: BigInt(2), shared: true, address: 'i64' });
        log('shared WebAssembly Memory64: OK (' + memory64.buffer.byteLength + ' bytes)');
    } catch (e) {
        log('shared WebAssembly Memory64: FAILED: ' + e);
    }
    try {
        WebAssembly.compile(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])).then(
            function () { log('WebAssembly.compile: OK'); },
            function (e) { log('WebAssembly.compile: FAILED: ' + e); }
        );
    } catch (e) {
        log('WebAssembly.compile: FAILED: ' + e);
    }
    if (navigator.gpu && typeof navigator.gpu.requestAdapter === 'function') {
        navigator.gpu.requestAdapter().then(function (adapter) {
            log('WebGPU adapter: ' + (adapter ? 'available' : 'unavailable'));
        }).catch(function (e) { log('WebGPU adapter: FAILED: ' + e); });
    }
    fetch(location.href, { method: 'HEAD' }).then(function (r) {
        log('own HEAD: status=' + r.status +
            ' COOP=' + r.headers.get('cross-origin-opener-policy') +
            ' COEP=' + r.headers.get('cross-origin-embedder-policy') +
            ' CORP=' + r.headers.get('cross-origin-resource-policy') +
            ' type=' + r.headers.get('content-type'));
    }).catch(function (e) { log('own HEAD: FAILED: ' + e); });
    fetch(location.href).then(function (r) {
        log('own GET: status=' + r.status);
    }).catch(function (e) { log('own GET: FAILED: ' + e); });
})();
</script>
</body></html>"""

        const val STREAM_BUFFER_SIZE = 64 * 1024
        const val SMALL_FILE_BUFFER_BYTES = 4 * 1024 * 1024
        const val HEADER_CROSS_ORIGIN_OPENER_POLICY = "cross-origin-opener-policy"
        const val HEADER_CROSS_ORIGIN_EMBEDDER_POLICY = "cross-origin-embedder-policy"
        const val COOP_SAME_ORIGIN = "same-origin"
        const val COEP_REQUIRE_CORP = "require-corp"
        const val MAX_LISTING_ENTRIES = 1000
        val INDEX_FILE_NAMES = listOf("index.html", "index.htm")
        val MANIFEST_FILE_NAMES = listOf("manifest.webmanifest", "manifest.json")
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_ENTRY_SEARCH_DEPTH = 3
        const val MAX_ENTRY_SEARCH_DIRECTORIES = 32
        const val MAX_ENTRY_POINT_CACHE_ENTRIES = 64
        const val DIRECTORY_MIME = "vnd.android.document/directory"
        val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)

        fun escapeHtml(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /**
         * Extracts a top-level string field from a JSON document without a
         * JSON library (the engine stays pure JVM). Handles whitespace and
         * escaped characters inside the string value. Returns null when the
         * field is missing or not a string.
         */
        fun extractJsonStringField(json: String, field: String): String? {
            var searchFrom = 0
            while (true) {
                val keyIndex = json.indexOf("\"$field\"", searchFrom)
                if (keyIndex < 0) return null
                var i = keyIndex + field.length + 2
                while (i < json.length && json[i].isWhitespace()) i++
                if (i >= json.length || json[i] != ':') {
                    searchFrom = keyIndex + 1
                    continue
                }
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (i >= json.length || json[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < json.length) {
                    val c = json[i]
                    if (c == '\\' && i + 1 < json.length) {
                        when (val escaped = json[i + 1]) {
                            'u' -> {
                                // JSON unicode escape: \uXXXX
                                if (i + 5 >= json.length) return null
                                val hex = json.substring(i + 2, i + 6)
                                val code = try {
                                    Integer.parseInt(hex, 16)
                                } catch (e: NumberFormatException) {
                                    return null
                                }
                                sb.append(code.toChar())
                                i += 6
                            }
                            else -> {
                                sb.append(escaped)
                                i += 2
                            }
                        }
                        continue
                    }
                    if (c == '"') return sb.toString()
                    sb.append(c)
                    i++
                }
                return null
            }
        }

        /** Percent-encodes a single path segment for use inside a listing link. */
        fun urlEncodePathSegment(segment: String): String {
            val sb = StringBuilder(segment.length)
            for (byte in segment.toByteArray(StandardCharsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val safe = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                    c == '.'.code || c == '~'.code
                if (safe) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    sb.append(HEX[c ushr 4])
                    sb.append(HEX[c and 0xF])
                }
            }
            return sb.toString()
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
