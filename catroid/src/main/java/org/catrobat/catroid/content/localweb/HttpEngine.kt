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
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
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
            handleDirectory(store, entry, rawPath, relativeToPrefix, isHead, out)
        } else {
            handleFile(store, entry, headers, isHead, out)
        }
    }

    private fun handleDirectory(
        store: DocumentStore,
        entry: DocumentEntry,
        rawPath: String,
        normalized: String,
        isHead: Boolean,
        out: OutputStream
    ) {
        // Redirect to a trailing slash so relative resource URLs resolve correctly.
        if (!rawPath.endsWith("/")) {
            val location = rawPath + "/"
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

        // No index file: render a minimal directory listing.
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
