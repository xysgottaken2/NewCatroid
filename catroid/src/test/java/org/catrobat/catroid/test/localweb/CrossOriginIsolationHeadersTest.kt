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

import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Regression tests for cross-origin isolation response headers (COOP/COEP).
 *
 * Reported evidence: an external webpage showed "not cross-origin isolated:
 * open the page over https (or localhost); the server sends COOP/COEP" -
 * `crossOriginIsolated` requires the document to be served from a secure
 * context (https or localhost) AND to carry
 * `Cross-Origin-Opener-Policy: same-origin` and
 * `Cross-Origin-Embedder-Policy: require-corp`. The server now adds both
 * headers to every response it constructs (files, listings, redirects and
 * errors), exactly once, with an opt-out constructor flag.
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class CrossOriginIsolationHeadersTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("coop-coep-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(isolationHeaders: Boolean = true) {
        val store = FileDocumentStore(rootDir)
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { "/" },
            addCrossOriginIsolationHeaders = isolationHeaders
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    private fun write(relativePath: String, content: String) {
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun assertIsolationHeaders(response: RawHttpResponse) {
        assertEquals("same-origin", response.headers["cross-origin-opener-policy"])
        assertEquals("require-corp", response.headers["cross-origin-embedder-policy"])
    }

    private fun assertHeaderAppearsExactlyOnce(raw: ByteArray, header: String) {
        val text = String(raw, StandardCharsets.UTF_8)
        val occurrences = text.split(header, ignoreCase = true).size - 1
        assertEquals("header $header must appear exactly once", 1, occurrences)
    }

    @Test
    fun `html javascript and wasm responses carry coop and coep headers`() {
        write("index.html", "<html></html>")
        write("app.js", "console.log('x');")
        write("assets/game.wasm", "\u0000asm\u0001\u0000\u0000\u0000")
        startServer()

        assertIsolationHeaders(rawHttpRequest(port, "GET", "/index.html"))
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/app.js"))
        val wasm = rawHttpRequest(port, "GET", "/assets/game.wasm")
        assertIsolationHeaders(wasm)
        assertEquals("application/wasm", wasm.headers["content-type"])
    }

    @Test
    fun `headers are preserved for deeply nested files`() {
        write("a/b/c/d/style.css", "body {}")
        startServer()
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/a/b/c/d/style.css"))
    }

    @Test
    fun `range responses carry the headers`() {
        write("big.bin", "0123456789")
        startServer()
        val response = rawHttpRequest(port, "GET", "/big.bin", mapOf("Range" to "bytes=0-3"))
        assertEquals(206, response.statusCode)
        assertIsolationHeaders(response)
    }

    @Test
    fun `redirects carry the headers`() {
        write("play/index.html", "<html></html>")
        startServer()
        // 301 trailing-slash redirect
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/play"))
        // 302 entry-point redirect (no default document at the root)
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/"))
    }

    @Test
    fun `error responses carry the headers`() {
        write("play/index.html", "<html></html>")
        startServer()
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/missing.js"))       // 404
        assertIsolationHeaders(rawHttpRequest(port, "GET", "/../escape"))       // 403
        assertIsolationHeaders(rawHttpRequest(port, "POST", "/index.html"))     // 405
    }

    @Test
    fun `directory listings carry the headers`() {
        write("docs/readme.txt", "hi")
        write("docs/guide.txt", "hi")
        startServer()
        val listing = rawHttpRequest(port, "GET", "/docs/")
        assertEquals(200, listing.statusCode)
        assertIsolationHeaders(listing)
    }

    @Test
    fun `headers are not duplicated`() {
        write("index.html", "<html></html>")
        startServer()
        val raw = rawHttpRequestBytes(port, "GET", "/index.html")
        assertHeaderAppearsExactlyOnce(raw, "Cross-Origin-Opener-Policy")
        assertHeaderAppearsExactlyOnce(raw, "Cross-Origin-Embedder-Policy")
        // HEAD responses carry them too, exactly once.
        val headRaw = rawHttpRequestBytes(port, "HEAD", "/index.html")
        assertHeaderAppearsExactlyOnce(headRaw, "Cross-Origin-Opener-Policy")
        assertHeaderAppearsExactlyOnce(headRaw, "Cross-Origin-Embedder-Policy")
    }

    @Test
    fun `headers can be disabled via the constructor flag`() {
        write("index.html", "<html></html>")
        startServer(isolationHeaders = false)
        val response = rawHttpRequest(port, "GET", "/index.html")
        assertEquals(200, response.statusCode)
        assertFalse(response.headers.containsKey("cross-origin-opener-policy"))
        assertFalse(response.headers.containsKey("cross-origin-embedder-policy"))
        // The response itself is unchanged otherwise.
        assertEquals("<html></html>", response.bodyText())
    }

    @Test
    fun `existing serving behavior is unchanged when headers are enabled`() {
        write("index.html", "<html>entry</html>")
        write("assets/app.js", "console.log('asset');")
        startServer()
        val index = rawHttpRequest(port, "GET", "/")
        assertEquals(200, index.statusCode)
        assertEquals("<html>entry</html>", index.bodyText())
        assertTrue(index.headers["content-type"]!!.startsWith("text/html"))
        val asset = rawHttpRequest(port, "GET", "/assets/app.js")
        assertEquals(200, asset.statusCode)
        assertEquals("console.log('asset');", asset.bodyText())
        assertEquals("text/javascript; charset=utf-8", asset.headers["content-type"])
    }
}
