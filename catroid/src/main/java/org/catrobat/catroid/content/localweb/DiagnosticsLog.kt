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

package org.catrobat.catroid.content.localweb

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * One entry of the HTTP request log: what was requested, what the server
 * answered, how many body bytes were sent, how long it took, and a detail
 * string for redirects, listings, errors and aborts. Request-target query
 * strings are redacted from log paths/details; the HTTP engine still forwards
 * the original query on actual redirects. Never contains file contents - only
 * paths, statuses, sizes and failure reasons.
 */
data class RequestLogEntry(
    val timestampMs: Long,
    val method: String,
    val path: String,
    val status: Int,
    val mimeType: String?,
    val bytesSent: Long,
    val durationMs: Long,
    val detail: String?
) {
    /** Approximate per-request payload rate as observed by the local server. */
    val mebibytesPerSecond: Double?
        get() = if (bytesSent <= 0L || durationMs <= 0L) null else
            bytesSent.toDouble() * 1000.0 / durationMs.toDouble() / (1024.0 * 1024.0)
}

/**
 * One captured WebView event: a console message (log/warning/error, with
 * source and line when available) or a resource/navigation failure (with the
 * URL and the HTTP status or error code).
 */
data class WebViewEvent(
    val timestampMs: Long,
    val kind: Kind,
    val level: String,
    val message: String,
    val url: String? = null,
    val line: Int = -1
) {
    enum class Kind { CONSOLE, RESOURCE_ERROR, HTTP_ERROR, NAVIGATION_ERROR }
}

/**
 * Bounded ring buffer of [RequestLogEntry] fed by the HTTP engine for every
 * request it handles. Bounded so a long-running server cannot exhaust memory;
 * oldest entries are dropped first.
 */
class RequestLog(private val capacity: Int = 200) {

    private val entries = ArrayDeque<RequestLogEntry>()
    private val lock = Any()

    fun add(entry: RequestLogEntry) {
        synchronized(lock) {
            while (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun snapshot(): List<RequestLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    fun formatForCopy(header: String = ""): String {
        val sb = StringBuilder()
        if (header.isNotEmpty()) sb.append(header).append('\n')
        sb.append("HTTP requests (newest last, max $capacity):\n")
        val entries = snapshot()
        if (entries.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            for (entry in entries) {
                val rate = entry.mebibytesPerSecond?.let {
                    "  rate=${String.format(Locale.US, "%.2f", it)} MiB/s"
                }.orEmpty()
                sb.append("  ").append(entry.method).append(' ').append(entry.path)
                    .append(" -> ").append(entry.status)
                    .append("  mime=").append(entry.mimeType ?: "-")
                    .append("  bytes=").append(entry.bytesSent)
                    .append("  ").append(entry.durationMs).append("ms")
                    .append(rate)
                    .append(if (entry.detail != null) "  [" + entry.detail + "]" else "")
                    .append('\n')
            }
        }
        return sb.toString()
    }
}

/**
 * Bounded ring buffer of [WebViewEvent] captured from the embedded WebView
 * (console messages, resource load failures, HTTP error responses,
 * navigation errors).
 */
class WebViewEventLog(private val capacity: Int = 200) {

    private val events = ArrayDeque<WebViewEvent>()
    private val lock = Any()

    fun add(event: WebViewEvent) {
        synchronized(lock) {
            while (events.size >= capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): List<WebViewEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    fun formatForCopy(header: String = ""): String {
        val sb = StringBuilder()
        if (header.isNotEmpty()) sb.append(header).append('\n')
        sb.append("WebView events (newest last, max $capacity):\n")
        val events = snapshot()
        if (events.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            for (event in events) {
                sb.append("  ").append(timeFormat.format(Date(event.timestampMs)))
                    .append(' ').append(event.kind).append('/').append(event.level)
                    .append(": ").append(event.message)
                    .append(if (event.url != null) "  (" + event.url +
                        (if (event.line >= 0) ":" + event.line else "") + ")" else "")
                    .append('\n')
            }
        }
        return sb.toString()
    }
}
