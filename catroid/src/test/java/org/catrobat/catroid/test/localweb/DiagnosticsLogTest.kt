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

import org.catrobat.catroid.content.localweb.RequestLog
import org.catrobat.catroid.content.localweb.RequestLogEntry
import org.catrobat.catroid.content.localweb.WebViewEvent
import org.catrobat.catroid.content.localweb.WebViewEventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure-JVM diagnostics ring buffers ([RequestLog],
 * [WebViewEventLog]) that feed the in-app diagnostics screen: bounded
 * capacity (oldest dropped), newest-last order, clear, and copy formatting.
 */
class DiagnosticsLogTest {

    private fun entry(path: String, status: Int = 200) = RequestLogEntry(
        timestampMs = 1000L,
        method = "GET",
        path = path,
        status = status,
        mimeType = "text/html",
        bytesSent = 42,
        durationMs = 3,
        detail = null
    )

    @Test
    fun `request log keeps entries newest last`() {
        val log = RequestLog(capacity = 10)
        log.add(entry("/a"))
        log.add(entry("/b"))
        assertEquals(listOf("/a", "/b"), log.snapshot().map { it.path })
    }

    @Test
    fun `request log is bounded and drops the oldest entries`() {
        val log = RequestLog(capacity = 3)
        repeat(5) { log.add(entry("/f$it")) }
        assertEquals(listOf("/f2", "/f3", "/f4"), log.snapshot().map { it.path })
    }

    @Test
    fun `request log clear empties it`() {
        val log = RequestLog()
        log.add(entry("/a"))
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `request log copy format contains the request details`() {
        val log = RequestLog()
        log.add(
            RequestLogEntry(
                timestampMs = 1000L, method = "GET", path = "/play/app.js", status = 200,
                mimeType = "text/javascript", bytesSent = 1234, durationMs = 7,
                detail = "size mismatch: provider reported 2000, sent 1234 bytes"
            )
        )
        log.add(entry("/missing.js", status = 404))
        val text = log.formatForCopy()
        assertTrue(text.contains("GET /play/app.js -> 200"))
        assertTrue(text.contains("mime=text/javascript"))
        assertTrue(text.contains("bytes=1234"))
        assertTrue(text.contains("size mismatch"))
        assertTrue(text.contains("GET /missing.js -> 404"))
    }

    @Test
    fun `request log copy format handles empty log`() {
        val log = RequestLog()
        assertTrue(log.formatForCopy().contains("(none)"))
    }

    @Test
    fun `webview event log captures and formats events`() {
        val log = WebViewEventLog(capacity = 2)
        log.add(
            WebViewEvent(
                timestampMs = 1000L, kind = WebViewEvent.Kind.CONSOLE, level = "ERROR",
                message = "Uncaught TypeError: Cannot read properties of undefined",
                url = "http://localhost:8080/play/app.js", line = 42
            )
        )
        log.add(
            WebViewEvent(
                timestampMs = 2000L, kind = WebViewEvent.Kind.RESOURCE_ERROR, level = "WARN",
                message = "resource load failed: net::ERR_CONNECTION_REFUSED (code -7)",
                url = "http://localhost:8080/play/tex.png"
            )
        )
        log.add(
            WebViewEvent(
                timestampMs = 3000L, kind = WebViewEvent.Kind.HTTP_ERROR, level = "ERROR",
                message = "HTTP error response: 404", url = "http://localhost:8080/missing.js"
            )
        )
        // capacity 2: the first event was dropped
        val snapshot = log.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(WebViewEvent.Kind.HTTP_ERROR, snapshot[1].kind)
        val text = log.formatForCopy()
        assertTrue(text.contains("Uncaught TypeError") || text.contains("resource load failed"))
        assertTrue(text.contains("HTTP error response: 404"))
        assertTrue(text.contains("http://localhost:8080/missing.js"))
    }
}
