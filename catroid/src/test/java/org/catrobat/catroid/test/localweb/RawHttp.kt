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
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.localweb

import java.net.Socket
import java.nio.charset.StandardCharsets

/** A parsed raw HTTP response (header names are lower-cased). */
data class RawHttpResponse(
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun bodyText(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * Sends a raw HTTP/1.1 request over a loopback socket and parses the response.
 * All responses of the embedded server carry `Connection: close` and a known
 * Content-Length, so no chunked decoding is needed here.
 */
fun rawHttpRequest(
    port: Int,
    method: String,
    path: String,
    extraHeaders: Map<String, String> = emptyMap()
): RawHttpResponse {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10000
        val request = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
        return parseRawResponse(socket.getInputStream().readBytes())
    }
}

/**
 * Sends a raw HTTP/1.1 POST with a body (Content-Length set automatically) and
 * parses the response. Used to exercise the data batch endpoint of hosted
 * games.
 */
fun rawHttpPost(
    port: Int,
    path: String,
    body: ByteArray,
    extraHeaders: Map<String, String> = emptyMap()
): RawHttpResponse {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10000
        val request = buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII) + body
        socket.getOutputStream().write(request)
        socket.getOutputStream().flush()
        return parseRawResponse(socket.getInputStream().readBytes())
    }
}

/**
 * Like [rawHttpRequest] but returns the complete raw response bytes (status
 * line + headers + body). Used by tests that must assert on the exact bytes
 * on the wire, e.g. that a failed stream never appends a second response to
 * a started one.
 */
fun rawHttpRequestBytes(
    port: Int,
    method: String,
    path: String,
    extraHeaders: Map<String, String> = emptyMap()
): ByteArray {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10000
        val request = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
        return socket.getInputStream().readBytes()
    }
}

private fun parseRawResponse(raw: ByteArray): RawHttpResponse {
    val headerEnd = indexOfHeaderEnd(raw)
    val headerBlock = String(raw, 0, headerEnd, StandardCharsets.UTF_8)
    val body = raw.copyOfRange(headerEnd + 4, raw.size)
    val lines = headerBlock.split("\r\n")
    val statusParts = lines[0].split(' ')
    val statusCode = statusParts[1].toInt()
    val reason = statusParts.drop(2).joinToString(" ")
    val headers = HashMap<String, String>()
    for (i in 1 until lines.size) {
        val colon = lines[i].indexOf(':')
        if (colon > 0) {
            headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
        }
    }
    return RawHttpResponse(statusCode, reason, headers, body)
}

private fun indexOfHeaderEnd(raw: ByteArray): Int {
    for (i in 0 until raw.size - 3) {
        if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
            raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
        ) {
            return i
        }
    }
    throw IllegalStateException("response has no header terminator")
}
