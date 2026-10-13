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
 */
class HttpEngine(
    private val storeProvider: () -> DocumentStore?,
    private val urlPrefixProvider: () -> String = { "/" }
) {

    /** Cache of discovered entry points per directory (tree-relative path). */
    private val entryPointCache = HashMap<String, String?>()
    private val entryPointCacheLock = Any()

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
        try {
            handleInternal(method, rawTarget, headers, out)
        } catch (e: Exception) {
            try {
                out.writeSimpleError(500, "Internal Server Error", "500 Internal Server Error")
            } catch (ignored: Exception) {
            }
        } finally {
            try {
                out.flush()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handleInternal(
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        out: OutputStream
    ) {
        val isHead = method.equals("HEAD", ignoreCase = true)
        if (!method.equals("GET", ignoreCase = true) && !isHead) {
            out.writeSimpleError(405, "Method Not Allowed", "405 Method Not Allowed", extraHeaders = listOf("Allow" to "GET, HEAD"))
            return
        }

        val rawPath = rawTarget.substringBefore('?').ifEmpty { "/" }
        val query = if ('?' in rawTarget) rawTarget.substringAfter('?') else ""

        // Normalize the full request path first: invalid paths and traversal
        // attempts escaping the served root are rejected before anything else.
        val normalized = HttpPaths.normalize(rawPath)
        if (normalized == null) {
            out.writeSimpleError(403, "Forbidden", "403 Forbidden (invalid path)")
            return
        }

        // Then confine the normalized path to the configured URL prefix.
        val relativeToPrefix = HttpPaths.stripPrefix(normalized, urlPrefixProvider())
        if (relativeToPrefix == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (path is outside the served prefix)")
            return
        }

        val store = storeProvider()
        if (store == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found (no directory is being hosted)")
            return
        }

        val entry = store.resolve(relativeToPrefix)
        if (entry == null) {
            out.writeSimpleError(404, "Not Found", "404 Not Found")
            return
        }

        if (entry.isDirectory) {
            handleDirectory(store, entry, rawPath, relativeToPrefix, query, isHead, out)
        } else {
            handleFile(store, entry, headers, isHead, out)
        }
    }

    private fun handleDirectory(
        store: DocumentStore,
        entry: DocumentEntry,
        rawPath: String,
        normalized: String,
        query: String,
        isHead: Boolean,
        out: OutputStream
    ) {
        // Redirect to a trailing slash so relative resource URLs resolve correctly.
        if (!rawPath.endsWith("/")) {
            val location = rawPath + "/" + if (query.isEmpty()) "" else "?$query"
            out.writeSimpleError(
                301, "Moved Permanently", "301 Moved Permanently",
                extraHeaders = listOf("Location" to location)
            )
            return
        }

        for (indexName in INDEX_FILE_NAMES) {
            val index = store.resolve(if (normalized.isEmpty()) indexName else "$normalized/$indexName")
            if (index != null && !index.isDirectory) {
                handleFile(store, index, emptyMap(), isHead, out)
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
            return
        }

        // No entry point found: render a minimal directory listing.
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
        headers: Map<String, String>,
        isHead: Boolean,
        out: OutputStream
    ) {
        val contentType = resolveContentType(entry)
        val size = entry.size

        val rangeResult = if (size >= 0) HttpRanges.parse(headers["range"], size) else HttpRanges.Result.Ignore

        when (rangeResult) {
            is HttpRanges.Result.NotSatisfiable -> {
                out.writeSimpleError(
                    416, "Range Not Satisfiable", "416 Range Not Satisfiable",
                    extraHeaders = listOf("Content-Range" to "bytes */$size")
                )
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
                if (!isHead) writeFileRange(store, entry, rangeResult.start, rangeResult.length, out)
                return
            }
            is HttpRanges.Result.Ignore -> {
                // Full response. When the length is unknown the body is sent with
                // chunked transfer encoding so large files are still streamed.
                if (size >= 0) {
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOf(
                            "Content-Type" to contentType,
                            "Content-Length" to size.toString(),
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    if (!isHead) writeFileRange(store, entry, 0, size, out)
                } else {
                    writeResponseHeaders(
                        out, 200, "OK",
                        listOf(
                            "Content-Type" to contentType,
                            "Transfer-Encoding" to "chunked",
                            "Accept-Ranges" to "bytes"
                        )
                    )
                    if (!isHead) writeFileChunked(store, entry, out)
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
        store: DocumentStore,
        entry: DocumentEntry,
        start: Long,
        length: Long,
        out: OutputStream
    ) {
        store.openInput(entry)?.use { input ->
            skipFully(input, start)
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Unexpected end of file while streaming ${entry.name}")
                out.write(buffer, 0, read)
                remaining -= read
            }
        } ?: throw IllegalStateException("Unable to open ${entry.name}")
    }

    private fun writeFileChunked(store: DocumentStore, entry: DocumentEntry, out: OutputStream) {
        store.openInput(entry)?.use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write((Integer.toHexString(read) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
                out.write(buffer, 0, read)
                out.write(CRLF)
            }
            out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        } ?: throw IllegalStateException("Unable to open ${entry.name}")
    }

    private fun writeResponseHeaders(out: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        for ((name, value) in headers) {
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        builder.append("Connection: close").append("\r\n")
        builder.append("Server: NewCatroidLocalServer").append("\r\n")
        builder.append("\r\n")
        out.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
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
                        sb.append(json[i + 1])
                        i += 2
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
