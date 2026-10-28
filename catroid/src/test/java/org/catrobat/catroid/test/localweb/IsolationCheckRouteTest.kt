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

import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.catrobat.catroid.content.localweb.RequestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the built-in cross-origin isolation check page
 * (`/__newcatroid_isolation_check`): a minimal diagnostic page served by the
 * server itself, with the standard response headers, that isolates WebView
 * cross-origin-isolation behavior from any hosted game's JavaScript. It
 * exists to answer, on a real device, whether crossOriginIsolated is false
 * because of the WebView (a known Android WebView limitation: no cross-origin
 * isolation regardless of headers) or because of the hosted page/navigation.
 *
 * Pure JVM (real HttpServerEngine over loopback).
 */
class IsolationCheckRouteTest {

    private var engine: HttpServerEngine? = null
    private var port = -1
    private lateinit var requestLog: RequestLog

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
    }

    private fun startServer(
        storeRoot: File? = null,
        urlPrefix: String = "/"
    ) {
        val root = storeRoot
        val storeProvider: () -> org.catrobat.catroid.content.localweb.DocumentStore? =
            { if (root != null) org.catrobat.catroid.content.localweb.FileDocumentStore(root) else null }
        requestLog = RequestLog()
        val newEngine = HttpServerEngine(
            storeProvider = storeProvider,
            urlPrefixProvider = { urlPrefix },
            requestLog = requestLog
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @Test
    fun `isolation check page is served with the standard isolation headers`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/__newcatroid_isolation_check")
        assertEquals(200, response.statusCode)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals("same-origin", response.headers["cross-origin-opener-policy"])
        assertEquals("require-corp", response.headers["cross-origin-embedder-policy"])
    }

    @Test
    fun `isolation check page reports the isolation and capability probes`() {
        startServer()
        val body = rawHttpRequest(port, "GET", "/__newcatroid_isolation_check").bodyText()
        assertTrue(body.contains("crossOriginIsolated"))
        assertTrue(body.contains("isSecureContext"))
        assertTrue(body.contains("SharedArrayBuffer"))
        assertTrue(body.contains("shared WebAssembly Memory64"))
        assertTrue(body.contains("OffscreenCanvas"))
        assertTrue(body.contains("AudioWorkletNode"))
        assertTrue(body.contains("navigator.storage.getDirectory"))
        assertTrue(body.contains("FileSystemSyncAccessHandle"))
        assertTrue(body.contains("WebGPU adapter"))
        assertTrue(body.contains("userAgent"))
        assertTrue(body.contains("cross-origin-opener-policy"))
        assertTrue(body.contains("cross-origin-embedder-policy"))
        assertTrue(body.contains("own HEAD"))
    }

    @Test
    fun `isolation check page is served without a hosted directory`() {
        // The route is a server-level diagnostic: it must work even when no
        // folder is hosted.
        startServer(storeRoot = null)
        val response = rawHttpRequest(port, "GET", "/__newcatroid_isolation_check")
        assertEquals(200, response.statusCode)
        // A normal path still answers 404 (no directory is being hosted).
        assertEquals(404, rawHttpRequest(port, "GET", "/index.html").statusCode)
    }

    @Test
    fun `isolation check page works under a url prefix`() {
        startServer(urlPrefix = "/game")
        val response = rawHttpRequest(port, "GET", "/game/__newcatroid_isolation_check")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("crossOriginIsolated"))
    }

    @Test
    fun `head request to the isolation check page returns headers without a body`() {
        startServer()
        val response = rawHttpRequest(port, "HEAD", "/__newcatroid_isolation_check")
        assertEquals(200, response.statusCode)
        assertEquals("same-origin", response.headers["cross-origin-opener-policy"])
        assertTrue(response.body.isEmpty())
    }

    @Test
    fun `the isolation check request is recorded in the request log`() {
        startServer()
        rawHttpRequest(port, "GET", "/__newcatroid_isolation_check")
        val entries = requestLog.snapshot()
        assertEquals(1, entries.size)
        assertEquals(200, entries[0].status)
        assertEquals("text/html; charset=utf-8", entries[0].mimeType)
        assertTrue(entries[0].detail!!.contains("isolation check"))
    }
}
