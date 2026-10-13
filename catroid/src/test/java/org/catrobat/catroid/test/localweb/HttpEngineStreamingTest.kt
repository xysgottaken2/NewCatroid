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
import org.catrobat.catroid.content.localweb.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression tests for protocol-correct file streaming - the reported problem:
 * the hosted application got stuck on an infinite loading screen although the
 * entry HTML opened. Root cause found in the code: `HttpEngine.handleFile`
 * wrote the 200 status line and `Content-Length` headers BEFORE opening the
 * document stream, so a document that could not be opened (or a stream that
 * ended early) made the engine append a second "500" response to the already
 * started one - the browser received a corrupted body (a script or WASM
 * module delivered as error text / truncated bytes), the application never
 * finished initializing and its loading screen spun forever.
 *
 * These tests exercise the actual serving pipeline (real HttpServerEngine
 * over loopback) with failure-injecting DocumentStore wrappers:
 *
 * - a document that cannot be opened produces a clean error response, never
 *   a 200 response followed by a second response;
 * - a stream that fails mid-read aborts the connection with a truncated
 *   body that is a strict prefix of the file - never corrupted with an
 *   appended error response;
 * - wrong provider-reported sizes are corrected for buffered (small) files:
 *   Content-Length always matches the bytes actually sent;
 * - unknown sizes stream with chunked encoding;
 * - binary files (all byte values, WASM) are served byte-for-byte;
 * - large files stream completely and byte-for-byte;
 * - Range requests return exact byte slices (buffered and streamed);
 * - concurrent requests all deliver intact bytes;
 * - per-request diagnostics are logged (method, path, status, sizes,
 *   failure reasons) without logging file contents.
 *
 * Pure JVM (FileDocumentStore over real temp files + the real engine).
 */
class HttpEngineStreamingTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1
    private val logLines = mutableListOf<String>()
    private val logLock = Any()

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("streaming-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(store: DocumentStore) {
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { "/" },
            logger = { level, message, _ ->
                synchronized(logLock) { logLines.add("$level $message") }
            }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    private fun write(relativePath: String, content: ByteArray) {
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    private fun write(relativePath: String, content: String) = write(relativePath, content.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun loggedLines(): List<String> = synchronized(logLock) { logLines.toList() }

    // ---------- a document that cannot be opened ----------

    @Test
    fun `a document that cannot be opened produces a clean error response`() {
        write("app.js", "console.log('real');")
        val fileStore = FileDocumentStore(rootDir)
        val failing = object : DocumentStore by fileStore {
            override fun openInput(entry: DocumentEntry): InputStream? =
                if (entry.name == "app.js") null else fileStore.openInput(entry)
        }
        startServer(failing)

        val raw = rawHttpRequestBytes(port, "GET", "/app.js")
        val text = String(raw, StandardCharsets.UTF_8)
        // Exactly one status line: a clean 404, not a 200 followed by a 500.
        assertTrue("response must start with a single status line, was: ${text.take(60)}", text.startsWith("HTTP/1.1 404"))
        assertEquals("only one status line may appear", 1, text.split("HTTP/1.1").size - 1)
        val response = rawHttpRequest(port, "GET", "/app.js")
        assertEquals(404, response.statusCode)
        assertTrue(response.bodyText().contains("could not be opened"))
        // Diagnostics distinguish "cannot be opened" from "cannot be resolved".
        assertTrue(loggedLines().any { it.contains("WARN") && it.contains("could not be opened") })
    }

    // ---------- a stream that fails mid-read ----------

    @Test
    fun `a stream failing mid read aborts cleanly without corrupting the response`() {
        // Larger than the small-file buffer threshold, so the response is
        // streamed (not buffered) and the early end of the stream aborts it.
        val full = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        write("big.js", full)
        val fileStore = FileDocumentStore(rootDir)
        val failing = object : DocumentStore by fileStore {
            override fun openInput(entry: DocumentEntry): InputStream? {
                if (entry.name != "big.js") return fileStore.openInput(entry)
                // Delivers only half the bytes, then ends the stream early.
                return object : InputStream() {
                    var delivered = 0
                    override fun read(): Int = if (delivered < full.size / 2) full[delivered++].toInt() else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (delivered >= full.size / 2) return -1
                        val n = minOf(len, full.size / 2 - delivered)
                        System.arraycopy(full, delivered, b, off, n)
                        delivered += n
                        return n
                    }
                }
            }
        }
        startServer(failing)

        val raw = rawHttpRequestBytes(port, "GET", "/big.js")
        val text = String(raw, StandardCharsets.UTF_8)
        // The response started with 200 (headers were legitimately sent), but
        // nothing may be appended after the truncated body.
        assertTrue(text.startsWith("HTTP/1.1 200"))
        assertFalse("no second response may be appended", text.contains("HTTP/1.1 500"))
        assertFalse("no error text may be appended", text.contains("Internal Server Error"))
        // The received body is a strict prefix of the original file.
        val bodyStart = text.indexOf("\r\n\r\n") + 4
        val body = raw.copyOfRange(bodyStart, raw.size)
        assertEquals(full.size / 2, body.size)
        assertTrue(full.copyOfRange(0, full.size / 2).contentEquals(body))
        // The abort is logged as a mid-stream failure.
        assertTrue(loggedLines().any { it.contains("ERROR") && it.contains("aborted mid-stream") })
    }

    // ---------- wrong provider-reported sizes ----------

    @Test
    fun `a reported size larger than the file is corrected by buffering`() {
        write("small.js", "console.log('small');")
        val fileStore = FileDocumentStore(rootDir)
        val wrongSize = object : DocumentStore by fileStore {
            override fun resolve(relativePath: String): DocumentEntry? =
                fileStore.resolve(relativePath)?.let { if (it.isDirectory) it else it.copy(size = it.size + 1_000_000) }
        }
        startServer(wrongSize)
        val response = rawHttpRequest(port, "GET", "/small.js")
        assertEquals(200, response.statusCode)
        val actual = "console.log('small');".toByteArray(StandardCharsets.UTF_8)
        assertEquals(actual.size.toString(), response.headers["content-length"])
        assertTrue(actual.contentEquals(response.body))
    }

    @Test
    fun `a reported size smaller than the file is corrected by buffering`() {
        write("small.js", "console.log('small');")
        val fileStore = FileDocumentStore(rootDir)
        val wrongSize = object : DocumentStore by fileStore {
            override fun resolve(relativePath: String): DocumentEntry? =
                fileStore.resolve(relativePath)?.let { if (it.isDirectory) it else it.copy(size = 3) }
        }
        startServer(wrongSize)
        val response = rawHttpRequest(port, "GET", "/small.js")
        assertEquals(200, response.statusCode)
        val actual = "console.log('small');".toByteArray(StandardCharsets.UTF_8)
        assertEquals(actual.size.toString(), response.headers["content-length"])
        assertTrue(actual.contentEquals(response.body))
    }

    // ---------- unknown size -> chunked ----------

    @Test
    fun `an unknown size streams with chunked encoding and intact bytes`() {
        val content = "console.log('chunked');".toByteArray(StandardCharsets.UTF_8)
        write("chunked.js", content)
        val fileStore = FileDocumentStore(rootDir)
        val unknownSize = object : DocumentStore by fileStore {
            override fun resolve(relativePath: String): DocumentEntry? =
                fileStore.resolve(relativePath)?.let { if (it.isDirectory) it else it.copy(size = -1) }
        }
        startServer(unknownSize)
        val response = rawHttpRequest(port, "GET", "/chunked.js")
        assertEquals(200, response.statusCode)
        assertEquals("chunked", response.headers["transfer-encoding"])
        assertTrue(content.contentEquals(decodeChunked(response.body)))
    }

    private fun decodeChunked(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        while (pos < body.size) {
            val lineEnd = body.indexOf("\r\n".toByteArray(), pos)
            val size = String(body, pos, lineEnd - pos, StandardCharsets.US_ASCII).toInt(16)
            if (size == 0) break
            out.write(body, lineEnd + 2, size)
            pos = lineEnd + 2 + size + 2
        }
        return out.toByteArray()
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        outer@ for (i in from..this.size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---------- binary integrity ----------

    @Test
    fun `binary files are served byte for byte`() {
        // All 256 byte values repeated, plus a WASM magic header - the exact
        // corruption class that breaks scripts, textures and WASM modules.
        val wasmLike = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) +
            ByteArray(4096) { (it % 256).toByte() } +
            ByteArray(1000) { 0xFF.toByte() } +
            ByteArray(1000) { 0x00 }
        write("assets/game.wasm", wasmLike)
        write("assets/texture.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(500) { (it * 7 % 256).toByte() })
        startServer(FileDocumentStore(rootDir))

        val wasm = rawHttpRequest(port, "GET", "/assets/game.wasm")
        assertEquals(200, wasm.statusCode)
        assertEquals("application/wasm", wasm.headers["content-type"])
        assertTrue("wasm bytes must be identical", wasmLike.contentEquals(wasm.body))
        assertEquals(sha256(wasmLike), sha256(wasm.body))

        val png = rawHttpRequest(port, "GET", "/assets/texture.png")
        assertEquals(200, png.statusCode)
        assertEquals("image/png", png.headers["content-type"])
        assertTrue(png.body.contentEquals(rawHttpRequest(port, "GET", "/assets/texture.png").body))
    }

    @Test
    fun `a large file streams completely and byte for byte`() {
        val large = ByteArray(5 * 1024 * 1024) { (it * 31 % 251).toByte() }
        write("data/big.bin", large)
        startServer(FileDocumentStore(rootDir))
        val response = rawHttpRequest(port, "GET", "/data/big.bin")
        assertEquals(200, response.statusCode)
        assertEquals(large.size.toString(), response.headers["content-length"])
        assertTrue("large file must stream completely and intact", large.contentEquals(response.body))
        assertEquals(sha256(large), sha256(response.body))
    }

    // ---------- ranges ----------

    @Test
    fun `range requests return exact byte slices for buffered and streamed files`() {
        val small = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray(StandardCharsets.UTF_8)
        write("small.txt", small)
        val large = ByteArray(2 * 1024 * 1024) { (it % 253).toByte() }
        write("large.bin", large)
        startServer(FileDocumentStore(rootDir))

        val smallRange = rawHttpRequest(port, "GET", "/small.txt", mapOf("Range" to "bytes=10-19"))
        assertEquals(206, smallRange.statusCode)
        assertEquals("bytes 10-19/${small.size}", smallRange.headers["content-range"])
        assertTrue(small.copyOfRange(10, 20).contentEquals(smallRange.body))

        val largeRange = rawHttpRequest(port, "GET", "/large.bin", mapOf("Range" to "bytes=100000-100099"))
        assertEquals(206, largeRange.statusCode)
        assertEquals("bytes 100000-100099/${large.size}", largeRange.headers["content-range"])
        assertTrue(large.copyOfRange(100000, 100100).contentEquals(largeRange.body))
    }

    // ---------- concurrency ----------

    @Test
    fun `concurrent requests all deliver intact bytes`() {
        val files = (0 until 8).map { index ->
            val content = ByteArray(50_000 + index * 1000) { (it + index % 256).toByte() }
            write("files/file$index.js", content)
            "file$index.js" to content
        }.toMap()
        startServer(FileDocumentStore(rootDir))

        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(files.size)
        val failures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
        for ((name, content) in files) {
            pool.execute {
                try {
                    val response = rawHttpRequest(port, "GET", "/files/$name")
                    if (response.statusCode != 200 || !content.contentEquals(response.body)) {
                        failures[name] = AssertionError("status ${response.statusCode}, bytes ${response.body.size}/${content.size}")
                    }
                } catch (e: Exception) {
                    failures[name] = e
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("all requests complete", latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("concurrent requests failed: $failures", failures.isEmpty())
    }

    // ---------- diagnostics ----------

    @Test
    fun `every request is logged with method path status and size`() {
        write("app.js", "console.log('x');")
        write("missing.js", "")
        File(rootDir, "missing.js").delete()
        startServer(FileDocumentStore(rootDir))

        rawHttpRequest(port, "GET", "/app.js")
        rawHttpRequest(port, "GET", "/missing.js")

        val lines = loggedLines()
        assertTrue("success logged", lines.any { it.startsWith("INFO") && it.contains("GET /app.js -> 200") && it.contains("bytes") })
        assertTrue("resolve miss logged", lines.any { it.contains("GET /missing.js -> 404") })
        assertFalse("file contents are never logged", lines.any { it.contains("console.log") })
    }

    @Test
    fun `head requests report the actual content length without a body`() {
        val content = "console.log('head');".toByteArray(StandardCharsets.UTF_8)
        write("head.js", content)
        startServer(FileDocumentStore(rootDir))
        val response = rawHttpRequest(port, "HEAD", "/head.js")
        assertEquals(200, response.statusCode)
        assertEquals(content.size.toString(), response.headers["content-length"])
        assertTrue(response.body.isEmpty())
    }
}
