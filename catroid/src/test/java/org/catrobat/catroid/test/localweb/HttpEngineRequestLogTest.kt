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

import org.catrobat.catroid.content.localweb.DocumentEntry
import org.catrobat.catroid.content.localweb.DocumentStore
import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.catrobat.catroid.content.localweb.RequestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Tests that the HTTP engine records one structured diagnostics entry per
 * request it handles (status, MIME type, bytes sent, duration, detail),
 * covering successes, redirects, listings, errors and aborted responses - the
 * data shown on the in-app diagnostics screen and used to pinpoint a hosted
 * application that gets stuck loading.
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class HttpEngineRequestLogTest {

    private lateinit var rootDir: File
    private lateinit var requestLog: RequestLog
    private var engine: HttpServerEngine? = null
    private var port = -1

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("request-log-test").toFile()
        requestLog = RequestLog(capacity = 100)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(store: DocumentStore = FileDocumentStore(rootDir)) {
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { "/" },
            requestLog = requestLog
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

    private fun write(relativePath: String, content: ByteArray) {
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    private fun lastEntry() = requestLog.snapshot().last()

    @Test
    fun `successful file request is recorded with status mime and bytes`() {
        write("app.js", "console.log('x');")
        startServer()
        val response = rawHttpRequest(port, "GET", "/app.js")
        assertEquals(200, response.statusCode)

        val entry = lastEntry()
        assertEquals("GET", entry.method)
        assertEquals("/app.js", entry.path)
        assertEquals(200, entry.status)
        assertEquals("text/javascript; charset=utf-8", entry.mimeType)
        assertEquals("console.log('x');".length.toLong(), entry.bytesSent)
        assertTrue(entry.durationMs >= 0)
    }

    @Test
    fun `not found request is recorded with the failure detail`() {
        write("index.html", "<html></html>")
        startServer()
        rawHttpRequest(port, "GET", "/missing.js")
        val entry = lastEntry()
        assertEquals(404, entry.status)
        assertNotNull(entry.detail)
        assertTrue(entry.detail!!.contains("document not resolved"))
    }

    @Test
    fun `traversal rejection is recorded with the failure detail`() {
        write("index.html", "<html></html>")
        startServer()
        rawHttpRequest(port, "GET", "/../escape")
        val entry = lastEntry()
        assertEquals(403, entry.status)
        assertTrue(entry.detail!!.contains("escaping"))
    }

    @Test
    fun `redirects are recorded`() {
        write("play/index.html", "<html></html>")
        startServer()
        rawHttpRequest(port, "GET", "/play")
        assertEquals(301, lastEntry().status)
        assertTrue(lastEntry().detail!!.contains("slash redirect"))

        File(rootDir, "index.html").delete()
        write("game.html", "<html>game</html>")
        rawHttpRequest(port, "GET", "/")
        assertEquals(302, lastEntry().status)
        assertTrue(lastEntry().detail!!.contains("entry point redirect"))
    }

    @Test
    fun `directory listing is recorded`() {
        write("docs/a.txt", "a")
        write("docs/b.txt", "b")
        startServer()
        rawHttpRequest(port, "GET", "/docs/")
        val entry = lastEntry()
        assertEquals(200, entry.status)
        assertTrue(entry.detail!!.contains("directory listing"))
    }

    @Test
    fun `range request is recorded with bytes and range detail`() {
        write("big.bin", "0123456789")
        startServer()
        rawHttpRequest(port, "GET", "/big.bin", mapOf("Range" to "bytes=0-3"))
        val entry = lastEntry()
        assertEquals(206, entry.status)
        assertEquals(4, entry.bytesSent)
        assertTrue(entry.detail!!.contains("range"))
    }

    @Test
    fun `a response aborted mid stream is recorded with partial bytes`() {
        // Larger than the buffer threshold so the response is streamed; the
        // stream ends early, so the engine aborts the started response.
        val full = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        write("big.js", full)
        val fileStore = FileDocumentStore(rootDir)
        val failing = object : DocumentStore by fileStore {
            override fun openInput(entry: DocumentEntry): InputStream? {
                if (entry.name != "big.js") return fileStore.openInput(entry)
                return object : InputStream() {
                    var delivered = 0
                    val limit = full.size / 2
                    override fun read(): Int = if (delivered < limit) full[delivered++].toInt() else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (delivered >= limit) return -1
                        val n = minOf(len, limit - delivered)
                        System.arraycopy(full, delivered, b, off, n)
                        delivered += n
                        return n
                    }
                }
            }
        }
        startServer(failing)
        rawHttpRequestBytes(port, "GET", "/big.js")

        val entry = lastEntry()
        assertEquals(200, entry.status)
        assertEquals((full.size / 2).toLong(), entry.bytesSent)
        assertTrue(entry.detail!!.contains("aborted mid-stream"))
        assertTrue(entry.detail!!.contains("stream ended early"))
    }

    @Test
    fun `every request produces exactly one log entry`() {
        write("index.html", "<html></html>")
        write("app.js", "console.log('x');")
        startServer()
        rawHttpRequest(port, "GET", "/index.html")
        rawHttpRequest(port, "GET", "/app.js")
        rawHttpRequest(port, "GET", "/missing.js")
        assertEquals(3, requestLog.snapshot().size)
    }
}
