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
    private val requestLog: RequestLog? = null
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
    fun handle(method: String, rawTarget: String, headers: Map<String, String>, out: OutputStream) {
        val response = ResponseStream(out)
        val record = RequestRecord(method, rawTarget)
        val startedAt = System.nanoTime()
        try {
            handleInternal(method, rawTarget, headers, response, record)
        } catch (e: Exception) {
            if (!response.started) {
                record.status = 500
                record.detail = "internal error: ${e.message}"
                logger(LogLevel.ERROR, "$method $rawTarget -> request failed before any response was sent", e)
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
                logger(LogLevel.ERROR, "$method $rawTarget -> response aborted mid-stream (${record.detail}, ${response.bytesWritten} body bytes sent)", e)
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
        out: OutputStream,
        record: RequestRecord
    ) {
        val isHead = method.equals("HEAD", ignoreCase = true)
        if (!method.equals("GET", ignoreCase = true) && !isHead) {
            out.writeSimpleError(405, "Method Not Allowed", "405 Method Not Allowed", extraHeaders = listOf("Allow" to "GET, HEAD"))
            record.status = 405
            record.detail = "method not allowed"
            logger(LogLevel.INFO, "$method $rawTarget -> 405 method not allowed", null)
            return
        }

        val rawPath = rawTarget.substringBefore('?').ifEmpty { "/" }
        val query = if ('?' in rawTarget) rawTarget.substringAfter('?') else ""

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
            val searcher = if (suffixSearcherStore !== store) {
                PathSuffixSearcher(store).also {
                    suffixSearcher = it
                    suffixSearcherStore = store
                }
            } else {
                suffixSearcher!!
            }
            entry = searcher.find(relativeToPrefix)
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
            handleFile(store, entry, rawPath, headers, isHead, out, record)
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
            record.detail = "slash redirect to $location"
            logger(LogLevel.INFO, "GET $rawPath -> 301 directory slash redirect to $location", null)
            return
        }

        for (indexName in INDEX_FILE_NAMES) {
            val index = store.resolve(if (normalized.isEmpty()) indexName else "$normalized/$indexName")
            if (index != null && !index.isDirectory) {
                handleFile(store, index, rawPath, emptyMap(), isHead, out, record)
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
            record.detail = "entry point redirect to $location"
            logger(LogLevel.INFO, "GET $rawPath -> 302 entry point redirect to $location", null)
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
        record: RequestRecord
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
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOf(
                            "Content-Type" to contentType,
                            "Content-Length" to actual.toString(),
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    if (!isHead) out.write(bytes)
                    record.status = 200
                    record.mimeType = contentType
                    record.bytesSent = if (isHead) 0 else actual
                    if (actual != size) {
                        record.detail = "size mismatch: provider reported $size, sent $actual bytes"
                    }
                    logger(LogLevel.INFO, "GET $requestPath -> 200 $contentType $actual bytes (${entry.id})", null)
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
